# Heart rate on Windows — status

**Short version: there is no working version of heart-rate monitoring on Windows
yet.** The AirPods Pro 3 *accept* the heart-rate request and *acknowledge* it, but
they never send any readings. The feature is therefore **off by default** and hidden
behind a warning in **Settings ▸ Experimental**. This page records everything that
was tested, so nobody has to repeat it.

Tested on AirPods Pro 3 (A3063), AAP-reported firmware `81.2675000075000000.6877`.
Worth knowing: nobody has heart rate working on this firmware generation on **any**
platform, including Android — on older firmware it did work there. So the firmware
version is a factor as much as the host.

---

## What we send (byte-identical to the working clients)

The daemon drives the exact same AAP sequence, byte for byte, that the working
Android/iOS clients use — same opcodes, same constants, same ordering:

| Step | Frame |
|------|-------|
| connect service 0 | `00 00 00 00 01 00 03 …` |
| capabilities 0    | `04 00 00 00 01 00 00` |
| connect service 4 | `00 00 04 00 01 00 03 …` |
| capabilities 4    | `04 00 04 00 01 00 00` |
| `HRM_STATE` (0x30) enable | `04 00 04 00 09 00 30 01 00 00 00` |
| `HEART_RATE_START_1S`     | `04 00 04 00 17 00 00 00 10 00 10 00 08 e3 46 42 0b 08 13 10 02 1a 05 01 40 42 0f 00` |

(The `e3 46` in the START frame is Android's exact varint constant for the 1 s
period; we pinned it to rule out a value mismatch.)

The AirPods reply with the ACK `4a 02 08 13` — i.e. they *understood and
accepted* the enable — but the data frame that carries a reading
(`08 13 1A 12 <18-byte payload>`, BPM = `payload[1]`) **never arrives**.

## What we ruled out (this is not our code)

Every plausible transport/host cause was investigated and eliminated:

- **Not the sequence / timing.** Byte-identical to the working clients, with the
  same inter-step delays and quiet period. Both buds in-ear, re-paired,
  rebooted.
- **Not L2CAP ERTM (Enhanced Retransmission Mode).** We tried opening the AAP
  channel with `BRB_L2CA_OPEN_ENHANCED_CHANNEL` + `CM_RETRANSMISSION_AND_FLOW`.
  Windows `bthport` does **not** serialize the ERTM RFC option to the wire for a
  client profile driver (proven across two driver builds). And it wouldn't have
  mattered: the working Android runs AAP over **Basic mode** anyway
  (`l2c_link_adjust_chnl_allocation: FCR Mode:0`), which is what our driver uses.
- **Not encryption.** We opened the AAP channel with encryption *required* —
  `CF_LINK_ENCRYPTED` in the BRB `ChannelFlags` — and confirmed it connects,
  encrypted; the AirPods still ACK service 19 and stream **zero** readings. (An
  earlier attempt also put `CF_LINK_ENCRYPTED` in `ConfigOut/In.Flags`, which are
  a `CFG_*` option bitmask — not link flags — and that broke every connect with
  `STATUS_INVALID_PARAMETER 0xC000000D`. That was a field-placement bug, not
  encryption; with it in the correct field the encrypted channel changes nothing.)
- **Not a missing capability.** The AirPods themselves advertise the `HRM_STATE`
  (0x30) capability to us and ACK the enable — they simply withhold the data.
- **Not descriptor enumeration.** Proper protobuf parsing of
  `request_all_descriptors` confirmed our unit returns only `devmotion6`, never
  the `HEARTRATE` descriptor — even on a unit that a working host *does* get HR
  from. The gate is applied by the AirPods, per-host.

Even the LibrePods author gets no data from his unit on a non-Apple host.

## The leading explanation: a host-privilege gate, not host identity

AirPods restrict biometric (heart-rate) data, but the restriction is **not on the
host's *identity*** — it's on being the buds' **primary, privileged host**. Two
independent lines of evidence settle this.

**All three possible Device-ID records were tested — none unlock HR.** The one
vendor knob Windows exposes is the local **Device ID (PnP/SDP `0x1200`)** record
under `BTHPORT\Parameters`. We set each value, rebooted to republish, and re-tested
with the iPhone off and both buds in-ear:

| Host DID | Identity it presents | Result |
|---|---|---|
| `004C:2027:0100` | a real AirPod's own record | ACK, no readings |
| `004C:0000:0000` | Android's spoof (vendor only) | ACK, no readings |
| `004C:7805:1A50` | **an iPhone's exact record** | ACK, no readings |

The channel connects identically in all three, and HR stays blocked in all three.
`0x7805` means "Apple host", not specifically "iPhone".

**macOS — whose DID is byte-identical to an iPhone's — behaves the same.** A Mac
presents the exact `004C:7805:1A50` record (all 54 Host-Identification bytes match
an iPhone). Opening the AAP channel from a Mac (user-space IOBluetooth, no driver)
and replaying the same enable gets the same `4a 02 08 13` ACK and **zero** readings
(see `extras/macos-hr-probe/` on the `macos-hr-probe` branch). So a host already
carrying the iPhone DID, on a completely different OS, is blocked too — identity is
not the gate.

**What actually separates the working hosts from us is privilege / owning the host
session:**

- **iPhone** — is the buds' real primary host *and* runs Apple's own on-device
  software (the iPhone computes BPM from the raw PPG + motion stream; see Apple
  support HT `123184`). It works because it is the privileged system host, not
  merely because it "is an iPhone".
- **Android (the working LibrePods)** — is *not* an ordinary app. It ships a Magisk
  module (a privileged system app) and reaches the channel by reflection *inside*
  the open Bluetooth stack (Fluoride). It effectively **becomes the host** from
  within the stack.
- **Windows / macOS (us)** — open a **second, unprivileged AAP session** while the
  OS stays the buds' real host. The buds ACK, then withhold the biometric stream.

Seen this way the gate is **consistent on every platform: the raw AAP biometric
stream goes only to the privileged, primary-host system software.** On iOS that is
Apple's own stack — it receives the raw PPG, computes the BPM, and exposes the
*result* through **HealthKit**, a permission-gated system API. Third-party iOS apps
(Strava, etc.) read that already-computed value **from HealthKit**; they never open
the AAP biometric channel themselves. So iOS apps aren't "beating" the gate — they
consume the value over the sanctioned, system-mediated path, one step removed from
the buds.

What LibrePods does on Windows/macOS is the **direct** path — a secondary AAP
session asking the buds for the stream — and that is exactly what the buds withhold
from a non-primary host. Windows has **neither** option Apple's platform offers: it
can't be the primary-host system (`bthport` is closed — no in-stack hook point like
Fluoride's, so LibrePods can only run a secondary AAP session via our profile driver
alongside the OS's real host relationship, never inside it), and there is no
HealthKit-equivalent system component ingesting the AAP biometric stream for us to
read a computed value from. So the request goes through, the AirPods acknowledge it,
and they return no readings.

## What the toggle does

**Settings ▸ Experimental ▸ "Show heart-rate monitoring (experimental)"** only
un-hides the heart-rate card on the device page. It does **not** make the feature
work. Expect the card to stay empty. It's kept for the day a working path on Windows
is found.

## References

- `windows/drivers/aap/L2cap.c` — the driver runs the AAP channel
  in L2CAP **Basic** mode (the ERTM/encryption experiments are documented in the
  comments there).
- `windows/daemon/src/main.rs` — `hr_retry_campaign` / the HR
  enable + start sequence and constants (`HR_START_SEQ`, `HR_INIT_QUIET_MS`, …).
- `extras/macos-hr-probe/` (branch `macos-hr-probe`) — the independent macOS
  user-space probe that reaches the same conclusion from a host whose Device ID is
  byte-identical to an iPhone's (buds ACK `4a 02 08 13`, then send nothing).
