//! Local IPC used by later launches (to raise the window) and by the COSMIC panel
//! applet (to show battery levels and change noise control).
//!
//! The protocol is newline-delimited text over the single-instance Unix socket:
//!
//! | client → server               | effect                                          |
//! |-------------------------------|-------------------------------------------------|
//! | `open`                        | show the main window                            |
//! | `subscribe [applet]`          | server sends the current [`Status`] as one JSON |
//! |                               | line, then another line on every change         |
//! | `listening_mode <n>`          | 1 = off, 2 = ANC, 3 = transparency, 4 = adaptive |
//! | `conversation_detect <0\|1>`  | toggle conversation awareness                   |
//! | `cycle_listening_mode`        | ANC → transparency → adaptive (→ off) → ANC     |
//! | `connect [mac]`               | connect saved AirPods (e.g. back from an iPhone) |
//! | `quit`                        | quit LibrePods                                  |

use crate::bluetooth::aacp::{BatteryStatus, ControlCommandIdentifiers};
use crate::ui::messages::BluetoothUIMessage;
use crate::ui::tray::MyTray;
use log::{debug, warn};
use serde::Serialize;
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::sync::mpsc::UnboundedSender;
use tokio::sync::{Mutex, watch};

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct SavedAirPods {
    pub mac: String,
    pub name: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct Battery {
    pub level: u8,
    pub charging: bool,
    /// False for a bud resting in the case, or the case with its lid shut;
    /// `level` is then the last reported value.
    pub connected: bool,
}

/// Snapshot of the connected device, as shown in the tray / applet.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct Status {
    pub connected: bool,
    /// The AirPods' name, e.g. "Satya's AirPods Pro".
    pub name: Option<String>,
    /// All saved AirPods, so the applet can offer to connect a specific pair.
    pub saved: Vec<SavedAirPods>,
    /// Single battery for over-ear headphones (AirPods Max).
    pub headphone: Option<Battery>,
    pub left: Option<Battery>,
    pub right: Option<Battery>,
    pub case: Option<Battery>,
    pub listening_mode: Option<u8>,
    pub allow_off: bool,
    pub conversation_detect: Option<bool>,
}

fn battery(level: Option<u8>, status: Option<BatteryStatus>) -> Option<Battery> {
    level.map(|level| Battery {
        level,
        charging: status == Some(BatteryStatus::Charging),
        connected: status != Some(BatteryStatus::Disconnected),
    })
}

impl Status {
    fn from_tray(t: &MyTray) -> Self {
        Self {
            connected: t.connected,
            name: t.device_name.clone(),
            saved: t
                .saved_airpods
                .iter()
                .map(|(mac, name)| SavedAirPods { mac: mac.clone(), name: name.clone() })
                .collect(),
            headphone: battery(t.battery_headphone, t.battery_headphone_status),
            left: battery(t.battery_l, t.battery_l_status),
            right: battery(t.battery_r, t.battery_r_status),
            case: battery(t.battery_c, t.battery_c_status),
            listening_mode: t.listening_mode,
            allow_off: t.allow_off_option == Some(0x01),
            conversation_detect: t.conversation_detect_enabled,
        }
    }
}

/// Owns the tray state, publishes every change to IPC subscribers, and mirrors it
/// into the StatusNotifier tray icon while that is shown. The icon can be hidden
/// while the COSMIC panel applet is running, so the state lives here rather than
/// inside ksni. Works without a tray (`--no-tray`) too.
#[derive(Clone)]
pub struct TrayHandle {
    state: Arc<Mutex<MyTray>>,
    tray: Arc<Mutex<Option<ksni::Handle<MyTray>>>>,
    /// False with `--no-tray`: never show the icon.
    tray_allowed: bool,
    status: Arc<watch::Sender<Status>>,
    applets: Arc<std::sync::atomic::AtomicUsize>,
}

impl TrayHandle {
    pub fn new(tray: Option<ksni::Handle<MyTray>>, state: MyTray, tray_allowed: bool) -> Self {
        let initial = Status::from_tray(&state);
        Self {
            state: Arc::new(Mutex::new(state)),
            tray: Arc::new(Mutex::new(tray)),
            tray_allowed,
            status: Arc::new(watch::channel(initial).0),
            applets: Arc::new(std::sync::atomic::AtomicUsize::new(0)),
        }
    }

    pub async fn update<R, F>(&self, f: F) -> Option<R>
    where
        R: Send,
        F: FnOnce(&mut MyTray) -> R + Send,
    {
        let mut state = self.state.lock().await;
        let r = f(&mut state);
        self.status.send_if_modified(|s| {
            let new = Status::from_tray(&state);
            let changed = *s != new;
            *s = new;
            changed
        });
        let snapshot = state.clone();
        drop(state);
        if let Some(handle) = self.tray.lock().await.as_ref() {
            handle.update(move |t| *t = snapshot).await;
        }
        Some(r)
    }

    pub fn subscribe(&self) -> watch::Receiver<Status> {
        self.status.subscribe()
    }

    /// Shows or hides the StatusNotifier icon.
    async fn set_tray_visible(&self, visible: bool) {
        use ksni::TrayMethods;
        let mut tray = self.tray.lock().await;
        if visible && tray.is_none() && self.tray_allowed {
            let state = self.state.lock().await.clone();
            match state.spawn().await {
                Ok(handle) => *tray = Some(handle),
                Err(e) => warn!("Could not show tray icon: {}", e),
            }
        } else if !visible && let Some(handle) = tray.take() {
            handle.shutdown().await;
        }
    }

    /// Called when a COSMIC panel applet connects: it replaces the tray icon.
    async fn applet_connected(&self) {
        use std::sync::atomic::Ordering;
        if self.applets.fetch_add(1, Ordering::SeqCst) == 0 {
            log::info!("COSMIC applet connected; hiding the tray icon.");
            self.set_tray_visible(false).await;
        }
    }

    async fn applet_disconnected(&self) {
        use std::sync::atomic::Ordering;
        if self.applets.fetch_sub(1, Ordering::SeqCst) == 1 {
            // The applet reconnects within a few seconds when the panel restarts;
            // don't flash the tray icon in between.
            tokio::time::sleep(std::time::Duration::from_secs(6)).await;
            if self.applets.load(Ordering::SeqCst) == 0 {
                log::info!("COSMIC applet gone; showing the tray icon again.");
                self.set_tray_visible(true).await;
            }
        }
    }

    /// Returns whether the command was handed to a connected device.
    async fn send_command(&self, id: ControlCommandIdentifiers, value: u8) -> bool {
        let tx = self.update(|t| t.command_tx.clone()).await.flatten();
        match tx {
            Some(tx) => tx.send((id, vec![value])).is_ok(),
            None => {
                debug!("IPC command ignored: no device connected");
                false
            }
        }
    }
}

static GLOBAL: std::sync::OnceLock<TrayHandle> = std::sync::OnceLock::new();

/// Makes the shared state reachable from the window (which runs on another thread).
pub fn set_global(handle: TrayHandle) {
    let _ = GLOBAL.set(handle);
}

/// The shared status, once the Bluetooth side has started.
pub fn global_status() -> Option<watch::Receiver<Status>> {
    GLOBAL.get().map(|h| h.subscribe())
}

/// Updates the AirPods name shown in the applet, e.g. after a rename in the window.
pub fn set_device_name(name: String) {
    if let Some(handle) = GLOBAL.get().cloned() {
        std::thread::spawn(move || {
            if let Ok(rt) = tokio::runtime::Runtime::new() {
                rt.block_on(handle.update(move |t| t.device_name = Some(name)));
            }
        });
    }
}

pub async fn serve(
    listener: std::os::unix::net::UnixListener,
    ui_tx: UnboundedSender<BluetoothUIMessage>,
    tray: TrayHandle,
) {
    let listener = match listener
        .set_nonblocking(true)
        .and_then(|_| tokio::net::UnixListener::from_std(listener))
    {
        Ok(l) => l,
        Err(e) => {
            warn!("IPC socket unusable: {}", e);
            return;
        }
    };
    loop {
        let Ok((stream, _)) = listener.accept().await else {
            continue;
        };
        let ui_tx = ui_tx.clone();
        let tray = tray.clone();
        let peer_is_applet = peer_is_cosmic_applet(&stream);
        tokio::spawn(async move {
            let (read, write) = stream.into_split();
            let mut write = Some(write);
            let mut lines = BufReader::new(read).lines();
            let mut writer: Option<tokio::task::JoinHandle<()>> = None;
            let mut is_applet = false;
            while let Ok(Some(line)) = lines.next_line().await {
                let Some(command) = parse_command(&line) else {
                    debug!("Unknown IPC command: {:?}", line);
                    continue;
                };
                match command {
                    Command::Open => {
                        log::info!("Activated via IPC; opening window.");
                        let _ = ui_tx.send(BluetoothUIMessage::OpenWindow);
                    }
                    Command::Subscribe { applet } => {
                        let Some(mut w) = write.take() else { continue };
                        if (applet || peer_is_applet) && !is_applet {
                            is_applet = true;
                            tray.applet_connected().await;
                        }
                        let mut rx = tray.subscribe();
                        writer = Some(tokio::spawn(async move {
                            loop {
                                let json = serde_json::to_string(&*rx.borrow_and_update())
                                    .unwrap_or_default();
                                if w.write_all(format!("{}\n", json).as_bytes()).await.is_err()
                                    || rx.changed().await.is_err()
                                {
                                    break;
                                }
                            }
                        }));
                    }
                    Command::Quit => {
                        log::info!("Quit requested via IPC.");
                        std::process::exit(0);
                    }
                    Command::Connect(addr) => {
                        match addr.or_else(crate::bluetooth::discovery::saved_airpods_address) {
                            Some(addr) => {
                                tokio::spawn(async move {
                                    if let Err(e) = crate::bluetooth::discovery::connect_device(addr).await {
                                        warn!("Connect requested via IPC failed: {}", e);
                                    }
                                });
                            }
                            None => warn!("Connect requested, but no AirPods are saved yet"),
                        }
                    }
                    Command::CycleListeningMode => {
                        let current = tray.update(|t| (t.listening_mode, t.allow_off_option)).await;
                        if let Some((current, allow_off)) = current {
                            let next = next_listening_mode(current, allow_off == Some(0x01));
                            let _ = tray
                                .send_command(ControlCommandIdentifiers::ListeningMode, next)
                                .await;
                        }
                    }
                    Command::ListeningMode(v) => {
                        let _ = tray.send_command(ControlCommandIdentifiers::ListeningMode, v).await;
                    }
                    Command::ConversationDetect(enabled) => {
                        let sent = tray
                            .send_command(
                                ControlCommandIdentifiers::ConversationDetectConfig,
                                if enabled { 0x01 } else { 0x02 },
                            )
                            .await;
                        if sent {
                            tray.update(move |t| t.conversation_detect_enabled = Some(enabled))
                                .await;
                        }
                    }
                }
            }
            if let Some(w) = writer {
                w.abort();
            }
            if is_applet {
                tray.applet_disconnected().await;
            }
        });
    }
}

/// Identifies the COSMIC panel applet by the connecting process, so the tray icon
/// is hidden even for applet builds that don't announce themselves.
fn peer_is_cosmic_applet(stream: &tokio::net::UnixStream) -> bool {
    let Some(pid) = stream.peer_cred().ok().and_then(|c| c.pid()) else {
        return false;
    };
    // `comm` is truncated to 15 characters: "librepods-cosmic-applet" -> "librepods-cosmi".
    std::fs::read_to_string(format!("/proc/{}/comm", pid))
        .map(|comm| comm.trim_end().starts_with("librepods-cosmi"))
        .unwrap_or(false)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Command {
    Open,
    Subscribe { applet: bool },
    Quit,
    /// Connect the given AirPods, or the first saved ones.
    Connect(Option<bluer::Address>),
    CycleListeningMode,
    /// 1 = off, 2 = noise cancellation, 3 = transparency, 4 = adaptive.
    ListeningMode(u8),
    ConversationDetect(bool),
}

pub(crate) fn parse_command(line: &str) -> Option<Command> {
    let mut parts = line.split_whitespace();
    let name = parts.next()?;
    let arg = parts.next();
    let number = || arg.and_then(|v| v.parse::<u8>().ok());
    Some(match name {
        "open" => Command::Open,
        "subscribe" => Command::Subscribe { applet: arg == Some("applet") },
        "quit" => Command::Quit,
        "connect" => match arg {
            None => Command::Connect(None),
            Some(mac) => Command::Connect(Some(mac.parse().ok()?)),
        },
        "cycle_listening_mode" => Command::CycleListeningMode,
        "listening_mode" => Command::ListeningMode(number().filter(|v| (1..=4).contains(v))?),
        "conversation_detect" => Command::ConversationDetect(number()? != 0),
        _ => return None,
    })
}

/// Next mode for the "cycle" shortcut: ANC → transparency → adaptive (→ off).
pub(crate) fn next_listening_mode(current: Option<u8>, allow_off: bool) -> u8 {
    let mut order = vec![0x02u8, 0x03, 0x04];
    if allow_off {
        order.push(0x01);
    }
    current
        .and_then(|c| order.iter().position(|&m| m == c))
        .map(|i| order[(i + 1) % order.len()])
        .unwrap_or(order[0])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_commands() {
        assert_eq!(parse_command("open"), Some(Command::Open));
        assert_eq!(parse_command("subscribe"), Some(Command::Subscribe { applet: false }));
        assert_eq!(parse_command("subscribe applet"), Some(Command::Subscribe { applet: true }));
        assert_eq!(parse_command("quit"), Some(Command::Quit));
        assert_eq!(parse_command("connect"), Some(Command::Connect(None)));
        assert_eq!(
            parse_command("connect 70:AE:D5:D4:8B:37"),
            Some(Command::Connect(Some("70:AE:D5:D4:8B:37".parse().unwrap())))
        );
        assert_eq!(parse_command("listening_mode 2"), Some(Command::ListeningMode(2)));
        assert_eq!(parse_command("conversation_detect 0"), Some(Command::ConversationDetect(false)));
        assert_eq!(parse_command("conversation_detect 1"), Some(Command::ConversationDetect(true)));
        assert_eq!(parse_command("cycle_listening_mode"), Some(Command::CycleListeningMode));
    }

    #[test]
    fn rejects_bad_commands() {
        assert_eq!(parse_command(""), None);
        assert_eq!(parse_command("bogus"), None);
        assert_eq!(parse_command("listening_mode 9"), None);
        assert_eq!(parse_command("listening_mode"), None);
        assert_eq!(parse_command("connect not-a-mac"), None);
    }

    #[test]
    fn cycles_listening_modes() {
        assert_eq!(next_listening_mode(Some(0x02), false), 0x03);
        assert_eq!(next_listening_mode(Some(0x03), false), 0x04);
        assert_eq!(next_listening_mode(Some(0x04), false), 0x02);
        assert_eq!(next_listening_mode(Some(0x04), true), 0x01);
        assert_eq!(next_listening_mode(Some(0x01), true), 0x02);
        // Off while Off isn't allowed, or unknown: start at noise cancellation.
        assert_eq!(next_listening_mode(Some(0x01), false), 0x02);
        assert_eq!(next_listening_mode(None, false), 0x02);
    }

    #[test]
    fn status_marks_bud_in_case_as_not_connected() {
        let tray = MyTray {
            device_name: Some("Pods".into()),
            saved_airpods: vec![("AA:BB:CC:DD:EE:FF".into(), "Pods".into())],
            conversation_detect_enabled: Some(true),
            battery_headphone: None,
            battery_headphone_status: None,
            battery_l: Some(90),
            battery_l_status: Some(BatteryStatus::Charging),
            battery_r: Some(87),
            battery_r_status: Some(BatteryStatus::Disconnected),
            battery_c: None,
            battery_c_status: None,
            connected: true,
            listening_mode: Some(0x03),
            allow_off_option: Some(0x01),
            command_tx: None,
            ui_tx: None,
        };
        let s = Status::from_tray(&tray);
        assert_eq!(s.name.as_deref(), Some("Pods"));
        assert_eq!(s.left, Some(Battery { level: 90, charging: true, connected: true }));
        assert_eq!(s.right, Some(Battery { level: 87, charging: false, connected: false }));
        assert_eq!(s.case, None);
        assert!(s.allow_off);
    }
}

#[cfg(test)]
mod contract_tests {
    use super::*;

    /// The JSON shape the COSMIC applet parses (see cosmic-applet/src/ipc.rs).
    #[test]
    fn status_json_matches_applet_contract() {
        let status = Status {
            connected: true,
            name: Some("Pods".into()),
            saved: vec![SavedAirPods { mac: "AA:BB:CC:DD:EE:FF".into(), name: "Pods".into() }],
            left: Some(Battery { level: 90, charging: true, connected: true }),
            listening_mode: Some(3),
            conversation_detect: Some(false),
            ..Default::default()
        };
        let v: serde_json::Value = serde_json::to_value(&status).unwrap();
        for key in ["connected", "name", "saved", "headphone", "left", "right", "case", "listening_mode", "allow_off", "conversation_detect"] {
            assert!(v.get(key).is_some(), "missing key {key}");
        }
        assert_eq!(v["left"]["level"], 90);
        assert_eq!(v["left"]["charging"], true);
        assert_eq!(v["left"]["connected"], true);
        assert_eq!(v["saved"][0]["mac"], "AA:BB:CC:DD:EE:FF");
        assert!(v["case"].is_null());
    }
}
