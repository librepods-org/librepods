use std::collections::HashMap;
use std::time::Duration;
use bluer::Address;
use log::{debug, info};
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use tokio::time::sleep;
use crate::bluetooth::att::{ATTHandles, ATTManager};
use crate::devices::enums::{DeviceData, DeviceInformation, DeviceType};
use crate::ui::messages::BluetoothUIMessage;
use crate::utils::get_devices_path;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NothingInformation{
    pub serial_number: String,
    pub firmware_version: String
}

pub struct NothingDevice{
    pub att_manager: ATTManager,
    pub information: NothingInformation
}

impl NothingDevice{
    pub async fn new(
        mac_address: Address,
        ui_tx: mpsc::UnboundedSender<BluetoothUIMessage>
    ) -> Self {
        let mut att_manager = ATTManager::new();
        att_manager.connect(mac_address).await.expect("Failed to connect");

        let (tx, mut rx) = mpsc::unbounded_channel::<Vec<u8>>();

        att_manager.register_listener(
            ATTHandles::NothingEverythingRead,
            tx
        ).await;

        let devices: HashMap<String, DeviceData> =
            std::fs::read_to_string(get_devices_path())
                .ok()
                .and_then(|s| serde_json::from_str(&s).ok())
                .unwrap_or_default();
        let device_key = mac_address.to_string();
        let information = if let Some(device_data) = devices.get(&device_key) {
            let info = device_data.information.clone();
            if let Some(DeviceInformation::Nothing(ref nothing_info)) = info {
                nothing_info.clone()
            } else {
                NothingInformation{
                    serial_number: String::new(),
                    firmware_version: String::new()
                }
            }
        } else {
            NothingInformation{
                serial_number: String::new(),
                firmware_version: String::new()
            }
        };

        // Request version information
        att_manager.write(
            ATTHandles::NothingEverything,
            &[
                0x55, 0x20,
                0x01, 0x42,
                0xC0, 0x00,
                0x00, 0x00,
                0x00, 0x00 // something, idk
            ]
        ).await.expect("Failed to write");

        sleep(Duration::from_millis(100)).await;

        // Request serial number
        att_manager.write(
            ATTHandles::NothingEverything,
            &[
                0x55, 0x20,
                0x01, 0x06,
                0xC0, 0x00,
                0x00, 0x13,
                0x00, 0x00
            ]
        ).await.expect("Failed to write");

        // let ui_tx_clone = ui_tx.clone();
        let information_l = information.clone();
        tokio::spawn(async move {
            while let Some(data) = rx.recv().await {
                if data.starts_with(&[
                    0x55, 0x20,
                    0x01, 0x42, 0x40
                ]) {
                    let firmware_version = String::from_utf8_lossy(&data[8..]).to_string();
                    info!("Received firmware version from Nothing device {}: {}", mac_address, firmware_version);
                    let new_information = NothingInformation{
                        serial_number: information_l.serial_number.clone(),
                        firmware_version: firmware_version.clone()
                    };
                    let mut new_devices = devices.clone();
                    new_devices.insert(
                        device_key.clone(),
                        DeviceData{
                            name: devices.get(&device_key)
                                .map(|d| d.name.clone())
                                .unwrap_or("Nothing Device".to_string()),
                            type_: devices.get(&device_key)
                                .map(|d| d.type_.clone())
                                .unwrap_or(DeviceType::Nothing),
                            information: Some(DeviceInformation::Nothing(new_information)),
                        }
                    );
                    let json = serde_json::to_string(&new_devices).unwrap();
                    std::fs::write(get_devices_path(), json).expect("Failed to write devices file");
                } else if data.starts_with(
                    &[
                        0x55, 0x20,
                        0x01, 0x06, 0x40
                    ]
                ) {
                    let serial_number_start_position = data.iter().position(|&b| b == "S".as_bytes()[0]).unwrap_or(8);
                    let serial_number_end = data.iter()
                        .skip(serial_number_start_position)
                        .position(|&b| b == 0x0A)
                        .map(|pos| pos + serial_number_start_position)
                        .unwrap_or(data.len());
                    if data.get(serial_number_start_position + 1) == Some(&"H".as_bytes()[0]) {
                        let serial_number = String::from_utf8_lossy(
                            &data[serial_number_start_position..serial_number_end]
                        ).to_string();
                        info!("Received serial number from Nothing device {}: {}", mac_address, serial_number);
                        let new_information = NothingInformation{
                            serial_number: serial_number.clone(),
                            firmware_version: information_l.firmware_version.clone()
                        };
                        let mut new_devices = devices.clone();
                        new_devices.insert(
                            device_key.clone(),
                            DeviceData{
                                name: devices.get(&device_key)
                                    .map(|d| d.name.clone())
                                    .unwrap_or("Nothing Device".to_string()),
                                type_: devices.get(&device_key)
                                    .map(|d| d.type_.clone())
                                    .unwrap_or(DeviceType::Nothing),
                                information: Some(DeviceInformation::Nothing(new_information)),
                            }
                        );
                        let json = serde_json::to_string(&new_devices).unwrap();
                        std::fs::write(get_devices_path(), json).expect("Failed to write devices file");
                    } else {
                        debug!("Serial number format unexpected from Nothing device {}: {:?}", mac_address, data);
                    }
                }
                
                debug!("Received data from (Nothing) device {}, data: {:?}", mac_address, data);
            }
        });

        NothingDevice{
            att_manager,
            information
        }
    }
}