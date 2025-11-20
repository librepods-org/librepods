import logging
import signal
import socket
import struct
import sys
import threading
from socket import socket as Socket, TimeoutError
from queue import Queue
from threading import Thread
from typing import Any, Dict, List, Optional

# Configure logging
logging.basicConfig(level=logging.DEBUG, format='%(asctime)s - %(levelname)s - %(message)s')

from PyQt5.QtWidgets import QApplication, QWidget, QVBoxLayout, QHBoxLayout, QLabel, QSlider, QCheckBox, QPushButton, QLineEdit, QFormLayout, QGridLayout
from PyQt5.QtCore import Qt, QTimer, pyqtSignal, QObject

OPCODE_READ_REQUEST: int = 0x0A
OPCODE_WRITE_REQUEST: int = 0x12
OPCODE_HANDLE_VALUE_NTF: int = 0x1B

ATT_HANDLES: Dict[str, int] = {
    'TRANSPARENCY': 0x18,
    'LOUD_SOUND_REDUCTION': 0x1B,
    'HEARING_AID': 0x2A,
}

ATT_CCCD_HANDLES: Dict[str, int] = {
    'TRANSPARENCY': ATT_HANDLES['TRANSPARENCY'] + 1,
    'LOUD_SOUND_REDUCTION': ATT_HANDLES['LOUD_SOUND_REDUCTION'] + 1,
    'HEARING_AID': ATT_HANDLES['HEARING_AID'] + 1,
}

PSM_ATT: int = 31

class ATTManager:
    def __init__(self, mac_address: str) -> None:
        self.mac_address: str = mac_address
        self.sock: Optional[Socket] = None
        self.responses: Queue = Queue()
        self.listeners: Dict[int, List[Any]] = {}
        self.notification_thread: Optional[Thread] = None
        self.running: bool = False
        # Avoid logging full MAC address to prevent sensitive data exposure
        mac_tail: str = ':'.join(mac_address.split(':')[-2:]) if isinstance(mac_address, str) and ':' in mac_address else '[redacted]'
        logging.info(f"ATTManager initialized")

    def connect(self) -> None:
        logging.info("Attempting to connect to ATT socket")
        self.sock = Socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
        self.sock.connect((self.mac_address, PSM_ATT))
        self.sock.settimeout(0.1)
        self.running = True
        self.notification_thread = Thread(target=self._listen_notifications)
        self.notification_thread.start()
        logging.info("Connected to ATT socket")

    def disconnect(self) -> None:
        logging.info("Disconnecting from ATT socket")
        self.running = False
        if self.sock:
            logging.info("Closing socket")
            self.sock.close()
        if self.notification_thread:
            logging.info("Stopping notification thread")
            self.notification_thread.join(timeout=1.0)
        logging.info("Disconnected from ATT socket")

    def register_listener(self, handle: int, listener: Any) -> None:
        if handle not in self.listeners:
            self.listeners[handle] = []
        self.listeners[handle].append(listener)
        logging.debug(f"Registered listener for handle {handle}")

    def unregister_listener(self, handle: int, listener: Any) -> None:
        if handle in self.listeners:
            self.listeners[handle].remove(listener)
            logging.debug(f"Unregistered listener for handle {handle}")

    def enable_notifications(self, handle: Any) -> None:
        self.write_cccd(handle, b'\x01\x00')
        logging.info(f"Enabled notifications for handle {handle.name}")

    def read(self, handle: Any) -> bytes:
        handle_value: int = ATT_HANDLES[handle.name]
        lsb: int = handle_value & 0xFF
        msb: int = (handle_value >> 8) & 0xFF
        pdu: bytes = bytes([OPCODE_READ_REQUEST, lsb, msb])
        logging.debug(f"Sending read request for handle {handle.name}: {pdu.hex()}")
        self._write_raw(pdu)
        response: bytes = self._read_response()
        logging.debug(f"Read response for handle {handle.name}: {response.hex()}")
        return response

    def write(self, handle: Any, value: bytes) -> None:
        handle_value: int = ATT_HANDLES[handle.name]
        lsb: int = handle_value & 0xFF
        msb: int = (handle_value >> 8) & 0xFF
        pdu: bytes = bytes([OPCODE_WRITE_REQUEST, lsb, msb]) + value
        logging.debug(f"Sending write request for handle {handle.name}: {pdu.hex()}")
        self._write_raw(pdu)
        try:
            self._read_response()
            logging.debug(f"Write response received for handle {handle.name}")
        except:
            logging.warning(f"No write response received for handle {handle.name}")

    def write_cccd(self, handle: Any, value: bytes) -> None:
        handle_value: int = ATT_CCCD_HANDLES[handle.name]
        lsb: int = handle_value & 0xFF
        msb: int = (handle_value >> 8) & 0xFF
        pdu: bytes = bytes([OPCODE_WRITE_REQUEST, lsb, msb]) + value
        logging.debug(f"Sending CCCD write request for handle {handle.name}: {pdu.hex()}")
        self._write_raw(pdu)
        try:
            self._read_response()
            logging.debug(f"CCCD write response received for handle {handle.name}")
        except:
            logging.warning(f"No CCCD write response received for handle {handle.name}")

    def _write_raw(self, pdu: bytes) -> None:
        self.sock.send(pdu)
        logging.debug(f"Sent PDU: {pdu.hex()}")

    def _read_pdu(self) -> Optional[bytes]:
        try:
            data: bytes = self.sock.recv(512)
            logging.debug(f"Received PDU: {data.hex()}")
            return data
        except TimeoutError:
            return None
        except:
            raise

    def _read_response(self, timeout: float = 2.0) -> bytes:
        try:
            response: bytes = self.responses.get(timeout=timeout)[1:]  # Skip opcode
            logging.debug(f"Response received: {response.hex()}")
            return response
        except:
            logging.error("No response received within timeout")
            raise Exception("No response received")

    def _listen_notifications(self) -> None:
        logging.info("Starting notification listener thread")
        while self.running:
            try:
                pdu: Optional[bytes] = self._read_pdu()
            except:
                break
            if pdu is None:
                continue
            if len(pdu) > 0 and pdu[0] == OPCODE_HANDLE_VALUE_NTF:
                logging.debug(f"Notification PDU received: {pdu.hex()}")
                handle: int = pdu[1] | (pdu[2] << 8)
                value: bytes = pdu[3:]
                logging.debug(f"Notification for handle {handle}: {value.hex()}")
                if handle in self.listeners:
                    for listener in self.listeners[handle]:
                        listener(value)
            else:
                self.responses.put(pdu)
        logging.info("Notification listener thread stopped, trying to reconnect")
        if self.running:
            try:
                self.connect()
            except Exception as e:
                logging.error(f"Reconnection failed: {e}")

class HearingAidSettings:
    def __init__(self, left_eq: List[float], right_eq: List[float], left_amp: float, right_amp: float, left_tone: float, right_tone: float,
                 left_conv: bool, right_conv: bool, left_anr: float, right_anr: float, net_amp: float, balance: float, own_voice: float) -> None:
        self.left_eq: List[float] = left_eq
        self.right_eq: List[float] = right_eq
        self.left_amplification: float = left_amp
        self.right_amplification: float = right_amp
        self.left_tone: float = left_tone
        self.right_tone: float = right_tone
        self.left_conversation_boost: bool = left_conv
        self.right_conversation_boost: bool = right_conv
        self.left_ambient_noise_reduction: float = left_anr
        self.right_ambient_noise_reduction: float = right_anr
        self.net_amplification: float = net_amp
        self.balance: float = balance
        self.own_voice_amplification: float = own_voice
        logging.debug(f"HearingAidSettings created: amp={net_amp}, balance={balance}, tone={left_tone}, anr={left_anr}, conv={left_conv}")

def parse_hearing_aid_settings(data: bytes) -> Optional[HearingAidSettings]:
    logging.debug(f"Parsing hearing aid settings from data: {data.hex()}")
    if len(data) < 104:
        logging.warning("Data too short for parsing")
        return None
    buffer: bytes = data
    offset: int = 0

    offset += 4
    
    logging.info(f"Parsing hearing aid settings, starting read at offset 4, value: {buffer[offset]:02x}")

    left_eq: List[float] = []
    for i in range(8):
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

    avg: float = (left_amp + right_amp) / 2
    amplification: float = max(-1, min(1, avg))
    diff: float = right_amp - left_amp
    balance: float = max(-1, min(1, diff))

    settings: HearingAidSettings = HearingAidSettings(left_eq, right_eq, left_amp, right_amp, left_tone, right_tone,
                              left_conv, right_conv, left_anr, right_anr, amplification, balance, own_voice)
    logging.info(f"Parsed settings: amp={amplification}, balance={balance}")
    return settings

def send_hearing_aid_settings(att_manager: ATTManager, settings: HearingAidSettings) -> None:
    logging.info("Sending hearing aid settings")
    data: bytes = att_manager.read(type('Handle', (), {'name': 'HEARING_AID'})())
    if len(data) < 104:
        logging.error("Read data too short for sending settings")
        return
    buffer: bytearray = bytearray(data)

    # Modify byte at index 2 to 0x64
    buffer[2] = 0x64

    # Left ear
    for i in range(8):
        struct.pack_into('<f', buffer, 4 + i * 4, settings.left_eq[i])
    struct.pack_into('<f', buffer, 36, settings.left_amplification)
    struct.pack_into('<f', buffer, 40, settings.left_tone)
    struct.pack_into('<f', buffer, 44, 1.0 if settings.left_conversation_boost else 0.0)
    struct.pack_into('<f', buffer, 48, settings.left_ambient_noise_reduction)

    # Right ear
    for i in range(8):
        struct.pack_into('<f', buffer, 52 + i * 4, settings.right_eq[i])
    struct.pack_into('<f', buffer, 84, settings.right_amplification)
    struct.pack_into('<f', buffer, 88, settings.right_tone)
    struct.pack_into('<f', buffer, 92, 1.0 if settings.right_conversation_boost else 0.0)
    struct.pack_into('<f', buffer, 96, settings.right_ambient_noise_reduction)

    # Own voice
    struct.pack_into('<f', buffer, 100, settings.own_voice_amplification)

    att_manager.write(type('Handle', (), {'name': 'HEARING_AID'})(), buffer)
    logging.info("Hearing aid settings sent")

class SignalEmitter(QObject):
    update_ui: pyqtSignal = pyqtSignal(HearingAidSettings)

class HearingAidApp(QWidget):
    def __init__(self, mac_address: str) -> None:
        super().__init__()
        self.mac_address: str = mac_address
        self.att_manager: ATTManager = ATTManager(mac_address)
        self.emitter: SignalEmitter = SignalEmitter()
        self.emitter.update_ui.connect(self.on_update_ui)
        self.debounce_timer: QTimer = QTimer()
        self.debounce_timer.setSingleShot(True)
        self.debounce_timer.timeout.connect(self.send_settings)
        logging.info("HearingAidConfig initialized")

        self.init_ui()
        self.connect_att()

    def init_ui(self) -> None:
        logging.debug("Initializing UI")
        self.setWindowTitle("Hearing Aid Adjustments")
        layout: QVBoxLayout = QVBoxLayout()

        # EQ Inputs
        eq_layout: QGridLayout = QGridLayout()
        self.left_eq_inputs: List[QLineEdit] = []
        self.right_eq_inputs: List[QLineEdit] = []

        eq_labels: List[str] = ["250Hz", "500Hz", "1kHz", "2kHz", "3kHz", "4kHz", "6kHz", "8kHz"]
        eq_layout.addWidget(QLabel("Frequency"), 0, 0)
        eq_layout.addWidget(QLabel("Left"), 0, 1)
        eq_layout.addWidget(QLabel("Right"), 0, 2)

        for i, label in enumerate(eq_labels):
            eq_layout.addWidget(QLabel(label), i + 1, 0)
            left_input: QLineEdit = QLineEdit()
            right_input: QLineEdit = QLineEdit()
            left_input.setPlaceholderText("Left")
            right_input.setPlaceholderText("Right")
            self.left_eq_inputs.append(left_input)
            self.right_eq_inputs.append(right_input)
            eq_layout.addWidget(left_input, i + 1, 1)
            eq_layout.addWidget(right_input, i + 1, 2)

        eq_group: QWidget = QWidget()
        eq_group.setLayout(eq_layout)
        layout.addWidget(QLabel("Loss, in dBHL"))
        layout.addWidget(eq_group)

        # Amplification
        self.amp_slider: QSlider = QSlider(Qt.Horizontal)
        self.amp_slider.setRange(-100, 100)
        self.amp_slider.setValue(50)
        layout.addWidget(QLabel("Amplification"))
        layout.addWidget(self.amp_slider)

        # Balance
        self.balance_slider: QSlider = QSlider(Qt.Horizontal)
        self.balance_slider.setRange(-100, 100)
        self.balance_slider.setValue(50)
        layout.addWidget(QLabel("Balance"))
        layout.addWidget(self.balance_slider)

        # Tone
        self.tone_slider: QSlider = QSlider(Qt.Horizontal)
        self.tone_slider.setRange(-100, 100)
        self.tone_slider.setValue(50)
        layout.addWidget(QLabel("Tone"))
        layout.addWidget(self.tone_slider)

        # Ambient Noise Reduction
        self.anr_slider: QSlider = QSlider(Qt.Horizontal)
        self.anr_slider.setRange(0, 100)
        self.anr_slider.setValue(0)
        layout.addWidget(QLabel("Ambient Noise Reduction"))
        layout.addWidget(self.anr_slider)

        # Conversation Boost
        self.conv_checkbox: QCheckBox = QCheckBox("Conversation Boost")
        layout.addWidget(self.conv_checkbox)

        # Own Voice Amplification
        self.own_voice_slider: QSlider = QSlider(Qt.Horizontal)
        self.own_voice_slider.setRange(0, 100)
        self.own_voice_slider.setValue(50)
        # layout.addWidget(QLabel("Own Voice Amplification"))
        # layout.addWidget(self.own_voice_slider) # seems to have no effect
        
        # Reset button
        self.reset_button: QPushButton = QPushButton("Reset")
        layout.addWidget(self.reset_button)

        # Connect signals
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
        logging.debug("UI initialized")

    def connect_att(self) -> None:
        logging.info("Connecting to ATT in UI")
        try:
            self.att_manager.connect()
            self.att_manager.enable_notifications(type('Handle', (), {'name': 'HEARING_AID'})())
            self.att_manager.register_listener(ATT_HANDLES['HEARING_AID'], self.on_notification)
            # Initial read
            data: bytes = self.att_manager.read(type('Handle', (), {'name': 'HEARING_AID'})())
            settings: Optional[HearingAidSettings] = parse_hearing_aid_settings(data)
            if settings:
                self.emitter.update_ui.emit(settings)
                logging.info("Initial settings loaded")
        except Exception as e:
            if e.errno == 111:
                logging.error("Connection refused. Try reconnecting your AirPods.")
                sys.exit(1)
            else:
                logging.error(f"Connection failed: {e}")

    def on_notification(self, value: bytes) -> None:
        logging.debug("Notification received")
        settings: Optional[HearingAidSettings] = parse_hearing_aid_settings(value)
        if settings:
            self.emitter.update_ui.emit(settings)

    def on_update_ui(self, settings: HearingAidSettings) -> None:
        logging.debug("Updating UI with settings")
        self.amp_slider.setValue(int(settings.net_amplification * 100))
        self.balance_slider.setValue(int(settings.balance * 100))
        self.tone_slider.setValue(int(settings.left_tone * 100))
        self.anr_slider.setValue(int(settings.left_ambient_noise_reduction * 100))
        self.conv_checkbox.setChecked(settings.left_conversation_boost)
        self.own_voice_slider.setValue(int(settings.own_voice_amplification * 100))

        for i, value in enumerate(settings.left_eq):
            self.left_eq_inputs[i].setText(f"{value:.2f}")
        for i, value in enumerate(settings.right_eq):
            self.right_eq_inputs[i].setText(f"{value:.2f}")

    def on_value_changed(self) -> None:
        logging.debug("UI value changed, starting debounce")
        self.debounce_timer.start(100)

    def send_settings(self) -> None:
        logging.info("Sending settings from UI")
        amp: float = self.amp_slider.value() / 100.0
        balance: float = self.balance_slider.value() / 100.0
        tone: float = self.tone_slider.value() / 100.0
        anr: float = self.anr_slider.value() / 100.0
        conv: bool = self.conv_checkbox.isChecked()
        own_voice: float = self.own_voice_slider.value() / 100.0

        left_amp: float = amp + (0.5 - balance) * amp * 2 if balance < 0 else amp
        right_amp: float = amp + (balance - 0.5) * amp * 2 if balance > 0 else amp

        left_eq: List[float] = [float(input_box.text() or 0) for input_box in self.left_eq_inputs]
        right_eq: List[float] = [float(input_box.text() or 0) for input_box in self.right_eq_inputs]

        settings: HearingAidSettings = HearingAidSettings(
            left_eq, right_eq, left_amp, right_amp, tone, tone,
            conv, conv, anr, anr, amp, balance, own_voice
        )
        Thread(target=send_hearing_aid_settings, args=(self.att_manager, settings)).start()

    def reset_settings(self):
        logging.debug("Resetting settings to defaults")
        self.amp_slider.setValue(0)
        self.balance_slider.setValue(0)
        self.tone_slider.setValue(0)
        self.anr_slider.setValue(50)
        self.conv_checkbox.setChecked(False)
        self.own_voice_slider.setValue(50)
        self.on_value_changed()

    def closeEvent(self, event: Any) -> None:
        logging.info("Closing app")
        self.att_manager.disconnect()
        event.accept()

if __name__ == "__main__":
    if len(sys.argv) != 2:
        logging.error("Usage: python hearing-aid-adjustments.py <MAC_ADDRESS>")
        sys.exit(1)
    mac: str = sys.argv[1]
    mac_regex: str = r'^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$'
    import re
    if not re.match(mac_regex, mac):
        logging.error("Invalid MAC address format")
        sys.exit(1)
    logging.info(f"Starting app")
    app: QApplication = QApplication(sys.argv)
    
    def quit_app(signum: int, frame: Any) -> None:
        app.quit()
    
    signal.signal(signal.SIGINT, quit_app)
    
    window = HearingAidApp(mac)
    window.show()
    sys.exit(app.exec_())
