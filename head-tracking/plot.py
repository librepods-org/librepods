import asciichartpy as acp
import logging
import matplotlib.pyplot as plt
import numpy as np
import os
import struct
import time
from bluetooth import BluetoothSocket
from colors import *
from connection_manager import ConnectionManager
from datetime import datetime as DateTime
from drawille import Canvas
from head_orientation import HeadOrientation
from logging import Logger, StreamHandler
from matplotlib.animation import FuncAnimation
from matplotlib.legend import Legend
from matplotlib.pyplot import Axes, Figure
from numpy.typing import NDArray
from rich.live import Live
from rich.layout import Layout
from rich.panel import Panel
from rich.console import Console
from threading import Lock, Thread
from typing import Any, Dict, List, Optional, TextIO, Tuple, Union

handler: StreamHandler = StreamHandler()
handler.setFormatter(ColorFormatter())
logger: Logger = logging.getLogger("airpods-head-tracking")
logger.setLevel(logging.INFO)
logger.addHandler(handler)
logger.propagate = True

INIT_CMD: str = "00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00"
NOTIF_CMD: str = "04 00 04 00 0F 00 FF FF FE FF"
START_CMD: str = "04 00 04 00 17 00 00 00 10 00 10 00 08 A1 02 42 0B 08 0E 10 02 1A 05 01 40 9C 00 00"
STOP_CMD: str = "04 00 04 00 17 00 00 00 10 00 11 00 08 7E 10 02 42 0B 08 4E 10 02 1A 05 01 00 00 00 00"

KEY_FIELDS: Dict[str, Tuple[int, int]] = {
    "orientation 1": (43, 2),
    "orientation 2": (45, 2),
    "orientation 3": (47, 2),

    "Horizontal Acceleration": (51, 2),
    "Vertical Acceleration": (53, 2),

    "unkown 1": (61, 2),
    "unkown 2 ": (49, 2),
}

class AirPodsTracker:
    def __init__(self) -> None:
        self.sock: BluetoothSocket = None
        self.recording: bool = False
        self.log_file: Optional[TextIO] = None
        self.listener_thread: Optional[Thread] = None
        self.bt_addr: str = "28:2D:7F:C2:05:5B"
        self.psm: int = 0x1001
        self.raw_packets: List[bytes] = []
        self.parsed_packets: List[bytes] = []
        self.live_data: List[bytes] = []
        self.live_plotting: bool = False
        self.animation: FuncAnimation = None
        self.fig: Optional[Figure] = None
        self.axes: Optional[Axes] = None
        self.lines: Dict[str, Any] = {}
        self.selected_fields: List[str] = []
        self.data_lock: Lock = Lock()
        self.orientation_offset: int = 5500
        self.use_terminal: bool = True # '--terminal' in sys.argv
        self.orientation_visualizer: HeadOrientation = HeadOrientation(use_terminal=self.use_terminal)

        self.conn: Optional[ConnectionManager] = None

    def connect(self):
        try:
            logger.info("Trying to connect to %s on PSM 0x%04X...", self.bt_addr, self.psm)
            self.conn = ConnectionManager(self.bt_addr, self.psm, logger=logger)
            if not self.conn.connect():
                logger.error("Connection failed via ConnectionManager.")
                return False
            self.sock = self.conn.sock
            self.sock.send(bytes.fromhex(NOTIF_CMD))
            logger.info("Sent initialization command.")

            self.listener_thread = Thread(target=self.listen, daemon=True)
            self.listener_thread.start()
            return True
        except Exception as e:
            logger.error("Connection error: %s", e)
            return False

    def start_tracking(self, duration: Optional[float] = None) -> None:
        if not self.recording:
            self.conn.send_start()
            filename: str = f"head_tracking_{DateTime.now().strftime('%Y%m%d_%H%M%S')}.log"
            self.log_file = open(filename, "w")
            self.recording = True
            logger.info("Recording started. Saving data to %s", filename)

            if duration is not None and duration > 0:
                def auto_stop() -> None:
                    time.sleep(duration)
                    if self.recording:
                        self.stop_tracking()
                        logger.info("Recording automatically stopped after %s seconds.", duration)

                timer_thread = Thread(target=auto_stop, daemon=True)
                timer_thread.start()
                logger.info("Will automatically stop recording after %s seconds.", duration)
        else:
            logger.info("Already recording.")

    def stop_tracking(self) -> None:
        if self.recording:
            self.conn.send_stop()
            self.recording = False
            if self.log_file is not None:
                self.log_file.close()
                self.log_file = None
            logger.info("Recording stopped.")
        else:
            logger.info("Not currently recording.")

    def format_hex(self, data: bytes) -> str:
        hex_str: str = data.hex()
        return ' '.join(hex_str[i:i + 2] for i in range(0, len(hex_str), 2))

    def parse_raw_packet(self, hex_string: str) -> bytes:
        return bytes.fromhex(hex_string.replace(" ", ""))

    def interpret_bytes(self, raw_bytes: bytes, start: int, length: int, data_type: str = "signed_short") -> Optional[Union[int, float]]:
        if start + length > len(raw_bytes):
            return None

        match data_type:
            case "signed_short":
                return int.from_bytes(raw_bytes[start:start + 2], byteorder='little', signed=True)
            case "unsigned_short":
                return int.from_bytes(raw_bytes[start:start + 2], byteorder='little', signed=False)
            case "signed_short_be":
                return int.from_bytes(raw_bytes[start:start + 2], byteorder='big', signed=True)
            case "float_le":
                if start + 4 <= len(raw_bytes):
                    return struct.unpack('<f', raw_bytes[start:start + 4])[0]
            case "float_be":
                if start + 4 <= len(raw_bytes):
                    return struct.unpack('>f', raw_bytes[start:start + 4])[0]
            case _:
                return None

    def normalize_orientation(self, value: Optional[Union[int, float]], field_name: str) -> Optional[Union[int, float]]:
        if 'orientation' in field_name.lower():
            return value + self.orientation_offset

        return value

    def parse_packet_all_fields(self, raw_bytes: bytes) -> Dict[str, Union[int, float]]:
        packet: Dict[str, Union[int, float]] = {}

        packet["seq_num"] = int.from_bytes(raw_bytes[12:14], byteorder='little')

        for field_name, (start, length) in KEY_FIELDS.items():
            if field_name == "float_val" and start + 4 <= len(raw_bytes):
                packet[field_name] = self.interpret_bytes(raw_bytes, start, 4, "float_le")
            else:
                raw_value = self.interpret_bytes(raw_bytes, start, length, "signed_short")
                if raw_value is not None:
                    packet[field_name] = self.normalize_orientation(raw_value, field_name)

        for i in range(30, min(90, len(raw_bytes) - 1), 2):
            field_name: str = f"byte_{i:02d}"
            raw_value: Optional[Union[int, float]] = self.interpret_bytes(raw_bytes, i, 2, "signed_short")
            if raw_value is not None:
                packet[field_name] = self.normalize_orientation(raw_value, field_name)

        return packet

    def apply_dark_theme(self, fig: Figure, axes: List[Axes]) -> None:
        fig.patch.set_facecolor('#1e1e1e')
        for ax in axes:
            ax.set_facecolor('#2d2d2d')

            ax.title.set_color('white')
            ax.xaxis.label.set_color('white')
            ax.yaxis.label.set_color('white')
            ax.tick_params(colors='white')
            ax.tick_params(axis='x', colors='white')
            ax.tick_params(axis='y', colors='white')

            ax.grid(True, color='#555555', alpha=0.3, linestyle='--')

            for spine in ax.spines.values():
                spine.set_color('#555555')

            legend: Optional[Legend] = ax.get_legend()
            if (legend):
                legend.get_frame().set_facecolor('#2d2d2d')
                legend.get_frame().set_alpha(0.7)
                for text in legend.get_texts():
                    text.set_color('white')

    def listen(self) -> None:
        while True:
            try:
                data: bytes = self.sock.recv(1024)
                formatted: str = self.format_hex(data)
                timestamp: str = DateTime.now().isoformat()

                is_valid: bool = self.is_valid_tracking_packet(formatted)

                if not self.live_plotting:
                    if is_valid:
                        logger.info("%s - Response: %s...", timestamp, formatted[:60])
                    else:
                        logger.info("%s - Skipped non-tracking packet.", timestamp)

                if is_valid:
                    if self.recording and self.log_file is not None:
                        self.log_file.write(formatted + "\n")
                        self.log_file.flush()

                    try:
                        raw_bytes: bytes = self.parse_raw_packet(formatted)
                        packet: Dict[str, Union[int, float]] = self.parse_packet_all_fields(raw_bytes)

                        with self.data_lock:
                            self.live_data.append(packet)
                            if len(self.live_data) > 300:
                                self.live_data.pop(0)

                    except Exception as e:
                        logger.error(f"Error parsing packet: {e}")

            except Exception as e:
                logger.error("Error receiving data: %s", e)
                break

    def load_log_file(self, filepath: str) -> bool:
        self.raw_packets = []
        self.parsed_packets = []
        try:
            with open(filepath, 'r') as f:
                for line in f:
                    line = line.strip()
                    if line:
                        try:
                            raw_bytes: bytes = self.parse_raw_packet(line)
                            self.raw_packets.append(raw_bytes)
                            packet: Dict[str, Union[int, float]] = self.parse_packet_all_fields(raw_bytes)

                            min_seq_num: int = min(
                                [parsed_packet["seq_num"] for parsed_packet in self.parsed_packets], default=0
                            )

                            if packet["seq_num"] > min_seq_num:
                                self.parsed_packets.append(packet)

                        except Exception as e:
                            logger.error(f"Error parsing line: {e}")

            logger.info(f"Loaded {len(self.parsed_packets)} packets from {filepath}")
            return True
        except Exception as e:
            logger.error(f"Error loading log file: {e}")
            return False

    def extract_field_values(self, field_name: str, data_source: str = 'loaded') -> List[Union[int, float]]:
        if data_source == 'loaded':
            data: List[Dict[str, Union[int, float]]] = self.parsed_packets
        else:
            with self.data_lock:
                data: List[Dict[str, Union[int, float]]] = self.live_data.copy()

        values: List[Union[int, float]] = [packet.get(field_name, 0) for packet in data if field_name in packet]

        if data_source == 'live' and len(values) > 5:
            try:
                values: NDArray[Any] = np.array(values, dtype=float)
                values = np.convolve(values, np.ones(5) / 5, mode='valid')
            except Exception as e:
                logger.warning(f"Smoothing error (non-critical): {e}")

        return values

    def is_valid_tracking_packet(self, hex_string: str) -> bool:
        standard_header: str = "04 00 04 00 17 00 00 00 10 00"
        
        if not hex_string.startswith(standard_header):
            if self.live_plotting:
                logger.warning("Invalid packet header: %s", hex_string[:30])
            return False

        if len(hex_string.split()) < 80:
            if self.live_plotting:
                logger.warning("Invalid packet length: %s", hex_string[:30])
            return False

        return True
    

    def plot_fields(self, field_names: Optional[List[str]] = None) -> None:
        if not self.parsed_packets:
            logger.error("No data to plot. Load a log file first.")
            return

        if field_names is None:
            field_names: List[str] = list(KEY_FIELDS.keys())

        if not self.orientation_visualizer.calibration_complete:
            if len(self.parsed_packets) < self.orientation_visualizer.calibration_sample_count:
                logger.error("Not enough packets for calibration. Need at least 10 packets.")
                return
            for packet in self.parsed_packets[:self.orientation_visualizer.calibration_sample_count]:
                self.orientation_visualizer.add_calibration_sample([
                    packet.get('orientation 1', 0),
                    packet.get('orientation 2', 0),
                    packet.get('orientation 3', 0)
                ])

        if self.use_terminal:
            self._plot_fields_terminal(field_names)

        else:
            acceleration_fields: List[str] = [f for f in field_names if 'acceleration' in f.lower()]
            orientation_fields: List[str] = [f for f in field_names if 'orientation' in f.lower()]
            other_fields: List[str] = [f for f in field_names if f not in acceleration_fields + orientation_fields]

            fig, axes = plt.subplots(3, 1, figsize=(14, 12), sharex=True)
            self.apply_dark_theme(fig, axes)

            acceleration_colors: List[str] = ['#FFFF00', '#00FFFF']
            orientation_colors: List[str] = ['#FF00FF', '#00FF00', '#FFA500']
            other_colors: List[str] = ['#52b788', '#f4a261', '#e76f51', '#2a9d8f']

            if acceleration_fields:
                for i, field in enumerate(acceleration_fields):
                    values = self.extract_field_values(field)
                    axes[0].plot(values, label=field, color=acceleration_colors[i % len(acceleration_colors)], linewidth=2)
                axes[0].set_title("Acceleration Data", fontsize=14)
                axes[0].legend()

            if orientation_fields:
                for i, field in enumerate(orientation_fields):
                    values = self.extract_field_values(field)
                    axes[1].plot(values, label=field, color=orientation_colors[i % len(orientation_colors)], linewidth=2)
                axes[1].set_title("Orientation Data", fontsize=14)
                axes[1].legend()

            if other_fields:
                for i, field in enumerate(other_fields):
                    values = self.extract_field_values(field)
                    axes[2].plot(values, label=field, color=other_colors[i % len(other_colors)], linewidth=2)
                axes[2].set_title("Other Fields", fontsize=14)
                axes[2].legend()

            plt.xlabel("Packet Index", fontsize=12)
            plt.tight_layout()
            plt.show()

    def _plot_fields_terminal(self, field_names: List[str]) -> None:
        """Internal method for terminal-based plotting"""
        terminal_width: int = os.get_terminal_size().columns
        plot_width: int = min(terminal_width - 10, 120)
        plot_height: int = 15

        acceleration_fields: List[str] = [f for f in field_names if 'acceleration' in f.lower()]
        orientation_fields: List[str] = [f for f in field_names if 'orientation' in f.lower()]
        other_fields: List[str] = [f for f in field_names if f not in acceleration_fields + orientation_fields]

        def plot_group(fields: List[str], title: str) -> None:
            if not fields:
                return

            print(f"\n{title}")
            print("=" * len(title))

            for field in fields:
                values: List[float] = self.extract_field_values(field)
                if len(values) > plot_width:
                    values = values[-plot_width:]

                if title == "Acceleration Data":
                    chart: str = acp.plot(values, {'height': plot_height})
                    print(chart)
                else:
                    chart: str = acp.plot(values, {'height': plot_height})
                    print(chart)

                print(f"Min: {min(values):.2f}, Max: {max(values):.2f}, " + f"Mean: {np.mean(values):.2f}")
                print()

        plot_group(acceleration_fields, "Acceleration Data")
        plot_group(orientation_fields, "Orientation Data")
        plot_group(other_fields, "Other Fields")

    def create_braille_plot(self, values: List[float], width: int = 80, height: int = 20, y_label: bool = True, fixed_y_min: Optional[float] = None, fixed_y_max: Optional[float] = None) -> str:
        canvas: Canvas = Canvas()
        if fixed_y_min is None or fixed_y_max is None:
            local_min, local_max = min(values), max(values)
        else:
            local_min, local_max = fixed_y_min, fixed_y_max
        y_range: float = local_max - local_min or 1
        x_step: int = max(1, len(values) // width)
        for i, v in enumerate(values[::x_step]):
            y: int = int(((v - local_min) / y_range) * (height * 2 - 1))
            canvas.set(i, y)
        frame: str = canvas.frame()
        if y_label:
            lines: List[str] = frame.split('\n')
            labeled_lines: List[str] = []
            for idx, line in enumerate(lines):
                if idx == 0:
                    labeled_lines.append(f"{local_max:6.0f} {line}")
                elif idx == len(lines)-1:
                    labeled_lines.append(f"{local_min:6.0f} {line}")
                else:
                    labeled_lines.append("       " + line)
            frame = "\n".join(labeled_lines)
        return frame

    def _start_live_plotting_terminal(self, record_data: bool = False, duration: Optional[float] = None) -> None:
        import sys, select, tty, termios
        old_settings = termios.tcgetattr(sys.stdin)
        tty.setcbreak(sys.stdin.fileno()) 
        console: Console = Console()
        term_width: int = console.width
        plot_width: int = round(min(term_width / 2 - 15, 120))
        ori_height: int = 10
        
        def make_compact_layout() -> Layout:
            layout: Layout = Layout()
            layout.split_column(
                Layout(name="header", size=3),
                Layout(name="main", ratio=1),
            )
            layout["main"].split_row(
                Layout(name="accelerations", ratio=1),
                Layout(name="orientations", ratio=1)
            )
            layout["accelerations"].split_column(
                Layout(name="vertical", ratio=1),
                Layout(name="horizontal", ratio=1)
            )
            layout["orientations"].split_column(
                Layout(name="face", ratio=1),
                Layout(name="raw", ratio=1)
            )
            return layout
        
        layout: Layout = make_compact_layout()
        
        try:
            import time
            with Live(layout, refresh_per_second=20, screen=True) as live:
                while True:
                    if sys.stdin in select.select([sys.stdin], [], [], 0)[0]:
                        ch = sys.stdin.read(1)
                        if ch == 'p':
                            self.paused = not self.paused
                            logger.info("Paused" if self.paused else "Resumed")
                    if self.paused:
                        time.sleep(0.1)
                        rec_str: str = " [red][REC][/red]" if record_data else ""
                        left: str = "AirPods Head Tracking - v1.0.0"
                        right: str = "Ctrl+C - Close | p - Pause" + rec_str
                        status: str = "[bold red]Paused[/bold red]"
                        header: List[str] = list(" " * term_width)
                        header[0:len(left)] = list(left)
                        header[term_width - len(right):] = list(right)
                        start: int = (term_width - len(status)) // 2
                        header[start:start+len(status)] = list(status)
                        header_text: str = "".join(header)
                        layout["header"].update(Panel(header_text, style="bold white on black"))
                        continue
                    
                    with self.data_lock:
                        if len(self.live_data) < 1:
                            continue
                        latest: Dict[str, float] = self.live_data[-1]
                        data: List[Dict[str, float]] = self.live_data[-plot_width:]
                    
                    if not self.orientation_visualizer.calibration_complete:
                        sample: List[float] = [
                            latest.get('orientation 1', 0),
                            latest.get('orientation 2', 0),
                            latest.get('orientation 3', 0)
                        ]
                        self.orientation_visualizer.add_calibration_sample(sample)
                        time.sleep(0.05)
                        rec_str: str = " [red][REC][/red]" if record_data else ""
                        
                        left: str = "AirPods Head Tracking - v1.0.0"
                        status: str = "[bold yellow]Calibrating...[/bold yellow]"
                        right: str = "Ctrl+C - Close | p - Pause"
                        remaining: int = max(term_width - len(left) - len(right), 0)
                        header_text: str = f"{left}{status.center(remaining)}{right}{rec_str}"
                        layout["header"].update(Panel(header_text, style="bold white on black"))
                        live.refresh()
                        continue
                    
                    o1: float = latest.get('orientation 1', 0)
                    o2: float = latest.get('orientation 2', 0)
                    o3: float = latest.get('orientation 3', 0)
                    orientation: Dict[str, float] = self.orientation_visualizer.calculate_orientation(o1, o2, o3)
                    pitch: float = orientation['pitch']
                    yaw: float = orientation['yaw']
                    
                    h_accel: List[float] = [p.get('Horizontal Acceleration', 0) for p in data]
                    v_accel: List[float] = [p.get('Vertical Acceleration', 0) for p in data]
                    if len(h_accel) > plot_width:
                        h_accel = h_accel[-plot_width:]
                    if len(v_accel) > plot_width:
                        v_accel = v_accel[-plot_width:]
                    global_min: float = min(min(v_accel), min(h_accel))
                    global_max: float = max(max(v_accel), max(h_accel))
                    config_acc: Dict[str, float] = {'height': 20, 'min': global_min, 'max': global_max}
                    vert_plot: str = acp.plot(v_accel, config_acc)
                    horiz_plot: str = acp.plot(h_accel, config_acc)
                    
                    rec_str: str = " [red][REC][/red]" if record_data else ""
                    left: str = "AirPods Head Tracking - v1.0.0"
                    right: str = "Ctrl+C - Close | p - Pause" + rec_str
                    status: str = "[bold green]Live[/bold green]"
                    header: List[str] = list(" " * term_width)
                    header[0:len(left)] = list(left)
                    header[term_width - len(right):] = list(right)
                    start: int = (term_width - len(status)) // 2
                    header[start:start+len(status)] = list(status)
                    header_text: str = "".join(header)
                    layout["header"].update(Panel(header_text, style="bold white on black"))
                    
                    face_art: str = self.orientation_visualizer.create_face_art(pitch, yaw)
                    layout["accelerations"]["vertical"].update(Panel(
                        "[bold yellow]Vertical Acceleration[/]\n" +
                        vert_plot + "\n" +
                        f"Cur: {v_accel[-1]:6.1f} | Min: {min(v_accel):6.1f} | Max: {max(v_accel):6.1f}",
                        style="yellow"
                    ))
                    layout["accelerations"]["horizontal"].update(Panel(
                        "[bold cyan]Horizontal Acceleration[/]\n" +
                        horiz_plot + "\n" +
                        f"Cur: {h_accel[-1]:6.1f} | Min: {min(h_accel):6.1f} | Max: {max(h_accel):6.1f}",
                        style="cyan"
                    ))
                    layout["orientations"]["face"].update(Panel(face_art, title="[green]Orientation - Visualization[/]", style="green"))
                    
                    o2_values: List[float] = [p.get('orientation 2', 0) for p in data[-plot_width:]]
                    o3_values: List[float] = [p.get('orientation 3', 0) for p in data[-plot_width:]]
                    o2_values: List[float] = o2_values[:plot_width]
                    o3_values: List[float] = o3_values[:plot_width]
                    common_min: float = min(min(o2_values), min(o3_values))
                    common_max: float = max(max(o2_values), max(o3_values))
                    config_ori: Dict[str, float] = {'height': ori_height, 'min': common_min, 'max': common_max, 'format': "{:6.0f}"}
                    chart_o2: str = acp.plot(o2_values, config_ori)
                    chart_o3: str = acp.plot(o3_values, config_ori)
                    layout["orientations"]["raw"].update(Panel(
                        "[bold yellow]Orientation 1:[/]\n" + chart_o2 + "\n" +
                        f"Cur: {o2_values[-1]:6.1f} | Min: {min(o2_values):6.1f} | Max: {max(o2_values):6.1f}\n\n" +
                        "[bold green]Orientation 2:[/]\n" + chart_o3 + "\n" +
                        f"Cur: {o3_values[-1]:6.1f} | Min: {min(o3_values):6.1f} | Max: {max(o3_values):6.1f}",
                        title="[cyan]Orientation Raw[/]", style="yellow"
                    ))
                    live.refresh()
                    time.sleep(0.05)
        except KeyboardInterrupt:
            logger.info("\nStopped.")
            if record_data:
                self.stop_tracking()
            else:
                if self.sock:
                    self.sock.send(bytes.fromhex(STOP_CMD))
        finally:
            termios.tcsetattr(sys.stdin, termios.TCSADRAIN, old_settings)

    def _start_live_plotting(self, record_data: bool = False, duration: Optional[float] = None) -> None:
        terminal_width: int = os.get_terminal_size().columns
        plot_width: int = min(terminal_width - 10, 80)
        plot_height: int = 10

        try:
            while True:
                os.system('clear' if os.name == 'posix' else 'cls')
                with self.data_lock:
                    if len(self.live_data) == 0:
                        print("\nWaiting for data...")
                        time.sleep(0.1)
                        continue

                    data: List[Dict[str, float]] = self.live_data[-plot_width:]

                acceleration_fields: List[str] = [f for f in KEY_FIELDS.keys() if 'acceleration' in f.lower()]
                orientation_fields: List[str] = [f for f in KEY_FIELDS.keys() if 'orientation' in f.lower()]
                other_fields: List[str] = [f for f in KEY_FIELDS.keys() if f not in acceleration_fields + orientation_fields]

                def plot_group(fields: List[str], title: str) -> None:
                    if not fields:
                        return

                    print(f"\n{title}")
                    print("=" * len(title))

                    for field in fields:
                        values: List[float] = [packet.get(field, 0) for packet in data if field in packet]
                        if len(values) > 0:
                            chart: str = acp.plot(values, {'height': plot_height})
                            print(chart)
                            print(f"Current: {values[-1]:.2f}, " +
                                  f"Min: {min(values):.2f}, Max: {max(values):.2f}")
                        print()

                plot_group(acceleration_fields, "Acceleration Data")
                plot_group(orientation_fields, "Orientation Data")
                plot_group(other_fields, "Other Fields")

                print("\nPress Ctrl+C to stop plotting")
                time.sleep(0.1)

        except KeyboardInterrupt:
            logger.info("\nLive plotting stopped.")
            self.sock.send(bytes.fromhex(STOP_CMD))
            if record_data:
                self.stop_tracking()
            self.live_plotting = False

    def start_live_plotting(self, record_data: bool = False, duration: Optional[float] = None) -> None:
        if self.sock is None:
            if not self.connect():
                logger.error("Could not connect to AirPods. Live plotting aborted.")
                return
        if not self.recording and record_data:
            self.start_tracking(duration)
            logger.info("Recording enabled during live plotting")
        elif not self.recording:
            self.sock.send(bytes.fromhex(START_CMD))
            logger.info("Head tracking started (not recording to file)")
        with self.data_lock:
            self.live_data = []
        self.live_plotting = True
        self.paused = False
        if self.use_terminal:
            self._start_live_plotting_terminal(record_data, duration)
        else:
            from matplotlib.gridspec import GridSpec, GridSpecFromSubplotSpec
            fig: Figure = plt.figure(figsize=(14, 6))
            gs: GridSpec = GridSpec(1, 2, width_ratios=[1, 1])
            ax_accel: Axes = fig.add_subplot(gs[0])
            subgs: GridSpecFromSubplotSpec = GridSpecFromSubplotSpec(2, 1, subplot_spec=gs[1], height_ratios=[2, 1])
            ax_head_top: Axes = fig.add_subplot(subgs[0], projection='3d')
            ax_ori: Axes = fig.add_subplot(subgs[1])
            
            ax_accel.set_title("Acceleration Data")
            ax_accel.set_xlabel("Packet Index")
            ax_accel.set_ylabel("Acceleration")
            ax_accel.legend(loc='upper right', framealpha=0.7)
            fig.patch.set_facecolor('#1e1e1e')
            ax_accel.set_facecolor('#2d2d2d')
            self.apply_dark_theme(fig, [ax_accel, ax_head_top, ax_ori])
            plt.ion()

            def update_plot(_: int) -> None:
                with self.data_lock:
                    data: List[Dict[str, float]] = self.live_data.copy()
                if len(data) == 0:
                    return
                
                latest: Dict[str, float] = data[-1]
                
                if not self.orientation_visualizer.calibration_complete:
                    sample: List[float] = [
                        latest.get('orientation 1', 0),
                        latest.get('orientation 2', 0),
                        latest.get('orientation 3', 0)
                    ]
                    self.orientation_visualizer.add_calibration_sample(sample)
                    ax_head_top.cla()
                    ax_head_top.text(0.5, 0.5, "Calibrating... please wait", horizontalalignment='center', verticalalignment='center', transform=ax_head_top.transAxes, color='white')
                    fig.canvas.draw_idle()
                    return

                h_accel: List[float] = [p.get('Horizontal Acceleration', 0) for p in data]
                v_accel: List[float] = [p.get('Vertical Acceleration', 0) for p in data]
                x_vals: List[int] = list(range(len(h_accel)))
                ax_accel.cla()
                ax_accel.plot(x_vals, v_accel, label='Vertical Acceleration', color='#FFFF00', linewidth=2)
                ax_accel.plot(x_vals, h_accel, label='Horizontal Acceleration', color='#00FFFF', linewidth=2)
                ax_accel.set_title("Acceleration Data")
                ax_accel.set_xlabel("Packet Index")
                ax_accel.set_ylabel("Acceleration")
                ax_accel.legend(loc='upper right', framealpha=0.7)
                ax_accel.set_facecolor('#2d2d2d')
                ax_accel.title.set_color('white')
                ax_accel.xaxis.label.set_color('white')
                ax_accel.yaxis.label.set_color('white')
                
                latest: Dict[str, float] = data[-1]
                o1: float = latest.get('orientation 1', 0)
                o2: float = latest.get('orientation 2', 0)
                o3: float = latest.get('orientation 3', 0)
                orientation: Dict[str, float] = self.orientation_visualizer.calculate_orientation(o1, o2, o3)
                pitch: float = orientation['pitch']
                yaw: float = orientation['yaw']
        
                ax_head_top.cla()
                ax_head_top.set_title("Head Orientation")
                ax_head_top.set_xlim([-1, 1])
                ax_head_top.set_ylim([-1, 1])
                ax_head_top.set_zlim([-1, 1])
                ax_head_top.set_facecolor('#2d2d2d')
                pitch_rad = np.radians(pitch)
                yaw_rad = np.radians(yaw)
                Rz: NDArray[Any] = np.array([
                    [np.cos(yaw_rad), np.sin(yaw_rad), 0],
                    [-np.sin(yaw_rad), np.cos(yaw_rad), 0],
                    [0, 0, 1]
                ])
                Ry: NDArray[Any] = np.array([
                    [np.cos(pitch_rad), 0, np.sin(pitch_rad)],
                    [0, 1, 0],
                    [-np.sin(pitch_rad), 0, np.cos(pitch_rad)]
                ])
                R: NDArray[Any] = Rz @ Ry
                dir_vec: NDArray[Any] = R @ np.array([1, 0, 0])
                ax_head_top.quiver(0, 0, 0, dir_vec[0], dir_vec[1], dir_vec[2],
                                   color='r', length=0.8, linewidth=3)
                
                ax_ori.cla()
                o2_values: List[float] = [p.get('orientation 2', 0) for p in data]
                o3_values: List[float] = [p.get('orientation 3', 0) for p in data]
                x_range: List[int] = list(range(len(o2_values)))
                ax_ori.plot(x_range, o2_values, label='Orientation 1', color='red', linewidth=2)
                ax_ori.plot(x_range, o3_values, label='Orientation 2', color='green', linewidth=2)
                ax_ori.set_facecolor('#2d2d2d')
                ax_ori.tick_params(colors='white')
                ax_ori.set_title("Orientation Raw")
                ax_ori.legend(facecolor='#2d2d2d', edgecolor='#555555', 
                              labelcolor='white', loc='upper right')
                ax_ori.text(0.95, 0.9, f"Pitch: {pitch:.1f}°\nYaw: {yaw:.1f}°",
                            transform=ax_ori.transAxes, color='white',
                            ha='right', va='top', bbox=dict(facecolor='#2d2d2d', alpha=0.5))
                fig.canvas.draw_idle()
            self.animation = FuncAnimation(
                fig, update_plot,
                interval=20,
                blit=False,
                cache_frame_data=False
            )
            plt.show(block=True)
            self.sock.send(bytes.fromhex(STOP_CMD))
            logger.info("Stopping head tracking AirPods.")
            if self.recording and record_data:
                self.stop_tracking()
                logger.info("Recording stopped after sending close command")
            else:
                logger.info("Live plotting ended (no recording to stop).")
            self.live_plotting = False
            self.animation = None
            plt.ioff()

    def interactive_mode(self) -> None:
        from prompt_toolkit import PromptSession
        session: PromptSession = PromptSession("> ")
        logger.info("\nAirPods Head Tracking Analyzer")
        print("------------------------------")
        logger.info("Commands:")
        print("  connect                 - connect to your AirPods")
        print("  start [seconds]         - start recording head tracking data, optionally for specified duration")
        print("  stop                    - stop recording")
        print("  load <file>             - load and parse a log file")
        print("  plot                    - plot all sensor data fields")
        print("  live [seconds]          - start live plotting (without recording), optionally stop recording after seconds")
        print("  liver [seconds]         - start live plotting with recording, optionally stop recording after seconds")
        print("  gestures                - start gesture detection")
        print("  quit                    - exit the program")
        
        while True:
            try:
                cmd_input: str = session.prompt("> ")
                cmd_parts: List[str] = cmd_input.strip().split()
                if not cmd_parts:
                    continue
                cmd = cmd_parts[0].lower()
                match cmd:
                    case "connect":
                        self.connect()
                    case "start":
                        duration = float(cmd_parts[1]) if len(cmd_parts) > 1 else None
                        self.start_tracking(duration)
                    case "stop":
                        self.stop_tracking()
                    case "load":
                        if len(cmd_parts) > 1:
                            self.load_log_file(cmd_parts[1])
                    case "plot":
                        self.plot_fields()
                    case "live":
                        duration = float(cmd_parts[1]) if len(cmd_parts) > 1 else None
                        logger.info("Starting live plotting mode (without recording)%s.", 
                                    f" for {duration} seconds" if duration else "")
                        self.start_live_plotting(record_data=False, duration=duration)
                    case "liver":
                        duration = float(cmd_parts[1]) if len(cmd_parts) > 1 else None
                        logger.info("Starting live plotting mode WITH recording%s.", 
                                    f" for {duration} seconds" if duration else "")
                        self.start_live_plotting(record_data=True, duration=duration)
                    case "gestures":
                        from gestures import GestureDetector
                        if self.conn is not None:
                            detector: GestureDetector = GestureDetector(conn=self.conn)
                        else:
                            detector: GestureDetector = GestureDetector()
                        detector.start_detection()
                    case "quit":
                        logger.info("Exiting.")
                        if self.conn != None:
                            self.conn.disconnect()
                        break
                    case "help":
                        logger.info("\nAirPods Head Tracking Analyzer")
                        logger.info("------------------------------")
                        logger.info("Commands:")
                        logger.info("  connect                 - connect to your AirPods")
                        logger.info("  start [seconds]         - start recording head tracking data, optionally for specified duration")
                        logger.info("  stop                    - stop recording")
                        logger.info("  load <file>             - load and parse a log file")
                        logger.info("  plot                    - plot all sensor data fields")
                        logger.info("  live [seconds]          - start live plotting (without recording), optionally stop recording after seconds")
                        logger.info("  liver [seconds]         - start live plotting with recording, optionally stop recording after seconds")
                        logger.info("  gestures                - start gesture detection")
                        logger.info("  quit                    - exit the program")
                    case _:
                        logger.info("Unknown command. Type 'help' to see available commands.")
            except KeyboardInterrupt:
                logger.info("Use 'quit' to exit.")
            except EOFError:
                logger.info("Exiting.")
                if self.conn != None:
                    self.conn.disconnect()
                break

if __name__ == "__main__":
    import sys
    tracker: AirPodsTracker = AirPodsTracker()
    tracker.interactive_mode()
