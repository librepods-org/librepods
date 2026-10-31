use crate::audio::card::{self, CardRef};
use crate::bluetooth::aacp::AACPManager;
use crate::bluetooth::aacp::EarDetectionStatus;
use dbus::arg::RefArg;
use dbus::blocking::Connection;
use dbus::blocking::stdintf::org_freedesktop_dbus::Properties;
use libpulse_binding::callbacks::ListResult;
use libpulse_binding::context::introspect::SinkInfo;
use libpulse_binding::context::{Context, FlagSet as ContextFlagSet};
use libpulse_binding::def::Retval;
use libpulse_binding::mainloop::standard::Mainloop;
use libpulse_binding::operation::State as OperationState;
use libpulse_binding::proplist::Proplist;
use libpulse_binding::volume::{ChannelVolumes, Volume};
use log::{debug, error, info, warn};
use std::cell::RefCell;
use std::process::Command;
use std::rc::Rc;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Mutex;

#[derive(Clone, Debug)]
struct OwnedCardProfileInfo {
    name: Option<String>,
}

#[derive(Clone, Debug)]
struct OwnedCardInfo {
    index: u32,
    proplist: Proplist,
    profiles: Vec<OwnedCardProfileInfo>,
}

#[derive(Clone, Debug)]
struct OwnedSinkInfo {
    name: Option<String>,
    proplist: Proplist,
    volume: ChannelVolumes,
}

struct MediaControllerState {
    connected_device_mac: String,
    local_mac: String,
    is_playing: bool,
    paused_by_app_services: Vec<String>,
    device_index: Option<u32>,
    cached_a2dp_profile: String,
    old_in_ear_data: Vec<bool>,
    ear_detection_enabled: bool,
    /// Playback streams that were on the AirPods when we switched the card off,
    /// moved back when the card comes back.
    saved_streams: Vec<card::SavedStream>,
    conv_original_volume: Option<u32>,
    conv_conversation_started: bool,
    playback_listener_running: bool,
    /// Last seen hi-res mic state, to detect a call starting.
    mic_was_active: bool,
    /// Last seen "another device is playing / in a call on the AirPods".
    other_was_active: bool,
    /// Desktop default-device refresh done for this connection.
    default_refreshed: bool,
}

impl MediaControllerState {
    fn new() -> Self {
        MediaControllerState {
            connected_device_mac: String::new(),
            local_mac: String::new(),
            is_playing: false,
            paused_by_app_services: Vec::new(),
            device_index: None,
            cached_a2dp_profile: String::new(),
            old_in_ear_data: vec![false, false],
            ear_detection_enabled: true,
            saved_streams: Vec::new(),
            mic_was_active: false,
            other_was_active: false,
            default_refreshed: false,
            conv_original_volume: None,
            conv_conversation_started: false,
            playback_listener_running: false,
        }
    }
}

#[derive(Clone)]
pub struct MediaController {
    state: Arc<Mutex<MediaControllerState>>,
    /// Held for a whole activate / deactivate (and the debounced "still no bud
    /// in?" check + deactivate), so switching the AirPods' audio on and off can't
    /// interleave. The profile lock in audio::card only covers single changes.
    routing: Arc<Mutex<()>>,
}

impl MediaController {
    pub fn new(connected_mac: String, local_mac: String) -> Self {
        let mut state = MediaControllerState::new();
        state.connected_device_mac = connected_mac;
        state.local_mac = local_mac;
        MediaController {
            state: Arc::new(Mutex::new(state)),
            routing: Arc::new(Mutex::new(())),
        }
    }

    pub async fn start_playback_listener(
        &self,
        aacp_manager: AACPManager,
        control_tx: tokio::sync::mpsc::UnboundedSender<(
            crate::bluetooth::aacp::ControlCommandIdentifiers,
            Vec<u8>,
        )>,
    ) {
        let mut state = self.state.lock().await;
        if state.playback_listener_running {
            debug!("Playback listener already running");
            return;
        }
        state.playback_listener_running = true;
        drop(state);

        let controller_clone = self.clone();
        tokio::spawn(async move {
            controller_clone
                .playback_listener_loop(aacp_manager, control_tx)
                .await;
        });
    }

    async fn playback_listener_loop(
        &self,
        aacp_manager: AACPManager,
        control_tx: tokio::sync::mpsc::UnboundedSender<(
            crate::bluetooth::aacp::ControlCommandIdentifiers,
            Vec<u8>,
        )>,
    ) {
        info!("Starting playback listener loop");
        loop {
            tokio::select! {
                _ = aacp_manager.disconnected() => {
                    info!("AirPods disconnected, stopping playback listener");
                    self.state.lock().await.playback_listener_running = false;
                    return;
                }
                _ = tokio::time::sleep(Duration::from_millis(500)) => {}
            }

            let is_playing = tokio::task::spawn_blocking(|| Self::check_if_playing())
                .await
                .unwrap_or(false);
            // An app opening the hi-res mic (e.g. joining a voice call) is the
            // call equivalent of "playback started": calls aren't MPRIS players,
            // so without this a call would never take the AirPods back from
            // another device.
            let mic_active = aacp_manager.mic_active();
            let local_mac = self.state.lock().await.local_mac.clone();
            let other_active = aacp_manager.other_device_using_audio(&local_mac).await;

            let mut state = self.state.lock().await;
            let was_playing = state.is_playing;
            state.is_playing = is_playing;
            let was_mic_active = std::mem::replace(&mut state.mic_was_active, mic_active);
            let other_was_active = std::mem::replace(&mut state.other_was_active, other_active);
            drop(state);

            if !was_playing && is_playing {
                self.take_over_audio(&aacp_manager, &control_tx, "Media playback started")
                    .await;
            } else if !was_mic_active && mic_active {
                self.take_over_audio(&aacp_manager, &control_tx, "Hi-res mic started")
                    .await;
            } else if other_was_active && !other_active && (mic_active || is_playing) {
                // E.g. a phone call on an iPhone took the AirPods in the middle
                // of a voice call here. When it ends nothing else would bring
                // them back: the mic never stopped, so there is no new start.
                self.take_over_audio(
                    &aacp_manager,
                    &control_tx,
                    "Other device released the AirPods during a call or playback",
                )
                .await;
            }
        }
    }

    /// Claim the AirPods for this computer: tell them we own the connection,
    /// bring up A2DP and ask other connected devices (iPhone) to let go.
    async fn take_over_audio(
        &self,
        aacp_manager: &AACPManager,
        control_tx: &tokio::sync::mpsc::UnboundedSender<(
            crate::bluetooth::aacp::ControlCommandIdentifiers,
            Vec<u8>,
        )>,
        reason: &str,
    ) {
        // Copy what we need and release the AACP state lock right away: sending
        // packets takes the same lock (holding it here would deadlock when
        // another device is listed), and holding it through A2DP activation
        // would stall all AirPods packet handling.
        let (worn, connected_devices) = {
            let aacp_state = aacp_manager.state.lock().await;
            (
                aacp_state
                    .ear_detection_status
                    .contains(&EarDetectionStatus::InEar),
                aacp_state.connected_devices.clone(),
            )
        };
        if !worn {
            info!("{} but buds not in ear, skipping takeover", reason);
            return;
        }
        info!("{}, taking ownership and activating a2dp", reason);
        let _ = control_tx.send((
            crate::bluetooth::aacp::ControlCommandIdentifiers::OwnsConnection,
            vec![0x01],
        ));
        self.activate_a2dp_profile().await;

        let local_mac = self.state.lock().await.local_mac.clone();
        for device in connected_devices {
            if device.mac == local_mac {
                continue;
            }
            info!("Asking AirPods to hand over from {}", device.mac);
            if let Err(e) = aacp_manager
                .send_media_information(&local_mac, &device.mac, true)
                .await
            {
                error!("Failed to send media information to {}: {}", device.mac, e);
            }
            if let Err(e) = aacp_manager.send_smart_routing_show_ui(&device.mac).await {
                error!(
                    "Failed to send smart routing show ui to {}: {}",
                    device.mac, e
                );
            }
            if let Err(e) = aacp_manager.send_hijack_request(&device.mac).await {
                error!("Failed to send hijack request to {}: {}", device.mac, e);
            }
        }
        debug!("completed takeover process");
    }

    fn check_if_playing() -> bool {
        let conn = match Connection::new_session() {
            Ok(c) => c,
            Err(_) => return false,
        };
        Self::list_mpris_services(&conn).iter().any(|service| {
            let proxy = conn.with_proxy(service, "/org/mpris/MediaPlayer2", Duration::from_secs(5));
            proxy
                .get::<String>("org.mpris.MediaPlayer2.Player", "PlaybackStatus")
                .map(|s| s == "Playing")
                .unwrap_or(false)
        })
    }

    fn is_kdeconnect_service(service: &str) -> bool {
        service.starts_with("org.mpris.MediaPlayer2.kdeconnect.mpris_")
    }

    /// `other_device_active`: another device (iPhone) is currently playing or
    /// in a call on the AirPods; then a bud going in doesn't pull audio here.
    pub async fn handle_ear_detection(
        &self,
        old_statuses: Vec<EarDetectionStatus>,
        new_statuses: Vec<EarDetectionStatus>,
        other_device_active: bool,
    ) {
        let old_in_ear_data: Vec<bool> = old_statuses
            .iter()
            .map(|s| *s == EarDetectionStatus::InEar)
            .collect();
        let new_in_ear_data: Vec<bool> = new_statuses
            .iter()
            .map(|s| *s == EarDetectionStatus::InEar)
            .collect();
        info!(
            "Ear Detection - old_in_ear_data: {:?}, new_in_ear_data: {:?}",
            old_in_ear_data, new_in_ear_data
        );

        {
            let mut state = self.state.lock().await;
            state.old_in_ear_data = new_in_ear_data.clone();
            if !state.ear_detection_enabled {
                debug!("Ear detection disabled, skipping");
                return;
            }
        }

        let settings = crate::utils::AppSettings::load();
        let mut actions = ear_actions(
            &old_in_ear_data,
            &new_in_ear_data,
            settings.audio_off_when_not_wearing,
            settings.pause_when_one_removed,
        );
        if actions.activate_a2dp && other_device_active {
            // Putting a bud in must not pull the AirPods away from another device
            // that is playing or in a call (that would bypass the ownership
            // handover). Playing media or joining a call here still takes over.
            info!("Another device is using the AirPods; not switching audio here");
            actions.activate_a2dp = false;
            actions.resume = false;
        }
        debug!("Ear actions: {:?}", actions);

        if !new_in_ear_data.iter().any(|&b| b) {
            // Conversation awareness may have ducked the volume to 25%; with no
            // bud worn it would stay there.
            self.end_conversation_ducking().await;
        }
        if actions.pause {
            // Only pause if something is playing to the AirPods: taking them off
            // shouldn't pause music playing on the speakers or another headset.
            if self.airpods_have_streams().await {
                self.pause().await;
            } else {
                debug!("Nothing playing on the AirPods; not pausing");
            }
        }
        if actions.activate_a2dp {
            self.activate_a2dp_profile().await;
        }
        if actions.resume {
            self.resume().await;
        }
        if actions.deactivate_a2dp {
            // Debounced: adjusting a bud briefly reports both out. Media is
            // already paused; only switch the output if nobody put a bud back.
            let controller = self.clone();
            tokio::spawn(async move {
                tokio::time::sleep(AUDIO_OFF_DEBOUNCE).await;
                // Check and switch under the routing lock: a bud going back in
                // right now either sees the card already off (and turns it back
                // on) or makes still_out false here. Without the lock, the bud-in
                // path could see A2DP still on, do nothing, and then this would
                // switch it off with a bud in.
                let _routing = controller.routing.lock().await;
                let still_out = !controller
                    .state
                    .lock()
                    .await
                    .old_in_ear_data
                    .iter()
                    .any(|&b| b);
                if still_out {
                    info!("No bud in ear; switching audio to another output");
                    controller.deactivate_a2dp_profile_locked().await;
                } else {
                    debug!("Bud back in ear within debounce; keeping AirPods audio");
                }
            });
        }
    }

    pub async fn activate_a2dp_profile(&self) {
        let _routing = self.routing.lock().await;
        self.activate_a2dp_profile_locked().await;
    }

    async fn activate_a2dp_profile_locked(&self) {
        debug!("Entering activate_a2dp_profile");
        let state = self.state.lock().await;

        if state.connected_device_mac.is_empty() {
            warn!("Connected device MAC is empty, cannot activate A2DP profile");
            return;
        }

        let mac = state.connected_device_mac.clone();
        drop(state);

        // Look the card up fresh and wait for its A2DP profiles: right after the
        // AirPods connect, ear detection can arrive before PipeWire has created
        // the bluez card or before BlueZ has connected A2DP, and the card index
        // changes whenever the card is recreated. A WirePlumber restart is only
        // a last resort (it cuts every audio stream).
        let mut ready = false;
        for attempt in 0..A2DP_WAIT_ATTEMPTS {
            if let Some(idx) = self.get_audio_device_index(&mac).await {
                self.state.lock().await.device_index = Some(idx);
                if self.is_a2dp_profile_available().await {
                    ready = true;
                    break;
                }
            }
            debug!("A2DP not available yet (attempt {}), waiting", attempt + 1);
            tokio::time::sleep(Duration::from_millis(500)).await;
        }

        if !ready {
            warn!("A2DP profile still not available after waiting");
            if !self.restart_wire_plumber_last_resort().await {
                return;
            }
            // Note: no state lock held across these calls (they take it).
            let idx = self.get_audio_device_index(&mac).await;
            self.state.lock().await.device_index = idx;
            if idx.is_none() || !self.is_a2dp_profile_available().await {
                error!("A2DP profile still not available after WirePlumber restart");
                return;
            }
        }

        let preferred_profile = self.get_preferred_a2dp_profile().await;
        if preferred_profile.is_empty() {
            error!("No suitable A2DP profile found");
            return;
        }

        let device_index = self.state.lock().await.device_index;
        if let Some(idx) = device_index {
            let active = tokio::task::spawn_blocking(move || {
                card::active_profile(CardRef::Index(idx))
            })
            .await
            .unwrap_or(None);
            if active.as_deref() == Some(preferred_profile.as_str()) {
                // Re-setting the same profile restarts the transport for nothing.
                debug!("A2DP profile {} already active", preferred_profile);
                self.restore_saved_streams(idx).await;
                self.restore_pending_conv_volume().await;
                // First activation after (re)connecting: the sink just appeared.
                self.refresh_desktop_default(false).await;
                return;
            }
            info!("Activating A2DP profile for AirPods: {}", preferred_profile);
            let profile_name = preferred_profile.clone();
            let success = tokio::task::spawn_blocking(move || {
                card::set_profile(CardRef::Index(idx), &profile_name)
            })
            .await
            .unwrap_or(false);

            if success {
                info!("Successfully activated A2DP profile: {}", preferred_profile);
                // Fresh transport: a hi-res A2DP reset requested before this
                // can be skipped (see hires_mic::schedule_a2dp_reset).
                if active.as_deref().is_none_or(|p| !p.starts_with("a2dp")) {
                    card::note_activated();
                }
                self.restore_saved_streams(idx).await;
                self.restore_pending_conv_volume().await;
                self.refresh_desktop_default(true).await;
            } else {
                warn!("Failed to activate A2DP profile: {}", preferred_profile);
            }
        } else {
            error!("Device index not available for activating profile.");
        }
    }

    async fn pause(&self) {
        debug!("Pausing playback");

        let paused_services = tokio::task::spawn_blocking(|| {
            let conn = match Connection::new_session() {
                Ok(c) => c,
                Err(_) => return vec![],
            };
            let mut paused_services = Vec::new();

            for service in Self::list_mpris_services(&conn) {
                debug!("Checking playback status for service: {}", service);
                let proxy = conn.with_proxy(
                    &service,
                    "/org/mpris/MediaPlayer2",
                    Duration::from_secs(5),
                );

                if let Ok(playback_status) =
                    proxy.get::<String>("org.mpris.MediaPlayer2.Player", "PlaybackStatus")
                    && playback_status == "Playing"
                {
                    if proxy
                        .method_call::<(), _, &str, &str>(
                            "org.mpris.MediaPlayer2.Player",
                            "Pause",
                            (),
                        )
                        .is_ok()
                    {
                        info!("Paused playback for: {}", service);
                        paused_services.push(service);
                    } else {
                        error!("Failed to pause {}", service);
                    }
                }
            }

            paused_services
        })
            .await
            .unwrap();

        if !paused_services.is_empty() {
            info!("Paused {} media player(s) via DBus", paused_services.len());
            let mut state = self.state.lock().await;
            state.paused_by_app_services = paused_services;
            state.is_playing = false;
        } else {
            info!("No playing media players found to pause");
        }
    }

    pub async fn pause_all_media(&self) {
        debug!("Pausing all media (without tracking for resume)");

        let paused_count = tokio::task::spawn_blocking(|| {
            let conn = match Connection::new_session() {
                Ok(c) => c,
                Err(_) => return 0,
            };
            let mut paused_count = 0;

            for service in Self::list_mpris_services(&conn) {
                let proxy =
                    conn.with_proxy(&service, "/org/mpris/MediaPlayer2", Duration::from_secs(5));
                if let Ok(playback_status) =
                    proxy.get::<String>("org.mpris.MediaPlayer2.Player", "PlaybackStatus")
                    && playback_status == "Playing"
                {
                    if proxy
                        .method_call::<(), _, &str, &str>(
                            "org.mpris.MediaPlayer2.Player",
                            "Pause",
                            (),
                        )
                        .is_ok()
                    {
                        info!("Paused playback for: {}", service);
                        paused_count += 1;
                    } else {
                        error!("Failed to pause {}", service);
                    }
                }
            }
            paused_count
        })
            .await
            .unwrap();

        if paused_count > 0 {
            info!("Paused {} media player(s) due to ownership loss", paused_count);
            self.state.lock().await.is_playing = false;
        } else {
            debug!("No playing media players found to pause");
        }
    }

    async fn resume(&self) {
        debug!("Resuming playback");
        let services = self.state.lock().await.paused_by_app_services.clone();

        if services.is_empty() {
            debug!("No services to resume");
            return;
        }

        let resumed_count = tokio::task::spawn_blocking(move || {
            let conn = match Connection::new_session() {
                Ok(c) => c,
                Err(_) => return 0,
            };
            let mut resumed_count = 0;
            for service in services {
                let proxy =
                    conn.with_proxy(&service, "/org/mpris/MediaPlayer2", Duration::from_secs(5));
                if proxy
                    .method_call::<(), _, &str, &str>("org.mpris.MediaPlayer2.Player", "Play", ())
                    .is_ok()
                {
                    info!("Resumed playback for: {}", service);
                    resumed_count += 1;
                } else {
                    warn!("Failed to resume {}", service);
                }
            }
            resumed_count
        })
            .await
            .unwrap();

        if resumed_count > 0 {
            info!("Resumed {} media player(s) via DBus", resumed_count);
            self.state.lock().await.paused_by_app_services.clear();
        } else {
            error!("Failed to resume any media players via DBus");
        }
    }

    pub async fn next_track(&self) {
        info!("Skipping to next track");
        self.mpris_command("Next").await;
    }

    pub async fn previous_track(&self) {
        info!("Going to previous track");
        self.mpris_command("Previous").await;
    }

    async fn is_a2dp_profile_available(&self) -> bool {
        let index = match self.state.lock().await.device_index {
            Some(i) => i,
            None => {
                debug!("Device index is None, returning false");
                return false;
            }
        };

        tokio::task::spawn_blocking(move || {
            let available = get_card_info_list_sync()
                .iter()
                .find(|c| c.index == index)
                .map(|card| {
                    card.profiles
                        .iter()
                        .any(|p| p.name.as_ref().is_some_and(|n| n.starts_with("a2dp-sink")))
                })
                .unwrap_or(false);
            debug!("A2DP profile available: {}", available);
            available
        })
            .await
            .unwrap_or(false)
    }

    async fn get_preferred_a2dp_profile(&self) -> String {
        let state = self.state.lock().await;
        let device_index = state.device_index;
        let cached_profile = state.cached_a2dp_profile.clone();
        drop(state);

        let index = match device_index {
            Some(i) => i,
            None => return String::new(),
        };

        if !cached_profile.is_empty() && self.is_profile_available(index, &cached_profile).await {
            debug!("Using cached A2DP profile: {}", cached_profile);
            return cached_profile;
        }

        // Prefer AAC ("a2dp-sink"): the AirPods' native codec, and lighter on the
        // link than SBC-XQ while the hi-res mic stream shares it.
        for profile in ["a2dp-sink", "a2dp-sink-sbc_xq", "a2dp-sink-sbc"] {
            if self.is_profile_available(index, profile).await {
                info!("Selected best available A2DP profile: {}", profile);
                self.state.lock().await.cached_a2dp_profile = profile.to_string();
                return profile.to_string();
            }
        }
        debug!("No suitable profile found");
        String::new()
    }

    async fn is_profile_available(&self, card_index: u32, profile: &str) -> bool {
        let profile_name = profile.to_string();
        tokio::task::spawn_blocking(move || {
            let available = get_card_info_list_sync()
                .iter()
                .find(|c| c.index == card_index)
                .map(|card| {
                    card.profiles
                        .iter()
                        .any(|p| p.name.as_ref() == Some(&profile_name))
                })
                .unwrap_or(false);
            debug!("Profile {} available: {}", profile_name, available);
            available
        })
            .await
            .unwrap_or(false)
    }

    /// Restart WirePlumber only as a last resort: it drops every audio stream
    /// (including an ongoing call), so never during hi-res capture and at most
    /// once per WIREPLUMBER_RESTART_COOLDOWN across the whole process.
    async fn restart_wire_plumber_last_resort(&self) -> bool {
        if crate::audio::hires_mic::any_capture_active() {
            warn!("Not restarting WirePlumber during an active mic capture");
            return false;
        }
        {
            let mut last = LAST_WIREPLUMBER_RESTART
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            if last.is_some_and(|t| t.elapsed() < WIREPLUMBER_RESTART_COOLDOWN) {
                warn!("WirePlumber was restarted recently; not restarting again");
                return false;
            }
            *last = Some(std::time::Instant::now());
        }
        info!("Restarting WirePlumber to rediscover A2DP profiles");
        let result = tokio::task::spawn_blocking(|| {
            Command::new("systemctl")
                .args(["--user", "restart", "wireplumber"])
                .output()
        })
        .await;

        match result {
            Ok(Ok(output)) if output.status.success() => {
                info!("WirePlumber restarted successfully");
                tokio::time::sleep(Duration::from_secs(2)).await;
                true
            }
            _ => {
                error!("Failed to restart WirePlumber. Do you use wireplumber?");
                false
            }
        }
    }

    async fn get_audio_device_index(&self, mac: &str) -> Option<u32> {
        if mac.is_empty() {
            return None;
        }
        let mac_clone = mac.to_string();

        tokio::task::spawn_blocking(move || {
            for card in get_card_info_list_sync() {
                if let Some(device_string) = card.proplist.get_str("device.string")
                    && device_string.contains(&mac_clone)
                {
                    debug!("Found audio device index for MAC {}: {}", mac_clone, card.index);
                    return Some(card.index);
                }
            }
            debug!("No matching Bluetooth card found for MAC address: {}", mac_clone);
            None
        })
            .await
            .unwrap_or(None)
    }

    pub async fn deactivate_a2dp_profile(&self) {
        let _routing = self.routing.lock().await;
        self.deactivate_a2dp_profile_locked().await;
    }

    async fn deactivate_a2dp_profile_locked(&self) {
        debug!("Entering deactivate_a2dp_profile");
        let mut state = self.state.lock().await;

        // Fresh lookup: a cached index is stale once the card was recreated
        // (reconnect, PipeWire restart), and setting a profile on it fails.
        // (get_audio_device_index doesn't take the state lock.)
        state.device_index = self
            .get_audio_device_index(&state.connected_device_mac)
            .await;

        if state.connected_device_mac.is_empty() || state.device_index.is_none() {
            warn!("Connected device MAC or index is empty, cannot deactivate A2DP profile");
            return;
        }
        let device_index = state.device_index.unwrap();
        drop(state);

        // Remember which apps were playing to the AirPods; streams that target
        // the AirPods explicitly don't follow the default sink back by themselves.
        let streams = tokio::task::spawn_blocking(move || card::streams_on_card(device_index))
            .await
            .unwrap_or_default();
        if !streams.is_empty() {
            debug!("Saving {} stream(s) to move back later", streams.len());
            self.state.lock().await.saved_streams = streams;
        }

        info!("Deactivating A2DP profile for AirPods by setting to off");

        let success = tokio::task::spawn_blocking(move || {
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                card::set_profile(CardRef::Index(device_index), "off")
            }))
                .unwrap_or_else(|e| {
                    warn!("Panic while switching the card profile off: {:?}", e);
                    false
                })
        })
            .await
            .unwrap_or(false);

        if success {
            info!("Successfully deactivated A2DP profile");
        } else {
            warn!("Failed to deactivate A2DP profile");
        }
    }

    pub async fn handle_conversational_awareness(&self, status: u8) {
        debug!("Entering handle_conversational_awareness with status: {}", status);

        let mac = self.state.lock().await.connected_device_mac.clone();
        if mac.is_empty() {
            debug!("No connected device MAC, skipping conversational awareness");
            return;
        }

        let sink = match get_sink_name_by_mac(&mac).await {
            Some(s) => s,
            None => {
                warn!(
                    "Could not find sink for MAC {}, skipping conversational awareness",
                    mac
                );
                return;
            }
        };

        let current_volume_opt = tokio::task::spawn_blocking({
            let sink = sink.clone();
            move || get_sink_volume_percent_by_name_sync(&sink)
        })
            .await
            .unwrap_or(None);

        match status {
            1 => {
                let original = current_volume_opt.unwrap_or(0);
                debug!("Conversation start (1). Current volume: {}", original);
                {
                    let mut state = self.state.lock().await;
                    if !state.conv_conversation_started {
                        state.conv_original_volume = Some(original);
                        state.conv_conversation_started = true;
                        // Persist it so a disconnect mid-conversation can't leave
                        // the AirPods at 25% (restored on the next activation).
                        let mut settings = crate::utils::AppSettings::load();
                        settings.conv_volume_restore = Some(original);
                        settings.save();
                    } else {
                        debug!(
                            "Conversation already started; not overwriting conv_original_volume"
                        );
                    }
                }
                if original > 25 {
                    let sink_clone = sink.clone();
                    tokio::task::spawn_blocking(move || transition_sink_volume(&sink_clone, 25))
                        .await
                        .unwrap_or(false);
                    info!(
                        "Conversation start: lowered volume to 25% (original {})",
                        original
                    );
                } else {
                    debug!("Original volume {} <= 25, not reducing to 25", original);
                }
            }
            2 => {
                let original = self.state.lock().await.conv_original_volume;
                if let Some(orig) = original {
                    debug!("Conversation reduce (2). Original: {}", orig);
                    if orig > 15 {
                        let sink_clone = sink.clone();
                        tokio::task::spawn_blocking(move || {
                            transition_sink_volume(&sink_clone, 15)
                        })
                            .await
                            .unwrap_or(false);
                        info!(
                            "Conversation reduce: lowered volume to 15% (original {})",
                            orig
                        );
                    } else {
                        debug!("Original {} <= 15, not reducing to 15", orig);
                    }
                } else {
                    debug!("No original volume known for status 2, skipping");
                }
            }
            3 => {
                let (conv_started, conv_original) = {
                    let state = self.state.lock().await;
                    (state.conv_conversation_started, state.conv_original_volume)
                };
                if !conv_started {
                    debug!("Received status 3 but conversation was not started; ignoring increase");
                    return;
                }
                if let Some(orig) = conv_original {
                    let target = orig.min(25);
                    let sink_clone = sink.clone();
                    tokio::task::spawn_blocking(move || transition_sink_volume(&sink_clone, target))
                        .await
                        .unwrap_or(false);
                    info!(
                        "Conversation partial increase (3): set volume to {} (original {})",
                        target, orig
                    );
                } else if let Some(orig_from_current) = current_volume_opt {
                    let target = orig_from_current.min(25);
                    let sink_clone = sink.clone();
                    tokio::task::spawn_blocking(move || transition_sink_volume(&sink_clone, target))
                        .await
                        .unwrap_or(false);
                    info!(
                        "Conversation partial increase (3) with fallback current: set volume to {} (measured {})",
                        target, orig_from_current
                    );
                } else {
                    debug!("No original volume known for status 3, skipping");
                }
            }
            4 | 6 | 7 => {
                debug!("Conversation end ({}), restoring volume if needed", status);
                self.restore_volume_if_needed(&sink).await;
            }
            _ => {
                debug!("Conversation status ({}), ignoring", status);
            }
        }
    }

    async fn mpris_command(&self, command: &'static str) {
        tokio::task::spawn_blocking(move || {
            let conn = match Connection::new_session() {
                Ok(c) => c,
                Err(_) => return,
            };

            let services = Self::list_mpris_services(&conn);
            let mut playing = None;
            let mut fallback = None;

            for service in &services {
                let proxy =
                    conn.with_proxy(service, "/org/mpris/MediaPlayer2", Duration::from_secs(5));
                if let Ok(status) =
                    proxy.get::<String>("org.mpris.MediaPlayer2.Player", "PlaybackStatus")
                {
                    if status == "Playing" && playing.is_none() {
                        playing = Some(service);
                    }
                }
                if fallback.is_none() {
                    fallback = Some(service);
                }
            }

            if let Some(service) = playing.or(fallback) {
                let proxy =
                    conn.with_proxy(service, "/org/mpris/MediaPlayer2", Duration::from_secs(5));
                if proxy
                    .method_call::<(), _, &str, &str>(
                        "org.mpris.MediaPlayer2.Player",
                        command,
                        (),
                    )
                    .is_ok()
                {
                    info!("Sent {} to: {}", command, service);
                } else {
                    debug!("Failed to send {} to: {}", command, service);
                }
            }
        })
            .await
            .unwrap();
    }

    async fn restore_volume_if_needed(&self, sink: &str) {
        let maybe_original = {
            let mut state = self.state.lock().await;
            if state.conv_conversation_started {
                let orig = state.conv_original_volume;
                state.conv_original_volume = None;
                state.conv_conversation_started = false;
                orig
            } else {
                return;
            }
        };

        if let Some(orig) = maybe_original {
            let sink = sink.to_string();
            tokio::task::spawn_blocking(move || transition_sink_volume(&sink, orig))
                .await
                .unwrap_or(false);
        }
        clear_pending_conv_volume();
    }

    /// After the AirPods' sink (re)appears, make desktop volume controls pick
    /// up the default again (see card::refresh_default). `force`: the sink was
    /// just recreated; otherwise only once per connection.
    async fn refresh_desktop_default(&self, force: bool) {
        let mac = {
            let mut state = self.state.lock().await;
            if state.default_refreshed && !force {
                return;
            }
            state.default_refreshed = true;
            state.connected_device_mac.clone()
        };
        tokio::spawn(async move {
            // Give desktop clients time to learn about the new sink first.
            tokio::time::sleep(DEFAULT_REFRESH_DELAY).await;
            if let Some(sink) = get_sink_name_by_mac(&mac).await {
                let _ = tokio::task::spawn_blocking(move || {
                    card::refresh_default(card::DefaultKind::Sink, &sink)
                })
                .await;
            }
        });
    }

    /// Restore the volume if conversation awareness ducked it.
    async fn end_conversation_ducking(&self) {
        let (started, mac) = {
            let state = self.state.lock().await;
            (state.conv_conversation_started, state.connected_device_mac.clone())
        };
        if !started {
            return;
        }
        if let Some(sink) = get_sink_name_by_mac(&mac).await {
            info!("No bud in ear; restoring the volume lowered by conversation awareness");
            self.restore_volume_if_needed(&sink).await;
        }
    }

    /// A volume ducked by conversation awareness whose restore was cut short by
    /// a disconnect: apply it once the AirPods' sink exists again.
    async fn restore_pending_conv_volume(&self) {
        let Some(volume) = crate::utils::AppSettings::load().conv_volume_restore else {
            return;
        };
        if self.state.lock().await.conv_conversation_started {
            return; // a conversation is in progress now; it restores at its end
        }
        let mac = self.state.lock().await.connected_device_mac.clone();
        for _ in 0..10 {
            if let Some(sink) = get_sink_name_by_mac(&mac).await {
                info!("Restoring volume {}% left lowered by an interrupted conversation", volume);
                tokio::task::spawn_blocking(move || transition_sink_volume(&sink, volume))
                    .await
                    .unwrap_or(false);
                clear_pending_conv_volume();
                return;
            }
            tokio::time::sleep(Duration::from_millis(300)).await;
        }
    }

    /// True if any playback stream is going to the AirPods right now.
    async fn airpods_have_streams(&self) -> bool {
        let mac = self.state.lock().await.connected_device_mac.clone();
        let Some(idx) = self.get_audio_device_index(&mac).await else {
            return false;
        };
        tokio::task::spawn_blocking(move || !card::streams_on_card(idx).is_empty())
            .await
            .unwrap_or(true)
    }

    fn list_mpris_services(conn: &Connection) -> Vec<String> {
        let proxy = conn.with_proxy(
            "org.freedesktop.DBus",
            "/org/freedesktop/DBus",
            Duration::from_secs(5),
        );

        let (names,): (Vec<String>,) =
            match proxy.method_call("org.freedesktop.DBus", "ListNames", ()) {
                Ok(n) => n,
                Err(_) => return vec![],
            };

        names
            .into_iter()
            .filter(|s| {
                s.starts_with("org.mpris.MediaPlayer2.") && !Self::is_kdeconnect_service(s)
            })
            .collect()
    }
}

/// ~8 s of 500 ms attempts waiting for the bluez card and its A2DP profiles.
const A2DP_WAIT_ATTEMPTS: u32 = 16;
const WIREPLUMBER_RESTART_COOLDOWN: Duration = Duration::from_secs(600);
static LAST_WIREPLUMBER_RESTART: std::sync::Mutex<Option<std::time::Instant>> =
    std::sync::Mutex::new(None);

fn clear_pending_conv_volume() {
    let mut settings = crate::utils::AppSettings::load();
    if settings.conv_volume_restore.take().is_some() {
        settings.save();
    }
}

/// Delay before refreshing the desktop's default device after the sink appears.
const DEFAULT_REFRESH_DELAY: Duration = Duration::from_millis(1500);

/// How long "both buds out" must last before audio switches to another output
/// (the same moment the hi-res monitor hides the AirPods mic).
const AUDIO_OFF_DEBOUNCE: Duration = card::NOT_WORN_GRACE;

impl MediaController {
    /// Move streams saved at deactivation back onto the AirPods' sink.
    async fn restore_saved_streams(&self, card_index: u32) {
        let streams = std::mem::take(&mut self.state.lock().await.saved_streams);
        if streams.is_empty() {
            return;
        }
        let _ = tokio::task::spawn_blocking(move || {
            card::move_streams_to_card(&streams, card_index, Duration::from_secs(3))
        })
        .await;
    }
}

#[derive(Debug, Default, PartialEq, Eq)]
struct EarActions {
    pause: bool,
    resume: bool,
    activate_a2dp: bool,
    deactivate_a2dp: bool,
}

/// What an ear-detection change should do. `old`/`new` are per-bud "in ear"
/// flags; `old` is empty on the first report after connecting.
///
/// - 0 buds in (from >= 1): pause media; switch audio away if
///   `audio_off_when_not_wearing` (debounced by the caller).
/// - >= 1 bud in (from 0): bring the AirPods' audio back and resume what we paused.
/// - 2 -> 1 / 1 -> 2: keep playing, unless `pause_when_one_removed`.
fn ear_actions(
    old: &[bool],
    new: &[bool],
    audio_off_when_not_wearing: bool,
    pause_when_one_removed: bool,
) -> EarActions {
    let old_in = old.iter().filter(|&&b| b).count();
    let new_in = new.iter().filter(|&&b| b).count();
    let first_report = old.is_empty();
    let mut a = EarActions::default();

    if new_in == 0 {
        if old_in > 0 {
            a.pause = true;
            a.deactivate_a2dp = audio_off_when_not_wearing;
        } else if first_report {
            // Connected while not worn (e.g. buds on the desk): don't grab audio.
            a.deactivate_a2dp = audio_off_when_not_wearing;
        }
    } else if old_in == 0 {
        a.activate_a2dp = true;
        a.resume = !first_report;
    } else if new_in < old_in {
        a.pause = pause_when_one_removed;
    } else if new_in > old_in {
        a.resume = pause_when_one_removed;
    }
    a
}

#[cfg(test)]
mod tests {
    use super::{EarActions, ear_actions};

    const BOTH: [bool; 2] = [true, true];
    const ONE: [bool; 2] = [true, false];
    const NONE: [bool; 2] = [false, false];

    #[test]
    fn one_bud_out_keeps_playing_by_default() {
        assert_eq!(ear_actions(&BOTH, &ONE, true, false), EarActions::default());
        assert_eq!(ear_actions(&ONE, &BOTH, true, false), EarActions::default());
    }

    #[test]
    fn one_bud_out_pauses_when_opted_in() {
        let a = ear_actions(&BOTH, &ONE, true, true);
        assert!(a.pause && !a.deactivate_a2dp);
        assert!(ear_actions(&ONE, &BOTH, true, true).resume);
    }

    #[test]
    fn both_out_pauses_and_switches_audio_away() {
        for old in [BOTH, ONE] {
            let a = ear_actions(&old, &NONE, true, false);
            assert!(a.pause && a.deactivate_a2dp && !a.activate_a2dp);
        }
        let a = ear_actions(&BOTH, &NONE, false, false);
        assert!(a.pause && !a.deactivate_a2dp);
    }

    #[test]
    fn bud_back_in_restores_audio_and_resumes() {
        for new in [ONE, BOTH] {
            let a = ear_actions(&NONE, &new, true, false);
            assert!(a.activate_a2dp && a.resume && !a.pause);
        }
    }

    #[test]
    fn first_report_after_connect() {
        let a = ear_actions(&[], &BOTH, true, false);
        assert!(a.activate_a2dp && !a.resume);
        let a = ear_actions(&[], &NONE, true, false);
        assert!(a.deactivate_a2dp && !a.pause);
    }

    #[test]
    fn in_case_or_disconnected_buds_count_as_out() {
        // [InCase, OutOfEar] -> [Disconnected, OutOfEar]: still nothing worn.
        assert_eq!(ear_actions(&NONE, &NONE, true, false), EarActions::default());
    }
}

// --- PulseAudio helpers ---

fn pulse_connect() -> Option<(Mainloop, Context)> {
    let mut mainloop = Mainloop::new()?;
    let mut context = Context::new(&mainloop, "LibrePods")?;
    context.connect(None, ContextFlagSet::NOAUTOSPAWN, None).ok()?;
    loop {
        mainloop.iterate(false);
        match context.get_state() {
            libpulse_binding::context::State::Ready => break,
            libpulse_binding::context::State::Failed
            | libpulse_binding::context::State::Terminated => return None,
            _ => {}
        }
    }
    Some((mainloop, context))
}

fn get_card_info_list_sync() -> Vec<OwnedCardInfo> {
    let (mut mainloop, context) = match pulse_connect() {
        Some(c) => c,
        None => return vec![],
    };

    let introspector = context.introspect();
    let cards: Rc<RefCell<Vec<OwnedCardInfo>>> = Rc::new(RefCell::new(Vec::new()));
    let op = introspector.get_card_info_list({
        let cards = cards.clone();
        move |result| {
            if let ListResult::Item(item) = result {
                let profiles = item
                    .profiles
                    .iter()
                    .map(|p| OwnedCardProfileInfo {
                        name: p.name.as_ref().map(|n| n.to_string()),
                    })
                    .collect();
                cards.borrow_mut().push(OwnedCardInfo {
                    index: item.index,
                    proplist: item.proplist.clone(),
                    profiles,
                });
            }
        }
    });

    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }
    mainloop.quit(Retval(0));
    // Clone instead of Rc::try_unwrap(..).unwrap(): if the server goes away
    // mid-request (pipewire-pulse restart) the callback still holds a reference,
    // and with panic = "abort" the unwrap would kill the whole app.
    drop(op);
    let result = cards.borrow().clone();
    result
}

fn get_sink_volume_percent_by_name_sync(sink_name: &str) -> Option<u32> {
    let (mut mainloop, context) = pulse_connect()?;
    let introspector = context.introspect();
    let sink_info: Rc<RefCell<Option<OwnedSinkInfo>>> = Rc::new(RefCell::new(None));
    let op = introspector.get_sink_info_by_name(sink_name, {
        let sink_info = sink_info.clone();
        move |result: ListResult<&SinkInfo>| {
            if let ListResult::Item(item) = result {
                *sink_info.borrow_mut() = Some(OwnedSinkInfo {
                    name: item.name.as_ref().map(|s| s.to_string()),
                    proplist: item.proplist.clone(),
                    volume: item.volume,
                });
            }
        }
    });
    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }
    mainloop.quit(Retval(0));

    let borrowed = sink_info.borrow();
    let info = borrowed.as_ref()?;
    let channels = info.volume.len();
    if channels == 0 {
        return None;
    }
    let total: f64 = info.volume.get().iter().map(|v| v.0 as f64).sum();
    let percent = ((total / channels as f64 / Volume::NORMAL.0 as f64) * 100.0).round() as u32;
    Some(percent)
}

pub fn transition_sink_volume(sink_name: &str, target_volume: u32) -> bool {
    let (mut mainloop, context) = match pulse_connect() {
        Some(c) => c,
        None => return false,
    };
    let mut introspector = context.introspect();
    let sink_info: Rc<RefCell<Option<OwnedSinkInfo>>> = Rc::new(RefCell::new(None));
    let op = introspector.get_sink_info_by_name(sink_name, {
        let sink_info = sink_info.clone();
        move |result: ListResult<&SinkInfo>| {
            if let ListResult::Item(item) = result {
                *sink_info.borrow_mut() = Some(OwnedSinkInfo {
                    name: item.name.as_ref().map(|s| s.to_string()),
                    proplist: item.proplist.clone(),
                    volume: item.volume,
                });
            }
        }
    });
    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }

    if let Some(info) = sink_info.borrow().as_ref() {
        let channels = info.volume.len();
        let mut new_volumes = ChannelVolumes::default();
        let raw =
            (((target_volume as f64) / 100.0) * Volume::NORMAL.0.as_f64().unwrap()).round() as u32;
        new_volumes.set(channels, Volume(raw));

        let op = introspector.set_sink_volume_by_name(sink_name, &new_volumes, None);
        while op.get_state() == OperationState::Running {
            mainloop.iterate(false);
        }
        mainloop.quit(Retval(0));
        true
    } else {
        error!("Sink not found: {}", sink_name);
        false
    }
}

async fn get_sink_name_by_mac(mac: &str) -> Option<String> {
    if mac.is_empty() {
        return None;
    }
    let mac_clone = mac.to_string();

    tokio::task::spawn_blocking(move || {
        let (mut mainloop, context) = pulse_connect()?;
        let introspector = context.introspect();
        let sink_list: Rc<RefCell<Vec<OwnedSinkInfo>>> = Rc::new(RefCell::new(Vec::new()));
        let op = introspector.get_sink_info_list({
            let sink_list = sink_list.clone();
            move |result: ListResult<&SinkInfo>| {
                if let ListResult::Item(item) = result {
                    sink_list.borrow_mut().push(OwnedSinkInfo {
                        name: item.name.as_ref().map(|s| s.to_string()),
                        proplist: item.proplist.clone(),
                        volume: item.volume,
                    });
                }
            }
        });
        while op.get_state() == OperationState::Running {
            mainloop.iterate(false);
        }
        mainloop.quit(Retval(0));

        for sink in sink_list.borrow().iter() {
            if let Some(device_string) = sink.proplist.get_str("device.string")
                && device_string.to_uppercase().contains(&mac_clone.to_uppercase())
                && let Some(name) = &sink.name
            {
                info!("Found sink name for MAC {}: {}", mac_clone, name);
                return Some(name.to_string());
            }
            if let Some(bluez_path) = sink.proplist.get_str("bluez.path") {
                let mac_from_path = bluez_path
                    .split('/')
                    .next_back()
                    .unwrap_or("")
                    .replace("dev_", "")
                    .replace('_', ":");
                if mac_from_path.eq_ignore_ascii_case(&mac_clone)
                    && let Some(name) = &sink.name
                {
                    info!("Found sink name for MAC {}: {}", mac_clone, name);
                    return Some(name.to_string());
                }
            }
        }
        error!("No matching sink found for MAC address: {}", mac_clone);
        None
    })
        .await
        .unwrap_or(None)
}