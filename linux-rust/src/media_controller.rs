use crate::bluetooth::aacp::{AACPManager, ControlCommandIdentifiers, EarDetectionStatus};
use dbus::arg::RefArg;
use dbus::blocking::Connection;
use dbus::blocking::stdintf::org_freedesktop_dbus::{ObjectManager, Properties};
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
use std::collections::HashMap;
use std::process::Command;
use std::rc::Rc;
use std::sync::{Arc, LazyLock, Mutex as StdMutex};
use std::time::Duration;
use tokio::sync::{Mutex, oneshot};
use tokio::task::AbortHandle;

#[derive(Clone, Debug)]
struct OwnedCardProfileInfo {
    name: Option<String>,
}

#[derive(Clone, Debug)]
struct OwnedCardInfo {
    index: u32,
    proplist: Proplist,
    profiles: Vec<OwnedCardProfileInfo>,
    active_profile: Option<String>,
}

#[derive(Clone, Debug)]
struct OwnedSinkInfo {
    name: Option<String>,
    proplist: Proplist,
    volume: ChannelVolumes,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ProfileOperationError {
    NoConnectedDevice,
    AudioDeviceNotFound,
    ConnectedDeviceChanged,
    WirePlumberRestartFailed,
    A2dpProfileUnavailable,
    NoPreferredA2dpProfile,
    ProfileWorkerStopped,
    ListenerReplaced,
    ActivationSupersededByDeactivation,
    ProfileSwitchFailed(String),
}

pub type ProfileOperationResult = std::result::Result<(), ProfileOperationError>;

const A2DP_PROFILE_ENUMERATION_GRACE: Duration = Duration::from_secs(2);
const A2DP_POST_RESTART_SETTLE: Duration = Duration::from_secs(2);
const A2DP_POST_RESTART_PROBE_LIMIT: usize = 5;
const A2DP_POST_RESTART_BACKOFF: [Duration; 4] = [
    Duration::from_secs(1),
    Duration::from_secs(2),
    Duration::from_secs(3),
    Duration::from_secs(4),
];
const BLUEZ_DBUS_TIMEOUT: Duration = Duration::from_secs(5);
const BLUEZ_DESTINATION: &str = "org.bluez";
const BLUEZ_DEVICE_INTERFACE: &str = "org.bluez.Device1";
const A2DP_SINK_UUID: &str = "0000110b-0000-1000-8000-00805f9b34fb";

type A2dpRecoveryFuture<'a, Output> =
    std::pin::Pin<Box<dyn std::future::Future<Output = Output> + Send + 'a>>;

trait A2dpRecoveryOperations: Send {
    fn probe(&mut self) -> A2dpRecoveryFuture<'_, Result<bool, ProfileOperationError>>;
    fn restart_wire_plumber(&mut self) -> A2dpRecoveryFuture<'_, bool>;
    fn sleep(&mut self, duration: Duration) -> A2dpRecoveryFuture<'_, ()>;
}

trait A2dpActivationOperations: A2dpRecoveryOperations {
    fn resolve_device_index(
        &mut self,
    ) -> A2dpRecoveryFuture<'_, Result<u32, ProfileOperationError>>;
    fn request_a2dp_connection(&mut self) -> A2dpRecoveryFuture<'_, Result<(), String>>;
    fn get_active_profile(&mut self, card_index: u32) -> A2dpRecoveryFuture<'_, Option<String>>;
    fn is_a2dp_profile_available(&mut self) -> A2dpRecoveryFuture<'_, bool>;
    fn current_device_index(
        &mut self,
    ) -> A2dpRecoveryFuture<'_, Result<u32, ProfileOperationError>>;
    fn get_preferred_a2dp_profile(&mut self, card_index: u32) -> A2dpRecoveryFuture<'_, String>;
    fn switch_profile(
        &mut self,
        card_index: u32,
        profile: String,
    ) -> A2dpRecoveryFuture<'_, ProfileOperationResult>;
}

async fn recover_a2dp_profile<Operations>(operations: &mut Operations) -> ProfileOperationResult
where
    Operations: A2dpRecoveryOperations + ?Sized,
{
    operations.sleep(A2DP_PROFILE_ENUMERATION_GRACE).await;
    if operations.probe().await? {
        return Ok(());
    }

    warn!("A2DP profile not available after grace period, restarting WirePlumber");
    if !operations.restart_wire_plumber().await {
        error!("Could not restart WirePlumber, A2DP profile unavailable");
        return Err(ProfileOperationError::WirePlumberRestartFailed);
    }

    operations.sleep(A2DP_POST_RESTART_SETTLE).await;
    for attempt in 0..A2DP_POST_RESTART_PROBE_LIMIT {
        if operations.probe().await? {
            return Ok(());
        }

        if let Some(delay) = A2DP_POST_RESTART_BACKOFF.get(attempt) {
            debug!(
                "A2DP profile still not ready after WirePlumber restart (attempt {}/{}), retrying",
                attempt + 1,
                A2DP_POST_RESTART_PROBE_LIMIT
            );
            operations.sleep(*delay).await;
        }
    }

    error!("A2DP profile still not available after WirePlumber restart");
    Err(ProfileOperationError::A2dpProfileUnavailable)
}

async fn active_a2dp_profile_is_current<Operations>(
    operations: &mut Operations,
    device_index: u32,
) -> Result<bool, ProfileOperationError>
where
    Operations: A2dpActivationOperations + ?Sized,
{
    let Some(active_profile) = operations.get_active_profile(device_index).await else {
        return Ok(false);
    };
    if should_switch_to_a2dp_profile(Some(active_profile.as_str())) {
        return Ok(false);
    }

    let current_device_index = operations.current_device_index().await?;
    if current_device_index == device_index {
        info!(
            "A2DP profile {} already active, not switching",
            active_profile
        );
        return Ok(true);
    }

    debug!(
        "Ignoring active profile {} from stale audio device index {}; current index is {}",
        active_profile, device_index, current_device_index
    );
    Ok(false)
}

async fn activate_a2dp_profile_with_operations<Operations>(
    operations: &mut Operations,
) -> ProfileOperationResult
where
    Operations: A2dpActivationOperations + ?Sized,
{
    let initial_device_index = match operations.resolve_device_index().await {
        Ok(device_index) => Some(device_index),
        Err(ProfileOperationError::AudioDeviceNotFound) => {
            if let Err(error) = operations.request_a2dp_connection().await {
                warn!("Failed to request an A2DP connection from BlueZ: {}", error);
            }
            None
        }
        Err(error) => return Err(error),
    };

    if let Some(device_index) = initial_device_index
        && active_a2dp_profile_is_current(operations, device_index).await?
    {
        return Ok(());
    }

    if initial_device_index.is_none() {
        recover_a2dp_profile(operations).await?;
    }

    let mut current_device_index = operations.resolve_device_index().await?;
    if active_a2dp_profile_is_current(operations, current_device_index).await? {
        return Ok(());
    }
    if let Some(initial_device_index) = initial_device_index
        && current_device_index != initial_device_index
    {
        debug!(
            "Audio device index changed from {} to {} during activation",
            initial_device_index, current_device_index
        );
    }

    if !operations.is_a2dp_profile_available().await {
        recover_a2dp_profile(operations).await?;
        current_device_index = operations.resolve_device_index().await?;
        if active_a2dp_profile_is_current(operations, current_device_index).await? {
            return Ok(());
        }
        if !operations.is_a2dp_profile_available().await {
            return Err(ProfileOperationError::A2dpProfileUnavailable);
        }
    }

    let preferred_profile = operations
        .get_preferred_a2dp_profile(current_device_index)
        .await;
    if preferred_profile.is_empty() {
        error!("No suitable A2DP profile found");
        return Err(ProfileOperationError::NoPreferredA2dpProfile);
    }

    operations
        .switch_profile(current_device_index, preferred_profile)
        .await
}

async fn activate_profile_then_start_listener<Activation, Listener>(
    initial_activation: Activation,
    start_listener: Listener,
) where
    Activation: std::future::Future<Output = ProfileOperationResult>,
    Listener: std::future::Future<Output = ()>,
{
    if let Err(error) = initial_activation.await {
        error!(
            "Failed to activate A2DP during media controller setup: {:?}",
            error
        );
    }
    start_listener.await;
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum RoutingPacketKind {
    MediaInformation,
    ShowUi,
    HijackRequest,
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum PlaybackTakeoverFailure {
    ProfileActivation(ProfileOperationError),
    OwnershipSend(String),
    RoutingSend {
        device_mac: String,
        packet: RoutingPacketKind,
        error: String,
    },
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum PlaybackTakeoverOutcome {
    Completed,
    SkippedNotInEar,
    AbortedDisconnected,
    AbortedConnectionChanged,
    AbortedListenerReplaced,
    AbortedEarStateChanged,
    Failed(PlaybackTakeoverFailure),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum EarProfileAction {
    ActivateA2dp,
}

fn ear_profile_action_for_transition(
    old_in_ear_data: &[bool],
    new_in_ear_data: &[bool],
) -> Option<EarProfileAction> {
    let old_all_out = old_in_ear_data.iter().all(|&in_ear| !in_ear);
    let new_has_at_least_one_in = new_in_ear_data.iter().any(|&in_ear| in_ear);

    if old_all_out && new_has_at_least_one_in {
        Some(EarProfileAction::ActivateA2dp)
    } else {
        None
    }
}

fn should_attempt_playback_takeover(
    was_playing: bool,
    is_playing: bool,
    takeover_was_complete: bool,
) -> bool {
    is_playing && (!was_playing || !takeover_was_complete)
}

fn normalized_profile_name(profile: &str) -> String {
    profile.replace('_', "-")
}

fn is_a2dp_sink_profile(profile: &str) -> bool {
    let profile = normalized_profile_name(profile);
    profile == "a2dp-sink"
        || profile
            .strip_prefix("a2dp-sink-")
            .is_some_and(|codec| !codec.is_empty())
}

fn should_switch_to_a2dp_profile(active_profile: Option<&str>) -> bool {
    !active_profile.is_some_and(is_a2dp_sink_profile)
}

fn preferred_a2dp_profile<'a>(profiles: impl IntoIterator<Item = &'a str>) -> Option<&'a str> {
    let profiles: Vec<_> = profiles.into_iter().collect();

    ["a2dp-sink-sbc-xq", "a2dp-sink-sbc", "a2dp-sink"]
        .into_iter()
        .find_map(|preferred| {
            profiles
                .iter()
                .copied()
                .find(|profile| normalized_profile_name(profile) == preferred)
        })
        .or_else(|| {
            profiles
                .iter()
                .copied()
                .find(|profile| is_a2dp_sink_profile(profile))
        })
}

fn normalized_mac(value: &str) -> String {
    value
        .chars()
        .filter(|character| !matches!(*character, ':' | '-' | '_'))
        .map(|character| character.to_ascii_lowercase())
        .collect()
}

fn bluez_device_path_for_mac<'a>(
    target_mac: &str,
    devices: impl IntoIterator<Item = (&'a str, &'a str)>,
) -> Option<String> {
    let target_mac = normalized_mac(target_mac);
    if target_mac.is_empty() {
        return None;
    }

    devices.into_iter().find_map(|(path, address)| {
        (normalized_mac(address) == target_mac).then(|| path.to_owned())
    })
}

fn request_a2dp_connection_sync(mac: &str) -> Result<(), String> {
    let connection = Connection::new_system()
        .map_err(|error| format!("could not connect to the system D-Bus: {error}"))?;
    let object_manager = connection.with_proxy(BLUEZ_DESTINATION, "/", BLUEZ_DBUS_TIMEOUT);
    let objects = object_manager
        .get_managed_objects()
        .map_err(|error| format!("BlueZ GetManagedObjects failed: {error}"))?;
    let devices: Vec<(String, String)> = objects
        .iter()
        .filter_map(|(path, interfaces)| {
            let address = interfaces
                .get(BLUEZ_DEVICE_INTERFACE)?
                .get("Address")?
                .0
                .as_str()?;
            Some((path.to_string(), address.to_owned()))
        })
        .collect();
    let device_path = bluez_device_path_for_mac(
        mac,
        devices
            .iter()
            .map(|(path, address)| (path.as_str(), address.as_str())),
    )
    .ok_or_else(|| format!("BlueZ has no Device1 object for {mac}"))?;

    let device = connection.with_proxy(BLUEZ_DESTINATION, device_path, BLUEZ_DBUS_TIMEOUT);
    device
        .method_call::<(), _, _, _>(BLUEZ_DEVICE_INTERFACE, "ConnectProfile", (A2DP_SINK_UUID,))
        .map_err(|error| format!("BlueZ ConnectProfile(A2DP) failed for {mac}: {error}"))
}

#[derive(Clone)]
struct PlaybackListenerEntry {
    generation: u64,
    abort_handle: AbortHandle,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct PlaybackListenerGeneration {
    device_mac: String,
    generation: u64,
}

#[derive(Default)]
struct PlaybackListenerRegistryState {
    next_generation: u64,
    entries: HashMap<String, PlaybackListenerEntry>,
}

#[derive(Clone, Default)]
struct PlaybackListenerRegistry {
    state: Arc<StdMutex<PlaybackListenerRegistryState>>,
}

impl PlaybackListenerRegistry {
    fn replace(&self, device_mac: &str, abort_handle: AbortHandle) -> u64 {
        let device_mac = normalized_mac(device_mac);
        let (generation, previous) = {
            let mut state = self
                .state
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            state.next_generation = state.next_generation.wrapping_add(1);
            let generation = state.next_generation;
            let previous = state.entries.insert(
                device_mac,
                PlaybackListenerEntry {
                    generation,
                    abort_handle,
                },
            );
            (generation, previous)
        };

        if let Some(previous) = previous {
            previous.abort_handle.abort();
        }
        generation
    }

    fn is_current(&self, device_mac: &str, generation: u64) -> bool {
        let device_mac = normalized_mac(device_mac);
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .entries
            .get(&device_mac)
            .is_some_and(|entry| entry.generation == generation)
    }

    fn run_if_current(&self, device_mac: &str, generation: u64, action: impl FnOnce()) -> bool {
        let device_mac = normalized_mac(device_mac);
        let state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if !state
            .entries
            .get(&device_mac)
            .is_some_and(|entry| entry.generation == generation)
        {
            return false;
        }

        action();
        true
    }

    fn remove_if_current(&self, device_mac: &str, generation: u64) -> bool {
        let device_mac = normalized_mac(device_mac);
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if !state
            .entries
            .get(&device_mac)
            .is_some_and(|entry| entry.generation == generation)
        {
            return false;
        }

        state.entries.remove(&device_mac);
        true
    }

    #[cfg(test)]
    fn current_entry(&self, device_mac: &str) -> Option<PlaybackListenerEntry> {
        let device_mac = normalized_mac(device_mac);
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .entries
            .get(&device_mac)
            .cloned()
    }
}

fn run_blocking_profile_action<Output>(
    registry: &PlaybackListenerRegistry,
    listener_generation: Option<&PlaybackListenerGeneration>,
    action: impl FnOnce() -> Output,
) -> Result<Output, ProfileOperationError> {
    let Some(listener_generation) = listener_generation else {
        return Ok(action());
    };
    let mut output = None;
    if !registry.run_if_current(
        &listener_generation.device_mac,
        listener_generation.generation,
        || output = Some(action()),
    ) {
        return Err(ProfileOperationError::ListenerReplaced);
    }

    Ok(output.expect("current listener action did not run"))
}

// MediaController instances are recreated on reconnect, so listener ownership
// must outlive any one controller while remaining scoped to the AirPods MAC.
static PLAYBACK_LISTENERS: LazyLock<PlaybackListenerRegistry> =
    LazyLock::new(PlaybackListenerRegistry::default);

struct PlaybackListenerCleanup {
    registry: PlaybackListenerRegistry,
    device_mac: String,
    generation: u64,
}

impl Drop for PlaybackListenerCleanup {
    fn drop(&mut self) {
        self.registry
            .remove_if_current(&self.device_mac, self.generation);
    }
}

fn pulse_property_matches_mac(property: &str, mac: &str) -> bool {
    let normalized_target_mac = normalized_mac(mac);
    !normalized_target_mac.is_empty()
        && normalized_mac(property).contains(normalized_target_mac.as_str())
}

struct MediaControllerState {
    connected_device_mac: String,
    local_mac: String,
    is_playing: bool,
    playback_takeover_complete: bool,
    paused_by_app_services: Vec<String>,
    device_index: Option<u32>,
    cached_a2dp_profile: String,
    a2dp_connection_requested: bool,
    old_in_ear_data: Vec<bool>,
    user_played_the_media: bool,
    i_paused_the_media: bool,
    ear_detection_enabled: bool,
    conv_original_volume: Option<u32>,
    conv_conversation_started: bool,
}

impl MediaControllerState {
    fn new() -> Self {
        MediaControllerState {
            connected_device_mac: String::new(),
            local_mac: String::new(),
            is_playing: false,
            playback_takeover_complete: false,
            paused_by_app_services: Vec::new(),
            device_index: None,
            cached_a2dp_profile: String::new(),
            a2dp_connection_requested: false,
            old_in_ear_data: vec![false, false],
            user_played_the_media: false,
            i_paused_the_media: false,
            ear_detection_enabled: true,
            conv_original_volume: None,
            conv_conversation_started: false,
        }
    }
}

#[derive(Clone, Copy)]
struct ProfileActivationEpoch {
    value: u64,
    deactivation_in_progress: bool,
}

#[derive(Default)]
struct ProfileOperationEpochState {
    value: u64,
    deactivations_in_progress: usize,
}

#[derive(Default)]
struct ProfileOperationEpoch {
    state: StdMutex<ProfileOperationEpochState>,
}

impl ProfileOperationEpoch {
    fn capture_activation(&self) -> ProfileActivationEpoch {
        let state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        ProfileActivationEpoch {
            value: state.value,
            deactivation_in_progress: state.deactivations_in_progress > 0,
        }
    }

    fn activation_is_current(&self, activation: ProfileActivationEpoch) -> bool {
        let state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        !activation.deactivation_in_progress
            && state.deactivations_in_progress == 0
            && state.value == activation.value
    }

    fn begin_deactivation(&self) {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        state.deactivations_in_progress += 1;
        state.value = state.value.wrapping_add(1);
    }

    fn invalidate_waiters(&self) {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        state.value = state.value.wrapping_add(1);
    }

    fn retain_deactivation_side_effect(&self) {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        state.deactivations_in_progress += 1;
    }

    fn finish_deactivation_side_effect(&self) {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        state.value = state.value.wrapping_add(1);
        state.deactivations_in_progress = state
            .deactivations_in_progress
            .checked_sub(1)
            .expect("profile deactivation side-effect guard underflow");
    }

    fn finish_deactivation(&self) {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        state.deactivations_in_progress = state
            .deactivations_in_progress
            .checked_sub(1)
            .expect("profile deactivation epoch guard underflow");
    }
}

struct ProfileDeactivationEpochGuard {
    epoch: Arc<ProfileOperationEpoch>,
}

impl ProfileDeactivationEpochGuard {
    fn begin(epoch: Arc<ProfileOperationEpoch>) -> Self {
        epoch.begin_deactivation();
        Self { epoch }
    }

    fn invalidate_waiters_after_side_effect(&self) {
        self.epoch.invalidate_waiters();
    }
}

impl Drop for ProfileDeactivationEpochGuard {
    fn drop(&mut self) {
        self.epoch.finish_deactivation();
    }
}

struct ProfileDeactivationSideEffectGuard {
    epoch: Arc<ProfileOperationEpoch>,
}

impl ProfileDeactivationSideEffectGuard {
    fn begin(epoch: Arc<ProfileOperationEpoch>) -> Self {
        epoch.retain_deactivation_side_effect();
        Self { epoch }
    }
}

impl Drop for ProfileDeactivationSideEffectGuard {
    fn drop(&mut self) {
        self.epoch.finish_deactivation_side_effect();
    }
}

#[derive(Clone)]
pub struct MediaController {
    state: Arc<Mutex<MediaControllerState>>,
    device_index_lookup_lock: Arc<Mutex<()>>,
    profile_operation_lock: Arc<Mutex<()>>,
    profile_operation_epoch: Arc<ProfileOperationEpoch>,
    playback_listeners: PlaybackListenerRegistry,
    playback_listener_generation: Arc<StdMutex<Option<PlaybackListenerGeneration>>>,
}

struct MediaControllerA2dpOperations<'a> {
    controller: &'a MediaController,
    expected_mac: &'a str,
    listener_generation: Option<PlaybackListenerGeneration>,
}

impl A2dpRecoveryOperations for MediaControllerA2dpOperations<'_> {
    fn probe(&mut self) -> A2dpRecoveryFuture<'_, Result<bool, ProfileOperationError>> {
        let controller = self.controller;
        let expected_mac = self.expected_mac;
        Box::pin(async move {
            controller
                .probe_a2dp_profile_with_lookup(
                    expected_mac,
                    controller.get_audio_device_index(expected_mac),
                    controller.is_a2dp_profile_available(),
                )
                .await
        })
    }

    fn restart_wire_plumber(&mut self) -> A2dpRecoveryFuture<'_, bool> {
        let controller = self.controller;
        let listener_generation = self.listener_generation.clone();
        Box::pin(async move { controller.restart_wire_plumber(listener_generation).await })
    }

    fn sleep(&mut self, duration: Duration) -> A2dpRecoveryFuture<'_, ()> {
        Box::pin(async move { tokio::time::sleep(duration).await })
    }
}

impl A2dpActivationOperations for MediaControllerA2dpOperations<'_> {
    fn resolve_device_index(
        &mut self,
    ) -> A2dpRecoveryFuture<'_, Result<u32, ProfileOperationError>> {
        let controller = self.controller;
        let expected_mac = self.expected_mac;
        Box::pin(async move {
            controller
                .resolve_device_index(
                    expected_mac,
                    controller.get_audio_device_index(expected_mac),
                )
                .await
        })
    }

    fn request_a2dp_connection(&mut self) -> A2dpRecoveryFuture<'_, Result<(), String>> {
        let controller = self.controller;
        let expected_mac = self.expected_mac;
        let listener_generation = self.listener_generation.clone();
        Box::pin(async move {
            controller
                .request_a2dp_connection_once_for_listener_with(
                    expected_mac,
                    listener_generation,
                    |mac| request_a2dp_connection_sync(&mac),
                )
                .await
        })
    }

    fn get_active_profile(&mut self, card_index: u32) -> A2dpRecoveryFuture<'_, Option<String>> {
        let controller = self.controller;
        Box::pin(async move { controller.get_active_profile(card_index).await })
    }

    fn is_a2dp_profile_available(&mut self) -> A2dpRecoveryFuture<'_, bool> {
        let controller = self.controller;
        Box::pin(async move { controller.is_a2dp_profile_available().await })
    }

    fn current_device_index(
        &mut self,
    ) -> A2dpRecoveryFuture<'_, Result<u32, ProfileOperationError>> {
        let controller = self.controller;
        let expected_mac = self.expected_mac;
        Box::pin(async move {
            let state = controller.state.lock().await;
            if state.connected_device_mac != expected_mac {
                return Err(ProfileOperationError::ConnectedDeviceChanged);
            }
            state
                .device_index
                .ok_or(ProfileOperationError::AudioDeviceNotFound)
        })
    }

    fn get_preferred_a2dp_profile(&mut self, card_index: u32) -> A2dpRecoveryFuture<'_, String> {
        let controller = self.controller;
        Box::pin(async move { controller.get_preferred_a2dp_profile(card_index).await })
    }

    fn switch_profile(
        &mut self,
        card_index: u32,
        profile: String,
    ) -> A2dpRecoveryFuture<'_, ProfileOperationResult> {
        let playback_listeners = self.controller.playback_listeners.clone();
        let listener_generation = self.listener_generation.clone();
        Box::pin(async move {
            info!("Activating A2DP profile for AirPods: {}", profile);
            let profile_name = profile.clone();
            let success = tokio::task::spawn_blocking(move || {
                run_blocking_profile_action(
                    &playback_listeners,
                    listener_generation.as_ref(),
                    || set_card_profile_sync(card_index, &profile_name),
                )
            })
            .await
            .map_err(|_| ProfileOperationError::ProfileWorkerStopped)??;

            if !success {
                warn!("Failed to activate A2DP profile: {}", profile);
                return Err(ProfileOperationError::ProfileSwitchFailed(profile));
            }

            info!("Successfully activated A2DP profile: {}", profile);
            Ok(())
        })
    }
}

impl MediaController {
    pub fn new(connected_mac: String, local_mac: String) -> Self {
        Self::with_playback_listener_registry(
            connected_mac,
            local_mac,
            (*PLAYBACK_LISTENERS).clone(),
        )
    }

    fn with_playback_listener_registry(
        connected_mac: String,
        local_mac: String,
        playback_listeners: PlaybackListenerRegistry,
    ) -> Self {
        let mut state = MediaControllerState::new();
        state.connected_device_mac = connected_mac;
        state.local_mac = local_mac;
        MediaController {
            state: Arc::new(Mutex::new(state)),
            device_index_lookup_lock: Arc::new(Mutex::new(())),
            profile_operation_lock: Arc::new(Mutex::new(())),
            profile_operation_epoch: Arc::new(ProfileOperationEpoch::default()),
            playback_listeners,
            playback_listener_generation: Arc::new(StdMutex::new(None)),
        }
    }

    pub(crate) fn is_current_playback_session(&self) -> bool {
        let listener_generation = self
            .playback_listener_generation
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        listener_generation.is_some_and(|listener_generation| {
            self.playback_listeners.is_current(
                &listener_generation.device_mac,
                listener_generation.generation,
            )
        })
    }

    pub async fn start_playback_listener(&self, aacp_manager: AACPManager) {
        self.start_playback_listener_with_activation(aacp_manager, |controller| async move {
            controller.activate_a2dp_profile().await
        })
        .await;
    }

    #[cfg(test)]
    async fn start_playback_listener_without_initial_activation(&self, aacp_manager: AACPManager) {
        self.start_playback_listener_with_activation(aacp_manager, |_| async { Ok(()) })
            .await;
    }

    async fn start_playback_listener_with_activation<Activate, Activation>(
        &self,
        aacp_manager: AACPManager,
        initial_activation: Activate,
    ) where
        Activate: FnOnce(MediaController) -> Activation + Send + 'static,
        Activation: std::future::Future<Output = ProfileOperationResult> + Send + 'static,
    {
        let connected_device_mac = self.state.lock().await.connected_device_mac.clone();
        let (generation_tx, generation_rx) = oneshot::channel();

        let controller_clone = self.clone();
        let listener_device_mac = connected_device_mac.clone();
        let listener = tokio::spawn(async move {
            let Ok(listener_generation) = generation_rx.await else {
                return;
            };
            let _cleanup = PlaybackListenerCleanup {
                registry: controller_clone.playback_listeners.clone(),
                device_mac: listener_device_mac.clone(),
                generation: listener_generation,
            };
            activate_profile_then_start_listener(
                initial_activation(controller_clone.clone()),
                controller_clone.playback_listener_loop(
                    aacp_manager,
                    &listener_device_mac,
                    listener_generation,
                ),
            )
            .await;
        });
        let listener_generation = self
            .playback_listeners
            .replace(&connected_device_mac, listener.abort_handle());
        *self
            .playback_listener_generation
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(PlaybackListenerGeneration {
            device_mac: connected_device_mac.clone(),
            generation: listener_generation,
        });
        if generation_tx.send(listener_generation).is_err() {
            self.playback_listeners
                .remove_if_current(&connected_device_mac, listener_generation);
        }
    }

    async fn playback_listener_loop(
        &self,
        aacp_manager: AACPManager,
        listener_device_mac: &str,
        listener_generation: u64,
    ) {
        info!("Starting playback listener loop");
        loop {
            tokio::time::sleep(Duration::from_millis(500)).await;

            if !self
                .playback_listeners
                .is_current(listener_device_mac, listener_generation)
            {
                return;
            }

            let is_playing = tokio::task::spawn_blocking(|| Self::check_if_playing())
                .await
                .unwrap_or(false);

            if !self
                .playback_listeners
                .is_current(listener_device_mac, listener_generation)
            {
                return;
            }

            let mut state = self.state.lock().await;
            let was_playing = state.is_playing;
            let takeover_was_complete = state.playback_takeover_complete;
            state.is_playing = is_playing;
            if !is_playing {
                state.playback_takeover_complete = false;
            }
            let local_mac = state.local_mac.clone();
            drop(state);

            if should_attempt_playback_takeover(was_playing, is_playing, takeover_was_complete) {
                let outcome = self
                    .handle_playback_started(
                        &aacp_manager,
                        &local_mac,
                        listener_device_mac,
                        listener_generation,
                        self.activate_a2dp_profile(),
                    )
                    .await;
                match &outcome {
                    PlaybackTakeoverOutcome::Completed => {}
                    PlaybackTakeoverOutcome::SkippedNotInEar => {
                        info!("Media playback started but buds not in ear, skipping takeover");
                    }
                    PlaybackTakeoverOutcome::AbortedDisconnected => {
                        warn!("Playback takeover aborted because AACP disconnected");
                    }
                    PlaybackTakeoverOutcome::AbortedConnectionChanged => {
                        warn!("Playback takeover aborted because the AACP connection changed");
                    }
                    PlaybackTakeoverOutcome::AbortedListenerReplaced => {
                        debug!("Playback takeover aborted because the listener was replaced");
                    }
                    PlaybackTakeoverOutcome::AbortedEarStateChanged => {
                        info!("Playback takeover aborted because the buds left the ear");
                    }
                    PlaybackTakeoverOutcome::Failed(failure) => {
                        error!("Playback takeover failed: {:?}", failure);
                    }
                }
                if !self
                    .record_takeover_outcome(listener_device_mac, listener_generation, &outcome)
                    .await
                {
                    return;
                }
            }
        }
    }

    async fn record_takeover_outcome(
        &self,
        listener_device_mac: &str,
        listener_generation: u64,
        outcome: &PlaybackTakeoverOutcome,
    ) -> bool {
        if matches!(outcome, PlaybackTakeoverOutcome::AbortedListenerReplaced) {
            return false;
        }

        let takeover_completed = matches!(outcome, PlaybackTakeoverOutcome::Completed);
        let mut state = self.state.lock().await;
        self.playback_listeners
            .run_if_current(listener_device_mac, listener_generation, || {
                state.playback_takeover_complete = takeover_completed
            })
    }

    async fn handle_playback_started(
        &self,
        aacp_manager: &AACPManager,
        local_mac: &str,
        listener_device_mac: &str,
        listener_generation: u64,
        activate_a2dp: impl std::future::Future<Output = ProfileOperationResult>,
    ) -> PlaybackTakeoverOutcome {
        // Task abortion cannot stop an already-running blocking profile switch, so
        // also fence every takeover boundary with the listener generation.
        if !self
            .playback_listeners
            .is_current(listener_device_mac, listener_generation)
        {
            return PlaybackTakeoverOutcome::AbortedListenerReplaced;
        }

        let connection_generation = {
            let aacp_state = aacp_manager.state.lock().await;
            if aacp_state.sender.is_none() {
                return PlaybackTakeoverOutcome::AbortedDisconnected;
            }
            if !aacp_state
                .ear_detection_status
                .contains(&EarDetectionStatus::InEar)
            {
                return PlaybackTakeoverOutcome::SkippedNotInEar;
            }
            aacp_state.connection_generation
        };

        if !self
            .playback_listeners
            .is_current(listener_device_mac, listener_generation)
        {
            return PlaybackTakeoverOutcome::AbortedListenerReplaced;
        }

        info!("Media playback started, activating A2DP before taking ownership");
        let activation_result = activate_a2dp.await;
        if !self
            .playback_listeners
            .is_current(listener_device_mac, listener_generation)
        {
            return PlaybackTakeoverOutcome::AbortedListenerReplaced;
        }
        if let Err(error) = activation_result {
            return PlaybackTakeoverOutcome::Failed(PlaybackTakeoverFailure::ProfileActivation(
                error,
            ));
        }

        {
            let aacp_state = aacp_manager.state.lock().await;
            if aacp_state.connection_generation != connection_generation {
                return PlaybackTakeoverOutcome::AbortedConnectionChanged;
            }
            if aacp_state.sender.is_none() {
                return PlaybackTakeoverOutcome::AbortedDisconnected;
            }
            if !aacp_state
                .ear_detection_status
                .contains(&EarDetectionStatus::InEar)
            {
                return PlaybackTakeoverOutcome::AbortedEarStateChanged;
            }
        }

        if !self
            .playback_listeners
            .is_current(listener_device_mac, listener_generation)
        {
            return PlaybackTakeoverOutcome::AbortedListenerReplaced;
        }

        if let Err(error) = aacp_manager
            .send_control_command_for_generation(
                ControlCommandIdentifiers::OwnsConnection,
                &[0x01],
                connection_generation,
            )
            .await
        {
            return PlaybackTakeoverOutcome::Failed(PlaybackTakeoverFailure::OwnershipSend(
                error.to_string(),
            ));
        }

        if !self
            .playback_listeners
            .is_current(listener_device_mac, listener_generation)
        {
            return PlaybackTakeoverOutcome::AbortedListenerReplaced;
        }

        let connected_devices = {
            let aacp_state = aacp_manager.state.lock().await;
            if aacp_state.connection_generation != connection_generation {
                return PlaybackTakeoverOutcome::AbortedConnectionChanged;
            }
            if aacp_state.sender.is_none() {
                return PlaybackTakeoverOutcome::AbortedDisconnected;
            }
            if !aacp_state
                .ear_detection_status
                .contains(&EarDetectionStatus::InEar)
            {
                return PlaybackTakeoverOutcome::AbortedEarStateChanged;
            }
            aacp_state.connected_devices.clone()
        };

        if !self
            .playback_listeners
            .is_current(listener_device_mac, listener_generation)
        {
            return PlaybackTakeoverOutcome::AbortedListenerReplaced;
        }

        info!("Ownership queued, sending smart-routing takeover packets");
        for device in connected_devices {
            if device.mac == local_mac {
                continue;
            }

            if !self
                .playback_listeners
                .is_current(listener_device_mac, listener_generation)
            {
                return PlaybackTakeoverOutcome::AbortedListenerReplaced;
            }
            if let Err(error) = aacp_manager
                .send_media_information_for_generation(
                    local_mac,
                    &device.mac,
                    true,
                    connection_generation,
                )
                .await
            {
                return PlaybackTakeoverOutcome::Failed(PlaybackTakeoverFailure::RoutingSend {
                    device_mac: device.mac,
                    packet: RoutingPacketKind::MediaInformation,
                    error: error.to_string(),
                });
            }

            if !self
                .playback_listeners
                .is_current(listener_device_mac, listener_generation)
            {
                return PlaybackTakeoverOutcome::AbortedListenerReplaced;
            }
            if let Err(error) = aacp_manager
                .send_smart_routing_show_ui_for_generation(&device.mac, connection_generation)
                .await
            {
                return PlaybackTakeoverOutcome::Failed(PlaybackTakeoverFailure::RoutingSend {
                    device_mac: device.mac,
                    packet: RoutingPacketKind::ShowUi,
                    error: error.to_string(),
                });
            }

            if !self
                .playback_listeners
                .is_current(listener_device_mac, listener_generation)
            {
                return PlaybackTakeoverOutcome::AbortedListenerReplaced;
            }
            if let Err(error) = aacp_manager
                .send_hijack_request_for_generation(&device.mac, connection_generation)
                .await
            {
                return PlaybackTakeoverOutcome::Failed(PlaybackTakeoverFailure::RoutingSend {
                    device_mac: device.mac,
                    packet: RoutingPacketKind::HijackRequest,
                    error: error.to_string(),
                });
            }
        }

        if !self
            .playback_listeners
            .is_current(listener_device_mac, listener_generation)
        {
            return PlaybackTakeoverOutcome::AbortedListenerReplaced;
        }

        debug!("Completed playback takeover process");
        PlaybackTakeoverOutcome::Completed
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

        if let Some(EarProfileAction::ActivateA2dp) =
            ear_profile_action_for_transition(&old_in_ear_data, &new_in_ear_data)
        {
            debug!("Condition met: buds inserted, activating A2DP and checking play state");
            if let Err(error) = self.activate_a2dp_profile().await {
                error!("Failed to activate A2DP after ear insertion: {:?}", error);
            }
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

    pub async fn activate_a2dp_profile(&self) -> ProfileOperationResult {
        debug!("Entering activate_a2dp_profile");
        let mac = {
            let state = self.state.lock().await;
            state.connected_device_mac.clone()
        };

        if mac.is_empty() {
            warn!("Connected device MAC is empty, cannot activate A2DP profile");
            return Err(ProfileOperationError::NoConnectedDevice);
        }

        let listener_generation = self
            .playback_listener_generation
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        let mut operations = MediaControllerA2dpOperations {
            controller: self,
            expected_mac: &mac,
            listener_generation,
        };
        self.run_a2dp_activation(activate_a2dp_profile_with_operations(&mut operations))
            .await
    }

    async fn run_a2dp_activation<Activation>(
        &self,
        activation: Activation,
    ) -> ProfileOperationResult
    where
        Activation: std::future::Future<Output = ProfileOperationResult> + Send,
    {
        let activation_epoch = self.profile_operation_epoch.capture_activation();
        let _operation_guard = self.profile_operation_lock.lock().await;
        if !self
            .profile_operation_epoch
            .activation_is_current(activation_epoch)
        {
            return Err(ProfileOperationError::ActivationSupersededByDeactivation);
        }
        activation.await
    }

    async fn run_a2dp_deactivation<Deactivation>(
        &self,
        deactivation: Deactivation,
    ) -> ProfileOperationResult
    where
        Deactivation: std::future::Future<Output = ProfileOperationResult> + Send,
    {
        let deactivation_epoch =
            ProfileDeactivationEpochGuard::begin(Arc::clone(&self.profile_operation_epoch));
        let operation_guard = self.profile_operation_lock.lock().await;
        let result = deactivation.await;
        deactivation_epoch.invalidate_waiters_after_side_effect();
        drop(operation_guard);
        drop(deactivation_epoch);
        result
    }

    #[cfg(test)]
    async fn request_a2dp_connection_once_with<Request>(
        &self,
        expected_mac: &str,
        request: Request,
    ) -> Result<(), String>
    where
        Request: FnOnce(String) -> Result<(), String> + Send + 'static,
    {
        self.request_a2dp_connection_once_for_listener_with(expected_mac, None, request)
            .await
    }

    async fn request_a2dp_connection_once_for_listener_with<Request>(
        &self,
        expected_mac: &str,
        listener_generation: Option<PlaybackListenerGeneration>,
        request: Request,
    ) -> Result<(), String>
    where
        Request: FnOnce(String) -> Result<(), String> + Send + 'static,
    {
        {
            let mut state = self.state.lock().await;
            if state.connected_device_mac != expected_mac {
                return Err("connected device changed before the A2DP request".into());
            }
            if state.a2dp_connection_requested {
                debug!("A2DP was already requested for this media controller connection");
                return Ok(());
            }
            state.a2dp_connection_requested = true;
        }

        info!(
            "Requesting an A2DP connection from BlueZ for {}",
            expected_mac
        );
        let mac = expected_mac.to_owned();
        let playback_listeners = self.playback_listeners.clone();
        let result = tokio::task::spawn_blocking(move || {
            run_blocking_profile_action(&playback_listeners, listener_generation.as_ref(), || {
                request(mac)
            })
        })
        .await
        .map_err(|error| format!("A2DP request worker stopped: {error}"))?;
        match result {
            Ok(request_result) => request_result,
            Err(ProfileOperationError::ListenerReplaced) => {
                Err("playback listener replaced before the A2DP request".into())
            }
            Err(error) => Err(format!("A2DP request skipped: {error:?}")),
        }
    }

    async fn run_blocking_deactivation_action<Action>(
        &self,
        listener_generation: Option<PlaybackListenerGeneration>,
        action: Action,
    ) -> Result<bool, ProfileOperationError>
    where
        Action: FnOnce() -> bool + Send + 'static,
    {
        let playback_listeners = self.playback_listeners.clone();
        let side_effect_guard =
            ProfileDeactivationSideEffectGuard::begin(Arc::clone(&self.profile_operation_epoch));
        tokio::task::spawn_blocking(move || {
            let _side_effect_guard = side_effect_guard;
            run_blocking_profile_action(&playback_listeners, listener_generation.as_ref(), action)
        })
        .await
        .map_err(|_| ProfileOperationError::ProfileWorkerStopped)?
    }

    async fn resolve_device_index<Lookup>(
        &self,
        expected_mac: &str,
        lookup: Lookup,
    ) -> Result<u32, ProfileOperationError>
    where
        Lookup: std::future::Future<Output = Option<u32>>,
    {
        let _lookup_guard = self.device_index_lookup_lock.lock().await;
        let device_index = lookup.await;
        {
            let mut state = self.state.lock().await;
            if state.connected_device_mac != expected_mac {
                return Err(ProfileOperationError::ConnectedDeviceChanged);
            }
            state.device_index = device_index;
        }

        device_index.ok_or_else(|| {
            warn!("Could not find the current audio device index by MAC");
            ProfileOperationError::AudioDeviceNotFound
        })
    }

    async fn probe_a2dp_profile_with_lookup<Lookup, Recheck>(
        &self,
        expected_mac: &str,
        lookup: Lookup,
        recheck: Recheck,
    ) -> Result<bool, ProfileOperationError>
    where
        Lookup: std::future::Future<Output = Option<u32>>,
        Recheck: std::future::Future<Output = bool>,
    {
        let refreshed_device_index = match self.resolve_device_index(expected_mac, lookup).await {
            Ok(device_index) => device_index,
            Err(ProfileOperationError::AudioDeviceNotFound) => return Ok(false),
            Err(error) => return Err(error),
        };

        debug!(
            "Refreshed device_index while probing for A2DP profile: {}",
            refreshed_device_index
        );

        Ok(recheck.await)
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

    pub async fn pause_all_media(&self) -> bool {
        debug!("Pausing all media (without tracking for resume)");

        self.pause_all_media_with(|| {
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
    }

    async fn pause_all_media_with<Pause>(&self, pause: Pause) -> bool
    where
        Pause: FnOnce() -> usize + Send + 'static,
    {
        let listener_generation = self
            .playback_listener_generation
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        let Some(listener_generation) = listener_generation else {
            debug!("Skipping ownership pause without a playback listener generation");
            return false;
        };
        let playback_listeners = self.playback_listeners.clone();
        let paused_count = match tokio::task::spawn_blocking(move || {
            run_blocking_profile_action(&playback_listeners, Some(&listener_generation), pause)
        })
        .await
        {
            Ok(Ok(paused_count)) => paused_count,
            Ok(Err(ProfileOperationError::ListenerReplaced)) => {
                debug!("Skipping ownership pause for a replaced playback listener");
                return false;
            }
            Ok(Err(error)) => {
                error!("Ownership pause skipped: {:?}", error);
                return false;
            }
            Err(error) => {
                error!("Ownership pause worker stopped: {}", error);
                return false;
            }
        };

        if paused_count > 0 {
            info!(
                "Paused {} media player(s) due to ownership loss",
                paused_count
            );
            self.state.lock().await.is_playing = false;
        } else {
            debug!("No playing media players found to pause");
        }
        true
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
        let device_index = {
            let state = self.state.lock().await;
            state.device_index
        };
        let index = match device_index {
            Some(index) => index,
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
                    preferred_a2dp_profile(card.profiles.iter().filter_map(|p| p.name.as_deref()))
                        .is_some()
                })
                .unwrap_or(false);
            debug!("A2DP profile available: {}", available);
            available
        })
        .await
        .unwrap_or(false)
    }

    async fn get_preferred_a2dp_profile(&self, device_index: u32) -> String {
        let state = self.state.lock().await;
        let cached_profile = state.cached_a2dp_profile.clone();
        drop(state);

        if !cached_profile.is_empty()
            && self
                .is_profile_available(device_index, &cached_profile)
                .await
        {
            debug!("Using cached A2DP profile: {}", cached_profile);
            return cached_profile;
        }

        let profile = tokio::task::spawn_blocking(move || {
            get_card_info_list_sync()
                .iter()
                .find(|card| card.index == device_index)
                .and_then(|card| {
                    preferred_a2dp_profile(
                        card.profiles
                            .iter()
                            .filter_map(|profile| profile.name.as_deref()),
                    )
                })
                .map(str::to_owned)
        })
        .await
        .unwrap_or(None);

        if let Some(profile) = profile {
            info!("Selected best available A2DP profile: {}", profile);
            self.state.lock().await.cached_a2dp_profile = profile.clone();
            return profile;
        }
        debug!("No suitable profile found");
        String::new()
    }

    async fn get_active_profile(&self, card_index: u32) -> Option<String> {
        tokio::task::spawn_blocking(move || {
            get_card_info_list_sync()
                .into_iter()
                .find(|card| card.index == card_index)
                .and_then(|card| card.active_profile)
        })
        .await
        .unwrap_or(None)
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

    async fn restart_wire_plumber(
        &self,
        listener_generation: Option<PlaybackListenerGeneration>,
    ) -> bool {
        self.restart_wire_plumber_with(listener_generation, || {
            Command::new("systemctl")
                .args(["--user", "restart", "wireplumber"])
                .output()
                .is_ok_and(|output| output.status.success())
        })
        .await
    }

    async fn restart_wire_plumber_with<Restart>(
        &self,
        listener_generation: Option<PlaybackListenerGeneration>,
        restart: Restart,
    ) -> bool
    where
        Restart: FnOnce() -> bool + Send + 'static,
    {
        info!("Restarting WirePlumber to rediscover A2DP profiles");
        let playback_listeners = self.playback_listeners.clone();
        let result = tokio::task::spawn_blocking(move || {
            run_blocking_profile_action(&playback_listeners, listener_generation.as_ref(), restart)
        })
        .await;

        match result {
            Ok(Ok(true)) => {
                info!("WirePlumber restarted successfully");
                true
            }
            Ok(Ok(false)) => {
                error!("Failed to restart WirePlumber. Do you use wireplumber?");
                false
            }
            Ok(Err(ProfileOperationError::ListenerReplaced)) => {
                debug!("Skipping WirePlumber restart for a replaced playback listener");
                false
            }
            Ok(Err(error)) => {
                error!("WirePlumber restart skipped: {:?}", error);
                false
            }
            Err(error) => {
                error!("WirePlumber restart worker stopped: {}", error);
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
                    && pulse_property_matches_mac(&device_string, &mac_clone)
                {
                    info!(
                        "Found audio device index for MAC {}: {}",
                        mac_clone, card.index
                    );
                    return Some(card.index);
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

    pub async fn deactivate_a2dp_profile(&self) -> ProfileOperationResult {
        debug!("Entering deactivate_a2dp_profile");
        let mac = {
            let state = self.state.lock().await;
            state.connected_device_mac.clone()
        };

        if mac.is_empty() {
            warn!("Connected device MAC is empty, cannot deactivate A2DP profile");
            return Err(ProfileOperationError::NoConnectedDevice);
        }

        let listener_generation = self
            .playback_listener_generation
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        self.run_a2dp_deactivation(self.deactivate_a2dp_profile_for_listener_with_lookup(
            &mac,
            self.get_audio_device_index(&mac),
            listener_generation,
        ))
        .await
    }

    #[cfg(test)]
    async fn deactivate_a2dp_profile_with_lookup<Lookup>(
        &self,
        mac: &str,
        lookup: Lookup,
    ) -> ProfileOperationResult
    where
        Lookup: std::future::Future<Output = Option<u32>>,
    {
        self.deactivate_a2dp_profile_for_listener_with_lookup(mac, lookup, None)
            .await
    }

    async fn deactivate_a2dp_profile_for_listener_with_lookup<Lookup>(
        &self,
        mac: &str,
        lookup: Lookup,
        listener_generation: Option<PlaybackListenerGeneration>,
    ) -> ProfileOperationResult
    where
        Lookup: std::future::Future<Output = Option<u32>>,
    {
        self.resolve_device_index(mac, lookup).await?;

        let device_index = {
            let state = self.state.lock().await;
            if state.connected_device_mac != mac {
                return Err(ProfileOperationError::ConnectedDeviceChanged);
            }
            state
                .device_index
                .ok_or(ProfileOperationError::AudioDeviceNotFound)?
        };

        info!("Deactivating A2DP profile for AirPods by setting to off");
        let success = self
            .run_blocking_deactivation_action(listener_generation, move || {
                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    set_card_profile_sync(device_index, "off")
                }))
                .unwrap_or_else(|e| {
                    warn!("Panic in set_card_profile_sync: {:?}", e);
                    false
                })
            })
            .await?;

        if !success {
            warn!("Failed to deactivate A2DP profile");
            return Err(ProfileOperationError::ProfileSwitchFailed("off".into()));
        }

        info!("Successfully deactivated A2DP profile");
        Ok(())
    }

    pub async fn handle_conversational_awareness(&self, status: u8) {
        debug!(
            "Entering handle_conversational_awareness with status: {}",
            status
        );

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
                    let target = orig_from_current.min(25);
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
                    .method_call::<(), _, &str, &str>("org.mpris.MediaPlayer2.Player", command, ())
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
            .filter(|s| s.starts_with("org.mpris.MediaPlayer2.") && !Self::is_kdeconnect_service(s))
            .collect()
    }
}

// --- PulseAudio helpers ---

fn pulse_connect() -> Option<(Mainloop, Context)> {
    let mut mainloop = Mainloop::new()?;
    let mut context = Context::new(&mainloop, "LibrePods")?;
    context
        .connect(None, ContextFlagSet::NOAUTOSPAWN, None)
        .ok()?;
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
                    active_profile: item
                        .active_profile
                        .as_ref()
                        .and_then(|profile| profile.name.as_ref().map(|name| name.to_string())),
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
    let callback_result = Rc::new(RefCell::new(None));
    let callback_result_for_handler = callback_result.clone();
    let op = introspector.set_card_profile_by_index(
        card_index,
        profile_name,
        Some(Box::new(move |success| {
            *callback_result_for_handler.borrow_mut() = Some(success);
        })),
    );
    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }
    mainloop.quit(Retval(0));
    let callback_result = *callback_result.borrow();
    profile_switch_succeeded(callback_result)
}

fn profile_switch_succeeded(callback_result: Option<bool>) -> bool {
    callback_result.unwrap_or(false)
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
            let matches_mac = ["device.string", "bluez.path"]
                .into_iter()
                .filter_map(|property| sink.proplist.get_str(property))
                .any(|property| pulse_property_matches_mac(&property, &mac_clone));
            if matches_mac && let Some(name) = &sink.name {
                info!("Found sink name for MAC {}: {}", mac_clone, name);
                return Some(name.to_string());
            }
        }
        error!("No matching sink found for MAC address: {}", mac_clone);
        None
    })
    .await
    .unwrap_or(None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bluetooth::aacp::{ConnectedDevice, QueuedAacpPacket, opcodes};
    use std::collections::VecDeque;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use tokio::sync::{mpsc, oneshot};
    use tokio::time::timeout;

    const CONNECTED_MAC: &str = "AA:BB:CC:DD:EE:FF";
    const LOCAL_MAC: &str = "11:22:33:44:55:66";
    const REMOTE_MAC: &str = "77:88:99:AA:BB:CC";

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    enum RecoveryAction {
        Probe,
        Restart,
        Sleep(Duration),
    }

    struct RecordingRecoveryOperations {
        probe_results: VecDeque<Result<bool, ProfileOperationError>>,
        restart_result: bool,
        actions: Vec<RecoveryAction>,
    }

    impl RecordingRecoveryOperations {
        fn new(
            probe_results: impl IntoIterator<Item = Result<bool, ProfileOperationError>>,
            restart_result: bool,
        ) -> Self {
            Self {
                probe_results: probe_results.into_iter().collect(),
                restart_result,
                actions: Vec::new(),
            }
        }
    }

    impl A2dpRecoveryOperations for RecordingRecoveryOperations {
        fn probe(&mut self) -> A2dpRecoveryFuture<'_, Result<bool, ProfileOperationError>> {
            self.actions.push(RecoveryAction::Probe);
            let result = self
                .probe_results
                .pop_front()
                .expect("recovery performed an unexpected profile probe");
            Box::pin(async move { result })
        }

        fn restart_wire_plumber(&mut self) -> A2dpRecoveryFuture<'_, bool> {
            self.actions.push(RecoveryAction::Restart);
            let result = self.restart_result;
            Box::pin(async move { result })
        }

        fn sleep(&mut self, duration: Duration) -> A2dpRecoveryFuture<'_, ()> {
            self.actions.push(RecoveryAction::Sleep(duration));
            Box::pin(async {})
        }
    }

    #[derive(Clone, Debug, PartialEq, Eq)]
    enum ActivationAction {
        ResolveDeviceIndex,
        RequestA2dpConnection,
        ActiveProfile(u32),
        ProfileAvailable,
        CurrentDeviceIndex,
        PreferredProfile(u32),
        Probe,
        Restart,
        Sleep(Duration),
        SwitchProfile { card_index: u32, profile: String },
    }

    struct RecordingActivationOperations {
        lookup_results: VecDeque<Result<Option<u32>, ProfileOperationError>>,
        profile_available_results: VecDeque<bool>,
        active_profile: Option<String>,
        preferred_profile: String,
        preferred_profiles_by_index: HashMap<u32, String>,
        current_device_index: Option<u32>,
        restart_result: bool,
        request_result: Result<(), String>,
        actions: Vec<ActivationAction>,
    }

    impl RecordingActivationOperations {
        fn new(
            lookup_results: impl IntoIterator<Item = Option<u32>>,
            profile_available_results: impl IntoIterator<Item = bool>,
            active_profile: Option<&str>,
            restart_result: bool,
        ) -> Self {
            Self {
                lookup_results: lookup_results.into_iter().map(Ok).collect(),
                profile_available_results: profile_available_results.into_iter().collect(),
                active_profile: active_profile.map(str::to_owned),
                preferred_profile: "a2dp-sink".into(),
                preferred_profiles_by_index: HashMap::new(),
                current_device_index: None,
                restart_result,
                request_result: Ok(()),
                actions: Vec::new(),
            }
        }

        fn take_lookup_result(&mut self) -> Result<Option<u32>, ProfileOperationError> {
            let result = self
                .lookup_results
                .pop_front()
                .expect("activation performed an unexpected audio card lookup");
            if let Ok(device_index) = &result {
                self.current_device_index = *device_index;
            }
            result
        }
    }

    impl A2dpRecoveryOperations for RecordingActivationOperations {
        fn probe(&mut self) -> A2dpRecoveryFuture<'_, Result<bool, ProfileOperationError>> {
            self.actions.push(ActivationAction::Probe);
            let result = match self.take_lookup_result() {
                Ok(Some(_)) => Ok(self
                    .profile_available_results
                    .pop_front()
                    .expect("profile availability was not injected for the discovered card")),
                Ok(None) => Ok(false),
                Err(error) => Err(error),
            };
            Box::pin(async move { result })
        }

        fn restart_wire_plumber(&mut self) -> A2dpRecoveryFuture<'_, bool> {
            self.actions.push(ActivationAction::Restart);
            let result = self.restart_result;
            Box::pin(async move { result })
        }

        fn sleep(&mut self, duration: Duration) -> A2dpRecoveryFuture<'_, ()> {
            self.actions.push(ActivationAction::Sleep(duration));
            Box::pin(async {})
        }
    }

    impl A2dpActivationOperations for RecordingActivationOperations {
        fn resolve_device_index(
            &mut self,
        ) -> A2dpRecoveryFuture<'_, Result<u32, ProfileOperationError>> {
            self.actions.push(ActivationAction::ResolveDeviceIndex);
            let result = match self.take_lookup_result() {
                Ok(Some(device_index)) => Ok(device_index),
                Ok(None) => Err(ProfileOperationError::AudioDeviceNotFound),
                Err(error) => Err(error),
            };
            Box::pin(async move { result })
        }

        fn request_a2dp_connection(&mut self) -> A2dpRecoveryFuture<'_, Result<(), String>> {
            self.actions.push(ActivationAction::RequestA2dpConnection);
            let result = self.request_result.clone();
            Box::pin(async move { result })
        }

        fn get_active_profile(
            &mut self,
            card_index: u32,
        ) -> A2dpRecoveryFuture<'_, Option<String>> {
            assert_eq!(self.current_device_index, Some(card_index));
            self.actions
                .push(ActivationAction::ActiveProfile(card_index));
            let profile = self.active_profile.clone();
            Box::pin(async move { profile })
        }

        fn is_a2dp_profile_available(&mut self) -> A2dpRecoveryFuture<'_, bool> {
            self.actions.push(ActivationAction::ProfileAvailable);
            let available = self
                .profile_available_results
                .pop_front()
                .expect("profile availability was not injected");
            Box::pin(async move { available })
        }

        fn current_device_index(
            &mut self,
        ) -> A2dpRecoveryFuture<'_, Result<u32, ProfileOperationError>> {
            self.actions.push(ActivationAction::CurrentDeviceIndex);
            let result = self
                .current_device_index
                .ok_or(ProfileOperationError::AudioDeviceNotFound);
            Box::pin(async move { result })
        }

        fn get_preferred_a2dp_profile(
            &mut self,
            card_index: u32,
        ) -> A2dpRecoveryFuture<'_, String> {
            assert_eq!(self.current_device_index, Some(card_index));
            self.actions
                .push(ActivationAction::PreferredProfile(card_index));
            let profile = self
                .preferred_profiles_by_index
                .get(&card_index)
                .cloned()
                .unwrap_or_else(|| self.preferred_profile.clone());
            Box::pin(async move { profile })
        }

        fn switch_profile(
            &mut self,
            card_index: u32,
            profile: String,
        ) -> A2dpRecoveryFuture<'_, ProfileOperationResult> {
            assert_eq!(self.current_device_index, Some(card_index));
            self.actions.push(ActivationAction::SwitchProfile {
                card_index,
                profile: profile.clone(),
            });
            self.active_profile = Some(profile);
            Box::pin(async { Ok(()) })
        }
    }

    fn controller() -> MediaController {
        MediaController::new(CONNECTED_MAC.into(), LOCAL_MAC.into())
    }

    fn controller_with_listener_registry(
        connected_mac: &str,
        playback_listeners: PlaybackListenerRegistry,
    ) -> MediaController {
        MediaController::with_playback_listener_registry(
            connected_mac.into(),
            LOCAL_MAC.into(),
            playback_listeners,
        )
    }

    fn takeover_controller() -> (MediaController, u64) {
        let playback_listeners = PlaybackListenerRegistry::default();
        let controller =
            controller_with_listener_registry(CONNECTED_MAC, playback_listeners.clone());
        let placeholder = tokio::spawn(async {});
        let listener_generation =
            playback_listeners.replace(CONNECTED_MAC, placeholder.abort_handle());
        (controller, listener_generation)
    }

    async fn wait_until_finished(abort_handle: &AbortHandle) {
        timeout(Duration::from_millis(250), async {
            while !abort_handle.is_finished() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("aborted playback listener did not stop");
    }

    #[test]
    fn both_buds_removal_keeps_current_audio_profile() {
        assert_eq!(
            ear_profile_action_for_transition(&[true, true], &[false, false]),
            None
        );
    }

    #[test]
    fn first_bud_insertion_activates_a2dp_profile() {
        assert_eq!(
            ear_profile_action_for_transition(&[false, false], &[true, false]),
            Some(EarProfileAction::ActivateA2dp)
        );
    }

    #[test]
    fn card_profile_callback_false_or_missing_is_failure() {
        assert!(profile_switch_succeeded(Some(true)));
        assert!(!profile_switch_succeeded(Some(false)));
        assert!(!profile_switch_succeeded(None));
    }

    #[tokio::test]
    async fn concurrent_a2dp_activations_run_one_pipeline_at_a_time() {
        let controller = controller();
        let (first_started_tx, first_started_rx) = oneshot::channel();
        let (release_first_tx, release_first_rx) = oneshot::channel();
        let first_controller = controller.clone();
        let first = tokio::spawn(async move {
            first_controller
                .run_a2dp_activation(async move {
                    first_started_tx
                        .send(())
                        .expect("first activation start receiver dropped");
                    release_first_rx
                        .await
                        .expect("first activation release sender dropped");
                    Ok(())
                })
                .await
        });

        timeout(Duration::from_secs(1), first_started_rx)
            .await
            .expect("first activation did not start")
            .expect("first activation start sender dropped");

        let (second_started_tx, mut second_started_rx) = oneshot::channel();
        let (second_polled_tx, second_polled_rx) = oneshot::channel();
        let second = tokio::spawn(async move {
            let mut activation = Box::pin(async move {
                controller
                    .run_a2dp_activation(async move {
                        let _ = second_started_tx.send(());
                        Ok(())
                    })
                    .await
            });
            let mut second_polled_tx = Some(second_polled_tx);
            std::future::poll_fn(move |context| {
                let result = std::future::Future::poll(activation.as_mut(), context);
                if let Some(second_polled_tx) = second_polled_tx.take() {
                    let _ = second_polled_tx.send(());
                }
                result
            })
            .await
        });
        timeout(Duration::from_secs(1), second_polled_rx)
            .await
            .expect("second activation was not polled")
            .expect("second activation poll sender dropped");
        let second_started_before_release = match second_started_rx.try_recv() {
            Ok(()) => true,
            Err(oneshot::error::TryRecvError::Empty) => false,
            Err(oneshot::error::TryRecvError::Closed) => {
                panic!("second activation start sender dropped")
            }
        };

        release_first_tx
            .send(())
            .expect("first activation release receiver dropped");
        let (first_result, second_result) = timeout(Duration::from_secs(1), async {
            tokio::join!(first, second)
        })
        .await
        .expect("serialized activations did not finish");
        assert_eq!(first_result.expect("first activation task failed"), Ok(()));
        assert_eq!(
            second_result.expect("second activation task failed"),
            Ok(())
        );
        assert!(
            !second_started_before_release,
            "second activation entered while the first pipeline was active"
        );
    }

    #[tokio::test]
    async fn activation_and_deactivation_share_one_profile_operation_gate() {
        let controller = controller();
        let (activation_started_tx, activation_started_rx) = oneshot::channel();
        let (release_activation_tx, release_activation_rx) = oneshot::channel();
        let activation_controller = controller.clone();
        let activation = tokio::spawn(async move {
            activation_controller
                .run_a2dp_activation(async move {
                    activation_started_tx
                        .send(())
                        .expect("activation start receiver dropped");
                    release_activation_rx
                        .await
                        .expect("activation release sender dropped");
                    Ok(())
                })
                .await
        });

        timeout(Duration::from_secs(1), activation_started_rx)
            .await
            .expect("activation did not start")
            .expect("activation start sender dropped");

        let (deactivation_started_tx, mut deactivation_started_rx) = oneshot::channel();
        let (deactivation_polled_tx, deactivation_polled_rx) = oneshot::channel();
        let deactivation_controller = controller.clone();
        let deactivation = tokio::spawn(async move {
            let mut pipeline = Box::pin(async move {
                deactivation_controller
                    .run_a2dp_deactivation(async move {
                        let _ = deactivation_started_tx.send(());
                        Ok(())
                    })
                    .await
            });
            let mut deactivation_polled_tx = Some(deactivation_polled_tx);
            std::future::poll_fn(move |context| {
                let result = std::future::Future::poll(pipeline.as_mut(), context);
                if let Some(deactivation_polled_tx) = deactivation_polled_tx.take() {
                    let _ = deactivation_polled_tx.send(());
                }
                result
            })
            .await
        });
        timeout(Duration::from_secs(1), deactivation_polled_rx)
            .await
            .expect("deactivation was not polled")
            .expect("deactivation poll sender dropped");
        let deactivation_started_before_release = match deactivation_started_rx.try_recv() {
            Ok(()) => true,
            Err(oneshot::error::TryRecvError::Empty) => false,
            Err(oneshot::error::TryRecvError::Closed) => {
                panic!("deactivation start sender dropped")
            }
        };

        release_activation_tx
            .send(())
            .expect("activation release receiver dropped");
        let (activation_result, deactivation_result) = timeout(Duration::from_secs(1), async {
            tokio::join!(activation, deactivation)
        })
        .await
        .expect("activation and deactivation did not finish");
        assert_eq!(activation_result.expect("activation task failed"), Ok(()));
        assert_eq!(
            deactivation_result.expect("deactivation task failed"),
            Ok(())
        );
        assert!(
            !deactivation_started_before_release,
            "deactivation entered while activation held the profile-operation gate"
        );
    }

    #[tokio::test]
    async fn activation_queued_before_deactivation_is_superseded() {
        let controller = controller();
        let actions = Arc::new(StdMutex::new(Vec::new()));
        let first_actions = Arc::clone(&actions);
        let (first_started_tx, first_started_rx) = oneshot::channel();
        let (release_first_tx, release_first_rx) = oneshot::channel();
        let first_controller = controller.clone();
        let first = tokio::spawn(async move {
            first_controller
                .run_a2dp_activation(async move {
                    first_actions.lock().unwrap().push("first activation");
                    first_started_tx
                        .send(())
                        .expect("first activation start receiver dropped");
                    release_first_rx
                        .await
                        .expect("first activation release sender dropped");
                    Ok(())
                })
                .await
        });
        timeout(Duration::from_secs(1), first_started_rx)
            .await
            .expect("first activation did not start")
            .expect("first activation start sender dropped");

        let second_actions = Arc::clone(&actions);
        let second_controller = controller.clone();
        let (second_polled_tx, second_polled_rx) = oneshot::channel();
        let second = tokio::spawn(async move {
            let mut pipeline = Box::pin(second_controller.run_a2dp_activation(async move {
                second_actions.lock().unwrap().push("second activation");
                Ok(())
            }));
            let mut second_polled_tx = Some(second_polled_tx);
            std::future::poll_fn(move |context| {
                let result = std::future::Future::poll(pipeline.as_mut(), context);
                if let Some(second_polled_tx) = second_polled_tx.take() {
                    let _ = second_polled_tx.send(());
                }
                result
            })
            .await
        });
        timeout(Duration::from_secs(1), second_polled_rx)
            .await
            .expect("queued activation was not polled")
            .expect("queued activation poll sender dropped");

        let deactivation_actions = Arc::clone(&actions);
        let deactivation_controller = controller.clone();
        let (deactivation_polled_tx, deactivation_polled_rx) = oneshot::channel();
        let deactivation = tokio::spawn(async move {
            let mut pipeline =
                Box::pin(deactivation_controller.run_a2dp_deactivation(async move {
                    deactivation_actions.lock().unwrap().push("deactivation");
                    Ok(())
                }));
            let mut deactivation_polled_tx = Some(deactivation_polled_tx);
            std::future::poll_fn(move |context| {
                let result = std::future::Future::poll(pipeline.as_mut(), context);
                if let Some(deactivation_polled_tx) = deactivation_polled_tx.take() {
                    let _ = deactivation_polled_tx.send(());
                }
                result
            })
            .await
        });
        timeout(Duration::from_secs(1), deactivation_polled_rx)
            .await
            .expect("queued deactivation was not polled")
            .expect("queued deactivation poll sender dropped");

        release_first_tx
            .send(())
            .expect("first activation release receiver dropped");
        let (first_result, second_result, deactivation_result) =
            timeout(Duration::from_secs(1), async {
                tokio::join!(first, second, deactivation)
            })
            .await
            .expect("queued profile operations did not finish");

        assert_eq!(first_result.expect("first activation task failed"), Ok(()));
        assert_eq!(
            second_result.expect("queued activation task failed"),
            Err(ProfileOperationError::ActivationSupersededByDeactivation)
        );
        assert_eq!(
            deactivation_result.expect("deactivation task failed"),
            Ok(())
        );
        assert_eq!(
            actions.lock().unwrap().as_slice(),
            ["first activation", "deactivation"]
        );
    }

    #[tokio::test]
    async fn activation_started_during_deactivation_is_superseded() {
        let controller = controller();
        let actions = Arc::new(StdMutex::new(Vec::new()));
        let deactivation_actions = Arc::clone(&actions);
        let (deactivation_started_tx, deactivation_started_rx) = oneshot::channel();
        let (release_deactivation_tx, release_deactivation_rx) = oneshot::channel();
        let deactivation_controller = controller.clone();
        let deactivation = tokio::spawn(async move {
            deactivation_controller
                .run_a2dp_deactivation(async move {
                    deactivation_actions.lock().unwrap().push("deactivation");
                    deactivation_started_tx
                        .send(())
                        .expect("deactivation start receiver dropped");
                    release_deactivation_rx
                        .await
                        .expect("deactivation release sender dropped");
                    Ok(())
                })
                .await
        });
        timeout(Duration::from_secs(1), deactivation_started_rx)
            .await
            .expect("deactivation did not start")
            .expect("deactivation start sender dropped");

        let activation_actions = Arc::clone(&actions);
        let activation_controller = controller.clone();
        let (activation_polled_tx, activation_polled_rx) = oneshot::channel();
        let activation = tokio::spawn(async move {
            let mut pipeline = Box::pin(activation_controller.run_a2dp_activation(async move {
                activation_actions.lock().unwrap().push("activation");
                Ok(())
            }));
            let mut activation_polled_tx = Some(activation_polled_tx);
            std::future::poll_fn(move |context| {
                let result = std::future::Future::poll(pipeline.as_mut(), context);
                if let Some(activation_polled_tx) = activation_polled_tx.take() {
                    let _ = activation_polled_tx.send(());
                }
                result
            })
            .await
        });
        timeout(Duration::from_secs(1), activation_polled_rx)
            .await
            .expect("activation during deactivation was not polled")
            .expect("activation poll sender dropped");

        release_deactivation_tx
            .send(())
            .expect("deactivation release receiver dropped");
        let (deactivation_result, activation_result) = timeout(Duration::from_secs(1), async {
            tokio::join!(deactivation, activation)
        })
        .await
        .expect("deactivation and queued activation did not finish");

        assert_eq!(
            deactivation_result.expect("deactivation task failed"),
            Ok(())
        );
        assert_eq!(
            activation_result.expect("activation task failed"),
            Err(ProfileOperationError::ActivationSupersededByDeactivation)
        );
        assert_eq!(actions.lock().unwrap().as_slice(), ["deactivation"]);
    }

    #[tokio::test]
    async fn activation_started_after_deactivation_completes_runs() {
        let controller = controller();
        let actions = Arc::new(StdMutex::new(Vec::new()));
        let deactivation_actions = Arc::clone(&actions);

        assert_eq!(
            controller
                .run_a2dp_deactivation(async move {
                    deactivation_actions.lock().unwrap().push("deactivation");
                    Ok(())
                })
                .await,
            Ok(())
        );

        let activation_actions = Arc::clone(&actions);
        assert_eq!(
            controller
                .run_a2dp_activation(async move {
                    activation_actions.lock().unwrap().push("activation");
                    Ok(())
                })
                .await,
            Ok(())
        );
        assert_eq!(
            actions.lock().unwrap().as_slice(),
            ["deactivation", "activation"]
        );
    }

    #[tokio::test]
    async fn cancelled_deactivation_keeps_activation_superseded_until_worker_finishes() {
        let controller = controller();
        let deactivation_controller = controller.clone();
        let action_controller = controller.clone();
        let (worker_started_tx, worker_started_rx) = oneshot::channel();
        let (release_worker_tx, release_worker_rx) = std::sync::mpsc::channel();
        let (worker_finished_tx, worker_finished_rx) = oneshot::channel();
        let deactivation = tokio::spawn(async move {
            deactivation_controller
                .run_a2dp_deactivation(async move {
                    action_controller
                        .run_blocking_deactivation_action(None, move || {
                            worker_started_tx
                                .send(())
                                .expect("deactivation worker start receiver dropped");
                            let released = release_worker_rx.recv().is_ok();
                            let _ = worker_finished_tx.send(());
                            released
                        })
                        .await
                        .map(|_| ())
                })
                .await
        });
        timeout(Duration::from_secs(1), worker_started_rx)
            .await
            .expect("deactivation worker did not start")
            .expect("deactivation worker start sender dropped");

        deactivation.abort();
        let cancellation = deactivation
            .await
            .expect_err("deactivation task was not cancelled");
        assert!(cancellation.is_cancelled());

        let activation_calls = Arc::new(AtomicUsize::new(0));
        let recorded_activation_calls = Arc::clone(&activation_calls);
        let activation_result = controller
            .run_a2dp_activation(async move {
                recorded_activation_calls.fetch_add(1, Ordering::SeqCst);
                Ok(())
            })
            .await;

        assert_eq!(
            activation_result,
            Err(ProfileOperationError::ActivationSupersededByDeactivation)
        );
        assert_eq!(activation_calls.load(Ordering::SeqCst), 0);

        release_worker_tx
            .send(())
            .expect("deactivation worker release receiver dropped");
        timeout(Duration::from_secs(1), worker_finished_rx)
            .await
            .expect("deactivation worker did not finish")
            .expect("deactivation worker finish sender dropped");
    }

    #[tokio::test]
    async fn stale_listener_generation_skips_blocking_profile_action() {
        let playback_listeners = PlaybackListenerRegistry::default();
        let first_listener = tokio::spawn(std::future::pending::<()>());
        let first_generation =
            playback_listeners.replace(CONNECTED_MAC, first_listener.abort_handle());
        let replacement_listener = tokio::spawn(std::future::pending::<()>());
        let replacement_generation =
            playback_listeners.replace(CONNECTED_MAC, replacement_listener.abort_handle());
        let stale_generation = PlaybackListenerGeneration {
            device_mac: CONNECTED_MAC.into(),
            generation: first_generation,
        };
        let mut action_ran = false;

        let result =
            run_blocking_profile_action(&playback_listeners, Some(&stale_generation), || {
                action_ran = true
            });

        assert!(playback_listeners.remove_if_current(CONNECTED_MAC, replacement_generation));
        replacement_listener.abort();
        assert_eq!(result, Err(ProfileOperationError::ListenerReplaced));
        assert!(!action_ran);
    }

    #[tokio::test]
    async fn stale_listener_generation_skips_fenced_ownership_pause() {
        let playback_listeners = PlaybackListenerRegistry::default();
        let controller =
            controller_with_listener_registry(CONNECTED_MAC, playback_listeners.clone());
        let first_listener = tokio::spawn(std::future::pending::<()>());
        let first_generation =
            playback_listeners.replace(CONNECTED_MAC, first_listener.abort_handle());
        *controller
            .playback_listener_generation
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(PlaybackListenerGeneration {
            device_mac: CONNECTED_MAC.into(),
            generation: first_generation,
        });
        let replacement_listener = tokio::spawn(std::future::pending::<()>());
        let replacement_generation =
            playback_listeners.replace(CONNECTED_MAC, replacement_listener.abort_handle());
        let pause_calls = Arc::new(AtomicUsize::new(0));
        let recorded_pause_calls = Arc::clone(&pause_calls);

        let cleanup_ran = controller
            .pause_all_media_with(move || {
                recorded_pause_calls.fetch_add(1, Ordering::SeqCst);
                1
            })
            .await;

        assert!(playback_listeners.remove_if_current(CONNECTED_MAC, replacement_generation));
        replacement_listener.abort();
        assert!(!cleanup_ran);
        assert_eq!(pause_calls.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn stale_listener_generation_skips_wire_plumber_restart_command() {
        let playback_listeners = PlaybackListenerRegistry::default();
        let controller =
            controller_with_listener_registry(CONNECTED_MAC, playback_listeners.clone());
        let first_listener = tokio::spawn(std::future::pending::<()>());
        let first_generation =
            playback_listeners.replace(CONNECTED_MAC, first_listener.abort_handle());
        let stale_generation = PlaybackListenerGeneration {
            device_mac: CONNECTED_MAC.into(),
            generation: first_generation,
        };
        let replacement_listener = tokio::spawn(std::future::pending::<()>());
        let replacement_generation =
            playback_listeners.replace(CONNECTED_MAC, replacement_listener.abort_handle());
        let restart_calls = Arc::new(AtomicUsize::new(0));
        let recorded_restart_calls = Arc::clone(&restart_calls);

        let restarted = controller
            .restart_wire_plumber_with(Some(stale_generation), move || {
                recorded_restart_calls.fetch_add(1, Ordering::SeqCst);
                true
            })
            .await;

        assert!(playback_listeners.remove_if_current(CONNECTED_MAC, replacement_generation));
        replacement_listener.abort();
        assert!(!restarted);
        assert_eq!(restart_calls.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn listener_setup_requests_one_activation_and_survives_failure() {
        let playback_listeners = PlaybackListenerRegistry::default();
        let controller =
            controller_with_listener_registry(CONNECTED_MAC, playback_listeners.clone());
        let activation_requests = Arc::new(AtomicUsize::new(0));
        let activation_count = Arc::clone(&activation_requests);
        let (activation_finished_tx, activation_finished_rx) = oneshot::channel();

        controller
            .start_playback_listener_with_activation(AACPManager::new(), move |_| async move {
                activation_count.fetch_add(1, Ordering::SeqCst);
                activation_finished_tx
                    .send(())
                    .expect("activation completion receiver dropped");
                Err(ProfileOperationError::AudioDeviceNotFound)
            })
            .await;
        let listener = playback_listeners
            .current_entry(CONNECTED_MAC)
            .expect("playback listener was not registered");
        timeout(Duration::from_millis(250), activation_finished_rx)
            .await
            .expect("initial activation did not finish")
            .expect("activation completion sender dropped");
        tokio::task::yield_now().await;

        assert_eq!(activation_requests.load(Ordering::SeqCst), 1);
        assert!(!listener.abort_handle.is_finished());
        assert!(playback_listeners.is_current(CONNECTED_MAC, listener.generation));

        assert!(playback_listeners.remove_if_current(CONNECTED_MAC, listener.generation));
        listener.abort_handle.abort();
    }

    #[tokio::test]
    async fn activation_failure_still_polls_listener_future() {
        let (listener_started_tx, listener_started_rx) = oneshot::channel();

        timeout(Duration::from_millis(250), async move {
            activate_profile_then_start_listener(
                async { Err(ProfileOperationError::AudioDeviceNotFound) },
                async move {
                    listener_started_tx
                        .send(())
                        .expect("listener start receiver dropped");
                },
            )
            .await;
            listener_started_rx
                .await
                .expect("listener start sender dropped");
        })
        .await
        .expect("listener future was not polled after activation failure");
    }

    #[tokio::test]
    async fn controller_session_predicate_tracks_same_mac_listener_replacement() {
        let playback_listeners = PlaybackListenerRegistry::default();
        let first = controller_with_listener_registry(CONNECTED_MAC, playback_listeners.clone());
        first
            .start_playback_listener_without_initial_activation(AACPManager::new())
            .await;
        let first_listener = playback_listeners
            .current_entry(CONNECTED_MAC)
            .expect("first playback listener was not registered");
        assert!(first.is_current_playback_session());

        let replacement =
            controller_with_listener_registry("aa-bb-cc-dd-ee-ff", playback_listeners.clone());
        replacement
            .start_playback_listener_without_initial_activation(AACPManager::new())
            .await;
        let replacement_listener = playback_listeners
            .current_entry(CONNECTED_MAC)
            .expect("replacement playback listener was not registered");

        wait_until_finished(&first_listener.abort_handle).await;
        assert!(!first.is_current_playback_session());
        assert!(replacement.is_current_playback_session());

        assert!(
            playback_listeners.remove_if_current(CONNECTED_MAC, replacement_listener.generation)
        );
        replacement_listener.abort_handle.abort();
    }

    #[tokio::test]
    async fn same_mac_listener_replacement_aborts_previous_listener() {
        let playback_listeners = PlaybackListenerRegistry::default();
        let first = controller_with_listener_registry(CONNECTED_MAC, playback_listeners.clone());
        first
            .start_playback_listener_without_initial_activation(AACPManager::new())
            .await;
        let first_listener = playback_listeners
            .current_entry(CONNECTED_MAC)
            .expect("first playback listener was not registered");

        let replacement =
            controller_with_listener_registry("aa-bb-cc-dd-ee-ff", playback_listeners.clone());
        replacement
            .start_playback_listener_without_initial_activation(AACPManager::new())
            .await;
        let replacement_listener = playback_listeners
            .current_entry(CONNECTED_MAC)
            .expect("replacement playback listener was not registered");

        wait_until_finished(&first_listener.abort_handle).await;
        assert_ne!(first_listener.generation, replacement_listener.generation);
        assert!(
            playback_listeners.is_current("aa-bb-cc-dd-ee-ff", replacement_listener.generation)
        );

        assert!(
            playback_listeners.remove_if_current(CONNECTED_MAC, replacement_listener.generation)
        );
        replacement_listener.abort_handle.abort();
    }

    #[tokio::test]
    async fn same_mac_replacement_cancels_in_flight_initial_activation() {
        let playback_listeners = PlaybackListenerRegistry::default();
        let first = controller_with_listener_registry(CONNECTED_MAC, playback_listeners.clone());
        let (activation_started_tx, activation_started_rx) = oneshot::channel();
        let (release_activation_tx, release_activation_rx) = oneshot::channel();
        first
            .start_playback_listener_with_activation(AACPManager::new(), move |_| async move {
                activation_started_tx
                    .send(())
                    .expect("activation start receiver dropped");
                release_activation_rx
                    .await
                    .expect("activation release sender dropped");
                Ok(())
            })
            .await;
        let first_listener = playback_listeners
            .current_entry(CONNECTED_MAC)
            .expect("first playback listener was not registered");
        timeout(Duration::from_millis(250), activation_started_rx)
            .await
            .expect("initial activation did not start")
            .expect("activation start sender dropped");

        let replacement =
            controller_with_listener_registry("aa-bb-cc-dd-ee-ff", playback_listeners.clone());
        replacement
            .start_playback_listener_without_initial_activation(AACPManager::new())
            .await;
        let replacement_listener = playback_listeners
            .current_entry(CONNECTED_MAC)
            .expect("replacement playback listener was not registered");

        wait_until_finished(&first_listener.abort_handle).await;
        assert!(release_activation_tx.send(()).is_err());
        assert!(playback_listeners.is_current(CONNECTED_MAC, replacement_listener.generation));

        assert!(
            playback_listeners.remove_if_current(CONNECTED_MAC, replacement_listener.generation)
        );
        replacement_listener.abort_handle.abort();
    }

    #[tokio::test]
    async fn other_mac_listener_is_preserved_during_replacement() {
        let playback_listeners = PlaybackListenerRegistry::default();
        let first = controller_with_listener_registry(CONNECTED_MAC, playback_listeners.clone());
        first
            .start_playback_listener_without_initial_activation(AACPManager::new())
            .await;
        let first_listener = playback_listeners
            .current_entry(CONNECTED_MAC)
            .expect("first playback listener was not registered");

        let other = controller_with_listener_registry(REMOTE_MAC, playback_listeners.clone());
        other
            .start_playback_listener_without_initial_activation(AACPManager::new())
            .await;
        let other_listener = playback_listeners
            .current_entry(REMOTE_MAC)
            .expect("other playback listener was not registered");

        let replacement =
            controller_with_listener_registry(CONNECTED_MAC, playback_listeners.clone());
        replacement
            .start_playback_listener_without_initial_activation(AACPManager::new())
            .await;
        let replacement_listener = playback_listeners
            .current_entry(CONNECTED_MAC)
            .expect("replacement playback listener was not registered");

        wait_until_finished(&first_listener.abort_handle).await;
        assert!(!other_listener.abort_handle.is_finished());
        assert!(playback_listeners.is_current(REMOTE_MAC, other_listener.generation));

        assert!(
            playback_listeners.remove_if_current(CONNECTED_MAC, replacement_listener.generation)
        );
        replacement_listener.abort_handle.abort();
        assert!(playback_listeners.remove_if_current(REMOTE_MAC, other_listener.generation));
        other_listener.abort_handle.abort();
    }

    #[tokio::test]
    async fn listener_cleanup_only_removes_its_own_generation() {
        let playback_listeners = PlaybackListenerRegistry::default();
        let first_placeholder = tokio::spawn(async {});
        let first_generation =
            playback_listeners.replace(CONNECTED_MAC, first_placeholder.abort_handle());
        let replacement_placeholder = tokio::spawn(async {});
        let replacement_generation =
            playback_listeners.replace("aa-bb-cc-dd-ee-ff", replacement_placeholder.abort_handle());

        drop(PlaybackListenerCleanup {
            registry: playback_listeners.clone(),
            device_mac: CONNECTED_MAC.into(),
            generation: first_generation,
        });
        assert!(playback_listeners.is_current(CONNECTED_MAC, replacement_generation));

        drop(PlaybackListenerCleanup {
            registry: playback_listeners.clone(),
            device_mac: "aa-bb-cc-dd-ee-ff".into(),
            generation: replacement_generation,
        });
        assert!(playback_listeners.current_entry(CONNECTED_MAC).is_none());
    }

    #[tokio::test]
    async fn incomplete_takeover_outcomes_restore_the_playback_retry_edge() {
        let (controller, listener_generation) = takeover_controller();
        let retryable_outcomes = [
            PlaybackTakeoverOutcome::SkippedNotInEar,
            PlaybackTakeoverOutcome::AbortedDisconnected,
            PlaybackTakeoverOutcome::AbortedConnectionChanged,
            PlaybackTakeoverOutcome::AbortedEarStateChanged,
            PlaybackTakeoverOutcome::Failed(PlaybackTakeoverFailure::ProfileActivation(
                ProfileOperationError::WirePlumberRestartFailed,
            )),
        ];

        for outcome in retryable_outcomes {
            {
                let mut state = controller.state.lock().await;
                state.is_playing = true;
                state.playback_takeover_complete = true;
            }

            assert!(
                controller
                    .record_takeover_outcome(CONNECTED_MAC, listener_generation, &outcome)
                    .await
            );
            let state = controller.state.lock().await;
            assert!(state.is_playing);
            assert!(!state.playback_takeover_complete);
            assert!(should_attempt_playback_takeover(
                true,
                state.is_playing,
                state.playback_takeover_complete,
            ));
        }
    }

    #[tokio::test]
    async fn completed_takeover_keeps_the_observed_playback_state() {
        let (controller, listener_generation) = takeover_controller();
        controller.state.lock().await.is_playing = true;

        assert!(
            controller
                .record_takeover_outcome(
                    CONNECTED_MAC,
                    listener_generation,
                    &PlaybackTakeoverOutcome::Completed,
                )
                .await
        );
        let state = controller.state.lock().await;
        assert!(state.is_playing);
        assert!(state.playback_takeover_complete);
        assert!(!should_attempt_playback_takeover(
            true,
            state.is_playing,
            state.playback_takeover_complete,
        ));
    }

    #[tokio::test]
    async fn replaced_listener_cannot_rearm_or_mutate_replacement_playback_state() {
        let playback_listeners = PlaybackListenerRegistry::default();
        let controller =
            controller_with_listener_registry(CONNECTED_MAC, playback_listeners.clone());
        let old_placeholder = tokio::spawn(async {});
        let old_generation =
            playback_listeners.replace(CONNECTED_MAC, old_placeholder.abort_handle());
        let replacement_placeholder = tokio::spawn(async {});
        let replacement_generation =
            playback_listeners.replace(CONNECTED_MAC, replacement_placeholder.abort_handle());
        {
            let mut state = controller.state.lock().await;
            state.is_playing = true;
            state.playback_takeover_complete = true;
        }

        assert!(
            !controller
                .record_takeover_outcome(
                    CONNECTED_MAC,
                    old_generation,
                    &PlaybackTakeoverOutcome::Failed(PlaybackTakeoverFailure::ProfileActivation(
                        ProfileOperationError::WirePlumberRestartFailed,
                    ),),
                )
                .await
        );
        let state = controller.state.lock().await;
        assert!(state.is_playing);
        assert!(state.playback_takeover_complete);
        assert!(playback_listeners.is_current(CONNECTED_MAC, replacement_generation));
    }

    #[tokio::test]
    async fn profile_appearing_during_grace_skips_wire_plumber_restart() {
        let mut operations = RecordingRecoveryOperations::new([Ok(true)], false);

        let result = recover_a2dp_profile(&mut operations).await;

        assert_eq!(result, Ok(()));
        assert_eq!(
            operations.actions,
            vec![
                RecoveryAction::Sleep(Duration::from_secs(2)),
                RecoveryAction::Probe,
            ]
        );
    }

    #[tokio::test]
    async fn post_restart_recovery_retries_until_profile_is_ready() {
        let mut operations =
            RecordingRecoveryOperations::new([Ok(false), Ok(false), Ok(false), Ok(true)], true);

        let result = recover_a2dp_profile(&mut operations).await;

        assert_eq!(result, Ok(()));
        assert_eq!(
            operations.actions,
            vec![
                RecoveryAction::Sleep(Duration::from_secs(2)),
                RecoveryAction::Probe,
                RecoveryAction::Restart,
                RecoveryAction::Sleep(Duration::from_secs(2)),
                RecoveryAction::Probe,
                RecoveryAction::Sleep(Duration::from_secs(1)),
                RecoveryAction::Probe,
                RecoveryAction::Sleep(Duration::from_secs(2)),
                RecoveryAction::Probe,
            ]
        );
    }

    #[tokio::test]
    async fn persistent_profile_failure_is_bounded_without_final_sleep() {
        let mut operations =
            RecordingRecoveryOperations::new(std::iter::repeat_with(|| Ok(false)).take(6), true);

        let result = recover_a2dp_profile(&mut operations).await;

        assert_eq!(result, Err(ProfileOperationError::A2dpProfileUnavailable));
        assert_eq!(
            operations.actions,
            vec![
                RecoveryAction::Sleep(Duration::from_secs(2)),
                RecoveryAction::Probe,
                RecoveryAction::Restart,
                RecoveryAction::Sleep(Duration::from_secs(2)),
                RecoveryAction::Probe,
                RecoveryAction::Sleep(Duration::from_secs(1)),
                RecoveryAction::Probe,
                RecoveryAction::Sleep(Duration::from_secs(2)),
                RecoveryAction::Probe,
                RecoveryAction::Sleep(Duration::from_secs(3)),
                RecoveryAction::Probe,
                RecoveryAction::Sleep(Duration::from_secs(4)),
                RecoveryAction::Probe,
            ]
        );
        assert_eq!(operations.actions.last(), Some(&RecoveryAction::Probe));
        assert!(operations.probe_results.is_empty());
    }

    #[tokio::test]
    async fn wire_plumber_restart_failure_is_terminal() {
        let mut operations = RecordingRecoveryOperations::new([Ok(false)], false);

        let result = recover_a2dp_profile(&mut operations).await;

        assert_eq!(result, Err(ProfileOperationError::WirePlumberRestartFailed));
        assert_eq!(
            operations.actions,
            vec![
                RecoveryAction::Sleep(Duration::from_secs(2)),
                RecoveryAction::Probe,
                RecoveryAction::Restart,
            ]
        );
    }

    #[tokio::test]
    async fn connected_device_change_during_recovery_is_terminal() {
        let mut operations = RecordingRecoveryOperations::new(
            [
                Ok(false),
                Ok(false),
                Err(ProfileOperationError::ConnectedDeviceChanged),
                Ok(true),
            ],
            true,
        );

        let result = recover_a2dp_profile(&mut operations).await;

        assert_eq!(result, Err(ProfileOperationError::ConnectedDeviceChanged));
        assert_eq!(
            operations.actions,
            vec![
                RecoveryAction::Sleep(Duration::from_secs(2)),
                RecoveryAction::Probe,
                RecoveryAction::Restart,
                RecoveryAction::Sleep(Duration::from_secs(2)),
                RecoveryAction::Probe,
                RecoveryAction::Sleep(Duration::from_secs(1)),
                RecoveryAction::Probe,
            ]
        );
        assert_eq!(operations.probe_results.len(), 1);
    }

    #[tokio::test]
    async fn initial_lookup_miss_recovers_when_card_appears_during_grace() {
        let mut operations = RecordingActivationOperations::new(
            [None, Some(42), Some(42)],
            [true, true],
            Some("off"),
            false,
        );
        operations.current_device_index = Some(7);

        let result = activate_a2dp_profile_with_operations(&mut operations).await;

        assert_eq!(result, Ok(()));
        assert_eq!(
            operations.actions,
            vec![
                ActivationAction::ResolveDeviceIndex,
                ActivationAction::RequestA2dpConnection,
                ActivationAction::Sleep(Duration::from_secs(2)),
                ActivationAction::Probe,
                ActivationAction::ResolveDeviceIndex,
                ActivationAction::ActiveProfile(42),
                ActivationAction::ProfileAvailable,
                ActivationAction::PreferredProfile(42),
                ActivationAction::SwitchProfile {
                    card_index: 42,
                    profile: "a2dp-sink".into(),
                },
            ]
        );
        assert_eq!(operations.current_device_index, Some(42));
        assert_eq!(
            operations
                .actions
                .iter()
                .filter(|action| matches!(action, ActivationAction::Restart))
                .count(),
            0
        );
        assert_eq!(
            operations
                .actions
                .iter()
                .filter(|action| matches!(action, ActivationAction::SwitchProfile { .. }))
                .count(),
            1
        );
    }

    #[tokio::test]
    async fn initial_lookup_miss_requests_a2dp_once_then_switches_recovered_card_once() {
        let mut operations = RecordingActivationOperations::new(
            [None, Some(42), Some(42)],
            [true, true],
            Some("off"),
            false,
        );

        let result = activate_a2dp_profile_with_operations(&mut operations).await;

        assert_eq!(result, Ok(()));
        assert_eq!(
            operations
                .actions
                .iter()
                .filter(|action| matches!(action, ActivationAction::RequestA2dpConnection))
                .count(),
            1,
            "initial missing card must request A2DP exactly once"
        );
        assert_eq!(
            operations.actions,
            vec![
                ActivationAction::ResolveDeviceIndex,
                ActivationAction::RequestA2dpConnection,
                ActivationAction::Sleep(Duration::from_secs(2)),
                ActivationAction::Probe,
                ActivationAction::ResolveDeviceIndex,
                ActivationAction::ActiveProfile(42),
                ActivationAction::ProfileAvailable,
                ActivationAction::PreferredProfile(42),
                ActivationAction::SwitchProfile {
                    card_index: 42,
                    profile: "a2dp-sink".into(),
                },
            ]
        );
        assert_eq!(operations.current_device_index, Some(42));
        assert_eq!(
            operations
                .actions
                .iter()
                .filter(|action| matches!(action, ActivationAction::SwitchProfile { .. }))
                .count(),
            1
        );
    }

    #[tokio::test]
    async fn existing_audio_card_does_not_request_a2dp_connection() {
        let mut operations =
            RecordingActivationOperations::new([Some(42), Some(42)], [true], Some("off"), false);

        let result = activate_a2dp_profile_with_operations(&mut operations).await;

        assert_eq!(result, Ok(()));
        assert!(
            !operations
                .actions
                .contains(&ActivationAction::RequestA2dpConnection)
        );
    }

    #[tokio::test]
    async fn a2dp_request_error_still_runs_bounded_recovery() {
        let mut operations = RecordingActivationOperations::new(
            [None, Some(42), Some(42)],
            [true, true],
            Some("off"),
            false,
        );
        operations.request_result = Err("injected BlueZ failure".into());

        let result = activate_a2dp_profile_with_operations(&mut operations).await;

        assert_eq!(result, Ok(()));
        assert_eq!(
            &operations.actions[..3],
            &[
                ActivationAction::ResolveDeviceIndex,
                ActivationAction::RequestA2dpConnection,
                ActivationAction::Sleep(Duration::from_secs(2)),
            ]
        );
        assert!(operations.actions.contains(&ActivationAction::Probe));
    }

    #[tokio::test]
    async fn media_controller_requests_a2dp_once_across_repeated_misses() {
        let first_controller = controller();
        let requests = Arc::new(AtomicUsize::new(0));
        let first_requests = requests.clone();
        let second_requests = requests.clone();

        let (first, second) = tokio::join!(
            first_controller.request_a2dp_connection_once_with(CONNECTED_MAC, move |_| {
                first_requests.fetch_add(1, Ordering::SeqCst);
                Ok(())
            }),
            first_controller.request_a2dp_connection_once_with(CONNECTED_MAC, move |_| {
                second_requests.fetch_add(1, Ordering::SeqCst);
                Ok(())
            }),
        );

        assert_eq!(first, Ok(()));
        assert_eq!(second, Ok(()));
        assert_eq!(requests.load(Ordering::SeqCst), 1);

        let later_requests = requests.clone();
        assert_eq!(
            first_controller
                .request_a2dp_connection_once_with(CONNECTED_MAC, move |_| {
                    later_requests.fetch_add(1, Ordering::SeqCst);
                    Ok(())
                })
                .await,
            Ok(())
        );
        assert_eq!(requests.load(Ordering::SeqCst), 1);

        let replacement_controller = controller();
        let replacement_requests = requests.clone();
        assert_eq!(
            replacement_controller
                .request_a2dp_connection_once_with(CONNECTED_MAC, move |_| {
                    replacement_requests.fetch_add(1, Ordering::SeqCst);
                    Ok(())
                })
                .await,
            Ok(())
        );
        assert_eq!(requests.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn bluez_device_path_selection_matches_address_across_adapters() {
        let devices = [
            ("/org/bluez/hci0/dev_11_22_33_44_55_66", "11:22:33:44:55:66"),
            ("/org/bluez/hci1/dev_AA_BB_CC_DD_EE_FF", "AA:BB:CC:DD:EE:FF"),
        ];

        assert_eq!(
            bluez_device_path_for_mac("aa-bb-cc-dd-ee-ff", devices),
            Some("/org/bluez/hci1/dev_AA_BB_CC_DD_EE_FF".into())
        );
    }

    #[tokio::test]
    async fn persistent_initial_lookup_miss_stays_bounded_and_returns_error() {
        let mut operations = RecordingActivationOperations::new(
            std::iter::repeat(None).take(7),
            [],
            Some("off"),
            true,
        );

        let result = activate_a2dp_profile_with_operations(&mut operations).await;

        assert_eq!(result, Err(ProfileOperationError::A2dpProfileUnavailable));
        assert_eq!(
            operations.actions,
            vec![
                ActivationAction::ResolveDeviceIndex,
                ActivationAction::RequestA2dpConnection,
                ActivationAction::Sleep(Duration::from_secs(2)),
                ActivationAction::Probe,
                ActivationAction::Restart,
                ActivationAction::Sleep(Duration::from_secs(2)),
                ActivationAction::Probe,
                ActivationAction::Sleep(Duration::from_secs(1)),
                ActivationAction::Probe,
                ActivationAction::Sleep(Duration::from_secs(2)),
                ActivationAction::Probe,
                ActivationAction::Sleep(Duration::from_secs(3)),
                ActivationAction::Probe,
                ActivationAction::Sleep(Duration::from_secs(4)),
                ActivationAction::Probe,
            ]
        );
        assert!(operations.lookup_results.is_empty());
        assert!(
            !operations
                .actions
                .iter()
                .any(|action| matches!(action, ActivationAction::SwitchProfile { .. }))
        );
    }

    #[tokio::test]
    async fn initial_active_a2dp_profile_remains_untouched() {
        let mut operations =
            RecordingActivationOperations::new([Some(42)], [], Some("a2dp-sink-aac"), false);

        let result = activate_a2dp_profile_with_operations(&mut operations).await;

        assert_eq!(result, Ok(()));
        assert_eq!(
            operations.actions,
            vec![
                ActivationAction::ResolveDeviceIndex,
                ActivationAction::ActiveProfile(42),
                ActivationAction::CurrentDeviceIndex,
            ]
        );
    }

    #[tokio::test]
    async fn activation_selects_profile_after_refreshing_card_index() {
        let mut operations =
            RecordingActivationOperations::new([Some(21), Some(42)], [true], Some("off"), false);

        let result = activate_a2dp_profile_with_operations(&mut operations).await;

        assert_eq!(result, Ok(()));
        assert_eq!(
            operations.actions,
            vec![
                ActivationAction::ResolveDeviceIndex,
                ActivationAction::ActiveProfile(21),
                ActivationAction::ResolveDeviceIndex,
                ActivationAction::ActiveProfile(42),
                ActivationAction::ProfileAvailable,
                ActivationAction::PreferredProfile(42),
                ActivationAction::SwitchProfile {
                    card_index: 42,
                    profile: "a2dp-sink".into(),
                },
            ]
        );
    }

    #[tokio::test]
    async fn recreated_final_card_recovers_and_selects_its_own_profile() {
        let mut operations = RecordingActivationOperations::new(
            [Some(21), Some(42), Some(42), Some(42)],
            [false, true, true],
            Some("off"),
            false,
        );
        operations
            .preferred_profiles_by_index
            .insert(21, "a2dp-sink-sbc-xq".into());
        operations
            .preferred_profiles_by_index
            .insert(42, "a2dp-sink-aac".into());

        let result = activate_a2dp_profile_with_operations(&mut operations).await;

        assert_eq!(result, Ok(()));
        assert!(operations.actions.contains(&ActivationAction::Probe));
        assert!(
            !operations
                .actions
                .contains(&ActivationAction::PreferredProfile(21)),
            "the replaced card's profile must not be selected"
        );
        let final_resolution = operations
            .actions
            .iter()
            .rposition(|action| matches!(action, ActivationAction::ResolveDeviceIndex))
            .expect("activation did not perform a final card lookup");
        let final_selection = operations
            .actions
            .iter()
            .position(|action| *action == ActivationAction::PreferredProfile(42))
            .expect("activation did not select the recreated card's profile");
        assert!(final_selection > final_resolution);
        assert_eq!(
            operations.actions.last(),
            Some(&ActivationAction::SwitchProfile {
                card_index: 42,
                profile: "a2dp-sink-aac".into(),
            })
        );
    }

    async fn takeover_manager(capacity: usize) -> (AACPManager, mpsc::Receiver<QueuedAacpPacket>) {
        let manager = AACPManager::new();
        let (packet_tx, packet_rx) = mpsc::channel(capacity);
        {
            let mut state = manager.state.lock().await;
            state.sender = Some(packet_tx);
            state.connection_generation = 7;
            state.ear_detection_status = vec![EarDetectionStatus::InEar];
            state.connected_devices = vec![ConnectedDevice {
                mac: REMOTE_MAC.into(),
                info1: 0,
                info2: 0,
                r#type: None,
            }];
        }
        (manager, packet_rx)
    }

    #[tokio::test]
    async fn device_index_lookup_replaces_stale_cache() {
        let controller = controller();
        controller.state.lock().await.device_index = Some(7);
        let lookup_controller = controller.clone();

        let device_index = timeout(
            Duration::from_millis(250),
            controller.resolve_device_index(CONNECTED_MAC, async move {
                let state = lookup_controller.state.lock().await;
                assert_eq!(state.device_index, Some(7));
                drop(state);
                Some(21)
            }),
        )
        .await
        .expect("device lookup waited on a MediaController state guard")
        .unwrap();

        assert_eq!(device_index, 21);
        assert_eq!(controller.state.lock().await.device_index, Some(21));
    }

    #[tokio::test]
    async fn concurrent_device_index_lookups_cannot_overwrite_newer_result() {
        let controller = controller();
        let first_controller = controller.clone();
        let (first_started_tx, first_started_rx) = oneshot::channel();
        let (release_first_tx, release_first_rx) = oneshot::channel();
        let first_lookup = tokio::spawn(async move {
            first_controller
                .resolve_device_index(CONNECTED_MAC, async move {
                    first_started_tx
                        .send(())
                        .expect("first lookup start receiver dropped");
                    release_first_rx
                        .await
                        .expect("first lookup release sender dropped");
                    Some(21)
                })
                .await
        });

        timeout(Duration::from_millis(250), first_started_rx)
            .await
            .expect("first lookup did not start")
            .expect("first lookup start sender dropped");

        let mut second_lookup =
            Box::pin(controller.resolve_device_index(CONNECTED_MAC, async move { Some(42) }));
        tokio::select! {
            biased;
            result = &mut second_lookup => {
                panic!("second lookup completed before the first lookup: {result:?}");
            }
            _ = tokio::task::yield_now() => {}
        }

        release_first_tx
            .send(())
            .expect("first lookup release receiver dropped");
        assert_eq!(
            timeout(Duration::from_millis(250), first_lookup)
                .await
                .expect("first lookup did not complete")
                .expect("first lookup task failed")
                .unwrap(),
            21
        );
        assert_eq!(
            timeout(Duration::from_millis(250), &mut second_lookup)
                .await
                .expect("second lookup did not complete")
                .unwrap(),
            42
        );
        assert_eq!(controller.state.lock().await.device_index, Some(42));
    }

    #[tokio::test]
    async fn deactivation_does_not_fall_back_to_stale_device_index() {
        let controller = controller();
        controller.state.lock().await.device_index = Some(7);
        let lookup_controller = controller.clone();

        let result = timeout(
            Duration::from_millis(250),
            controller.deactivate_a2dp_profile_with_lookup(CONNECTED_MAC, async move {
                let state = lookup_controller.state.lock().await;
                assert_eq!(state.device_index, Some(7));
                drop(state);
                None
            }),
        )
        .await
        .expect("deactivation lookup waited on a MediaController state guard");

        assert_eq!(result, Err(ProfileOperationError::AudioDeviceNotFound));
        assert_eq!(controller.state.lock().await.device_index, None);
    }

    #[tokio::test]
    async fn device_index_lookup_rejects_connected_device_change() {
        let controller = controller();
        let lookup_controller = controller.clone();

        let result = controller
            .resolve_device_index(CONNECTED_MAC, async move {
                lookup_controller.state.lock().await.connected_device_mac = REMOTE_MAC.into();
                Some(21)
            })
            .await;

        assert_eq!(result, Err(ProfileOperationError::ConnectedDeviceChanged));
    }

    #[tokio::test]
    async fn profile_probe_refreshes_index_without_media_state_guard() {
        let controller = controller();
        let lookup_controller = controller.clone();
        let recheck_controller = controller.clone();

        let is_available = timeout(
            Duration::from_millis(250),
            controller.probe_a2dp_profile_with_lookup(
                CONNECTED_MAC,
                async move {
                    let state = lookup_controller.state.lock().await;
                    drop(state);
                    Some(42)
                },
                async move {
                    let state = recheck_controller.state.lock().await;
                    assert_eq!(state.device_index, Some(42));
                    true
                },
            ),
        )
        .await
        .expect("profile probe retained the MediaController state guard")
        .unwrap();

        assert!(is_available);
        assert_eq!(controller.state.lock().await.device_index, Some(42));
    }

    #[tokio::test]
    async fn profile_probe_treats_missing_audio_card_as_not_ready() {
        let controller = controller();
        controller.state.lock().await.device_index = Some(7);

        let result = controller
            .probe_a2dp_profile_with_lookup(CONNECTED_MAC, async { None }, async { true })
            .await;

        assert_eq!(result, Ok(false));
        assert_eq!(controller.state.lock().await.device_index, None);
    }

    #[tokio::test]
    async fn takeover_aborts_when_connection_generation_changes_during_activation() {
        let (controller, listener_generation) = takeover_controller();
        let (manager, mut packet_rx) = takeover_manager(4).await;
        let activation_manager = manager.clone();

        let outcome = controller
            .handle_playback_started(
                &manager,
                LOCAL_MAC,
                CONNECTED_MAC,
                listener_generation,
                async move {
                    activation_manager.state.lock().await.connection_generation += 1;
                    Ok::<(), ProfileOperationError>(())
                },
            )
            .await;

        assert_eq!(outcome, PlaybackTakeoverOutcome::AbortedConnectionChanged);
        assert!(packet_rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn takeover_aborts_when_ear_state_changes_during_activation() {
        let (controller, listener_generation) = takeover_controller();
        let (manager, mut packet_rx) = takeover_manager(4).await;
        let activation_manager = manager.clone();

        let outcome = controller
            .handle_playback_started(
                &manager,
                LOCAL_MAC,
                CONNECTED_MAC,
                listener_generation,
                async move {
                    activation_manager.state.lock().await.ear_detection_status =
                        vec![EarDetectionStatus::OutOfEar];
                    Ok::<(), ProfileOperationError>(())
                },
            )
            .await;

        assert_eq!(outcome, PlaybackTakeoverOutcome::AbortedEarStateChanged);
        assert!(packet_rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn takeover_aborts_when_listener_generation_changes_during_activation() {
        let playback_listeners = PlaybackListenerRegistry::default();
        let controller =
            controller_with_listener_registry(CONNECTED_MAC, playback_listeners.clone());
        let placeholder = tokio::spawn(async {});
        let listener_generation =
            playback_listeners.replace(CONNECTED_MAC, placeholder.abort_handle());
        let (manager, mut packet_rx) = takeover_manager(4).await;
        let activation_listeners = playback_listeners.clone();
        let replacement_placeholder = tokio::spawn(async {});
        let replacement_abort_handle = replacement_placeholder.abort_handle();

        let outcome = controller
            .handle_playback_started(
                &manager,
                LOCAL_MAC,
                CONNECTED_MAC,
                listener_generation,
                async move {
                    activation_listeners.replace(CONNECTED_MAC, replacement_abort_handle);
                    Ok::<(), ProfileOperationError>(())
                },
            )
            .await;

        assert_eq!(outcome, PlaybackTakeoverOutcome::AbortedListenerReplaced);
        assert!(packet_rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn takeover_reports_profile_activation_failure_without_sending() {
        let (controller, listener_generation) = takeover_controller();
        let (manager, mut packet_rx) = takeover_manager(4).await;

        let outcome = controller
            .handle_playback_started(
                &manager,
                LOCAL_MAC,
                CONNECTED_MAC,
                listener_generation,
                async { Err(ProfileOperationError::WirePlumberRestartFailed) },
            )
            .await;

        assert_eq!(
            outcome,
            PlaybackTakeoverOutcome::Failed(PlaybackTakeoverFailure::ProfileActivation(
                ProfileOperationError::WirePlumberRestartFailed,
            ),)
        );
        assert!(packet_rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn takeover_reports_closed_ownership_channel() {
        let (controller, listener_generation) = takeover_controller();
        let (manager, packet_rx) = takeover_manager(4).await;
        drop(packet_rx);

        let outcome = controller
            .handle_playback_started(
                &manager,
                LOCAL_MAC,
                CONNECTED_MAC,
                listener_generation,
                async { Ok::<(), ProfileOperationError>(()) },
            )
            .await;

        assert!(matches!(
            outcome,
            PlaybackTakeoverOutcome::Failed(PlaybackTakeoverFailure::OwnershipSend(_))
        ));
    }

    #[tokio::test]
    async fn takeover_reports_routing_send_failure() {
        let (controller, listener_generation) = takeover_controller();
        let (manager, mut packet_rx) = takeover_manager(1).await;
        let takeover = tokio::spawn(async move {
            controller
                .handle_playback_started(
                    &manager,
                    LOCAL_MAC,
                    CONNECTED_MAC,
                    listener_generation,
                    async { Ok::<(), ProfileOperationError>(()) },
                )
                .await
        });

        let ownership = timeout(Duration::from_millis(250), packet_rx.recv())
            .await
            .expect("timed out waiting for ownership packet")
            .expect("ownership packet channel closed")
            .into_fire_and_forget_data();
        assert_eq!(ownership[4], opcodes::CONTROL_COMMAND);
        drop(packet_rx);

        let outcome = timeout(Duration::from_millis(250), takeover)
            .await
            .expect("routing send did not observe the closed channel")
            .expect("takeover task failed");
        assert!(matches!(
            outcome,
            PlaybackTakeoverOutcome::Failed(PlaybackTakeoverFailure::RoutingSend { .. })
        ));
    }

    #[tokio::test]
    async fn takeover_queues_ownership_before_exact_routing_packet_order() {
        let (controller, listener_generation) = takeover_controller();
        let (manager, mut packet_rx) = takeover_manager(4).await;

        let outcome = timeout(
            Duration::from_millis(250),
            controller.handle_playback_started(
                &manager,
                LOCAL_MAC,
                CONNECTED_MAC,
                listener_generation,
                async { Ok::<(), ProfileOperationError>(()) },
            ),
        )
        .await
        .expect("playback takeover deadlocked while sending packets");
        assert_eq!(outcome, PlaybackTakeoverOutcome::Completed);

        let mut packets = Vec::new();
        for _ in 0..4 {
            packets.push(
                timeout(Duration::from_millis(250), packet_rx.recv())
                    .await
                    .expect("timed out waiting for takeover packet")
                    .expect("takeover packet channel closed")
                    .into_fire_and_forget_data(),
            );
        }

        assert_eq!(
            packets[0],
            vec![
                0x04,
                0x00,
                0x04,
                0x00,
                opcodes::CONTROL_COMMAND,
                0x00,
                ControlCommandIdentifiers::OwnsConnection as u8,
                0x01,
                0x00,
                0x00,
                0x00,
            ]
        );
        assert_eq!(
            packets[1..]
                .iter()
                .map(|packet| (packet[4], packet[12]))
                .collect::<Vec<_>>(),
            vec![
                (opcodes::SMART_ROUTING, 0x82),
                (opcodes::SMART_ROUTING, 0x7E),
                (opcodes::SMART_ROUTING, 0x62),
            ]
        );
        assert!(packet_rx.try_recv().is_err());
    }

    #[test]
    fn active_hyphenated_a2dp_codec_skips_profile_switch() {
        assert!(!should_switch_to_a2dp_profile(Some("a2dp-sink-aac")));
    }

    #[test]
    fn active_underscored_a2dp_codec_skips_profile_switch() {
        assert!(!should_switch_to_a2dp_profile(Some("a2dp_sink_aac")));
    }

    #[test]
    fn active_off_profile_requires_profile_switch() {
        assert!(should_switch_to_a2dp_profile(Some("off")));
    }

    #[test]
    fn active_headset_profile_requires_profile_switch() {
        assert!(should_switch_to_a2dp_profile(Some("headset-head-unit")));
    }

    #[test]
    fn preferred_a2dp_profile_returns_hyphenated_name_exactly() {
        let profiles = ["off", "headset-head-unit", "a2dp-sink"];

        assert_eq!(preferred_a2dp_profile(profiles), Some("a2dp-sink"));
    }

    #[test]
    fn preferred_a2dp_profile_returns_underscored_name_exactly() {
        let profiles = ["off", "headset_head_unit", "a2dp_sink"];

        assert_eq!(preferred_a2dp_profile(profiles), Some("a2dp_sink"));
    }

    #[test]
    fn preferred_a2dp_profile_returns_mixed_separator_codec_name_exactly() {
        let profiles = ["off", "a2dp-sink-sbc_xq"];

        assert_eq!(preferred_a2dp_profile(profiles), Some("a2dp-sink-sbc_xq"));
    }

    #[test]
    fn preferred_a2dp_profile_preserves_codec_preference_order() {
        let profiles = [
            "a2dp_sink_aac",
            "a2dp_sink",
            "a2dp-sink-sbc",
            "a2dp-sink-sbc_xq",
        ];

        assert_eq!(preferred_a2dp_profile(profiles), Some("a2dp-sink-sbc_xq"));
    }

    #[test]
    fn preferred_a2dp_profile_falls_back_to_another_sink_codec() {
        let profiles = ["off", "a2dp_sink_ldac"];

        assert_eq!(preferred_a2dp_profile(profiles), Some("a2dp_sink_ldac"));
    }

    #[test]
    fn preferred_a2dp_profile_rejects_non_sink_profiles() {
        let profiles = [
            "off",
            "headset_head_unit",
            "a2dp-source-aac",
            "not-a2dp-sink-aac",
            "a2dp_sink_",
        ];

        assert_eq!(preferred_a2dp_profile(profiles), None);
    }

    #[test]
    fn pulse_property_matches_mac_in_device_string() {
        assert!(pulse_property_matches_mac(
            "bluez_card.28-2D_7f-AA_bb-CC",
            "28:2d:7F:aa:BB:cc"
        ));
    }

    #[test]
    fn pulse_property_matches_mac_in_bluez_path() {
        assert!(pulse_property_matches_mac(
            "/org/bluez/hci0/dev_28_2D_7F_AA_BB_CC",
            "28-2d-7f-aa-bb-cc"
        ));
    }

    #[test]
    fn pulse_property_matches_mac_rejects_another_device() {
        assert!(!pulse_property_matches_mac(
            "bluez_card.28_2D_7F_AA_BB_CD",
            "28:2d:7f:aa:bb:cc"
        ));
    }

    #[test]
    fn pulse_property_matches_mac_rejects_empty_mac() {
        assert!(!pulse_property_matches_mac(
            "bluez_card.28_2D_7F_AA_BB_CC",
            ""
        ));
    }
}
