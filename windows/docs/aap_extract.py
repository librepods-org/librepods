#!/usr/bin/env python3
"""Extract & annotate AirPods AAP packets from a hex/text export.

Feed it anything that contains hex byte runs — tshark `-e data` output, a
Wireshark "Export as plain text", or a copy-pasted hex dump. It reconstructs the
byte arrays, keeps the ones that look like AAP (header `04 00 04 00` or a bare
control/notify opcode) and prints them one per line with the opcode named.

    tshark -r cap.pklg -Y btl2cap -T fields -e frame.time_relative -e data \
      | python3 aap_extract.py

Deliberately forgiving about input format — tweak on the Mac if your export
differs. This is a starting point, not a full dissector.
"""
import re
import sys

AAP_HEADER = bytes([0x04, 0x00, 0x04, 0x00])

# opcode (byte after the 04 00 04 00 header) -> human name
OPCODES = {
    0x04: "battery",
    0x06: "ear-detection",
    0x09: "control-command",
    0x0F: "request-notifications",
    0x1A: "rename",
    0x4B: "conversational-awareness",
    0x4D: "set-features",
    0x58: "hi-res-audio",
    0x17: "rtbuddy (heart-rate)",
}

# control-command id (byte after opcode 0x09) -> human name
CONTROL_IDS = {
    0x0D: "ANC (1=off 2=NC 3=transparency 4=adaptive)",
    0x26: "adaptive/personalized volume",
    0x28: "conversational awareness",
    0x2E: "adaptive-noise strength (0..100)",
    0x30: "HRM state",
    0x34: "allow-off",
}

# battery component id -> name; status byte -> meaning
BATT_COMPONENT = {0x01: "headphone", 0x02: "right", 0x04: "left", 0x08: "case"}
BATT_STATUS = {0x01: "charging", 0x02: "not-charging", 0x04: "disconnected", 0x05: "charging-in-case"}

# grab runs of hex bytes: "04 00 04 00", "04:00:04:00", or "04000400"
HEX_RUN = re.compile(r"(?:[0-9a-fA-F]{2}[\s:]*){4,}")


def to_bytes(token: str) -> bytes:
    h = re.sub(r"[^0-9a-fA-F]", "", token)
    if len(h) % 2:
        h = h[:-1]
    try:
        return bytes.fromhex(h)
    except ValueError:
        return b""


def annotate(b: bytes) -> str:
    if len(b) >= 5 and b[:4] == AAP_HEADER:
        op = b[4]
        name = OPCODES.get(op, f"opcode 0x{op:02x} (UNKNOWN)")
        extra = ""
        if op == 0x09 and len(b) >= 8:
            cid = b[6]
            extra = f"  id=0x{cid:02x} {CONTROL_IDS.get(cid, 'UNKNOWN')}  value=0x{b[7]:02x}"
        elif op == 0x04 and len(b) >= 7:
            # battery: count at b[6], then 5-byte records (id, ?, level, status, ?)
            count = b[6]
            parts = []
            base = 7
            for _ in range(count):
                if base + 3 >= len(b):
                    break
                comp = BATT_COMPONENT.get(b[base], f"0x{b[base]:02x}")
                level = b[base + 2]
                status = BATT_STATUS.get(b[base + 3], f"0x{b[base + 3]:02x}")
                parts.append(f"{comp}={level}%/{status}")
                base += 5
            extra = "  " + "  ".join(parts)
        elif op == 0x06 and len(b) >= 7:
            ear = {0x00: "in-ear", 0x02: "in-case", 0x03: "disconnected"}
            extra = f"  primary={ear.get(b[5], hex(b[5]))} secondary={ear.get(b[6], hex(b[6]))}"
        return f"{name}{extra}"
    return "(non-AAP)"


def main() -> None:
    seen = 0
    for line in sys.stdin:
        for m in HEX_RUN.finditer(line):
            b = to_bytes(m.group())
            if len(b) >= 5 and b[:4] == AAP_HEADER:
                seen += 1
                hexs = " ".join(f"{x:02x}" for x in b)
                print(f"{annotate(b):<48}  {hexs}")
    if seen == 0:
        print("no AAP packets found — check the export format "
              "(need hex byte runs starting 04 00 04 00)", file=sys.stderr)


if __name__ == "__main__":
    main()
