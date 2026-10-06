#!/usr/bin/env python3

# Needs https://github.com/google/bumble on Windows
# See https://github.com/google/bumble/blob/main/docs/mkdocs/src/platforms/windows.md for usage.
# You need to associate WinUSB with your Bluetooth interface. Once done, you can roll back to the original driver from Device Manager.

import asyncio
import colorama
import logging
import platform
from argparse import ArgumentParser, Namespace
from asyncio import Queue, TimeoutError
from colorama import Fore, Style
from logging import Formatter, LogRecord, Logger, StreamHandler
from socket import socket as Socket
from typing import Any, Dict, List, Optional, Tuple

colorama.init(autoreset=True)

handler: StreamHandler = StreamHandler()
class ColorFormatter(Formatter):
    COLORS: Dict[int, str] = {
        logging.DEBUG: Fore.BLUE,
        logging.INFO: Fore.GREEN,
        logging.WARNING: Fore.YELLOW,
        logging.ERROR: Fore.RED,
        logging.CRITICAL: Fore.MAGENTA,
    }
    def format(self, record: LogRecord) -> str:
        color: str = self.COLORS.get(record.levelno, "")
        prefix: str = f"{color}[{record.levelname}:{record.name}]{Style.RESET_ALL}"
        return f"{prefix} {record.getMessage()}"
handler.setFormatter(ColorFormatter())
logging.basicConfig(level=logging.INFO, handlers=[handler])
logger: Logger = logging.getLogger("proximitykeys")

PROXIMITY_KEY_TYPES: Dict[int, str]  = {0x01: "IRK", 0x04: "ENC_KEY"}

def parse_proximity_keys_response(data: bytes) -> Optional[List[Tuple[str, bytes]]]:
    if len(data) < 7 or data[4] != 0x31:
        return None
    key_count: int = data[6]
    keys: List[Tuple[str, bytes]] = []
    offset: int = 7
    for _ in range(key_count):
        if offset + 3 >= len(data):
            break
        key_type: int = data[offset]
        key_length: int = data[offset + 2]
        offset += 4
        if offset + key_length > len(data):
            break
        key_bytes: bytes = data[offset:offset + key_length]
        keys.append((PROXIMITY_KEY_TYPES.get(key_type, f"TYPE_{key_type:02X}"), key_bytes))
        offset += key_length
    return keys

def hexdump(data: bytes) -> str:
    return " ".join(f"{b:02X}" for b in data)

async def run_bumble(bdaddr: str) -> int:
    try:
        from bumble.l2cap import ClassicChannelSpec
        from bumble.transport import open_transport
        from bumble.device import Device
        from bumble.host import Host
        from bumble.core import PhysicalTransport
        from bumble.pairing import PairingConfig, PairingDelegate
        from bumble.hci import HCI_Error
    except ImportError:
        logger.error("Bumble not installed")
        return 1

    PSM_PROXIMITY: int = 0x1001
    HANDSHAKE: bytes = bytes.fromhex("00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00")
    KEY_REQ: bytes = bytes.fromhex("04 00 04 00 30 00 05 00")

    class KeyStore:
        async def delete(self, name: str) -> None:
            pass
        async def update(self, name: str, keys: Any) -> None:
            pass
        async def get(self, _name: str) -> Optional[Any]:
            return None
        async def get_all(self) -> List[Tuple[str, Any]]:
            return []

        async def get_resolving_keys(self) -> List[Tuple[bytes, Any]]:
            all_keys: List[Tuple[str, Any]] = await self.get_all()
            resolving_keys: List[Tuple[bytes, Any]] = []
            for name, keys in all_keys:
                if getattr(keys, "irk", None) is not None:
                    resolving_keys.append((
                        keys.irk.value,
                        getattr(keys, "address", "UNKNOWN")
                    ))
            return resolving_keys

    async def exchange_keys(channel: Any, timeout: float = 5.0) -> Optional[List[Tuple[str, bytes]]]:
        recv_q: Queue = Queue()
        channel.sink = lambda sdu: recv_q.put_nowait(sdu)
        logger.info("Sending handshake packet...")
        channel.send_pdu(HANDSHAKE)
        await asyncio.sleep(0.5)
        logger.info("Sending key request packet...")
        channel.send_pdu(KEY_REQ)
        while True:
            try:
                pkt: bytes = await asyncio.wait_for(recv_q.get(), timeout)
            except TimeoutError:
                logger.error("Timed out waiting for SDU response")
                return None
            logger.debug("Received SDU (%d bytes): %s", len(pkt), hexdump(pkt))
            keys: Optional[List[Tuple[str, bytes]]] = parse_proximity_keys_response(pkt)
            if keys:
                return keys

    async def get_device() -> Tuple[Any, Device]:
        logger.info("Opening transport...")
        transport: Any = await open_transport("usb:0")
        device: Device = Device(host=Host(controller_source=transport.source, controller_sink=transport.sink))
        device.classic_enabled = True
        device.le_enabled = False
        device.keystore = KeyStore()
        device.pairing_config_factory = lambda conn: PairingConfig(
            sc=True, mitm=False, bonding=True,
            delegate=PairingDelegate(io_capability=PairingDelegate.NO_OUTPUT_NO_INPUT)
        )
        await device.power_on()
        logger.info("Device powered on")
        return transport, device

    async def create_channel_and_exchange(conn: Any) -> None:
        spec: ClassicChannelSpec = ClassicChannelSpec(psm=PSM_PROXIMITY, mtu=2048)
        logger.info("Requesting L2CAP channel on PSM = 0x%04X", spec.psm)
        if not conn.is_encrypted:
            logger.info("Enabling link encryption...")
            await conn.encrypt()
            await asyncio.sleep(0.05)
        channel: Any = await conn.create_l2cap_channel(spec=spec)
        keys: Optional[List[Tuple[str, bytes]]] = await exchange_keys(channel, timeout=8.0)
        if not keys:
            logger.warning("No proximity keys found")
            return
        logger.info("Keys successfully retrieved")
        print(f"{Fore.CYAN}{Style.BRIGHT}Proximity Keys:{Style.RESET_ALL}")
        for name, key_bytes in keys:
            print(f"  {Fore.MAGENTA}{name}{Style.RESET_ALL}: {hexdump(key_bytes)}")

    transport, device = await get_device()
    logger.info("Connecting to %s (BR/EDR)...", bdaddr)
    try:
        connection = await device.connect(bdaddr, PhysicalTransport.BR_EDR)
        logger.info("Connected to %s (handle %s)", connection.peer_address, connection.handle)
        logger.info("Authenticating...")
        await connection.authenticate()
        if not connection.is_encrypted:
            logger.info("Encrypting link...")
            await connection.encrypt()
        await create_channel_and_exchange(connection)
    except HCI_Error as e:
        if "PAIRING_NOT_ALLOWED_ERROR" in str(e):
            logger.error("Put your device into pairing mode and run the script again")
        else:
            logger.error("HCI error: %s", e)
    except Exception as e:
        logger.error("Unexpected error: %s", e)
    finally:
        if hasattr(transport, "close"):
            logger.info("Closing transport...")
            await transport.close()
        logger.info("Transport closed")
    return 0

def run_linux(bdaddr: str) -> None:
    import socket
    PSM: int = 0x1001
    handshake: bytes = bytes.fromhex("00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00")
    key_req: bytes = bytes.fromhex("04 00 04 00 30 00 05 00")

    logger.info("Connecting to %s (L2CAP)...", bdaddr)
    sock: Socket = Socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
    try:
        sock.connect((bdaddr, PSM))
        logger.info("Connected, sending handshake and key request...")
        sock.send(handshake)
        sock.send(key_req)

        while True:
            pkt: bytes = sock.recv(1024)
            logger.debug("Received packet (%d bytes): %s", len(pkt), hexdump(pkt))
            keys: Optional[List[Tuple[str, bytes]]] = parse_proximity_keys_response(pkt)
            if keys:
                logger.info("Keys successfully retrieved")
                print(f"{Fore.CYAN}{Style.BRIGHT}Proximity Keys:{Style.RESET_ALL}")
                for name, key_bytes in keys:
                    print(f"  {Fore.MAGENTA}{name}{Style.RESET_ALL}: {hexdump(key_bytes)}")
                break
            else:
                logger.warning("Received packet did not contain keys, waiting...")
    except Exception as e:
        logger.error("Error during L2CAP exchange: %s", e)
    finally:
        sock.close()
        logger.info("Connection closed")

def main() -> None:
    parser: ArgumentParser = ArgumentParser()
    parser.add_argument("bdaddr")
    parser.add_argument("--debug", action="store_true")
    parser.add_argument("--bumble", action="store_true")
    args: Namespace = parser.parse_args()
    logging.getLogger().setLevel(logging.DEBUG if args.debug else logging.INFO)

    if args.bumble or platform.system() == "Windows":
        asyncio.run(run_bumble(args.bdaddr))
    else:
        run_linux(args.bdaddr)

if __name__ == "__main__":
    main()
