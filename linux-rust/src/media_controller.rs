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

#[derive(Clone)]
struct OwnedCardProfileInfo {
    name: Option<String>,
}

#[derive(Clone)]
struct OwnedCardInfo {
    index: u32,
    proplist: Proplist,
    profiles: Vec<OwnedCardProfileInfo>,
}

#[derive(Clone)]
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
                let aacp_state = aacp_manager.state.lock().await;
                if !aacp_state
                    .ear_detection_status
                    .contains(&EarDetectionStatus::InEar)
                {
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

                let connected_devices = aacp_state.connected_devices.clone();
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

        let proxy = conn.with_proxy(
            "org.freedesktop.DBus",
            "/org/freedesktop/DBus",
            Duration::from_secs(5),
        );
        let (names,): (Vec<String>,) =
            match proxy.method_call("org.freedesktop.DBus", "ListNames", ()) {
                Ok(n) => n,
                Err(_) => return false,
            };

        for service in names {
            if !service.starts_with("org.mpris.MediaPlayer2.") {
                continue;
            }
            if Self::is_kdeconnect_service(&service) {
                continue;
            }

            let proxy =
                conn.with_proxy(&service, "/org/mpris/MediaPlayer2", Duration::from_secs(5));
            if let Ok(playback_status) =
                proxy.get::<String>("org.mpris.MediaPlayer2.Player", "PlaybackStatus")
                && playback_status == "Playing"
            {
                return true;
            }
        }
        false
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

        if new_has_at_least_one_in && old_all_out {
            debug!("Condition met: buds inserted, activating A2DP and checking play state");
            self.activate_a2dp_profile().await;
            {
                let mut state = self.state.lock().await;
                if state.is_playing {
                    state.user_played_the_media = true;
                    debug!("Set user_played_the_media to true as media was playing");
                }
            }
        } else if new_all_out {
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
                {
                    let mut state = self.state.lock().await;
                    state.i_paused_the_media = false;
                }
            } else if !old_all_out {
                debug!("Pausing media as buds are not fully in ear");
                self.pause().await;
                {
                    let mut state = self.state.lock().await;
                    state.i_paused_the_media = true;
                }
            } else {
                debug!("Playing media");
                self.resume().await;
                {
                    let mut state = self.state.lock().await;
                    state.i_paused_the_media = false;
                }
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

        let device_index = state.device_index;
        let mac = state.connected_device_mac.clone();
        drop(state);

        let mut current_device_index = device_index;

        if current_device_index.is_none() {
            warn!("Device index not found, trying to get it.");
            current_device_index = self.get_audio_device_index(&mac).await;
            if let Some(idx) = current_device_index {
                let mut state = self.state.lock().await;
                state.device_index = Some(idx);
            } else {
                warn!("Could not get device index. Cannot activate A2DP profile.");
                return;
            }
        }

        if !self.is_a2dp_profile_available().await {
            warn!("A2DP profile not available, attempting to restart WirePlumber");
            if self.restart_wire_plumber().await {
                let mut state = self.state.lock().await;
                state.device_index = self
                    .get_audio_device_index(&state.connected_device_mac)
                    .await;
                debug!(
                    "Updated device_index after WirePlumber restart: {:?}",
                    state.device_index
                );
                if !self.is_a2dp_profile_available().await {
                    error!("A2DP profile still not available after WirePlumber restart");
                    return;
                }
            } else {
                error!("Could not restart WirePlumber, A2DP profile unavailable");
                return;
            }
        }

        let preferred_profile = self.get_preferred_a2dp_profile().await;
        if preferred_profile.is_empty() {
            error!("No suitable A2DP profile found");
            return;
        }

        info!("Activating A2DP profile for AirPods: {}", preferred_profile);
        let state = self.state.lock().await;
        let device_index = state.device_index;
        drop(state);

        if let Some(idx) = device_index {
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
            debug!("Listing DBus names for media players");
            let conn = Connection::new_session().unwrap();
            let proxy = conn.with_proxy(
                "org.freedesktop.DBus",
                "/org/freedesktop/DBus",
                Duration::from_secs(5),
            );
            let (names,): (Vec<String>,) = proxy
                .method_call("org.freedesktop.DBus", "ListNames", ())
                .unwrap();
            let mut paused_services = Vec::new();

            for service in names {
                if !service.starts_with("org.mpris.MediaPlayer2.") {
                    continue;
                }
                if Self::is_kdeconnect_service(&service) {
                    debug!("Skipping kdeconnect service: {}", service);
                    continue;
                }

                debug!("Checking playback status for service: {}", service);
                let proxy =
                    conn.with_proxy(&service, "/org/mpris/MediaPlayer2", Duration::from_secs(5));
                if let Ok(playback_status) =
                    proxy.get::<String>("org.mpris.MediaPlayer2.Player", "PlaybackStatus")
                    && playback_status == "Playing"
                {
                    debug!("Service {} is playing, attempting to pause", service);
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
                        debug!("Failed to pause service: {}", service);
                        error!("Failed to pause {}", service);
                    }
                }
            }
            paused_services
        })
        .await
        .unwrap();

        if !paused_services.is_empty() {
            debug!("Paused services: {:?}", paused_services);
            info!("Paused {} media player(s) via DBus", paused_services.len());
            let mut state = self.state.lock().await;
            state.paused_by_app_services = paused_services;
            state.is_playing = false;
        } else {
            debug!("No playing media players found");
            info!("No playing media players found to pause");
        }
    }

    pub async fn pause_all_media(&self) {
        debug!("Pausing all media (without tracking for resume)");

        let paused_count = tokio::task::spawn_blocking(|| {
            debug!("Listing DBus names for media players");
            let conn = Connection::new_session().unwrap();
            let proxy = conn.with_proxy(
                "org.freedesktop.DBus",
                "/org/freedesktop/DBus",
                Duration::from_secs(5),
            );
            let (names,): (Vec<String>,) = proxy
                .method_call("org.freedesktop.DBus", "ListNames", ())
                .unwrap();
            let mut paused_count = 0;

            for service in names {
                if !service.starts_with("org.mpris.MediaPlayer2.") {
                    continue;
                }
                if Self::is_kdeconnect_service(&service) {
                    debug!("Skipping kdeconnect service: {}", service);
                    continue;
                }

                debug!("Checking playback status for service: {}", service);
                let proxy =
                    conn.with_proxy(&service, "/org/mpris/MediaPlayer2", Duration::from_secs(5));
                if let Ok(playback_status) =
                    proxy.get::<String>("org.mpris.MediaPlayer2.Player", "PlaybackStatus")
                    && playback_status == "Playing"
                {
                    debug!("Service {} is playing, attempting to pause", service);
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
                        debug!("Failed to pause service: {}", service);
                        error!("Failed to pause {}", service);
                    }
                }
            }
            paused_count
        })
        .await
        .unwrap();

        if paused_count > 0 {
            info!(
                "Paused {} media player(s) due to ownership loss",
                paused_count
            );
            let mut state = self.state.lock().await;
            state.is_playing = false;
        } else {
            debug!("No playing media players found to pause");
        }
    }

    async fn resume(&self) {
        debug!("Entering resume method");
        debug!("Resuming playback");
        let state = self.state.lock().await;
        let services = state.paused_by_app_services.clone();
        drop(state);

        if services.is_empty() {
            debug!("No services to resume");
            info!("No services to resume");
            return;
        }

        let resumed_count = tokio::task::spawn_blocking(move || {
            let conn = Connection::new_session().unwrap();
            let mut resumed_count = 0;
            for service in services {
                if Self::is_kdeconnect_service(&service) {
                    debug!("Skipping kdeconnect service: {}", service);
                    continue;
                }

                debug!("Attempting to resume service: {}", service);
                let proxy =
                    conn.with_proxy(&service, "/org/mpris/MediaPlayer2", Duration::from_secs(5));
                if proxy
                    .method_call::<(), _, &str, &str>("org.mpris.MediaPlayer2.Player", "Play", ())
                    .is_ok()
                {
                    info!("Resumed playback for: {}", service);
                    resumed_count += 1;
                } else {
                    debug!("Failed to resume service: {}", service);
                    warn!("Failed to resume {}", service);
                }
            }
            resumed_count
        })
        .await
        .unwrap();

        if resumed_count > 0 {
            debug!("Resumed {} services", resumed_count);
            info!("Resumed {} media player(s) via DBus", resumed_count);
            let mut state = self.state.lock().await;
            state.paused_by_app_services.clear();
        } else {
            debug!("Failed to resume any services");
            error!("Failed to resume any media players via DBus");
        }
    }

    async fn is_a2dp_profile_available(&self) -> bool {
        debug!("Entering is_a2dp_profile_available");
        let state = self.state.lock().await;
        let device_index = state.device_index;
        drop(state);

        let index = match device_index {
            Some(i) => i,
            None => {
                debug!("Device index is None, returning false");
                return false;
            }
        };

        tokio::task::spawn_blocking(move || {
            let mut mainloop = Mainloop::new().unwrap();
            let mut context =
                Context::new(&mainloop, "LibrePods-is_a2dp_profile_available").unwrap();
            context
                .connect(None, ContextFlagSet::NOAUTOSPAWN, None)
                .unwrap();
            loop {
                match mainloop.iterate(false) {
                    _ if context.get_state() == libpulse_binding::context::State::Ready => break,
                    _ if context.get_state() == libpulse_binding::context::State::Failed
                        || context.get_state() == libpulse_binding::context::State::Terminated =>
                    {
                        return false;
                    }
                    _ => {}
                }
            }

            let introspector = context.introspect();
            let card_info_list = Rc::new(RefCell::new(None));
            let op = introspector.get_card_info_list({
                let card_info_list = card_info_list.clone();
                let mut list = Vec::new();
                move |result| match result {
                    ListResult::Item(item) => {
                        let profiles = item
                            .profiles
                            .iter()
                            .map(|p| OwnedCardProfileInfo {
                                name: p.name.as_ref().map(|n| n.to_string()),
                            })
                            .collect();
                        list.push(OwnedCardInfo {
                            index: item.index,
                            proplist: item.proplist.clone(),
                            profiles,
                        });
                    }
                    ListResult::End => *card_info_list.borrow_mut() = Some(list.clone()),
                    ListResult::Error => *card_info_list.borrow_mut() = None,
                }
            });

            while op.get_state() == OperationState::Running {
                mainloop.iterate(false);
            }
            mainloop.quit(Retval(0));

            if let Some(list) = card_info_list.borrow().as_ref()
                && let Some(card) = list.iter().find(|c| c.index == index)
            {
                let available = card.profiles.iter().any(|p| {
                    p.name
                        .as_ref()
                        .is_some_and(|name| name.starts_with("a2dp-sink"))
                });
                debug!("A2DP profile available: {}", available);
                return available;
            }
            debug!("A2DP profile not available");
            false
        })
        .await
        .unwrap_or(false)
    }

    async fn get_preferred_a2dp_profile(&self) -> String {
        debug!("Entering get_preferred_a2dp_profile");
        let state = self.state.lock().await;
        let device_index = state.device_index;
        let cached_profile = state.cached_a2dp_profile.clone();
        drop(state);

        let index = match device_index {
            Some(i) => i,
            None => {
                debug!("Device index is None, returning empty string");
                return String::new();
            }
        };

        if !cached_profile.is_empty() && self.is_profile_available(index, &cached_profile).await {
            debug!("Using cached A2DP profile: {}", cached_profile);
            return cached_profile;
        }

        let profiles_to_check = vec!["a2dp-sink-sbc_xq", "a2dp-sink-sbc", "a2dp-sink"];
        for profile in profiles_to_check {
            debug!("Checking availability of profile: {}", profile);
            if self.is_profile_available(index, profile).await {
                debug!("Selected profile: {}", profile);
                info!("Selected best available A2DP profile: {}", profile);
                let mut state = self.state.lock().await;
                state.cached_a2dp_profile = profile.to_string();
                return profile.to_string();
            }
        }
        debug!("No suitable profile found");
        String::new()
    }

    async fn is_profile_available(&self, card_index: u32, profile: &str) -> bool {
        debug!(
            "Entering is_profile_available for card index: {}, profile: {}",
            card_index, profile
        );
        let profile_name = profile.to_string();
        tokio::task::spawn_blocking(move || {
            let mut mainloop = Mainloop::new().unwrap();
            let mut context = Context::new(&mainloop, "LibrePods-is_profile_available").unwrap();
            context
                .connect(None, ContextFlagSet::NOAUTOSPAWN, None)
                .unwrap();
            loop {
                match mainloop.iterate(false) {
                    _ if context.get_state() == libpulse_binding::context::State::Ready => break,
                    _ if context.get_state() == libpulse_binding::context::State::Failed
                        || context.get_state() == libpulse_binding::context::State::Terminated =>
                    {
                        return false;
                    }
                    _ => {}
                }
            }

            let introspector = context.introspect();
            let card_info_list = Rc::new(RefCell::new(None));
            let op = introspector.get_card_info_list({
                let card_info_list = card_info_list.clone();
                let mut list = Vec::new();
                move |result| match result {
                    ListResult::Item(item) => {
                        let profiles = item
                            .profiles
                            .iter()
                            .map(|p| OwnedCardProfileInfo {
                                name: p.name.as_ref().map(|n| n.to_string()),
                            })
                            .collect();
                        list.push(OwnedCardInfo {
                            index: item.index,
                            proplist: item.proplist.clone(),
                            profiles,
                        });
                    }
                    ListResult::End => *card_info_list.borrow_mut() = Some(list.clone()),
                    ListResult::Error => *card_info_list.borrow_mut() = None,
                }
            });

            while op.get_state() == OperationState::Running {
                mainloop.iterate(false);
            }
            mainloop.quit(Retval(0));

            if let Some(list) = card_info_list.borrow().as_ref()
                && let Some(card) = list.iter().find(|c| c.index == card_index)
            {
                let available = card
                    .profiles
                    .iter()
                    .any(|p| p.name.as_ref() == Some(&profile_name));
                debug!("Profile {} available: {}", profile_name, available);
                return available;
            }
            debug!("Profile {} not available", profile_name);
            false
        })
        .await
        .unwrap_or(false)
    }

    async fn restart_wire_plumber(&self) -> bool {
        debug!("Entering restart_wire_plumber");
        info!("Restarting WirePlumber to rediscover A2DP profiles");
        let result = Command::new("systemctl")
            .args(["--user", "restart", "wireplumber"])
            .output();

        match result {
            Ok(output) if output.status.success() => {
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
        debug!("Entering get_audio_device_index for MAC: {}", mac);
        if mac.is_empty() {
            debug!("MAC is empty, returning None");
            return None;
        }
        let mac_clone = mac.to_string();

        tokio::task::spawn_blocking(move || {
            let mut mainloop = Mainloop::new().unwrap();
            let mut context = Context::new(&mainloop, "LibrePods-get_audio_device_index").unwrap();
            context
                .connect(None, ContextFlagSet::NOAUTOSPAWN, None)
                .unwrap();

            loop {
                match mainloop.iterate(false) {
                    _ if context.get_state() == libpulse_binding::context::State::Ready => break,
                    _ if context.get_state() == libpulse_binding::context::State::Failed
                        || context.get_state() == libpulse_binding::context::State::Terminated =>
                    {
                        return None;
                    }
                    _ => {}
                }
            }

            let introspector = context.introspect();
            let card_info_list = Rc::new(RefCell::new(None));
            let op = introspector.get_card_info_list({
                let card_info_list = card_info_list.clone();
                let mut list = Vec::new();
                move |result| match result {
                    ListResult::Item(item) => {
                        let profiles = item
                            .profiles
                            .iter()
                            .map(|p| OwnedCardProfileInfo {
                                name: p.name.as_ref().map(|n| n.to_string()),
                            })
                            .collect();
                        list.push(OwnedCardInfo {
                            index: item.index,
                            proplist: item.proplist.clone(),
                            profiles,
                        });
                    }
                    ListResult::End => *card_info_list.borrow_mut() = Some(list.clone()),
                    ListResult::Error => *card_info_list.borrow_mut() = None,
                }
            });

            while op.get_state() == OperationState::Running {
                mainloop.iterate(false);
            }
            mainloop.quit(Retval(0));

            if let Some(list) = card_info_list.borrow().as_ref() {
                for card in list {
                    debug!("Checking card index {} for MAC match", card.index);
                    let props = &card.proplist;
                    if let Some(device_string) = props.get_str("device.string")
                        && device_string.contains(&mac_clone)
                    {
                        info!(
                            "Found audio device index for MAC {}: {}",
                            mac_clone, card.index
                        );
                        return Some(card.index);
                    }
                }
            }
            error!(
                "No matching Bluetooth card found for MAC address: {}",
                mac_clone
            );
            None
        })
        .await
        .unwrap_or(None)
    }

    pub async fn deactivate_a2dp_profile(&self) {
        debug!("Entering deactivate_a2dp_profile");
        let mut state = self.state.lock().await;

        if state.device_index.is_none() {
            state.device_index = self
                .get_audio_device_index(&state.connected_device_mac)
                .await;
        }

        if state.connected_device_mac.is_empty() || state.device_index.is_none() {
            warn!("Connected device MAC or index is empty, cannot deactivate A2DP profile");
            return;
        }
        let device_index = state.device_index.unwrap();
        drop(state);

        info!("Deactivating A2DP profile for AirPods by setting to off");

        let success =
            tokio::task::spawn_blocking(move || set_card_profile_sync(device_index, "off"))
                .await
                .unwrap_or(false);

        if success {
            info!("Successfully deactivated A2DP profile");
        } else {
            warn!("Failed to deactivate A2DP profile");
        }
    }

    pub async fn handle_conversational_awareness(&self, status: u8) {
        debug!(
            "Entering handle_conversational_awareness with status: {}",
            status
        );

        let mac;
        {
            let state = self.state.lock().await;
            mac = state.connected_device_mac.clone();
        }
        if mac.is_empty() {
            debug!("No connected device MAC, skipping conversational awareness");
            return;
        }

        let sink_name = get_sink_name_by_mac(&mac).await;
        let sink = match sink_name {
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
                let original = {
                    let state = self.state.lock().await;
                    state.conv_original_volume
                };
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
                let maybe_orig = {
                    let state = self.state.lock().await;
                    (state.conv_conversation_started, state.conv_original_volume)
                };
                if !maybe_orig.0 {
                    debug!("Received status 3 but conversation was not started; ignoring increase");
                    return;
                }
                if let Some(orig) = maybe_orig.1 {
                    let target = if orig > 25 { 25 } else { orig };
                    let sink_clone = sink.clone();
                    tokio::task::spawn_blocking(move || {
                        transition_sink_volume(&sink_clone, target)
                    })
                    .await
                    .unwrap_or(false);
                    info!(
                        "Conversation partial increase (3): set volume to {} (original {})",
                        target, orig
                    );
                } else if let Some(orig_from_current) = current_volume_opt {
                    let target = if orig_from_current > 25 {
                        25
                    } else {
                        orig_from_current
                    };
                    let sink_clone = sink.clone();
                    tokio::task::spawn_blocking(move || {
                        transition_sink_volume(&sink_clone, target)
                    })
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
            4 => {
                let mut maybe_original = None;
                {
                    let mut state = self.state.lock().await;
                    if state.conv_conversation_started {
                        maybe_original = state.conv_original_volume;
                        state.conv_original_volume = None;
                        state.conv_conversation_started = false;
                    } else {
                        debug!(
                            "Received status 4 but conversation was not started; ignoring restore"
                        );
                        return;
                    }
                }
                if let Some(orig) = maybe_original {
                    let sink_clone = sink.clone();
                    tokio::task::spawn_blocking(move || transition_sink_volume(&sink_clone, orig))
                        .await
                        .unwrap_or(false);
                    info!("Conversation end (4): restored volume to original {}", orig);
                } else {
                    debug!("No stored original volume to restore to on status 4");
                }
            }
            6 => {
                let mut maybe_original = None;
                {
                    let mut state = self.state.lock().await;
                    if state.conv_conversation_started {
                        maybe_original = state.conv_original_volume;
                        state.conv_original_volume = None;
                        state.conv_conversation_started = false;
                    } else {
                        debug!(
                            "Received status 6 but conversation was not started; ignoring restore"
                        );
                        return;
                    }
                }
                if let Some(orig) = maybe_original {
                    let sink_clone = sink.clone();
                    tokio::task::spawn_blocking(move || transition_sink_volume(&sink_clone, orig))
                        .await
                        .unwrap_or(false);
                    info!("Conversation end (6): restored volume to original {}", orig);
                } else {
                    debug!("No stored original volume to restore to on status 6");
                }
            }
            7 => {
                let mut maybe_original = None;
                {
                    let mut state = self.state.lock().await;
                    if state.conv_conversation_started {
                        maybe_original = state.conv_original_volume;
                        state.conv_original_volume = None;
                        state.conv_conversation_started = false;
                    } else {
                        debug!(
                            "Received status 7 but conversation was not started; ignoring restore"
                        );
                        return;
                    }
                }
                if let Some(orig) = maybe_original {
                    let sink_clone = sink.clone();
                    tokio::task::spawn_blocking(move || transition_sink_volume(&sink_clone, orig))
                        .await
                        .unwrap_or(false);
                    info!("Conversation end (7): restored volume to original {}", orig);
                } else {
                    debug!("No stored original volume to restore to on status 7");
                }
            }
            _ => {
                debug!("Unknown conversational awareness status: {}", status);
            }
        }
    }
}

fn get_sink_volume_percent_by_name_sync(sink_name: &str) -> Option<u32> {
    let mut mainloop = Mainloop::new().unwrap();
    let mut context = Context::new(&mainloop, "LibrePods-get_sink_volume").unwrap();
    context
        .connect(None, ContextFlagSet::NOAUTOSPAWN, None)
        .unwrap();
    loop {
        match mainloop.iterate(false) {
            _ if context.get_state() == libpulse_binding::context::State::Ready => break,
            _ if context.get_state() == libpulse_binding::context::State::Failed
                || context.get_state() == libpulse_binding::context::State::Terminated =>
            {
                return None;
            }
            _ => {}
        }
    }

    let introspector = context.introspect();
    let sink_info_option = Rc::new(RefCell::new(None));
    let op = introspector.get_sink_info_by_name(sink_name, {
        let sink_info_option = sink_info_option.clone();
        move |result: ListResult<&SinkInfo>| {
            if let ListResult::Item(item) = result {
                let owned_item = OwnedSinkInfo {
                    name: item.name.as_ref().map(|s| s.to_string()),
                    proplist: item.proplist.clone(),
                    volume: item.volume,
                };
                *sink_info_option.borrow_mut() = Some(owned_item);
            }
        }
    });
    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }
    mainloop.quit(Retval(0));

    if let Some(sink_info) = sink_info_option.borrow().as_ref() {
        let channels = sink_info.volume.len();
        if channels == 0 {
            return None;
        }
        let total: f64 = sink_info.volume.get().iter().map(|v| v.0 as f64).sum();
        let average_raw = total / channels as f64;
        let percent = ((average_raw / Volume::NORMAL.0 as f64) * 100.0).round() as u32;
        Some(percent)
    } else {
        None
    }
}

fn set_card_profile_sync(card_index: u32, profile_name: &str) -> bool {
    let mut mainloop = Mainloop::new().unwrap();
    let mut context = Context::new(&mainloop, "LibrePods-set_card_profile").unwrap();
    context
        .connect(None, ContextFlagSet::NOAUTOSPAWN, None)
        .unwrap();

    loop {
        match mainloop.iterate(false) {
            _ if context.get_state() == libpulse_binding::context::State::Ready => break,
            _ if context.get_state() == libpulse_binding::context::State::Failed
                || context.get_state() == libpulse_binding::context::State::Terminated =>
            {
                return false;
            }
            _ => {}
        }
    }

    let mut introspector = context.introspect();
    let op = introspector.set_card_profile_by_index(card_index, profile_name, None);

    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }
    mainloop.quit(Retval(0));

    true
}

pub fn transition_sink_volume(sink_name: &str, target_volume: u32) -> bool {
    let mut mainloop = Mainloop::new().unwrap();
    let mut context = Context::new(&mainloop, "LibrePods-transition_sink_volume").unwrap();
    context
        .connect(None, ContextFlagSet::NOAUTOSPAWN, None)
        .unwrap();
    loop {
        match mainloop.iterate(false) {
            _ if context.get_state() == libpulse_binding::context::State::Ready => break,
            _ if context.get_state() == libpulse_binding::context::State::Failed
                || context.get_state() == libpulse_binding::context::State::Terminated =>
            {
                return false;
            }
            _ => {}
        }
    }

    let mut introspector = context.introspect();
    let sink_info_option = Rc::new(RefCell::new(None));
    let op = introspector.get_sink_info_by_name(sink_name, {
        let sink_info_option = sink_info_option.clone();
        move |result: ListResult<&SinkInfo>| {
            if let ListResult::Item(item) = result {
                let owned_item = OwnedSinkInfo {
                    name: item.name.as_ref().map(|s| s.to_string()),
                    proplist: item.proplist.clone(),
                    volume: item.volume,
                };
                *sink_info_option.borrow_mut() = Some(owned_item);
            }
        }
    });
    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }
    if let Some(sink_info) = sink_info_option.borrow().as_ref() {
        let channels = sink_info.volume.len();
        let mut new_volumes = ChannelVolumes::default();
        let raw =
            (((target_volume as f64) / 100.0) * Volume::NORMAL.0.as_f64().unwrap()).round() as u32;
        let vol = Volume(raw);
        new_volumes.set(channels, vol);

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
    debug!("Entering get_sink_name_by_mac for MAC: {}", mac);
    if mac.is_empty() {
        debug!("MAC is empty, returning None");
        return None;
    }
    let mac_clone = mac.to_string();

    tokio::task::spawn_blocking(move || {
        let mut mainloop = Mainloop::new().unwrap();
        let mut context = Context::new(&mainloop, "LibrePods-get_sink_name_by_mac").unwrap();
        context
            .connect(None, ContextFlagSet::NOAUTOSPAWN, None)
            .unwrap();

        loop {
            match mainloop.iterate(false) {
                _ if context.get_state() == libpulse_binding::context::State::Ready => break,
                _ if context.get_state() == libpulse_binding::context::State::Failed
                    || context.get_state() == libpulse_binding::context::State::Terminated =>
                {
                    return None;
                }
                _ => {}
            }
        }

        let introspector = context.introspect();
        let sink_info_list = Rc::new(RefCell::new(Some(Vec::new())));
        let op = introspector.get_sink_info_list({
            let sink_info_list = sink_info_list.clone();
            move |result: ListResult<&SinkInfo>| {
                if let ListResult::Item(item) = result {
                    let owned_item = OwnedSinkInfo {
                        name: item.name.as_ref().map(|s| s.to_string()),
                        proplist: item.proplist.clone(),
                        volume: item.volume,
                    };
                    sink_info_list
                        .borrow_mut()
                        .as_mut()
                        .unwrap()
                        .push(owned_item);
                }
            }
        });

        while op.get_state() == OperationState::Running {
            mainloop.iterate(false);
        }
        mainloop.quit(Retval(0));

        if let Some(list) = sink_info_list.borrow().as_ref() {
            for sink in list {
                if let Some(device_string) = sink.proplist.get_str("device.string")
                    && device_string
                        .to_uppercase()
                        .contains(&mac_clone.to_uppercase())
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
        }
        error!("No matching sink found for MAC address: {}", mac_clone);
        None
    })
    .await
    .unwrap_or(None)
}
