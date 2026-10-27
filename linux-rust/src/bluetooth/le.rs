//! Watches the AirPods' BLE adverts for battery, lid and connection state. Uses BlueZ's
//! advertisement monitor when available; otherwise falls back to plain LE discovery, but only
//! while no AirPods hold an AACP connection, so scanning never competes with audio, and only
//! when some AirPods have auto-connect on, so it doesn't hold up keyboards and mice.

use crate::bluetooth::aacp::BatteryStatus;
use crate::bluetooth::proximity::{
    AccessoryState, BudsAdvert, CaseAdvert, Level, parse_buds_advert, parse_case_advert,
    resolve_rpa,
};
use crate::devices::enums::{DeviceData, DeviceInformation, DeviceType};
use crate::devices::models::AirPodsModel;
use crate::prefs::parse_prefs;
use crate::ui::tray::MyTray;
use crate::utils::{get_devices_path, get_preferences_path};
use bluer::monitor::{Monitor, MonitorEvent, Pattern};
use bluer::{Adapter, AdapterEvent, Address, DiscoveryFilter, DiscoveryTransport, Session};
use futures::StreamExt;
use log::{debug, info, warn};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::{Mutex, watch};

const APPLE_COMPANY_ID: u16 = 0x004C;
const AUTO_CONNECT_COOLDOWN: Duration = Duration::from_secs(60);
const AUTO_CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
const FOREIGN_ADDRESS_TTL: Duration = Duration::from_secs(600);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScanMode {
    /// BlueZ advertisement monitor: passive, harmless while audio plays.
    Monitor,
    /// Active LE discovery: only while no AirPods are connected over AACP and some have
    /// auto-connect on.
    Discovery,
}

/// Which AirPods currently have a Bluetooth connection (from BlueZ `Device1.Connected`).
#[derive(Default)]
pub struct ConnectedAirPods(HashSet<String>);

impl ConnectedAirPods {
    /// Records one pair's state and returns whether any AirPods are still connected.
    pub fn set(&mut self, mac: &str, connected: bool) -> bool {
        if connected {
            self.0.insert(mac.to_string());
        } else {
            self.0.remove(mac);
        }
        !self.0.is_empty()
    }
}

/// Discovery runs in short bursts so it doesn't hog the radio the keyboard, mouse and other
/// Bluetooth devices share.
#[derive(Debug, Clone, Copy)]
pub struct ScanSchedule {
    pub burst: Duration,
    pub pause: Duration,
}

impl ScanSchedule {
    pub const DEFAULT: ScanSchedule = ScanSchedule {
        burst: Duration::from_secs(12),
        pause: Duration::from_secs(48),
    };

    fn position(&self, elapsed: Duration) -> Duration {
        let cycle = (self.burst + self.pause).as_millis().max(1);
        Duration::from_millis((elapsed.as_millis() % cycle) as u64)
    }

    pub fn is_scanning(&self, elapsed: Duration) -> bool {
        self.position(elapsed) < self.burst
    }

    /// Time left in the current phase (burst or pause).
    pub fn remaining(&self, elapsed: Duration) -> Duration {
        let position = self.position(elapsed);
        if position < self.burst {
            self.burst - position
        } else {
            self.burst + self.pause - position
        }
    }
}

pub fn should_scan(mode: ScanMode, aacp_connected: bool) -> bool {
    match mode {
        ScanMode::Monitor => true,
        ScanMode::Discovery => !aacp_connected,
    }
}

/// At most one auto-connect attempt per AirPods per cooldown.
pub struct AutoConnectGate {
    cooldown: Duration,
    last_attempt: HashMap<String, Instant>,
}

impl AutoConnectGate {
    pub fn new(cooldown: Duration) -> Self {
        AutoConnectGate {
            cooldown,
            last_attempt: HashMap::new(),
        }
    }

    pub fn should_attempt(&mut self, mac: &str, enabled: bool, now: Instant) -> bool {
        if !enabled {
            return false;
        }
        match self.last_attempt.get(mac) {
            Some(last) if now.duration_since(*last) < self.cooldown => false,
            _ => {
                self.last_attempt.insert(mac.to_string(), now);
                true
            }
        }
    }
}

/// Auto-connect is opt-in per MAC in preferences.json (`{"<mac>": {"autoConnect": true}}`).
pub fn auto_connect_enabled(prefs_json: &str, mac: &str) -> bool {
    parse_prefs(prefs_json)
        .get(mac)
        .is_some_and(|prefs| prefs.auto_connect)
}

/// While discovery runs, BLE keyboards and mice can't reconnect (seen on an Intel AX200), so
/// bursts only run when some AirPods have auto-connect on.
pub fn burst_due(schedule: &ScanSchedule, elapsed: Duration, prefs_json: &str) -> bool {
    schedule.is_scanning(elapsed) && parse_prefs(prefs_json).values().any(|p| p.auto_connect)
}

struct KnownAirPods {
    mac: String,
    irk: [u8; 16],
    enc_key: Option<[u8; 16]>,
}

fn decode_key(hex_key: &str) -> Option<[u8; 16]> {
    hex::decode(hex_key).ok()?.try_into().ok()
}

fn load_known_airpods() -> Vec<KnownAirPods> {
    let devices: HashMap<String, DeviceData> = std::fs::read_to_string(get_devices_path())
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default();
    devices
        .into_iter()
        .filter(|(_, data)| data.type_ == DeviceType::AirPods)
        .filter_map(|(mac, data)| match data.information {
            Some(DeviceInformation::AirPods(info)) => Some(KnownAirPods {
                mac,
                irk: decode_key(&info.le_keys.irk)?,
                enc_key: decode_key(&info.le_keys.enc_key),
            }),
            _ => None,
        })
        .collect()
}

fn describe(level: Option<Level>) -> String {
    match level {
        Some(l) if l.charging => format!("{}% (charging)", l.percent),
        Some(l) => format!("{}%", l.percent),
        None => "-".to_string(),
    }
}

/// Shared state for advert handling across both scan modes.
struct AdvertHandler {
    tray_handle: Option<ksni::Handle<MyTray>>,
    aacp_connected: watch::Receiver<bool>,
    known: Vec<KnownAirPods>,
    verified: HashMap<Address, usize>,
    foreign: HashMap<Address, Instant>,
    last_buds: HashMap<String, BudsAdvert>,
    last_case: Option<CaseAdvert>,
    gate: AutoConnectGate,
}

impl AdvertHandler {
    fn new(
        tray_handle: Option<ksni::Handle<MyTray>>,
        aacp_connected: watch::Receiver<bool>,
    ) -> Self {
        AdvertHandler {
            tray_handle,
            aacp_connected,
            known: load_known_airpods(),
            verified: HashMap::new(),
            foreign: HashMap::new(),
            last_buds: HashMap::new(),
            last_case: None,
            gate: AutoConnectGate::new(AUTO_CONNECT_COOLDOWN),
        }
    }

    /// Re-reads keys learned on recent connections and starts a fresh scan session.
    fn reload_keys(&mut self) {
        self.known = load_known_airpods();
        self.reset_session();
        debug!("Loaded LE keys for {} AirPods", self.known.len());
    }

    /// Forgets address decisions and the last adverts, so the first advert of a new session
    /// always reaches the tray (it may have been cleared on disconnect).
    fn reset_session(&mut self) {
        self.verified.clear();
        self.foreign.clear();
        self.last_buds.clear();
        self.last_case = None;
    }

    fn resolve(&mut self, address: Address, now: Instant) -> Option<usize> {
        if let Some(index) = self.verified.get(&address) {
            return Some(*index);
        }
        if let Some(seen) = self.foreign.get(&address)
            && now.duration_since(*seen) < FOREIGN_ADDRESS_TTL
        {
            return None;
        }
        let address_str = address.to_string();
        match self
            .known
            .iter()
            .position(|k| resolve_rpa(&address_str, &k.irk))
        {
            Some(index) => {
                info!("BLE address {} belongs to AirPods {}", address, self.known[index].mac);
                self.verified.insert(address, index);
                Some(index)
            }
            None => {
                self.foreign.insert(address, now);
                None
            }
        }
    }

    async fn handle(&mut self, address: Address, apple_data: &[u8]) {
        let now = Instant::now();
        let aacp_connected = *self.aacp_connected.borrow();

        if let Some(index) = self.resolve(address, now)
            && let Some(enc_key) = self.known[index].enc_key
            && let Some(advert) = parse_buds_advert(apple_data, &enc_key)
        {
            let mac = self.known[index].mac.clone();
            if self.last_buds.get(&mac) != Some(&advert) {
                info!(
                    "BLE status for {} ({}): left {}, right {}, case {}, in ear L:{} R:{}, lid {}, state {:?}",
                    mac,
                    AirPodsModel::from_ble_model_id(advert.model_id).display_name(),
                    describe(advert.left),
                    describe(advert.right),
                    describe(advert.case),
                    advert.left_in_ear,
                    advert.right_in_ear,
                    if advert.lid_open { "open" } else { "closed" },
                    advert.state
                );
                if !aacp_connected {
                    self.update_tray(advert.left, advert.right, advert.case).await;
                }
                self.last_buds.insert(mac.clone(), advert.clone());
            }
            if advert.state == AccessoryState::Disconnected && !aacp_connected {
                let prefs = std::fs::read_to_string(get_preferences_path()).unwrap_or_default();
                if self
                    .gate
                    .should_attempt(&mac, auto_connect_enabled(&prefs, &mac), now)
                {
                    tokio::spawn(auto_connect(mac));
                }
            }
            return;
        }

        let keys: Vec<[u8; 16]> = self.known.iter().filter_map(|k| k.enc_key).collect();
        if let Some(advert) = parse_case_advert(apple_data, &keys)
            && self.last_case.as_ref() != Some(&advert)
        {
            info!(
                "BLE case status: case {}, left {}, right {}",
                describe(advert.case),
                describe(advert.left),
                describe(advert.right)
            );
            if !aacp_connected {
                self.update_tray(advert.left, advert.right, advert.case).await;
            }
            self.last_case = Some(advert);
        }
    }

    async fn update_tray(&self, left: Option<Level>, right: Option<Level>, case: Option<Level>) {
        let Some(handle) = &self.tray_handle else {
            return;
        };
        let status = |level: Option<Level>| {
            Some(match level {
                None => BatteryStatus::Disconnected,
                Some(l) if l.charging => BatteryStatus::Charging,
                Some(_) => BatteryStatus::NotCharging,
            })
        };
        handle
            .update(|tray: &mut MyTray| {
                tray.battery_l = left.map(|l| l.percent);
                tray.battery_l_status = status(left);
                tray.battery_r = right.map(|l| l.percent);
                tray.battery_r_status = status(right);
                tray.battery_c = case.map(|l| l.percent);
                tray.battery_c_status = status(case);
            })
            .await;
    }
}

async fn auto_connect(mac: String) {
    info!("AirPods {} are advertising as disconnected, connecting", mac);
    let attempt = tokio::time::timeout(
        AUTO_CONNECT_TIMEOUT,
        tokio::process::Command::new("bluetoothctl")
            .arg("connect")
            .arg(&mac)
            .output(),
    )
    .await;
    match attempt {
        Ok(Ok(output)) if output.status.success() => info!("Auto-connected to AirPods {}", mac),
        Ok(Ok(output)) => warn!(
            "Auto-connect to {} failed: {}",
            mac,
            String::from_utf8_lossy(&output.stderr).trim()
        ),
        Ok(Err(e)) => warn!("Could not run bluetoothctl for {}: {}", mac, e),
        Err(_) => warn!("Auto-connect to {} timed out", mac),
    }
}

fn apple_data(data: &HashMap<u16, Vec<u8>>) -> Option<&[u8]> {
    data.get(&APPLE_COMPANY_ID).map(Vec::as_slice)
}

pub async fn start_le_monitor(
    tray_handle: Option<ksni::Handle<MyTray>>,
    aacp_connected: watch::Receiver<bool>,
) -> bluer::Result<()> {
    let session = Session::new().await?;
    let adapter = session.default_adapter().await?;
    adapter.set_powered(true).await?;

    let handler = Arc::new(Mutex::new(AdvertHandler::new(
        tray_handle,
        aacp_connected.clone(),
    )));

    match run_monitor(&adapter, handler.clone()).await {
        Ok(()) => Ok(()),
        Err(e) => {
            info!(
                "BlueZ advertisement monitor unavailable ({}); using LE discovery while AirPods are disconnected",
                e
            );
            run_discovery(&adapter, handler, aacp_connected).await
        }
    }
}

async fn run_monitor(adapter: &Adapter, handler: Arc<Mutex<AdvertHandler>>) -> bluer::Result<()> {
    let pattern = Pattern {
        data_type: 0xFF, // Manufacturer specific data
        start_position: 0,
        content: vec![0x4C, 0x00], // Apple manufacturer ID (76) in LE
    };
    let mm = adapter.monitor().await?;
    let mut monitor_handle = mm
        .register(Monitor {
            monitor_type: bluer::monitor::Type::OrPatterns,
            patterns: Some(vec![pattern]),
            ..Default::default()
        })
        .await?;
    info!("BLE scanning in {:?} mode", ScanMode::Monitor);

    while let Some(event) = monitor_handle.next().await {
        let MonitorEvent::DeviceFound(device_id) = event else {
            continue;
        };
        let Ok(device) = adapter.device(device_id.device) else {
            continue;
        };
        let address = device.address();
        let Ok(mut events) = device.events().await else {
            continue;
        };
        let handler = handler.clone();
        tokio::spawn(async move {
            while let Some(bluer::DeviceEvent::PropertyChanged(property)) = events.next().await {
                if let bluer::DeviceProperty::ManufacturerData(data) = property
                    && let Some(apple) = apple_data(&data)
                {
                    handler.lock().await.handle(address, apple).await;
                }
            }
        });
    }
    Ok(())
}

async fn run_discovery(
    adapter: &Adapter,
    handler: Arc<Mutex<AdvertHandler>>,
    mut aacp_connected: watch::Receiver<bool>,
) -> bluer::Result<()> {
    let schedule = ScanSchedule::DEFAULT;
    let filter = DiscoveryFilter {
        transport: DiscoveryTransport::Le,
        duplicate_data: false,
        ..Default::default()
    };
    loop {
        while !should_scan(ScanMode::Discovery, *aacp_connected.borrow()) {
            if aacp_connected.changed().await.is_err() {
                return Ok(());
            }
        }
        info!(
            "BLE scanning in {:?} mode ({} s bursts every {} s, AirPods not connected, only while auto-connect is on)",
            ScanMode::Discovery,
            schedule.burst.as_secs(),
            (schedule.burst + schedule.pause).as_secs()
        );
        let started = Instant::now();

        'allowed: loop {
            let elapsed = started.elapsed();
            let phase_end = tokio::time::sleep(schedule.remaining(elapsed));
            tokio::pin!(phase_end);

            let prefs = std::fs::read_to_string(get_preferences_path()).unwrap_or_default();
            if burst_due(&schedule, elapsed, &prefs) {
                handler.lock().await.reload_keys();
                if let Err(e) = adapter.set_discovery_filter(filter.clone()).await {
                    debug!("Could not set LE discovery filter: {}", e);
                }
                let mut events = match adapter.discover_devices_with_changes().await {
                    Ok(events) => events,
                    Err(e) => {
                        warn!("Could not start LE discovery: {}", e);
                        tokio::select! {
                            _ = &mut phase_end => continue 'allowed,
                            changed = aacp_connected.changed() => {
                                if changed.is_err() || !should_scan(ScanMode::Discovery, *aacp_connected.borrow()) {
                                    break 'allowed;
                                }
                                continue 'allowed;
                            }
                        }
                    }
                };
                debug!("BLE scan burst started");
                loop {
                    tokio::select! {
                        _ = &mut phase_end => break,
                        changed = aacp_connected.changed() => {
                            if changed.is_err() || !should_scan(ScanMode::Discovery, *aacp_connected.borrow()) {
                                break 'allowed;
                            }
                        }
                        event = events.next() => {
                            let Some(event) = event else { break };
                            if let AdapterEvent::DeviceAdded(address) = event
                                && let Ok(device) = adapter.device(address)
                                && let Ok(Some(data)) = device.manufacturer_data().await
                                && let Some(apple) = apple_data(&data)
                            {
                                handler.lock().await.handle(address, apple).await;
                            }
                        }
                    }
                }
                debug!("BLE scan burst ended");
            } else {
                tokio::select! {
                    _ = &mut phase_end => {}
                    changed = aacp_connected.changed() => {
                        if changed.is_err() || !should_scan(ScanMode::Discovery, *aacp_connected.borrow()) {
                            break 'allowed;
                        }
                    }
                }
            }
        }
        info!("LE discovery stopped");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    const MAC: &str = "AA:BB:CC:DD:EE:01";

    #[test]
    fn any_airpods_connected_tracks_each_pair() {
        let mut connected = ConnectedAirPods::default();
        assert!(connected.set("AA:AA:AA:AA:AA:01", true));
        assert!(connected.set("AA:AA:AA:AA:AA:02", true));
        assert!(connected.set("AA:AA:AA:AA:AA:01", false)); // the other pair is still connected
        assert!(!connected.set("AA:AA:AA:AA:AA:02", false));
    }

    #[test]
    fn disconnect_of_unknown_device_changes_nothing() {
        let mut connected = ConnectedAirPods::default();
        connected.set(MAC, true);
        assert!(connected.set("11:22:33:44:55:66", false));
    }

    #[test]
    fn scan_schedule_bursts() {
        let schedule = ScanSchedule {
            burst: Duration::from_secs(12),
            pause: Duration::from_secs(48),
        };
        assert!(schedule.is_scanning(Duration::ZERO));
        assert!(schedule.is_scanning(Duration::from_secs(11)));
        assert!(!schedule.is_scanning(Duration::from_secs(12)));
        assert!(!schedule.is_scanning(Duration::from_secs(59)));
        assert!(schedule.is_scanning(Duration::from_secs(60)));
        assert_eq!(schedule.remaining(Duration::from_secs(5)), Duration::from_secs(7));
        assert_eq!(schedule.remaining(Duration::from_secs(20)), Duration::from_secs(40));
    }

    #[test]
    fn bursts_only_run_when_some_airpods_auto_connect() {
        let schedule = ScanSchedule::DEFAULT;
        // Discovery stops BLE keyboards and mice from reconnecting, so no auto-connect means no bursts.
        assert!(!burst_due(&schedule, Duration::ZERO, ""));
        assert!(!burst_due(&schedule, Duration::ZERO, "{}"));
        let off = format!(r#"{{"{MAC}":{{"autoConnect":false,"earDetection":true}}}}"#);
        assert!(!burst_due(&schedule, Duration::ZERO, &off));
        let on = format!(r#"{{"11:22:33:44:55:66":{{}},"{MAC}":{{"autoConnect":true}}}}"#);
        assert!(burst_due(&schedule, Duration::ZERO, &on));
        assert!(!burst_due(&schedule, Duration::from_secs(30), &on)); // still pauses between bursts
    }

    #[test]
    fn new_scan_session_forgets_last_adverts() {
        use crate::bluetooth::proximity::{AccessoryState, BudsAdvert, CaseAdvert, Level};
        let level = Some(Level { percent: 50, charging: false });
        let (_tx, rx) = watch::channel(false);
        let mut handler = AdvertHandler {
            tray_handle: None,
            aacp_connected: rx,
            known: vec![],
            verified: HashMap::new(),
            foreign: HashMap::new(),
            last_buds: HashMap::from([(
                MAC.to_string(),
                BudsAdvert {
                    model_id: 0x0E20,
                    primary_left: true,
                    left_in_ear: false,
                    right_in_ear: false,
                    lid_open: true,
                    color: 0,
                    state: AccessoryState::Idle,
                    left: level,
                    right: level,
                    case: level,
                },
            )]),
            last_case: Some(CaseAdvert { case: level, left: level, right: level }),
            gate: AutoConnectGate::new(Duration::from_secs(60)),
        };
        handler.reset_session();
        assert!(handler.last_buds.is_empty());
        assert!(handler.last_case.is_none());
    }

    #[test]
    fn scan_policy_never_scans_while_connected() {
        assert!(!should_scan(ScanMode::Discovery, true));
        assert!(should_scan(ScanMode::Discovery, false));
        assert!(should_scan(ScanMode::Monitor, true));
    }

    #[test]
    fn auto_connect_respects_cooldown() {
        let mut gate = AutoConnectGate::new(Duration::from_secs(60));
        let t0 = Instant::now();
        assert!(gate.should_attempt(MAC, true, t0));
        assert!(!gate.should_attempt(MAC, true, t0 + Duration::from_secs(10)));
        assert!(gate.should_attempt(MAC, true, t0 + Duration::from_secs(61)));
        assert!(gate.should_attempt("11:22:33:44:55:66", true, t0 + Duration::from_secs(10)));
    }

    #[test]
    fn auto_connect_disabled_never_attempts() {
        let mut gate = AutoConnectGate::new(Duration::from_secs(60));
        assert!(!gate.should_attempt(MAC, false, Instant::now()));
    }

    #[test]
    fn auto_connect_off_without_pref() {
        assert!(!auto_connect_enabled("{}", MAC));
        assert!(!auto_connect_enabled("not json", MAC));
        let enabled = format!(r#"{{"{MAC}":{{"autoConnect":true}}}}"#);
        assert!(auto_connect_enabled(&enabled, MAC));
        // Stage 3 adds non-boolean and other keys next to autoConnect; they must not hide it.
        let mixed = format!(r#"{{"{MAC}":{{"autoConnect":true,"earDetection":false,"conversationVolume":40}}}}"#);
        assert!(auto_connect_enabled(&mixed, MAC));
    }
}
