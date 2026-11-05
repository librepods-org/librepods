#!/usr/bin/env python3
"""Regression tests for AAP protocol documentation."""

from pathlib import Path
import re
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
AAP_DEFINITIONS = REPOSITORY_ROOT / "AAP Definitions.md"


class ProtocolDocumentationTests(unittest.TestCase):
    def test_battery_walkthrough_labels_match_component_bytes(self):
        definitions = AAP_DEFINITIONS.read_text(encoding="utf-8")
        battery_section = definitions.split("## Battery", 1)[1].split(
            "## Noise Control", 1
        )[0]
        components = {
            int(value, 16): label
            for label, value in re.findall(
                r"^\|\s*(Case|Left|Right)\s*\|\s*([0-9A-F]{2})\s*\|$",
                battery_section,
                re.MULTILINE,
            )
        }
        packet_match = re.search(
            r"Example packet.*?```plaintext\n([0-9A-F ]+)\n```",
            battery_section,
            re.DOTALL,
        )
        if packet_match is None:
            self.fail("battery example packet is missing")
        packet = [int(byte, 16) for byte in packet_match.group(1).split()]
        walkthrough = {
            int(position): label
            for position, label in re.findall(
                r"^\|\s*(8|13|18)th byte\s*\|\s*Battery type - (Left|Right|Case)",
                battery_section,
                re.MULTILINE,
            )
        }

        for position in (8, 13, 18):
            with self.subTest(position=position):
                self.assertEqual(components[packet[position - 1]], walkthrough[position])

    def test_notification_request_documents_a2698_battery_limitation(self):
        definitions = AAP_DEFINITIONS.read_text(encoding="utf-8")
        notification_section = definitions.split("# Requesting notifications", 1)[1].split(
            "# Notifications", 1
        )[0]

        self.assertIn("04 00 04 00 0F 00 FF FF FE FF", notification_section)
        self.assertIn("04 00 04 00 0F 00 FF FF FF FF", notification_section)
        self.assertIn("AirPods Pro 2 (A2698", notification_section)
        self.assertIn("does not deliver battery notifications", notification_section)
        self.assertIn(
            "Prefer `04 00 04 00 0F 00 FF FF FF FF`",
            notification_section,
        )


if __name__ == "__main__":
    unittest.main()
