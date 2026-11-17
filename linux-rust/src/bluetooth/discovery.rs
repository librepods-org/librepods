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

/// Saved AirPods as (address, name), sorted by name.
pub(crate) fn saved_airpods() -> Vec<(String, String)> {
    airpods_in(&crate::utils::load_devices())
}

pub(crate) fn airpods_in(
    devices: &std::collections::HashMap<String, crate::devices::enums::DeviceData>,
) -> Vec<(String, String)> {
    let mut list: Vec<(String, String)> = devices
        .iter()
        .filter(|(_, d)| d.type_ == crate::devices::enums::DeviceType::AirPods)
        .map(|(mac, d)| (mac.clone(), d.name.clone()))
        .collect();
    list.sort_by(|a, b| a.1.cmp(&b.1).then(a.0.cmp(&b.0)));
    list
}

/// The first saved AirPods, used when "Connect" doesn't say which.
pub(crate) fn saved_airpods_address() -> Option<bluer::Address> {
    saved_airpods().first().and_then(|(mac, _)| mac.parse().ok())
}

/// The saved (devices.json) name for a device.
pub(crate) fn saved_device_name(mac: &str) -> Option<String> {
    let json = std::fs::read_to_string(crate::utils::get_devices_path()).ok()?;
    let devices: std::collections::HashMap<String, crate::devices::enums::DeviceData> =
        serde_json::from_str(&json).ok()?;
    devices.get(mac).map(|d| d.name.clone())
}

/// Is this an unpaired Apple audio device seen over BR/EDR (classic Bluetooth)?
async fn is_unpaired_airpods(device: &bluer::Device) -> bool {
    if device.is_paired().await.unwrap_or(true) {
        return false;
    }
    // A Class of Device is only known from a BR/EDR inquiry; major class 0x04 is
    // audio/video. AirPods' LE-only "Find My" identity has no class.
    let audio = matches!(device.class().await, Ok(Some(c)) if (c >> 8) & 0x1F == 0x04);
    if !audio {
        return false;
    }
    let apple = matches!(device.modalias().await, Ok(Some(m)) if m.vendor == 0x004C);
    let named = device
        .name()
        .await
        .ok()
        .flatten()
        .is_some_and(|n| n.contains("AirPods"));
    apple || named
}

/// Finds AirPods in pairing mode and pairs, trusts and connects them.
///
/// AirPods are always visible through their LE "Find My" broadcast. If BlueZ
/// pairs over that LE identity, pairing times out or fails with
/// `br-connection-busy`, and audio never works. So this searches with a
/// BR/EDR-only discovery filter, and pairs right away, before the AirPods leave
/// pairing mode. Returns the AirPods' name.
pub(crate) async fn pair_new_airpods() -> Result<String, String> {
    use bluer::{DiscoveryFilter, DiscoveryTransport};
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
    // AirPods use "Just Works" pairing; an agent without callbacks handles it
    // without needing another app's pairing dialog.
    let _agent = session.register_agent(bluer::agent::Agent::default()).await.ok();
    adapter
        .set_discovery_filter(DiscoveryFilter {
            transport: DiscoveryTransport::BrEdr,
            ..Default::default()
        })
        .await
        .map_err(|e| format!("Couldn't start searching: {e}"))?;
    let discovery = adapter
        .discover_devices()
        .await
        .map_err(|e| format!("Couldn't start searching: {e}"))?;

    log::info!("Pairing: searching for AirPods in pairing mode (BR/EDR)...");
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(60);
    let found = loop {
        if tokio::time::Instant::now() > deadline {
            break None;
        }
        let mut hit = None;
        for addr in adapter.device_addresses().await.unwrap_or_default() {
            if let Ok(device) = adapter.device(addr)
                && is_unpaired_airpods(&device).await
            {
                hit = Some(device);
                break;
            }
        }
        if hit.is_some() {
            break hit;
        }
        tokio::time::sleep(std::time::Duration::from_millis(700)).await;
    };
    drop(discovery); // stop searching before pairing
    let _ = adapter.set_discovery_filter(DiscoveryFilter::default()).await;

    let Some(device) = found else {
        return Err("No AirPods in pairing mode were found. Open the case near this computer and \
                    hold the button on the back until the light flashes white, then try again."
            .to_string());
    };
    let name = device.name().await.ok().flatten().unwrap_or_else(|| "AirPods".to_string());
    log::info!("Pairing: found {} ({}), pairing...", name, device.address());
    if let Err(e) = device.pair().await {
        log::warn!("Pairing {} failed: {}", device.address(), e);
        let msg = e.to_string();
        return Err(if msg.contains("busy") || msg.contains("Authentication") || msg.contains("Timeout") {
            format!("Pairing failed ({msg}). If this keeps happening, set ControllerMode = bredr in \
                     /etc/bluetooth/main.conf, restart Bluetooth, pair, then set it back to dual.")
        } else {
            format!("Pairing failed: {msg}")
        });
    }
    let _ = device.set_trusted(true).await;
    if let Err(e) = connect_device(device.address()).await {
        log::warn!("Paired {} but connecting failed: {}", device.address(), e);
    }
    log::info!("Pairing: {} paired.", name);
    Ok(name)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::devices::enums::{DeviceData, DeviceType};
    use std::collections::HashMap;

    fn device(name: &str, type_: DeviceType) -> DeviceData {
        DeviceData { name: name.into(), type_, information: None }
    }

    #[test]
    fn lists_only_airpods_sorted_by_name() {
        let devices = HashMap::from([
            ("11:11:11:11:11:11".to_string(), device("Work AirPods", DeviceType::AirPods)),
            ("22:22:22:22:22:22".to_string(), device("Ear (1)", DeviceType::Nothing)),
            ("33:33:33:33:33:33".to_string(), device("Gym AirPods", DeviceType::AirPods)),
        ]);
        assert_eq!(
            airpods_in(&devices),
            vec![
                ("33:33:33:33:33:33".to_string(), "Gym AirPods".to_string()),
                ("11:11:11:11:11:11".to_string(), "Work AirPods".to_string()),
            ]
        );
        assert!(airpods_in(&HashMap::new()).is_empty());
    }
}
