mod bluetooth;
mod media_controller;
mod ui;
mod utils;
mod devices;

use std::env;
use log::info;
use dbus::blocking::Connection;
use dbus::blocking::stdintf::org_freedesktop_dbus::Properties;
use dbus::message::MatchRule;
use dbus::arg::{RefArg, Variant};
use std::collections::HashMap;
use std::sync::Arc;
use crate::bluetooth::discovery::{find_connected_airpods, find_other_managed_devices};
use devices::airpods::AirPodsDevice;
use bluer::{Address, InternalErrorKind};
use ksni::TrayMethods;
use crate::ui::tray::MyTray;
use clap::Parser;
use crate::bluetooth::le::start_le_monitor;
use tokio::sync::mpsc::unbounded_channel;
use tokio::sync::RwLock;
use crate::bluetooth::managers::DeviceManagers;
use crate::devices::enums::DeviceData;
use crate::ui::messages::BluetoothUIMessage;
use crate::utils::get_devices_path;

#[derive(Parser)]
struct Args {
    #[arg(long, short='d', help="Enable debug logging")]
    debug: bool,
    #[arg(long, help="Disable system tray, useful if your environment doesn't support AppIndicator or StatusNotifier")]
    no_tray: bool,
    #[arg(long, help="Start the application minimized to tray")]
    start_minimized: bool,
    #[arg(long, help="Enable Bluetooth LE debug logging. Only use when absolutely necessary; this produces a lot of logs.")]
    le_debug: bool,
    #[arg(long, short='v', help="Show application version and exit")]
    version: bool
}

fn main() -> iced::Result {
    let args = Args::parse();

    if args.version {
        println!("You are running LibrePods version {}", env!("CARGO_PKG_VERSION"));
        return Ok(());
    }

    let log_level = if args.debug { "debug" } else { "info" };
    let wayland_display = env::var("WAYLAND_DISPLAY").is_ok();
    if env::var("RUST_LOG").is_err() {
        if wayland_display {
            unsafe { env::set_var("WGPU_BACKEND", "gl") };
        }
        unsafe { env::set_var("RUST_LOG", log_level.to_owned() + &format!(",winit=warn,tracing=warn,iced_wgpu=warn,wgpu_hal=warn,wgpu_core=warn,cosmic_text=warn,naga=warn,iced_winit=warn,librepods_rust::bluetooth::le={}", if args.le_debug { "debug" } else { "warn" })) };
    }
    env_logger::init();

    let (ui_tx, ui_rx) = unbounded_channel::<BluetoothUIMessage>();

    let device_managers: Arc<RwLock<HashMap<String, DeviceManagers>>> = Arc::new(RwLock::new(HashMap::new()));
    let device_managers_clone = device_managers.clone();
    std::thread::spawn(|| {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async_main(ui_tx, device_managers_clone)).unwrap();
    });

    ui::window::start_ui(ui_rx, args.start_minimized, device_managers)
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
    let devices_list: HashMap<String, DeviceData> = serde_json::from_str(&devices_json).unwrap_or_else(|e| {
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
            command_tx: None,
            ui_tx: Some(ui_tx.clone()),
        };
        let handle = tray.spawn().await.unwrap();
        Some(handle)
    };

    let session = bluer::Session::new().await?;
    let adapter = session.default_adapter().await?;
    adapter.set_powered(true).await?;

    let le_tray_clone = tray_handle.clone();
    tokio::spawn(async move {
        info!("Starting LE monitor...");
        if let Err(e) = start_le_monitor(le_tray_clone).await {
            log::error!("LE monitor error: {}", e);
        }
    });

    info!("Listening for new connections.");

    info!("Checking for connected devices...");
    match find_connected_airpods(&adapter).await {
        Ok(device) => {
            let name = device.name().await?.unwrap_or_else(|| "Unknown".to_string());
            info!("Found connected AirPods: {}, initializing.", name);
            let airpods_device = AirPodsDevice::new(device.address(), tray_handle.clone(), ui_tx.clone()).await;

            let mut managers = device_managers.write().await;
            // let dev_managers = DeviceManagers::with_both(airpods_device.aacp_manager.clone(), airpods_device.att_manager.clone());
            let dev_managers = DeviceManagers::with_aacp(airpods_device.aacp_manager.clone());
            managers
                .entry(device.address().to_string())
                .or_insert(dev_managers)
                .set_aacp(airpods_device.aacp_manager);
            drop(managers);
            ui_tx.send(BluetoothUIMessage::DeviceConnected(device.address().to_string())).unwrap();
        }
        Err(_) => {
            info!("No connected AirPods found.");
        }
    }

    match find_other_managed_devices(&adapter, managed_devices_mac.clone()).await {
        Ok(devices) => {
            for device in devices {
                let addr_str = device.address().to_string();
                info!("Found connected managed device: {}, initializing.", addr_str);
                let type_ = devices_list.get(&addr_str).unwrap().type_.clone();
                let ui_tx_clone = ui_tx.clone();
                let device_managers = device_managers.clone();
                tokio::spawn(async move {
                    let mut managers = device_managers.write().await;
                    if type_ == devices::enums::DeviceType::Nothing {
                        let dev = devices::nothing::NothingDevice::new(device.address(), ui_tx_clone.clone()).await;
                        let dev_managers = DeviceManagers::with_att(dev.att_manager.clone());
                        managers
                            .entry(addr_str.clone())
                            .or_insert(dev_managers)
                            .set_att(dev.att_manager);
                        ui_tx_clone.send(BluetoothUIMessage::DeviceConnected(addr_str)).unwrap();
                    }
                    drop(managers)
                });
            }
        }
        Err(e) => {
            log::debug!("type of error: {:?}", e.kind);
            if e.kind != bluer::ErrorKind::Internal(InternalErrorKind::Io(std::io::ErrorKind::NotFound)) {
                log::error!("Error finding other managed devices: {}", e);
            } else {
                info!("No other managed devices found.");
            }
        }
    }

    let conn = Connection::new_system()?;
    let rule = MatchRule::new_signal("org.freedesktop.DBus.Properties", "PropertiesChanged");
    conn.add_match(rule, move |_: (), conn, msg| {
        let Some(path) = msg.path() else { return true; };
        if !path.contains("/org/bluez/hci") || !path.contains("/dev_") {
            return true;
        }
        // debug!("PropertiesChanged signal for path: {}", path);
        let Ok((iface, changed, _)) = msg.read3::<String, HashMap<String, Variant<Box<dyn RefArg>>>, Vec<String>>() else {
            return true;
        };
        if iface != "org.bluez.Device1" {
            return true;
        }
        let Some(connected_var) = changed.get("Connected") else { return true; };
        let Some(is_connected) = connected_var.0.as_ref().as_u64() else { return true; };
        if is_connected == 0 {
            return true;
        }
        let proxy = conn.with_proxy("org.bluez", path, std::time::Duration::from_millis(5000));
        let Ok(uuids) = proxy.get::<Vec<String>>("org.bluez.Device1", "UUIDs") else { return true; };
        let target_uuid = "74ec2172-0bad-4d01-8f77-997b2be0722a";

        let Ok(addr_str) = proxy.get::<String>("org.bluez.Device1", "Address") else { return true; };
        let Ok(addr) = addr_str.parse::<Address>() else { return true; };

        if managed_devices_mac.contains(&addr_str) {
            info!("Managed device connected: {}, initializing", addr_str);
            let type_ = devices_list.get(&addr_str).unwrap().type_.clone();
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
                    ui_tx_clone.send(BluetoothUIMessage::DeviceConnected(addr_str.clone())).unwrap();
                });
            }
            return true;
        }

        if !uuids.iter().any(|u| u.to_lowercase() == target_uuid) {
            return true;
        }
        let name = proxy.get::<String>("org.bluez.Device1", "Name").unwrap_or_else(|_| "Unknown".to_string());
        info!("AirPods connected: {}, initializing", name);
        let handle_clone = tray_handle.clone();
        let ui_tx_clone = ui_tx.clone();
        let device_managers = device_managers.clone();
        tokio::spawn(async move {
            let airpods_device = AirPodsDevice::new(addr, handle_clone, ui_tx_clone.clone()).await;
            let mut managers = device_managers.write().await;
            // let dev_managers = DeviceManagers::with_both(airpods_device.aacp_manager.clone(), airpods_device.att_manager.clone());
            let dev_managers = DeviceManagers::with_aacp(airpods_device.aacp_manager.clone());
            managers
                .entry(addr_str.clone())
                .or_insert(dev_managers)
                .set_aacp(airpods_device.aacp_manager);
            drop(managers);
            ui_tx_clone.send(BluetoothUIMessage::DeviceConnected(addr_str.clone())).unwrap();
        });
        true
    })?;

    info!("Listening for Bluetooth connections via D-Bus...");
    loop {
        conn.process(std::time::Duration::from_millis(1000))?;
    }
}