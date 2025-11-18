import logging
from logging import Formatter, LogRecord
from typing import Dict

class Colors:
    RESET: str = "\033[0m"
    BOLD: str = "\033[1m"
    RED: str = "\033[91m"
    GREEN: str = "\033[92m"
    YELLOW: str = "\033[93m"
    BLUE: str = "\033[94m"
    MAGENTA: str = "\033[95m"
    CYAN: str = "\033[96m"
    WHITE: str = "\033[97m"
    BG_BLACK: str = "\033[40m"

class ColorFormatter(Formatter):
    FORMATS: Dict[int, str] = {
        logging.DEBUG: f"{Colors.BLUE}[%(levelname)s] %(message)s{Colors.RESET}",
        logging.INFO: f"{Colors.GREEN}%(message)s{Colors.RESET}",
        logging.WARNING: f"{Colors.YELLOW}%(message)s{Colors.RESET}",
        logging.ERROR: f"{Colors.RED}[%(levelname)s] %(message)s{Colors.RESET}",
        logging.CRITICAL: f"{Colors.RED}{Colors.BOLD}[%(levelname)s] %(message)s{Colors.RESET}"
    }

    def format(self, record: LogRecord) -> str:
        log_fmt: str = self.FORMATS.get(record.levelno)
        formatter: Formatter = Formatter(log_fmt, datefmt="%H:%M:%S")
        return formatter.format(record)
