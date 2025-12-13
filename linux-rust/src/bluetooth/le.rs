use crate::bluetooth::aacp::BatteryStatus;
use crate::devices::enums::{DeviceData, DeviceInformation, DeviceType};
use crate::ui::tray::MyTray;
use crate::utils::{ah, get_devices_path, get_preferences_path};
use aes::Aes128;
use aes::cipher::generic_array::GenericArray;
use aes::cipher::{BlockDecrypt, KeyInit};
use bluer::monitor::{Monitor, MonitorEvent, Pattern};
use bluer::{Address, Session};
use futures::StreamExt;
use hex;
use log::{debug, info};
use serde_json;
use std::collections::{HashMap, HashSet};
use std::str::FromStr;
use std::sync::Arc;
use tokio::sync::Mutex;

fn decrypt(key: &[u8; 16], data: &[u8; 16]) -> [u8; 16] {
    let cipher = Aes128::new(&GenericArray::from(*key));
    let mut block = GenericArray::from(*data);
    cipher.decrypt_block(&mut block);
    block.into()
}

fn verify_rpa(addr: &str, irk: &[u8; 16]) -> bool {
    let rpa: Vec<u8> = addr
        .split(':')
        .map(|s| u8::from_str_radix(s, 16).unwrap())
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    if rpa.len() != 6 {
        return false;
    }
    let prand_slice = &rpa[3..6];
    let prand: [u8; 3] = prand_slice.try_into().unwrap();
    let hash_slice = &rpa[0..3];
    let hash: [u8; 3] = hash_slice.try_into().unwrap();
    let computed_hash = ah(irk, &prand);
    debug!(
        "Verifying RPA: addr={}, hash={:?}, computed_hash={:?}",
        addr, hash, computed_hash
    );
    hash == computed_hash
}

pub async fn start_le_monitor(tray_handle: Option<ksni::Handle<MyTray>>) -> bluer::Result<()> {
    let session = Session::new().await?;
    let adapter = session.default_adapter().await?;
    adapter.set_powered(true).await?;

    let all_devices: HashMap<String, DeviceData> = std::fs::read_to_string(get_devices_path())
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default();

    let mut verified_macs: HashMap<Address, String> = HashMap::new();
    let mut failed_macs: HashSet<Address> = HashSet::new();
    let connecting_macs = Arc::new(Mutex::new(HashSet::<Address>::new()));

    let pattern = Pattern {
        data_type: 0xFF, // Manufacturer specific data
        start_position: 0,
        content: vec![0x4C, 0x00], // Apple manufacturer ID (76) in LE
    };

    let mm = adapter.monitor().await?;
    let mut monitor_handle = mm
        .register(Monitor {
            monitor_type: bluer::monitor::Type::OrPatterns,
            rssi_low_threshold: None,
            rssi_high_threshold: None,
            rssi_low_timeout: None,
            rssi_high_timeout: None,
            rssi_sampling_period: None,
            patterns: Some(vec![pattern]),
            ..Default::default()
        })
        .await?;

    debug!("Started LE monitor");

    while let Some(mevt) = monitor_handle.next().await {
        if let MonitorEvent::DeviceFound(devid) = mevt {
            let adapter_monitor_clone = adapter.clone();
            let dev = adapter_monitor_clone.device(devid.device)?;
            let addr = dev.address();
            let addr_str = addr.to_string();

            let matched_airpods_mac: Option<String>;
            let mut matched_enc_key: Option<[u8; 16]> = None;

            if let Some(airpods_mac) = verified_macs.get(&addr) {
                matched_airpods_mac = Some(airpods_mac.clone());
            } else if failed_macs.contains(&addr) {
                continue;
            } else {
                debug!("Checking RPA for device: {}", addr_str);
                let mut found_mac = None;
                for (airpods_mac, device_data) in &all_devices {
                    if device_data.type_ == DeviceType::AirPods
                        && let Some(DeviceInformation::AirPods(info)) = &device_data.information
                        && let Ok(irk_bytes) = hex::decode(&info.le_keys.irk)
                        && irk_bytes.len() == 16
                    {
                        let irk: [u8; 16] = irk_bytes.as_slice().try_into().unwrap();
                        debug!(
                            "Verifying RPA {} for airpods MAC {} with IRK {}",
                            addr_str, airpods_mac, info.le_keys.irk
                        );
                        if verify_rpa(&addr_str, &irk) {
                            info!(
                                "Matched our device ({}) with the irk for {}",
                                addr, airpods_mac
                            );
                            verified_macs.insert(addr, airpods_mac.clone());
                            found_mac = Some(airpods_mac.clone());
                            break;
                        }
                    }
                }

                if let Some(mac) = found_mac {
                    matched_airpods_mac = Some(mac);
                } else {
                    failed_macs.insert(addr);
                    debug!("Device {} did not match any of our irks", addr);
                    continue;
                }
            }

            if let Some(ref mac) = matched_airpods_mac
                && let Some(device_data) = all_devices.get(mac)
                && let Some(DeviceInformation::AirPods(info)) = &device_data.information
                && let Ok(enc_key_bytes) = hex::decode(&info.le_keys.enc_key)
                && enc_key_bytes.len() == 16
            {
                matched_enc_key = Some(enc_key_bytes.as_slice().try_into().unwrap());
            }

            if matched_airpods_mac.is_some() {
                let mut events = dev.events().await?;
                let tray_handle_clone = tray_handle.clone();
                let connecting_macs_clone = Arc::clone(&connecting_macs);
                tokio::spawn(async move {
                    while let Some(ev) = events.next().await {
                        match ev {
                            bluer::DeviceEvent::PropertyChanged(prop) => {
                                if let bluer::DeviceProperty::ManufacturerData(data) = prop {
                                    if let Some(enc_key) = &matched_enc_key
                                        && let Some(apple_data) = data.get(&76)
                                        && apple_data.len() > 20
                                    {
                                        let last_16: [u8; 16] =
                                            apple_data[apple_data.len() - 16..].try_into().unwrap();
                                        let decrypted = decrypt(enc_key, &last_16);
                                        debug!(
                                            "Decrypted data from airpods_mac {}: {}",
                                            matched_airpods_mac
                                                .as_ref()
                                                .unwrap_or(&"unknown".to_string()),
                                            hex::encode(decrypted)
                                        );

                                        let connection_state = apple_data[10] as usize;
                                        debug!("Connection state: {}", connection_state);
                                        if connection_state == 0x00 {
                                            let pref_path = get_preferences_path();
                                            let preferences: HashMap<
                                                String,
                                                HashMap<String, bool>,
                                            > = std::fs::read_to_string(&pref_path)
                                                .ok()
                                                .and_then(|s| serde_json::from_str(&s).ok())
                                                .unwrap_or_default();
                                            let auto_connect = preferences
                                                .get(matched_airpods_mac.as_ref().unwrap())
                                                .and_then(|prefs| prefs.get("autoConnect"))
                                                .copied()
                                                .unwrap_or(true);
                                            debug!(
                                                "Auto-connect preference for {}: {}",
                                                matched_airpods_mac.as_ref().unwrap(),
                                                auto_connect
                                            );
                                            if auto_connect {
                                                let real_address =
                                                    Address::from_str(&addr_str).unwrap();
                                                let mut cm = connecting_macs_clone.lock().await;
                                                if cm.contains(&real_address) {
                                                    info!(
                                                        "Already connecting to {}, skipping duplicate attempt.",
                                                        matched_airpods_mac.as_ref().unwrap()
                                                    );
                                                    return;
                                                }
                                                cm.insert(real_address);
                                                // let adapter_clone = adapter_monitor_clone.clone();
                                                // let real_device = adapter_clone.device(real_address).unwrap();
                                                info!(
                                                    "AirPods are disconnected, attempting to connect to {}",
                                                    matched_airpods_mac.as_ref().unwrap()
                                                );
                                                // if let Err(e) = real_device.connect().await {
                                                //     info!("Failed to connect to AirPods {}: {}", matched_airpods_mac.as_ref().unwrap(), e);
                                                // } else {
                                                //     info!("Successfully connected to AirPods {}", matched_airpods_mac.as_ref().unwrap());
                                                // }
                                                // call bluetoothctl connect <mac> for now, I don't know why bluer connect isn't working
                                                let output =
                                                    tokio::process::Command::new("bluetoothctl")
                                                        .arg("connect")
                                                        .arg(matched_airpods_mac.as_ref().unwrap())
                                                        .output()
                                                        .await;
                                                match output {
                                                    Ok(output) => {
                                                        if output.status.success() {
                                                            info!(
                                                                "Successfully connected to AirPods {}",
                                                                matched_airpods_mac
                                                                    .as_ref()
                                                                    .unwrap()
                                                            );
                                                            cm.remove(&real_address);
                                                        } else {
                                                            let stderr = String::from_utf8_lossy(
                                                                &output.stderr,
                                                            );
                                                            info!(
                                                                "Failed to connect to AirPods {}: {}",
                                                                matched_airpods_mac
                                                                    .as_ref()
                                                                    .unwrap(),
                                                                stderr
                                                            );
                                                        }
                                                    }
                                                    Err(e) => {
                                                        info!(
                                                            "Failed to execute bluetoothctl to connect to AirPods {}: {}",
                                                            matched_airpods_mac.as_ref().unwrap(),
                                                            e
                                                        );
                                                    }
                                                }
                                                info!(
                                                    "Auto-connect is disabled for {}, not attempting to connect.",
                                                    matched_airpods_mac.as_ref().unwrap()
                                                );
                                            }
                                        }

                                        let status = apple_data[5] as usize;
                                        let primary_left = (status >> 5) & 0x01 == 1;
                                        let this_in_case = (status >> 6) & 0x01 == 1;
                                        let xor_factor = primary_left ^ this_in_case;
                                        let is_left_in_ear = if xor_factor {
                                            (status & 0x02) != 0
                                        } else {
                                            (status & 0x08) != 0
                                        };
                                        let is_right_in_ear = if xor_factor {
                                            (status & 0x08) != 0
                                        } else {
                                            (status & 0x02) != 0
                                        };
                                        let is_flipped = !primary_left;

                                        let left_byte_index = if is_flipped { 2 } else { 1 };
                                        let right_byte_index = if is_flipped { 1 } else { 2 };

                                        let left_byte = decrypted[left_byte_index] as i32;
                                        let right_byte = decrypted[right_byte_index] as i32;
                                        let case_byte = decrypted[3] as i32;

                                        let (left_battery, left_charging) = if left_byte == 0xff {
                                            (0, false)
                                        } else {
                                            (left_byte & 0x7F, (left_byte & 0x80) != 0)
                                        };
                                        let (right_battery, right_charging) = if right_byte == 0xff
                                        {
                                            (0, false)
                                        } else {
                                            (right_byte & 0x7F, (right_byte & 0x80) != 0)
                                        };
                                        let (case_battery, case_charging) = if case_byte == 0xff {
                                            (0, false)
                                        } else {
                                            (case_byte & 0x7F, (case_byte & 0x80) != 0)
                                        };

                                        if let Some(handle) = &tray_handle_clone {
                                            handle
                                                .update(|tray: &mut MyTray| {
                                                    tray.battery_l = if left_byte == 0xff {
                                                        None
                                                    } else {
                                                        Some(left_battery as u8)
                                                    };
                                                    tray.battery_l_status = if left_byte == 0xff {
                                                        Some(BatteryStatus::Disconnected)
                                                    } else if left_charging {
                                                        Some(BatteryStatus::Charging)
                                                    } else {
                                                        Some(BatteryStatus::NotCharging)
                                                    };
                                                    tray.battery_r = if right_byte == 0xff {
                                                        None
                                                    } else {
                                                        Some(right_battery as u8)
                                                    };
                                                    tray.battery_r_status = if right_byte == 0xff {
                                                        Some(BatteryStatus::Disconnected)
                                                    } else if right_charging {
                                                        Some(BatteryStatus::Charging)
                                                    } else {
                                                        Some(BatteryStatus::NotCharging)
                                                    };
                                                    tray.battery_c = if case_byte == 0xff {
                                                        None
                                                    } else {
                                                        Some(case_battery as u8)
                                                    };
                                                    tray.battery_c_status = if case_byte == 0xff {
                                                        Some(BatteryStatus::Disconnected)
                                                    } else if case_charging {
                                                        Some(BatteryStatus::Charging)
                                                    } else {
                                                        Some(BatteryStatus::NotCharging)
                                                    };
                                                })
                                                .await;
                                        }

                                        debug!(
                                            "Battery status: Left: {}, Right: {}, Case: {}, InEar: L:{} R:{}",
                                            if left_byte == 0xff {
                                                "disconnected".to_string()
                                            } else {
                                                format!(
                                                    "{}% (charging: {})",
                                                    left_battery, left_charging
                                                )
                                            },
                                            if right_byte == 0xff {
                                                "disconnected".to_string()
                                            } else {
                                                format!(
                                                    "{}% (charging: {})",
                                                    right_battery, right_charging
                                                )
                                            },
                                            if case_byte == 0xff {
                                                "disconnected".to_string()
                                            } else {
                                                format!(
                                                    "{}% (charging: {})",
                                                    case_battery, case_charging
                                                )
                                            },
                                            is_left_in_ear,
                                            is_right_in_ear
                                        );
                                    }
                                }
                            }
                        }
                    }
                });
            }
        }
    }

    Ok(())
}
