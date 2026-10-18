#!/usr/bin/env python3

# Needs https://github.com/google/bumble on Windows
# See https://github.com/google/bumble/blob/main/docs/mkdocs/src/platforms/windows.md for usage.
# You need to associate WinUSB with your Bluetooth interface. Once done, you can roll back to the original driver from Device Manager.

import asyncio
import argparse
import logging
import signal
import struct
import sys
import threading
import platform
from queue import Queue
from typing import Any, Callable, Dict, List, Optional

from PyQt5.QtGui import QDoubleValidator
from colorama import Fore, Style, init as colorama_init
colorama_init(autoreset=True)

from PyQt5.QtWidgets import (
    QApplication, QWidget, QVBoxLayout, QLabel, QSlider,
    QCheckBox, QPushButton, QLineEdit, QGridLayout, QComboBox
)
from PyQt5.QtCore import Qt, QTimer, pyqtSignal, QObject

handler = logging.StreamHandler()
class ColorFormatter(logging.Formatter):
    COLORS = {
        logging.DEBUG: Fore.BLUE,
        logging.INFO: Fore.GREEN,
        logging.WARNING: Fore.YELLOW,
        logging.ERROR: Fore.RED,
        logging.CRITICAL: Fore.MAGENTA,
    }
    def format(self, record):
        color = self.COLORS.get(record.levelno, "")
        prefix = f"{color}[{record.levelname}:{record.name}]{Style.RESET_ALL}"
        return f"{prefix} {record.getMessage()}"

handler.setFormatter(ColorFormatter())
logging.basicConfig(level=logging.INFO, handlers=[handler])
logger = logging.getLogger("hearing-aid")

OPCODE_READ_REQUEST: int = 0x0A
OPCODE_READ_RESPONSE: int = 0x0B
OPCODE_WRITE_REQUEST: int = 0x12
OPCODE_WRITE_RESPONSE: int = 0x13
OPCODE_HANDLE_VALUE_NTF: int = 0x1B

ATT_HANDLES: Dict[str, int] = {
    'LOUD_SOUND_REDUCTION': 0x1B,
    'HEARING_AID': 0x2A,
}

ATT_CCCD_HANDLES: Dict[str, int] = {
    'LOUD_SOUND_REDUCTION': ATT_HANDLES['LOUD_SOUND_REDUCTION'] + 1,
    'HEARING_AID': ATT_HANDLES['HEARING_AID'] + 1,
}

AACP_HEADER = bytes([0x04, 0x00, 0x04, 0x00])
AACP_HANDSHAKE = bytes([0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])

class AACPOpcodes:
    SET_FEATURE_FLAGS = 0x4D
    REQUEST_NOTIFICATIONS = 0x0F
    CONTROL_COMMAND = 0x09

class ControlCommandId:
    HEARING_AID = 0x2C
    HPS_GAIN_SWIPE = 0x2F
    HEARING_ASSIST_CONFIG = 0x33
    LISTENING_MODE = 0x0D
    OWNS_CONNECTION = 0x06


class BluezChannel:
    def __init__(self, socket, loop):
        self.socket = socket
        self.loop = loop
        self.sink = None
        self._running = True
        self._thread = threading.Thread(target=self._read_loop, daemon=True)
        self._thread.start()

    def send_pdu(self, pdu):
        try:
            logger.debug(f"Sending PDU: {pdu.hex() if pdu else 'None'}")
            self.socket.send(pdu)
        except OSError as e:
            logger.error(f"Socket send error: {e}")

    def _read_loop(self):
        while self._running:
            try:
                data = self.socket.recv(2048)
                logger.debug(f"Received PDU: {data.hex() if data else 'None'}")
                if not data:
                    break
                if self.sink:
                    self.loop.call_soon_threadsafe(self.sink, data)
            except OSError:
                break

    def stop(self):
        self._running = False
        try:
            self.socket.close()
        except:
            pass


def _make_reader(ch):
    recv_q: asyncio.Queue = asyncio.Queue()

    def _sink(sdu):
        try:
            recv_q.put_nowait(sdu)
        except Exception:
            logger.debug("Dropping SDU in sink fallback")

    try:
        ch.sink = _sink
    except Exception:
        logger.debug("Failed to set channel.sink fallback")

    async def _reader_from_sink():
        item = await recv_q.get()
        return item

    return _reader_from_sink


class HearingAidSettings:
    def __init__(self, left_eq: List[float], right_eq: List[float], left_amp: float, right_amp: float,
                 left_tone: float, right_tone: float, left_conv: bool, right_conv: bool,
                 left_anr: float, right_anr: float, net_amp: float, balance: float, own_voice: float) -> None:
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
        logger.warning("Data too short for parsing")
        return None
    buffer = data
    offset = 4

    left_eq = []
    for _ in range(8):
        val, = struct.unpack('<f', buffer[offset:offset+4])
        left_eq.append(val)
        offset += 4

    left_amp, = struct.unpack('<f', buffer[offset:offset+4])
    offset += 4
    left_tone, = struct.unpack('<f', buffer[offset:offset+4])
    offset += 4
    left_conv_float, = struct.unpack('<f', buffer[offset:offset+4])
    left_conv = left_conv_float > 0.5
    offset += 4
    left_anr, = struct.unpack('<f', buffer[offset:offset+4])
    offset += 4

    right_eq = []
    for _ in range(8):
        val, = struct.unpack('<f', buffer[offset:offset+4])
        right_eq.append(val)
        offset += 4

    right_amp, = struct.unpack('<f', buffer[offset:offset+4])
    offset += 4
    right_tone, = struct.unpack('<f', buffer[offset:offset+4])
    offset += 4
    right_conv_float, = struct.unpack('<f', buffer[offset:offset+4])
    right_conv = right_conv_float > 0.5
    offset += 4
    right_anr, = struct.unpack('<f', buffer[offset:offset+4])
    offset += 4

    own_voice, = struct.unpack('<f', buffer[offset:offset+4])

    avg = (left_amp + right_amp) / 2
    amplification = max(-1, min(1, avg))
    diff = right_amp - left_amp
    balance = max(-1, min(1, diff))

    return HearingAidSettings(left_eq, right_eq, left_amp, right_amp, left_tone, right_tone,
                              left_conv, right_conv, left_anr, right_anr, amplification, balance, own_voice)

class AACPManager:
    def __init__(self):
        self.channel = None
        self.running = False
        self._recv_q = asyncio.Queue()
        self.control_cmd_listeners: Dict[int, List[Callable[[bytes], None]]] = {}

    def set_channel(self, channel):
        self.channel = channel

        def _sink(pdu):
            try:
                self._recv_q.put_nowait(pdu)
            except Exception:
                logger.debug("Dropping SDU")

        channel.sink = _sink
        self.running = True

    def register_control_cmd_listener(self, cmd_id: int, listener: Callable[[bytes], None]):
        if cmd_id not in self.control_cmd_listeners:
            self.control_cmd_listeners[cmd_id] = []
        self.control_cmd_listeners[cmd_id].append(listener)

    async def send_handshake(self):
        self.channel.send_pdu(AACP_HANDSHAKE)
        logger.info("AACP handshake sent")

    async def send_notification_request(self):
        pdu = (
            AACP_HEADER
            + bytes([AACPOpcodes.REQUEST_NOTIFICATIONS, 0x00, 0xFF, 0xFF, 0xFF, 0xFF])
        )
        self.channel.send_pdu(pdu)
        logger.info("AACP notification request sent")

    async def send_set_feature_flags(self):
        pdu = (
            AACP_HEADER
            + bytes([
                AACPOpcodes.SET_FEATURE_FLAGS, 0x00, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])
        )
        self.channel.send_pdu(pdu)
        logger.info("AACP set feature flags sent")

    async def send_control_command(self, cmd_id: int, value: bytes):
        padded = (value + bytes(4))[:4]
        pdu = AACP_HEADER + bytes([AACPOpcodes.CONTROL_COMMAND, 0x00, cmd_id]) + padded
        self.channel.send_pdu(pdu)
        logger.info(f"AACP control command {cmd_id:#04x} sent: {value.hex()}")

    async def listen(self):
        logger.info("AACP listener started")
        while self.running:
            try:
                pdu = await self._recv_q.get()
                if not isinstance(pdu, (bytes, bytearray)):
                    pdu = bytes(pdu)
                self._handle_packet(pdu)
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.debug(f"AACP listen error: {e}")
                break
        logger.info("AACP listener stopped")

    def _handle_packet(self, pdu: bytes):
        if len(pdu) < 5 or pdu[:4] != AACP_HEADER:
            return

        opcode = pdu[4]
        payload = pdu[4:]

        if opcode == AACPOpcodes.CONTROL_COMMAND:
            if len(payload) < 7:
                return
            cmd_id = payload[2]
            value = payload[3:7]
            value = value.rstrip(b'\x00') or bytes([0])

            logger.info(f"AACP control command received: {cmd_id:#04x} = {value.hex()}")

            if cmd_id in self.control_cmd_listeners:
                for listener in self.control_cmd_listeners[cmd_id]:
                    listener(value)

    def stop(self):
        self.running = False


class ATTManager:
    def __init__(self):
        self.channel = None
        self.responses: Queue = Queue()
        self.listeners: Dict[int, List[Any]] = {}
        self.running = False
        self._recv_q = asyncio.Queue()

    def set_channel(self, channel):
        self.channel = channel

        def _sink(pdu):
            try:
                self._recv_q.put_nowait(pdu)
            except Exception:
                logger.debug("Dropping ATT PDU")

        channel.sink = _sink
        self.running = True

    def register_listener(self, handle: int, listener: Any) -> None:
        if handle not in self.listeners:
            self.listeners[handle] = []
        self.listeners[handle].append(listener)

    async def enable_notifications(self, handle_name: str) -> None:
        await self.write_cccd(handle_name, b'\x01\x00')
        logger.info(f"Enabled notifications for handle {handle_name}")

    async def read(self, handle_name: str) -> bytes:
        handle_value = ATT_HANDLES[handle_name]
        lsb = handle_value & 0xFF
        msb = (handle_value >> 8) & 0xFF
        pdu = bytes([OPCODE_READ_REQUEST, lsb, msb])
        self.channel.send_pdu(pdu)
        response = await self._read_response()
        return response

    async def write(self, handle_name: str, value: bytes) -> None:
        handle_value = ATT_HANDLES[handle_name]
        lsb = handle_value & 0xFF
        msb = (handle_value >> 8) & 0xFF
        pdu = bytes([OPCODE_WRITE_REQUEST, lsb, msb]) + value
        self.channel.send_pdu(pdu)
        try:
            await self._read_response(timeout=2.0)
        except Exception:
            logger.warning(f"No write response received for handle {handle_name}")

    async def write_cccd(self, handle_name: str, value: bytes) -> None:
        handle_value = ATT_CCCD_HANDLES[handle_name]
        lsb = handle_value & 0xFF
        msb = (handle_value >> 8) & 0xFF
        pdu = bytes([OPCODE_WRITE_REQUEST, lsb, msb]) + value
        self.channel.send_pdu(pdu)
        try:
            await self._read_response(timeout=2.0)
        except Exception:
            logger.warning(f"No CCCD write response received for handle {handle_name}")

    async def _read_response(self, timeout: float = 2.0) -> bytes:
        try:
            response = await asyncio.wait_for(
                asyncio.get_event_loop().run_in_executor(
                    None, lambda: self.responses.get(timeout=timeout)
                ),
                timeout=timeout + 0.5
            )
            return response[1:]  # Skip opcode
        except Exception:
            raise Exception("No response received") from None

    async def listen_notifications(self) -> None:
        logger.info("ATT notification listener started")
        while self.running:
            try:
                pdu = await self._recv_q.get()

                if not isinstance(pdu, (bytes, bytearray)):
                    pdu = bytes(pdu)

                if len(pdu) > 0 and pdu[0] == OPCODE_HANDLE_VALUE_NTF:
                    handle = pdu[1] | (pdu[2] << 8)
                    value = pdu[3:]
                    if handle in self.listeners:
                        for listener in self.listeners[handle]:
                            listener(value)
                else:
                    self.responses.put(pdu)

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.debug(f"ATT listen error: {e}")
                break

        logger.info("ATT notification listener stopped")

    def stop(self):
        self.running = False



class SignalEmitter(QObject):
    update_ui = pyqtSignal(HearingAidSettings)
    update_hearing_aid_toggle = pyqtSignal(bool)
    update_swipe_toggle = pyqtSignal(bool)
    update_loud_sound_reduction_toggle = pyqtSignal(bool)
    update_listening_mode = pyqtSignal(int)
    connected = pyqtSignal()


class HearingAidApp(QWidget):
    def __init__(self, att_manager: ATTManager, aacp_manager: AACPManager,
                 loop: asyncio.AbstractEventLoop) -> None:
        super().__init__()
        self.att_manager = att_manager
        self.aacp_manager = aacp_manager
        self.loop = loop
        self.emitter = SignalEmitter()
        self.emitter.update_ui.connect(self.on_update_ui)
        self.emitter.update_hearing_aid_toggle.connect(self._set_hearing_aid_toggle)
        self.emitter.update_swipe_toggle.connect(self._set_swipe_toggle)
        self.emitter.update_loud_sound_reduction_toggle.connect(self._set_loud_sound_reduction_toggle)
        self.emitter.update_listening_mode.connect(self._set_listening_mode)
        self.emitter.connected.connect(self.on_connected)
        self.debounce_timer = QTimer()
        self.debounce_timer.setSingleShot(True)
        self.debounce_timer.timeout.connect(self.send_settings)
        self.init_ui()

    def init_ui(self) -> None:
        self.setWindowTitle("LibrePods - Hearing Aid")
        layout = QVBoxLayout()

        # Status label
        self.status_label = QLabel("Connecting...")
        layout.addWidget(self.status_label, alignment=Qt.AlignCenter)

        # Listening Mode combo box
        self.listening_mode_combo = QComboBox()
        self.listening_mode_combo.addItems(["Off", "Noise Cancellation", "Transparency", "Adaptive"])
        self.listening_mode_combo.currentIndexChanged.connect(self.on_listening_mode_changed)
        layout.addWidget(QLabel("Listening Mode"))
        layout.addWidget(self.listening_mode_combo)

        layout.addWidget(QLabel("Switch to Transparency mode for Hearing Aid to work."))

        layout.addSpacing(20)

        self.loud_sound_reduction_checkbox = QCheckBox("Loud Sound Reduction")
        self.loud_sound_reduction_checkbox.stateChanged.connect(self.on_loud_sound_reduction_toggle)
        layout.addWidget(self.loud_sound_reduction_checkbox)

        self.hearing_aid_checkbox = QCheckBox("Hearing Aid")
        self.hearing_aid_checkbox.stateChanged.connect(self.on_hearing_aid_toggle)
        layout.addWidget(self.hearing_aid_checkbox)

        layout.addSpacing(20)

        # EQ Inputs
        eq_layout = QGridLayout()
        self.left_eq_inputs: List[QLineEdit] = []
        self.right_eq_inputs: List[QLineEdit] = []

        eq_labels = ["250Hz", "500Hz", "1kHz", "2kHz", "3kHz", "4kHz", "6kHz", "8kHz"]
        eq_layout.addWidget(QLabel("Frequency"), 0, 0)
        eq_layout.addWidget(QLabel("Left"), 0, 1, alignment=Qt.AlignCenter)
        eq_layout.addWidget(QLabel("Right"), 0, 2, alignment=Qt.AlignCenter)

        validator = QDoubleValidator(0.0, 100.0, 6)

        for i, label in enumerate(eq_labels):
            eq_layout.addWidget(QLabel(label), i + 1, 0)
            left_input = QLineEdit()
            right_input = QLineEdit()
            left_input.setValidator(validator)
            right_input.setValidator(validator)
            left_input.setPlaceholderText("Left")
            right_input.setPlaceholderText("Right")
            self.left_eq_inputs.append(left_input)
            self.right_eq_inputs.append(right_input)
            eq_layout.addWidget(left_input, i + 1, 1)
            eq_layout.addWidget(right_input, i + 1, 2)

        eq_group = QWidget()
        eq_group.setLayout(eq_layout)
        layout.addWidget(QLabel("Loss, in dBHL"))
        layout.addWidget(eq_group)

        # Amplification
        self.amp_slider = QSlider(Qt.Horizontal)
        self.amp_slider.setRange(-100, 100)
        self.amp_slider.setValue(0)
        layout.addWidget(QLabel("Amplification"))
        layout.addWidget(self.amp_slider)

        self.swipe_checkbox = QCheckBox("Swipe to control amplification")
        self.swipe_checkbox.stateChanged.connect(self.on_swipe_toggle)
        layout.addWidget(self.swipe_checkbox)

        # Balance
        self.balance_slider = QSlider(Qt.Horizontal)
        self.balance_slider.setRange(-100, 100)
        self.balance_slider.setValue(0)
        layout.addWidget(QLabel("Balance"))
        layout.addWidget(self.balance_slider)

        # Tone
        self.tone_slider = QSlider(Qt.Horizontal)
        self.tone_slider.setRange(-100, 100)
        self.tone_slider.setValue(0)
        layout.addWidget(QLabel("Tone"))
        layout.addWidget(self.tone_slider)

        # Ambient Noise Reduction
        self.anr_slider = QSlider(Qt.Horizontal)
        self.anr_slider.setRange(0, 100)
        self.anr_slider.setValue(0)
        layout.addWidget(QLabel("Ambient Noise Reduction"))
        layout.addWidget(self.anr_slider)

        # Conversation Boost
        self.conv_checkbox = QCheckBox("Conversation Boost")
        layout.addWidget(self.conv_checkbox)

        # Own Voice Amplification
        self.own_voice_slider = QSlider(Qt.Horizontal)
        self.own_voice_slider.setRange(0, 100)
        self.own_voice_slider.setValue(50)
        layout.addWidget(QLabel("Own Voice Amplification"))
        layout.addWidget(self.own_voice_slider)

        # Reset button
        self.reset_button = QPushButton("Reset adjustments")
        layout.addWidget(self.reset_button)

        # Connect signals for ATT settings
        for input_box in self.left_eq_inputs + self.right_eq_inputs:
            input_box.textChanged.connect(self.on_value_changed)
        self.amp_slider.valueChanged.connect(self.on_value_changed)
        self.balance_slider.valueChanged.connect(self.on_value_changed)
        self.tone_slider.valueChanged.connect(self.on_value_changed)
        self.anr_slider.valueChanged.connect(self.on_value_changed)
        self.conv_checkbox.stateChanged.connect(self.on_value_changed)
        self.own_voice_slider.valueChanged.connect(self.on_value_changed)
        self.reset_button.clicked.connect(self.reset_settings)

        self.setLayout(layout)

    def on_connected(self) -> None:
        self.status_label.setText("Connected")
        self.att_manager.register_listener(ATT_HANDLES['HEARING_AID'], self.on_att_notification)
        self.att_manager.register_listener(ATT_HANDLES['LOUD_SOUND_REDUCTION'], self.on_loud_sound_reduction_notification)
        self.aacp_manager.register_control_cmd_listener(ControlCommandId.HEARING_AID, self._on_hearing_aid_cmd)
        self.aacp_manager.register_control_cmd_listener(ControlCommandId.HPS_GAIN_SWIPE, self._on_swipe_cmd)
        self.aacp_manager.register_control_cmd_listener(ControlCommandId.LISTENING_MODE, self._on_listening_mode_cmd)
        asyncio.run_coroutine_threadsafe(self._initial_setup(), self.loop)

    def on_loud_sound_reduction_notification(self, value: bytes) -> None:
        enabled = value[0] == 0x01 if value else False
        self.emitter.update_loud_sound_reduction_toggle.emit(enabled)

    def _set_loud_sound_reduction_toggle(self, enabled: bool):
        self.loud_sound_reduction_checkbox.blockSignals(True)
        self.loud_sound_reduction_checkbox.setChecked(enabled)
        self.loud_sound_reduction_checkbox.blockSignals(False)

    def on_loud_sound_reduction_toggle(self, state: int):
        enabled = state == Qt.Checked
        asyncio.run_coroutine_threadsafe(self._send_loud_sound_reduction_toggle(enabled), self.loop)

    async def _send_loud_sound_reduction_toggle(self, enabled: bool):
        value = bytes([0x01]) if enabled else bytes([0x00])
        await self.att_manager.write('LOUD_SOUND_REDUCTION', value)

    def _on_hearing_aid_cmd(self, value: bytes):
        enabled = value[0] == 0x01 if value else False
        self.emitter.update_hearing_aid_toggle.emit(enabled)

    def _on_swipe_cmd(self, value: bytes):
        enabled = value[0] == 0x01 if value else False
        self.emitter.update_swipe_toggle.emit(enabled)

    def _on_listening_mode_cmd(self, value: bytes):
        mode_value = value[0] if value else 0x01
        index = mode_value - 1 if 1 <= mode_value <= 4 else 0
        self.emitter.update_listening_mode.emit(index)

    def _set_hearing_aid_toggle(self, enabled: bool):
        self.hearing_aid_checkbox.blockSignals(True)
        self.hearing_aid_checkbox.setChecked(enabled)
        self.hearing_aid_checkbox.blockSignals(False)

    def _set_swipe_toggle(self, enabled: bool):
        self.swipe_checkbox.blockSignals(True)
        self.swipe_checkbox.setChecked(enabled)
        self.swipe_checkbox.blockSignals(False)

    def _set_listening_mode(self, index: int):
        self.listening_mode_combo.blockSignals(True)
        self.listening_mode_combo.setCurrentIndex(index)
        self.listening_mode_combo.blockSignals(False)

    def on_hearing_aid_toggle(self, state: int):
        enabled = state == Qt.Checked
        asyncio.run_coroutine_threadsafe(self._send_hearing_aid_toggle(enabled), self.loop)

    def on_swipe_toggle(self, state: int):
        enabled = state == Qt.Checked
        asyncio.run_coroutine_threadsafe(self._send_swipe_toggle(enabled), self.loop)

    def on_listening_mode_changed(self, index: int):
        value = index + 1
        asyncio.run_coroutine_threadsafe(self._send_listening_mode(value), self.loop)

    async def _send_hearing_aid_toggle(self, enabled: bool):
        if enabled:
            await self.aacp_manager.send_control_command(ControlCommandId.HEARING_AID, bytes([0x01, 0x01]))
            await self.aacp_manager.send_control_command(ControlCommandId.HEARING_ASSIST_CONFIG, bytes([0x01]))
        else:
            await self.aacp_manager.send_control_command(ControlCommandId.HEARING_AID, bytes([0x02, 0x02]))
            await self.aacp_manager.send_control_command(ControlCommandId.HEARING_ASSIST_CONFIG, bytes([0x02]))

    async def _send_swipe_toggle(self, enabled: bool):
        value = bytes([0x01]) if enabled else bytes([0x02])
        await self.aacp_manager.send_control_command(ControlCommandId.HPS_GAIN_SWIPE, value)

    async def _send_listening_mode(self, value: int):
        await self.aacp_manager.send_control_command(ControlCommandId.LISTENING_MODE, bytes([value]))

    async def _initial_setup(self):
        try:
            await self.att_manager.enable_notifications('HEARING_AID')
            await self.att_manager.enable_notifications('LOUD_SOUND_REDUCTION')
            data = await self.att_manager.read('HEARING_AID')
            settings = parse_hearing_aid_settings(data)
            if settings:
                self.emitter.update_ui.emit(settings)
                logger.info("Initial ATT settings loaded")
            loud_sound_data = await self.att_manager.read('LOUD_SOUND_REDUCTION')
            loud_sound_enabled = loud_sound_data[0] == 0x01 if loud_sound_data else False
            self.emitter.update_loud_sound_reduction_toggle.emit(loud_sound_enabled)
            logger.info("Initial loud sound reduction setting loaded")
        except Exception as e:
            logger.error(f"Initial ATT setup failed: {e}")

    def on_att_notification(self, value: bytes) -> None:
        settings = parse_hearing_aid_settings(value)
        if settings:
            self.emitter.update_ui.emit(settings)

    def on_update_ui(self, settings: HearingAidSettings) -> None:
        self.amp_slider.blockSignals(True)
        self.balance_slider.blockSignals(True)
        self.tone_slider.blockSignals(True)
        self.anr_slider.blockSignals(True)
        self.conv_checkbox.blockSignals(True)
        self.own_voice_slider.blockSignals(True)

        self.amp_slider.setValue(int(settings.net_amplification * 100))
        self.balance_slider.setValue(int(settings.balance * 100))
        self.tone_slider.setValue(int(settings.left_tone * 100))
        self.anr_slider.setValue(int(settings.left_ambient_noise_reduction * 100))
        self.conv_checkbox.setChecked(settings.left_conversation_boost)
        self.own_voice_slider.setValue(int(settings.own_voice_amplification * 100))

        for i, value in enumerate(settings.left_eq):
            self.left_eq_inputs[i].blockSignals(True)
            self.left_eq_inputs[i].setText(f"{value:.2f}")
            self.left_eq_inputs[i].blockSignals(False)
        for i, value in enumerate(settings.right_eq):
            self.right_eq_inputs[i].blockSignals(True)
            self.right_eq_inputs[i].setText(f"{value:.2f}")
            self.right_eq_inputs[i].blockSignals(False)

        self.amp_slider.blockSignals(False)
        self.balance_slider.blockSignals(False)
        self.tone_slider.blockSignals(False)
        self.anr_slider.blockSignals(False)
        self.conv_checkbox.blockSignals(False)
        self.own_voice_slider.blockSignals(False)

    def on_value_changed(self) -> None:
        self.debounce_timer.start(100)

    def send_settings(self) -> None:
        asyncio.run_coroutine_threadsafe(self._send_settings_async(), self.loop)

    async def _send_settings_async(self) -> None:
        try:
            amp = self.amp_slider.value() / 100.0
            balance = self.balance_slider.value() / 100.0
            tone = self.tone_slider.value() / 100.0
            anr = self.anr_slider.value() / 100.0
            conv = self.conv_checkbox.isChecked()
            own_voice = self.own_voice_slider.value() / 100.0

            left_amp = amp + (0.5 - balance) * amp * 2 if balance < 0 else amp
            right_amp = amp + (balance - 0.5) * amp * 2 if balance > 0 else amp

            left_eq = [float(input_box.text() or 0) for input_box in self.left_eq_inputs]
            right_eq = [float(input_box.text() or 0) for input_box in self.right_eq_inputs]

            settings = HearingAidSettings(
                left_eq, right_eq, left_amp, right_amp, tone, tone,
                conv, conv, anr, anr, amp, balance, own_voice
            )
            await self._send_hearing_aid_settings(settings)
        except Exception as e:
            logger.error(f"Failed to send settings: {e}")

    async def _send_hearing_aid_settings(self, settings: HearingAidSettings) -> None:
        data = await self.att_manager.read('HEARING_AID')
        if len(data) < 104:
            logger.error("Read data too short for sending settings")
            return
        buffer = bytearray(data)
        # buffer[0] = 0x02
        # buffer[1] = 0x02
        buffer[2] = 0x64

        for i in range(8):
            struct.pack_into('<f', buffer, 4 + i * 4, settings.left_eq[i])
        struct.pack_into('<f', buffer, 36, settings.left_amplification)
        struct.pack_into('<f', buffer, 40, settings.left_tone)
        struct.pack_into('<f', buffer, 44, 1.0 if settings.left_conversation_boost else 0.0)
        struct.pack_into('<f', buffer, 48, settings.left_ambient_noise_reduction)

        for i in range(8):
            struct.pack_into('<f', buffer, 52 + i * 4, settings.right_eq[i])
        struct.pack_into('<f', buffer, 84, settings.right_amplification)
        struct.pack_into('<f', buffer, 88, settings.right_tone)
        struct.pack_into('<f', buffer, 92, 1.0 if settings.right_conversation_boost else 0.0)
        struct.pack_into('<f', buffer, 96, settings.right_ambient_noise_reduction)

        struct.pack_into('<f', buffer, 100, settings.own_voice_amplification)

        await self.att_manager.write('HEARING_AID', buffer)
        logger.info("Hearing aid settings sent")

    def reset_settings(self):
        self.amp_slider.setValue(0)
        self.balance_slider.setValue(0)
        self.tone_slider.setValue(0)
        self.anr_slider.setValue(50)
        self.conv_checkbox.setChecked(False)
        self.own_voice_slider.setValue(50)
        self.on_value_changed()

    def closeEvent(self, event) -> None:
        self.att_manager.stop()
        self.aacp_manager.stop()
        event.accept()


# Make sure shutdown event is created earlier
SHUTDOWN_EVENT = threading.Event()

# Add a global shutdown event to signal the async loop to exit cleanly
async def run_bluez(bdaddr: str, att_manager: ATTManager, aacp_manager: AACPManager,
                     app_window: HearingAidApp, shutdown_event: threading.Event = SHUTDOWN_EVENT):
    try:
        import bluetooth
    except ImportError:
        logger.error("PyBluez (bluetooth) not installed. Install it or use --bumble.")
        return 1

    logger.info(f"Connecting to {bdaddr} using bluez sockets...")

    att_channel = None
    aacp_channel = None
    att_listen_task = None
    aacp_listen_task = None
    try:
        # ATT
        att_sock = bluetooth.BluetoothSocket(bluetooth.L2CAP)
        att_sock.connect((bdaddr, 31))
        logger.info("Connected to ATT (PSM 31)")

        # AACP
        aacp_sock = bluetooth.BluetoothSocket(bluetooth.L2CAP)
        aacp_sock.connect((bdaddr, 4097))
        logger.info("Connected to AACP (PSM 4097)")

        loop = asyncio.get_running_loop()

        att_channel = BluezChannel(att_sock, loop)
        att_manager.set_channel(att_channel)

        aacp_channel = BluezChannel(aacp_sock, loop)
        aacp_manager.set_channel(aacp_channel)

        # AACP Setup
        await aacp_manager.send_handshake()
        await asyncio.sleep(0.1)
        await aacp_manager.send_notification_request()
        await asyncio.sleep(0.1)
        await aacp_manager.send_set_feature_flags()
        await asyncio.sleep(0.1)
        await aacp_manager.send_control_command(ControlCommandId.OWNS_CONNECTION, bytes([0x01]))

        app_window.emitter.connected.emit()

        att_listen_task = asyncio.create_task(att_manager.listen_notifications())
        aacp_listen_task = asyncio.create_task(aacp_manager.listen())

        logger.info("BlueZ connection established. UI is now active.")

        try:
            await loop.run_in_executor(None, shutdown_event.wait)
        except asyncio.CancelledError:
            pass
    except Exception as e:
        logger.error(f"BlueZ connection failed: {e}")
        return 1
    finally:
        # Ensure we attempt a clean shutdown
        if att_listen_task:
            att_listen_task.cancel()
            try:
                await att_listen_task
            except asyncio.CancelledError:
                pass
            except Exception:
                pass
        if aacp_listen_task:
            aacp_listen_task.cancel()
            try:
                await aacp_listen_task
            except asyncio.CancelledError:
                pass
            except Exception:
                pass
        if att_channel:
            try:
                att_channel.stop()
            except Exception:
                pass
        if aacp_channel:
            try:
                aacp_channel.stop()
            except Exception:
                pass

    return 0


async def run_bumble(bdaddr: str, att_manager: ATTManager, aacp_manager: AACPManager,
                     app_window: HearingAidApp, shutdown_event: threading.Event = SHUTDOWN_EVENT):
    try:
        from bumble.l2cap import ClassicChannelSpec, ClassicChannel
        from bumble.transport import open_transport
        from bumble.device import Device, Connection
        from bumble.host import Host
        from bumble.core import PhysicalTransport, UUID
        from bumble.pairing import PairingConfig, PairingDelegate
        from bumble.hci import HCI_Error
        from bumble.keys import JsonKeyStore
        from bumble.sdp import ServiceAttribute, DataElement
    except ImportError:
        logger.error("Bumble not installed")
        return 1

    transport = None
    device = None
    connection = None
    att_listen_task = None
    aacp_listen_task = None

    try:
        async def get_device():
            logger.info("Opening transport...")
            transport = await open_transport("usb:0")
            device = Device(host=Host(controller_source=transport.source, controller_sink=transport.sink))
            device.classic_enabled = True
            device.le_enabled = False
            device.keystore = JsonKeyStore.from_device(device, "./keys.json")
            device.pairing_config_factory = lambda conn: PairingConfig(
                sc=True, mitm=False, bonding=True,
                delegate=PairingDelegate(io_capability=PairingDelegate.NO_OUTPUT_NO_INPUT)
            )
            await device.power_on()
            logger.info("Device powered on")

            def on_l2cap_connection(channel: ClassicChannel):
                logger.info("Incoming L2CAP connection on PSM %d", channel.psm)
                async def handle_data():
                    try:
                        reader = _make_reader(channel)
                        while True:
                            data = await reader()
                            print(f"Received PDU on PSM {channel.psm}: {data.hex() if data else 'None'}")
                    except Exception as e:
                        logger.info("L2CAP channel on PSM %d closed: %s", channel.psm, e)
                asyncio.create_task(handle_data())

            att_server_spec = ClassicChannelSpec(psm=31, mtu=512)
            device.create_l2cap_server(att_server_spec, handler=on_l2cap_connection)
            logger.info("L2CAP server registered on PSM 0x%04X", att_server_spec.psm)

            device.sdp_service_records =  {
                    0x4f491200: [
                        ServiceAttribute(0x0000, DataElement.unsigned_integer_32(0x4f491200)),
                        ServiceAttribute(0x0001, DataElement.sequence([DataElement.uuid(UUID.from_16_bits(0x1200))])),
                        ServiceAttribute(0x0002, DataElement.unsigned_integer_32(0x00000000)),
                        ServiceAttribute(0x0005, DataElement.sequence([DataElement.uuid(UUID.from_16_bits(0x1002))])),
                        ServiceAttribute(0x0006, DataElement.sequence([
                            DataElement.unsigned_integer_16(0x656e), DataElement.unsigned_integer_16(0x006a), DataElement.unsigned_integer_16(0x0100),
                            DataElement.unsigned_integer_16(0x6672), DataElement.unsigned_integer_16(0x006a), DataElement.unsigned_integer_16(0x0110),
                            DataElement.unsigned_integer_16(0x6465), DataElement.unsigned_integer_16(0x006a), DataElement.unsigned_integer_16(0x0120),
                            DataElement.unsigned_integer_16(0x6a61), DataElement.unsigned_integer_16(0x006a), DataElement.unsigned_integer_16(0x0130)
                        ])),
                        ServiceAttribute(0x0008, DataElement.unsigned_integer_8(0xff)),
                        ServiceAttribute(0x0101, DataElement.text_string('PnP Information')),
                        ServiceAttribute(0x0200, DataElement.unsigned_integer_16(0x0102)),
                        ServiceAttribute(0x0201, DataElement.unsigned_integer_16(0x004c)),
                        ServiceAttribute(0x0202, DataElement.unsigned_integer_16(0x0000)),
                        ServiceAttribute(0x0203, DataElement.unsigned_integer_16(0x0f60)),
                        ServiceAttribute(0x0204, DataElement.boolean(True)),
                        ServiceAttribute(0x0205, DataElement.unsigned_integer_16(0x0001)),
                        ServiceAttribute(0xa000, DataElement.unsigned_integer_32(0x00a026c4)),
                        ServiceAttribute(0xafff, DataElement.unsigned_integer_16(0x0001))
                    ]
                }

            logger.info("SDP service records set up")

            return transport, device

        async def setup_aacp(conn: Connection):
            spec = ClassicChannelSpec(psm=4097, mtu=2048)
            logger.info("Requesting AACP channel on PSM = 0x%04X", spec.psm)
            if not conn.is_encrypted:
                await conn.encrypt()
                await asyncio.sleep(0.05)
            channel: ClassicChannel = await conn.create_l2cap_channel(spec=spec)
            aacp_manager.set_channel(channel)
            logger.info("AACP channel established")

            await aacp_manager.send_handshake()
            await asyncio.sleep(0.1)
            await aacp_manager.send_notification_request()
            await asyncio.sleep(0.1)
            await aacp_manager.send_set_feature_flags()

            return channel

        async def setup_att(conn: Connection):
            spec = ClassicChannelSpec(psm=31, mtu=512)
            logger.info("Requesting ATT channel on PSM = 0x%04X", spec.psm)
            if not conn.is_encrypted:
                await conn.encrypt()
                await asyncio.sleep(0.05)
            channel: ClassicChannel = await conn.create_l2cap_channel(spec=spec)
            att_manager.set_channel(channel)
            logger.info("ATT channel established")
            return channel

        transport, device = await get_device()
        logger.info("Connecting to %s (BR/EDR)...", bdaddr)
        connection = await device.connect(bdaddr, PhysicalTransport.BR_EDR)
        logger.info("Connected to %s (handle %s)", connection.peer_address, connection.handle)
        logger.info("Authenticating...")
        await connection.authenticate()
        if not connection.is_encrypted:
            logger.info("Encrypting link...")
            await connection.encrypt()

        await setup_aacp(connection)
        await setup_att(connection)

        app_window.emitter.connected.emit()

        att_listen_task = asyncio.create_task(att_manager.listen_notifications())
        aacp_listen_task = asyncio.create_task(aacp_manager.listen())

        logger.info("Connection established. UI is now active.")
        try:
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(None, shutdown_event.wait)
        except asyncio.CancelledError:
            pass
    except HCI_Error as e:
        if "PAIRING_NOT_ALLOWED_ERROR" in str(e):
            logger.error("Put your device into pairing mode and run the script again")
        else:
            logger.error("HCI error: %s", e)
    except Exception as e:
        logger.error("Unexpected error: %s", e)
    finally:
        logger.info("Shutting down bumble connection...")
        # Cancel and await listening tasks
        if att_listen_task:
            att_listen_task.cancel()
            try:
                await att_listen_task
            except asyncio.CancelledError:
                pass
            except Exception:
                pass
        if aacp_listen_task:
            aacp_listen_task.cancel()
            try:
                await aacp_listen_task
            except asyncio.CancelledError:
                pass
            except Exception:
                pass

        # Attempt to cleanly disconnect the remote device
        if connection:
            try:
                await connection.disconnect()
            except Exception:
                pass

        if transport:
            logger.info("Closing transport...")
            try:
                await transport.close()
            except Exception:
                pass
            logger.info("Transport closed")
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("bdaddr", help="Bluetooth address of the hearing aid device")
    parser.add_argument("--debug", action="store_true", help="Enable debug logging")
    parser.add_argument("--bumble", action="store_true", help="Force use of Bumble stack (default on Windows)")
    args = parser.parse_args()
    logging.getLogger().setLevel(logging.DEBUG if args.debug else logging.INFO)

    qt_app = QApplication(sys.argv)
    loop = asyncio.new_event_loop()

    att_manager = ATTManager()
    aacp_manager = AACPManager()

    window = HearingAidApp(att_manager, aacp_manager, loop)
    window.show()

    def quit_app(signum, frame):
        # Signal the application shutdown to both main thread and the async thread
        SHUTDOWN_EVENT.set()
        try:
            att_manager.stop()
            aacp_manager.stop()
        except Exception:
            pass
        qt_app.quit()

    signal.signal(signal.SIGINT, quit_app)
    signal.signal(signal.SIGTERM, quit_app)
    
    def run_async():
        asyncio.set_event_loop(loop)
        use_bumble = args.bumble or platform.system() == "Windows"
        try:
            if use_bumble:
                loop.run_until_complete(run_bumble(args.bdaddr, att_manager, aacp_manager, window, SHUTDOWN_EVENT))
            else:
                import subprocess
                ps_output = subprocess.run(["ps", "-A"], capture_output=True, text=True).stdout
                if "librepods" in ps_output:
                    logger.error("LibrePods is running. Please close it before using this script.")
                    loop.call_soon_threadsafe(loop.stop)
                    quit_app(None, None)
                    return
                loop.run_until_complete(
                    run_bluez(args.bdaddr, att_manager, aacp_manager, window, SHUTDOWN_EVENT)
                )
        except Exception as e:
            logger.error("Async thread error: %s", e)
        finally:
            loop.call_soon_threadsafe(loop.stop)

    # Keep the async thread non-daemon so cleanup can run
    async_thread = threading.Thread(target=run_async, daemon=False)
    async_thread.start()

    timer = QTimer()
    timer.timeout.connect(lambda: None)
    timer.start(100)

    # Run GUI and wait for async thread cleanup
    exit_code = qt_app.exec_()

    # Ensure shutdown is signaled (in case user closed the window)
    SHUTDOWN_EVENT.set()

    # Wait for async thread to finish gracefully for a short timeout
    async_thread.join(timeout=10)

    sys.exit(exit_code)


if __name__ == "__main__":
    main()
