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
use std::rc::Rc;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;

#[derive(Clone, Debug)]
struct OwnedCardProfileInfo {
    name: Option<String>,
}

#[derive(Clone, Debug)]
struct OwnedCardInfo {
    index: u32,
    proplist: Proplist,
    active_profile: Option<String>,
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
    user_played_the_media: bool,
    i_paused_the_media: bool,
    ear_detection_enabled: bool,
    disconnect_when_not_wearing: bool,
    conv_original_volume: Option<u32>,
    conv_conversation_started: bool,
    playback_listener_running: bool,
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
            user_played_the_media: false,
            i_paused_the_media: false,
            ear_detection_enabled: true,
            disconnect_when_not_wearing: true,
            conv_original_volume: None,
            conv_conversation_started: false,
            playback_listener_running: false,
        }
    }
}

#[derive(Clone)]
pub struct MediaController {
    state: Arc<Mutex<MediaControllerState>>,
}

impl MediaController {
    pub fn new(connected_mac: String, local_mac: String) -> Self {
        let mut state = MediaControllerState::new();
        state.connected_device_mac = connected_mac;
        state.local_mac = local_mac;
        MediaController {
            state: Arc::new(Mutex::new(state)),
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
            tokio::time::sleep(Duration::from_millis(500)).await;

            let is_playing = tokio::task::spawn_blocking(|| Self::check_if_playing())
                .await
                .unwrap_or(false);

            let mut state = self.state.lock().await;
            let was_playing = state.is_playing;
            state.is_playing = is_playing;
            let local_mac = state.local_mac.clone();
            drop(state);

            if !was_playing && is_playing {
                // Copy what we need and release the lock: the sends below lock the
                // same state, so holding it here deadlocks as soon as another device
                // (e.g. an iPhone) is connected to the AirPods (upstream #896).
                let (in_ear, connected_devices) = {
                    let aacp_state = aacp_manager.state.lock().await;
                    (
                        aacp_state
                            .ear_detection_status
                            .contains(&EarDetectionStatus::InEar),
                        aacp_state.connected_devices.clone(),
                    )
                };
                if !in_ear {
                    info!("Media playback started but buds not in ear, skipping takeover");
                    continue;
                }
                info!("Media playback started, taking ownership and activating a2dp");
                let _ = control_tx.send((
                    crate::bluetooth::aacp::ControlCommandIdentifiers::OwnsConnection,
                    vec![0x01],
                ));
                self.activate_a2dp_profile().await;

                info!("already connected locally, hijacking connection by asking AirPods");

                for device in connected_devices {
                    if device.mac != local_mac {
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
                }

                debug!("completed playback takeover process");
            }
        }
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

    pub async fn handle_ear_detection(
        &self,
        old_statuses: Vec<EarDetectionStatus>,
        new_statuses: Vec<EarDetectionStatus>,
    ) {
        debug!(
            "Entering handle_ear_detection with old_statuses: {:?}, new_statuses: {:?}",
            old_statuses, new_statuses
        );

        let old_in_ear_data: Vec<bool> = old_statuses
            .iter()
            .map(|s| *s == EarDetectionStatus::InEar)
            .collect();
        let new_in_ear_data: Vec<bool> = new_statuses
            .iter()
            .map(|s| *s == EarDetectionStatus::InEar)
            .collect();

        let in_ear = new_in_ear_data.iter().all(|&b| b);
        let old_all_out = old_in_ear_data.iter().all(|&b| !b);
        let new_has_at_least_one_in = new_in_ear_data.iter().any(|&b| b);
        let new_all_out = new_in_ear_data.iter().all(|&b| !b);

        debug!(
            "Computed states: in_ear={}, old_all_out={}, new_has_at_least_one_in={}, new_all_out={}",
            in_ear, old_all_out, new_has_at_least_one_in, new_all_out
        );

        {
            let state = self.state.lock().await;
            if !state.ear_detection_enabled {
                debug!("Ear detection disabled, skipping");
                return;
            }
        }

        // During a call, leave playback and the audio profile alone: pausing or
        // switching profiles would drop the call's audio, and a bud's proximity
        // sensor can flicker while it's adjusted.
        if self.call_in_progress().await {
            info!("Call in progress; ear detection won't pause media or change the audio profile.");
            self.state.lock().await.old_in_ear_data = new_in_ear_data;
            return;
        }

        let transition = ear_transition(&old_in_ear_data, &new_in_ear_data);
        if transition == EarTransition::Inserted {
            debug!("Condition met: buds inserted, activating A2DP and checking play state");
            self.activate_a2dp_profile().await;
            {
                let mut state = self.state.lock().await;
                if state.is_playing {
                    state.user_played_the_media = true;
                    debug!("Set user_played_the_media to true as media was playing");
                }
            }
        } else if transition == EarTransition::Removed {
            // Only when the last bud comes out, not on every repeated "out" report
            // (which used to pause and turn the audio off again and again).
            debug!("Condition met: buds removed, pausing media");
            self.pause().await;
            {
                let state = self.state.lock().await;
                if state.disconnect_when_not_wearing {
                    debug!("Disconnect when not wearing enabled, deactivating A2DP");
                    drop(state);
                    self.deactivate_a2dp_profile().await;
                }
            }
        }

        let reset_user_played = (old_in_ear_data.iter().any(|&b| !b)
            && new_in_ear_data.iter().all(|&b| b))
            || (new_in_ear_data.iter().any(|&b| !b) && old_in_ear_data.iter().all(|&b| b));
        if reset_user_played {
            debug!("Transition detected, resetting user_played_the_media");
            let mut state = self.state.lock().await;
            state.user_played_the_media = false;
        }

        info!(
            "Ear Detection - old_in_ear_data: {:?}, new_in_ear_data: {:?}",
            old_in_ear_data, new_in_ear_data
        );

        let mut old_sorted = old_in_ear_data.clone();
        old_sorted.sort();
        let mut new_sorted = new_in_ear_data.clone();
        new_sorted.sort();
        if new_sorted != old_sorted {
            debug!("Ear data changed, checking resume/pause logic");
            if in_ear {
                debug!("Resuming media as buds are in ear");
                self.resume().await;
                self.state.lock().await.i_paused_the_media = false;
            } else if !old_all_out {
                debug!("Pausing media as buds are not fully in ear");
                self.pause().await;
                self.state.lock().await.i_paused_the_media = true;
            } else {
                debug!("Playing media");
                self.resume().await;
                self.state.lock().await.i_paused_the_media = false;
            }
        }

        {
            let mut state = self.state.lock().await;
            state.old_in_ear_data = new_in_ear_data;
            debug!("Updated old_in_ear_data to {:?}", state.old_in_ear_data);
        }
    }

    pub async fn activate_a2dp_profile(&self) {
        debug!("Entering activate_a2dp_profile");
        let state = self.state.lock().await;

        if state.connected_device_mac.is_empty() {
            warn!("Connected device MAC is empty, cannot activate A2DP profile");
            return;
        }

        let mac = state.connected_device_mac.clone();
        drop(state);

        let Some(card_index) = self.resolve_card_index_with(&mac, true).await else {
            warn!("Could not find the audio card. Cannot activate A2DP profile.");
            return;
        };

        // Switching the profile recreates the PipeWire sink, which interrupts the
        // stream that is starting and makes WirePlumber pause the player. Skip it
        // when A2DP is already active (upstream #766 / #913).
        match active_profile(card_index).await.as_deref() {
            Some(p) if p.starts_with("a2dp-sink") => {
                debug!("A2DP profile already active; not switching.");
                return;
            }
            // An app (e.g. a Google Meet call) is using the AirPods' microphone.
            // Switching to A2DP would cut the call's audio and microphone, and
            // WirePlumber would switch straight back, so the call keeps dropping.
            Some(p) if is_call_profile(p) => {
                info!("A call is using the AirPods ({p}); not switching to A2DP.");
                return;
            }
            _ => {}
        }

        // Profiles can appear shortly after the card itself; only fall back to
        // restarting WirePlumber (which interrupts all audio) if they never do.
        let mut a2dp_available = false;
        for _ in 0..6 {
            if self.is_a2dp_profile_available().await {
                a2dp_available = true;
                break;
            }
            tokio::time::sleep(Duration::from_millis(500)).await;
        }

        if !a2dp_available {
            // The card exists without A2DP: the profile itself isn't connected.
            // Ask BlueZ for it rather than restarting WirePlumber, which cuts all
            // audio on the system and can drop the very connection being set up.
            warn!("A2DP profile not available; asking BlueZ to connect it");
            bluez_connect_a2dp(&mac).await;
            for _ in 0..10 {
                tokio::time::sleep(Duration::from_millis(500)).await;
                if let Some(idx) = self.get_audio_device_index(&mac).await {
                    self.state.lock().await.device_index = Some(idx);
                    if self.is_a2dp_profile_available().await {
                        a2dp_available = true;
                        break;
                    }
                }
            }
            if !a2dp_available {
                error!("A2DP profile still not available for {}", mac);
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
            info!("Activating A2DP profile for AirPods: {}", preferred_profile);
            let profile_name = preferred_profile.clone();
            let success =
                tokio::task::spawn_blocking(move || set_card_profile_sync(idx, &profile_name))
                    .await
                    .unwrap_or(false);

            if success {
                info!("Successfully activated A2DP profile: {}", preferred_profile);
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

        for profile in ["a2dp-sink-sbc_xq", "a2dp-sink-sbc", "a2dp-sink"] {
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

    /// Looks up the PipeWire/PulseAudio card for `mac`, waiting up to 10 s for it to
    /// appear. Never trusts a previously cached index: cards are recreated (with a
    /// new index) whenever the device reconnects or Bluetooth restarts.
    ///
    /// With `connect_audio`, also asks BlueZ to (re)connect the A2DP profile if the
    /// card doesn't show up: BlueZ's own A2DP setup can fail with EBUSY when it
    /// races our AAP L2CAP connection right after the AirPods connect, and it
    /// does not retry by itself, leaving the AirPods with no audio at all.
    async fn resolve_card_index_with(&self, mac: &str, connect_audio: bool) -> Option<u32> {
        for attempt in 0..30 {
            if let Some(idx) = self.get_audio_device_index(mac).await {
                self.state.lock().await.device_index = Some(idx);
                return Some(idx);
            }
            if connect_audio && matches!(attempt, 4 | 12 | 20) {
                bluez_connect_a2dp(mac).await;
            }
            tokio::time::sleep(Duration::from_millis(500)).await;
        }
        self.state.lock().await.device_index = None;
        error!("No Bluetooth audio card appeared for {}", mac);
        None
    }

    async fn resolve_card_index(&self, mac: &str) -> Option<u32> {
        self.resolve_card_index_with(mac, false).await
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
                    info!("Found audio device index for MAC {}: {}", mac_clone, card.index);
                    return Some(card.index);
                }
            }
            debug!("No matching Bluetooth card found for MAC address: {}", mac_clone);
            None
        })
            .await
            .unwrap_or(None)
    }

    /// True while the AirPods are in a headset profile, i.e. a call is using them.
    async fn call_in_progress(&self) -> bool {
        let mac = self.state.lock().await.connected_device_mac.clone();
        if mac.is_empty() {
            return false;
        }
        let profile_says_call = match self.get_audio_device_index(&mac).await {
            Some(index) => active_profile(index).await.is_some_and(|p| is_call_profile(&p)),
            None => false,
        };
        // The microphone check also covers WirePlumber having dropped to A2DP mid-call.
        profile_says_call
            || tokio::task::spawn_blocking(move || mic_users_sync(&mac) > 0)
                .await
                .unwrap_or(false)
    }

    pub async fn deactivate_a2dp_profile(&self) {
        debug!("Entering deactivate_a2dp_profile");
        let mac = self.state.lock().await.connected_device_mac.clone();
        if mac.is_empty() {
            warn!("Connected device MAC is empty, cannot deactivate A2DP profile");
            return;
        }
        let Some(device_index) = self.resolve_card_index(&mac).await else {
            warn!("Could not find the audio card, cannot deactivate A2DP profile");
            return;
        };
        match active_profile(device_index).await.as_deref() {
            Some(p) if is_call_profile(p) => {
                info!("A call is using the AirPods ({p}); not turning their audio off.");
                return;
            }
            Some("off") => {
                debug!("Audio profile already off.");
                return;
            }
            _ => {}
        }

        info!("Deactivating A2DP profile for AirPods by setting to off");

        let success = tokio::task::spawn_blocking(move || {
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                set_card_profile_sync(device_index, "off")
            }))
                .unwrap_or_else(|e| {
                    warn!("Panic in set_card_profile_sync: {:?}", e);
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

/// The card's active profile, e.g. "a2dp-sink-sbc_xq", "headset-head-unit", "off".
async fn active_profile(card_index: u32) -> Option<String> {
    tokio::task::spawn_blocking(move || {
        get_card_info_list_sync()
            .into_iter()
            .find(|c| c.index == card_index)
            .and_then(|c| c.active_profile)
    })
    .await
    .ok()
    .flatten()
}

/// Headset (HFP/HSP) profiles carry the microphone: an app is in a call or
/// recording. WirePlumber switches to them automatically when that starts.
pub(crate) fn is_call_profile(profile: &str) -> bool {
    profile.starts_with("headset")
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum EarTransition {
    /// The first bud went in (from none in).
    Inserted,
    /// The last bud came out (from at least one in).
    Removed,
    Unchanged,
}

/// Compares in-ear flags (one per bud) before and after an ear-detection event.
pub(crate) fn ear_transition(old: &[bool], new: &[bool]) -> EarTransition {
    let old_any_in = old.iter().any(|&b| b);
    let new_any_in = new.iter().any(|&b| b);
    match (old_any_in, new_any_in) {
        (false, true) => EarTransition::Inserted,
        (true, false) => EarTransition::Removed,
        _ => EarTransition::Unchanged,
    }
}

/// What the call watcher should do for one card.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum CallAction {
    /// An app uses the AirPods' microphone but the card is on A2DP (no mic):
    /// switch back to a call profile.
    Repair(String),
    /// Apply the "Call audio" preference at the start of a call.
    Prefer(String),
    /// Undo a preference applied this call (the user went back to Automatic).
    Revert(String),
    /// A profile LibrePods applied was switched away from within a minute:
    /// treat it as not working on this adapter (e.g. silent mSBC / CVSD).
    MarkSilent(String),
}

const PREFER_WINDOW: Duration = Duration::from_secs(10);
const PROBATION: Duration = Duration::from_secs(60);

/// Per-card state across one call, so the "Call audio" preference is applied
/// at most once, only at the start of a call, and can be undone safely.
#[derive(Debug, Default)]
pub(crate) struct CallSession {
    started: Option<Instant>,
    preference_handled: bool,
    repair_polls: u8,
    /// The last call profile that was active and not on probation, i.e. one
    /// that was working before LibrePods changed anything.
    last_good: Option<String>,
    /// A profile LibrePods applied, when, and what was active before it.
    applied: Option<(String, Instant, String)>,
}

impl CallSession {
    pub(crate) fn tick(
        &mut self,
        now: Instant,
        active: &str,
        available: &[String],
        mic_in_use: bool,
        preference: crate::utils::CallAudio,
        silent: &[String],
    ) -> Vec<CallAction> {
        let offered = |p: &str| available.iter().any(|a| a == p);
        if !mic_in_use && !is_call_profile(active) {
            *self = Self::default(); // not in a call
            return vec![];
        }
        let mut actions = vec![];
        let started = *self.started.get_or_insert(now);

        if let Some((profile, at, before)) = self.applied.clone() {
            let target = preference.profile();
            if active == profile && target != Some(profile.as_str()) && offered(&before) {
                // Preference changed away mid-call: put back what worked.
                actions.push(CallAction::Revert(before.clone()));
                actions.push(CallAction::MarkSilent(profile));
                self.applied = None;
            } else if is_call_profile(active) && active != profile && now.duration_since(at) < PROBATION {
                actions.push(CallAction::MarkSilent(profile));
                self.applied = None;
            } else if now.duration_since(at) >= PROBATION {
                self.applied = None; // survived probation: it works
            }
        }

        if is_call_profile(active) {
            self.repair_polls = 0;
            if self.applied.is_none() {
                self.last_good = Some(active.to_string());
            }
            if !self.preference_handled {
                self.preference_handled = true;
                if let Some(target) = preference.profile()
                    && target != active
                    && offered(target)
                    && !silent.iter().any(|s| s == target)
                    && now.duration_since(started) < PREFER_WINDOW
                {
                    actions.push(CallAction::Prefer(target.to_string()));
                    self.applied = Some((target.to_string(), now, active.to_string()));
                }
            }
        } else if mic_in_use {
            // Needs two polls in a row so WirePlumber's own switch goes first.
            self.repair_polls += 1;
            if self.repair_polls >= 2 {
                self.repair_polls = 0;
                const BEST_FIRST: [&str; 3] = ["headset-head-unit", "headset-head-unit-msbc", "headset-head-unit-cvsd"];
                let usable = |p: &str| offered(p) && !silent.iter().any(|s| s == p);
                let target = self
                    .last_good
                    .clone()
                    .filter(|p| offered(p))
                    .or_else(|| preference.profile().filter(|p| usable(p)).map(str::to_string))
                    .or_else(|| BEST_FIRST.iter().find(|p| usable(p)).map(|p| p.to_string()))
                    .or_else(|| BEST_FIRST.iter().find(|p| offered(p)).map(|p| p.to_string()));
                if let Some(target) = target {
                    actions.push(CallAction::Repair(target));
                }
            }
        }
        actions
    }
}

/// Call profiles found not to work on each device during this run (e.g. silent
/// mSBC), shown in Settings and not applied again until the preference changes.
static SILENT_PROFILES: std::sync::LazyLock<std::sync::Mutex<std::collections::HashMap<String, Vec<String>>>> =
    std::sync::LazyLock::new(Default::default);

/// The call profiles found silent on any connected AirPods.
pub fn silent_call_profiles() -> Vec<String> {
    SILENT_PROFILES
        .lock()
        .map(|m| m.values().flatten().cloned().collect())
        .unwrap_or_default()
}

/// Keeps calls working on saved AirPods, on any desktop (PipeWire's PulseAudio
/// interface only): repairs the case where an app uses the AirPods' microphone
/// but the card dropped to A2DP (the other side heard silence), and applies the
/// "Call audio" preference once at the start of each call. One instance for the
/// whole app, so it doesn't pile up across reconnects.
pub async fn call_audio_watcher() {
    let mut sessions: std::collections::HashMap<String, CallSession> = Default::default();
    let mut macs: Vec<String> = vec![];
    let mut last_preference = crate::utils::load_call_audio();
    for tick in 0u64.. {
        tokio::time::sleep(Duration::from_secs(1)).await;
        if tick % 10 == 0 {
            macs = crate::bluetooth::discovery::saved_airpods().into_iter().map(|(mac, _)| mac).collect();
        }
        if macs.is_empty() {
            continue;
        }
        let preference = crate::utils::load_call_audio();
        if preference != last_preference {
            // Choosing an option again is a request to retry it.
            if let Ok(mut silent) = SILENT_PROFILES.lock() {
                silent.clear();
            }
            last_preference = preference;
        }
        let cards_macs = macs.clone();
        let cards = tokio::task::spawn_blocking(move || {
            get_card_info_list_sync()
                .into_iter()
                .filter_map(|card| {
                    let device = card.proplist.get_str("device.string")?;
                    let mac = cards_macs.iter().find(|m: &&String| device.contains(String::as_str(m)))?.clone();
                    let active = card.active_profile.clone()?;
                    let available: Vec<String> =
                        card.profiles.iter().filter_map(|p| p.name.clone()).collect();
                    let mic_in_use = mic_users_sync(&mac) > 0;
                    Some((mac, card.index, active, available, mic_in_use))
                })
                .collect::<Vec<_>>()
        })
        .await
        .unwrap_or_default();

        sessions.retain(|mac, _| cards.iter().any(|c| &c.0 == mac));
        for (mac, index, active, available, mic_in_use) in cards {
            let silent = SILENT_PROFILES.lock().ok().and_then(|m| m.get(&mac).cloned()).unwrap_or_default();
            let actions = sessions.entry(mac.clone()).or_default().tick(
                Instant::now(), &active, &available, mic_in_use, preference, &silent,
            );
            for action in actions {
                let target = match &action {
                    CallAction::MarkSilent(profile) => {
                        warn!("Call audio: {profile} was switched away from right after being applied; not using it again on {mac}");
                        if let Ok(mut silent) = SILENT_PROFILES.lock() {
                            let list = silent.entry(mac.clone()).or_default();
                            if !list.contains(profile) {
                                list.push(profile.clone());
                            }
                        }
                        continue;
                    }
                    CallAction::Repair(t) => {
                        info!("Call guard: an app is using the AirPods' microphone but they're on {active}; switching to {t}");
                        t.clone()
                    }
                    CallAction::Prefer(t) => {
                        info!("Call audio: call started on {active}; applying preference {preference:?} -> {t}");
                        t.clone()
                    }
                    CallAction::Revert(t) => {
                        info!("Call audio: preference changed mid-call; restoring {t}");
                        t.clone()
                    }
                };
                let ok = tokio::task::spawn_blocking(move || set_card_profile_sync(index, &target))
                    .await
                    .unwrap_or(false);
                if !ok {
                    warn!("Could not switch the AirPods' audio profile");
                }
            }
        }
    }
}

/// Active (not paused) recording streams on the AirPods' microphone. WirePlumber
/// keeps a placeholder microphone source for Bluetooth headsets in every
/// profile, so apps can be "recording" from it even on A2DP.
fn mic_users_sync(mac: &str) -> usize {
    let Some((mut mainloop, context)) = pulse_connect() else {
        return 0;
    };
    let introspector = context.introspect();
    let sources: Rc<RefCell<Vec<u32>>> = Rc::new(RefCell::new(Vec::new()));
    let op = introspector.get_source_info_list({
        let sources = sources.clone();
        let mac = mac.to_string();
        move |result| {
            if let ListResult::Item(source) = result
                && source.monitor_of_sink.is_none()
                && source.proplist.get_str("api.bluez5.address").is_some_and(|a| a.eq_ignore_ascii_case(&mac))
            {
                sources.borrow_mut().push(source.index);
            }
        }
    });
    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }
    let count = Rc::new(RefCell::new(0usize));
    let op = introspector.get_source_output_info_list({
        let sources = sources.clone();
        let count = count.clone();
        move |result| {
            if let ListResult::Item(output) = result
                && !output.corked
                && sources.borrow().contains(&output.source)
            {
                *count.borrow_mut() += 1;
            }
        }
    });
    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }
    mainloop.quit(Retval(0));
    let n = *count.borrow();
    n
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
                    active_profile: item
                        .active_profile
                        .as_ref()
                        .and_then(|p| p.name.as_ref().map(|n| n.to_string())),
                    profiles,
                });
            }
        }
    });

    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }
    mainloop.quit(Retval(0));
    Rc::try_unwrap(cards).unwrap().into_inner()
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

fn set_card_profile_sync(card_index: u32, profile_name: &str) -> bool {
    let (mut mainloop, context) = match pulse_connect() {
        Some(c) => c,
        None => return false,
    };
    let mut introspector = context.introspect();
    let op = introspector.set_card_profile_by_index(card_index, profile_name, None);
    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }
    mainloop.quit(Retval(0));
    true
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
/// Asks BlueZ to connect the AirPods' A2DP sink profile.
async fn bluez_connect_a2dp(mac: &str) {
    const A2DP_SINK: &str = "0000110b-0000-1000-8000-00805f9b34fb";
    let Ok(addr) = mac.parse::<bluer::Address>() else {
        return;
    };
    let result = async {
        let session = bluer::Session::new().await?;
        let device = session.default_adapter().await?.device(addr)?;
        device
            .connect_profile(&A2DP_SINK.parse().expect("valid UUID"))
            .await
    }
    .await;
    match result {
        Ok(()) => info!("Connected A2DP profile for {}", mac),
        Err(e) => warn!("BlueZ could not connect A2DP for {}: {}", mac, e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recognizes_call_profiles() {
        for p in ["headset-head-unit", "headset-head-unit-msbc", "headset-head-unit-cvsd"] {
            assert!(is_call_profile(p), "{p}");
        }
        for p in ["a2dp-sink", "a2dp-sink-sbc_xq", "off"] {
            assert!(!is_call_profile(p), "{p}");
        }
    }

    fn profiles(names: &[&str]) -> Vec<String> {
        names.iter().map(|s| s.to_string()).collect()
    }

    use crate::utils::CallAudio;
    use std::time::{Duration, Instant};

    const AVAIL: [&str; 4] = ["a2dp-sink-sbc_xq", "headset-head-unit", "headset-head-unit-msbc", "headset-head-unit-cvsd"];

    fn tick(s: &mut CallSession, t0: Instant, secs: u64, active: &str, mic: bool, pref: CallAudio, silent: &[&str]) -> Vec<CallAction> {
        let silent: Vec<String> = silent.iter().map(|s| s.to_string()).collect();
        s.tick(t0 + Duration::from_secs(secs), active, &profiles(&AVAIL), mic, pref, &silent)
    }

    #[test]
    fn automatic_never_switches_codecs() {
        let (mut s, t0) = (CallSession::default(), Instant::now());
        assert!(tick(&mut s, t0, 0, "headset-head-unit", true, CallAudio::Auto, &[]).is_empty());
        assert!(tick(&mut s, t0, 5, "headset-head-unit", true, CallAudio::Auto, &[]).is_empty());
    }

    #[test]
    fn preference_applies_once_at_call_start() {
        let (mut s, t0) = (CallSession::default(), Instant::now());
        assert_eq!(
            tick(&mut s, t0, 1, "headset-head-unit", true, CallAudio::Reliable, &[]),
            vec![CallAction::Prefer("headset-head-unit-msbc".into())]
        );
        assert!(tick(&mut s, t0, 2, "headset-head-unit-msbc", true, CallAudio::Reliable, &[]).is_empty());
        assert!(tick(&mut s, t0, 90, "headset-head-unit-msbc", true, CallAudio::Reliable, &[]).is_empty());
    }

    #[test]
    fn preference_is_not_applied_mid_call() {
        let (mut s, t0) = (CallSession::default(), Instant::now());
        assert!(tick(&mut s, t0, 0, "headset-head-unit", true, CallAudio::Auto, &[]).is_empty());
        // Chosen 5 minutes into the call: next call only.
        assert!(tick(&mut s, t0, 300, "headset-head-unit", true, CallAudio::Reliable, &[]).is_empty());
    }

    #[test]
    fn going_back_to_automatic_restores_the_working_codec() {
        // Today's case: Reliable (mSBC) was silent; choosing Automatic must bring LC3 back.
        let (mut s, t0) = (CallSession::default(), Instant::now());
        tick(&mut s, t0, 1, "headset-head-unit", true, CallAudio::Reliable, &[]);
        assert_eq!(
            tick(&mut s, t0, 20, "headset-head-unit-msbc", true, CallAudio::Auto, &[]),
            vec![CallAction::Revert("headset-head-unit".into()), CallAction::MarkSilent("headset-head-unit-msbc".into())]
        );
    }

    #[test]
    fn codec_switched_away_quickly_is_marked_silent() {
        let (mut s, t0) = (CallSession::default(), Instant::now());
        tick(&mut s, t0, 1, "headset-head-unit", true, CallAudio::Compatible, &[]);
        assert_eq!(
            tick(&mut s, t0, 15, "headset-head-unit", true, CallAudio::Compatible, &[]),
            vec![CallAction::MarkSilent("headset-head-unit-cvsd".into())]
        );
    }

    #[test]
    fn silent_codec_is_not_applied_again() {
        let (mut s, t0) = (CallSession::default(), Instant::now());
        assert!(tick(&mut s, t0, 1, "headset-head-unit", true, CallAudio::Reliable, &["headset-head-unit-msbc"]).is_empty());
    }

    #[test]
    fn repairs_mic_on_a2dp_with_last_working_codec() {
        // The 14:37 / 15:06 case: mid-call drop to A2DP while Chrome uses the mic.
        let (mut s, t0) = (CallSession::default(), Instant::now());
        tick(&mut s, t0, 0, "headset-head-unit", true, CallAudio::Auto, &[]);
        assert!(tick(&mut s, t0, 30, "a2dp-sink-sbc_xq", true, CallAudio::Auto, &[]).is_empty(), "first poll waits");
        assert_eq!(
            tick(&mut s, t0, 31, "a2dp-sink-sbc_xq", true, CallAudio::Auto, &[]),
            vec![CallAction::Repair("headset-head-unit".into())]
        );
    }

    #[test]
    fn repair_avoids_silent_codecs() {
        let (mut s, t0) = (CallSession::default(), Instant::now());
        tick(&mut s, t0, 0, "a2dp-sink-sbc_xq", true, CallAudio::Reliable, &["headset-head-unit-msbc"]);
        assert_eq!(
            tick(&mut s, t0, 1, "a2dp-sink-sbc_xq", true, CallAudio::Reliable, &["headset-head-unit-msbc"]),
            vec![CallAction::Repair("headset-head-unit".into())]
        );
    }

    #[test]
    fn music_without_a_call_is_left_alone() {
        let (mut s, t0) = (CallSession::default(), Instant::now());
        for secs in 0..5 {
            assert!(tick(&mut s, t0, secs, "a2dp-sink-sbc_xq", false, CallAudio::Reliable, &[]).is_empty());
        }
    }

    #[test]
    fn ear_transitions_fire_once() {
        use EarTransition::*;
        assert_eq!(ear_transition(&[false, false], &[true, false]), Inserted);
        assert_eq!(ear_transition(&[], &[true, true]), Inserted);
        assert_eq!(ear_transition(&[true, false], &[false, false]), Removed);
        assert_eq!(ear_transition(&[true, true], &[false, false]), Removed);
        // Repeated "both out" reports must not count as removing them again.
        assert_eq!(ear_transition(&[false, false], &[false, false]), Unchanged);
        assert_eq!(ear_transition(&[], &[false, false]), Unchanged);
        // One bud out of two is not "removed".
        assert_eq!(ear_transition(&[true, true], &[true, false]), Unchanged);
    }
}
