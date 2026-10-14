use crate::bluetooth::aacp::{
    AACPEvent, AACPManager, AACPManagerState, AudioSourceType,
    ControlCommandIdentifiers as Control, EarDetectionStatus,
};
use crate::media_controller::MediaController;
use bluer::Address;
use std::time::{Duration, Instant};
use tokio::io::{AsyncBufReadExt, BufReader};

type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;
type ControlSession = (AACPManager, tokio::sync::mpsc::UnboundedReceiver<AACPEvent>);

#[derive(Clone, Copy, PartialEq, Eq)]
enum Recovery {
    Immediate,
    WaitingForPlayback,
    PlaybackRequested,
}

impl Recovery {
    fn playback(&mut self, fresh_play: bool, stopped: bool) {
        match (*self, fresh_play, stopped) {
            (Self::WaitingForPlayback, true, _) => *self = Self::PlaybackRequested,
            (Self::PlaybackRequested, _, true) => *self = Self::WaitingForPlayback,
            _ => {}
        }
    }
}

async fn connect_control(address: Address) -> Result<ControlSession> {
    let mut manager = AACPManager::new();
    let (tx, events) = tokio::sync::mpsc::unbounded_channel();
    manager.set_event_channel(tx).await;
    manager.connect(address).await;
    let result = async {
        if manager.state.lock().await.sender.is_none() {
            return Err("AirPods control connection failed".into());
        }
        manager.send_handshake().await?;
        tokio::time::sleep(Duration::from_millis(300)).await;
        manager.send_set_feature_flags_packet().await?;
        tokio::time::sleep(Duration::from_millis(300)).await;
        manager.send_notification_request().await?;
        // A missing ownership report does not prove that Linux is unowned.
        manager
            .send_control_command(Control::OwnsConnection, &[0])
            .await?;
        wait_routing_ready(&manager).await?;
        Ok(())
    }
    .await;
    if let Err(error) = result {
        manager.disconnect().await;
        return Err(error);
    }
    Ok((manager, events))
}

async fn wait_routing_ready(manager: &AACPManager) -> Result<()> {
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let state = manager.state.lock().await;
            if state.sender.is_none() {
                return Err("AirPods disconnected during initialization".into());
            }
            let ready = !state.connected_devices.is_empty()
                && !state.ear_detection_status.is_empty()
                && state.audio_source.is_some();
            drop(state);
            if ready {
                return Ok(());
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    })
    .await
    .map_err(|_| "AirPods did not provide fresh routing and wear state")?
}

async fn announce(manager: &AACPManager, local: &str) -> Result<()> {
    let peers = manager.state.lock().await.connected_devices.clone();
    for peer in peers.iter().filter(|d| d.mac != local) {
        manager
            .send_media_information_new_device(local, &peer.mac)
            .await?;
        manager.send_add_tipi_device(local, &peer.mac).await?;
    }
    Ok(())
}

fn take_allowed(state: &AACPManagerState, local: &str) -> bool {
    state.sender.is_some()
        && state.connected_devices.len() == 2
        && state.connected_devices.iter().any(|d| d.mac == local)
        && state.connected_devices.iter().any(|d| d.mac != local)
        && state
            .ear_detection_status
            .contains(&EarDetectionStatus::InEar)
        && state.audio_source.as_ref().is_some_and(|source| {
            source.r#type == AudioSourceType::None
                || (source.mac == local && source.r#type == AudioSourceType::Media)
        })
}

async fn status(manager: &AACPManager, local: &str) {
    let state = manager.state.lock().await;
    let owner = state
        .control_command_status_list
        .iter()
        .find(|s| s.identifier == Control::OwnsConnection)
        .map(|s| match s.value.as_slice() {
            [0] => "not-linux",
            [1] => "linux",
            _ => "unknown",
        })
        .unwrap_or("unknown");
    println!(
        "STATUS connected={} owner={} hosts={} wear={:?} take-ready={}",
        state.sender.is_some(),
        owner,
        state.connected_devices.len(),
        state.ear_detection_status,
        take_allowed(&state, local)
    );
    for host in &state.connected_devices {
        println!(
            "HOST {} flags={:02x}/{:02x}",
            if host.mac == local { "linux" } else { "remote" },
            host.info1,
            host.info2
        );
    }
    if let Some(source) = &state.audio_source {
        println!(
            "AUDIO source={} activity={:?}",
            if source.mac == local {
                "linux"
            } else if source.mac == "00:00:00:00:00:00" {
                "none"
            } else {
                "remote"
            },
            source.r#type
        );
    } else {
        println!("AUDIO unknown");
    }
}

pub async fn run(address: Address, daemon: bool) -> Result<()> {
    let session = bluer::Session::new().await?;
    let adapter = session.default_adapter().await?;
    let local = adapter.address().await?.to_string();
    let (mut manager, mut events) = if daemon && !adapter.device(address)?.is_connected().await? {
        // Do not race the iPhone to reconnect when starting with the buds in their case.
        let manager = AACPManager::new();
        let (tx, events) = tokio::sync::mpsc::unbounded_channel();
        manager.set_event_channel(tx).await;
        (manager, events)
    } else {
        connect_control(address).await?
    };
    let mut disconnected = manager.state.lock().await.sender.is_none();
    if daemon {
        if !disconnected {
            let device = adapter.device(address)?;
            tokio::time::timeout(
                Duration::from_secs(5),
                device.connect_profile(&uuid::Uuid::from_u128(0x0000110b00001000800000805f9b34fb)),
            )
            .await??;
            manager
                .send_control_command(Control::OwnsConnection, &[0])
                .await?;
            announce(&manager, &local).await?;
        }
        println!("AUTO service enabled; waiting for a new playback start");
    } else {
        println!("READY commands: status, refresh, announce, take, release, auto, manual, quit");
        println!("Automatic switching is disabled until the auto command.");
    }
    let media = MediaController::new(address.to_string(), local.clone());
    let (play_tx, mut play_rx) = tokio::sync::mpsc::channel(1);
    tokio::spawn(async move {
        loop {
            let sampled_at = Instant::now();
            let playing = tokio::task::spawn_blocking(MediaController::check_if_playing)
                .await
                .unwrap_or(false);
            if play_tx.send((sampled_at, playing)).await.is_err() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(500)).await;
        }
    });
    let mut automatic = daemon;
    let mut was_playing = None;
    let mut playback_baseline_at = Instant::now();
    let mut lines = BufReader::new(tokio::io::stdin()).lines();
    let mut terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
    let mut tick = tokio::time::interval(Duration::from_millis(200));
    let mut pending: Option<(bool, Instant)> = None;
    let mut requested_take = false;
    let mut quitting = false;
    let mut retry: Option<tokio::task::JoinHandle<Result<ControlSession>>> = None;
    let mut retry_at = Instant::now();
    let mut retry_delay = Duration::from_secs(1);
    let mut case_closed = false;
    let mut recovery = if disconnected {
        Recovery::WaitingForPlayback
    } else {
        Recovery::Immediate
    };
    loop {
        tokio::select! {
            biased;
            Some(event) = events.recv() => {
                match event {
                    AACPEvent::ControlCommand(s) if s.identifier == Control::OwnsConnection => {
                        if s.value == vec![0] {
                            if requested_take && automatic { media.pause_all_media().await; }
                            requested_take = false;
                        }
                        if let Some((desired, started)) = pending {
                            if s.value == vec![u8::from(desired)] {
                                println!("ACK owner={} elapsed_ms={}",
                                    if desired { "linux" } else { "not-linux" }, started.elapsed().as_millis());
                                pending = None;
                                if !desired { requested_take = false; }
                                if quitting { break; }
                            }
                        }
                        status(&manager, &local).await;
                    }
                    AACPEvent::ConnectedDevices(old, new) => {
                        if automatic && new.iter().any(|d| d.mac != local && !old.iter().any(|o| o.mac == d.mac)) {
                            announce(&manager, &local).await?;
                        }
                        status(&manager, &local).await;
                    }
                    AACPEvent::EarDetection(_, new) => {
                        if new.len() == 2 && new.iter().all(|s| *s == EarDetectionStatus::InCase) {
                            case_closed = true;
                        } else if new.iter().any(|s| matches!(s, EarDetectionStatus::InEar | EarDetectionStatus::OutOfEar)) {
                            case_closed = false;
                        }
                        status(&manager, &local).await;
                    }
                    AACPEvent::AudioSource(_) => status(&manager, &local).await,
                    AACPEvent::OwnershipToFalseRequest => {
                        if automatic && requested_take { media.pause_all_media().await; }
                        let already_released = manager.state.lock().await.control_command_status_list
                            .iter().any(|s| s.identifier == Control::OwnsConnection && s.value == vec![0]);
                        if already_released && !requested_take {
                            println!("REMOTE requested ownership; Linux already yielded");
                            continue;
                        }
                        println!("REMOTE requested ownership; yielding");
                        manager.send_control_command(Control::OwnsConnection, &[0]).await?;
                        pending = Some((false, Instant::now()));
                    }
                    _ => {}
                }
            }
            Some((sampled_at, playing)) = play_rx.recv() => {
                if sampled_at < playback_baseline_at { continue; }
                let fresh_play = was_playing == Some(false) && playing;
                let stopped = was_playing == Some(true) && !playing;
                was_playing = Some(playing);
                if automatic && disconnected {
                    recovery.playback(fresh_play, stopped);
                    if fresh_play && recovery == Recovery::PlaybackRequested {
                        retry_at = Instant::now();
                        println!("AUTO new playback requested reconnection after case closure");
                    }
                }
                if automatic && !disconnected && !quitting && pending.is_none() {
                    if fresh_play {
                        if take_allowed(&*manager.state.lock().await, &local) {
                            requested_take = true;
                            pending = Some((true, Instant::now()));
                            manager.send_control_command(Control::OwnsConnection, &[1]).await?;
                            println!("AUTO playback started; requested Linux ownership");
                        } else {
                            println!("AUTO takeover blocked by remote activity, wear, or missing host state");
                        }
                    } else if stopped && requested_take {
                        pending = Some((false, Instant::now()));
                        manager.send_control_command(Control::OwnsConnection, &[0]).await?;
                        println!("AUTO playback stopped; releasing ownership");
                    }
                }
            }
            result = async { retry.as_mut().unwrap().await }, if retry.is_some() => {
                retry = None;
                match result? {
                    Ok((new_manager, new_events)) => {
                        if recovery == Recovery::WaitingForPlayback {
                            new_manager.disconnect().await;
                            adapter.device(address)?.disconnect().await?;
                            println!("RECONNECT cancelled because playback stopped");
                            continue;
                        }
                        manager = new_manager;
                        events = new_events;
                        disconnected = false;
                        if automatic {
                            announce(&manager, &local).await?;
                        }
                        if recovery == Recovery::PlaybackRequested {
                            if take_allowed(&*manager.state.lock().await, &local) {
                                requested_take = true;
                                pending = Some((true, Instant::now()));
                                manager.send_control_command(Control::OwnsConnection, &[1]).await?;
                                println!("AUTO reconnected for new playback; requested Linux ownership");
                            } else {
                                println!("AUTO reconnected; takeover blocked by remote activity, wear, or missing host state");
                            }
                        }
                        recovery = Recovery::Immediate;
                        was_playing = None;
                        playback_baseline_at = Instant::now();
                        while play_rx.try_recv().is_ok() {}
                        retry_delay = Duration::from_secs(1);
                        println!("RECONNECTED with fresh routing and wear state");
                    }
                    Err(error) => {
                        println!("RECONNECT failed: {error}; retrying in {}s", retry_delay.as_secs());
                        retry_at = Instant::now() + retry_delay;
                        retry_delay = (retry_delay * 2).min(Duration::from_secs(8));
                    }
                }
            }
            _ = tick.tick() => {
                if manager.state.lock().await.sender.is_none() {
                    if !disconnected {
                        println!("DISCONNECTED clearing ownership and playback baseline");
                        if requested_take && automatic { media.pause_all_media().await; }
                        manager.disconnect().await;
                        events.close();
                        while events.try_recv().is_ok() {}
                        pending = None;
                        requested_take = false;
                        was_playing = None;
                        disconnected = true;
                        recovery = if automatic && case_closed {
                            println!("CASE waiting for new Linux playback before reconnecting");
                            Recovery::WaitingForPlayback
                        } else {
                            Recovery::Immediate
                        };
                        case_closed = false;
                        retry_at = Instant::now() + Duration::from_secs(1);
                    }
                    if retry.is_none() && recovery != Recovery::WaitingForPlayback && Instant::now() >= retry_at && !quitting {
                        let adapter = adapter.clone();
                        retry = Some(tokio::spawn(async move {
                            let result = connect_control(address).await?;
                            if automatic {
                                let profile = uuid::Uuid::from_u128(0x0000110b00001000800000805f9b34fb);
                                let device = adapter.device(address)?;
                                let audio = tokio::time::timeout(Duration::from_secs(5), device.connect_profile(&profile)).await;
                                match audio {
                                    Ok(Ok(())) => {}
                                    _ => {
                                        result.0.disconnect().await;
                                        return Err("A2DP reconnection failed".into());
                                    }
                                }
                                result.0.send_control_command(Control::OwnsConnection, &[0]).await?;
                            }
                            Ok(result)
                        }));
                    }
                }
                if pending.is_some_and(|(_, at)| at.elapsed() > Duration::from_secs(5)) {
                    println!("TIMEOUT ownership acknowledgement not received; audible handoff unconfirmed");
                    pending = None;
                    if quitting { return Err("release was not acknowledged".into()); }
                }
            }
            _ = tokio::signal::ctrl_c(), if !quitting => { quitting = true; }
            _ = terminate.recv(), if !quitting => { quitting = true; }
            line = lines.next_line(), if !quitting && !daemon => {
                let line = line?;
                let command = line.as_deref().map(str::trim).unwrap_or("quit");
                if disconnected && !matches!(command, "status" | "quit" | "manual" | "") {
                    println!("WAIT reconnecting; status, manual, and quit remain available");
                    continue;
                }
                match command {
                    "auto" => {
                        let device = adapter.device(address)?;
                        device.connect_profile(&uuid::Uuid::from_u128(0x0000110b00001000800000805f9b34fb)).await?;
                        if !requested_take {
                            manager.send_control_command(Control::OwnsConnection, &[0]).await?;
                        }
                        automatic = true;
                        announce(&manager, &local).await?;
                        was_playing = None;
                        playback_baseline_at = Instant::now();
                        println!("AUTO enabled; waiting for a new playback start after the baseline");
                    }
                    "manual" => { automatic = false; println!("AUTO disabled"); }
                    "status" => status(&manager, &local).await,
                    "refresh" => { manager.send_notification_request().await?; }
                    "announce" => {
                        announce(&manager, &local).await?;
                        println!("Announced Linux to connected remote hosts");
                    }
                    "take" | "release" if pending.is_some() => println!("WAIT ownership request pending"),
                    "take" => {
                        if !take_allowed(&*manager.state.lock().await, &local) {
                            println!("BLOCKED take needs two hosts, an in-ear bud, and confirmed idle audio");
                            continue;
                        }
                        requested_take = true;
                        pending = Some((true, Instant::now()));
                        manager.send_control_command(Control::OwnsConnection, &[1]).await?;
                        println!("REQUEST owner=linux");
                    }
                    "release" => {
                        let already_released = manager.state.lock().await.control_command_status_list
                            .iter().any(|s| s.identifier == Control::OwnsConnection && s.value == vec![0]);
                        if already_released && !requested_take {
                            println!("Ownership already released");
                            continue;
                        }
                        pending = Some((false, Instant::now()));
                        manager.send_control_command(Control::OwnsConnection, &[0]).await?;
                        println!("REQUEST owner=not-linux");
                    }
                    "quit" => { quitting = true; }
                    "" => {}
                    _ => println!("Commands: status, refresh, announce, take, release, auto, manual, quit"),
                }
            }
        }
        if quitting && !pending.is_some_and(|(desired, _)| !desired) {
            if !requested_take {
                break;
            }
            pending = Some((false, Instant::now()));
            manager
                .send_control_command(Control::OwnsConnection, &[0])
                .await?;
            println!("EXIT releasing ownership");
        }
    }
    if let Some(task) = retry {
        task.abort();
    }
    manager.disconnect().await;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn case_recovery_requires_new_playback_and_cancels_when_stopped() {
        let mut recovery = Recovery::WaitingForPlayback;
        recovery.playback(false, false);
        assert!(recovery == Recovery::WaitingForPlayback);
        recovery.playback(true, false);
        assert!(recovery == Recovery::PlaybackRequested);
        recovery.playback(false, true);
        assert!(recovery == Recovery::WaitingForPlayback);
        recovery.playback(true, false);
        assert!(recovery == Recovery::PlaybackRequested);
        recovery = Recovery::Immediate;
        recovery.playback(false, true);
        assert!(recovery == Recovery::Immediate);
    }

    #[tokio::test]
    async fn open_transport_is_not_a_ready_control_session() {
        let manager = AACPManager::new();
        let (sender, _receiver) = tokio::sync::mpsc::channel(1);
        manager.state.lock().await.sender = Some(sender);
        assert!(
            tokio::time::timeout(Duration::from_millis(20), wait_routing_ready(&manager))
                .await
                .is_err()
        );
        manager
            .receive_packet(&[4, 0, 4, 0, 0x2e, 0, 0, 0, 1, 1, 2, 3, 4, 5, 6, 0, 0])
            .await;
        assert!(
            tokio::time::timeout(Duration::from_millis(20), wait_routing_ready(&manager))
                .await
                .is_err()
        );
        manager.receive_packet(&[4, 0, 4, 0, 6, 0, 0, 0]).await;
        manager
            .receive_packet(&[4, 0, 4, 0, 0x0e, 0, 0, 0, 0, 0, 0, 0, 0])
            .await;
        wait_routing_ready(&manager).await.unwrap();
        manager.disconnect().await;
        assert!(wait_routing_ready(&manager).await.is_err());
    }

    #[tokio::test]
    async fn connected_hosts_count_and_truncation() {
        let manager = AACPManager::new();
        let packet = [
            4, 0, 4, 0, 0x2e, 0, 0, 0, 2, 1, 2, 3, 4, 5, 6, 0, 0, 7, 8, 9, 10, 11, 12, 1, 0,
        ];
        for end in 0..packet.len() {
            manager.receive_packet(&packet[..end]).await;
        }
        assert!(manager.state.lock().await.connected_devices.is_empty());
        manager.receive_packet(&packet).await;
        let state = manager.state.lock().await;
        assert_eq!(state.connected_devices.len(), 2);
        assert_eq!(state.connected_devices[1].mac, "07:08:09:0A:0B:0C");
    }

    #[tokio::test]
    async fn unknown_activity_must_not_become_idle() {
        let manager = AACPManager::new();
        manager
            .receive_packet(&[4, 0, 4, 0, 0x0e, 0, 0, 0, 0, 0, 0, 0, 0x42])
            .await;
        assert_eq!(
            manager
                .state
                .lock()
                .await
                .audio_source
                .as_ref()
                .unwrap()
                .r#type,
            AudioSourceType::Unknown
        );
    }

    #[tokio::test]
    async fn short_notifications_do_not_panic_or_claim_ownership() {
        let manager = AACPManager::new();
        for opcode in [0x06, 0x09, 0x0e, 0x11, 0x1d, 0x2e] {
            let packet = [4, 0, 4, 0, opcode, 0, 0];
            for end in 0..=packet.len() {
                manager.receive_packet(&packet[..end]).await;
            }
        }
        assert!(!take_allowed(
            &*manager.state.lock().await,
            "01:02:03:04:05:06"
        ));
    }

    #[tokio::test]
    async fn take_requires_idle_worn_and_both_hosts() {
        let manager = AACPManager::new();
        manager
            .receive_packet(&[
                4, 0, 4, 0, 0x2e, 0, 0, 0, 2, 1, 2, 3, 4, 5, 6, 0, 0, 7, 8, 9, 10, 11, 12, 1, 0,
            ])
            .await;
        manager.receive_packet(&[4, 0, 4, 0, 6, 0, 0, 0]).await;
        let (sender, _receiver) = tokio::sync::mpsc::channel(1);
        manager.state.lock().await.sender = Some(sender);
        let local = "01:02:03:04:05:06";
        assert!(!take_allowed(&*manager.state.lock().await, local));
        for activity in [0, 1, 2, 0xff] {
            manager
                .receive_packet(&[4, 0, 4, 0, 0x0e, 0, 0, 0, 0, 0, 0, 0, activity])
                .await;
            assert_eq!(
                take_allowed(&*manager.state.lock().await, local),
                activity == 0
            );
        }
        manager
            .receive_packet(&[4, 0, 4, 0, 0x0e, 0, 0, 0, 0, 0, 0, 0, 0])
            .await;
        manager.state.lock().await.ear_detection_status.clear();
        assert!(!take_allowed(&*manager.state.lock().await, local));
        manager
            .state
            .lock()
            .await
            .ear_detection_status
            .push(EarDetectionStatus::InEar);
        manager.state.lock().await.connected_devices.pop();
        assert!(!take_allowed(&*manager.state.lock().await, local));
    }

    #[tokio::test]
    async fn disconnect_invalidates_routing_and_wear_before_reconnect() {
        let manager = AACPManager::new();
        manager
            .receive_packet(&[
                4, 0, 4, 0, 0x2e, 0, 0, 0, 2, 1, 2, 3, 4, 5, 6, 0, 0, 7, 8, 9, 10, 11, 12, 1, 0,
            ])
            .await;
        manager.receive_packet(&[4, 0, 4, 0, 6, 0, 0, 0]).await;
        manager
            .receive_packet(&[4, 0, 4, 0, 9, 0, 6, 1, 0, 0, 0])
            .await;
        manager
            .receive_packet(&[4, 0, 4, 0, 0x0e, 0, 0, 0, 0, 0, 0, 0, 0])
            .await;
        let (sender, _receiver) = tokio::sync::mpsc::channel(1);
        manager.state.lock().await.sender = Some(sender.clone());
        assert!(take_allowed(
            &*manager.state.lock().await,
            "01:02:03:04:05:06"
        ));
        manager.disconnect().await;
        let mut state = manager.state.lock().await;
        assert!(state.sender.is_none());
        assert!(!state.owns);
        assert!(state.control_command_status_list.is_empty());
        assert!(state.audio_source.is_none());
        assert!(state.ear_detection_status.is_empty());
        assert!(state.connected_devices.is_empty());
        state.sender = Some(sender);
        assert!(!take_allowed(&state, "01:02:03:04:05:06"));
    }
}
