use crate::bluetooth::aacp::BatteryStatus;
use crate::devices::enums::{DeviceData, DeviceInformation, DeviceType};
use crate::ui::tray::MyTray;
use crate::utils::{ah, get_devices_path, get_preferences_path};
use aes::Aes128;
use aes::cipher::Array;
use aes::cipher::{BlockCipherDecrypt, KeyInit};
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
    let cipher = Aes128::new(&Array::from(*key));
    let mut block = Array::from(*data);
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

    // This used to register a BlueZ AdvertisementMonitor. That only works when the
    // kernel/controller advertises advertising-monitor support; otherwise bluetoothd logs
    //   "Adv Monitor Manager created with supported features:0x00000000"
    // and then never delivers a single DeviceFound, while RegisterMonitor still succeeds
    // (so it looks healthy and auto-connect silently never fires). Drive it from ordinary
    // discovery instead, which works on every adapter.
    let discover_adapter = adapter.clone();
    tokio::spawn(async move {
        loop {
            match discover_adapter.discover_devices().await {
                Ok(mut stream) => {
                    info!("BLE discovery started");
                    while (stream.next().await).is_some() {}
                    info!("BLE discovery stream ended, restarting");
                }
                Err(e) => log::warn!("BLE discovery error: {}", e),
            }
            tokio::time::sleep(std::time::Duration::from_secs(5)).await;
        }
    });

    let all_devices: HashMap<String, DeviceData> = std::fs::read_to_string(get_devices_path())
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default();

    let mut verified_macs: HashMap<Address, String> = HashMap::new();
    let mut failed_macs: HashMap<Address, std::time::Instant> = HashMap::new();
    let connecting_macs = Arc::new(Mutex::new(HashSet::<Address>::new()));

    loop {
        let addrs = adapter.device_addresses().await.unwrap_or_default();

        for addr in addrs {
            // Resolve (and cache) which of our devices this rotating address belongs to.
            let mac = match verified_macs.get(&addr) {
                Some(mac) => mac.clone(),
                None => {
                    if failed_macs
                        .get(&addr)
                        .is_some_and(|t| t.elapsed() < std::time::Duration::from_secs(600))
                    {
                        continue;
                    }
                    let addr_str = addr.to_string();
                    let mut found = None;
                    for (airpods_mac, device_data) in &all_devices {
                        if device_data.type_ == DeviceType::AirPods
                            && let Some(DeviceInformation::AirPods(info)) = &device_data.information
                            && let Ok(irk_bytes) = hex::decode(&info.le_keys.irk)
                            && irk_bytes.len() == 16
                        {
                            let irk: [u8; 16] = irk_bytes.as_slice().try_into().unwrap();
                            if verify_rpa(&addr_str, &irk) {
                                info!(
                                    "Matched our device ({}) with the irk for {}",
                                    addr, airpods_mac
                                );
                                found = Some(airpods_mac.clone());
                                break;
                            }
                        }
                    }
                    match found {
                        Some(mac) => {
                            verified_macs.insert(addr, mac.clone());
                            mac
                        }
                        None => {
                            failed_macs.insert(addr, std::time::Instant::now());
                            debug!("Device {} did not match any of our irks", addr);
                            continue;
                        }
                    }
                }
            };

            let Ok(dev) = adapter.device(addr) else {
                continue;
            };
            let Ok(Some(manufacturer_data)) = dev.manufacturer_data().await else {
                continue;
            };
            let Some(apple_data) = manufacturer_data.get(&76) else {
                continue;
            };
            if apple_data.len() <= 20 {
                continue;
            }

            let Some(enc_key) = all_devices
                .get(&mac)
                .and_then(|d| match &d.information {
                    Some(DeviceInformation::AirPods(info)) => {
                        hex::decode(&info.le_keys.enc_key).ok()
                    }
                    _ => None,
                })
                .and_then(|k| <[u8; 16]>::try_from(k.as_slice()).ok())
            else {
                continue;
            };

            let last_16: [u8; 16] = apple_data[apple_data.len() - 16..].try_into().unwrap();
            let decrypted = decrypt(&enc_key, &last_16);
            debug!("Decrypted data from {}: {}", mac, hex::encode(decrypted));

            let connection_state = apple_data[10] as usize;
            debug!("Connection state: {}", connection_state);

            // Auto-connect: the pods advertise as disconnected, grab them.
            if connection_state == 0x00 {
                let preferences: HashMap<String, HashMap<String, bool>> =
                    std::fs::read_to_string(get_preferences_path())
                        .ok()
                        .and_then(|s| serde_json::from_str(&s).ok())
                        .unwrap_or_default();
                let auto_connect = preferences
                    .get(&mac)
                    .and_then(|prefs| prefs.get("autoConnect"))
                    .copied()
                    .unwrap_or(true);
                debug!("Auto-connect preference for {}: {}", mac, auto_connect);
                if auto_connect {
                    let mut cm = connecting_macs.lock().await;
                    if cm.contains(&addr) {
                        debug!("Already connecting to {}, skipping duplicate attempt.", mac);
                    } else {
                        cm.insert(addr);
                        info!("AirPods are disconnected, attempting to connect to {}", mac);
                        let output = tokio::process::Command::new("bluetoothctl")
                            .arg("connect")
                            .arg(&mac)
                            .output()
                            .await;
                        match output {
                            Ok(output) if output.status.success() => {
                                info!("Successfully connected to AirPods {}", mac);
                            }
                            Ok(output) => info!(
                                "Failed to connect to AirPods {}: {}",
                                mac,
                                String::from_utf8_lossy(&output.stderr)
                            ),
                            Err(e) => info!(
                                "Failed to execute bluetoothctl to connect to AirPods {}: {}",
                                mac, e
                            ),
                        }
                        // Always release the guard, otherwise a single failed attempt blocks
                        // auto-connect for the rest of the session.
                        cm.remove(&addr);
                    }
                }
            }

            // Advertisement battery / ear state for the tray.
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

            let left_byte_index = if primary_left { 2 } else { 1 };
            let right_byte_index = if primary_left { 1 } else { 2 };
            let left_byte = decrypted[left_byte_index] as i32;
            let right_byte = decrypted[right_byte_index] as i32;
            let case_byte = decrypted[3] as i32;

            let (left_battery, left_charging) = if left_byte == 0xff {
                (0, false)
            } else {
                (left_byte & 0x7F, (left_byte & 0x80) != 0)
            };
            let (right_battery, right_charging) = if right_byte == 0xff {
                (0, false)
            } else {
                (right_byte & 0x7F, (right_byte & 0x80) != 0)
            };
            let (case_battery, case_charging) = if case_byte == 0xff {
                (0, false)
            } else {
                (case_byte & 0x7F, (case_byte & 0x80) != 0)
            };

            if let Some(handle) = &tray_handle {
                handle
                    .update(|tray: &mut MyTray| {
                        tray.battery_l = (left_byte != 0xff).then_some(left_battery as u8);
                        tray.battery_l_status = if left_byte == 0xff {
                            Some(BatteryStatus::Disconnected)
                        } else if left_charging {
                            Some(BatteryStatus::Charging)
                        } else {
                            Some(BatteryStatus::NotCharging)
                        };
                        tray.battery_r = (right_byte != 0xff).then_some(right_battery as u8);
                        tray.battery_r_status = if right_byte == 0xff {
                            Some(BatteryStatus::Disconnected)
                        } else if right_charging {
                            Some(BatteryStatus::Charging)
                        } else {
                            Some(BatteryStatus::NotCharging)
                        };
                        tray.battery_c = (case_byte != 0xff).then_some(case_battery as u8);
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
                "Advertisement battery: L={}% R={}% C={}%, in ear L={} R={}",
                left_battery, right_battery, case_battery, is_left_in_ear, is_right_in_ear
            );
        }

        failed_macs.retain(|_, t| t.elapsed() < std::time::Duration::from_secs(600));
        tokio::time::sleep(std::time::Duration::from_secs(3)).await;
    }
}
