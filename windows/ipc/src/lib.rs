//! Shared IPC protocol between `librepodsd` (the driver-owning daemon) and the
//! LibrePods UIs (the tray + the full app). Newline-delimited JSON over a Windows
//! named pipe — see `../../../docs/windows/daemon-ipc/PLAN.md`.

use serde::{Deserialize, Serialize};

/// Two one-directional named pipes (a single duplex pipe deadlocks: a Windows
/// *synchronous* handle serializes I/O, so a blocking ReadFile for commands
/// stalls the WriteFile for events on the same handle). The daemon only WRITES
/// events on `PIPE_EVENTS` and only READS commands on `PIPE_CMDS`, so no handle
/// ever does both directions concurrently.
pub const PIPE_EVENTS: &str = r"\\.\pipe\LibrePods-events";
pub const PIPE_CMDS: &str = r"\\.\pipe\LibrePods-cmds";

/// Raw L2CAP proxy for the full app (Phase 3): the daemon owns the exclusive
/// driver, so the app can't open it — it runs its AAP session over these instead.
/// The daemon writes each incoming AAP packet to `PIPE_L2CAP_RX` (length-prefixed:
/// a u16 LE length, then the bytes) and reads the app's outgoing packets (same
/// framing) from `PIPE_L2CAP_TX`, forwarding them to the driver. One pipe per
/// direction (a sync duplex handle would deadlock).
pub const PIPE_L2CAP_RX: &str = r"\\.\pipe\LibrePods-l2cap-rx";
pub const PIPE_L2CAP_TX: &str = r"\\.\pipe\LibrePods-l2cap-tx";

/// Battery levels (percent), each optional — a packet may carry only some.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Battery {
    pub left: Option<u8>,
    pub right: Option<u8>,
    pub case: Option<u8>,
    pub headphone: Option<u8>,
    // Per-component charging flag (default false for backward-compatible parsing).
    #[serde(default)]
    pub left_charging: bool,
    #[serde(default)]
    pub right_charging: bool,
    #[serde(default)]
    pub case_charging: bool,
    #[serde(default)]
    pub headphone_charging: bool,
}

/// The daemon's authoritative state, pushed to clients on connect and on change.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Snapshot {
    pub connected: bool,
    /// The AAP session is nominally up (`connected`) but the channel has gone
    /// silent — the AirPods are attached to Windows yet not talking to us, so the
    /// hi-res mic and every live notification are dead. The app surfaces "Repair
    /// connection" on this, because `connected` alone stays true forever in that
    /// state and used to hide the button exactly when it was needed.
    /// (serde default: older snapshots.)
    #[serde(default)]
    pub link_stale: bool,
    pub dev_name: String,
    pub battery: Battery,
    /// Noise-control mode: 0 = unknown, 1 = off, 2 = ANC, 3 = transparency, 4 = adaptive.
    pub anc: u8,
    /// An app is currently recording from the virtual mic (hi-res stream on).
    pub mic_recording: bool,
    /// Auto-enable the hi-res mic on recording (vs. manual control).
    pub auto_mode: bool,
    /// Conversational Awareness: lower the volume automatically when you speak.
    pub conversational_awareness: bool,
    /// Adaptive/Personalized Volume: adjust the volume to the environment.
    pub adaptive_volume: bool,
    /// Allow the "Off" option in noise control (vs. only ANC/Transparency/Adaptive).
    pub allow_off: bool,
    /// System output volume 0..=100 (the default render endpoint — the AirPods
    /// when they're active). Owned by the daemon so it can duck for CA.
    pub volume: u8,
    /// The output is muted.
    pub muted: bool,
    /// Latest validated heart-rate BPM (AirPods Pro 3 RTBuddy). `None` when HR
    /// monitoring is off or no sample has been decoded yet. Opt-in (drains battery).
    pub heart_rate: Option<u16>,
    /// Device metadata parsed from the 0x1D packet (model number, firmware, serial).
    /// Empty until that packet arrives. The serial is sensitive — the app keeps
    /// this info hidden behind a reveal toggle. (serde default: older snapshots.)
    #[serde(default)]
    pub model: String,
    #[serde(default)]
    pub firmware: String,
    #[serde(default)]
    pub serial: String,
    /// Every host the AirPods report themselves connected to (AAP 0x2E) — this PC
    /// plus whatever else is sharing them. Multipoint is invisible to Windows, so
    /// this list is the only place the user can see that a phone is also holding
    /// the buds. Empty until the first 0x2E arrives. (serde default: older snapshots.)
    #[serde(default)]
    pub multipoint: Vec<ConnectedDevice>,
}

/// One host connected to the AirPods, as reported by the buds themselves.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ConnectedDevice {
    /// 48-bit Bluetooth address, formatted `aa:bb:cc:dd:ee:ff`.
    pub address: String,
    /// True when this entry is one of our own radios.
    pub is_this_pc: bool,
    /// Best-effort human label: the Windows name when the device happens to be
    /// paired here, else a type guess, else "Unknown device".
    pub name: String,
    /// Device kind for the UI to pick an icon: "pc", "phone", "laptop", "watch",
    /// "tablet", "audio" or "unknown".
    pub kind: String,
}

/// A toggleable AAP control-command setting (the `id` byte of a 0x09 control
/// command). Values are sent as 0x01 (on) / 0x02 (off).
pub mod feature {
    pub const ADAPTIVE_VOLUME: u8 = 0x26;
    pub const CONVERSATIONAL_AWARENESS: u8 = 0x28;
    pub const ALLOW_OFF: u8 = 0x34;
}

/// Client → daemon. (Volume stays client-side via WASAPI — not the exclusive
/// resource — so it isn't routed through the daemon.)
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "cmd", rename_all = "snake_case")]
pub enum Command {
    /// Sent on connect; the daemon replies with a `State` snapshot.
    Hello { kind: ClientKind },
    /// Set noise-control mode (1..=4).
    SetAnc { mode: u8 },
    /// Set the hi-res mic mode (auto-enable and/or manual override).
    SetMicMode { auto: bool, manual: bool },
    /// Toggle an AAP control-command setting (see the `feature` module).
    SetFeature { feature: u8, on: bool },
    /// Set a raw AAP control-command value (opcode 0x09) — e.g. Adaptive noise
    /// strength (id 0x2E, value 0..=100). For settings that aren't on/off.
    SetControl { id: u8, value: u8 },
    /// Nudge the output volume by `delta` percent (the daemon owns volume).
    StepVolume { delta: i32 },
    /// Set the output volume to an absolute percent 0..=100 (e.g. a slider).
    SetVolume { percent: u8 },
    /// Mute/unmute the output.
    ToggleMute,
    /// Enable/disable AirPods Pro 3 heart-rate monitoring (opt-in; off by
    /// default because it drains battery). On sends the RTBuddy enable sequence;
    /// off sends the stop frame and clears `heart_rate`.
    SetHeartRate { on: bool },
    /// AirPods Pro 3 hearing assistance (accessibility amplification). On enables
    /// hearing-assist over AAP (0x2C/0x33), switches to Transparency, and writes the
    /// full settings to the ATT/GATT (PSM 0x001F, handle 0x2A); off disables it.
    /// `left_eq`/`right_eq` are the per-person audiogram: 8 bands of hearing loss in
    /// dB HL (250/500/1k/2k/3k/4k/6k/8k Hz). `amplification` 0..=1 overall gain,
    /// `balance` -1(L)..=1(R), `tone` -1..=1, `ambient_noise_reduction`/`own_voice`
    /// 0..=1, plus conversation boost.
    SetHearingAid {
        on: bool,
        left_eq: Vec<f32>,
        right_eq: Vec<f32>,
        amplification: f32,
        balance: f32,
        tone: f32,
        conversation_boost: bool,
        ambient_noise_reduction: f32,
        own_voice: f32,
    },
    /// Start the AAP session (the user accepted the "connect?" prompt).
    Connect,
    /// Rebuild the Windows audio route without touching the AAP session: toggle
    /// the AirPods' audio services off and on, which is exactly what pressing
    /// Disconnect then Connect does by hand.
    ///
    /// For the failure where the buds go silent while EVERYTHING reports healthy —
    /// the AAP channel at Ok(2), the Bluetooth device connected, the endpoint OK,
    /// the AirPods still selected — and so nothing can detect it automatically.
    /// One click instead of changing noise mode and restarting the video.
    RebuildAudio,
    /// Force a clean reconnect (the "Repair connection" button): re-assert the
    /// connect request AND drop the current driver so the daemon reopens a fresh
    /// AAP session + ATT channel — used to recover a wedged / desynced link where
    /// the OS still shows the AirPods connected but our session is stale.
    RepairConnection,
    /// Release the AAP control session (the "Disconnect" button). Stops
    /// controlling the AirPods without stealing the audio link from the OS.
    Disconnect,
    /// Rename the AirPods (sends the 0x1A rename command).
    SetName { name: String },
    /// Request a fresh `State` snapshot.
    GetState,
    /// Stop the daemon too (e.g. from the tray's "Quit").
    Shutdown,
}

/// Daemon → client.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "event", rename_all = "snake_case")]
pub enum Event {
    /// Full state, pushed on connect and whenever it changes.
    State(Snapshot),
    /// A notification for the client to render with its overlay UI.
    Overlay { title: String, body: String },
    /// The device is nearby (BLE) but not connected — the client shows a
    /// clickable card; a click sends `Command::Connect`.
    ConnectPrompt { name: String },
}

/// Which UI a client is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ClientKind {
    Tray,
    App,
}

/// Serialize a message as one NDJSON line (trailing `\n`).
pub fn to_line<T: Serialize>(v: &T) -> String {
    let mut s = serde_json::to_string(v).unwrap_or_default();
    s.push('\n');
    s
}

/// Parse one NDJSON line into a message.
pub fn from_line<T: for<'de> Deserialize<'de>>(line: &str) -> Option<T> {
    serde_json::from_str(line.trim()).ok()
}
