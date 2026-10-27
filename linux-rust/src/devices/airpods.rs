use crate::audio::SystemAudioBackend;
use crate::bluetooth::aacp::ControlCommandIdentifiers;
use crate::bluetooth::battery_provider::{BatteryReporter, system_battery_percent};
use crate::bluetooth::aacp::{AACPEvent, AACPManager, AirPodsLEKeys, ProximityKeyType};
use crate::devices::registry::ManagedDevice;
use crate::media_controller::MediaController;
use crate::devices::commands::{
    bud_from_aacp, cycle_listening_mode, effective_stem_action, press_from_aacp, reapply_on_connect,
    run_command,
};
use crate::prefs::{PrefsStore, StemAction};
use crate::prefs::load_app_settings;
use crate::ui::messages::BluetoothUIMessage;
use crate::ui::notifications::{LowBatteryTracker, Notifier, battery_summary, low_battery_text};
use crate::ui::tray::MyTray;
use crate::utils::{get_app_settings_path, get_devices_path};
use bluer::Address;
use ksni::Handle;
use log::{debug, error, info};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::task::JoinSet;
use tokio::time::{Duration, sleep};

pub struct AirPodsDevice {
    pub mac_address: Address,
    pub aacp_manager: AACPManager,
    // pub att_manager: ATTManager,
    pub media_controller: MediaController,
    // pub command_tx: Option<tokio::sync::mpsc::UnboundedSender<(ControlCommandIdentifiers, Vec<u8>)>>,
    /// Every background loop of this instance; aborted on shutdown.
    tasks: JoinSet<()>,
    battery_reporter: Option<BatteryReporter>,
}

/// What every AirPods instance shares with the rest of the app.
#[derive(Clone)]
pub struct DeviceContext {
    pub tray_handle: Option<Handle<MyTray>>,
    pub ui_tx: tokio::sync::mpsc::UnboundedSender<BluetoothUIMessage>,
    pub battery_reporter: Option<BatteryReporter>,
    pub prefs: PrefsStore,
    pub notifier: Option<Notifier>,
}

/// How long local playback leaves the AirPods alone after another device takes them.
const TAKEOVER_SUPPRESSION: Duration = Duration::from_secs(3);

impl AirPodsDevice {
    pub async fn new(mac_address: Address, ctx: DeviceContext) -> Option<Self> {
        let DeviceContext {
            tray_handle,
            ui_tx,
            battery_reporter,
            prefs,
            notifier,
        } = ctx;
        info!("Creating new AirPodsDevice for {}", mac_address);
        let mut aacp_manager = AACPManager::new();
        if !aacp_manager.connect(mac_address).await {
            error!("Could not open the AACP channel to {}", mac_address);
            return None;
        }

        // let mut att_manager = ATTManager::new();
        // att_manager.connect(mac_address).await.expect("Failed to connect ATT");

        if let Some(handle) = &tray_handle {
            handle
                .update(|tray: &mut MyTray| {
                    tray.connected = true;
                    tray.battery_headphone = None;
                    tray.battery_headphone_status = None;
                    tray.battery_l = None;
                    tray.battery_l_status = None;
                    tray.battery_r = None;
                    tray.battery_r_status = None;
                    tray.battery_c = None;
                    tray.battery_c_status = None;
                })
                .await;
        }

        info!("Sending handshake");
        if let Err(e) = aacp_manager.send_handshake().await {
            error!("Failed to send handshake to AirPods device: {}", e);
        }

        sleep(Duration::from_millis(300)).await;

        info!("Setting feature flags");
        if let Err(e) = aacp_manager.send_set_feature_flags_packet().await {
            error!("Failed to set feature flags: {}", e);
        }

        sleep(Duration::from_millis(300)).await;

        info!("Requesting notifications");
        if let Err(e) = aacp_manager.send_notification_request().await {
            error!("Failed to request notifications: {}", e);
        }

        info!("sending some packet");
        if let Err(e) = aacp_manager.send_some_packet().await {
            error!("Failed to send some packet: {}", e);
        }

        info!("Requesting Proximity Keys: IRK and ENC_KEY");
        if let Err(e) = aacp_manager
            .send_proximity_keys_request(vec![ProximityKeyType::Irk, ProximityKeyType::EncKey])
            .await
        {
            error!("Failed to request proximity keys: {}", e);
        }

        // The AirPods forget some settings when another device connects; send the user's
        // own choices again (stored listening-mode cycle, stem-press forwarding).
        for (id, value) in reapply_on_connect(&prefs.device(&mac_address.to_string())) {
            info!("Re-applying stored {:?}: {}", id, hex::encode(&value));
            if let Err(e) = aacp_manager.send_control_command(id, &value).await {
                error!("Failed to re-apply {:?}: {}", id, e);
            }
        }

        let local_mac = match local_adapter_address().await {
            Ok(address) => address.to_string(),
            Err(e) => {
                error!("Failed to get the local Bluetooth adapter address: {}", e);
                aacp_manager.disconnect().await;
                return None;
            }
        };

        let media_controller = MediaController::new(
            mac_address.to_string(),
            local_mac.clone(),
            Arc::new(SystemAudioBackend),
            prefs.clone(),
        );
        let mc_clone = media_controller.clone();
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        let (command_tx, mut command_rx) = tokio::sync::mpsc::unbounded_channel();

        aacp_manager.set_event_channel(tx).await;
        if let Some(handle) = &tray_handle {
            handle
                .update(|tray: &mut MyTray| tray.command_tx = Some(command_tx.clone()))
                .await;
        }

        let mut tasks = JoinSet::new();
        let aacp_manager_clone = aacp_manager.clone();
        tasks.spawn(async move {
            while let Some((id, value)) = command_rx.recv().await {
                if let Err(e) = aacp_manager_clone.send_control_command(id, &value).await {
                    log::error!("Failed to send control command: {}", e);
                }
            }
        });

        tasks.spawn(
            media_controller
                .clone()
                .run_playback_listener(aacp_manager.clone(), command_tx.clone()),
        );

        let (listening_mode_tx, mut listening_mode_rx) = tokio::sync::mpsc::unbounded_channel();
        aacp_manager
            .subscribe_to_control_command(
                ControlCommandIdentifiers::ListeningMode,
                listening_mode_tx,
            )
            .await;
        let tray_handle_clone = tray_handle.clone();
        tasks.spawn(async move {
            while let Some(value) = listening_mode_rx.recv().await {
                if let Some(handle) = &tray_handle_clone {
                    handle
                        .update(|tray: &mut MyTray| {
                            tray.listening_mode = Some(value[0]);
                        })
                        .await;
                }
            }
        });

        let (allow_off_tx, mut allow_off_rx) = tokio::sync::mpsc::unbounded_channel();
        aacp_manager
            .subscribe_to_control_command(ControlCommandIdentifiers::AllowOffOption, allow_off_tx)
            .await;
        let tray_handle_clone = tray_handle.clone();
        tasks.spawn(async move {
            while let Some(value) = allow_off_rx.recv().await {
                if let Some(handle) = &tray_handle_clone {
                    handle
                        .update(|tray: &mut MyTray| {
                            tray.allow_off_option = Some(value[0]);
                        })
                        .await;
                }
            }
        });

        let (conversation_detect_tx, mut conversation_detect_rx) =
            tokio::sync::mpsc::unbounded_channel();
        aacp_manager
            .subscribe_to_control_command(
                ControlCommandIdentifiers::ConversationDetectConfig,
                conversation_detect_tx,
            )
            .await;
        let tray_handle_clone = tray_handle.clone();
        tasks.spawn(async move {
            while let Some(value) = conversation_detect_rx.recv().await {
                if let Some(handle) = &tray_handle_clone {
                    handle
                        .update(|tray: &mut MyTray| {
                            tray.conversation_detect_enabled = Some(value[0] == 0x01);
                        })
                        .await;
                }
            }
        });

        let (owns_connection_tx, mut owns_connection_rx) = tokio::sync::mpsc::unbounded_channel();
        aacp_manager
            .subscribe_to_control_command(
                ControlCommandIdentifiers::OwnsConnection,
                owns_connection_tx,
            )
            .await;
        let mc_clone_owns = media_controller.clone();
        tasks.spawn(async move {
            while let Some(value) = owns_connection_rx.recv().await {
                let owns = value.first().copied().unwrap_or(0) != 0;
                if !owns {
                    info!("Lost ownership, pausing media and disconnecting audio");
                    mc_clone_owns.suppress_takeover(TAKEOVER_SUPPRESSION).await;
                    mc_clone_owns.pause_all_media().await;
                    mc_clone_owns.deactivate_a2dp_profile().await;
                }
            }
        });

        let aacp_manager_clone_events = aacp_manager.clone();
        let battery_reporter_events = battery_reporter.clone();
        let local_mac_events = local_mac.clone();
        let ui_tx_clone = ui_tx.clone();
        let command_tx_clone = command_tx.clone();
        let prefs_events = prefs.clone();
        tasks.spawn(async move {
            let mac = mac_address.to_string();
            // Desktop notifications are per connection: one "connected", one per low bud.
            let mut announced_connect = false;
            let mut low_battery = LowBatteryTracker::default();
            while let Some(event) = rx.recv().await {
                let event_clone = event.clone();
                match event {
                    AACPEvent::EarDetection(old_status, new_status) => {
                        debug!(
                            "Received EarDetection event: old_status={:?}, new_status={:?}",
                            old_status, new_status
                        );
                        debug!(
                            "Calling handle_ear_detection with old_status: {:?}, new_status: {:?}",
                            old_status, new_status
                        );
                        mc_clone
                            .handle_ear_detection(old_status, new_status)
                            .await;
                    }
                    AACPEvent::BatteryInfo(battery_info) => {
                        debug!("Received BatteryInfo event: {:?}", battery_info);
                        if let Some(handle) = &tray_handle {
                            handle
                                .update(|tray: &mut MyTray| {
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
                                })
                                .await;
                        }
                        debug!("Updated tray with new battery info");
                        if let Some(reporter) = &battery_reporter_events
                            && let Some(percent) = system_battery_percent(&battery_info)
                        {
                            reporter.update(&mac_address.to_string(), percent).await;
                        }

                        let _ = ui_tx_clone.send(BluetoothUIMessage::AACPUIEvent(
                            mac_address.to_string(),
                            event_clone,
                        ));
                        debug!("Sent BatteryInfo event to UI");

                        if let Some(notifier) = &notifier {
                            let settings = load_app_settings(&get_app_settings_path());
                            let name = saved_name(&mac).unwrap_or_else(|| "AirPods".to_string());
                            if !announced_connect {
                                announced_connect = true;
                                if settings.notify_on_connect {
                                    notifier.show_detached(
                                        &format!("connect:{}", mac),
                                        &format!("{} connected", name),
                                        &battery_summary(&battery_info),
                                    );
                                }
                            }
                            for component in low_battery.check(&battery_info) {
                                if settings.notify_low_battery
                                    && let Some(battery) =
                                        battery_info.iter().find(|b| b.component == component)
                                {
                                    notifier.show_detached(
                                        &format!("battery:{}", mac),
                                        &low_battery_text(&name, battery),
                                        "Charge them soon.",
                                    );
                                }
                            }
                        }
                    }
                    AACPEvent::DeviceInfo { model, .. } => {
                        if let Some(handle) = &tray_handle {
                            handle
                                .update(|tray: &mut MyTray| tray.model = Some(model))
                                .await;
                        }
                        let _ = ui_tx_clone.send(BluetoothUIMessage::AACPUIEvent(
                            mac_address.to_string(),
                            event_clone,
                        ));
                    }
                    AACPEvent::ControlCommand(status) => {
                        debug!("Received ControlCommand event: {:?}", status);
                        let _ = ui_tx_clone.send(BluetoothUIMessage::AACPUIEvent(
                            mac_address.to_string(),
                            event_clone,
                        ));
                        debug!("Sent ControlCommand event to UI");
                    }
                    AACPEvent::ConversationalAwareness(status) => {
                        debug!("Received ConversationalAwareness event: {}", status);
                        mc_clone.handle_conversational_awareness(status).await;
                    }
                    AACPEvent::ConnectedDevices(old_devices, new_devices) => {
                        let local_mac = local_mac_events.clone();
                        let new_devices_filtered = new_devices.iter().filter(|new_device| {
                            let not_in_old = old_devices
                                .iter()
                                .all(|old_device| old_device.mac != new_device.mac);
                            let not_local = new_device.mac != local_mac;
                            not_in_old && not_local
                        });

                        for device in new_devices_filtered {
                            info!(
                                "New connected device: {}, info1: {}, info2: {}",
                                device.mac, device.info1, device.info2
                            );
                            info!(
                                "Sending new Tipi packet for device {}, and sending media info to the device",
                                device.mac
                            );
                            let aacp_manager_clone = aacp_manager_clone_events.clone();
                            let local_mac_clone = local_mac.clone();
                            let device_mac_clone = device.mac.clone();
                            tokio::spawn(async move {
                                if let Err(e) = aacp_manager_clone
                                    .send_media_information_new_device(
                                        &local_mac_clone,
                                        &device_mac_clone,
                                    )
                                    .await
                                {
                                    error!("Failed to send media info new device: {}", e);
                                }
                                if let Err(e) = aacp_manager_clone
                                    .send_add_tipi_device(&local_mac_clone, &device_mac_clone)
                                    .await
                                {
                                    error!("Failed to send add tipi device: {}", e);
                                }
                            });
                        }
                    }
                    AACPEvent::OwnershipToFalseRequest => {
                        info!(
                            "Received ownership to false request. Setting ownership to false and pausing media."
                        );
                        let _ = command_tx_clone
                            .send((ControlCommandIdentifiers::OwnsConnection, vec![0x00]));
                        mc_clone.suppress_takeover(TAKEOVER_SUPPRESSION).await;
                        mc_clone.pause_all_media().await;
                        mc_clone.deactivate_a2dp_profile().await;
                    }
                    AACPEvent::StemPress(press_type, bud_type) => {
                        // Only press types in the forwarding mask arrive here.
                        let prefs = prefs_events.device(&mac_address.to_string());
                        let action = effective_stem_action(
                            &prefs.stem_actions,
                            bud_from_aacp(bud_type),
                            press_from_aacp(press_type),
                        );
                        info!(
                            "Stem press {:?} on {:?}: {:?}",
                            press_type, bud_type, action
                        );
                        match action {
                            StemAction::PlayPause => mc_clone.play_pause().await,
                            StemAction::NextTrack => mc_clone.next_track().await,
                            StemAction::PreviousTrack => mc_clone.previous_track().await,
                            StemAction::CycleListeningMode => {
                                cycle_listening_mode(&aacp_manager_clone_events, &prefs).await;
                            }
                            StemAction::Command(command) => run_command(&command),
                            StemAction::Native => {}
                        }
                    }
                    _ => {
                        debug!("Received unhandled AACP event: {:?}", event);
                        let _ = ui_tx_clone.send(BluetoothUIMessage::AACPUIEvent(
                            mac_address.to_string(),
                            event_clone,
                        ));
                        debug!("Sent unhandled AACP event to UI");
                    }
                }
            }
        });

        Some(AirPodsDevice {
            mac_address,
            aacp_manager,
            // att_manager,
            media_controller,
            // command_tx: Some(command_tx.clone()),
            tasks,
            battery_reporter,
        })
    }
}

/// The AirPods' name as saved in devices.json.
fn saved_name(mac: &str) -> Option<String> {
    let devices: std::collections::HashMap<String, crate::devices::enums::DeviceData> =
        serde_json::from_str(&std::fs::read_to_string(get_devices_path()).ok()?).ok()?;
    devices
        .get(mac)
        .map(|device| device.name.clone())
        .filter(|name| !name.is_empty() && name != mac)
}

async fn local_adapter_address() -> bluer::Result<Address> {
    let session = bluer::Session::new().await?;
    let adapter = session.default_adapter().await?;
    adapter.address().await
}

impl ManagedDevice for AirPodsDevice {
    fn is_alive(&self) -> bool {
        self.aacp_manager.is_connected()
    }

    fn shutdown(mut self) -> impl std::future::Future<Output = ()> + Send {
        async move {
            info!("Shutting down AirPodsDevice for {}", self.mac_address);
            self.tasks.abort_all();
            while self.tasks.join_next().await.is_some() {}
            self.aacp_manager.disconnect().await;
            if let Some(reporter) = &self.battery_reporter {
                reporter.remove(&self.mac_address.to_string()).await;
            }
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
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
    pub le_keys: AirPodsLEKeys,
}
