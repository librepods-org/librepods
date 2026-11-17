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

use crate::bluetooth::aacp::{BatteryStatus, ControlCommandIdentifiers};
use crate::ui::messages::BluetoothUIMessage;
use crate::ui::tray::MyTray;
use log::{debug, warn};
use serde::Serialize;
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::sync::mpsc::UnboundedSender;
use tokio::sync::{Mutex, watch};

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
        Self {
            state: Arc::new(Mutex::new(state)),
            tray: Arc::new(Mutex::new(tray)),
            tray_allowed,
            status: Arc::new(watch::channel(Status::default()).0),
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
                let mut parts = line.split_whitespace();
                match (parts.next(), parts.next().and_then(|v| v.parse::<u8>().ok())) {
                    (Some("open"), _) => {
                        log::info!("Activated via IPC; opening window.");
                        let _ = ui_tx.send(BluetoothUIMessage::OpenWindow);
                    }
                    (Some("subscribe"), _) if write.is_some() => {
                        let says_applet = line.split_whitespace().nth(1) == Some("applet");
                        if (says_applet || peer_is_applet) && !is_applet {
                            is_applet = true;
                            tray.applet_connected().await;
                        }
                        let mut rx = tray.subscribe();
                        let mut w = write.take().unwrap();
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
                    (Some("cycle_listening_mode"), _) => {
                        let current = tray.update(|t| (t.listening_mode, t.allow_off_option)).await;
                        if let Some((current, allow_off)) = current {
                            let mut order = vec![0x02u8, 0x03, 0x04];
                            if allow_off == Some(0x01) {
                                order.push(0x01);
                            }
                            let next = current
                                .and_then(|c| order.iter().position(|&m| m == c))
                                .map(|i| order[(i + 1) % order.len()])
                                .unwrap_or(order[0]);
                            let _ = tray
                                .send_command(ControlCommandIdentifiers::ListeningMode, next)
                                .await;
                        }
                    }
                    (Some("listening_mode"), Some(v)) if (1..=4).contains(&v) => {
                        let _ = tray.send_command(ControlCommandIdentifiers::ListeningMode, v).await;
                    }
                    (Some("conversation_detect"), Some(v)) => {
                        let enabled = v != 0;
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
                    _ => debug!("Unknown IPC command: {:?}", line),
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
