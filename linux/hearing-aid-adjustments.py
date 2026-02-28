import logging
import json
import os
import hashlib
from pathlib import Path
import signal
import socket
import struct
import sys
import threading
import time
from queue import Queue
from threading import Thread
from typing import Any, Dict, List, Optional, Tuple

from PyQt5.QtWidgets import (
    QApplication,
    QWidget,
    QVBoxLayout,
    QHBoxLayout,
    QLabel,
    QSlider,
    QCheckBox,
    QPushButton,
    QLineEdit,
    QGridLayout,
)
from PyQt5.QtCore import Qt, QTimer, pyqtSignal, QObject

# -----------------------------------------------------------------------------
# Based on LibrePods' linux/hearing-aid-adjustments.py (main branch).
# This version improves robustness on Linux/BlueZ:
# - Proper handling of ATT Indications (0x1D) + Confirmation (0x1E)
# - CCCD enables indications+notifications by default (0x0003) with fallback
# - Correct timeout handling (socket.timeout), and no recursive reconnect loop
# - Transaction lock so reads/writes don't get mixed with async PDUs
# - Optional read-back/echo validation after writing values
# -----------------------------------------------------------------------------

logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s - %(levelname)s - %(message)s",
)

# --- ATT opcodes ---
OPCODE_ERROR_RSP: int = 0x01
OPCODE_READ_REQUEST: int = 0x0A
OPCODE_READ_RESPONSE: int = 0x0B
OPCODE_WRITE_REQUEST: int = 0x12
OPCODE_WRITE_RESPONSE: int = 0x13
OPCODE_HANDLE_VALUE_NTF: int = 0x1B
OPCODE_HANDLE_VALUE_IND: int = 0x1D
OPCODE_HANDLE_VALUE_CFM: int = 0x1E

# CCCD values (little-endian)
CCCD_NOTIFY = b"\x01\x00"
CCCD_INDICATE = b"\x02\x00"
CCCD_BOTH = b"\x03\x00"

ATT_HANDLES: Dict[str, int] = {
    "TRANSPARENCY": 0x18,
    "LOUD_SOUND_REDUCTION": 0x1B,
    "HEARING_AID": 0x2A,
}

ATT_CCCD_HANDLES: Dict[str, int] = {
    "TRANSPARENCY": ATT_HANDLES["TRANSPARENCY"] + 1,
    "LOUD_SOUND_REDUCTION": ATT_HANDLES["LOUD_SOUND_REDUCTION"] + 1,
    "HEARING_AID": ATT_HANDLES["HEARING_AID"] + 1,
}

PSM_ATT: int = 31  # L2CAP ATT fixed channel
PSM_AACP: int = 0x1001  # AirPods AACP control channel (L2CAP)

# AACP constants (AACP control commands over L2CAP)
AACP_HEADER = bytes([0x04, 0x00, 0x04, 0x00])
AACP_OPCODE_CONTROL = 0x09
AACP_HANDSHAKE = bytes.fromhex("00000400010002000000000000000000")
AACP_SET_SPECIFIC_FEATURES = bytes.fromhex("040004004d00d700000000000000")
AACP_REQUEST_NOTIFICATIONS = bytes.fromhex("040004000f00ffffffffff")
AACP_HANDSHAKE_ACK = bytes.fromhex("01000400")
AACP_FEATURES_ACK = bytes.fromhex("040004002b00")
# Seen in LibrePods Android startup path; likely needed to unlock EQ write path.
AACP_ENABLE_EQ_WRITE_PATH = AACP_HEADER + bytes([0x29, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF])

# Headphone Accommodation (AACP 0x53) header: 04 00 04 00 53 00 84 00 02 00 [Phone] [Media] ...
AACP_HEADPHONE_ACCOMMODATION_PREFIX = bytes.fromhex("04000400530084000200")
HEADPHONE_ACCOMMODATION_PAYLOAD_LEN = 0x84  # 132 bytes following 0x53 00

HEARING_ASSIST_HEADER = bytes.fromhex("52 2A 00 02 02 64 00")
HEARING_ASSIST_TRAILER = bytes.fromhex("00 00 00 3F")  # float 0.5
HEARING_ASSIST_FREQS = [250, 500, 1000, 2000, 3000, 4000, 6000, 8000]
EXPECTED_BLUETOOTH_DID = "bluetooth:004C:0000:0000"
SCRIPT_REVISION = "2026-02-20-r1"

class ATTProtocolError(RuntimeError):
    pass

def _u16le(n: int) -> bytes:
    return bytes([n & 0xFF, (n >> 8) & 0xFF])

def _parse_error_rsp(pdu: bytes) -> Tuple[int, int, int]:
    # Error Response: 0x01 | req_opcode(1) | handle(2) | error_code(1)
    if len(pdu) < 5:
        return (0, 0, 0)
    req = pdu[1]
    handle = pdu[2] | (pdu[3] << 8)
    err = pdu[4]
    return (req, handle, err)

class ATTManager:
    def __init__(self, mac_address: str) -> None:
        self.mac_address: str = mac_address
        self.sock: Optional[socket.socket] = None

        self.responses: "Queue[bytes]" = Queue()
        self.listeners: Dict[int, List[Any]] = {}
        self.notification_thread: Optional[Thread] = None
        self.running: bool = False

        # Serialize request/response so async PDUs don't interleave with commands
        self.tx_lock = threading.Lock()

        # Store last async updates per handle (from notification/indication)
        self.last_values: Dict[int, bytes] = {}
        self.last_written: Dict[int, bytes] = {}
        self._handle_events: Dict[int, threading.Event] = {}

        logging.info("ATTManager initialized")

    def connect(self, retries: int = 5, retry_delay_s: float = 0.8) -> None:
        logging.info("Attempting to connect to ATT socket (PSM %d)", PSM_ATT)
        last_error: Optional[Exception] = None
        for attempt in range(1, retries + 1):
            self.sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
            try:
                # This requires an active BlueZ connection to the target device.
                self.sock.connect((self.mac_address, PSM_ATT))
                self.sock.settimeout(0.5)
                self.running = True
                self.notification_thread = Thread(target=self._listen_loop, name="att-listener", daemon=True)
                self.notification_thread.start()
                logging.info("Connected to ATT socket")
                return
            except OSError as e:
                last_error = e
                try:
                    self.sock.close()
                except Exception:
                    pass
                self.sock = None
                if e.errno in (111, 112, 113) and attempt < retries:
                    logging.warning(
                        "ATT connect attempt %d/%d failed with errno=%s; retrying in %.1fs",
                        attempt,
                        retries,
                        e.errno,
                        retry_delay_s,
                    )
                    time.sleep(retry_delay_s)
                    continue
                raise
        if last_error:
            raise last_error

    def disconnect(self) -> None:
        logging.info("Disconnecting from ATT socket")
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
        if self.notification_thread:
            self.notification_thread.join(timeout=1.0)
        self.sock = None
        logging.info("Disconnected")

    def register_listener(self, handle: int, listener: Any) -> None:
        self.listeners.setdefault(handle, []).append(listener)

    def enable_notifications(self, handle: Any, mode: str = "both") -> None:
        # AirPods often use indications; enabling BOTH avoids silent failures.
        if mode == "notify":
            val = CCCD_NOTIFY
        elif mode == "indicate":
            val = CCCD_INDICATE
        else:
            val = CCCD_BOTH

        try:
            self.write_cccd(handle, val)
        except Exception as e:
            # Fallback: some stacks reject 0x0003; try 0x0001.
            logging.warning("CCCD(%s) failed (%s). Falling back to notifications only.", mode, e)
            self.write_cccd(handle, CCCD_NOTIFY)

        logging.info("Enabled CCCD for %s (mode=%s)", handle.name, mode)

    def read(self, handle: Any, timeout: float = 2.0) -> bytes:
        handle_value: int = ATT_HANDLES[handle.name]
        pdu: bytes = bytes([OPCODE_READ_REQUEST]) + _u16le(handle_value)
        with self.tx_lock:
            self._write_raw(pdu)
            rsp = self._read_response_pdu(timeout=timeout)
        if not rsp:
            raise ATTProtocolError("Empty response")
        if rsp[0] == OPCODE_ERROR_RSP:
            req, h, err = _parse_error_rsp(rsp)
            raise ATTProtocolError(f"ATT Error (req=0x{req:02x} handle=0x{h:04x} err=0x{err:02x})")
        if rsp[0] != OPCODE_READ_RESPONSE:
            raise ATTProtocolError(f"Unexpected opcode 0x{rsp[0]:02x} for read response")
        value = rsp[1:]
        logging.debug("Read %s (%d bytes): %s", handle.name, len(value), value.hex())
        return value

    def write(self, handle: Any, value: bytes, timeout: float = 2.0) -> None:
        handle_value: int = ATT_HANDLES[handle.name]
        pdu: bytes = bytes([OPCODE_WRITE_REQUEST]) + _u16le(handle_value) + value
        with self.tx_lock:
            self._write_raw(pdu)
            rsp = self._read_response_pdu(timeout=timeout)
        if not rsp:
            raise ATTProtocolError("No write response")
        if rsp[0] == OPCODE_ERROR_RSP:
            req, h, err = _parse_error_rsp(rsp)
            raise ATTProtocolError(f"ATT Error (req=0x{req:02x} handle=0x{h:04x} err=0x{err:02x})")
        if rsp[0] != OPCODE_WRITE_RESPONSE:
            raise ATTProtocolError(f"Unexpected opcode 0x{rsp[0]:02x} for write response")
        logging.debug("Write ACK for %s", handle.name)

    def write_cccd(self, handle: Any, value: bytes, timeout: float = 2.0) -> None:
        handle_value: int = ATT_CCCD_HANDLES[handle.name]
        pdu: bytes = bytes([OPCODE_WRITE_REQUEST]) + _u16le(handle_value) + value
        with self.tx_lock:
            self._write_raw(pdu)
            rsp = self._read_response_pdu(timeout=timeout)
        if not rsp:
            raise ATTProtocolError("No CCCD write response")
        if rsp[0] == OPCODE_ERROR_RSP:
            req, h, err = _parse_error_rsp(rsp)
            raise ATTProtocolError(f"ATT Error (req=0x{req:02x} handle=0x{h:04x} err=0x{err:02x})")
        if rsp[0] != OPCODE_WRITE_RESPONSE:
            raise ATTProtocolError(f"Unexpected opcode 0x{rsp[0]:02x} for CCCD write response")
        logging.debug("CCCD write ACK for %s", handle.name)

    def wait_for_update(self, handle_value: int, timeout: float = 1.0) -> Optional[bytes]:
        evt = self._handle_events.setdefault(handle_value, threading.Event())
        evt.clear()
        ok = evt.wait(timeout=timeout)
        return self.last_values.get(handle_value) if ok else None

    # ---------------- private ----------------

    def _write_raw(self, pdu: bytes) -> None:
        if not self.sock:
            raise RuntimeError("Socket not connected")
        self.sock.send(pdu)
        logging.debug("Sent PDU: %s %s", _describe_att_pdu(pdu), pdu.hex())

    def _recv_pdu(self) -> Optional[bytes]:
        if not self.sock:
            return None
        try:
            data = self.sock.recv(512)
            if data:
                logging.debug("Received PDU: %s %s", _describe_att_pdu(data), data.hex())
            return data
        except (socket.timeout, TimeoutError):
            return None
        except OSError:
            if not self.running:
                return None
            raise

    def _read_response_pdu(self, timeout: float = 2.0) -> bytes:
        try:
            return self.responses.get(timeout=timeout)
        except Exception as e:
            raise ATTProtocolError(f"No response received within {timeout}s") from e

    def _handle_value_update(self, handle: int, value: bytes) -> None:
        self.last_values[handle] = value
        if handle in self.last_written and value != self.last_written[handle]:
            if _looks_like_blank_hearing_assist(value):
                logging.warning("Device reverted / host overwritten for handle 0x%04x (blank/template update).", handle)
        if handle in self.listeners:
            for listener in list(self.listeners[handle]):
                try:
                    listener(value)
                except Exception:
                    logging.exception("Listener failure for handle 0x%04x", handle)
        evt = self._handle_events.setdefault(handle, threading.Event())
        evt.set()

    def _listen_loop(self) -> None:
        logging.info("Starting ATT listener thread")
        while self.running:
            try:
                pdu = self._recv_pdu()
            except Exception:
                if self.running:
                    logging.exception("ATT recv failed")
                break
            if not pdu:
                continue

            op = pdu[0]
            if op in (OPCODE_HANDLE_VALUE_NTF, OPCODE_HANDLE_VALUE_IND):
                if len(pdu) < 3:
                    continue
                handle = pdu[1] | (pdu[2] << 8)
                value = pdu[3:]
                logging.debug("Async value (op=0x%02x handle=0x%04x) len=%d", op, handle, len(value))
                self._handle_value_update(handle, value)

                # Indications must be acknowledged with Handle Value Confirmation (0x1E).
                if op == OPCODE_HANDLE_VALUE_IND:
                    try:
                        self._write_raw(bytes([OPCODE_HANDLE_VALUE_CFM]))
                        logging.debug("Sent indication confirmation (0x1E)")
                    except Exception:
                        logging.exception("Failed to send indication confirmation")
            else:
                # Response PDUs (read/write/error, etc.)
                self.responses.put(pdu)

        logging.info("ATT listener thread exited")


class AACPManager:
    def __init__(self, mac_address: str) -> None:
        self.mac_address = mac_address
        self.sock: Optional[socket.socket] = None
        self.running = False
        self.listener_thread: Optional[Thread] = None
        self.tx_lock = threading.Lock()
        self.rx_lock = threading.Lock()
        self.rx_history: List[Tuple[float, bytes]] = []
        self.last_packet_by_opcode: Dict[int, bytes] = {}
        self.control_status: Dict[int, bytes] = {}
        self.control_status_events: Dict[int, threading.Event] = {}
        self._handshake_sent = False
        self._features_sent = False
        self._notifications_sent = False

    def connect(self) -> None:
        if self.sock:
            return
        logging.info("Connecting to AACP L2CAP socket (PSM 0x%04x)", PSM_AACP)
        self.sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
        self.sock.connect((self.mac_address, PSM_AACP))
        self.sock.settimeout(0.5)
        self.running = True
        self._handshake_sent = False
        self._features_sent = False
        self._notifications_sent = False
        self.listener_thread = Thread(target=self._listen_loop, name="aacp-listener", daemon=True)
        self.listener_thread.start()
        # Start AACP handshake sequence to avoid disconnects on control commands.
        self._send_handshake()
        logging.info("AACP connected")

    def disconnect(self) -> None:
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
        if self.listener_thread:
            self.listener_thread.join(timeout=1.0)
        self.sock = None
        self._handshake_sent = False
        self._features_sent = False
        self._notifications_sent = False
        logging.info("AACP disconnected")

    def ensure_connected(self) -> None:
        if self.sock:
            return
        self.connect()

    def send_control_command(self, identifier: int, data: bytes) -> None:
        # Control command packet: 04 00 04 00 09 00 <id> <d0> <d1> <d2> <d3>
        payload = bytearray(7)
        payload[0] = AACP_OPCODE_CONTROL
        payload[1] = 0x00
        payload[2] = identifier & 0xFF
        for i in range(4):
            payload[3 + i] = data[i] if i < len(data) else 0x00
        packet = AACP_HEADER + payload
        self._write_raw(packet)

    def send_packet(self, packet: bytes) -> None:
        self._write_raw(packet)

    def send_data_payload(self, payload: bytes) -> None:
        # AACP data packet prefix (04 00 04 00) + payload
        self._write_raw(AACP_HEADER + payload)

    def packets_since(self, monotonic_ts: float) -> List[bytes]:
        with self.rx_lock:
            return [pkt for ts, pkt in self.rx_history if ts >= monotonic_ts]

    def get_last_packet_by_opcode(self, opcode: int) -> Optional[bytes]:
        with self.rx_lock:
            pkt = self.last_packet_by_opcode.get(opcode)
            return bytes(pkt) if pkt is not None else None

    def get_control_status(self, identifier: int) -> Optional[bytes]:
        with self.rx_lock:
            value = self.control_status.get(identifier)
            return bytes(value) if value is not None else None

    def wait_for_control_status(self, identifier: int, timeout: float = 1.0) -> Optional[bytes]:
        evt = self.control_status_events.setdefault(identifier, threading.Event())
        evt.clear()
        ok = evt.wait(timeout=timeout)
        if not ok:
            return None
        return self.get_control_status(identifier)

    def _write_raw(self, packet: bytes) -> None:
        if not self.sock:
            raise RuntimeError("AACP socket not connected")
        with self.tx_lock:
            self.sock.send(packet)
        logging.debug("AACP sent: %s", packet.hex())

    def _recv_packet(self) -> Optional[bytes]:
        if not self.sock:
            return None
        try:
            data = self.sock.recv(2048)
            if data:
                with self.rx_lock:
                    self.rx_history.append((time.monotonic(), data))
                    if len(data) >= 5 and data.startswith(AACP_HEADER):
                        self.last_packet_by_opcode[data[4]] = bytes(data)
                        if data[4] == AACP_OPCODE_CONTROL and len(data) >= 11:
                            identifier = data[6]
                            value = bytes(data[7:11])
                            self.control_status[identifier] = value
                            evt = self.control_status_events.setdefault(identifier, threading.Event())
                            evt.set()
                    # Keep only a rolling window to avoid unbounded growth.
                    if len(self.rx_history) > 400:
                        del self.rx_history[:200]
                logging.debug("AACP recv: %s", data.hex())
            return data
        except (socket.timeout, TimeoutError):
            return None
        except OSError:
            if not self.running:
                return None
            raise

    def _listen_loop(self) -> None:
        while self.running:
            try:
                pkt = self._recv_packet()
            except Exception:
                if self.running:
                    logging.exception("AACP recv failed")
                break
            if not pkt:
                continue
            if pkt.startswith(AACP_HANDSHAKE_ACK):
                logging.info("AACP handshake ACK")
                self._send_set_specific_features()
                continue
            if pkt.startswith(AACP_FEATURES_ACK):
                logging.info("AACP features ACK")
                self._send_request_notifications()
                continue
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None

    def _send_handshake(self) -> None:
        if self._handshake_sent:
            return
        self._write_raw(AACP_HANDSHAKE)
        self._handshake_sent = True

    def _send_set_specific_features(self) -> None:
        if self._features_sent:
            return
        self._write_raw(AACP_SET_SPECIFIC_FEATURES)
        self._features_sent = True

    def _send_request_notifications(self) -> None:
        if self._notifications_sent:
            return
        self._write_raw(AACP_REQUEST_NOTIFICATIONS)
        self._notifications_sent = True


def _describe_att_pdu(pdu: bytes) -> str:
    if not pdu:
        return "ATT[empty]"
    op = pdu[0]
    if op in (OPCODE_READ_REQUEST, OPCODE_WRITE_REQUEST, OPCODE_HANDLE_VALUE_NTF, OPCODE_HANDLE_VALUE_IND):
        if len(pdu) >= 3:
            handle = pdu[1] | (pdu[2] << 8)
            return f"ATT(op=0x{op:02x} handle=0x{handle:04x})"
    return f"ATT(op=0x{op:02x})"


def _looks_like_blank_hearing_assist(value: bytes) -> bool:
    if len(value) < 32:
        return False
    nonzero = sum(1 for b in value if b != 0)
    return nonzero <= 8

# ---------------- Hearing Aid payload ----------------

class HearingAidSettings:
    def __init__(
        self,
        left_eq: List[float],
        right_eq: List[float],
        left_amp: float,
        right_amp: float,
        left_tone: float,
        right_tone: float,
        left_conv: bool,
        right_conv: bool,
        left_anr: float,
        right_anr: float,
        net_amp: float,
        balance: float,
        own_voice: float,
    ) -> None:
        self.left_eq = left_eq
        self.right_eq = right_eq
        self.left_amplification = left_amp
        self.right_amplification = right_amp
        self.left_tone = left_tone
        self.right_tone = right_tone
        self.left_conversation_boost = left_conv
        self.right_conversation_boost = right_conv
        self.left_ambient_noise_reduction = left_anr
        self.right_ambient_noise_reduction = right_anr
        self.net_amplification = net_amp
        self.balance = balance
        self.own_voice_amplification = own_voice

def parse_hearing_aid_settings(data: bytes) -> Optional[HearingAidSettings]:
    if len(data) < 104:
        return None

    buf = data
    if len(buf) >= 107 and buf.startswith(HEARING_ASSIST_HEADER):
        off = 7
    else:
        off = 4

    left_eq: List[float] = [struct.unpack("<f", buf[off + i * 4: off + (i + 1) * 4])[0] for i in range(8)]
    off += 32

    left_amp = struct.unpack("<f", buf[off:off + 4])[0]; off += 4
    left_tone = struct.unpack("<f", buf[off:off + 4])[0]; off += 4
    left_conv = struct.unpack("<f", buf[off:off + 4])[0] > 0.5; off += 4
    left_anr = struct.unpack("<f", buf[off:off + 4])[0]; off += 4

    right_eq: List[float] = [struct.unpack("<f", buf[off + i * 4: off + (i + 1) * 4])[0] for i in range(8)]
    off += 32

    right_amp = struct.unpack("<f", buf[off:off + 4])[0]; off += 4
    right_tone = struct.unpack("<f", buf[off:off + 4])[0]; off += 4
    right_conv = struct.unpack("<f", buf[off:off + 4])[0] > 0.5; off += 4
    right_anr = struct.unpack("<f", buf[off:off + 4])[0]; off += 4

    own_voice = struct.unpack("<f", buf[off:off + 4])[0] if len(buf) >= off + 4 else 0.5

    # Derive UI-friendly net amp & balance (best-effort)
    net_amp = max(-1.0, min(1.0, (left_amp + right_amp) / 2.0))
    denom = max(1e-6, abs(left_amp) + abs(right_amp))
    balance = max(-1.0, min(1.0, (right_amp - left_amp) / denom))

    return HearingAidSettings(
        left_eq, right_eq,
        left_amp, right_amp,
        left_tone, right_tone,
        left_conv, right_conv,
        left_anr, right_anr,
        net_amp, balance, own_voice
    )

def _interpolate_series(freqs: List[int], values: Dict[int, Optional[float]]) -> List[float]:
    result: List[float] = []
    for f in freqs:
        v = values.get(f)
        if v is not None:
            result.append(float(v))
            continue
        lower = [ff for ff in freqs if ff < f and values.get(ff) is not None]
        upper = [ff for ff in freqs if ff > f and values.get(ff) is not None]
        if lower and upper:
            f0 = max(lower)
            f1 = min(upper)
            v0 = float(values[f0])  # type: ignore[arg-type]
            v1 = float(values[f1])  # type: ignore[arg-type]
            t = (f - f0) / (f1 - f0)
            result.append(v0 + (v1 - v0) * t)
        elif lower:
            result.append(float(values[max(lower)]))  # type: ignore[arg-type]
        elif upper:
            result.append(float(values[min(upper)]))  # type: ignore[arg-type]
        else:
            result.append(0.0)
    return result


def build_hearing_assist_payload_from_audiogram(
    left: Dict[int, Optional[float]],
    right: Dict[int, Optional[float]],
    left_adj: Tuple[float, float, float, float],
    right_adj: Tuple[float, float, float, float],
) -> bytes:
    # Drop 125/750 Hz if supplied, interpolate missing among 250..8000.
    left_vals = {f: left.get(f) for f in HEARING_ASSIST_FREQS}
    right_vals = {f: right.get(f) for f in HEARING_ASSIST_FREQS}
    left_eq = _interpolate_series(HEARING_ASSIST_FREQS, left_vals)
    right_eq = _interpolate_series(HEARING_ASSIST_FREQS, right_vals)

    buf = bytearray()
    buf += HEARING_ASSIST_HEADER
    for v in left_eq:
        buf += struct.pack("<f", float(v))
    for v in left_adj:
        buf += struct.pack("<f", float(v))
    for v in right_eq:
        buf += struct.pack("<f", float(v))
    for v in right_adj:
        buf += struct.pack("<f", float(v))
    buf += HEARING_ASSIST_TRAILER
    if len(buf) != 107:
        raise ValueError(f"Invalid hearing assist payload length: {len(buf)}")
    return bytes(buf)

def _build_hearing_aid_payload_legacy(settings: HearingAidSettings, template: bytes) -> bytes:
    if len(template) < 104:
        raise ValueError("Template too short (<104 bytes)")

    buf = bytearray(template[:104])
    # Preserve header but force byte[2] to 0x64 (matches upstream behavior)
    buf[2] = 0x64

    for i in range(8):
        struct.pack_into("<f", buf, 4 + i * 4, float(settings.left_eq[i]))
    struct.pack_into("<f", buf, 36, float(settings.left_amplification))
    struct.pack_into("<f", buf, 40, float(settings.left_tone))
    struct.pack_into("<f", buf, 44, 1.0 if settings.left_conversation_boost else 0.0)
    struct.pack_into("<f", buf, 48, float(settings.left_ambient_noise_reduction))

    for i in range(8):
        struct.pack_into("<f", buf, 52 + i * 4, float(settings.right_eq[i]))
    struct.pack_into("<f", buf, 84, float(settings.right_amplification))
    struct.pack_into("<f", buf, 88, float(settings.right_tone))
    struct.pack_into("<f", buf, 92, 1.0 if settings.right_conversation_boost else 0.0)
    struct.pack_into("<f", buf, 96, float(settings.right_ambient_noise_reduction))

    struct.pack_into("<f", buf, 100, float(settings.own_voice_amplification))
    return bytes(buf)


def _build_hearing_assist_payload(settings: HearingAidSettings) -> bytes:
    left = {f: settings.left_eq[i] for i, f in enumerate(HEARING_ASSIST_FREQS)}
    right = {f: settings.right_eq[i] for i, f in enumerate(HEARING_ASSIST_FREQS)}
    left_adj = (
        settings.left_amplification,
        settings.left_tone,
        1.0 if settings.left_conversation_boost else 0.0,
        settings.left_ambient_noise_reduction,
    )
    right_adj = (
        settings.right_amplification,
        settings.right_tone,
        1.0 if settings.right_conversation_boost else 0.0,
        settings.right_ambient_noise_reduction,
    )
    return build_hearing_assist_payload_from_audiogram(left, right, left_adj, right_adj)


def read_hearing_aid_settings(att: ATTManager) -> Optional[HearingAidSettings]:
    handle_obj = type("Handle", (), {"name": "HEARING_AID"})()
    data = att.read(handle_obj)
    return parse_hearing_aid_settings(data)


def build_headphone_accommodation_packet(
    phone_enabled: bool = False,
    media_enabled: bool = False,
    block_values: Optional[List[float]] = None,
) -> bytes:
    # AACP 0x53 packet: 04 00 04 00 53 00 84 00 02 00 [Phone] [Media] + 128 bytes
    phone = 0x01 if phone_enabled else 0x02
    media = 0x01 if media_enabled else 0x02
    payload = bytearray()
    payload += AACP_HEADPHONE_ACCOMMODATION_PREFIX
    payload += bytes([phone, media])
    logging.debug("Headphone Accommodation flags: phone=%02x media=%02x", phone, media)

    if block_values is None:
        data = bytes(128)
    else:
        if len(block_values) != 8:
            raise ValueError("block_values must be 8 floats")
        data = bytearray()
        # Repeat 8 floats 4 times (32 floats => 128 bytes)
        for _ in range(4):
            for v in block_values:
                data += struct.pack("<f", float(v))
        data = bytes(data)

    payload += data
    return bytes(payload)

def send_hearing_aid_settings(att: ATTManager, settings: HearingAidSettings, verify: bool = True) -> Optional[HearingAidSettings]:
    handle_obj = type("Handle", (), {"name": "HEARING_AID"})()

    # Determine expected length from device read to avoid ATT "Invalid Attribute Value Length" (0x0d).
    template = att.read(handle_obj)
    payload_mode = "legacy"
    if len(template) >= 107 and template.startswith(HEARING_ASSIST_HEADER):
        # Some stacks expose the full 107-byte packet (0x52 0x2A 0x00 ... 0x3F).
        payload = _build_hearing_assist_payload(settings)
        payload_mode = "assist107"
    elif len(template) >= 104:
        # Most Linux ATT reads expose the 104-byte value (without 0x52 0x2A 0x00).
        # Use the same canonical 107-byte builder and strip the 3-byte packet prefix so
        # bytes stay aligned to gist format: 02 02 64 00 + floats + trailing 0.5.
        payload = _build_hearing_assist_payload(settings)[3:]
        payload_mode = "assist104"
    else:
        payload = _build_hearing_aid_payload_legacy(settings, template)
        payload_mode = "legacy-short-template"
    if settings.own_voice_amplification != 0.5:
        logging.info("Own voice slider ignored for canonical hearing payload; trailing float forced to 0.5.")
    logging.info(
        "HEARING_AID payload mode=%s len=%d prefix=%s",
        payload_mode,
        len(payload),
        payload[:8].hex(),
    )

    # Debug: show if payload contains some common float encodings (80/85 dBHL)
    hex_payload = payload.hex()
    if "0000a042" in hex_payload.lower() or "0000aa42" in hex_payload.lower():
        logging.info("Payload seems to include 80/85 dBHL float patterns (good sign).")

    att.write(handle_obj, payload)
    att.last_written[ATT_HANDLES["HEARING_AID"]] = payload

    if not verify:
        return None

    # Best effort validation:
    # 1) Wait briefly for async echo (notification/indication) from handle 0x2A.
    echoed = att.wait_for_update(ATT_HANDLES["HEARING_AID"], timeout=0.8)
    if echoed and len(echoed) >= 104:
        parsed = parse_hearing_aid_settings(echoed)
        logging.info("Got async echo update for HEARING_AID.")
        return parsed

    # 2) Read back (some devices return a template; still useful to detect obvious failures).
    try:
        rb = att.read(handle_obj)
        parsed = parse_hearing_aid_settings(rb)
        logging.info("Read-back after write completed.")
        return parsed
    except Exception as e:
        logging.warning("Read-back failed: %s", e)
        return None

# ---------------- UI ----------------

class SignalEmitter(QObject):
    update_ui: pyqtSignal = pyqtSignal(object)

class HearingAidApp(QWidget):
    def __init__(self, mac_address: str) -> None:
        super().__init__()
        self.mac_address = mac_address
        self.att_manager = ATTManager(mac_address)
        self.aacp_manager = AACPManager(mac_address)
        self.emitter = SignalEmitter()
        self.emitter.update_ui.connect(self.on_update_ui)

        self._updating_ui = False
        self.config_file = _config_file_for_mac(mac_address)
        self._commit_lock = threading.Lock()
        self._commit_in_progress = False

        self.debounce_timer = QTimer()
        self.debounce_timer.setSingleShot(True)
        self.debounce_timer.setInterval(250)
        self.debounce_timer.timeout.connect(self.send_settings)

        self.init_ui()
        self.connect_att()

    def _att_quiet_active(self) -> bool:
        return self._commit_in_progress

    def init_ui(self) -> None:
        self.setWindowTitle(f"Hearing Aid Adjustments (Linux) [{SCRIPT_REVISION}]")
        layout = QVBoxLayout()

        # EQ inputs
        eq_layout = QGridLayout()
        self.left_eq_inputs = []
        self.right_eq_inputs = []
        eq_labels = ["250Hz", "500Hz", "1kHz", "2kHz", "3kHz", "4kHz", "6kHz", "8kHz"]

        eq_layout.addWidget(QLabel("Frequency"), 0, 0)
        eq_layout.addWidget(QLabel("Left (dBHL)"), 0, 1)
        eq_layout.addWidget(QLabel("Right (dBHL)"), 0, 2)

        for i, label in enumerate(eq_labels):
            eq_layout.addWidget(QLabel(label), i + 1, 0)
            left_input = QLineEdit()
            right_input = QLineEdit()
            self.left_eq_inputs.append(left_input)
            self.right_eq_inputs.append(right_input)
            eq_layout.addWidget(left_input, i + 1, 1)
            eq_layout.addWidget(right_input, i + 1, 2)

        layout.addWidget(QLabel("Audiogram"))
        eq_group = QWidget()
        eq_group.setLayout(eq_layout)
        layout.addWidget(eq_group)

        # Sliders
        self.amp_slider = QSlider(Qt.Horizontal)
        self.amp_slider.setRange(0, 100)
        self.amp_slider.setValue(0)
        layout.addWidget(QLabel("Amplification"))
        layout.addWidget(self.amp_slider)

        self.balance_slider = QSlider(Qt.Horizontal)
        self.balance_slider.setRange(-100, 100)
        self.balance_slider.setValue(0)
        layout.addWidget(QLabel("Balance (L <-> R)"))
        layout.addWidget(self.balance_slider)

        self.tone_slider = QSlider(Qt.Horizontal)
        self.tone_slider.setRange(-100, 100)
        self.tone_slider.setValue(0)
        layout.addWidget(QLabel("Tone"))
        layout.addWidget(self.tone_slider)

        self.anr_slider = QSlider(Qt.Horizontal)
        self.anr_slider.setRange(0, 100)
        self.anr_slider.setValue(0)
        layout.addWidget(QLabel("Ambient Noise Reduction"))
        layout.addWidget(self.anr_slider)

        self.own_voice_slider = QSlider(Qt.Horizontal)
        self.own_voice_slider.setRange(0, 100)
        self.own_voice_slider.setValue(0)
        layout.addWidget(QLabel("Own Voice Amplification"))
        layout.addWidget(self.own_voice_slider)

        self.conv_checkbox = QCheckBox("Conversation Boost")
        self.conv_checkbox.setChecked(False)
        layout.addWidget(self.conv_checkbox)

        # Buttons
        btn_row = QHBoxLayout()
        self.apply_button = QPushButton("Apply")
        self.apply_commit_button = QPushButton("Apply / Commit")
        self.preset_80_button = QPushButton("Preset 80/100")
        self.read_raw_button = QPushButton("Read Raw Blob")
        self.refresh_button = QPushButton("Refresh / Read")
        self.reset_button = QPushButton("Reset")
        btn_row.addWidget(self.apply_button)
        btn_row.addWidget(self.apply_commit_button)
        btn_row.addWidget(self.preset_80_button)
        btn_row.addWidget(self.read_raw_button)
        btn_row.addWidget(self.refresh_button)
        btn_row.addWidget(self.reset_button)
        layout.addLayout(btn_row)

        self.debug_overwrite_checkbox = QCheckBox("Debug: overwrite UI from device reads")
        self.debug_overwrite_checkbox.setChecked(False)
        layout.addWidget(self.debug_overwrite_checkbox)

        self.commit_preset_checkbox = QCheckBox("Commit preset 80/100")
        self.commit_preset_checkbox.setChecked(True)
        layout.addWidget(self.commit_preset_checkbox)

        self.setLayout(layout)

        # Events: debounce for edits
        for w in (
            self.amp_slider, self.balance_slider, self.tone_slider,
            self.anr_slider, self.own_voice_slider
        ):
            w.valueChanged.connect(self.on_value_changed)

        self.conv_checkbox.stateChanged.connect(self.on_value_changed)
        for inp in self.left_eq_inputs + self.right_eq_inputs:
            inp.textChanged.connect(self.on_value_changed)

        self.apply_button.clicked.connect(self.send_settings)
        self.apply_commit_button.clicked.connect(self.apply_commit_sequence)
        self.preset_80_button.clicked.connect(self.apply_preset_80)
        self.read_raw_button.clicked.connect(self.read_raw_blob)
        self.refresh_button.clicked.connect(self.refresh_from_device)
        self.reset_button.clicked.connect(self.reset_settings)

    def connect_att(self) -> None:
        logging.info("Connecting to ATT in UI")
        try:
            self.att_manager.connect()
        except OSError as e:
            err = getattr(e, "errno", None)
            if err == 111:
                logging.error(
                    "Connection refused (errno=111). AirPods are likely connected elsewhere or not connected in BlueZ."
                )
                logging.error("Disconnect other hosts and connect the AirPods via bluetoothctl, then retry.")
            elif err == 112:
                logging.error(
                    "Host is down (errno=112). No active BLE link to %s at connect time.",
                    self.mac_address,
                )
                logging.error(
                    "Keep buds out of case/in ear, ensure they're connected in BlueZ, then relaunch."
                )
            elif err == 113:
                logging.error(
                    "No route to host (errno=113). Device may be out of range or no current route in BlueZ."
                )
            raise

        # Enable CCCD for hearing aid (both notify+indicate)
        self.att_manager.enable_notifications(type("Handle", (), {"name": "HEARING_AID"})(), mode="both")
        try:
            self.aacp_manager.connect()
        except Exception as e:
            logging.warning("AACP connection failed (apply/commit may not work): %s", e)

        # Initial read
        self.refresh_from_device()

    def refresh_from_device(self) -> None:
        if self._att_quiet_active():
            logging.info("Skipping refresh while apply/commit is running.")
            return
        logging.info("Refreshing from device (read HEARING_AID)")
        def worker():
            if self._att_quiet_active():
                logging.info("Refresh aborted: apply/commit is running.")
                return
            try:
                parsed = read_hearing_aid_settings(self.att_manager)
                if not parsed:
                    logging.warning("Read returned no/short data.")
                    return
                if self.debug_overwrite_checkbox.isChecked():
                    self.emitter.update_ui.emit(parsed)
                else:
                    logging.info("Device read parsed (not applied to UI). Enable the debug checkbox if you want it to overwrite UI.")
            except Exception as e:
                logging.error("Read failed: %s", e)

        Thread(target=worker, daemon=True).start()

    def on_update_ui(self, settings: HearingAidSettings) -> None:
        # Avoid feedback loop
        self._updating_ui = True
        widgets = [self.amp_slider, self.balance_slider, self.tone_slider, self.anr_slider, self.own_voice_slider, self.conv_checkbox]
        for w in widgets:
            w.blockSignals(True)

        try:
            # Populate EQ boxes
            for i in range(8):
                self.left_eq_inputs[i].setText(str(round(settings.left_eq[i], 2)))
                self.right_eq_inputs[i].setText(str(round(settings.right_eq[i], 2)))

            # Best effort: show net amp/balance
            self.amp_slider.setValue(int(round(max(0.0, min(1.0, settings.net_amplification)) * 100)))
            self.balance_slider.setValue(int(round(settings.balance * 100)))
            self.tone_slider.setValue(int(round(settings.left_tone * 100)))
            self.anr_slider.setValue(int(round(settings.left_ambient_noise_reduction * 100)))
            self.own_voice_slider.setValue(int(round(settings.own_voice_amplification * 100)))
            self.conv_checkbox.setChecked(bool(settings.left_conversation_boost))
        finally:
            for w in widgets:
                w.blockSignals(False)
            self._updating_ui = False

    def on_value_changed(self) -> None:
        if self._updating_ui:
            return
        if self._att_quiet_active():
            return
        self.debounce_timer.start()

    def _collect_settings(self) -> HearingAidSettings:
        amp = self.amp_slider.value() / 100.0
        balance = self.balance_slider.value() / 100.0  # -1..1
        tone = self.tone_slider.value() / 100.0
        anr = self.anr_slider.value() / 100.0
        own_voice = self.own_voice_slider.value() / 100.0
        conv = self.conv_checkbox.isChecked()

        # Practical balance behavior: positive -> favor RIGHT (reduce left), negative -> favor LEFT (reduce right)
        left_amp = amp * (1.0 - max(0.0, balance))
        right_amp = amp * (1.0 + min(0.0, balance))

        left_eq = [float(x.text() or 0.0) for x in self.left_eq_inputs]
        right_eq = [float(x.text() or 0.0) for x in self.right_eq_inputs]

        return HearingAidSettings(
            left_eq, right_eq,
            left_amp, right_amp,
            tone, tone,
            conv, conv,
            anr, anr,
            amp, balance,
            own_voice,
        )


    def _save_local(self, settings: HearingAidSettings) -> None:
        try:
            tmp = self.config_file.with_suffix(".json.tmp")
            tmp.write_text(json.dumps(_settings_to_dict(settings), indent=2, sort_keys=True))
            tmp.replace(self.config_file)
            logging.info("Saved local config: %s", str(self.config_file))
        except Exception as e:
            logging.warning("Failed to save local config: %s", e)

    def _load_local_into_ui(self) -> None:
        if not hasattr(self, "config_file"):
            return
        if not self.config_file.exists():
            logging.info("No local config found (OK): %s", str(self.config_file))
            return
        try:
            data = json.loads(self.config_file.read_text())
            settings = _settings_from_dict(data)
            # Avoid triggering auto writes while populating
            self._updating_ui = True
            self.on_update_ui(settings)
            self._updating_ui = False
            logging.info("Loaded local config into UI.")
        except Exception as e:
            logging.warning("Failed to load local config: %s", e)
    def send_settings(self) -> None:
        if self._att_quiet_active():
            logging.info("Skipping Apply while apply/commit is running.")
            return
        logging.info("Sending settings from UI")
        try:
            settings = self._collect_settings()
        except Exception as e:
            logging.error("Invalid input: %s", e)
            return

        # Always persist locally first (helps when device read-back is blank or resets later)
        self._save_local(settings)

        def worker():
            if self._att_quiet_active():
                logging.info("Apply worker aborted: apply/commit is running.")
                return
            try:
                # Write + read-back (verify) is useful for debugging, but some devices always return a
                # template/zeroed payload on reads. We log it, but we DON'T overwrite the UI unless
                # the debug checkbox is enabled.
                parsed = send_hearing_aid_settings(self.att_manager, settings, verify=False)
                if parsed and self.debug_overwrite_checkbox.isChecked():
                    self.emitter.update_ui.emit(parsed)
                elif parsed:
                    logging.info("Read-back parsed (not applied to UI): left_amp=%s right_amp=%s net_amp=%s balance=%s",
                                 parsed.left_amplification, parsed.right_amplification, parsed.net_amplification, parsed.balance)
                else:
                    logging.info("ATT write sent (verification read disabled).")
            except Exception as e:
                logging.error("Write failed: %s", e)

        Thread(target=worker, daemon=True).start()

    def apply_commit_sequence(self) -> None:
        # Hearing-aid focused apply path:
        # 1) Ensure hearing aid is enabled via AACP controls (0x2C and 0x33)
        # 2) Send AACP 0x29 capability packet used by LibrePods Android
        # 3) Write hearing-aid payload over ATT handle 0x002A
        def worker():
            with self._commit_lock:
                if self._commit_in_progress:
                    logging.info("Apply/commit already in progress.")
                    return
                self._commit_in_progress = True
            try:
                self.debounce_timer.stop()
                try:
                    settings = self._collect_settings()
                    if self.commit_preset_checkbox.isChecked():
                        settings.left_eq = [80.0] * 8
                        settings.right_eq = [80.0] * 8
                        settings.left_amplification = 1.0
                        settings.right_amplification = 1.0
                        settings.net_amplification = 1.0
                        settings.balance = 0.0
                except Exception as e:
                    logging.error("Invalid input: %s", e)
                    return

                self._save_local(settings)

                try:
                    self.aacp_manager.ensure_connected()
                    if not self.aacp_manager._notifications_sent:
                        time.sleep(0.3)

                    # Prime AACP channel similar to Android service startup.
                    self.aacp_manager.send_packet(AACP_HANDSHAKE)
                    time.sleep(0.1)
                    self.aacp_manager.send_packet(AACP_SET_SPECIFIC_FEATURES)
                    time.sleep(0.1)
                    self.aacp_manager.send_packet(AACP_REQUEST_NOTIFICATIONS)
                    time.sleep(0.1)

                    before_2c = self.aacp_manager.get_control_status(0x2C)
                    before_33 = self.aacp_manager.get_control_status(0x33)
                    logging.info(
                        "Control status before apply: 0x2C=%s 0x33=%s",
                        before_2c.hex() if before_2c else "none",
                        before_33.hex() if before_33 else "none",
                    )

                    # Hearing aid + hearing assist enabled (Android parity).
                    self.aacp_manager.send_control_command(0x2C, b"\x01\x01\x00\x00")
                    ack_2c = self.aacp_manager.wait_for_control_status(0x2C, timeout=0.4)
                    if ack_2c is not None:
                        logging.info("Control ack after 0x2C write: %s", ack_2c.hex())
                    else:
                        logging.info("No control ack observed for 0x2C within timeout.")

                    self.aacp_manager.send_control_command(0x33, b"\x01\x00\x00\x00")
                    ack_33 = self.aacp_manager.wait_for_control_status(0x33, timeout=0.4)
                    if ack_33 is not None:
                        logging.info("Control ack after 0x33 write: %s", ack_33.hex())
                    else:
                        logging.info("No control ack observed for 0x33 within timeout.")

                    # Extra packet used by Android service to unlock EQ-setting path.
                    self.aacp_manager.send_packet(AACP_ENABLE_EQ_WRITE_PATH)
                    logging.info("Sent AACP pre-sequence (0x2C/0x33 + 0x29).")
                    time.sleep(0.1)
                except Exception as e:
                    logging.warning("AACP pre-sequence failed; continuing with ATT write: %s", e)

                send_hearing_aid_settings(self.att_manager, settings, verify=False)
                logging.info("ATT hearing-aid payload write sent.")

                # Single delayed read for evidence only.
                time.sleep(0.8)
                try:
                    handle_obj = type("Handle", (), {"name": "HEARING_AID"})()
                    data = self.att_manager.read(handle_obj)
                    parsed = parse_hearing_aid_settings(data)
                    logging.info("Post-apply delayed read (%d bytes): %s", len(data), data.hex())
                    if parsed:
                        logging.info(
                            "Post-apply parsed: left_amp=%.3f right_amp=%.3f tone=%.3f anr=%.3f own_voice=%.3f",
                            parsed.left_amplification,
                            parsed.right_amplification,
                            parsed.left_tone,
                            parsed.left_ambient_noise_reduction,
                            parsed.own_voice_amplification,
                        )
                except Exception as e:
                    logging.warning("Post-apply delayed read failed: %s", e)
            except Exception as e:
                logging.error("Apply/commit sequence failed: %s", e)
            finally:
                self._commit_in_progress = False

        Thread(target=worker, daemon=True).start()

    def read_raw_blob(self) -> None:
        if self._att_quiet_active():
            logging.info("Skipping raw read while apply/commit is running.")
            return
        def worker():
            if self._att_quiet_active():
                logging.info("Raw read aborted: apply/commit is running.")
                return
            try:
                handle_obj = type("Handle", (), {"name": "HEARING_AID"})()
                data = self.att_manager.read(handle_obj)
                logging.info("Raw HEARING_AID blob (%d bytes): %s", len(data), data.hex())
                parsed = parse_hearing_aid_settings(data)
                if parsed:
                    logging.info(
                        "Parsed: left_eq=%s right_eq=%s left_amp=%.3f right_amp=%.3f tone=%.3f anr=%.3f conv=%s own_voice=%.3f",
                        [round(v, 2) for v in parsed.left_eq],
                        [round(v, 2) for v in parsed.right_eq],
                        parsed.left_amplification,
                        parsed.right_amplification,
                        parsed.left_tone,
                        parsed.left_ambient_noise_reduction,
                        parsed.left_conversation_boost,
                        parsed.own_voice_amplification,
                    )
            except Exception as e:
                logging.error("Read raw blob failed: %s", e)

        Thread(target=worker, daemon=True).start()

    def reset_settings(self) -> None:
        self.amp_slider.setValue(0)
        self.balance_slider.setValue(0)
        self.tone_slider.setValue(0)
        self.anr_slider.setValue(0)
        self.own_voice_slider.setValue(0)
        self.conv_checkbox.setChecked(False)
        for inp in self.left_eq_inputs + self.right_eq_inputs:
            inp.setText("0")
        # Don't auto-apply; user can press Apply

    def apply_preset_80(self) -> None:
        # Set all EQ values to 80 dBHL and amplification to 100%.
        self._updating_ui = True
        widgets = [self.amp_slider] + self.left_eq_inputs + self.right_eq_inputs
        for w in widgets:
            w.blockSignals(True)
        try:
            self.amp_slider.setValue(100)
            for inp in self.left_eq_inputs + self.right_eq_inputs:
                inp.setText("80")
        finally:
            for w in widgets:
                w.blockSignals(False)
            self._updating_ui = False

    def closeEvent(self, event: Any) -> None:
        self.att_manager.disconnect()
        self.aacp_manager.disconnect()
        event.accept()


def _config_file_for_mac(mac: str) -> Path:
    # Store per-device settings locally so you can re-apply even if the AirPods doesn't
    # expose readable/persistent values over ATT.
    safe = mac.replace(":", "_").replace("-", "_").lower()
    base = Path.home() / ".config" / "librepods"
    base.mkdir(parents=True, exist_ok=True)
    return base / f"hearing_aid_{safe}.json"

def _read_bluez_device_id(config_path: str = "/etc/bluetooth/main.conf") -> Optional[str]:
    try:
        text = Path(config_path).read_text(errors="ignore")
    except Exception:
        return None
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip().lower() == "deviceid":
            return value.strip()
    return None

def _log_did_spoof_status() -> None:
    current = _read_bluez_device_id()
    if current is None:
        logging.warning(
            "Could not read BlueZ DeviceID from /etc/bluetooth/main.conf; hearing-aid writes may be rejected."
        )
        return
    if current.lower() != EXPECTED_BLUETOOTH_DID.lower():
        logging.warning(
            "BlueZ DeviceID is '%s' (expected '%s' for Apple DID spoof).",
            current,
            EXPECTED_BLUETOOTH_DID,
        )
        logging.warning("Set DeviceID, restart bluetooth, and re-pair AirPods.")
        return
    logging.info("BlueZ DeviceID spoof is active: %s", current)


def _log_script_revision() -> None:
    try:
        script_path = Path(__file__).resolve()
        digest = hashlib.sha256(script_path.read_bytes()).hexdigest()[:12]
        logging.info(
            "Script revision: %s (%s, sha256=%s)",
            SCRIPT_REVISION,
            script_path,
            digest,
        )
    except Exception as e:
        logging.warning("Failed to compute script fingerprint: %s", e)


def _settings_to_dict(s: HearingAidSettings) -> dict:
    return {
        "left_eq": list(map(float, s.left_eq)),
        "right_eq": list(map(float, s.right_eq)),
        "left_amp": float(s.left_amplification),
        "right_amp": float(s.right_amplification),
        "left_tone": float(s.left_tone),
        "right_tone": float(s.right_tone),
        "left_conv": bool(s.left_conversation_boost),
        "right_conv": bool(s.right_conversation_boost),
        "left_anr": float(s.left_ambient_noise_reduction),
        "right_anr": float(s.right_ambient_noise_reduction),
        "net_amp": float(s.net_amplification),
        "balance": float(s.balance),
        "own_voice": float(s.own_voice_amplification),
        "version": 2,
    }


def _settings_from_dict(d: dict) -> HearingAidSettings:
    def _f(x, default=0.0):
        try:
            return float(x)
        except Exception:
            return float(default)

    def _b(x, default=False):
        try:
            return bool(x)
        except Exception:
            return bool(default)

    left_eq = [ _f(v) for v in (d.get("left_eq") or [0]*8) ][:8]
    right_eq = [ _f(v) for v in (d.get("right_eq") or [0]*8) ][:8]
    # Pad if shorter
    left_eq += [0.0] * (8 - len(left_eq))
    right_eq += [0.0] * (8 - len(right_eq))

    return HearingAidSettings(
        left_eq=left_eq,
        right_eq=right_eq,
        left_amp=_f(d.get("left_amp")),
        right_amp=_f(d.get("right_amp")),
        left_tone=_f(d.get("left_tone")),
        right_tone=_f(d.get("right_tone")),
        left_conv=_b(d.get("left_conv")),
        right_conv=_b(d.get("right_conv")),
        left_anr=_f(d.get("left_anr")),
        right_anr=_f(d.get("right_anr")),
        net_amp=_f(d.get("net_amp")),
        balance=_f(d.get("balance")),
        own_voice=_f(d.get("own_voice")),
    )


def _validate_mac(mac: str) -> bool:
    import re
    return bool(re.match(r"^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$", mac))

if __name__ == "__main__":
    if len(sys.argv) != 2 or not _validate_mac(sys.argv[1]):
        logging.error("Usage: python hearing-aid-adjustments_persist_local_v2.py <MAC_ADDRESS>")
        sys.exit(1)

    mac = sys.argv[1]
    logging.info("Starting app")
    _log_script_revision()
    _log_did_spoof_status()

    # If running under sudo, Qt can warn about XDG_RUNTIME_DIR ownership.
    # This avoids noisy warnings (and occasional Wayland/portal oddities).
    if os.geteuid() == 0:
        xdg = os.environ.get("XDG_RUNTIME_DIR", "")
        if xdg and xdg.startswith("/run/user/") and not xdg.startswith("/run/user/0"):
            new_xdg = "/tmp/xdg-root"
            try:
                os.makedirs(new_xdg, exist_ok=True)
                os.chmod(new_xdg, 0o700)
                os.environ["XDG_RUNTIME_DIR"] = new_xdg
            except Exception:
                pass


    app = QApplication(sys.argv)

    def quit_app(signum: int, frame: Any) -> None:
        app.quit()

    signal.signal(signal.SIGINT, quit_app)

    window = HearingAidApp(mac)
    window.show()
    sys.exit(app.exec_())
