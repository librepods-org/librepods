mod audio;
mod bluetooth;
mod control;
mod devices;
mod media_controller;
mod prefs;
mod ui;
mod utils;

use crate::bluetooth::battery_provider::BatteryReporter;
use crate::bluetooth::discovery::{find_connected_airpods, find_other_managed_devices};
use crate::bluetooth::le::{ConnectedAirPods, start_le_monitor};
use crate::bluetooth::managers::DeviceManagers;
use crate::devices::enums::DeviceData;
use crate::devices::registry::DeviceRegistry;
use crate::prefs::{PrefsStore, load_app_settings, migrate_legacy_stem_control, save_app_settings};
use crate::ui::messages::BluetoothUIMessage;
use crate::ui::notifications::Notifier;
use crate::ui::tray::MyTray;
use crate::utils::{get_app_settings_path, get_devices_path};
use bluer::{Address, InternalErrorKind};
use clap::Parser;
use dbus::arg::{RefArg, Variant};
use dbus::blocking::Connection;
use dbus::blocking::stdintf::org_freedesktop_dbus::Properties;
use dbus::message::MatchRule;
use devices::airpods::{AirPodsDevice, DeviceContext};
use ksni::TrayMethods;
use log::{debug, info, warn};
use std::collections::HashMap;
use std::env;
use std::sync::atomic::{AtomicBool};
use std::sync::Arc;
use tokio::sync::RwLock;
use tokio::sync::mpsc::unbounded_channel;

#[derive(Parser)]
struct Args {
    #[arg(long, short = 'd', help = "Enable debug logging")]
    debug: bool,
    #[arg(
        long,
        help = "Disable system tray, useful if your environment doesn't support AppIndicator or StatusNotifier"
    )]
    no_tray: bool,
    #[arg(long, help = "Start the application minimized to tray")]
    start_minimized: bool,
    #[arg(
        long,
        help = "Enable Bluetooth LE debug logging. Only use when absolutely necessary; this produces a lot of logs."
    )]
    le_debug: bool,
    #[arg(long, short = 'v', help = "Show application version and exit")]
    version: bool,
    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(clap::Subcommand)]
enum Command {
    /// Control the running LibrePods from a terminal.
    Ctl(control::CtlArgs),
}

fn main() -> iced::Result {
    let mut args = Args::parse();

    if args.version {
        println!(
            "You are running LibrePods version {}",
            env!("CARGO_PKG_VERSION")
        );
        return Ok(());
    }

    // `librepods ctl …` only talks to the running app; it never starts one.
    if let Some(Command::Ctl(ctl)) = args.command.take() {
        std::process::exit(control::run_ctl(ctl));
    }

    let log_level = if args.debug { "debug" } else { "info" };
    // let wayland_display = env::var("WAYLAND_DISPLAY").is_ok();
    // if wayland_display && env::var("WGPU_BACKEND").is_err() {
    //     unsafe { env::set_var("WGPU_BACKEND", "gl") };
    // }
    if env::var("RUST_LOG").is_err() {
        unsafe {
            env::set_var(
                "RUST_LOG",
                log_level.to_owned()
                    + &format!(
                        ",zbus=warn,winit=warn,tracing=warn,iced_wgpu=warn,wgpu_hal=warn,wgpu_core=warn,cosmic_text=warn,naga=warn,iced_winit=warn,librepods::bluetooth::le={}",
                        if args.le_debug { "debug" } else { "info" }
                    ),
            )
        };
    }
    env_logger::init();

    // A second launch (say, from the taskbar) shows the running LibrePods instead of starting
    // another instance that would compete for the same AirPods.
    if control::hand_over_to_running_instance(!args.start_minimized) {
        return Ok(());
    }
    migrate_app_settings();

    let (ui_tx, ui_rx) = unbounded_channel::<BluetoothUIMessage>();

    let device_managers: Arc<RwLock<HashMap<String, DeviceManagers>>> =
        Arc::new(RwLock::new(HashMap::new()));

    // Load stem_control initial value from settings JSON, then apply CLI override.
    if args.no_tray {
        // Run headless without UI
        info!("Running in headless mode (no GUI)");
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async_main(ui_tx, device_managers)).unwrap();
        Ok(())
    } else {
        // Run with UI
        let device_managers_clone = device_managers.clone();
        std::thread::spawn(|| {
            let rt = tokio::runtime::Runtime::new().unwrap();
            rt.block_on(async_main(ui_tx, device_managers_clone))
                .unwrap();
        });

        ui::window::start_ui(
            ui_rx,
            args.start_minimized,
            device_managers,
            PrefsStore::at_default_path(),
        )
    }
}

async fn async_main(
    ui_tx: tokio::sync::mpsc::UnboundedSender<BluetoothUIMessage>,
    device_managers: Arc<RwLock<HashMap<String, DeviceManagers>>>,
) -> bluer::Result<()> {
    let args = Args::parse();

    let mut managed_devices_mac: Vec<String> = Vec::new(); // includes ony non-AirPods. AirPods handled separately.

    let devices_path = get_devices_path();
    let devices_json = std::fs::read_to_string(&devices_path).unwrap_or_else(|e| {
        log::error!("Failed to read devices file: {}", e);
        "{}".to_string()
    });
    let devices_list: HashMap<String, DeviceData> = serde_json::from_str(&devices_json)
        .unwrap_or_else(|e| {
            log::error!("Deserialization failed: {}", e);
            HashMap::new()
        });
    for (mac, device_data) in devices_list.iter() {
        if device_data.type_ == devices::enums::DeviceType::Nothing {
            managed_devices_mac.push(mac.clone());
        }
    }

    let tray_handle = if args.no_tray {
        None
    } else {
        let tray = MyTray {
            conversation_detect_enabled: None,
            battery_headphone: None,
            battery_headphone_status: None,
            battery_l: None,
            battery_l_status: None,
            battery_r: None,
            battery_r_status: None,
            battery_c: None,
            battery_c_status: None,
            connected: false,
            listening_mode: None,
            allow_off_option: None,
            model: None,
            command_tx: None,
            ui_tx: Some(ui_tx.clone()),
        };
        let handle = tray.spawn().await.unwrap();
        Some(handle)
    };

    let session = bluer::Session::new().await?;
    let adapter = session.default_adapter().await?;
    adapter.set_powered(true).await?;

    let airpods: DeviceRegistry<AirPodsDevice> = DeviceRegistry::new();
    // Whether any AirPods are connected over Bluetooth (BlueZ Device1.Connected); the BLE
    // scanner only scans actively when none are, so scanning never competes with their audio.
    let (aacp_connected_tx, aacp_connected_rx) = tokio::sync::watch::channel(false);
    let aacp_connected = Arc::new(aacp_connected_tx);
    let connected_airpods = Arc::new(std::sync::Mutex::new(ConnectedAirPods::default()));
    let battery_reporter = BatteryReporter::register().await;
    let device_context = DeviceContext {
        tray_handle: tray_handle.clone(),
        ui_tx: ui_tx.clone(),
        battery_reporter,
        prefs: PrefsStore::at_default_path(),
        notifier: Notifier::connect().await,
    };
    // Kept for the life of the app: `librepods ctl` and second launches talk to it.
    let _control = control::serve(
        device_managers.clone(),
        device_context.prefs.clone(),
        Some(ui_tx.clone()),
    )
    .await;

    info!("Listening for new connections.");

    info!("Checking for connected devices...");
    match find_connected_airpods(&adapter).await {
        Ok(device) => {
            let name = device
                .name()
                .await?
                .unwrap_or_else(|| "Unknown".to_string());
            info!("Found connected AirPods: {}, initializing.", name);
            set_airpods_connected(
                &connected_airpods,
                &aacp_connected,
                &device.address().to_string(),
                true,
            );
            connect_airpods(
                airpods.clone(),
                device.address(),
                device_context.clone(),
                device_managers.clone(),
            )
            .await;
        }
        Err(_) => {
            info!("No connected AirPods found.");
        }
    }

    // Started after the initial check so it never scans while already-connected AirPods come up.
    let le_tray_clone = tray_handle.clone();
    tokio::spawn(async move {
        info!("Starting LE monitor...");
        if let Err(e) = start_le_monitor(le_tray_clone, aacp_connected_rx).await {
            log::error!("LE monitor error: {}", e);
        }
    });

    match find_other_managed_devices(&adapter, managed_devices_mac.clone()).await {
        Ok(devices) => {
            for device in devices {
                let addr_str = device.address().to_string();
                info!(
                    "Found connected managed device: {}, initializing.",
                    addr_str
                );
                let Some(device_data) = devices_list.get(&addr_str) else {
                    warn!("No saved entry for managed device {}, skipping", addr_str);
                    continue;
                };
                let type_ = device_data.type_.clone();
                let ui_tx_clone = ui_tx.clone();
                let device_managers = device_managers.clone();
                tokio::spawn(async move {
                    let mut managers = device_managers.write().await;
                    if type_ == devices::enums::DeviceType::Nothing {
                        let dev = devices::nothing::NothingDevice::new(
                            device.address(),
                            ui_tx_clone.clone(),
                        )
                        .await;
                        let dev_managers = DeviceManagers::with_att(dev.att_manager.clone());
                        managers
                            .entry(addr_str.clone())
                            .or_insert(dev_managers)
                            .set_att(dev.att_manager);
                        if let Err(e) = ui_tx_clone.send(BluetoothUIMessage::DeviceConnected(addr_str)) {
                            warn!("Failed to send DeviceConnected UI message: {:?}", e);
                        }
                    }
                    drop(managers)
                });
            }
        }
        Err(e) => {
            log::debug!("type of error: {:?}", e.kind);
            if e.kind
                != bluer::ErrorKind::Internal(InternalErrorKind::Io(std::io::ErrorKind::NotFound))
            {
                log::error!("Error finding other managed devices: {}", e);
            } else {
                info!("No other managed devices found.");
            }
        }
    }

    let conn = Connection::new_system()?;
    let rule = MatchRule::new_signal("org.freedesktop.DBus.Properties", "PropertiesChanged");
    conn.add_match(rule, move |_: (), conn, msg| {
        let Some(path) = msg.path() else {
            return true;
        };
        if !path.contains("/org/bluez/hci") || !path.contains("/dev_") {
            return true;
        }
        // debug!("PropertiesChanged signal for path: {}", path);
        let Ok((iface, changed, _)) =
            msg.read3::<String, HashMap<String, Variant<Box<dyn RefArg>>>, Vec<String>>()
        else {
            return true;
        };
        if iface != "org.bluez.Device1" {
            return true;
        }
        let Some(connected_var) = changed.get("Connected") else {
            return true;
        };
        let Some(is_connected) = connected_var.0.as_ref().as_u64() else {
            return true;
        };
        let proxy = conn.with_proxy("org.bluez", path, std::time::Duration::from_millis(5000));
        let Ok(uuids) = proxy.get::<Vec<String>>("org.bluez.Device1", "UUIDs") else {
            return true;
        };
        let target_uuid = "74ec2172-0bad-4d01-8f77-997b2be0722a";

        let Ok(addr_str) = proxy.get::<String>("org.bluez.Device1", "Address") else {
            return true;
        };
        let Ok(addr) = addr_str.parse::<Address>() else {
            return true;
        };
        if is_connected == 0 {
            if let Err(e) = ui_tx.send(BluetoothUIMessage::DeviceDisconnected(addr_str.clone())) {
                warn!("Failed to send DeviceDisconnected UI message: {:?}", e);
            }
            set_airpods_connected(&connected_airpods, &aacp_connected, &addr_str, false);
            tokio::spawn(disconnect_airpods(
                airpods.clone(),
                addr_str.clone(),
                tray_handle.clone(),
            ));
            return true;
        }
        if managed_devices_mac.contains(&addr_str) {
            info!("Managed device connected: {}, initializing", addr_str);
            let Some(device_data) = devices_list.get(&addr_str) else {
                warn!("No saved entry for managed device {}, skipping", addr_str);
                return true;
            };
            let type_ = device_data.type_.clone();
            if type_ == devices::enums::DeviceType::Nothing {
                let ui_tx_clone = ui_tx.clone();
                let device_managers = device_managers.clone();
                tokio::spawn(async move {
                    let mut managers = device_managers.write().await;
                    let dev = devices::nothing::NothingDevice::new(addr, ui_tx_clone.clone()).await;
                    let dev_managers = DeviceManagers::with_att(dev.att_manager.clone());
                    managers
                        .entry(addr_str.clone())
                        .or_insert(dev_managers)
                        .set_att(dev.att_manager);
                    drop(managers);
                    if let Err(e) = ui_tx_clone.send(BluetoothUIMessage::DeviceConnected(addr_str.clone())) {
                        warn!("Failed to send DeviceConnected UI message: {:?}", e);
                    }
                });
            }
            return true;
        }

        if !uuids.iter().any(|u| u.to_lowercase() == target_uuid) {
            return true;
        }
        let name = proxy
            .get::<String>("org.bluez.Device1", "Name")
            .unwrap_or_else(|_| "Unknown".to_string());
        info!("AirPods connected: {}, initializing", name);
        set_airpods_connected(&connected_airpods, &aacp_connected, &addr_str, true);
        tokio::spawn(connect_airpods(
            airpods.clone(),
            addr,
            device_context.clone(),
            device_managers.clone(),
        ));
        true
    })?;

    info!("Listening for Bluetooth connections via D-Bus...");
    loop {
        conn.process(std::time::Duration::from_millis(1000))?;
    }
}

/// Brings up the single AirPods instance for `addr`, unless one is already live or connecting.
async fn connect_airpods(
    registry: DeviceRegistry<AirPodsDevice>,
    addr: Address,
    ctx: DeviceContext,
    device_managers: Arc<RwLock<HashMap<String, DeviceManagers>>>,
) {
    let mac = addr.to_string();
    let addr_str = mac.clone();
    registry
        .connect_with(&mac, move || async move {
            let ui_tx = ctx.ui_tx.clone();
            let device = AirPodsDevice::new(addr, ctx).await?;
            let mut managers = device_managers.write().await;
            // let dev_managers = DeviceManagers::with_both(device.aacp_manager.clone(), device.att_manager.clone());
            managers
                .entry(addr_str.clone())
                .or_insert_with(|| DeviceManagers::with_aacp(device.aacp_manager.clone()))
                .set_aacp(device.aacp_manager.clone());
            drop(managers);
            if let Err(e) = ui_tx.send(BluetoothUIMessage::DeviceConnected(addr_str)) {
                warn!("Failed to send DeviceConnected UI message: {:?}", e);
            }
            Some(device)
        })
        .await;
}

/// Tears down the AirPods instance for `mac` and clears its state from the tray.
async fn disconnect_airpods(
    registry: DeviceRegistry<AirPodsDevice>,
    mac: String,
    tray_handle: Option<ksni::Handle<MyTray>>,
) {
    if !registry.disconnect(&mac).await {
        return;
    }
    if let Some(handle) = tray_handle {
        handle
            .update(|tray: &mut MyTray| {
                tray.connected = false;
                tray.battery_headphone = None;
                tray.battery_headphone_status = None;
                tray.battery_l = None;
                tray.battery_l_status = None;
                tray.battery_r = None;
                tray.battery_r_status = None;
                tray.battery_c = None;
                tray.battery_c_status = None;
                tray.listening_mode = None;
                tray.allow_off_option = None;
                tray.conversation_detect_enabled = None;
                tray.command_tx = None;
                tray.model = None;
            })
            .await;
    }
}

/// Moves the old global stem-press setting into each known AirPods' own stem actions, once.
fn migrate_app_settings() {
    let path = get_app_settings_path();
    let mut settings = load_app_settings(&path);
    let airpods_macs: Vec<String> = std::fs::read_to_string(get_devices_path())
        .ok()
        .and_then(|json| serde_json::from_str::<HashMap<String, DeviceData>>(&json).ok())
        .map(|devices| {
            devices
                .into_iter()
                .filter(|(_, data)| data.type_ == devices::enums::DeviceType::AirPods)
                .map(|(mac, _)| mac)
                .collect()
        })
        .unwrap_or_default();
    if migrate_legacy_stem_control(&mut settings, &PrefsStore::at_default_path(), &airpods_macs)
        && let Err(e) = save_app_settings(&path, &settings)
    {
        warn!("Could not save migrated app settings: {}", e);
    }
}

/// Records one AirPods pair's Bluetooth connection and tells the BLE scanner whether any are up.
fn set_airpods_connected(
    connected_airpods: &std::sync::Mutex<ConnectedAirPods>,
    aacp_connected: &tokio::sync::watch::Sender<bool>,
    mac: &str,
    connected: bool,
) {
    let any = connected_airpods
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .set(mac, connected);
    aacp_connected.send_replace(any);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ctl_subcommand_parses() {
        let args = Args::try_parse_from([
            "librepods",
            "ctl",
            "mode",
            "anc",
            "--mac",
            "AA:BB:CC:DD:EE:01",
        ])
        .unwrap();
        match args.command {
            Some(Command::Ctl(ctl)) => {
                assert_eq!(ctl.mac.as_deref(), Some("AA:BB:CC:DD:EE:01"));
                assert!(matches!(ctl.command, control::CtlCommand::Mode { ref mode } if mode == "anc"));
            }
            _ => panic!("expected the ctl subcommand"),
        }
        let plain = Args::try_parse_from(["librepods", "--start-minimized"]).unwrap();
        assert!(plain.command.is_none());
        assert!(plain.start_minimized);
    }
}
