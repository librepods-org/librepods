use crate::bluetooth::aacp::ControlCommandIdentifiers;
use crate::bluetooth::aacp::{AACPEvent, AACPManager, AirPodsLEKeys, ProximityKeyType};
use crate::media_controller::MediaController;
use crate::ui::messages::BluetoothUIMessage;
use crate::ui::tray::{MyTray, run_tray_command_worker};
use crate::utils::get_app_settings_path;
use bluer::Address;
use ksni::Handle;
use log::{debug, error, info};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::sync::Mutex;
use tokio::time::{Duration, sleep};

const OWNERSHIP_ACKNOWLEDGEMENT_TIMEOUT: Duration = Duration::from_secs(1);

async fn guarded_ownership_cleanup<
    CurrentBeforePause,
    Pause,
    CurrentBeforeDeactivation,
    Deactivation,
>(
    current_before_pause: CurrentBeforePause,
    pause: Pause,
    current_before_deactivation: CurrentBeforeDeactivation,
    deactivation: Deactivation,
) -> bool
where
    CurrentBeforePause: std::future::Future<Output = bool>,
    Pause: std::future::Future<Output = bool>,
    CurrentBeforeDeactivation: std::future::Future<Output = bool>,
    Deactivation: std::future::Future<Output = ()>,
{
    if !current_before_pause.await {
        return false;
    }
    if !pause.await {
        return false;
    }
    if !current_before_deactivation.await {
        return false;
    }
    deactivation.await;
    true
}

async fn cleanup_then_bounded_acknowledgement<
    CurrentBeforePause,
    Pause,
    CurrentBeforeDeactivation,
    Deactivation,
    CurrentBeforeAcknowledgement,
    Acknowledgement,
    Error,
>(
    current_before_pause: CurrentBeforePause,
    pause: Pause,
    current_before_deactivation: CurrentBeforeDeactivation,
    deactivation: Deactivation,
    current_before_acknowledgement: CurrentBeforeAcknowledgement,
    acknowledgement: Acknowledgement,
    timeout: Duration,
) -> Result<Option<Result<(), Error>>, tokio::time::error::Elapsed>
where
    CurrentBeforePause: std::future::Future<Output = bool>,
    Pause: std::future::Future<Output = bool>,
    CurrentBeforeDeactivation: std::future::Future<Output = bool>,
    Deactivation: std::future::Future<Output = ()>,
    CurrentBeforeAcknowledgement: std::future::Future<Output = bool>,
    Acknowledgement: std::future::Future<Output = Result<(), Error>>,
{
    if !guarded_ownership_cleanup(
        current_before_pause,
        pause,
        current_before_deactivation,
        deactivation,
    )
    .await
    {
        return Ok(None);
    }
    if !current_before_acknowledgement.await {
        return Ok(None);
    }
    tokio::time::timeout(timeout, acknowledgement)
        .await
        .map(Some)
}

async fn ownership_request_is_current(
    aacp_manager: &AACPManager,
    media_controller: &MediaController,
    connection_generation: u64,
) -> bool {
    aacp_manager
        .is_connection_generation_current(connection_generation)
        .await
        && media_controller.is_current_playback_session()
}

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

        let app_settings_path = get_app_settings_path();
        let settings = std::fs::read_to_string(&app_settings_path)
            .ok()
            .and_then(|s| serde_json::from_str::<serde_json::Value>(&s).ok());
        let stem_control = settings
            .clone()
            .and_then(|v| v.get("stem_control").cloned())
            .and_then(|s| serde_json::from_value(s).ok())
            .unwrap_or(false);

        if stem_control {
            // Enable stem press detection (double and triple tap)
            // StemConfig bitmask for the control command: single=0x01, double=0x02, triple=0x04, long=0x08
            // We want double and triple: 0x02 | 0x04 = 0x06
            // Note: these bitmask values differ from the StemPressType event enum values (0x05–0x08)
            info!("Enabling stem press detection for double and triple tap");
            if let Err(e) = aacp_manager
                .send_control_command(ControlCommandIdentifiers::StemConfig, &[0x06])
                .await
            {
                error!("Failed to enable stem press detection: {}", e);
            }
        }

        let session = bluer::Session::new()
            .await
            .expect("Failed to get bluer session");
        let adapter = session
            .default_adapter()
            .await
            .expect("Failed to get default adapter");
        let local_mac = adapter
            .address()
            .await
            .expect("Failed to get adapter address")
            .to_string();

        let media_controller = Arc::new(Mutex::new(MediaController::new(
            mac_address.to_string(),
            local_mac.clone(),
        )));
        let mc_clone = media_controller.clone();
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        let (command_tx, mut command_rx) = tokio::sync::mpsc::unbounded_channel();

        aacp_manager.set_event_channel(tx).await;
        if let Some(handle) = &tray_handle {
            handle
                .update(|tray: &mut MyTray| tray.command_tx = Some(command_tx.clone()))
                .await;
        }

        let aacp_manager_clone = aacp_manager.clone();
        let command_tray_handle = tray_handle.clone();
        tokio::spawn(run_tray_command_worker(
            command_rx,
            move |identifier, value, deadline| {
                let aacp_manager = aacp_manager_clone.clone();
                async move {
                    aacp_manager
                        .send_tray_control_command(identifier, &value, deadline)
                        .await
                }
            },
            move |request_key| {
                let tray_handle = command_tray_handle.clone();
                async move {
                    if let Some(handle) = tray_handle {
                        handle
                            .update(move |tray: &mut MyTray| {
                                tray.clear_pending_request(request_key);
                            })
                            .await;
                    }
                }
            },
        ));

        let mc_listener = media_controller.lock().await;
        let aacp_manager_clone_listener = aacp_manager.clone();
        mc_listener
            .start_playback_listener(aacp_manager_clone_listener)
            .await;
        drop(mc_listener);

        let (listening_mode_tx, mut listening_mode_rx) = tokio::sync::mpsc::unbounded_channel();
        aacp_manager
            .subscribe_to_control_command(
                ControlCommandIdentifiers::ListeningMode,
                listening_mode_tx,
            )
            .await;
        let tray_handle_clone = tray_handle.clone();
        tokio::spawn(async move {
            while let Some(value) = listening_mode_rx.recv().await {
                if let Some(mode) = value.first().copied()
                    && let Some(handle) = &tray_handle_clone
                {
                    handle
                        .update(move |tray: &mut MyTray| {
                            tray.apply_listening_mode_report(mode);
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
        tokio::spawn(async move {
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
        tokio::spawn(async move {
            while let Some(value) = conversation_detect_rx.recv().await {
                if let Some(handle) = &tray_handle_clone {
                    handle
                        .update(|tray: &mut MyTray| {
                            tray.apply_conversation_detection_report(value[0] == 0x01);
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
        tokio::spawn(async move {
            while let Some(value) = owns_connection_rx.recv().await {
                let owns = value.first().copied().unwrap_or(0) != 0;
                if !owns {
                    info!("Lost ownership, pausing media and disconnecting audio");
                    let controller = mc_clone_owns.lock().await.clone();
                    let cleanup_completed = guarded_ownership_cleanup(
                        async { controller.is_current_playback_session() },
                        controller.pause_all_media(),
                        async { controller.is_current_playback_session() },
                        async {
                            if let Err(error) = controller.deactivate_a2dp_profile().await {
                                error!(
                                    "Failed to deactivate A2DP after ownership loss: {:?}",
                                    error
                                );
                            }
                        },
                    )
                    .await;
                    if !cleanup_completed {
                        debug!("Skipping ownership-loss cleanup for a replaced media session");
                    }
                }
            }
        });

        let aacp_manager_clone_events = aacp_manager.clone();
        let local_mac_events = local_mac.clone();
        let ui_tx_clone = ui_tx.clone();
        tokio::spawn(async move {
            while let Some(event) = rx.recv().await {
                let event_clone = event.clone();
                match event {
                    AACPEvent::EarDetection(old_status, new_status) => {
                        debug!(
                            "Received EarDetection event: old_status={:?}, new_status={:?}",
                            old_status, new_status
                        );
                        let controller = mc_clone.lock().await;
                        debug!(
                            "Calling handle_ear_detection with old_status: {:?}, new_status: {:?}",
                            old_status, new_status
                        );
                        controller
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

                        let _ = ui_tx_clone.send(BluetoothUIMessage::AACPUIEvent(
                            mac_address.to_string(),
                            event_clone,
                        ));
                        debug!("Sent BatteryInfo event to UI");
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
                        let controller = mc_clone.lock().await;
                        controller.handle_conversational_awareness(status).await;
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
                    AACPEvent::OwnershipToFalseRequest {
                        connection_generation,
                    } => {
                        info!(
                            "Received ownership to false request. Pausing media before acknowledging ownership loss."
                        );
                        let controller = mc_clone.lock().await.clone();
                        let deactivation = async {
                            if let Err(error) = controller.deactivate_a2dp_profile().await {
                                error!(
                                    "Failed to deactivate A2DP after ownership request: {:?}",
                                    error
                                );
                            }
                        };
                        let acknowledgement = aacp_manager_clone_events
                            .send_control_command_for_generation(
                                ControlCommandIdentifiers::OwnsConnection,
                                &[0x00],
                                connection_generation,
                            );
                        match cleanup_then_bounded_acknowledgement(
                            ownership_request_is_current(
                                &aacp_manager_clone_events,
                                &controller,
                                connection_generation,
                            ),
                            controller.pause_all_media(),
                            ownership_request_is_current(
                                &aacp_manager_clone_events,
                                &controller,
                                connection_generation,
                            ),
                            deactivation,
                            ownership_request_is_current(
                                &aacp_manager_clone_events,
                                &controller,
                                connection_generation,
                            ),
                            acknowledgement,
                            OWNERSHIP_ACKNOWLEDGEMENT_TIMEOUT,
                        )
                        .await
                        {
                            Ok(Some(Ok(()))) => {}
                            Ok(Some(Err(error))) => {
                                error!("Failed to relinquish AACP ownership: {}", error)
                            }
                            Ok(None) => debug!(
                                "Skipping ownership request from a stale connection or media session"
                            ),
                            Err(_) => error!(
                                "Timed out while acknowledging AACP ownership relinquishment"
                            ),
                        }
                    }
                    AACPEvent::StemPress(press_type, bud_type) => {
                        use crate::bluetooth::aacp::StemPressType;
                        info!("Received Stem Press: {:?} on {:?}", press_type, bud_type);
                        if stem_control {
                            let controller = mc_clone.lock().await;
                            match press_type {
                                StemPressType::DoublePress => {
                                    info!("Double press detected, skipping to next track");
                                    controller.next_track().await;
                                }
                                StemPressType::TriplePress => {
                                    info!("Triple press detected, going to previous track");
                                    controller.previous_track().await;
                                }
                                _ => {
                                    debug!("Unhandled stem press type: {:?}", press_type);
                                }
                            }
                        } else {
                            debug!("Stem control disabled, ignoring stem press event");
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
    pub le_keys: AirPodsLEKeys,
}

#[cfg(test)]
mod tests {
    use super::{cleanup_then_bounded_acknowledgement, guarded_ownership_cleanup};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{Arc, Mutex as StdMutex};
    use tokio::time::Duration;

    #[tokio::test]
    async fn ownership_cleanup_finishes_before_acknowledgement_is_polled() {
        let actions = Arc::new(StdMutex::new(Vec::new()));
        let pause_actions = Arc::clone(&actions);
        let deactivation_actions = Arc::clone(&actions);
        let acknowledgement_actions = Arc::clone(&actions);

        let outcome = cleanup_then_bounded_acknowledgement(
            async { true },
            async move {
                pause_actions.lock().unwrap().push("pause");
                true
            },
            async { true },
            async move {
                deactivation_actions.lock().unwrap().push("deactivation");
            },
            async { true },
            async move {
                acknowledgement_actions
                    .lock()
                    .unwrap()
                    .push("acknowledgement");
                Ok::<(), &'static str>(())
            },
            Duration::from_millis(50),
        )
        .await;

        assert!(matches!(outcome, Ok(Some(Ok(())))));
        assert_eq!(
            actions.lock().unwrap().as_slice(),
            ["pause", "deactivation", "acknowledgement"]
        );
    }

    #[tokio::test]
    async fn ownership_acknowledgement_timeout_does_not_skip_cleanup() {
        let cleanup_finished = Arc::new(AtomicBool::new(false));
        let cleanup_flag = Arc::clone(&cleanup_finished);

        let outcome = cleanup_then_bounded_acknowledgement(
            async { true },
            async { true },
            async { true },
            async move {
                cleanup_flag.store(true, Ordering::SeqCst);
            },
            async { true },
            std::future::pending::<Result<(), &'static str>>(),
            Duration::from_millis(10),
        )
        .await;

        assert!(outcome.is_err());
        assert!(cleanup_finished.load(Ordering::SeqCst));
    }

    #[tokio::test]
    async fn stale_ownership_request_before_pause_skips_all_side_effects() {
        let actions = Arc::new(StdMutex::new(Vec::new()));
        let pause_actions = Arc::clone(&actions);
        let deactivation_actions = Arc::clone(&actions);
        let acknowledgement_actions = Arc::clone(&actions);

        let outcome = cleanup_then_bounded_acknowledgement(
            async { false },
            async move {
                pause_actions.lock().unwrap().push("pause");
                true
            },
            async { true },
            async move {
                deactivation_actions.lock().unwrap().push("deactivation");
            },
            async { true },
            async move {
                acknowledgement_actions
                    .lock()
                    .unwrap()
                    .push("acknowledgement");
                Ok::<(), &'static str>(())
            },
            Duration::from_millis(50),
        )
        .await;

        assert!(matches!(outcome, Ok(None)));
        assert!(actions.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn replaced_media_session_after_pause_skips_deactivation_and_acknowledgement() {
        let session_current = Arc::new(AtomicBool::new(true));
        let session_before_pause = Arc::clone(&session_current);
        let session_during_pause = Arc::clone(&session_current);
        let session_before_deactivation = Arc::clone(&session_current);
        let actions = Arc::new(StdMutex::new(Vec::new()));
        let pause_actions = Arc::clone(&actions);
        let deactivation_actions = Arc::clone(&actions);
        let acknowledgement_actions = Arc::clone(&actions);

        let outcome = cleanup_then_bounded_acknowledgement(
            async move { session_before_pause.load(Ordering::SeqCst) },
            async move {
                pause_actions.lock().unwrap().push("pause");
                session_during_pause.store(false, Ordering::SeqCst);
                true
            },
            async move { session_before_deactivation.load(Ordering::SeqCst) },
            async move {
                deactivation_actions.lock().unwrap().push("deactivation");
            },
            async { true },
            async move {
                acknowledgement_actions
                    .lock()
                    .unwrap()
                    .push("acknowledgement");
                Ok::<(), &'static str>(())
            },
            Duration::from_millis(50),
        )
        .await;

        assert!(matches!(outcome, Ok(None)));
        assert_eq!(actions.lock().unwrap().as_slice(), ["pause"]);
    }

    #[tokio::test]
    async fn stale_connection_before_acknowledgement_does_not_acknowledge_replacement() {
        let connection_current = Arc::new(AtomicBool::new(true));
        let connection_during_deactivation = Arc::clone(&connection_current);
        let connection_before_acknowledgement = Arc::clone(&connection_current);
        let actions = Arc::new(StdMutex::new(Vec::new()));
        let pause_actions = Arc::clone(&actions);
        let deactivation_actions = Arc::clone(&actions);
        let acknowledgement_actions = Arc::clone(&actions);

        let outcome = cleanup_then_bounded_acknowledgement(
            async { true },
            async move {
                pause_actions.lock().unwrap().push("pause");
                true
            },
            async { true },
            async move {
                deactivation_actions.lock().unwrap().push("deactivation");
                connection_during_deactivation.store(false, Ordering::SeqCst);
            },
            async move { connection_before_acknowledgement.load(Ordering::SeqCst) },
            async move {
                acknowledgement_actions
                    .lock()
                    .unwrap()
                    .push("acknowledgement");
                Ok::<(), &'static str>(())
            },
            Duration::from_millis(50),
        )
        .await;

        assert!(matches!(outcome, Ok(None)));
        assert_eq!(
            actions.lock().unwrap().as_slice(),
            ["pause", "deactivation"]
        );
    }

    #[tokio::test]
    async fn skipped_fenced_pause_stops_ownership_loss_cleanup() {
        let actions = Arc::new(StdMutex::new(Vec::new()));
        let deactivation_actions = Arc::clone(&actions);

        let cleanup_completed = guarded_ownership_cleanup(
            async { true },
            async { false },
            async { true },
            async move {
                deactivation_actions.lock().unwrap().push("deactivation");
            },
        )
        .await;

        assert!(!cleanup_completed);
        assert!(actions.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn skipped_fenced_pause_stops_ownership_request_cleanup() {
        let actions = Arc::new(StdMutex::new(Vec::new()));
        let deactivation_actions = Arc::clone(&actions);
        let acknowledgement_actions = Arc::clone(&actions);

        let outcome = cleanup_then_bounded_acknowledgement(
            async { true },
            async { false },
            async { true },
            async move {
                deactivation_actions.lock().unwrap().push("deactivation");
            },
            async { true },
            async move {
                acknowledgement_actions
                    .lock()
                    .unwrap()
                    .push("acknowledgement");
                Ok::<(), &'static str>(())
            },
            Duration::from_millis(50),
        )
        .await;

        assert!(matches!(outcome, Ok(None)));
        assert!(actions.lock().unwrap().is_empty());
    }
}
