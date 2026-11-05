#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Exercise the real monitor and measure both ends of its private D-Bus socket."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

import dbus

HERE = Path(__file__).resolve().parent


def wait_for_marker(path, marker, process, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if marker in path.read_text():
            return
        if process.poll() is not None:
            raise RuntimeError(f"{marker} process exited: {path.read_text()}")
        time.sleep(0.05)
    raise RuntimeError(f"Timeout waiting for {marker}: {path.read_text()}")


def queues(pid, daemon_pid):
    lines = subprocess.check_output(["ss", "-xnpH"], text=True).splitlines()
    entries = []
    for line in lines:
        fields = line.split()
        if len(fields) < 8 or fields[0] != "u_str" or fields[1] != "ESTAB":
            continue
        try:
            entries.append({
                "recv": int(fields[2]), "send": int(fields[3]),
                "local": int(fields[5]), "peer": int(fields[7]),
                "client": f"pid={pid}," in line,
                "daemon": f"pid={daemon_pid}," in line,
            })
        except ValueError:
            continue
    client_entries = [entry for entry in entries if entry["client"]]
    if not client_entries:
        raise RuntimeError(f"No connected Unix socket observed for monitor PID {pid}")
    pairs = [(client, peer) for client in client_entries for peer in entries
             if peer["daemon"] and peer["local"] == client["peer"]
             and peer["peer"] == client["local"]]
    if len(pairs) != 1:
        raise RuntimeError(
            f"Cannot observe a unique reciprocal D-Bus socket pair (found {len(pairs)})")
    client, daemon = pairs[0]
    return client["recv"], daemon["send"]


def rss_kib(pid):
    status = Path(f"/proc/{pid}/status").read_text()
    match = re.search(r"^VmRSS:\s+(\d+)\s+kB$", status, re.MULTILINE)
    return int(match.group(1)) if match else 0


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)


def run_case(client_binary, output_dir, name, messages, duration, noise_sender="foreign",
             connection_cycles=False):
    scratch = Path(os.environ.get("TMPDIR", Path.cwd() / ".diagnostics-scratch"))
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="librepods-dbus-", dir=scratch) as temporary:
        bus_path = Path(temporary) / "bus"
        config = Path(temporary) / "bus.conf"
        config.write_text(f"""<busconfig>
  <type>session</type>
  <listen>unix:path={bus_path}</listen>
  <auth>EXTERNAL</auth>
  <policy context="default">
    <allow own="*"/>
    <allow send_destination="*"/>
    <allow receive_sender="*"/>
  </policy>
  <limit name="max_outgoing_bytes">536870912</limit>
</busconfig>
""")
        processes = []
        logs = []
        try:
            broker_error = (output_dir / f"{name}-broker.log").open("w")
            logs.append(broker_error)
            broker = subprocess.Popen(
                ["dbus-daemon", f"--config-file={config}", "--nofork", "--print-address=1"],
                stdout=subprocess.PIPE, stderr=broker_error, text=True)
            processes.append(broker)
            assert broker.stdout is not None
            address = broker.stdout.readline().strip()
            if not address.startswith("unix:"):
                raise RuntimeError("The private D-Bus daemon did not publish a Unix address")
            environment = dict(os.environ, DBUS_SYSTEM_BUS_ADDRESS=address)
            fixture_path = output_dir / f"{name}-fixture.log"
            fixture_log = fixture_path.open("w")
            logs.append(fixture_log)
            fixture = subprocess.Popen(
                ["/usr/bin/python3", str(HERE / "dbus_fixture.py")],
                env=environment, stdout=fixture_log, stderr=subprocess.STDOUT, text=True)
            processes.append(fixture)
            wait_for_marker(fixture_path, "FIXTURE_READY", fixture)

            client_path = output_dir / f"{name}-monitor.log"
            client_log = client_path.open("w")
            logs.append(client_log)
            client = subprocess.Popen([str(client_binary)], env=environment,
                                      stdout=client_log, stderr=subprocess.STDOUT, text=True)
            processes.append(client)
            wait_for_marker(client_path, "MONITOR_READY", client)
            side_bus = dbus.bus.BusConnection(address)
            device_control = dbus.Interface(
                side_bus.get_object("org.bluez", "/"), "org.librepods.Test")
            bus_control = dbus.Interface(
                side_bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus"),
                "org.freedesktop.DBus")
            if connection_cycles:
                device_control.CycleConnections(timeout=5)
            else:
                device_control.Start(dbus.UInt32(1500), dbus.UInt32(messages),
                                     dbus.UInt32(65536), dbus.String(noise_sender), timeout=5)
            started = time.monotonic()
            high_since = None
            longest_backpressure = 0.0
            next_side_probe = started
            side_replies = 0
            samples = []
            while time.monotonic() - started < duration:
                if client.poll() is not None or fixture.poll() is not None:
                    raise RuntimeError(f"A probe process exited early; inspect {name} logs")
                now = time.monotonic()
                client_recv, broker_send = queues(client.pid, broker.pid)
                samples.append({"elapsed_s": round(now - started, 3),
                                "client_recv": client_recv, "broker_send": broker_send,
                                "rss_kib": rss_kib(client.pid)})
                if client_recv >= 32768 and broker_send >= 65536:
                    if high_since is None:
                        high_since = now
                    longest_backpressure = max(longest_backpressure, now - high_since)
                else:
                    high_since = None
                if now >= next_side_probe:
                    bus_control.GetId(timeout=1)
                    side_replies += 1
                    next_side_probe = now + 2
                time.sleep(0.1)

            observations = []
            for line in client_path.read_text().splitlines():
                if line.startswith("{"):
                    observations.append(json.loads(line))
            result = {
                "case": name, "noise_messages": messages,
                "noise_sender": noise_sender,
                "noise_payload_bytes": 65536,
                "client_recv_peak": max(sample["client_recv"] for sample in samples),
                "broker_send_peak": max(sample["broker_send"] for sample in samples),
                "rss_peak_kib": max(sample["rss_kib"] for sample in samples),
                "persistent_backpressure_s": round(longest_backpressure, 3),
                "side_client_replies": side_replies,
                "last_monitor_observation": observations[-1] if observations else None,
                "fixture_finished_load": (
                    "CONNECTION_CYCLES_SENT" if connection_cycles else "LOAD_SENT"
                ) in fixture_path.read_text(),
            }
            (output_dir / f"{name}-samples.json").write_text(json.dumps(samples, indent=2))
            (output_dir / f"{name}-result.json").write_text(json.dumps(result, indent=2))
            print(json.dumps(result), flush=True)
            return result
        finally:
            for process in reversed(processes):
                stop(process)
            for log in logs:
                log.close()


def assert_messages_processed(result, expected_connections):
    if not result["fixture_finished_load"]:
        raise AssertionError(f"{result['case']}: fixture did not finish sending its load")
    observation = result["last_monitor_observation"]
    if observation is None or observation["bus_replies"] < 1:
        raise AssertionError(f"{result['case']}: monitor did not resume bus reply processing")
    events = observation["device_events"]
    if [event["connected"] for event in events] != expected_connections:
        raise AssertionError(f"{result['case']}: missing or reordered device notifications: {events}")
    if any(event["address"] != "00:11:22:33:44:55"
           or event["name"] != "LibrePods test device" for event in events):
        raise AssertionError(f"{result['case']}: device identity changed: {events}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--client", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--control-only", action="store_true",
                        help="Run only the slow-reply control (used for observer fault injection)")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    control = run_case(args.client.resolve(), args.output, "slow-bluez-control", 0, 6)
    if control["persistent_backpressure_s"] >= 2:
        raise AssertionError("Control already has persistent socket backpressure")
    assert_messages_processed(control, [True])
    if args.control_only:
        return
    stressed = run_case(args.client.resolve(), args.output, "unrelated-properties-pressure", 4096, 14)
    if stressed["persistent_backpressure_s"] >= 2:
        raise AssertionError(
            "Unrelated PropertiesChanged traffic stopped the monitor from draining its "
            "D-Bus socket for at least two seconds; actual paired socket queues above")
    assert_messages_processed(stressed, [True])
    bluez_noise = run_case(args.client.resolve(), args.output,
                          "bluez-non-device-pressure", 4096, 14, noise_sender="bluez")
    if bluez_noise["persistent_backpressure_s"] >= 2:
        raise AssertionError(
            "BlueZ's non-device PropertiesChanged traffic stopped the monitor from "
            "draining its D-Bus socket for at least two seconds")
    assert_messages_processed(bluez_noise, [True])
    cycles = run_case(args.client.resolve(), args.output, "device-reconnect-cycles", 0, 3,
                      connection_cycles=True)
    assert_messages_processed(cycles, [True, False, True, False, True])


if __name__ == "__main__":
    main()
