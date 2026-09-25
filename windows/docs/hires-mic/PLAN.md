# Hi-res AirPods microphone on Windows

**Status: shipped** — the feature works end to end and is on by default (see the
Status section at the bottom for what each phase settled). Developed on the branch
`windows-hires-mic`, now part of the Windows port.

**Goal:** expose the AirPods' hi-res (AAC-ELD) microphone as a **native Windows
microphone**, so any app (Teams, Zoom, Discord, OBS…) can use it — the same
feature the Linux side is adding in
[PR #655](https://github.com/librepods-org/librepods/pull/655), but with a
self-contained **virtual audio driver** instead of PipeWire.

## Why a driver (not VB-Cable)

Windows has no API to "create a virtual microphone" — it needs a virtual audio
device driver. We already ship a signed kernel driver (`LibrePodsAAP`), so a
second one is the clean, dependency-free path (no third-party VB-Cable). Base it
on Microsoft's **SYSVAD** sample (a virtual audio device with capture + render
endpoints, no hardware). NOTE: audio drivers use **PortCls/AVStream** or the
newer **ACX** framework — different from the KMDF+BRB approach of `LibrePodsAAP`.
*(What we ended up using is the MS **ACX `AudioCodec`** sample rather than SYSVAD —
ACX on KMDF, already ROOT-enumerated and with a capture circuit.)*

## Architecture

```
AirPods ──AAP / L2CAP──▶ LibrePodsAAP driver ──IOCTL──▶ app
                                                         │  decode AAC-ELD (FFmpeg / libavcodec)
                                                         │  → PCM
                                                         ▼
                                        LibrePodsMic virtual audio driver
                                                         │
                                                         ▼
                                   Windows sees a "LibrePods Microphone"
                                   (Teams / Zoom / Discord / OBS / …)
```

The protocol (enable-mic control command, uplink packet framing, AAC-ELD params)
is platform-neutral and comes from PR #655 — it lands in the shared
`app` crate (`aacp`/`media_controller`), gated per platform for
the sink.

## Phases (incremental, each independently testable)

1. **Virtual-mic driver base** — build + test-sign + install SYSVAD (or a trimmed
   fork) so Windows shows a "LibrePods Microphone" capture endpoint. Prove it
   appears in Sound settings and apps. *(driver: `windows/drivers/mic/`)*
2. **PCM bridge** — an IOCTL/shared-ring for user mode to push PCM samples into
   the driver; feed a test tone / a WAV → verify it's audible on the virtual mic
   (record in Voice Recorder / Audacity).
3. **Protocol port** — from PR #655: the AAP command that enables the hi-res mic,
   the uplink packet framing + watchdog, and AAC-ELD decoding via FFmpeg
   (libavcodec) — in the shared crate, `platform::MicSink` for the OS sink.
4. **Integration** — talk into the AirPods → decoded audio reaches the virtual
   mic. Conversation-awareness pause during capture, AGC toggle, settings
   persistence (mirror #655).

## Risks / open questions

- **Audio driver complexity** — WaveRT buffering, formats, timing. SYSVAD is a
  large sample; getting a stable capture endpoint is the bulk of the work.
- **AAC-ELD patents** — the decoder (FFmpeg) is patent-encumbered; distribution
  implications (PR #655 raises the same). Keep the decoder optional / documented.
- **Latency** — L2CAP → decode → driver ring; needs a small, steady buffer.
- **Test-signing** — same Test Mode requirement as the AAP driver.

## References

- Microsoft SYSVAD (virtual audio device sample), and the ACX audio samples.
- LibrePods PR #655 (Linux hi-res mic: AAC-ELD + PipeWire) — the protocol RE.
- `../windows/drivers/aap/` — our existing driver + build/sign/install loop.

## Status

- **Phase 1 — DONE** ✅ (`windows/drivers/mic/`, based on the MS ACX
  AudioCodec sample). Builds with WDK 28000, installs via `install.ps1`, and
  Windows shows a virtual **"Microphone (AudioCodec Device)"** (confirmed on
  hardware). The audio source is still the sample's dummy; feeding real audio is
  next.
- **Phase 1b — DONE** ✅ (`522f9e8`): trimmed to **capture-only** — dropped the
  render (speaker) circuit in `Device.cpp` (create/add/remove) so only a mic
  endpoint exists (no phantom speaker grabbing default output). Builds clean.
  `install.ps1` now `devcon remove`s any prior device before installing so
  re-running updates in place.
- **Phase 2 — DONE** ✅ (`87ed69f`, validated on hardware): `Common/MicPipe.{h,cpp}`
  — a spin-locked global PCM ring buffer + a control device `\\.\LibrePodsMic`
  exposing `IOCTL_LIBREPODS_MIC_WRITE_PCM` (0x0022A000). `StreamEngine.cpp`
  `ProcessPacket` drains the ring instead of the WAV/tone dummy. Proven end to
  end: `lp-mic-test` (a user-mode tone feeder, `windows/tools/mic-test/`)
  pushed a 440 Hz sine and it was **recorded and audible** on "Microphone
  (AudioCodec Device)". The audio-driver de-risk is complete.
- **Phase 3a — DONE** ✅ (`c843d32`, validated on hardware): the AAP enable
  command works. The tray's "Hi-res microphone (test)" toggle sends `START_AUDIO`
  (`04 00 04 00 58 …`, from PR #655); the AirPods enter mic mode (A2DP playback
  drops to right-only mono, as expected — needs an A2DP reset like #655), and the
  receive loop confirmed the **0x58 uplink audio packets flow** ("receiving
  audio" card). Constants + `is_audio_packet` in `aap.rs`; protocol in
  [[hires-mic-protocol]].
- **Phase 3b — DONE** ✅ (validated on hardware, user's voice recorded clean &
  in tune). The tray decodes the 0x58 AUs (AAC-ELD) via an FFmpeg libavcodec
  shim (LGPL, `eld_shim.c`), resamples, and streams to `\\.\LibrePodsMic`. Key
  fixes: mic frames are **480 samples @ 64 kHz** (not 48 kHz — the 4-byte ASC
  lies; confirmed by the +180/AU timestamp = a 24 kHz clock over 7.5 ms frames),
  so resample **64000 → 48000**; capture circuit restricted to **48 kHz only**;
  and a **silence cushion** on the ring (80 ms in the shipped code) to absorb the
  bursty per-packet feed. Pitch went ~105 → ~122 Hz (ref ~148), zero click/gap artifacts. Audio is
  a bit quiet (a gain stage would help) and mono (single mic capsule).
- **Phase 4 — essentially COMPLETE** ✅ (plug-and-play). Done:
  - **A2DP auto-reset** — toggle the AirPods' AudioSink (0x110B) service to
    restore stereo after the mic stops (0x110D gave ERROR 1060; found via
    BluetoothEnumerateInstalledServices), with a persistent "Restoring stereo…"
    card through the reconnect.
  - **Auto-enable** — the driver's capture-activity counter
    (IOCTL_LIBREPODS_MIC_STATUS) lets the tray auto-start the hi-res stream when
    an app records and auto-stop (debounced) when it finishes. + a manual mode.
  - **Make-up gain** (×3, tanh soft-limit) so the mic isn't quiet.
  - **Minimal FFmpeg** (7.1, aac-only) — avcodec 69 MB → 0.7 MB.
  - **Name** "LibrePods" (device-agnostic).
  - Single-instance guard.

> The pipeline described above as living in "the tray" was later moved into
> `librepodsd`, which now owns the drivers and the decode; the UI is a thin IPC
> client. See [`../daemon-ipc/PLAN.md`](../daemon-ipc/PLAN.md).

**Follow-ups still open:** exact per-device dynamic name (IPolicyConfig, needs a
debugger), VendorID spoofing (Apple DID), and unifying the decode with PR #655 in a
shared crate. Robustness lessons learned since (all applied in the daemon): never
tear down the AAP L2CAP channel for a stall — a silence is usually just nobody
speaking, so re-send `START_AUDIO` in place, rate-limited; re-arm `START_AUDIO`
after any reconnect, because the AirPods forget stream state; and open the ATT
channel lazily, since an idle one gets torn down and stalls AAP on the shared ACL.
