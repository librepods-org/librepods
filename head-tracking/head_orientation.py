import math
import numpy as np
import logging
import os
from colors import *
from drawille import Canvas
from logging import Logger, StreamHandler
from matplotlib.animation import FuncAnimation
from matplotlib.pyplot import Axes, Figure
from numpy.typing import NDArray
from os import terminal_size as TerminalSize
from typing import Any, Dict, List, Optional, Tuple

handler: StreamHandler = StreamHandler()
handler.setFormatter(ColorFormatter())
log: Logger = logging.getLogger(__name__)
log.setLevel(logging.INFO)
log.addHandler(handler)
log.propagate = False

class HeadOrientation:
    def __init__(self, use_terminal: bool = False) -> None:
        self.orientation_offset: int = 5500
        self.o1_neutral: int = 19000
        self.o2_neutral: int = 0
        self.o3_neutral: int = 0
        self.calibration_samples: List[List[int]] = []
        self.calibration_complete: bool = False
        self.calibration_sample_count: int = 10
        self.fig: Optional[Figure] = None
        self.ax: Optional[Axes] = None
        self.arrow: Any = None
        self.animation: Optional[FuncAnimation] = None
        self.use_terminal: bool = use_terminal

    def reset_calibration(self) -> None:
        self.calibration_samples = []
        self.calibration_complete = False

    def add_calibration_sample(self, orientation_values: List[int]) -> bool:
        if len(self.calibration_samples) < self.calibration_sample_count:
            self.calibration_samples.append(orientation_values)
            return False
        if not self.calibration_complete:
            self._calculate_calibration()
            return True
        return True

    def _calculate_calibration(self) -> None: 
        if len(self.calibration_samples) < 3:
            log.warning("Not enough calibration samples")
            return
        samples: NDArray[[List[int]]] = np.array(self.calibration_samples)
        self.o1_neutral: float = np.mean(samples[:, 0])
        avg_o2: float = np.mean(samples[:, 1])
        avg_o3: float = np.mean(samples[:, 2])
        self.o2_neutral: float = avg_o2
        self.o3_neutral: float = avg_o3
        log.info("Calibration complete: o1_neutral=%.2f, o2_neutral=%.2f, o3_neutral=%.2f", 
                    self.o1_neutral, self.o2_neutral, self.o3_neutral)
        self.calibration_complete = True

    def calculate_orientation(self, o1: float, o2: float, o3: float) -> Dict[str, float]:
        if not self.calibration_complete:
            return {'pitch': 0, 'yaw': 0}
        o1_norm: float = o1 - self.o1_neutral
        o2_norm: float = o2 - self.o2_neutral
        o3_norm: float = o3 - self.o3_neutral
        pitch: float = (o2_norm + o3_norm) / 2 / 32000 * 180
        yaw: float = (o2_norm - o3_norm) / 2 / 32000 * 180
        return {'pitch': pitch, 'yaw': yaw}

    def create_face_art(self, pitch: float, yaw: float) -> str:
        if self.use_terminal:
            try:
                ts: TerminalSize = os.get_terminal_size()
                width, height = ts.columns, ts.lines * 2
            except Exception:
                width, height = 80, 40
        else:
            width, height = 80, 40
        center_x, center_y = width // 2, height // 2
        radius: int = (min(width, height) // 2 - 2) // 2
        pitch_rad: float = math.radians(pitch)
        yaw_rad: float = math.radians(yaw)
        canvas: Canvas = Canvas()

        def rotate_point(x: float, y: float, z: float, pitch_r: float, yaw_r: float) -> Tuple[int, int]:
            cos_y, sin_y = math.cos(yaw_r), math.sin(yaw_r)
            cos_p, sin_p = math.cos(pitch_r), math.sin(pitch_r)
            x1: float = x * cos_y - z * sin_y
            z1: float = x * sin_y + z * cos_y
            y1: float = y * cos_p - z1 * sin_p
            z2: float = y * sin_p + z1 * cos_p
            scale: float = 1 + (z2 / width)
            return int(center_x + x1 * scale), int(center_y + y1 * scale)
        for angle in range(0, 360, 2):
            rad: float = math.radians(angle)
            x: float = radius * math.cos(rad)
            y: float = radius * math.sin(rad)
            x1, y1 = rotate_point(x, y, 0, pitch_rad, yaw_rad)
            canvas.set(x1, y1)
        for eye in [(-radius//2, -radius//3, 2), (radius//2, -radius//3, 2)]:
            ex, ey, ez = eye
            x1, y1 = rotate_point(ex, ey, ez, pitch_rad, yaw_rad)
            for dx in [-1, 0, 1]:
                for dy in [-1, 0, 1]:
                    canvas.set(x1 + dx, y1 + dy)
        nx, ny = rotate_point(0, 0, 1, pitch_rad, yaw_rad)
        for dx in [-1, 0, 1]:
            for dy in [-1, 0, 1]:
                canvas.set(nx + dx, ny + dy)
        smile_depth: int = radius // 8
        mouth_local_y: int = radius // 4
        mouth_length: int = radius
        for x_offset in range(-mouth_length // 2, mouth_length // 2 + 1):
            norm: float = abs(x_offset) / (mouth_length / 2)
            y_offset: int = int((1 - norm ** 2) * smile_depth)
            local_x: int = x_offset
            local_y: int = mouth_local_y + y_offset
            mx, my = rotate_point(local_x, local_y, 0, pitch_rad, yaw_rad)
            canvas.set(mx, my)
        return canvas.frame()
