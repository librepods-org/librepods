#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""The observer CLI must reject an actual ss response missing its peer row."""

import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
SS_SHIM = '''#!/usr/bin/python3
import json
import os
import subprocess
import sys

lines = subprocess.check_output([os.environ["SS_REAL_PATH"], *sys.argv[1:]], text=True).splitlines()
clients = [line for line in lines if '"bluetooth' in line and line.startswith("u_str ESTAB")]
if not clients:
    raise RuntimeError("Invalid fault fixture: real monitor socket row not found")
client_inodes = {(line.split()[5], line.split()[7]) for line in clients}
peers = [line for line in lines if len(line.split()) >= 8
         and (line.split()[7], line.split()[5]) in client_inodes and line not in clients]
if not peers:
    raise RuntimeError("Invalid fault fixture: real reciprocal peer was not observable")
with open(os.environ["SS_FAULT_AUDIT"], "a") as audit:
    audit.write(json.dumps({"client_rows": len(clients), "omitted_peer_rows": len(peers)}) + "\\n")
print("\\n".join(clients))
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--client", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    real_ss = shutil.which("ss")
    if real_ss is None:
        raise RuntimeError("ss is unavailable; cannot create the sampling fault")
    scratch = Path(os.environ.get("TMPDIR", Path.cwd() / ".diagnostics-scratch"))
    scratch.mkdir(parents=True, exist_ok=True)
    audit_path = args.output.resolve() / "ss-fault-audit.jsonl"

    with tempfile.TemporaryDirectory(prefix="librepods-ss-fault-", dir=scratch) as temporary:
        command = Path(temporary) / "ss"
        command.write_text(SS_SHIM)
        command.chmod(0o755)
        environment = dict(os.environ, PATH=temporary + os.pathsep + os.environ["PATH"],
                           SS_REAL_PATH=real_ss, SS_FAULT_AUDIT=str(audit_path))
        child = subprocess.Popen(
            ["/usr/bin/python3", str(HERE / "test_dbus_backpressure.py"),
             "--client", str(args.client.resolve()), "--output", str(args.output.resolve() / "child"),
             "--control-only"],
            env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, start_new_session=True)
        try:
            stdout, stderr = child.communicate(timeout=30)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.communicate()
            raise RuntimeError("Observer CLI timed out while rejecting missing peer data")

    (args.output / "observer-cli.log").write_text(stdout + stderr)
    audits = [json.loads(line) for line in audit_path.read_text().splitlines()]
    if not audits or any(row["client_rows"] < 1 or row["omitted_peer_rows"] < 1 for row in audits):
        raise AssertionError("Missing-peer injection was not exercised on a real socket pair")
    result = {"fault": "missing_reciprocal_peer", "cli_exit_code": child.returncode,
              "sampling_calls": len(audits), "injection_validated": True,
              "rejected": child.returncode != 0
                          and "Cannot observe a unique reciprocal D-Bus socket pair" in stderr}
    (args.output / "observer-fault-status.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result), flush=True)
    if not result["rejected"]:
        raise AssertionError("Observer CLI accepted unmeasured peer data as a passing zero queue")


if __name__ == "__main__":
    main()
