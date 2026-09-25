# LibrePods — Windows docs

Notes for the Windows port: the drivers, the daemon, and the reverse-engineering
that each feature needed.

- **[`daemon-ipc/PLAN.md`](daemon-ipc/PLAN.md)** — the daemon + named-pipe IPC
  design: why `librepodsd` owns the drivers and the UIs are thin clients.
- **[`hires-mic/PLAN.md`](hires-mic/PLAN.md)** — the hi-res AirPods microphone:
  the AAP uplink protocol, AAC-ELD decoding, and the virtual audio driver.
- **[`heart-rate.md`](heart-rate.md)** — why there is no working heart-rate
  monitoring on Windows yet, and everything that was ruled out.
- **[`aap-packet-discovery.md`](aap-packet-discovery.md)** — how to capture
  ground-truth AAP traffic on macOS/iOS to confirm or discover packets.

Setup and usage live in the **[Windows README](../README.md)**; the drivers have
their own notes in [`../drivers/aap`](../drivers/aap) and
[`../drivers/mic`](../drivers/mic).

## Other platforms
- **Linux** — [`linux/README.md`](../../linux/README.md) (BlueZ/PipeWire, no
  port-specific driver needed).
- **Android** — the repo-root [README](../../README.md) and the app sources.

## Protocol (OS-agnostic)
AAP/AACP notes apply to every platform:
**[`AAP Definitions.md`](../../AAP%20Definitions.md)**,
**[`docs/control_commands.md`](../../docs/control_commands.md)**,
**[`docs/opcodes.md`](../../docs/opcodes.md)**,
**[`docs/device-info.md`](../../docs/device-info.md)**.
