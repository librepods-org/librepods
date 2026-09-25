# LibrePods on Windows

Open-source AirPods control for Windows: battery, noise control, ear detection,
conversational awareness, volume/mute, hearing aid, device rename — and the
AirPods' **hi-res microphone as a real Windows input**, so any app can use it.

It has these parts:

1. **`LibrePodsAAP` kernel driver** ([`drivers/aap`](drivers/aap)) — opens the Apple
   Accessory Protocol (AAP) L2CAP channel to the AirPods in kernel mode, which
   normal Windows apps cannot do, and exposes it via IOCTLs.
2. **`LibrePodsMic` kernel driver** ([`drivers/mic`](drivers/mic)) — a virtual audio
   device that publishes the AirPods' decoded hi-res mic as a Windows capture
   endpoint (Teams / Zoom / Discord / OBS …).
3. **`librepodsd` daemon** ([`daemon`](daemon)) — owns both drivers, the AAP session
   and the mic pipeline (AAC-ELD decode), and serves UI clients over named-pipe IPC.
   It holds the authoritative state.
4. **`librepods-winui` app** ([`winui`](winui)) — the native **WinUI 3** client; it
   lives in the system tray (closing hides it there) and is an IPC client of the
   daemon. It's what you run day-to-day.

Works with any AirPods (2/3, Pro 1/2/3, Max) and Apple Beats — the driver binds
to the AAP service every AirPod advertises, not to a specific model. Features
shown depend on the model (e.g. only Pro/Max have noise control).

---

## 1. Install (one-time)

> ⚠️ The drivers aren't signed by Microsoft, so Windows must run in **Test Mode**
> (same requirement as the commercial MagicAAP driver). This lowers a security
> setting. **Advanced users only** — a system restore point is recommended.

### a) Turn on Test Mode
1. Back up your **BitLocker recovery key** (if BitLocker is on) and make a
   **restore point**.
2. Disable **Secure Boot** in your firmware/BIOS (it blocks test-signed drivers).
3. In an **admin** PowerShell: `bcdedit /set testsigning on` → **reboot**.
   You should see "Test Mode" in the bottom-right of the desktop.

### b) Install — the one-shot way (recommended)
Download the CI release artifact (or build it yourself with
[`installer/make-dist.ps1`](installer/make-dist.ps1)), extract it, and run
[`installer/install.ps1`](installer/install.ps1) from an **admin** PowerShell
inside the extracted folder:

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

It checks Test Mode is on, test-signs and installs **both** drivers, copies
`librepodsd.exe` + `librepods-winui.exe` (and the FFmpeg DLLs) to
`%LOCALAPPDATA%\LibrePods`, registers the driver-recovery and mic-rename tasks,
and adds the app to startup. The folder is self-contained: no Windows SDK/WDK,
Visual Studio or VC++ redistributable needed. Reboot afterwards.

### c) Install — from the repo (prebuilt driver packages)
Both drivers are committed prebuilt, so you can install them without building:

```powershell
.\drivers\aap\install.ps1 -PackageDir .\drivers\aap\prebuilt   # AAP channel
.\drivers\mic\install.ps1 -Dir .\drivers\mic\prebuilt          # virtual mic
```

Success for the AAP driver shows `Driver package installed on device:
BTHENUM\{74ec2172-...}`. Check it loaded (should be `OK`, not error 52):
```powershell
Get-PnpDevice -FriendlyName "LibrePods AAP*" | Select Status
```
The mic driver creates a `ROOT\AudioCodec` device via `devcon`; a virtual
microphone should appear in **Sound ▸ Input**.

### Building the drivers yourself (optional)
Needs Visual Studio 2022/2026 with **Desktop development with C++** + **Spectre
x64/x86 libs** + a **Windows 11 SDK** and the **matching WDK** (SDK & WDK build
numbers must match, e.g. `28000`). See
[`drivers/aap/README.md`](drivers/aap/README.md) and
[`drivers/mic/README.md`](drivers/mic/README.md).

### Uninstall / revert
```powershell
pnputil /delete-driver oem<N>.inf /uninstall   # find <N> with: pnputil /enum-drivers
bcdedit /set testsigning off                    # then re-enable Secure Boot in BIOS
```

---

## 2. Run the app

Two pieces run: the **daemon** (`librepodsd.exe`, headless) and the **WinUI app**
(`librepods-winui.exe`). Build the daemon natively on Windows, or from WSL/Linux
(cross-compiled): `cargo build --release --target x86_64-pc-windows-gnu` in
`daemon/` — run `daemon/fetch-ffmpeg.sh` first, the FFmpeg slice used for AAC-ELD
decoding is fetched, not committed. Build the WinUI app with `dotnet build`. The
CI's release artifact bundles both plus the FFmpeg DLLs and the prebuilt drivers.

Launch `librepods-winui.exe`; it auto-starts the daemon, shows a tray icon, and its
window hides to the tray on close. To start it at login, use the installer (which
registers it) or [`startup.ps1`](startup.ps1).

### How it works
- The daemon finds your paired AirPods, opens the driver, and holds an **AAP
  session** (connect → handshake → request notifications), keeping battery, noise
  mode and the rest up to date, and serving the app over IPC.
- It watches passively for the AirPods' **BLE proximity advertisement** while
  disconnected and connects when they show up, so the session comes back on its
  own after they leave and return.
- **Left-click / right-click the WinUI tray icon** for the menu:
  - the device name + **Left / Right / Case** battery,
  - **Noise Control**: Off · Noise Cancellation · Transparency · Adaptive
    (the current one is checked; click to switch — sends the AAP command),
  - **Mute**, **Open** (the main window), **Quit**.
- The main window adds volume, ear detection, conversational awareness, adaptive
  volume, the hi-res mic, hearing aid and device rename.
- Hover the icon for a tooltip with battery + current mode.
- If the link drops it reconnects automatically.

### The hi-res microphone
The daemon watches the virtual mic's capture-activity counter: when any app opens
"LibrePods" as its microphone, it enables the AAP uplink automatically, decodes the
AAC-ELD stream and feeds the driver; when the app releases the mic it stops
(debounced) and restores A2DP stereo. There's a manual toggle too. While the mic is
active the AirPods are in their bidirectional call mode, so playback is mono — that
is the AirPods' behaviour, not a bug; stereo is restored when the mic stops.

### Bonus
Keeping the AAP session alive (app running) tends to **stabilize the audio** —
the AirPods stop bouncing between the HFP (mono, "static") and A2DP (stereo)
profiles, because a proper AAP host is talking to them.

### Notes / limits
- The AAP channel is **exclusive** — the daemon owns it, which is why the UIs are
  IPC clients rather than talking to the driver themselves. Several UI clients can
  run at once.
- **Heart rate** — no working version on Windows yet. It's off by default behind an
  experimental setting; see [`docs/heart-rate.md`](docs/heart-rate.md).
- Requires both drivers installed and Test Mode on.
