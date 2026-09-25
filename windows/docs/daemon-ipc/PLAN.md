# LibrePods Windows daemon + IPC — plan

> **Status: done and shipped.** `librepodsd` owns the drivers, the AAP session and
> the mic pipeline; the WinUI app is a thin IPC client. The two Rust front-ends this
> plan was written against (`librepods-tray.exe` and the iced `librepods.exe`) no
> longer exist on Windows — read their names below as history. See the Status
> section at the bottom for where things actually landed.

**Goal:** kill the exclusive-driver tug-of-war. Today both `librepods-tray.exe`
and `librepods.exe` want the single, **exclusive** driver handle, so only one can
run — hence the fragile "Open App" handoff, lingering daemon/zombie processes,
and duplicated AAP code in two binaries.

**Fix (the Gemini architecture):** one headless **daemon owns the driver + the
AAP session + the mic pipeline**; the tray and the full app become thin **IPC
clients**. They can run **at the same time**, nobody fights over the handle, and
battery/mic keep working even with no UI open.

```
                 ┌─ librepods-tray.exe   (UI client, light)
librepodsd.exe ──┤       IPC: \\.\pipe\LibrePods  (NDJSON)
(owns the driver)└─ librepods.exe        (UI client, iced GUI)
```

## Components

- **`librepodsd`** (new, `windows/daemon/`) — headless. Owns the driver
  handle + AAP session (today's tray `run_receiver`), the mic pipeline (decode
  AAC-ELD → feed `\\.\LibrePodsMic`), the auto-activate poll + A2DP reset, and
  the dynamic-rename trigger. Holds the **authoritative state**. Runs an IPC
  server. Single-instance (named mutex).
- **`librepods-ipc`** (new lib crate) — the shared `Command` / `Event` serde
  types + a tiny NDJSON framing helper, so daemon and clients agree on the wire.
- **`librepods-tray`** (refactored) — pure UI client: connects to the pipe
  (spawns the daemon if absent), renders the icon/menu/overlay from daemon
  events, sends commands. Its `driver`/`aap`/`eld`/`micpipe`/`a2dp` modules
  **move into the daemon**.
- **`librepods.exe`** (app, Windows) — becomes an IPC client too
  (Phase 3): its `platform/windows` backend talks to the daemon instead of the
  driver directly. On **Linux nothing changes** (still `bluer`, no daemon).

## IPC protocol

- **Transport:** Windows **named pipe** `\\.\pipe\LibrePods`, duplex, message
  mode, **multi-instance** (one pipe instance per connected client). Access
  restricted to the current user.
- **Framing:** newline-delimited JSON (NDJSON) — one `serde_json` value per line.
- **Client → Daemon `Command`:**
  - `Hello { kind: "tray" | "app" }` — sent on connect; daemon replies with a
    full `State` snapshot.
  - `SetAnc(u8)` (1..=4)
  - `SetMicMode { auto: bool, manual: bool }`
  - `GetState`
  - (volume was planned as **client-side** WASAPI; it ended up in the daemon
    instead — see the Status section, `SetVolume` / `StepVolume` / `ToggleMute`.)
- **Daemon → Client `Event`:**
  - `State(Snapshot { connected, battery{l,r,case,headphone}, anc, dev_name,
    mic_recording, auto_mode })` — pushed on every change, and once on connect.
  - `Overlay { title, body }` — a notification for the client to render (the
    daemon decides *when*; the tray/app draw it with their overlay UI).

## Lifecycle

- **Start:** the tray autostarts at login (as now) and **ensures the daemon is
  running** — if the pipe isn't there, it spawns `librepodsd.exe`, then connects.
  Client-spawns-daemon = no separate autostart entry, robust.
- **Single-instance:** the daemon holds a named mutex; a second spawn exits.
- **Death/restart:** a client that sees the pipe drop retries/reconnects (and
  re-spawns the daemon if needed). The daemon keeps running when UIs close.
- **Shutdown:** closing a UI just disconnects its pipe; the daemon lives on.
  (A tray "Quit LibrePods" can send a `Shutdown` that stops the daemon too.)

## Migration — incremental, nothing breaks between phases

1. **Daemon core.** New `librepodsd` + `librepods-ipc`. Move `run_receiver` +
   the auto-activate poll + the mic pipeline + `State` out of the tray into the
   daemon. Add the NDJSON named-pipe server; broadcast `State`/`Overlay`. Test
   the daemon standalone (it logs; the mic + battery work with no UI).
2. **Tray → client.** Strip the tray's driver/aap/eld/micpipe/a2dp; it connects
   to the daemon, renders from `Event`s, sends `Command`s, spawns the daemon if
   absent. **This alone ends the handle conflict for the tray** and deletes the
   "Open App handoff" hack.
3. **App → client.** Route app's `platform/windows` L2CAP/session
   backend through the daemon IPC. Now tray + full app coexist. (Heaviest phase —
   touches the shared cross-platform code; Linux path untouched.)
4. **Polish.** Daemon single-instance + autostart-on-demand + reconnect; installer
   ships `librepodsd.exe` and drops the exclusive-handoff shortcut logic.

## Status — DONE ✅ (validated on hardware)

- **`librepodsd`** owns both drivers, the AAP session and the hi-res mic; the UI is
  a thin IPC client. Confirmed on hardware: battery / ANC / volume / mic / ear
  detection / hearing aid all shown and controlled over IPC, auto-reconnect,
  overlay cards.
- **IPC = two one-directional named pipes** (`PIPE_EVENTS` daemon→client,
  `PIPE_CMDS` client→daemon), NDJSON, **async** (per-connection queue + writer
  thread each side). This was forced by two bugs hit on the way:
  1. **Sync-handle serialization / deadlock** — a single *duplex* pipe deadlocked:
     a Windows synchronous handle serializes I/O, so the daemon's blocking
     ReadFile (commands) stalled its WriteFile (events) on the same handle (it
     wrote 2 messages then hung). Split into two one-directional pipes.
  2. **UI freeze** — a synchronous blocking WriteFile on the tray's UI thread
     froze the menu. Both sides now decouple I/O onto their own threads.
  - Also: the named-pipe DACL must grant the same-user client (`D:(A;;GA;;;AU)…`);
    the default null descriptor denied it.
- **Volume moved into the daemon** (`daemon/src/volume.rs`, WASAPI
  `IAudioEndpointVolume`), against the original plan: the daemon is then the single
  arbiter, reports volume in the `Snapshot`, serves `SetVolume` / `StepVolume` /
  `ToggleMute`, and can duck for Conversational Awareness without an IPC round-trip.
- **The command surface grew well past the sketch** — ANC, feature toggles
  (conversational awareness, adaptive volume, allow-off), per-control values, mic
  mode, hearing aid, heart rate (experimental), rename, connect / disconnect /
  repair, shutdown. See `ipc/src/lib.rs` for the wire types.
- **BLE proximity** (beyond the plan): `le.rs` watches passively — and only while
  disconnected — for the AirPods proximity advertisement. It first drove a
  "connect?" prompt (`Event::ConnectPrompt`); today the daemon **auto-connects**
  when they appear and re-arms after a >45 s absence.
- **Single front-end, not two.** The plan assumed the tray and the iced app would
  coexist as clients. In practice the Rust tray was retired and the iced app was
  never ported: the **WinUI 3 app carries its own tray** and is the only client, so
  "Phase 3" was resolved by replacing the front-end rather than porting it. The IPC
  design is unchanged, and nothing stops a second client from connecting.

## Phase 3 — the "web-app" model (historical)

The original Phase 3 was to make the iced `librepods.exe` a client too, so tray and
app could coexist with the daemon as the single arbiter — every action atomic and
serialized through the server, no dual AAP sessions. That framing still describes
the architecture; it was reached by writing the **WinUI 3 client** instead of
porting the iced app, which was then dropped on Windows.

## Mic aligns with PR #655 ✅

Our Windows hi-res mic uses the **same** AAP commands (`START_AUDIO`/`STOP_AUDIO`,
0x58), AAC-ELD params (64 kHz true → resample 48 kHz, 480-sample/7.5 ms mono, ASC
`F8 E6 30 00`), 0x58 framing (22-byte AU header), and decoder (FFmpeg libavcodec,
LGPL) as Linux [PR #655](https://github.com/librepods-org/librepods/pull/655) —
so the decode/protocol is shareable. Deltas: we add a ×3 make-up gain with a tanh
soft-limit (the mic ran quiet), and our stall handling deliberately **never tears
down the channel** — it re-sends `START_AUDIO` in place (rate-limited to once per
5 s), because a silent uplink is usually just nobody speaking. Unifying the decode
into a shared crate is still open.

## Risks / notes

- **Multi-client pipe:** the daemon serves several pipe instances at once and
  broadcasts to all — one reader thread per client + a shared broadcast channel.
- **Mic ownership:** the daemon is the single owner of both the AAP driver and
  `\\.\LibrePodsMic`, so no UI can take the handles from under it.
- **Shared modules:** `aap` / `driver` / `eld` / `micpipe` / `a2dp` / `volume` all
  live in the daemon. If in-proc access is ever needed elsewhere, split them into a
  small `librepods-win-core` lib.
