use crate::bluetooth::aacp::{AACPManager, ProximityKeyType, AACPEvent, AirPodsLEKeys};
use crate::bluetooth::aacp::ControlCommandIdentifiers;
use crate::media_controller::MediaController;
use bluer::Address;
use log::{debug, info, error};
use std::sync::Arc;
use ksni::Handle;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;
use tokio::time::{sleep, Duration};
use crate::ui::tray::MyTray;
use crate::ui::messages::BluetoothUIMessage;

pub struct AirPodsDevice {
    pub mac_address: Address,
    pub aacp_manager: AACPManager,
    // pub att_manager: ATTManager,
    pub media_controller: Arc<Mutex<MediaController>>,
    // pub command_tx: Option<tokio::sync::mpsc::UnboundedSender<(ControlCommandIdentifiers, Vec<u8>)>>,
}

impl AirPodsDevice {
    pub async fn new(
        mac_address: Address,
        tray_handle: Option<Handle<MyTray>>,
        ui_tx: tokio::sync::mpsc::UnboundedSender<BluetoothUIMessage>,
    ) -> Self {
        info!("Creating new AirPodsDevice for {}", mac_address);
        let mut aacp_manager = AACPManager::new();
        aacp_manager.connect(mac_address).await;

        // let mut att_manager = ATTManager::new();
        // att_manager.connect(mac_address).await.expect("Failed to connect ATT");

        if let Some(handle) = &tray_handle {
            handle.update(|tray: &mut MyTray| tray.connected = true).await;
        }

        info!("Sending handshake");
        if let Err(e) = aacp_manager.send_handshake().await {
            error!("Failed to send handshake to AirPods device: {}", e);
        }

        sleep(Duration::from_millis(100)).await;

        info!("Setting feature flags");
        if let Err(e) = aacp_manager.send_set_feature_flags_packet().await {
            error!("Failed to set feature flags: {}", e);
        }

        sleep(Duration::from_millis(100)).await;

        info!("Requesting notifications");
        if let Err(e) = aacp_manager.send_notification_request().await {
            error!("Failed to request notifications: {}", e);
        }

        info!("sending some packet");
        if let Err(e) = aacp_manager.send_some_packet().await {
            error!("Failed to send some packet: {}", e);
        }

        info!("Requesting Proximity Keys: IRK and ENC_KEY");
        if let Err(e) = aacp_manager.send_proximity_keys_request(
            vec![ProximityKeyType::Irk, ProximityKeyType::EncKey],
        ).await {
            error!("Failed to request proximity keys: {}", e);
        }

        let session = bluer::Session::new().await.expect("Failed to get bluer session");
        let adapter = session.default_adapter().await.expect("Failed to get default adapter");
        let local_mac = adapter.address().await.expect("Failed to get adapter address").to_string();

        let media_controller = Arc::new(Mutex::new(MediaController::new(mac_address.to_string(), local_mac.clone())));
        let mc_clone = media_controller.clone();
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        let (command_tx, mut command_rx) = tokio::sync::mpsc::unbounded_channel();

        aacp_manager.set_event_channel(tx).await;
        if let Some(handle) = &tray_handle {
            handle.update(|tray: &mut MyTray| tray.command_tx = Some(command_tx.clone())).await;
        }

        let aacp_manager_clone = aacp_manager.clone();
        tokio::spawn(async move {
            while let Some((id, value)) = command_rx.recv().await {
                if let Err(e) = aacp_manager_clone.send_control_command(id, &value).await {
                    log::error!("Failed to send control command: {}", e);
                }
            }
        });

        let mc_listener = media_controller.lock().await;
        let aacp_manager_clone_listener = aacp_manager.clone();
        mc_listener.start_playback_listener(aacp_manager_clone_listener, command_tx.clone()).await;
        drop(mc_listener);

        let (listening_mode_tx, mut listening_mode_rx) = tokio::sync::mpsc::unbounded_channel();
        aacp_manager.subscribe_to_control_command(ControlCommandIdentifiers::ListeningMode, listening_mode_tx).await;
        let tray_handle_clone = tray_handle.clone();
        tokio::spawn(async move {
            while let Some(value) = listening_mode_rx.recv().await {
                if let Some(handle) = &tray_handle_clone {
                    handle.update(|tray: &mut MyTray| {
                        tray.listening_mode = Some(value[0]);
                    }).await;
                }
            }
        });

        let (allow_off_tx, mut allow_off_rx) = tokio::sync::mpsc::unbounded_channel();
        aacp_manager.subscribe_to_control_command(ControlCommandIdentifiers::AllowOffOption, allow_off_tx).await;
        let tray_handle_clone = tray_handle.clone();
        tokio::spawn(async move {
            while let Some(value) = allow_off_rx.recv().await {
                if let Some(handle) = &tray_handle_clone {
                    handle.update(|tray: &mut MyTray| {
                        tray.allow_off_option = Some(value[0]);
                    }).await;
                }
            }
        });

        let (conversation_detect_tx, mut conversation_detect_rx) = tokio::sync::mpsc::unbounded_channel();
        aacp_manager.subscribe_to_control_command(ControlCommandIdentifiers::ConversationDetectConfig, conversation_detect_tx).await;
        let tray_handle_clone = tray_handle.clone();
        tokio::spawn(async move {
            while let Some(value) = conversation_detect_rx.recv().await {
                if let Some(handle) = &tray_handle_clone {
                    handle.update(|tray: &mut MyTray| {
                        tray.conversation_detect_enabled = Some(value[0] == 0x01);
                    }).await;
                }
            }
        });

        let (owns_connection_tx, mut owns_connection_rx) = tokio::sync::mpsc::unbounded_channel();
        aacp_manager.subscribe_to_control_command(ControlCommandIdentifiers::OwnsConnection, owns_connection_tx).await;
        let mc_clone_owns = media_controller.clone();
        tokio::spawn(async move {
            while let Some(value) = owns_connection_rx.recv().await {
                let owns = value.first().copied().unwrap_or(0) != 0;
                if !owns {
                    info!("Lost ownership, pausing media and disconnecting audio");
                    let controller = mc_clone_owns.lock().await;
                    controller.pause_all_media().await;
                    controller.deactivate_a2dp_profile().await;
                }
            }
        });

        let aacp_manager_clone_events = aacp_manager.clone();
        let local_mac_events = local_mac.clone();
        let ui_tx_clone = ui_tx.clone();
        let command_tx_clone = command_tx.clone();
        tokio::spawn(async move {
            while let Some(event) = rx.recv().await {
                let event_clone = event.clone();
                match event {
                    AACPEvent::EarDetection(old_status, new_status) => {
                        debug!("Received EarDetection event: old_status={:?}, new_status={:?}", old_status, new_status);
                        let controller = mc_clone.lock().await;
                        debug!("Calling handle_ear_detection with old_status: {:?}, new_status: {:?}", old_status, new_status);
                        controller.handle_ear_detection(old_status, new_status).await;
                    }
                    AACPEvent::BatteryInfo(battery_info) => {
                        debug!("Received BatteryInfo event: {:?}", battery_info);
                        if let Some(handle) = &tray_handle {
                            handle.update(|tray: &mut MyTray| {
                                for b in &battery_info {
                                    match b.component as u8 {
                                        0x01 => {
                                            tray.battery_headphone = Some(b.level);
                                            tray.battery_headphone_status = Some(b.status);
                                        }
                                        0x02 => {
                                            tray.battery_r = Some(b.level);
                                            tray.battery_r_status = Some(b.status);
                                        }
                                        0x04 => {
                                            tray.battery_l = Some(b.level);
                                            tray.battery_l_status = Some(b.status);
                                        }
                                        0x08 => {
                                            tray.battery_c = Some(b.level);
                                            tray.battery_c_status = Some(b.status);
                                        }
                                        _ => {}
                                    }
                                }
                            }).await;
                        }
                        debug!("Updated tray with new battery info");

                        let _ = ui_tx_clone.send(BluetoothUIMessage::AACPUIEvent(mac_address.to_string(), event_clone));
                        debug!("Sent BatteryInfo event to UI");
                    }
                    AACPEvent::ControlCommand(status) => {
                        debug!("Received ControlCommand event: {:?}", status);
                        let _ = ui_tx_clone.send(BluetoothUIMessage::AACPUIEvent(mac_address.to_string(), event_clone));
                        debug!("Sent ControlCommand event to UI");
                    }
                    AACPEvent::ConversationalAwareness(status) => {
                        debug!("Received ConversationalAwareness event: {}", status);
                        let controller = mc_clone.lock().await;
                        controller.handle_conversational_awareness(status).await;
                    }
                    AACPEvent::ConnectedDevices(old_devices, new_devices) => {
                        let local_mac = local_mac_events.clone();
                        let new_devices_filtered = new_devices.iter().filter(|new_device| {
                            let not_in_old = old_devices.iter().all(|old_device| old_device.mac != new_device.mac);
                            let not_local = new_device.mac != local_mac;
                            not_in_old && not_local
                        });

                        for device in new_devices_filtered {
                            info!("New connected device: {}, info1: {}, info2: {}", device.mac, device.info1, device.info2);
                            info!("Sending new Tipi packet for device {}, and sending media info to the device", device.mac);
                            let aacp_manager_clone = aacp_manager_clone_events.clone();
                            let local_mac_clone = local_mac.clone();
                            let device_mac_clone = device.mac.clone();
                            tokio::spawn(async move {
                                if let Err(e) = aacp_manager_clone.send_media_information_new_device(&local_mac_clone, &device_mac_clone).await {
                                    error!("Failed to send media info new device: {}", e);
                                }
                                if let Err(e) = aacp_manager_clone.send_add_tipi_device(&local_mac_clone, &device_mac_clone).await {
                                    error!("Failed to send add tipi device: {}", e);
                                }
                            });
                        }
                    }
                    AACPEvent::OwnershipToFalseRequest => {
                        info!("Received ownership to false request. Setting ownership to false and pausing media.");
                        let _ = command_tx_clone.send((ControlCommandIdentifiers::OwnsConnection, vec![0x00]));
                        let controller = mc_clone.lock().await;
                        controller.pause_all_media().await;
                        controller.deactivate_a2dp_profile().await;
                    }
                    _ => {
                        debug!("Received unhandled AACP event: {:?}", event);
                        let _ = ui_tx_clone.send(BluetoothUIMessage::AACPUIEvent(mac_address.to_string(), event_clone));
                        debug!("Sent unhandled AACP event to UI");
                    }
                }
            }
        });

        AirPodsDevice {
            mac_address,
            aacp_manager,
            // att_manager,
            media_controller,
            // command_tx: Some(command_tx.clone()),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AirPodsInformation {
    pub name: String,
    pub model_number: String,
    pub manufacturer: String,
    pub serial_number: String,
    pub version1: String,
    pub version2: String,
    pub hardware_revision: String,
    pub updater_identifier: String,
    pub left_serial_number: String,
    pub right_serial_number: String,
    pub version3: String,
    pub le_keys: AirPodsLEKeys
}