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
