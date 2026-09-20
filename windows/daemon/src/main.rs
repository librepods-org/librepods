//! librepodsd — the LibrePods Windows daemon. Owns the exclusive AAP driver
//! handle, the AAP session, and the hi-res mic pipeline, and serves the tray /
//! full app over a named-pipe IPC (NDJSON). See ../../../docs/windows/daemon-ipc/PLAN.md.
//! Runs headless — no console window (it's spawned by the tray/app).

#![windows_subsystem = "windows"]
#![allow(dead_code)]

// No longer called — kept for a quick revert if the stereo-restore ever proves
// necessary; see the note in set_mic(). (Crate-level allow(dead_code) covers it.)
mod a2dp;
mod aap;
mod bt;
mod devnode;
mod driver;
mod eld;
mod gatt;
mod hearing;
mod hr;
mod le;
mod media;
mod micpipe;
mod rename;
mod volume;

use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use librepods_ipc as ipc;
use librepods_ipc::{
    from_line, to_line, Command, Event, Snapshot, PIPE_CMDS, PIPE_EVENTS, PIPE_L2CAP_RX,
    PIPE_L2CAP_TX,
};

use windows_sys::Win32::Foundation::{
    CloseHandle, GetLastError, ERROR_ALREADY_EXISTS, ERROR_PIPE_CONNECTED, HANDLE,
    INVALID_HANDLE_VALUE,
};
use windows_sys::Win32::Security::Authorization::ConvertStringSecurityDescriptorToSecurityDescriptorW;
use windows_sys::Win32::Security::{PSECURITY_DESCRIPTOR, SECURITY_ATTRIBUTES};
use windows_sys::Win32::Storage::FileSystem::{ReadFile, WriteFile, PIPE_ACCESS_DUPLEX};
use windows_sys::Win32::System::Pipes::{
    ConnectNamedPipe, CreateNamedPipeW, PIPE_READMODE_BYTE, PIPE_TYPE_BYTE, PIPE_UNLIMITED_INSTANCES,
    PIPE_WAIT,
};
use windows_sys::Win32::System::Threading::CreateMutexW;

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

/// Frame a raw AAP packet for the L2CAP proxy: u16 LE length, then the bytes.
fn frame(packet: &[u8]) -> Vec<u8> {
    let mut f = Vec::with_capacity(packet.len() + 2);
    f.extend_from_slice(&(packet.len() as u16).to_le_bytes());
    f.extend_from_slice(packet);
    f
}

fn log(s: &str) {
    use std::io::Write;
    // Wall-clock UTC HH:MM:SS.mmm prefix so log lines can be correlated in time.
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| {
            let secs = d.as_secs() % 86_400;
            format!(
                "{:02}:{:02}:{:02}.{:03}",
                secs / 3600,
                (secs % 3600) / 60,
                secs % 60,
                d.subsec_millis()
            )
        })
        .unwrap_or_default();
    if let Ok(la) = std::env::var("LOCALAPPDATA") {
        if let Ok(mut f) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(format!("{la}\\LibrePods\\daemon.log"))
        {
            let _ = writeln!(f, "{ts} {s}");
        }
    }
}

fn battery_text(b: &librepods_ipc::Battery, connected: bool) -> String {
    if !connected {
        return "Disconnected".to_string();
    }
    let f = |v: Option<u8>| v.map(|p| format!("{p}%")).unwrap_or_else(|| "—".into());
    format!("Left {}   Right {}   Case {}", f(b.left), f(b.right), f(b.case))
}

/// Owns a client's pipe HANDLE; closes it once both the reader and writer
/// threads have dropped their `Arc<Pipe>`. Duplex pipes allow the reader and
/// writer to use the same handle concurrently.
struct Pipe(HANDLE);
impl Drop for Pipe {
    fn drop(&mut self) {
        unsafe { CloseHandle(self.0) };
    }
}
unsafe impl Send for Pipe {}
unsafe impl Sync for Pipe {}

/// Outgoing queue to one client (drained by its writer thread — so a slow client
/// never blocks the session/broadcast, i.e. async delivery).
type ClientTx = std::sync::mpsc::Sender<Vec<u8>>;

/// Everything the session, poll and IPC threads share.
#[derive(Clone)]
struct Ctx {
    state: Arc<Mutex<Snapshot>>,
    clients: Arc<Mutex<Vec<ClientTx>>>,
    /// Raw-L2CAP proxy clients (the full app): each incoming AAP packet is
    /// forwarded to them (length-prefixed) so the app runs its session over us.
    l2cap_clients: Arc<Mutex<Vec<ClientTx>>>,
    /// The last battery + ANC packets (raw), keyed by kind (0=battery, 1=ANC),
    /// replayed to a newly-attached app so it shows the current state without us
    /// re-requesting (which cuts audio).
    replay: Arc<Mutex<std::collections::HashMap<u8, Vec<u8>>>>,
    driver_cell: Arc<Mutex<Option<driver::Driver>>>,
    mic_on: Arc<AtomicBool>,
    auto_mode: Arc<AtomicBool>,
    /// AirPods Pro 3 heart-rate monitoring is on (opt-in — off by default). When
    /// set, `run_receiver` feeds each recv chunk into the RTBuddy HR decoder.
    hr_on: Arc<AtomicBool>,
    /// Set by `run_receiver` the moment the decoder yields its first BPM sample.
    /// The HR retry thread polls this to know an enable attempt actually took
    /// (the RTBuddy stream almost never starts on the first try) — cleared before
    /// each attempt. This is the run_receiver↔retry-thread rendezvous.
    hr_got_sample: Arc<AtomicBool>,
    /// Set by `run_receiver` when an HR-prefixed frame arrives (the HEARTRATE
    /// service is streaming, even if it carries no reading payload yet). Once the
    /// service is live the retry campaign stops churning STOP/re-init and just
    /// keeps the stream open, so a reading can land whenever the sensor produces
    /// one (it may take real sustained activity). Cleared before each attempt.
    hr_stream_live: Arc<AtomicBool>,
    /// True while an HR retry thread is live. A one-thread guard so rapid on/off
    /// never stacks two retry campaigns over the one driver.
    hr_retrying: Arc<AtomicBool>,
    /// The user accepted the "connect?" prompt — the session may start.
    connect_requested: Arc<AtomicBool>,
    /// Set by the "Repair connection" command: asks `run_receiver` to drop + reopen
    /// the driver (fresh AAP session + ATT channel) to recover a wedged / desynced
    /// link. run_receiver clears it.
    wants_reconnect: Arc<AtomicBool>,
    pipe: Arc<micpipe::MicPipeCell>,
    /// Conversational Awareness volume duck — shared so `apply_command` can
    /// restore the volume if the user turns CA off mid-duck (no end event comes).
    conv_duck: Arc<Mutex<volume::ConvDuck>>,
    /// Latest rename seen on the app→driver proxy + when. Flushed to an overlay
    /// once it settles (the app may send several as you type), so there's a
    /// visible "Renamed to X" confirmation.
    pending_rename: Arc<Mutex<Option<(String, Instant)>>>,
    /// The ANC mode last commanded by the user + when. Used to ignore the
    /// transitional ANC echoes the AirPods emit while switching modes quickly
    /// (they briefly report Off), so the UI/toasts don't flicker through Off.
    anc_cmd: Arc<Mutex<Option<(u8, Instant)>>>,
    /// True while an audio-reclaim campaign is running — one at a time, so a
    /// burst of losses can't stack campaigns fighting each other for the buds.
    reclaiming: Arc<AtomicBool>,
    /// Set whenever we lose the AirPods to another device; consumed by the next
    /// successful handshake, which then rebuilds the audio route. See
    /// `force_audio_rebuild` for why a plain reconnect is not enough.
    audio_rebuild_pending: Arc<AtomicBool>,
    /// The user pressed Disconnect. Distinguishes "we are idle because you told us
    /// to let go" from "we are idle because another device took the buds" — only
    /// the second may resume by itself. Cleared by Connect / Repair.
    user_disconnected: Arc<AtomicBool>,
    /// When the last reclaim campaign ended. Cooldown: a phone that really owns
    /// the buds (a call — tier 1 on its side) must not be fought over and over.
    last_reclaim: Arc<Mutex<Option<Instant>>>,
    /// When "Repair connection" last ran. Debounce: a frustrated double/triple
    /// click drops and reopens the exclusive driver handle several times a
    /// second, and that churn is exactly what leaves the devnode in Code 38.
    last_repair: Arc<Mutex<Option<Instant>>>,
    dev_name: Arc<Mutex<String>>,
    mac: u64,
}

impl Ctx {
    /// Queue one NDJSON event for every client (never blocks — each client's
    /// writer thread drains its own queue). Drops clients whose writer has gone.
    fn send_event(&self, ev: &Event) {
        let bytes = to_line(ev).into_bytes();
        let mut clients = self.clients.lock().unwrap();
        clients.retain(|tx| tx.send(bytes.clone()).is_ok());
    }

    /// Broadcast the current state (with the live mic/auto flags folded in).
    fn push_state(&self) {
        let snap = {
            let mut s = self.state.lock().unwrap();
            s.mic_recording = self.mic_on.load(Ordering::Relaxed);
            s.auto_mode = self.auto_mode.load(Ordering::Relaxed);
            s.dev_name = self.dev_name.lock().unwrap().clone();
            s.clone()
        };
        self.send_event(&Event::State(snap));
    }

    /// Broadcast a notification for clients to render.
    fn overlay(&self, body: &str) {
        self.send_event(&Event::Overlay {
            title: self.dev_name.lock().unwrap().clone(),
            body: body.to_string(),
        });
    }

    /// Forward one raw AAP packet (length-prefixed: u16 LE + bytes) to every
    /// L2CAP-proxy client (the app). Never blocks — per-client writer threads.
    fn forward_l2cap(&self, packet: &[u8]) {
        let mut clients = self.l2cap_clients.lock().unwrap();
        if clients.is_empty() {
            return;
        }
        let f = frame(packet);
        clients.retain(|tx| tx.send(f.clone()).is_ok());
    }

    /// Remember a state packet (kind 0=battery, 1=ANC) to replay to new apps.
    fn cache_replay(&self, kind: u8, packet: &[u8]) {
        self.replay.lock().unwrap().insert(kind, packet.to_vec());
    }

    /// Read the live WASAPI volume/mute into the snapshot; push only on change so
    /// we don't spam clients. The caller's thread must have COM initialized.
    fn sync_volume(&self) {
        let vol = volume::get().unwrap_or(0);
        let muted = volume::is_muted();
        let changed = {
            let mut s = self.state.lock().unwrap();
            let c = s.volume != vol || s.muted != muted;
            s.volume = vol;
            s.muted = muted;
            c
        };
        if changed {
            self.push_state();
        }
    }
}

/// Write the whole buffer to a pipe handle. Returns false if the client is gone.
unsafe fn write_all(h: HANDLE, buf: &[u8]) -> bool {
    let mut off = 0usize;
    while off < buf.len() {
        let mut written = 0u32;
        let ok = WriteFile(
            h,
            buf[off..].as_ptr(),
            (buf.len() - off) as u32,
            &mut written,
            ptr::null_mut(),
        );
        if ok == 0 || written == 0 {
            return false;
        }
        off += written as usize;
    }
    true
}

/// Per-client reader: parse NDJSON commands and apply them until it disconnects.
fn client_reader(pipe: Arc<Pipe>, ctx: Ctx) {
    volume::init(); // this thread applies StepVolume/ToggleMute (WASAPI needs COM)
    let h = pipe.0;
    let mut buf = [0u8; 4096];
    let mut acc = String::new();
    loop {
        let mut read = 0u32;
        let ok =
            unsafe { ReadFile(h, buf.as_mut_ptr(), buf.len() as u32, &mut read, ptr::null_mut()) };
        if ok == 0 || read == 0 {
            break; // disconnected
        }
        acc.push_str(&String::from_utf8_lossy(&buf[..read as usize]));
        while let Some(nl) = acc.find('\n') {
            let line: String = acc.drain(..=nl).collect();
            if let Some(cmd) = from_line::<Command>(&line) {
                apply_command(&ctx, cmd);
            }
        }
    }
    // The pipe closes once this Arc and the writer thread's Arc both drop.
}

/// Build a security descriptor that lets same-user clients connect (the default
/// null descriptor denies them). Leaks one small SD per server — negligible.
unsafe fn pipe_sa(psd: &mut PSECURITY_DESCRIPTOR) -> SECURITY_ATTRIBUTES {
    let sddl = wide("D:(A;;GA;;;AU)(A;;GA;;;SY)");
    let ok = ConvertStringSecurityDescriptorToSecurityDescriptorW(
        sddl.as_ptr(),
        1, // SDDL_REVISION_1
        psd,
        ptr::null_mut(),
    );
    SECURITY_ATTRIBUTES {
        nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
        lpSecurityDescriptor: if ok != 0 { *psd } else { ptr::null_mut() },
        bInheritHandle: 0,
    }
}

/// Create one pipe instance and block until a client connects; returns its handle.
unsafe fn accept(name: &[u16], sa: *const SECURITY_ATTRIBUTES) -> Option<HANDLE> {
    let h = CreateNamedPipeW(
        name.as_ptr(),
        PIPE_ACCESS_DUPLEX,
        PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
        PIPE_UNLIMITED_INSTANCES,
        4096,
        4096,
        0,
        sa,
    );
    if h == INVALID_HANDLE_VALUE {
        thread::sleep(Duration::from_secs(1));
        return None;
    }
    // ERROR_PIPE_CONNECTED = the client beat us to it (still a success).
    let ok = ConnectNamedPipe(h, ptr::null_mut());
    if ok == 0 && GetLastError() != ERROR_PIPE_CONNECTED {
        CloseHandle(h);
        return None;
    }
    Some(h)
}

/// Events pipe: the daemon only WRITES here (one direction → no sync-handle
/// serialization). Each client gets a queue drained by its own writer thread.
unsafe fn events_server(ctx: Ctx) {
    let name = wide(PIPE_EVENTS);
    let mut psd: PSECURITY_DESCRIPTOR = ptr::null_mut();
    let sa = pipe_sa(&mut psd);
    loop {
        let h = match accept(&name, &sa) {
            Some(h) => h,
            None => continue,
        };
        let pipe = Arc::new(Pipe(h));
        let (tx, rx) = std::sync::mpsc::channel::<Vec<u8>>();
        let n = {
            let mut cl = ctx.clients.lock().unwrap();
            cl.push(tx);
            cl.len()
        };
        log(&format!("events: client connected ({n} total)"));
        let p = pipe.clone();
        thread::spawn(move || {
            for msg in rx {
                if !unsafe { write_all(p.0, &msg) } {
                    break;
                }
            }
        });
        ctx.push_state(); // greet the newcomer
    }
}

/// Commands pipe: the daemon only READS here (one direction). Commands are
/// global, so any client's commands just apply to the daemon.
unsafe fn cmds_server(ctx: Ctx) {
    let name = wide(PIPE_CMDS);
    let mut psd: PSECURITY_DESCRIPTOR = ptr::null_mut();
    let sa = pipe_sa(&mut psd);
    loop {
        let h = match accept(&name, &sa) {
            Some(h) => h,
            None => continue,
        };
        log("cmds: client connected");
        let pipe = Arc::new(Pipe(h));
        let c = ctx.clone();
        thread::spawn(move || client_reader(pipe, c));
    }
}

/// L2CAP-RX pipe: the daemon only WRITES forwarded AAP packets here (→ the app).
unsafe fn l2cap_rx_server(ctx: Ctx) {
    let name = wide(PIPE_L2CAP_RX);
    let mut psd: PSECURITY_DESCRIPTOR = ptr::null_mut();
    let sa = pipe_sa(&mut psd);
    loop {
        let h = match accept(&name, &sa) {
            Some(h) => h,
            None => continue,
        };
        let pipe = Arc::new(Pipe(h));
        let (tx, rx) = std::sync::mpsc::channel::<Vec<u8>>();
        // Replay the cached battery/ANC packets so the app shows current state
        // immediately (without us re-requesting, which would cut audio).
        for pkt in ctx.replay.lock().unwrap().values() {
            let _ = tx.send(frame(pkt));
        }
        ctx.l2cap_clients.lock().unwrap().push(tx);
        log("l2cap-rx: app attached");
        let p = pipe.clone();
        thread::spawn(move || {
            for msg in rx {
                if !unsafe { write_all(p.0, &msg) } {
                    break;
                }
            }
        });
    }
}

/// L2CAP-TX pipe: the daemon only READS the app's outgoing AAP packets and sends
/// them to the driver — dropping the setup packets it already sent itself.
unsafe fn l2cap_tx_server(ctx: Ctx) {
    let name = wide(PIPE_L2CAP_TX);
    let mut psd: PSECURITY_DESCRIPTOR = ptr::null_mut();
    let sa = pipe_sa(&mut psd);
    loop {
        let h = match accept(&name, &sa) {
            Some(h) => h,
            None => continue,
        };
        log("l2cap-tx: app attached");
        let pipe = Arc::new(Pipe(h));
        let c = ctx.clone();
        thread::spawn(move || l2cap_reader(pipe, c));
    }
}

/// A setup packet the daemon already sent — re-sending it re-negotiates the audio
/// profile and cuts sound, so we drop the app's copy.
fn is_setup(p: &[u8]) -> bool {
    p == aap::HANDSHAKE.as_slice()
        || p == aap::SET_FEATURES.as_slice()
        || p == aap::REQUEST_NOTIFS.as_slice()
}

/// Read length-prefixed ([u16 LE len][bytes]) AAP packets from the app → driver.
fn l2cap_reader(pipe: Arc<Pipe>, ctx: Ctx) {
    let h = pipe.0;
    let mut acc: Vec<u8> = Vec::new();
    let mut buf = [0u8; 4096];
    loop {
        let mut read = 0u32;
        let ok =
            unsafe { ReadFile(h, buf.as_mut_ptr(), buf.len() as u32, &mut read, ptr::null_mut()) };
        if ok == 0 || read == 0 {
            break;
        }
        acc.extend_from_slice(&buf[..read as usize]);
        while acc.len() >= 2 {
            let len = u16::from_le_bytes([acc[0], acc[1]]) as usize;
            if acc.len() < 2 + len {
                break;
            }
            let packet = acc[2..2 + len].to_vec();
            acc.drain(..2 + len);
            if !is_setup(&packet) {
                // The app renames over the proxy; remember the latest name so we
                // can show a "Renamed to X" confirmation once typing settles.
                if let Some(name) = aap::parse_rename(&packet) {
                    *ctx.pending_rename.lock().unwrap() = Some((name, Instant::now()));
                }
                if let Some(drv) = ctx.driver_cell.lock().unwrap().clone() {
                    let _ = drv.send(&packet);
                }
            }
        }
    }
}

/// Audio-ownership priority, mirroring the hierarchy Apple itself routes by:
///
///   1. Calls / FaceTime (highest)  — steals the audio from anything
///   2. User-initiated playback     — steals it from other media
///   3. Background audio / system sounds — ignored unless manually triggered
///
/// An iPhone notification chime is **tier 3**. By Apple's own order it must not
/// take the AirPods away from a tier-1 or tier-2 session on this host — but the
/// buds hand them over anyway, and we don't control the buds' side. So we
/// enforce the rule from here: after a loss we reclaim the audio route only when
/// THIS host held tier 1 or tier 2 at the moment the link dropped.
///
/// At tier 3 we hold nothing worth defending, so we let go and wait for the BLE
/// watcher — the previous behaviour, unchanged. And when the *phone* is at tier 1
/// (a real call) it keeps the buds: our campaign is bounded and backs off, so the
/// higher tier still wins wherever it lives.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Tier {
    /// Tier 1 — an app is capturing the AirPods microphone through us.
    Call,
    /// Tier 2 — a user-started playback session is running (SMTC says Playing).
    Playback,
    /// Tier 3 — nothing of ours is playing; a system sound at most.
    Background,
}

impl Tier {
    /// Tiers 1 and 2 own the route and are worth reclaiming; tier 3 is not.
    fn defends_audio(self) -> bool {
        self != Tier::Background
    }
    fn label(self) -> &'static str {
        match self {
            Tier::Call => "1/call",
            Tier::Playback => "2/playback",
            Tier::Background => "3/background",
        }
    }
}

/// Turn one AAP 0x2E entry into something the UI can show, trying the sources in
/// order of how much they can be trusted:
///
///   1. our own radios      — certain: this is the PC the user is looking at
///   2. paired to this PC   — Windows' own name + Class of Device (authoritative)
///   3. nothing             — say so, and show the address
///
/// A phone sharing the AirPods with us is normally NOT paired to this PC, so in
/// practice it lands on step 3 and shows as "Other device" with its address. That
/// is as far as the evidence goes; naming it "iPhone" would be invention.
fn describe_device(addr: u64, flags: [u8; 2], local_radios: &[u64]) -> ipc::ConnectedDevice {
    let b = addr.to_be_bytes();
    let address = format!(
        "{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}",
        b[2], b[3], b[4], b[5], b[6], b[7]
    );
    if local_radios.contains(&addr) {
        return ipc::ConnectedDevice {
            address,
            is_this_pc: true,
            name: "This PC".into(),
            kind: "pc".into(),
        };
    }
    if let Some((name, cod)) = bt::paired_device(addr) {
        if !name.is_empty() {
            return ipc::ConnectedDevice {
                address,
                is_this_pc: false,
                kind: bt::kind_from_cod(cod).into(),
                name,
            };
        }
    }
    // Nothing else to go on. The 0x2E flag bytes look like state, not class (see
    // aap::flags_hex), so we do NOT guess a type from them — an iPhone labelled
    // "Computer" is worse than no label at all.
    let _ = flags;
    ipc::ConnectedDevice {
        address,
        is_this_pc: false,
        name: "Other device".into(),
        kind: "unknown".into(),
    }
}

/// What this host currently holds the audio route for. `media::is_playing()`
/// needs COM on the calling thread (run_receiver already did `media::init()`).
fn host_tier(ctx: &Ctx) -> Tier {
    if ctx.mic_on.load(Ordering::Relaxed) {
        Tier::Call
    } else if media::is_playing() || volume::any_render_active() {
        // Two probes, because neither alone is enough: SMTC catches browsers and
        // media players (and keeps reporting through a brief device switch),
        // while the Core Audio session walk catches everything that just renders
        // — Teams, Discord, games — which is most of what you would be doing when
        // a phone notification steals the buds.
        Tier::Playback
    } else {
        Tier::Background
    }
}

/// Ask the OS to connect/disconnect the audio, off-thread (the call blocks for
/// seconds) and serialized, so a stale request can never undo a newer one.
fn audio_request(mac: u64, connect: bool, why: &'static str) {
    let verb = if connect { "connect" } else { "disconnect" };
    thread::spawn(move || match bt::request_audio(mac, connect) {
        // Always log the Win32 codes, not just the bool. Every one of these has
        // been logging `= false` with no way to tell why: 0 = ERROR_SUCCESS,
        // 5 = access denied (needs elevation), 1168 = not found, 1167 = device
        // not connected. The bool alone made the reclaim campaign undebuggable.
        Some(ok) => {
            let (a, b) = bt::last_service_codes();
            log(&format!("bt: {why} {verb} audio = {ok} (AudioSink={a} Handsfree={b})"));
        }
        None => log(&format!("bt: {why} {verb} audio superseded by a newer request — skipped")),
    });
}

/// Don't fight for the buds again within this long of the last campaign.
const RECLAIM_COOLDOWN: Duration = Duration::from_secs(60);

/// Waits before each reclaim attempt, ~39 s in total.
///
/// The first version gave up after three tries over 10 s and that was measurably
/// too impatient: in the 2026-08-31 Teams capture the campaign gave up at
/// 12:42:49 and the AAP session came back at 12:42:52 — three seconds late, so
/// the one moment we could have acted was the one we had just stopped watching.
/// A phone notification returns the buds in seconds; a real call on the phone
/// keeps them, and that case is ended by the tier re-check inside the loop
/// rather than by a short deadline.
const RECLAIM_BACKOFF_MS: [u64; 8] = [1_000, 2_000, 3_000, 4_000, 5_000, 6_000, 8_000, 10_000];

/// Force Windows to tear down and rebuild the audio link: disable the AirPods'
/// audio services, then re-enable them.
///
/// This is, exactly, what the user does by hand — Disconnect then Connect — and
/// by their account it is the ONLY thing that reliably brings the sound back
/// after a phone takes the buds: "em que perco o audio tenho que desligar e
/// voltar a ligar, mesmo em chamada, que nao recupera".
///
/// Reconnecting the AAP session is not enough. Windows keeps the device, the
/// endpoint and the profile all nominally healthy while the audio plays
/// elsewhere, so it has no reason to rebuild the stream on its own; the buds
/// come back attached but silent.
///
/// Timing is everything here. `BluetoothSetServiceState` needs the device to be
/// CONNECTED: across the 2026-08-31 logs it returned ERROR_SUCCESS on every call
/// made while the AirPods were attached and ERROR_INVALID_PARAMETER (87) on every
/// call made while they were away. So this must run after the link is back, never
/// during the handover — which is why it hangs off the handshake rather than off
/// the loss.
///
/// This is what the retired `a2dp::reset()` really did; it was dropped because
/// the churn wedged the driver into Code 38, which we now both understand and
/// recover from automatically.
fn force_audio_rebuild(mac: u64) {
    let _ = bt::request_audio(mac, false);
    let (a, b) = bt::last_service_codes();
    log(&format!("reclaim: forced rebuild — disable codes AudioSink={a} Handsfree={b}"));
    thread::sleep(Duration::from_millis(1_000));
    let _ = bt::request_audio(mac, true);
    let (a, b) = bt::last_service_codes();
    log(&format!("reclaim: forced rebuild — enable codes AudioSink={a} Handsfree={b}"));
}

/// Reclaim the audio route after a lower-tier event on another device took the
/// AirPods away from a tier-1/2 session here. Runs on its own thread; leaves
/// `connect_requested` set while it works so the AAP session re-opens the moment
/// the link is back, and clears it if the campaign gives up.
fn spawn_reclaim(ctx: &Ctx, tier: Tier) {
    if ctx.reclaiming.swap(true, Ordering::SeqCst) {
        return; // a campaign is already running
    }
    log(&format!("reclaim: host held tier {} — reclaiming the audio route", tier.label()));
    let ctx = ctx.clone();
    thread::spawn(move || {
        media::init(); // is_playing() needs COM on this thread too
        let mut won = false;
        for (i, wait) in RECLAIM_BACKOFF_MS.iter().enumerate() {
            thread::sleep(Duration::from_millis(*wait));
            // The user pressed Disconnect, or the connect loop gave up.
            if !ctx.connect_requested.load(Ordering::Relaxed) {
                log("reclaim: no longer requested — standing down");
                break;
            }
            // Re-check the tier: if the call ended or the playback stopped while we
            // waited, we have nothing left to defend and asking for the buds would
            // be a steal. This, not a deadline, is what ends a campaign against a
            // phone that legitimately owns them.
            if !host_tier(&ctx).defends_audio() {
                log("reclaim: host dropped to tier 3/background — standing down");
                break;
            }
            if bt::is_connected(ctx.mac) {
                // The link is back. The rebuild itself is done off the handshake
                // (see `audio_rebuild_pending`), so that it happens on EVERY
                // recovery — including the ones where nothing was playing at the
                // time and no campaign ever ran. Toggling here too would just
                // double the dropout.
                log("reclaim: link back — audio rebuild will follow the handshake");
                won = true;
                break;
            }
            // Deliberately NOT calling set_audio_connected here.
            //
            // `BluetoothSetServiceState` needs the device to be connected: across
            // the 2026-08-31 logs it returned ERROR_SUCCESS every time the AirPods
            // were attached (13:39:04, 13:39:10, 14:06:50, 14:06:56) and
            // ERROR_INVALID_PARAMETER (87) every time they were not (12:54, 13:02,
            // 14:01, 14:03, 14:36, 14:37). The old campaign called it only while
            // they were away with the phone — the one moment it cannot work — which
            // is why every attempt logged `= false` and nothing was ever reclaimed.
            //
            // So we wait instead. The work happens above, once `is_connected` turns
            // true: A2DP comes back on its own, and a call gets force_audio_rebuild.
            log(&format!(
                "reclaim: attempt {} — AirPods still away, waiting for the link",
                i + 1
            ));
        }
        if won {
            log("reclaim: audio route restored");
            ctx.overlay("Audio restored");
        } else {
            // Give the buds up: the phone kept them (its own tier 1), so stop
            // asking. The BLE watcher re-arms on their next appearance.
            log("reclaim: gave up — the other device keeps the AirPods");
            ctx.connect_requested.store(false, Ordering::Relaxed);
        }
        *ctx.last_reclaim.lock().unwrap() = Some(Instant::now());
        ctx.reclaiming.store(false, Ordering::SeqCst);
    });
}

/// Enable/disable the hi-res mic stream (manual path).
fn set_mic(ctx: &Ctx, on: bool) {
    let was = ctx.mic_on.swap(on, Ordering::Relaxed);
    if on && !was {
        if let Some(drv) = ctx.driver_cell.lock().unwrap().clone() {
            let _ = drv.send(&aap::START_AUDIO);
        }
        ctx.overlay("Using the AirPods microphone");
    } else if !on && was {
        // Deliberately NOT sending STOP_AUDIO — see the note below. Clearing
        // `mic_on` is enough: the receive loop stops decoding the 0x58 uplink and
        // the virtual mic goes silent, which is what "off" means to the user. The
        // buds keep the uplink armed until the session ends, and that is the point.
        //
        // No A2DP restore either. A btvs capture (trace/audio/audio-1.pcapng, 244 s) showed
        // every AVDTP SET_CONFIGURATION is identical SBC 44.1 kHz **JointStereo**, with
        // zero RECONFIGURE and no SCO/HFP anywhere — the hi-res uplink never degrades
        // playback to mono, so there is nothing to restore. Worse, a2dp::reset() is a
        // BluetoothSetServiceState disable/enable: in that same capture the AAP channel
        // (PSM 0x1001) opened once, went silent, and was never rebuilt while A2DP was
        // torn down and re-set-up five times — the churn that bricks the driver into
        // Code 38. Dropping it also removes a ~2.65 s audio dropout on every release.
        //
        // WHY STOP_AUDIO IS GONE (measured 2026-08-30, live over the l2cap-tx pipe):
        // sending it is what breaks playback to a single side. Leaving mic mode puts
        // the buds in a state where only one bud renders; it self-heals after minutes.
        // Re-sending START_AUDIO restores stereo INSTANTLY, which proves mic mode
        // itself is fine and only the exit is broken. This is also what a2dp::reset()
        // was really "curing": it rebuilt the audio link right after the bad exit, at
        // the cost of killing the AAP channel and bricking the driver into Code 38.
        // So we never leave mic mode mid-session. The uplink stays armed until the
        // session ends (a disconnect resets the buds anyway).
        //
        // No overlay here either: nothing is "released", so announcing it was both
        // wrong and noise. Going quiet is the honest signal.
    }
    ctx.push_state();
}

// ---- HR retry constants (mirror the Android HeartRateMonitor companion) ----
/// Wait this long for a decoded reading before re-enabling (Android's
/// FIRST_SAMPLE_TIMEOUT).
const HR_FIRST_SAMPLE_TIMEOUT_MS: u64 = 8_000;
/// Gap after HRM_STATE before the stream start (Android's START_COMMAND_DELAY).
const HR_START_COMMAND_DELAY_MS: u64 = 120;

/// Sequence number for sensor stream control frames. iOS increments this per
/// frame; whether the AirPods validate it is untested, so we count up too. Kept
/// above 127 so it always encodes as the two-byte varint the captures show, and
/// masked to 14 bits so it never overflows that encoding.
static HR_SEQ: AtomicU16 = AtomicU16::new(0);

fn next_hr_seq() -> u16 {
    let n = HR_SEQ.fetch_add(1, Ordering::Relaxed);
    128 + (n % (16_384 - 128))
}
/// Backoff between attempts, indexed by attempt number (last value repeats).
const HR_RETRY_BACKOFF_MS: [u64; 3] = [500, 1_000, 2_000];
/// Consecutive enable attempts (each an ~8 s sample wait) with no reading before we
/// give up. We do NOT rebuild / reconnect the L2CAP channel to "try again": on this
/// firmware the buds only ever ACK service 19 and never stream, so reconnecting is
/// pointless churn (it just re-opens the audio link). The user toggles HR off/on to
/// retry.
const HR_MAX_ATTEMPTS: u32 = 4;

/// How one retry campaign ended.
enum HrOutcome {
    /// The stream came up — a real sample was decoded; run_receiver keeps decoding.
    Live,
    /// The user turned HR off mid-campaign (`hr_on` went false).
    Stopped,
    /// The transport was lost (no driver), or the enable retries were exhausted with
    /// only ACKs — give up; re-arms on the next connect / HR re-toggle.
    GiveUp,
}

/// Enable/disable AirPods Pro 3 heart-rate monitoring. On → spawn a retry thread
/// that re-sends the RTBuddy AACP 1.3 enable sequence until the first sample
/// arrives (the stream almost never starts on the first attempt). Off → send the
/// STOP frame, clear the reading, and let the retry thread notice `hr_on` and
/// exit. The decoder itself is driven in `run_receiver` off `hr_on`.
fn set_heart_rate(ctx: &Ctx, on: bool) {
    let was = ctx.hr_on.swap(on, Ordering::Relaxed);
    let has_driver = ctx.driver_cell.lock().unwrap().is_some();
    log(&format!(
        "HR: set on={on} was={was} driver={}",
        if has_driver { "connected" } else { "NONE(no session yet)" }
    ));
    if on && !was {
        spawn_hr_retry(ctx);
        ctx.overlay("Heart rate monitoring on");
    } else if !on && was {
        if let Some(drv) = ctx.driver_cell.lock().unwrap().clone() {
            // Stop = interval 0 on both services (matching the start).
            let s = next_hr_seq() as u8;
            let _ = drv.send(&aap::hr_stream(s, aap::STREAM_HEART_RATE, 0));
            let _ = drv.send(&aap::hr_stream(s.wrapping_add(1), aap::STREAM_HEART_RATE_LEGACY, 0));
        }
        ctx.state.lock().unwrap().heart_rate = None;
        ctx.overlay("Heart rate monitoring off");
        // Any running retry thread observes hr_on=false on its next poll and exits.
    }
    ctx.push_state();
}

/// Start the HR retry campaign on its own thread — so the ~1 s of init sleeps and
/// the up-to-8 s sample waits never block the command reader or the recv loop.
/// Guarded by `hr_retrying` so two campaigns can't run over the one driver.
fn spawn_hr_retry(ctx: &Ctx) {
    // One-thread guard: bail if a campaign is already live.
    if ctx.hr_retrying.swap(true, Ordering::SeqCst) {
        return;
    }
    let ctx = ctx.clone();
    thread::spawn(move || {
        // Loop only to cover a rapid off→on that lost its spawn to the guard: a
        // campaign that Stopped (user off) re-runs iff hr_on is true again.
        loop {
            match hr_retry_campaign(&ctx) {
                HrOutcome::Live | HrOutcome::GiveUp => break,
                HrOutcome::Stopped => {
                    if !ctx.hr_on.load(Ordering::Relaxed) {
                        break;
                    }
                }
            }
        }
        ctx.hr_retrying.store(false, Ordering::SeqCst);
    });
}

/// Keep re-sending the enable sequence and waiting for a REAL heart-rate reading,
/// mirroring the Android HeartRateMonitor loop. One attempt = full enable + up to
/// FIRST_SAMPLE_TIMEOUT waiting for a *decoded reading*. Crucially, mere ACKs / a
/// live-but-empty stream do NOT end the campaign: the whole failure mode is that the
/// AirPods ACK service 19 yet never stream data. Plain re-enable retries repeat over
/// the SAME channel, up to HR_MAX_ATTEMPTS, then give up — we never rebuild/reconnect
/// the L2CAP channel, because the audio + mic links ride it and must never be
/// collapsed for a feature that (on this firmware) never yields data. Runs until a
/// reading lands, the user turns HR off, or the attempts are spent.
fn hr_retry_campaign(ctx: &Ctx) -> HrOutcome {
    let mut attempt: u32 = 0;
    while ctx.hr_on.load(Ordering::Relaxed) {
        ctx.hr_got_sample.store(false, Ordering::Relaxed);
        ctx.hr_stream_live.store(false, Ordering::Relaxed);
        let drv = match ctx.driver_cell.lock().unwrap().clone() {
            Some(d) => d,
            None => {
                log("HR: no driver — retry aborted (re-arms on reconnect)");
                return HrOutcome::GiveUp;
            }
        };
        // beforeFirstStart (Android): stop head tracking up front — it shares the
        // sensor service — and settle 220 ms, BEFORE the session init, matching the
        // working client's ordering exactly (the PR author confirmed his flow).
        let _ = drv.send(&aap::sensor_stream(next_hr_seq(), aap::STREAM_HEAD_TRACKING, 0));
        thread::sleep(Duration::from_millis(220));
        // AACP 1.3 session init (connect0/caps0/connect4/caps4), re-sent every attempt
        // so each retry re-establishes the session before the enable.
        let init: [(&[u8], u64); 4] = [
            (&aap::HR_CONNECT_SERVICE_0, 180),
            (&aap::HR_CAPABILITIES_SERVICE_0, 220),
            (&aap::HR_CONNECT_SERVICE_4, 180),
            (&aap::HR_CAPABILITIES_SERVICE_4, 220),
        ];
        for (pkt, delay) in init {
            if !ctx.hr_on.load(Ordering::Relaxed) {
                return HrOutcome::Stopped;
            }
            let _ = drv.send(pkt);
            thread::sleep(Duration::from_millis(delay));
        }
        // Enable + start, faithfully reproducing the working Android sequence
        // (upstream PR #702): switch on the PPG engine with HRM_STATE (control 0x30),
        // then start the 1 Hz heart-rate stream (service 19). The earlier
        // `request_all_descriptors` guess is dropped (Android doesn't send it and it
        // never helped); the 0x10 DEVMOTION6 stream is unrelated to HR and dropped.
        let _ = drv.send(&aap::HR_ENABLE);
        thread::sleep(Duration::from_millis(HR_START_COMMAND_DELAY_MS));
        // The HR "set interval" service differs by firmware (8*: 19; 9*: 84) and
        // thibaup saw it vary — so set the 1 Hz interval on BOTH 84 and 19; the buds
        // ignore the wrong one, and the decoder catches the reports on 19/20/84.
        let s = next_hr_seq() as u8;
        let _ = drv.send(&aap::hr_stream(s, aap::STREAM_HEART_RATE, aap::PERIOD_HEART_RATE_US)); // 84
        thread::sleep(Duration::from_millis(120));
        let _ = drv.send(&aap::hr_stream(
            s.wrapping_add(1),
            aap::STREAM_HEART_RATE_LEGACY,
            aap::PERIOD_HEART_RATE_US,
        )); // 19

        // Wait up to FIRST_SAMPLE_TIMEOUT for a REAL decoded reading. ACKs / a
        // live-but-empty stream are ignored on purpose — they are exactly the state
        // we are trying to get past.
        let wait_until = Instant::now() + Duration::from_millis(HR_FIRST_SAMPLE_TIMEOUT_MS);
        while Instant::now() < wait_until {
            if !ctx.hr_on.load(Ordering::Relaxed) {
                return HrOutcome::Stopped;
            }
            if ctx.hr_got_sample.load(Ordering::Relaxed) {
                log("HR: stream live (reading decoded) — retries done");
                return HrOutcome::Live;
            }
            thread::sleep(Duration::from_millis(200));
        }

        attempt += 1;
        let streaming = ctx.hr_stream_live.load(Ordering::Relaxed);
        log(&format!(
            "HR retry: attempt={attempt} — no reading in 8s (stream_frames={streaming})"
        ));
        // No reading after this attempt. Do NOT rebuild / reconnect the L2CAP channel:
        // on this firmware the buds only ever ACK service 19 and never stream, so
        // reconnecting to "try again" is pointless churn (it just re-opens the audio
        // link). Give up after HR_MAX_ATTEMPTS; the user toggles HR off/on to retry.
        if attempt >= HR_MAX_ATTEMPTS {
            log("HR: no reading after the enable retries (ACKs only) — giving up; \
                 toggle HR off/on to retry");
            ctx.overlay("Heart rate unavailable");
            return HrOutcome::GiveUp;
        }
        let backoff = HR_RETRY_BACKOFF_MS[(attempt as usize - 1).min(HR_RETRY_BACKOFF_MS.len() - 1)];
        let nap_until = Instant::now() + Duration::from_millis(backoff);
        while Instant::now() < nap_until && ctx.hr_on.load(Ordering::Relaxed) {
            thread::sleep(Duration::from_millis(100));
        }
    }
    HrOutcome::Stopped
}

fn apply_command(ctx: &Ctx, cmd: Command) {
    log(&format!("cmd received: {cmd:?}"));
    match cmd {
        Command::Hello { .. } | Command::GetState => ctx.push_state(),
        Command::SetAnc { mode } => {
            if (1..=4).contains(&mode) {
                // Remember the target so run_receiver can ignore transitional
                // echoes (the buds briefly report Off when switching quickly), and
                // reflect the click immediately so the UI feels instant.
                *ctx.anc_cmd.lock().unwrap() = Some((mode, Instant::now()));
                ctx.state.lock().unwrap().anc = mode;
                ctx.push_state();
                if let Some(drv) = ctx.driver_cell.lock().unwrap().clone() {
                    let _ = drv.send(&aap::anc_command(mode));
                }
            }
        }
        Command::SetMicMode { auto, manual } => {
            ctx.auto_mode.store(auto, Ordering::Relaxed);
            if !auto {
                set_mic(ctx, manual);
            } else {
                ctx.push_state();
            }
        }
        Command::SetFeature { feature, on } => {
            if let Some(drv) = ctx.driver_cell.lock().unwrap().clone() {
                let _ = drv.send(&aap::feature_command(feature, on));
            }
            // Turning CA off mid-duck: no end event will arrive, so restore the
            // pre-duck volume now instead of leaving it stuck low.
            if feature == ipc::feature::CONVERSATIONAL_AWARENESS && !on {
                ctx.conv_duck.lock().unwrap().restore();
            }
            // Optimistic: reflect the toggle immediately; the AirPods echo a
            // status which run_receiver uses to correct it if it differs.
            {
                let mut s = ctx.state.lock().unwrap();
                match feature {
                    ipc::feature::CONVERSATIONAL_AWARENESS => s.conversational_awareness = on,
                    ipc::feature::ADAPTIVE_VOLUME => s.adaptive_volume = on,
                    ipc::feature::ALLOW_OFF => s.allow_off = on,
                    _ => {}
                }
            }
            ctx.push_state();
        }
        Command::SetControl { id, value } => {
            if let Some(drv) = ctx.driver_cell.lock().unwrap().clone() {
                let _ = drv.send(&aap::control_command(id, value));
            }
        }
        Command::StepVolume { delta } => {
            volume::step(delta);
            ctx.sync_volume();
        }
        Command::SetVolume { percent } => {
            volume::set(percent.min(100));
            ctx.sync_volume();
        }
        Command::ToggleMute => {
            volume::toggle_mute();
            ctx.sync_volume();
        }
        Command::SetHeartRate { on } => set_heart_rate(ctx, on),
        Command::SetHearingAid {
            on,
            left_eq,
            right_eq,
            amplification,
            balance,
            tone,
            conversation_boost,
            ambient_noise_reduction,
            own_voice,
        } => {
            // Runs on its own thread — hearing::apply has ~1.3 s of enable settle
            // sleeps + ATT round-trips and must not block the command pump. Stamp a
            // generation first: a burst of commands (one per audiogram box) collapses
            // to just the latest, so the rest bail before hammering the driver.
            let gen = hearing::next_gen();
            if let Some(drv) = ctx.driver_cell.lock().unwrap().clone() {
                let ctx2 = ctx.clone();
                thread::spawn(move || {
                    match hearing::apply(
                        &drv, gen, on, &left_eq, &right_eq, amplification, balance, tone,
                        conversation_boost, ambient_noise_reduction, own_voice,
                    ) {
                        Ok(s) => {
                            log(&s);
                            ctx2.overlay(if on { "Hearing aid on" } else { "Hearing aid off" });
                        }
                        Err(e) => log(&format!("hearing aid FAILED: {e}")),
                    }
                });
            }
        }
        Command::Connect => {
            // The user accepted the prompt — let the session start, and ask the OS
            // to (re)connect the audio in case the device was BT-disconnected.
            ctx.connect_requested.store(true, Ordering::Relaxed);
            ctx.user_disconnected.store(false, Ordering::Relaxed);
            audio_request(ctx.mac, true, "user");
        }
        Command::RebuildAudio => {
            // Deliberately does NOT touch the AAP session: in the failure this
            // exists for, the session is perfectly healthy and rebuilding it is
            // both useless and disruptive. Only the audio services are toggled.
            log("cmd: rebuild audio route (user)");
            ctx.overlay("Rebuilding the audio route…");
            let mac = ctx.mac;
            thread::spawn(move || force_audio_rebuild(mac));
        }
        Command::RepairConnection => {
            // Force a clean reconnect: make sure the session is requested (in case we
            // were waiting on a prompt) and ask run_receiver to drop + reopen the
            // driver so a wedged / desynced link gets a fresh AAP session + ATT
            // channel. Also nudge the OS to (re)connect audio.
            // Debounce: each repair drops and reopens the exclusive driver handle.
            // Clicking it three times in two seconds (the log shows exactly that)
            // churns the devnode until it sticks in Code 38 and every later open
            // fails — the "driver in an error state" the user ends up in.
            {
                const REPAIR_DEBOUNCE: Duration = Duration::from_secs(5);
                let mut last = ctx.last_repair.lock().unwrap();
                if last.is_some_and(|t| t.elapsed() < REPAIR_DEBOUNCE) {
                    log("cmd: repair connection — ignored (one is already in flight)");
                    return;
                }
                *last = Some(Instant::now());
            }
            log("cmd: repair connection — forcing a clean reconnect");
            ctx.connect_requested.store(true, Ordering::Relaxed);
            ctx.user_disconnected.store(false, Ordering::Relaxed);
            ctx.wants_reconnect.store(true, Ordering::Relaxed);
            audio_request(ctx.mac, true, "repair");
        }
        Command::Disconnect => {
            // Real disconnect: drop the control session AND ask the OS to
            // disconnect the audio (toggle the A2DP/HFP services off). Never
            // auto-reconnect on our own (a prompt is required).
            ctx.connect_requested.store(false, Ordering::Relaxed);
            ctx.user_disconnected.store(true, Ordering::Relaxed);
            ctx.state.lock().unwrap().connected = false;
            *ctx.driver_cell.lock().unwrap() = None;
            ctx.push_state();
            ctx.overlay("Disconnected");
            audio_request(ctx.mac, false, "user");
        }
        Command::SetName { name } => {
            if !name.is_empty() && name.len() <= 64 {
                if let Some(drv) = ctx.driver_cell.lock().unwrap().clone() {
                    let _ = drv.send(&aap::build_rename(&name));
                }
                // Update our name optimistically so the UI keeps the new name
                // (the AirPods apply it; the OS's cached BT name may need re-pair).
                *ctx.dev_name.lock().unwrap() = name.clone();
                ctx.push_state();
                ctx.overlay(&format!("Renamed to “{name}”"));
            }
        }
        Command::Shutdown => {
            // Release the exclusive AAP driver handle BEFORE exiting so the kernel
            // devnode doesn't stick in Code 38 (CM_PROB_DRIVER_FAILED_PRIOR_UNLOAD)
            // for the next daemon. `exit(0)` skips destructors, so close it
            // explicitly here — this runs the driver's channel teardown, which the
            // OS-on-exit handle close does not do reliably.
            if let Some(drv) = ctx.driver_cell.lock().unwrap().clone() {
                log("shutdown: releasing AAP driver handle");
                drv.close_now();
            }
            std::process::exit(0);
        }
    }
}

/// The AAP session: keep the link up, decode the mic, track battery/ANC/ear
/// detection, and broadcast state + overlay events. (Ported from the tray.)
fn run_receiver(ctx: Ctx) {
    let mac = ctx.mac;
    log("run_receiver: entered");
    let mut buf = [0u8; 8192];
    let mut decoder: Option<eld::Decoder> = None;
    // Whether we've announced "mic fully operational" for the current capture session
    // (fires when the buds actually start streaming audio, not just when it's requested).
    let mut mic_announced = false;
    // RTBuddy heart-rate decoder (inert unless `hr_on`). Carry is reset per
    // connection so a partial frame never straddles a reconnect.
    let mut hr_decoder = hr::RtBuddyHeartRateDecoder::new();
    media::init(); // COM (MTA) for the SMTC ear-detection auto-pause
    volume::init(); // COM for the CA volume duck (same MTA)
    log("run_receiver: media init done");
    let mut last_anc = 0u8;
    // Hosts the AirPods report themselves connected to (AAP 0x2E), and our own
    // radio address so we can tell ourselves apart from the phone.
    let mut last_devices: Vec<(u64, [u8; 2])> = Vec::new();
    let local_radios = bt::local_radio_addresses();
    log(&format!(
        "bt: local radio(s): {}",
        local_radios.iter().map(|a| format!("{a:012x}")).collect::<Vec<_>>().join(", ")
    ));
    let mut last_case_present: Option<bool> = None;
    let mut pending_card = false;
    // Consecutive failures to reach the AirPods. After a few (they're on the
    // iPhone / gone), we give up so we DON'T keep stealing them back — reset the
    // gate and wait for a fresh prompt/Connect.
    let mut reach_fails = 0u32;
    // Throttles the "have the AirPods come home?" check in the idle gate below.
    let mut resume_poll = Instant::now() - Duration::from_secs(60);
    // The user asked to connect — keep trying for a generous window (the AirPods
    // may take a few seconds to become reachable) before giving up. We never
    // *steal* here: a failed connect() doesn't pull them, and once connected a
    // drop releases the gate (below).
    // Consecutive `Driver::open()` failures. Distinct from `reach_fails`: this
    // one is about the *devnode*, not the AirPods.
    let mut open_fails = 0u32;
    let give_up = |ctx: &Ctx, fails: &mut u32| {
        *fails += 1;
        if *fails >= 12 {
            *fails = 0;
            ctx.connect_requested.store(false, Ordering::Relaxed);
            log("run_receiver: gave up reaching AirPods — releasing");
        }
    };
    loop {
        // Gate: stay idle until the user accepts the "connect?" prompt — OR until
        // Windows has the AirPods connected again on its own.
        //
        // That second clause is what was missing, and it is the whole reason a
        // phone call killed the PC audio for good. On a real call the buds stay
        // with the phone for minutes: we exhaust the ~18 s of connect retries,
        // `give_up` clears connect_requested, and then nothing ever tries again.
        // The BLE watcher cannot help — it re-arms only after the buds have been
        // ABSENT for 45 s and reappear, and during the call they are present and
        // advertising the whole time. Measured 2026-08-31: released at 14:37:05,
        // gave up at 14:38:17, and the session only came back at 14:38:33 because
        // the user pressed Connect by hand.
        //
        // Resuming on `is_connected` steals nothing: the OS link is already up, so
        // the buds have come home by themselves and we are only reopening our own
        // AAP channel. An explicit Disconnect still wins — that is a decision, not
        // a loss, so `user_disconnected` holds us off until Connect/Repair.
        if !ctx.connect_requested.load(Ordering::Relaxed) {
            if !ctx.user_disconnected.load(Ordering::Relaxed)
                && resume_poll.elapsed() >= Duration::from_secs(5)
            {
                resume_poll = Instant::now();
                if bt::is_connected(mac) {
                    log("run_receiver: Windows has the AirPods again — resuming the session");
                    ctx.connect_requested.store(true, Ordering::Relaxed);
                    continue;
                }
            }
            thread::sleep(Duration::from_millis(500));
            continue;
        }
        let driver = match driver::Driver::open() {
            Ok(d) => {
                log("run_receiver: driver opened");
                open_fails = 0;
                d
            }
            Err(_) => {
                log("run_receiver: driver open FAILED");
                ctx.state.lock().unwrap().connected = false;
                *ctx.driver_cell.lock().unwrap() = None;
                ctx.push_state();
                // Tell the two failures apart. If Windows has NO classic link to
                // the AirPods, the devnode is simply not published and failing to
                // open it is correct — wait. But if the OS *does* have them and we
                // still cannot open the driver, the devnode is wedged (Code 38:
                // the previous instance never unloaded), and no amount of retrying
                // clears that — the daemon.log shows 1587 such failures in a row.
                // Ask the elevated task to restart it; it re-checks the devnode and
                // no-ops if it turns out healthy. Rate-limited to once every 2 min.
                open_fails += 1;
                if open_fails >= 4 && bt::is_connected(mac) && devnode::request_recovery() {
                    log("run_receiver: driver wedged while the OS still has the AirPods — requested an elevated devnode restart");
                    ctx.overlay("Recovering the AirPods driver…");
                }
                give_up(&ctx, &mut reach_fails);
                thread::sleep(Duration::from_millis(1500));
                continue;
            }
        };
        *ctx.driver_cell.lock().unwrap() = Some(driver.clone());
        let connected = driver.connect(mac, aap::PSM_AACP).unwrap_or(false);
        log(&format!("run_receiver: connect({mac:#x}) = {connected}"));
        if !connected {
            ctx.state.lock().unwrap().connected = false;
            ctx.push_state();
            give_up(&ctx, &mut reach_fails);
            thread::sleep(Duration::from_millis(1500));
            continue;
        }
        reach_fails = 0; // reached them — reset the give-up counter
        let _ = driver.send(&aap::HANDSHAKE);
        thread::sleep(Duration::from_millis(300));
        let _ = driver.send(&aap::SET_FEATURES);
        thread::sleep(Duration::from_millis(300));
        let _ = driver.send(&aap::REQUEST_NOTIFS);
        {
            let mut st = ctx.state.lock().unwrap();
            st.connected = true;
            st.link_stale = false; // fresh session — whatever was wedged is gone
        }
        pending_card = true;
        ctx.push_state();
        log("run_receiver: handshake done, connected=true");
        // We are back after losing the buds to another device. The AAP session
        // being up says nothing about the audio: Windows never tore the stream
        // down, so it will never rebuild it. Do what the user would do by hand.
        if ctx.audio_rebuild_pending.swap(false, Ordering::Relaxed) {
            let mac2 = mac;
            thread::spawn(move || {
                // Let the reconnect settle before touching the services, or the
                // disable lands mid-setup and the rebuild has to happen twice.
                thread::sleep(Duration::from_millis(1_500));
                log("audio: rebuilding the route after regaining the AirPods");
                force_audio_rebuild(mac2);
            });
        }
        // EXPERIMENT (opt-in): one-shot GATT discovery — walk the buds' GATT server as
        // a CLIENT to find any heart-rate characteristic we never enumerated. It wakes
        // hearing-assist and blocks the AAP loop while it listens, so it must NOT run in
        // normal use — it's gated behind the LIBREPODS_GATT_PROBE env flag (start the
        // daemon with that var set to enable it). Runs once per session.
        if std::env::var_os("LIBREPODS_GATT_PROBE").is_some() {
            static GATT_PROBED: AtomicBool = AtomicBool::new(false);
            if !GATT_PROBED.swap(true, Ordering::Relaxed) {
                for line in gatt::probe(&driver) {
                    log(&line);
                }
            }
        }
        hr_decoder.reset(); // fresh connection — drop any stale HR carry
        // Re-arm the HR stream if the user had it on before the (re)connect —
        // through the same retry path (the stream rarely starts first try).
        if ctx.hr_on.load(Ordering::Relaxed) {
            spawn_hr_retry(&ctx);
        }
        // Re-arm the hi-res mic uplink if it was engaged before the drop. The AirPods
        // forget their stream state on a BT disconnect, so without re-sending
        // START_AUDIO they never resume pushing 0x58 uplink packets — the device
        // looks connected but the virtual mic stays silent (and the app, seeing no
        // input, churns the audio device connecting/disconnecting). Drop the decoder
        // so the first packet after the resume lays a fresh silence cushion.
        decoder = None;
        mic_announced = false; // re-announce "operational" once audio resumes
        if ctx.mic_on.load(Ordering::Relaxed) {
            let _ = driver.send(&aap::START_AUDIO);
            log("run_receiver: re-armed hi-res mic uplink (mic was on before reconnect)");
        }

        let mut we_paused = false;
        let mut prev_ear = [false; 2]; // last [primary, secondary] in-ear state
        let mut last_status = Instant::now();
        let mut heartbeat = Instant::now();
        // ---- AAP keepalive: a probe with an expected answer, TCP-ACK style ----
        //
        // This is the detection that was missing. On 2026-08-31 the sound died for
        // ten and a half minutes while `driver.status()` read Ok(2) every second,
        // the Bluetooth device stayed connected and the endpoint stayed OK: there
        // was no passive signal to watch, anywhere. But an AAP control command is
        // acknowledged — the buds echo a status packet — so we can ASK instead of
        // waiting. Silence in response to a question is itself the signal.
        //
        // The probe re-asserts the noise mode the buds are already in: a write they
        // answer, that changes nothing audible. It runs only while this host is
        // actually playing something (a dead link matters then, and only then), and
        // two unanswered probes in a row trigger the same rebuild the user performs
        // by hand.
        let mut keepalive = Instant::now();
        // (when the probe was sent, what `last_data` was at that moment)
        let mut probe_pending: Option<(Instant, Instant)> = None;
        let mut probe_misses = 0u32;
        let mut last_audio = Instant::now(); // last time hi-res mic SDUs arrived
        let mut status_fails = 0u32;
        let mut low_warned = false; // low-battery overlay fired (hysteresis)
        let mut case_low_warned = false; // case low-battery overlay fired
        // Per-bud ear status, to notify on the transition into the case.
        let mut prev_status = [aap::EarStatus::Disconnected; 2];
        // HR diagnostics (logged every ~3s while monitoring).
        let mut hr_last_log = Instant::now();
        let (mut hr_bytes, mut hr_frames, mut hr_samples) = (0usize, 0u32, 0u32);
        // Frames carrying the type-19 heart-rate signature `08 13 1a 12` (vs the
        // 50 Hz type-16 raw-PPG flood, which shares the RTBuddy prefix).
        let mut hr_type19 = 0u32;
        let mut hr_type14 = 0u32; // head-tracking frames (sensor-service contention)
        // Diagnose stale-"connected": throttled log of the raw driver status when
        // it isn't a clean 2, so we can see what "cased" vs "both-out-resting"
        // actually report (the teardown decision hinges on them differing).
        let mut status_diag = Instant::now();
        // Zombie-channel detection. The channel can sit OPEN but silent for as long
        // as Windows still sees the AirPods (observed: 56 minutes) — `status_fails`
        // is reset every pass in that case, so the loop never releases and never
        // rebuilds, and the in-place mic re-arm below is gated behind `mic_on`. With
        // the mic off nothing ever nudges it: the AirPods are attached to the PC but
        // not to us, and the hi-res mic is dead. We surface that as `link_stale` so
        // the app can offer "Repair connection" (its button used to be hidden,
        // because `connected` stays true), and we nudge in place first.
        let mut link_stale = false;
        let mut last_link_nudge = Instant::now();
        // Last time an AAP packet actually arrived. The driver State drops to 0
        // both on a transient channel re-negotiation (e.g. both buds just left the
        // ears) and on a real disconnect (cased / on the phone) — status alone
        // can't tell them apart, so data flow is the tie-breaker.
        let mut last_data = Instant::now();
        // Rate-limit the in-place mic-uplink re-arm (see the stall handler below).
        let mut last_mic_rearm = Instant::now();
        // ATT (PSM 0x001F) hearing-aid server diagnostics, polled from the driver
        // and logged on change (DebugView never showed the driver's KdPrint).
        let mut att_poll = Instant::now();
        let mut last_att: (i32, u32, u32, i32, u32) = (0, 0, 0, 0, 0);
        loop {
            if att_poll.elapsed() >= Duration::from_millis(1500) {
                att_poll = Instant::now();
                if let Ok(d) = driver.att_diag() {
                    if d != last_att {
                        let was_open = last_att.4;
                        last_att = d;
                        let _ = was_open;
                        log(&format!(
                            "ATT: register=0x{:08X} registered={} indications={} accept=0x{:08X} channel_open={}",
                            d.0 as u32, d.1, d.2, d.3 as u32, d.4
                        ));
                    }
                }
            }
            // The user pressed Disconnect (connect_requested cleared) — release.
            if !ctx.connect_requested.load(Ordering::Relaxed) {
                log("run_receiver: disconnect requested — releasing");
                *ctx.driver_cell.lock().unwrap() = None;
                break;
            }
            // The user pressed "Repair connection": drop + reopen the driver for a
            // fresh AAP session + ATT channel. connect_requested stays set, so the
            // outer loop reconnects instead of releasing.
            if ctx.wants_reconnect.swap(false, Ordering::Relaxed) {
                log("run_receiver: repair requested — reopening AACP channel");
                *ctx.driver_cell.lock().unwrap() = None;
                break;
            }
            let mut got_data = false;
            if let Ok(n) = driver.recv(2000, &mut buf) {
                if n > 0 {
                    got_data = true;
                    last_data = Instant::now();
                    if link_stale {
                        link_stale = false;
                        ctx.state.lock().unwrap().link_stale = false;
                        ctx.push_state();
                        log("run_receiver: AAP channel talking again — link_stale cleared");
                    }
                    let data = &buf[..n];
                    // Forward the raw packet to the full app (if attached) so it
                    // runs its own AAP session over us.
                    ctx.forward_l2cap(data);
                    // Heart rate (opt-in): feed each chunk into the RTBuddy
                    // decoder; publish the latest validated BPM. Inert when off.
                    if ctx.hr_on.load(Ordering::Relaxed) {
                        hr_bytes += data.len();
                        if hr::contains_frame_prefix(data) {
                            hr_frames += 1;
                            ctx.hr_stream_live.store(true, Ordering::Relaxed);
                            // Count head-tracking (type 14: `08 0e 1a`) frames too,
                            // to see whether it's still streaming and stealing the
                            // sensor service from the computed heart rate.
                            if data.windows(3).any(|w| w == [0x08, 0x0e, 0x1a]) {
                                hr_type14 += 1;
                            }
                            // Only the 1 Hz heart-rate stream carries `08 13 1a 12`
                            // (type 19, 18-byte payload). Dump those; the 50 Hz
                            // type-16 raw-PPG frames share the prefix and would
                            // drown the log, so we only count them (hr_frames).
                            if data.windows(4).any(|w| w == [0x08, 0x13, 0x1a, 0x12]) {
                                hr_type19 += 1;
                                let dump: String = data
                                    .iter()
                                    .take(48)
                                    .map(|b| format!("{b:02x}"))
                                    .collect::<Vec<_>>()
                                    .join(" ");
                                log(&format!("HR type-19 ({} bytes): {dump}", data.len()));
                            }
                        }
                        let samples = hr_decoder.feed(data);
                        hr_samples += samples.len() as u32;
                        if !samples.is_empty() {
                            // Rendezvous with the retry thread: the stream is live.
                            ctx.hr_got_sample.store(true, Ordering::Relaxed);
                        }
                        if let Some(bpm) = samples.into_iter().last() {
                            let changed = {
                                let mut s = ctx.state.lock().unwrap();
                                let c = s.heart_rate != Some(bpm);
                                s.heart_rate = Some(bpm);
                                c
                            };
                            if changed {
                                ctx.push_state();
                            }
                        }
                        if hr_last_log.elapsed() >= Duration::from_secs(3) {
                            log(&format!(
                                "HR diag: bytes={hr_bytes} prefix={hr_frames} type14={hr_type14} type19={hr_type19} bpm_samples={hr_samples}"
                            ));
                            hr_last_log = Instant::now();
                            hr_bytes = 0;
                            hr_frames = 0;
                            hr_type19 = 0;
                            hr_type14 = 0;
                            hr_samples = 0;
                        }
                    } else if ctx.state.lock().unwrap().heart_rate.take().is_some() {
                        ctx.push_state();
                    }
                    // Hi-res mic: decode the 0x58 uplink AUs → feed the virtual mic.
                    if ctx.mic_on.load(Ordering::Relaxed) {
                        if aap::is_audio_packet(data) {
                            last_audio = Instant::now(); // watchdog: stream alive
                            if decoder.is_none() {
                                decoder = eld::Decoder::new();
                                // Prime the ring with silence so the first real
                                // AUs don't land in an empty buffer. The driver
                                // trims this back to its steady-state target on
                                // the same write (MicPipe.cpp), and trimmed
                                // silence costs nothing — so err on the generous
                                // side here rather than risk an opening underrun.
                                ctx.pipe.write(&[0i16; 3840]);
                            }
                            if let Some(dec) = decoder.as_mut() {
                                let mut out: Vec<i16> = Vec::new();
                                aap::for_each_au(data, |au| out.extend_from_slice(dec.decode(au)));
                                if !out.is_empty() {
                                    ctx.pipe.write(&out);
                                    // First real PCM reached the virtual mic — the hi-res
                                    // uplink is fully operational end-to-end. Announce once.
                                    if !mic_announced {
                                        mic_announced = true;
                                        ctx.overlay("Microphone ready — high quality");
                                    }
                                }
                            }
                        }
                    } else if decoder.is_some() {
                        decoder = None;
                        mic_announced = false; // mic released — next session re-announces
                    }
                    // Multipoint: the AirPods' own list of connected hosts. This is
                    // the earliest warning we get — in the 2026-08-31 capture the
                    // phone appeared here 3.2 s before Windows dropped us, and it is
                    // the ONLY signal that moves when the buds hand the audio over
                    // while every Windows-side indicator stays healthy.
                    if let Some(devs) = aap::parse_connected_devices(data) {
                        if devs != last_devices {
                            let list: Vec<ipc::ConnectedDevice> = devs
                                .iter()
                                .map(|(a, flags)| describe_device(*a, *flags, &local_radios))
                                .collect();
                            log(&format!(
                                "multipoint: {} device(s) on the AirPods: {}",
                                list.len(),
                                list.iter()
                                    .zip(devs.iter())
                                    .map(|(d, (_, f))| {
                                        format!(
                                            "{} [{}] {} flags={}",
                                            d.address,
                                            d.kind,
                                            d.name,
                                            aap::flags_hex(*f)
                                        )
                                    })
                                    .collect::<Vec<_>>()
                                    .join(", ")
                            ));
                            ctx.state.lock().unwrap().multipoint = list;
                            ctx.push_state();
                            last_devices = devs;
                        }
                    }
                    // Audio-route diagnostics — see aap::route_diag. Rare packets,
                    // so logging them unconditionally costs nothing.
                    if let Some((op, dump)) = aap::route_diag(data) {
                        log(&format!(
                            "route: 0x{op:02X} {} ({} bytes): {dump}",
                            aap::route_opcode_name(op),
                            data.len()
                        ));
                    }
                    if let Some(b) = aap::parse_battery(data) {
                        ctx.cache_replay(0, data); // replay to a newly-attached app
                        let (batt_text, present) = {
                            let mut s = ctx.state.lock().unwrap();
                            if b.left.is_some() {
                                s.battery.left = b.left;
                                s.battery.left_charging = b.left_charging;
                            }
                            if b.right.is_some() {
                                s.battery.right = b.right;
                                s.battery.right_charging = b.right_charging;
                            }
                            if b.case.is_some() {
                                s.battery.case = b.case;
                                s.battery.case_charging = b.case_charging;
                            }
                            if b.headphone.is_some() {
                                s.battery.headphone = b.headphone;
                                s.battery.headphone_charging = b.headphone_charging;
                            }
                            let present = s.battery.case.is_some();
                            (battery_text(&s.battery, s.connected), present)
                        };
                        ctx.push_state();
                        if pending_card {
                            ctx.overlay(&format!("Connected  ·  {batt_text}"));
                            pending_card = false;
                        } else if last_case_present.is_some_and(|prev| prev != present) {
                            let ev = if present { "Case opened" } else { "Case closed" };
                            ctx.overlay(&format!("{ev}  ·  {batt_text}"));
                        }
                        last_case_present = Some(present);
                        // Low-battery notification: warn once when either bud
                        // falls to <=20%, re-arm only after it recovers above 25%
                        // (hysteresis so it doesn't spam around the threshold).
                        let low = {
                            let s = ctx.state.lock().unwrap();
                            [s.battery.left, s.battery.right].into_iter().flatten().min()
                        };
                        if let Some(min) = low {
                            if min <= 20 && !low_warned {
                                ctx.overlay(&format!("Battery low — {min}%"));
                                low_warned = true;
                            } else if min > 25 {
                                low_warned = false;
                            }
                        }
                        // Case low battery (separate, quieter threshold).
                        if let Some(cl) = { ctx.state.lock().unwrap().battery.case } {
                            if cl <= 15 && !case_low_warned {
                                ctx.overlay(&format!("Case battery low — {cl}%"));
                                case_low_warned = true;
                            } else if cl > 20 {
                                case_low_warned = false;
                            }
                        }
                    }
                    if let Some(m) = aap::parse_anc_mode(data) {
                        ctx.cache_replay(1, data); // replay to a newly-attached app
                        // Ignore transitional echoes that don't match a recent user
                        // command (the buds briefly report Off when switching modes
                        // quickly). Accept once it matches, or when nothing is
                        // pending / the window passed (an external change).
                        let accept = match *ctx.anc_cmd.lock().unwrap() {
                            Some((target, t)) if t.elapsed() < Duration::from_millis(1500) => {
                                m == target
                            }
                            _ => true,
                        };
                        if accept {
                            ctx.state.lock().unwrap().anc = m;
                            ctx.push_state();
                            if last_anc != 0 && m != last_anc {
                                ctx.overlay(aap::anc_name(m));
                            }
                            last_anc = m;
                        }
                    }
                    // Sync the feature toggles from the AirPods' own status echoes
                    // (0x01 = on, 0x02 = off), so the tray checkmarks reflect the
                    // real device state — including whatever the iPhone last set.
                    {
                        let mut changed = false;
                        let mut s = ctx.state.lock().unwrap();
                        for (id, field) in [
                            (ipc::feature::CONVERSATIONAL_AWARENESS, 0),
                            (ipc::feature::ADAPTIVE_VOLUME, 1),
                            (ipc::feature::ALLOW_OFF, 2),
                        ] {
                            if let Some(v) = aap::parse_control_value(data, id) {
                                let on = v == 0x01;
                                let slot = match field {
                                    0 => &mut s.conversational_awareness,
                                    1 => &mut s.adaptive_volume,
                                    _ => &mut s.allow_off,
                                };
                                if *slot != on {
                                    *slot = on;
                                    changed = true;
                                }
                            }
                        }
                        drop(s);
                        if changed {
                            ctx.push_state();
                        }
                    }
                    // Conversational Awareness: the AirPods signal speech start/stop;
                    // we (the host, single volume owner) duck/restore the volume.
                    if let Some(status) = aap::parse_conversational_awareness(data) {
                        // Don't duck while the hi-res mic is in use (you're on a
                        // call — you ARE talking, but the call audio shouldn't
                        // drop). Restore if a duck was already in progress.
                        if ctx.mic_on.load(Ordering::Relaxed) {
                            ctx.conv_duck.lock().unwrap().restore();
                        } else {
                            ctx.conv_duck.lock().unwrap().on_status(status);
                        }
                    }
                    if let Some((model, firmware, serial)) = aap::parse_metadata(data) {
                        // Device identity (0x1D): store model/firmware/serial once.
                        let changed = {
                            let mut s = ctx.state.lock().unwrap();
                            let c = s.model != model;
                            if c {
                                s.model = model.clone();
                                s.firmware = firmware;
                                s.serial = serial;
                            }
                            c
                        };
                        if changed {
                            log(&format!("device metadata: model={model}"));
                            ctx.push_state();
                        }
                    }
                    if let Some((primary, secondary)) = aap::parse_ear_detection(data) {
                        // "In case" notification on the transition into the case.
                        // Ear-detection reports both buds together (never partial,
                        // unlike the battery packet) and comes over AAP — so this
                        // is reliable with no BLE, hence no audio static.
                        let now = [primary, secondary];
                        for i in 0..2 {
                            if now[i] == aap::EarStatus::InCase
                                && prev_status[i] != aap::EarStatus::InCase
                            {
                                ctx.overlay("AirPod in case");
                                break; // one overlay even if both go in at once
                            }
                        }
                        prev_status = now;
                        // 0x04 is a transitional (in-motion) value — hold the prior
                        // in-ear state for that bud instead of reading it as "out",
                        // so a bud being handled doesn't trigger a false auto-pause.
                        let new_ear = [
                            if primary.is_transitional() { prev_ear[0] } else { primary.in_ear() },
                            if secondary.is_transitional() { prev_ear[1] } else { secondary.in_ear() },
                        ];
                        if new_ear != prev_ear {
                            let all_in = new_ear[0] && new_ear[1];
                            let was_wearing = prev_ear[0] || prev_ear[1];
                            if all_in {
                                // both back in the ears → resume what we paused
                                if we_paused {
                                    media::play();
                                    we_paused = false;
                                }
                            } else if was_wearing && media::is_playing() {
                                // a bud was just removed (Apple-style: pause on a
                                // single removal, not only when both are out)
                                media::pause();
                                we_paused = true;
                            }
                            prev_ear = new_ear;
                        }
                    }
                }
            }
            if !got_data {
                // Tight while streaming (no ring underrun), throttle hard on idle.
                let nap = if ctx.mic_on.load(Ordering::Relaxed) { 4 } else { 150 };
                thread::sleep(Duration::from_millis(nap));
            }
            // Hi-res mic stall handling. Linux PR #655 re-armed the uplink (STOP→START)
            // on a stall, but on a weak RF link that churn tips the L2CAP link into a
            // full reconnect and drops the call — so we never touch the uplink here.
            // Crucially, while the mic is IN USE we leave the stream completely alone:
            // a silence is just you not speaking (muted / listening), not a fault, and
            // dropping the decoder mid-call would glitch the next words. We only reset
            // the clock and drop the decoder when the mic is NOT in use, so the next
            // capture session starts from a clean silence cushion.
            if ctx.mic_on.load(Ordering::Relaxed) {
                // in use — do nothing
            } else {
                last_audio = Instant::now();
                if decoder.is_some() {
                    decoder = None;
                    mic_announced = false;
                }
            }
            // Did the outstanding probe get its ACK? Any inbound AAP packet counts:
            // we are asking whether the channel carries traffic at all, not which
            // reply came back.
            if let Some((sent_at, baseline)) = probe_pending {
                if last_data > baseline {
                    probe_pending = None;
                    if probe_misses > 0 {
                        log("keepalive: answered — channel alive again");
                        probe_misses = 0;
                    }
                } else if sent_at.elapsed() >= Duration::from_secs(5) {
                    probe_pending = None;
                    probe_misses += 1;
                    log(&format!("keepalive: NO answer in 5s (miss {probe_misses}/2)"));
                    if probe_misses >= 2 {
                        probe_misses = 0;
                        // Two unanswered probes while audio should be playing: the
                        // buds are not listening to us, however healthy Windows
                        // claims everything is. Do what the user does by hand.
                        log("keepalive: link is dead despite a healthy status — rebuilding the audio route");
                        ctx.overlay("Restoring audio");
                        let mac2 = mac;
                        thread::spawn(move || force_audio_rebuild(mac2));
                    }
                }
            }
            // Send the next probe, but only while this host is actually playing.
            if probe_pending.is_none() && keepalive.elapsed() >= Duration::from_secs(30) {
                keepalive = Instant::now();
                let mode = ctx.state.lock().unwrap().anc;
                if mode != 0
                    && (ctx.mic_on.load(Ordering::Relaxed) || volume::any_render_active())
                {
                    let baseline = last_data;
                    if driver.send(&aap::anc_command(mode)).is_ok() {
                        probe_pending = Some((Instant::now(), baseline));
                    }
                }
            }
            // Heartbeat, logged UNCONDITIONALLY every 60 s.
            //
            // On 2026-08-31 the audio died for ten and a half minutes and the log
            // held not one line: `driver.status()` read Ok(2) every second, so the
            // whole diagnostic path below — the status diag, the link_stale check,
            // the release decision — sat inside the `else` branch and never ran.
            // We were blind by construction. This says, once a minute, how long it
            // has actually been since an AAP packet arrived, so the next time the
            // sound stops we can tell a quiet channel from a healthy one.
            if heartbeat.elapsed() >= Duration::from_secs(60) {
                heartbeat = Instant::now();
                log(&format!(
                    "heartbeat: aap_data_age={}ms status={:?} mic={} tier_probe_ok=true",
                    last_data.elapsed().as_millis(),
                    driver.status(),
                    ctx.mic_on.load(Ordering::Relaxed)
                ));
            }
            if last_status.elapsed() >= Duration::from_secs(1) {
                last_status = Instant::now();
                let st = driver.status();
                if !matches!(st, Ok(2)) && status_diag.elapsed() >= Duration::from_secs(2) {
                    let both_out = !prev_ear[0] && !prev_ear[1];
                    log(&format!(
                        "status diag: st={st:?} both_out={both_out} fails={status_fails} data_age={}ms",
                        last_data.elapsed().as_millis()
                    ));
                    status_diag = Instant::now();
                }
                if matches!(st, Ok(2)) {
                    status_fails = 0;
                } else {
                    // The driver State reads not-connected far more often than a real
                    // loss: whenever the buds play audio, the A2DP stream contends for the
                    // radio and the AAP channel goes quiet, and an idle channel reads the
                    // same. Tearing down on it churns a reconnect that toggles the OS
                    // audio link and kicks calls / playback. So don't trust the driver
                    // State — trust Windows' own BT status: hold the session as long as
                    // the OS still sees the AirPods connected, and give up only once it
                    // has lost them for a few seconds (really cased / handed to the phone).
                    //
                    // This used to call `bt::find_airpods().is_some()`, which does NOT
                    // answer that question: it enumerates with `fReturnRemembered` and
                    // never reads `fConnected`, so it is `Some` for any *paired* device,
                    // connected or not. The branch was therefore always taken and
                    // `status_fails` was pinned at 0 forever — the session was never
                    // released after the buds went to the phone, `connected` stayed true,
                    // the BLE watcher (which only scans while disconnected) never re-armed,
                    // and nothing ever asked for the audio back. That is the "loses the
                    // audio and never recovers" bug; daemon.log shows its signature as
                    // `fails=0` next to a data_age climbing past 42 s. `bt::is_connected`
                    // asks the real question.
                    let release = if bt::is_connected(mac) {
                        status_fails = 0; // still connected to Windows — keep the session
                        // Connected, but the AAP channel may have stalled (not just idle).
                        // If the hi-res mic is engaged and the channel has been silent for
                        // a while, the mic uplink has died — rebuild the channel IN PLACE
                        // (drop + reopen, which re-arms START_AUDIO on the handshake) to
                        // recover it, WITHOUT toggling the OS audio, so the mic comes back
                        // and the call isn't kicked. connect_requested stays set → the
                        // outer loop reopens the L2CAP channel, no set_audio_connected.
                        // Mic engaged but the AAP channel has gone silent — the uplink
                        // may have stalled. NEVER drop the channel here: the user is on a
                        // call, so a reconnect kicks it, and repeated drop+reopen churn
                        // bricks the driver into Code 38 (a self-feeding "driver open
                        // FAILED" loop). Instead re-arm the uplink IN PLACE by re-sending
                        // START_AUDIO on the still-open channel, at most once every few
                        // seconds. If it was just a silence (you not speaking), this is a
                        // harmless no-op; if the stream really stopped, it nudges the 0x58
                        // uplink back WITHOUT touching the L2CAP link.
                        if ctx.mic_on.load(Ordering::Relaxed)
                            && last_data.elapsed() >= Duration::from_secs(8)
                            && last_mic_rearm.elapsed() >= Duration::from_secs(5)
                        {
                            log("run_receiver: mic uplink silent — re-arming START_AUDIO in place");
                            let _ = driver.send(&aap::START_AUDIO);
                            last_mic_rearm = Instant::now();
                        }
                        // Zombie channel: open, the OS still sees the buds, but nothing
                        // has arrived for a long while and the mic isn't on (so the
                        // re-arm above never fires). Nudge IN PLACE first — re-assert
                        // the noise mode we already believe is active. That's a write
                        // the buds accept, it changes nothing audible, and if the link
                        // is alive at all they answer with a 0x0D control status, which
                        // clears the flag above. We deliberately do NOT drop + reopen
                        // the driver here: that is what "Repair connection" does, and
                        // repeated drop+reopen churn is what bricks it into Code 38.
                        // Recovery stays the user's call — we just make it offerable.
                        if !ctx.mic_on.load(Ordering::Relaxed)
                            && last_data.elapsed() >= Duration::from_secs(30)
                        {
                            if !link_stale {
                                link_stale = true;
                                ctx.state.lock().unwrap().link_stale = true;
                                ctx.push_state();
                                log("run_receiver: AAP channel silent >30s with mic off — link_stale set");
                            }
                            if last_link_nudge.elapsed() >= Duration::from_secs(30) {
                                let mode = ctx.state.lock().unwrap().anc;
                                if mode != 0 {
                                    log("run_receiver: nudging the silent channel in place (re-assert noise mode)");
                                    let _ = driver.send(&aap::anc_command(mode));
                                }
                                last_link_nudge = Instant::now();
                            }
                        }
                        false
                    } else {
                        status_fails += 1;
                        status_fails >= 3
                    };
                    if release {
                        // Which tier did we hold when the link went? That decides
                        // whether the loss was legitimate under Apple's order (see
                        // `Tier`) or a tier-3 event on another device jumping the
                        // queue — an iPhone notification chime being the case here.
                        let tier = host_tier(&ctx);
                        let cooling = ctx
                            .last_reclaim
                            .lock()
                            .unwrap()
                            .is_some_and(|t| t.elapsed() < RECLAIM_COOLDOWN);
                        log(&format!(
                            "run_receiver: AirPods no longer connected (OS) — releasing (host tier {}{})",
                            tier.label(),
                            if cooling { ", reclaim cooling down" } else { "" }
                        ));
                        ctx.overlay("Disconnected");
                        {
                            let mut s = ctx.state.lock().unwrap();
                            s.connected = false;
                            s.heart_rate = None; // stale once the link is gone
                        }
                        *ctx.driver_cell.lock().unwrap() = None;
                        hr_decoder.reset(); // drop HR carry across the reconnect
                        last_anc = 0;
                        last_case_present = None;
                        // Arm the rebuild regardless of tier. The tier decides
                        // whether it is worth CHASING the buds; it must not decide
                        // whether the audio gets repaired once they are back. Most
                        // of the user's losses happen at tier 3 (they picked up the
                        // phone with nothing playing here), and those were exactly
                        // the ones that never recovered.
                        ctx.audio_rebuild_pending.store(true, Ordering::Relaxed);
                        if tier.defends_audio() && !cooling {
                            // We were playing or on a call: a notification on the
                            // phone doesn't outrank that. Keep `connect_requested`
                            // set so the AAP session re-opens as soon as the audio
                            // link is back, and let the campaign clear it if the
                            // phone turns out to be holding them for real.
                            spawn_reclaim(&ctx, tier);
                        } else {
                            // Nothing of ours was playing — never reconnect on our
                            // own (that would steal them back from the iPhone).
                            // Wait for the BLE watcher / a prompt.
                            ctx.connect_requested.store(false, Ordering::Relaxed);
                        }
                        ctx.push_state();
                        break;
                    }
                }
            }
        }
        thread::sleep(Duration::from_secs(2));
    }
}

/// Auto-activate: enable the hi-res stream when an app records from the virtual
/// mic, disable it (debounced) when it stops, and restore A2DP stereo.
fn poll_mic(ctx: Ctx) {
    const MIC_IDLE_STOP_POLLS: u32 = 20; // 20 × 500 ms = 10 s (bridges VAD/probe gaps)
    let mut prev = ctx.pipe.status();
    let mut idle = 0u32;
    let mut on = false;
    loop {
        thread::sleep(Duration::from_millis(500));
        // Self-healing: `status()` reopens the pipe if it wasn't ready at boot or the
        // handle broke, so mic auto-detection never dies silently.
        let cur = ctx.pipe.status();
        let capturing = cur != prev;
        prev = cur;
        if !ctx.auto_mode.load(Ordering::Relaxed) {
            on = ctx.mic_on.load(Ordering::Relaxed);
            continue;
        }
        if capturing {
            idle = 0;
            if !on {
                on = true;
                ctx.mic_on.store(true, Ordering::Relaxed);
                if let Some(drv) = ctx.driver_cell.lock().unwrap().clone() {
                    let _ = drv.send(&aap::START_AUDIO);
                }
                ctx.overlay("Using the AirPods microphone");
                ctx.push_state();
            }
        } else {
            idle += 1;
            if on && idle >= MIC_IDLE_STOP_POLLS {
                on = false;
                ctx.mic_on.store(false, Ordering::Relaxed);
                // No STOP_AUDIO, no A2DP restore and no overlay — see the note in
                // set_mic(). Clearing `mic_on` stops the decoding; the uplink stays
                // armed so we never cross the exit that leaves playback on one side.
                ctx.push_state();
            }
        }
    }
}

fn main() {
    // Single instance: never run two daemons over the one exclusive driver.
    unsafe {
        let name = wide("Local\\LibrePodsDaemonSingleton");
        let _ = CreateMutexW(ptr::null(), 0, name.as_ptr());
        if GetLastError() == ERROR_ALREADY_EXISTS {
            return;
        }
    }

    log("=== librepodsd start ===");
    let (mac, dev_name) = match bt::find_airpods() {
        Some((m, n)) => (m, n),
        None => (0, "AirPods".to_string()),
    };
    log(&format!("find_airpods: mac={mac:#x} name='{dev_name}'"));

    let pipe = Arc::new(micpipe::MicPipeCell::new());
    log(&format!("mic pipe opened: {}", pipe.is_open()));
    let ctx = Ctx {
        state: Arc::new(Mutex::new(Snapshot {
            dev_name: dev_name.clone(),
            auto_mode: true,
            ..Default::default()
        })),
        clients: Arc::new(Mutex::new(Vec::new())),
        l2cap_clients: Arc::new(Mutex::new(Vec::new())),
        replay: Arc::new(Mutex::new(std::collections::HashMap::new())),
        driver_cell: Arc::new(Mutex::new(None)),
        mic_on: Arc::new(AtomicBool::new(false)),
        auto_mode: Arc::new(AtomicBool::new(true)),
        hr_on: Arc::new(AtomicBool::new(false)),
        hr_got_sample: Arc::new(AtomicBool::new(false)),
        hr_stream_live: Arc::new(AtomicBool::new(false)),
        hr_retrying: Arc::new(AtomicBool::new(false)),
        connect_requested: Arc::new(AtomicBool::new(false)),
        wants_reconnect: Arc::new(AtomicBool::new(false)),
        pipe,
        conv_duck: Arc::new(Mutex::new(volume::ConvDuck::default())),
        pending_rename: Arc::new(Mutex::new(None)),
        anc_cmd: Arc::new(Mutex::new(None)),
        reclaiming: Arc::new(AtomicBool::new(false)),
        audio_rebuild_pending: Arc::new(AtomicBool::new(false)),
        user_disconnected: Arc::new(AtomicBool::new(false)),
        last_reclaim: Arc::new(Mutex::new(None)),
        last_repair: Arc::new(Mutex::new(None)),
        dev_name: Arc::new(Mutex::new(dev_name.clone())),
        mac,
    };

    // Name the virtual mic after the connected device (elevated task, no UAC).
    if mac != 0 {
        rename::apply(&dev_name);
    }

    // IPC: two one-directional pipe servers (events out, commands in).
    {
        let c = ctx.clone();
        thread::spawn(move || unsafe { events_server(c) });
    }
    {
        let c = ctx.clone();
        thread::spawn(move || unsafe { cmds_server(c) });
    }
    // Raw-L2CAP proxy for the full app (Phase 3): RX (packets → app) + TX (app → driver).
    {
        let c = ctx.clone();
        thread::spawn(move || unsafe { l2cap_rx_server(c) });
    }
    {
        let c = ctx.clone();
        thread::spawn(move || unsafe { l2cap_tx_server(c) });
    }

    // BLE proximity: prompt "connect?" ONCE when the AirPods appear. The watcher
    // fires on every advertisement (several/sec), so a plain time debounce still
    // re-asks forever while they sit nearby — spammy, and it ignores a dismissed
    // prompt. Instead edge-trigger: fire once, then stay quiet until the buds have
    // been ABSENT (no advertisement for a while — cased or out of range) and return,
    // which is the natural "ask again on the next case-open" behaviour.
    if mac != 0 {
        let c = ctx.clone();
        let c_scan = ctx.clone();
        // (prompted_this_visit, last_advertisement_seen)
        let prox: Arc<Mutex<(bool, Instant)>> = Arc::new(Mutex::new((false, Instant::now())));
        thread::spawn(move || {
            le::watch_nearby(
                move || {
                    let now = Instant::now();
                    let mut p = prox.lock().unwrap();
                    // A >45 s gap since the last advertisement means they went away;
                    // re-arm so their next appearance prompts once more.
                    if now.duration_since(p.1) > Duration::from_secs(45) {
                        p.0 = false;
                    }
                    p.1 = now;
                    if p.0
                        || c.state.lock().unwrap().connected
                        || c.connect_requested.load(Ordering::Relaxed)
                    {
                        return;
                    }
                    p.0 = true; // acted on this visit — don't re-fire until they leave
                    drop(p);
                    // Auto-connect: open the AAP session automatically when the
                    // AirPods appear, instead of prompting. run_receiver picks up
                    // connect_requested; also nudge the OS audio up in case the
                    // classic link is down.
                    c.connect_requested.store(true, Ordering::Relaxed);
                    audio_request(c.mac, true, "auto");
                    log("ble: AirPods nearby → auto-connecting");
                },
                // Only scan while idle (disconnected) — no BLE radio during audio.
                move || !c_scan.state.lock().unwrap().connected,
            );
        });
    }

    // AAP session + auto-activate poll (only if we have a paired device).
    if mac != 0 {
        {
            let c = ctx.clone();
            thread::spawn(move || run_receiver(c));
        }
        {
            let c = ctx.clone();
            thread::spawn(move || poll_mic(c));
        }
    }
    // Volume poller: keep the Snapshot's volume/mute fresh (the daemon owns
    // volume) so the tray renders it — runs regardless of a paired device.
    {
        let c = ctx.clone();
        thread::spawn(move || {
            volume::init();
            // Wake the AirPods whenever playback STARTS on this host.
            //
            // The failure this prevents leaves no trace: on 2026-08-31 the sound
            // died for ten and a half minutes with the AAP channel at Ok(2) every
            // second, the Bluetooth device connected and the endpoint OK — nothing
            // to detect, anywhere. What brought it back was poking the buds with an
            // AAP command and restarting the video.
            //
            // So instead of detecting the fault we pre-empt it: on the transition
            // from silence to playing, re-assert the noise mode the buds are
            // already in. That is a write they acknowledge, it changes nothing
            // audible, and it is exactly the poke that worked by hand. It fires
            // only on the edge (not while audio keeps playing) and is rate-limited,
            // so a normal listening session sends one packet, not a stream of them.
            let mut was_playing = false;
            let mut last_wake = Instant::now() - Duration::from_secs(60);
            loop {
                c.sync_volume();
                let playing = volume::any_render_active();
                if playing && !was_playing && last_wake.elapsed() >= Duration::from_secs(3) {
                    let mode = c.state.lock().unwrap().anc;
                    if mode != 0 {
                        if let Some(drv) = c.driver_cell.lock().unwrap().clone() {
                            let _ = drv.send(&aap::anc_command(mode));
                            last_wake = Instant::now();
                            log("audio: playback started — waking the AirPods (re-assert noise mode)");
                        }
                    }
                }
                was_playing = playing;
                // Flush a settled rename into a confirmation overlay.
                let ready = {
                    let mut p = c.pending_rename.lock().unwrap();
                    match p.as_ref() {
                        Some((_, t)) if t.elapsed() >= Duration::from_millis(900) => p.take(),
                        _ => None,
                    }
                };
                if let Some((name, _)) = ready {
                    c.overlay(&format!("Renamed to “{name}”"));
                }
                thread::sleep(Duration::from_millis(500));
            }
        });
    }

    log("threads spawned; serving events + cmds pipes");
    loop {
        thread::sleep(Duration::from_secs(3600));
    }
}
