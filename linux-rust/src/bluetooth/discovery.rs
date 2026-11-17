use bluer::Adapter;
use log::debug;
use std::io::Error;

pub(crate) async fn find_connected_airpods(adapter: &Adapter) -> bluer::Result<bluer::Device> {
    let target_uuid = uuid::Uuid::parse_str("74ec2172-0bad-4d01-8f77-997b2be0722a").unwrap();

    let addrs = adapter.device_addresses().await?;
    for addr in addrs {
        let device = adapter.device(addr)?;
        if device.is_connected().await.unwrap_or(false)
            && let Ok(uuids) = device.uuids().await
            && let Some(uuids) = uuids
            && uuids.iter().any(|u| *u == target_uuid)
        {
            return Ok(device);
        }
    }
    Err(bluer::Error::from(Error::new(
        std::io::ErrorKind::NotFound,
        "No connected AirPods found",
    )))
}

pub async fn find_other_managed_devices(
    adapter: &Adapter,
    managed_macs: Vec<String>,
) -> bluer::Result<Vec<bluer::Device>> {
    let addrs = adapter.device_addresses().await?;
    let mut devices = Vec::new();
    for addr in addrs {
        let device = adapter.device(addr)?;
        let device_mac = device.address().to_string();
        let connected = device.is_connected().await.unwrap_or(false);
        debug!("Checking device: {}, connected: {}", device_mac, connected);
        if connected && managed_macs.contains(&device_mac) {
            debug!("Found managed device: {}", device_mac);
            devices.push(device);
        }
    }
    if !devices.is_empty() {
        return Ok(devices);
    }
    debug!("No other managed devices found");
    Err(bluer::Error::from(Error::new(
        std::io::ErrorKind::NotFound,
        "No other managed devices found",
    )))
}

/// Waits until a Bluetooth adapter exists and is powered on, then returns it.
///
/// We deliberately do not force the adapter on: desktop environments such as
/// COSMIC turn Bluetooth off by soft-blocking it via rfkill, and in that state
/// `set_powered(true)` fails with `org.bluez.Error.Failed`. Instead we wait for
/// the user to enable Bluetooth.
pub(crate) async fn wait_for_powered_adapter(session: &bluer::Session) -> Adapter {
    let mut reported = false;
    loop {
        match session.default_adapter().await {
            Ok(adapter) => match adapter.is_powered().await {
                Ok(true) => {
                    if reported {
                        log::info!("Bluetooth adapter {} is now powered on.", adapter.name());
                    }
                    return adapter;
                }
                Ok(false) => {
                    if !reported {
                        log::info!(
                            "Bluetooth adapter {} is off; waiting for Bluetooth to be enabled.",
                            adapter.name()
                        );
                        reported = true;
                    }
                }
                Err(e) => {
                    if !reported {
                        log::warn!("Could not query Bluetooth adapter state: {}", e);
                        reported = true;
                    }
                }
            },
            Err(e) => {
                if !reported {
                    log::warn!("No Bluetooth adapter available ({}); waiting.", e);
                    reported = true;
                }
            }
        }
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
    }
}

/// Connects a paired device (e.g. AirPods that are currently with an iPhone).
/// Uses BlueZ's Device1.Connect, falling back to `bluetoothctl connect`, which
/// some setups need. Returns a short, user-facing error.
pub(crate) async fn connect_device(addr: bluer::Address) -> Result<(), String> {
    let session = bluer::Session::new()
        .await
        .map_err(|e| format!("Can't reach Bluetooth: {e}"))?;
    let adapter = session
        .default_adapter()
        .await
        .map_err(|_| "No Bluetooth adapter found".to_string())?;
    if !adapter.is_powered().await.unwrap_or(false) {
        return Err("Bluetooth is turned off".to_string());
    }
    let device = adapter
        .device(addr)
        .map_err(|e| format!("Unknown device: {e}"))?;
    if device.is_connected().await.unwrap_or(false) {
        return Ok(());
    }
    log::info!("Connecting to {addr}...");
    match tokio::time::timeout(std::time::Duration::from_secs(20), device.connect()).await {
        Ok(Ok(())) => return Ok(()),
        Ok(Err(e)) => log::warn!("BlueZ connect to {addr} failed ({e}); trying bluetoothctl"),
        Err(_) => log::warn!("BlueZ connect to {addr} timed out; trying bluetoothctl"),
    }
    let output = tokio::process::Command::new("bluetoothctl")
        .args(["--timeout", "20", "connect", &addr.to_string()])
        .output()
        .await
        .map_err(|e| format!("Couldn't run bluetoothctl: {e}"))?;
    if device.is_connected().await.unwrap_or(false) {
        Ok(())
    } else {
        let out = String::from_utf8_lossy(&output.stdout);
        log::warn!("bluetoothctl connect {addr}: {}", out.trim());
        Err(if out.contains("page-timeout") || out.contains("Host is down") {
            "AirPods didn't respond. Make sure they're out of the case and nearby.".to_string()
        } else {
            "Couldn't connect. Try again, or disconnect them on your other device first.".to_string()
        })
    }
}

/// The first AirPods saved in devices.json, used by the tray/applet "Connect" action.
pub(crate) fn saved_airpods_address() -> Option<bluer::Address> {
    let json = std::fs::read_to_string(crate::utils::get_devices_path()).ok()?;
    let devices: std::collections::HashMap<String, crate::devices::enums::DeviceData> =
        serde_json::from_str(&json).ok()?;
    devices
        .iter()
        .find(|(_, d)| d.type_ == crate::devices::enums::DeviceType::AirPods)
        .and_then(|(mac, _)| mac.parse().ok())
}

/// The saved (devices.json) name for a device.
pub(crate) fn saved_device_name(mac: &str) -> Option<String> {
    let json = std::fs::read_to_string(crate::utils::get_devices_path()).ok()?;
    let devices: std::collections::HashMap<String, crate::devices::enums::DeviceData> =
        serde_json::from_str(&json).ok()?;
    devices.get(mac).map(|d| d.name.clone())
}
