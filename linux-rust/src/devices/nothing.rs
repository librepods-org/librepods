use crate::bluetooth::att::{ATTHandles, ATTManager};
use crate::devices::enums::{DeviceData, DeviceInformation, DeviceType};
use crate::ui::messages::BluetoothUIMessage;
use crate::utils::{get_devices_path, load_devices, upsert_device};
use bluer::Address;
use log::{debug, error, info};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io;
use std::path::Path;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::time::sleep;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NothingInformation {
    pub serial_number: String,
    pub firmware_version: String,
}

fn merge_nothing_information<F>(current: Option<DeviceData>, update: F) -> DeviceData
where
    F: FnOnce(&mut NothingInformation),
{
    let mut device = current.unwrap_or_else(|| DeviceData {
        name: "Nothing Device".to_string(),
        type_: DeviceType::Nothing,
        information: None,
    });
    let mut information = match device.information.take() {
        Some(DeviceInformation::Nothing(information)) => information,
        _ => NothingInformation {
            serial_number: String::new(),
            firmware_version: String::new(),
        },
    };
    update(&mut information);
    device.type_ = DeviceType::Nothing;
    device.information = Some(DeviceInformation::Nothing(information));
    device
}

fn persist_nothing_information<F>(path: &Path, key: &str, update: F) -> io::Result<DeviceData>
where
    F: FnOnce(&mut NothingInformation),
{
    upsert_device(path, key, |current| {
        merge_nothing_information(current, update)
    })
}

pub struct NothingDevice {
    pub att_manager: ATTManager,
    pub information: NothingInformation,
}

impl NothingDevice {
    pub async fn new(
        mac_address: Address,
        _ui_tx: mpsc::UnboundedSender<BluetoothUIMessage>,
    ) -> Self {
        let mut att_manager = ATTManager::new();
        att_manager
            .connect(mac_address)
            .await
            .expect("Failed to connect");

        let (tx, mut rx) = mpsc::unbounded_channel::<Vec<u8>>();

        att_manager
            .register_listener(ATTHandles::NothingEverythingRead, tx)
            .await;

        let devices_path = get_devices_path();
        let devices: HashMap<String, DeviceData> =
            load_devices(&devices_path).unwrap_or_else(|error| {
                error!("Failed to load devices before Nothing initialization: {error}");
                HashMap::new()
            });
        let device_key = mac_address.to_string();
        let information = if let Some(device_data) = devices.get(&device_key) {
            let info = device_data.information.clone();
            if let Some(DeviceInformation::Nothing(ref nothing_info)) = info {
                nothing_info.clone()
            } else {
                NothingInformation {
                    serial_number: String::new(),
                    firmware_version: String::new(),
                }
            }
        } else {
            NothingInformation {
                serial_number: String::new(),
                firmware_version: String::new(),
            }
        };

        // Request version information
        att_manager
            .write(
                ATTHandles::NothingEverything,
                &[
                    0x55, 0x20, 0x01, 0x42, 0xC0, 0x00, 0x00, 0x00, 0x00,
                    0x00, // something, idk
                ],
            )
            .await
            .expect("Failed to write");

        sleep(Duration::from_millis(100)).await;

        // Request serial number
        att_manager
            .write(
                ATTHandles::NothingEverything,
                &[0x55, 0x20, 0x01, 0x06, 0xC0, 0x00, 0x00, 0x13, 0x00, 0x00],
            )
            .await
            .expect("Failed to write");

        tokio::spawn(async move {
            while let Some(data) = rx.recv().await {
                if data.starts_with(&[0x55, 0x20, 0x01, 0x42, 0x40]) {
                    let firmware_version = String::from_utf8_lossy(&data[8..]).to_string();
                    info!(
                        "Received firmware version from Nothing device {}: {}",
                        mac_address, firmware_version
                    );
                    if let Err(error) =
                        persist_nothing_information(&devices_path, &device_key, |information| {
                            information.firmware_version = firmware_version;
                        })
                    {
                        error!(
                            "Failed to persist firmware version for Nothing device {}: {}",
                            mac_address, error
                        );
                    }
                } else if data.starts_with(&[0x55, 0x20, 0x01, 0x06, 0x40]) {
                    let serial_number_start_position = data
                        .iter()
                        .position(|&b| b == "S".as_bytes()[0])
                        .unwrap_or(8);
                    let serial_number_end = data
                        .iter()
                        .skip(serial_number_start_position)
                        .position(|&b| b == 0x0A)
                        .map(|pos| pos + serial_number_start_position)
                        .unwrap_or(data.len());
                    if data.get(serial_number_start_position + 1) == Some(&"H".as_bytes()[0]) {
                        let serial_number = String::from_utf8_lossy(
                            &data[serial_number_start_position..serial_number_end],
                        )
                        .to_string();
                        info!(
                            "Received serial number from Nothing device {}: {}",
                            mac_address, serial_number
                        );
                        if let Err(error) =
                            persist_nothing_information(&devices_path, &device_key, |information| {
                                information.serial_number = serial_number;
                            })
                        {
                            error!(
                                "Failed to persist serial number for Nothing device {}: {}",
                                mac_address, error
                            );
                        }
                    } else {
                        debug!(
                            "Serial number format unexpected from Nothing device {}: {:?}",
                            mac_address, data
                        );
                    }
                }

                debug!(
                    "Received data from (Nothing) device {}, data: {:?}",
                    mac_address, data
                );
            }
        });

        NothingDevice {
            att_manager,
            information,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::utils::save_devices;
    use tempfile::tempdir;

    #[test]
    fn latest_state_upserts_keep_both_nothing_metadata_responses() {
        let temp = tempdir().unwrap();
        let devices_path = temp.path().join("librepods").join("devices.json");
        let devices = HashMap::from([
            (
                "target".to_string(),
                DeviceData {
                    name: "Ear (2)".to_string(),
                    type_: DeviceType::Nothing,
                    information: None,
                },
            ),
            (
                "unrelated".to_string(),
                DeviceData {
                    name: "Keep me".to_string(),
                    type_: DeviceType::Nothing,
                    information: None,
                },
            ),
        ]);
        save_devices(&devices_path, &devices).unwrap();

        persist_nothing_information(&devices_path, "target", |information| {
            information.firmware_version = "1.0.8".to_string();
        })
        .unwrap();
        persist_nothing_information(&devices_path, "target", |information| {
            information.serial_number = "SH123456".to_string();
        })
        .unwrap();

        let saved = load_devices(&devices_path).unwrap();
        let target = saved.get("target").unwrap();
        assert_eq!(target.name, "Ear (2)");
        assert_eq!(target.type_, DeviceType::Nothing);
        let Some(DeviceInformation::Nothing(information)) = &target.information else {
            panic!("expected Nothing device information");
        };
        assert_eq!(information.firmware_version, "1.0.8");
        assert_eq!(information.serial_number, "SH123456");
        assert_eq!(saved.get("unrelated").unwrap().name, "Keep me");
    }
}
