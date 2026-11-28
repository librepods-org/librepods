mod bluetooth;
mod devices;
mod ipc;
mod media_controller;
mod notify;
mod single_instance;
mod ui;
mod utils;

use crate::bluetooth::discovery::{find_connected_airpods, find_other_managed_devices};
use crate::bluetooth::managers::DeviceManagers;
use crate::devices::enums::DeviceData;
use crate::ui::messages::BluetoothUIMessage;
use crate::ui::tray::MyTray;
use crate::utils::{get_app_settings_path, get_devices_path};
use bluer::{Address, InternalErrorKind};
use clap::Parser;
use dbus::arg::{RefArg, Variant};
use dbus::blocking::Connection;
use dbus::blocking::stdintf::org_freedesktop_dbus::Properties;
use dbus::message::MatchRule;
use devices::airpods::AirPodsDevice;
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
    #[arg(
        long,
        value_name = "MODE",
        help = "Tell the running LibrePods to switch noise control and exit: off, anc, transparency, adaptive, or cycle (bind this to a keyboard shortcut)"
    )]
    listening_mode: Option<String>,
    #[arg(long, help = "Print the current status of the running LibrePods as JSON and exit (for status bars)")]
    status: bool,
    #[arg(long, help = "Like --status, but print a new JSON line on every change")]
    watch: bool,
    #[arg(long, help = "Tell the running LibrePods to connect your AirPods (e.g. after using them with an iPhone) and exit")]
    connect: bool,
    #[arg(long, help = "Quit the running LibrePods")]
    quit: bool,
}

/// Client-side commands that talk to an already running instance.
fn run_client_command(args: &Args) -> Option<std::process::ExitCode> {
    use std::io::{BufRead, BufReader, Write};
    use std::process::ExitCode;

    let command = if let Some(mode) = &args.listening_mode {
        match mode.to_lowercase().as_str() {
            "off" => "listening_mode 1".to_string(),
            "anc" | "noise-cancellation" | "noise_cancellation" => "listening_mode 2".to_string(),
            "transparency" => "listening_mode 3".to_string(),
            "adaptive" => "listening_mode 4".to_string(),
            "cycle" => "cycle_listening_mode".to_string(),
            other => {
                eprintln!("Unknown listening mode '{}'. Use off, anc, transparency, adaptive or cycle.", other);
                return Some(ExitCode::from(2));
            }
        }
    } else if args.connect {
        "connect".to_string()
    } else if args.quit {
        "quit".to_string()
    } else if args.status || args.watch {
        "subscribe".to_string()
    } else {
        return None;
    };

    let mut stream = match std::os::unix::net::UnixStream::connect(single_instance::socket_path()) {
        Ok(s) => s,
        Err(_) => {
            eprintln!("LibrePods is not running.");
            return Some(ExitCode::FAILURE);
        }
    };
    if stream.write_all(format!("{}\n", command).as_bytes()).is_err() {
        eprintln!("Failed to talk to LibrePods.");
        return Some(ExitCode::FAILURE);
    }
    if command == "subscribe" {
        for line in BufReader::new(stream).lines() {
            let Ok(line) = line else { break };
            println!("{}", line);
            if !args.watch {
                break;
            }
        }
    }
    Some(ExitCode::SUCCESS)
}

fn main() -> iced::Result {
    let args = Args::parse();

    if let Some(code) = run_client_command(&args) {
        std::process::exit(if code == std::process::ExitCode::SUCCESS { 0 } else { 1 });
    }

    if args.version {
        println!(
            "You are running LibrePods version {}",
            env!("CARGO_PKG_VERSION")
        );
        return Ok(());
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
    // Check for a running instance before touching the log file: setting up
    // logging truncates it, which would wipe the running instance's log every
    // time LibrePods is launched again from the app launcher.
    let instance_listener = match single_instance::acquire() {
        single_instance::Instance::Primary(listener) => listener,
        single_instance::Instance::Secondary => {
            eprintln!("LibrePods is already running; asked it to open its window.");
            return Ok(());
        }
    };
    init_logging();

    // Settings writes assume these exist; a fresh install would otherwise fail to
    // save settings (upstream #633) or panic when saving devices.
    for path in [get_app_settings_path(), get_devices_path(), utils::get_preferences_path()] {
        if let Some(dir) = path.parent()
            && let Err(e) = std::fs::create_dir_all(dir)
        {
            warn!("Could not create {}: {}", dir.display(), e);
        }
    }


    let (ui_tx, ui_rx) = unbounded_channel::<BluetoothUIMessage>();

    let device_managers: Arc<RwLock<HashMap<String, DeviceManagers>>> =
        Arc::new(RwLock::new(HashMap::new()));

    // Load stem_control initial value from settings JSON, then apply CLI override.
    if args.no_tray {
        // Run headless without UI
        info!("Running in headless mode (no GUI)");
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async_main(ui_tx, device_managers, instance_listener)).unwrap();
        Ok(())
    } else {
        // Run with UI
        let device_managers_clone = device_managers.clone();
        std::thread::spawn(|| {
            let rt = tokio::runtime::Runtime::new().unwrap();
            if let Err(e) = rt.block_on(async_main(ui_tx, device_managers_clone, instance_listener)) {
                log::error!("Bluetooth backend stopped: {}", e);
            }
        });

        ui::window::start_ui(ui_rx, args.start_minimized, device_managers)
    }
}

async fn async_main(
    ui_tx: tokio::sync::mpsc::UnboundedSender<BluetoothUIMessage>,
    device_managers: Arc<RwLock<HashMap<String, DeviceManagers>>>,
    instance_listener: Option<std::os::unix::net::UnixListener>,
) -> bluer::Result<()> {
    let args = Args::parse();

    let mut managed_devices_mac: Vec<String> = Vec::new(); // includes ony non-AirPods. AirPods handled separately.

    let devices_path = get_devices_path();
    let devices_json = std::fs::read_to_string(&devices_path).unwrap_or_else(|e| {
        if e.kind() == std::io::ErrorKind::NotFound {
            debug!("No devices file at {}; starting with no managed devices.", devices_path.display());
        } else {
            log::error!("Failed to read devices file: {}", e);
        }
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

    // Known up front, so the applet can say e.g. "Satya's AirPods Pro: Not connected".
    let saved_name = bluetooth::discovery::saved_airpods_address()
        .and_then(|a| bluetooth::discovery::saved_device_name(&a.to_string()));
    let tray_state = || MyTray {
        device_name: saved_name.clone(),
        saved_airpods: bluetooth::discovery::saved_airpods(),
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
    let ksni_handle = if args.no_tray {
        None
    } else {
        match tray_state().spawn().await {
            Ok(handle) => Some(handle),
            Err(e) => {
                // No StatusNotifier host (e.g. a desktop without a tray); the COSMIC
                // applet and the window still work.
                warn!("Could not create tray icon: {}", e);
                None
            }
        }
    };
    let tray_handle = Some(ipc::TrayHandle::new(ksni_handle, tray_state(), !args.no_tray));
    ipc::set_global(tray_handle.clone().unwrap());
    tokio::spawn(notify::run(tray_handle.as_ref().unwrap().subscribe()));
    tokio::spawn(media_controller::call_audio_watcher());

    if let Some(listener) = instance_listener {
        tokio::spawn(ipc::serve(
            listener,
            ui_tx.clone(),
            tray_handle.clone().unwrap(),
        ));
    }

    tokio::spawn(bluetooth::le::run_le_monitor(tray_handle.clone()));

    info!("Listening for new connections.");

    // Scan for already-connected devices once Bluetooth is available. This runs in
    // the background so that the D-Bus listener below (and the tray/UI) keep working
    // while Bluetooth is turned off.
    {
        let tray_handle = tray_handle.clone();
        let ui_tx = ui_tx.clone();
        let device_managers = device_managers.clone();
        let managed_devices_mac = managed_devices_mac.clone();
        let devices_list = devices_list.clone();
        tokio::spawn(async move {
            let session = match bluer::Session::new().await {
                Ok(s) => s,
                Err(e) => {
                    log::error!("Failed to connect to BlueZ: {}", e);
                    return;
                }
            };
            let adapter = bluetooth::discovery::wait_for_powered_adapter(&session).await;
            info!("Checking for connected devices...");
            match find_connected_airpods(&adapter).await {
                Ok(device) => {
                    let name = device
                        .name()
                        .await
                        .ok()
                        .flatten()
                        .unwrap_or_else(|| "Unknown".to_string());
                    info!("Found connected AirPods: {}, initializing.", name);
                    let airpods_device =
                        AirPodsDevice::new(device.address(), tray_handle.clone(), ui_tx.clone()).await;

                    let mut managers = device_managers.write().await;
                    // let dev_managers = DeviceManagers::with_both(airpods_device.aacp_manager.clone(), airpods_device.att_manager.clone());
                    let dev_managers = DeviceManagers::with_aacp(airpods_device.aacp_manager.clone());
                    managers
                        .entry(device.address().to_string())
                        .or_insert(dev_managers)
                        .set_aacp(airpods_device.aacp_manager);
                    drop(managers);
                    if let Err(e) = ui_tx.send(BluetoothUIMessage::DeviceConnected(
                        device.address().to_string(),
                    )) {
                        warn!("Failed to send DeviceConnected UI message: {:?}", e);
                    }
                }
                Err(_) => {
                    info!("No connected AirPods found.");
                }
            }

            match find_other_managed_devices(&adapter, managed_devices_mac).await {
                Ok(devices) => {
                    for device in devices {
                        let addr_str = device.address().to_string();
                        info!(
                            "Found connected managed device: {}, initializing.",
                            addr_str
                        );
                        let type_ = devices_list.get(&addr_str).unwrap().type_.clone();
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
        });
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
        if is_connected==0 {
            if let Err(e) = ui_tx.send(BluetoothUIMessage::DeviceDisconnected(addr_str.clone())) {
                warn!("Failed to send DeviceConnected UI message: {:?}", e);
            }
            // Clear the tray/applet/IPC state, which otherwise keeps showing the
            // AirPods as connected with stale battery levels.
            if uuids.iter().any(|u| u.to_lowercase() == target_uuid)
                && let Some(handle) = tray_handle.clone()
            {
                tokio::spawn(async move {
                    handle
                        .update(|t| {
                            t.connected = false;
                            t.battery_headphone = None;
                            t.battery_headphone_status = None;
                            t.battery_l = None;
                            t.battery_l_status = None;
                            t.battery_r = None;
                            t.battery_r_status = None;
                            t.battery_c = None;
                            t.battery_c_status = None;
                            t.listening_mode = None;
                            t.conversation_detect_enabled = None;
                            t.command_tx = None;
                        })
                        .await;
                });
            }
            return true
        }
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
            if let Err(e) = ui_tx_clone.send(BluetoothUIMessage::DeviceConnected(addr_str.clone())) {
                warn!("Failed to send DeviceConnected UI message: {:?}", e);
            }
        });
        true
    })?;

    info!("Listening for Bluetooth connections via D-Bus...");
    loop {
        conn.process(std::time::Duration::from_millis(1000))?;
    }
}

/// Logs to stderr when run from a terminal. When launched from the app launcher,
/// autostart or the COSMIC applet, stderr goes nowhere, so log to
/// `$XDG_STATE_HOME/librepods/librepods.log` (default `~/.local/state/...`).
fn init_logging() {
    use std::io::IsTerminal;
    let mut builder = env_logger::Builder::from_default_env();
    if !std::io::stderr().is_terminal() {
        let state_dir = env::var("XDG_STATE_HOME").unwrap_or_else(|_| {
            format!("{}/.local/state", env::var("HOME").unwrap_or_default())
        });
        let dir = std::path::PathBuf::from(state_dir).join("librepods");
        let file = std::fs::create_dir_all(&dir)
            .and_then(|_| std::fs::File::create(dir.join("librepods.log")));
        if let Ok(file) = file {
            builder.target(env_logger::Target::Pipe(Box::new(file)));
        }
    }
    builder.init();
}
