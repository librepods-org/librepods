use dbus::blocking::Connection;
use dbus::blocking::stdintf::org_freedesktop_dbus::ObjectManager;
use libpulse_binding::callbacks::ListResult;
use libpulse_binding::context::introspect::SinkInfo;
use libpulse_binding::context::{Context, FlagSet as ContextFlagSet};
use libpulse_binding::def::Retval;
use libpulse_binding::mainloop::standard::Mainloop;
use libpulse_binding::operation::State as OperationState;
use libpulse_binding::proplist::Proplist;
use libpulse_binding::volume::{ChannelVolumes, Volume};
use log::{error, info, warn};
use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

pub const PREFERRED_A2DP_PROFILES: [&str; 3] = ["a2dp-sink-sbc_xq", "a2dp-sink-sbc", "a2dp-sink"];
pub const A2DP_SINK_UUID: &str = "0000110b-0000-1000-8000-00805f9b34fb";

#[derive(Debug, Clone, PartialEq)]
pub struct ProfileSnapshot {
    pub name: String,
    pub available: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CardSnapshot {
    pub index: u32,
    pub name: String,
    pub device_string: Option<String>,
    pub active_profile: Option<String>,
    pub profiles: Vec<ProfileSnapshot>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum A2dpAction {
    AlreadyActive,
    SetProfile(String),
    ConnectProfile,
    NoCard,
}

/// Finds the PipeWire/Pulse card belonging to the Bluetooth device `mac`, never any other card.
pub fn find_bluez_card<'a>(cards: &'a [CardSnapshot], mac: &str) -> Option<&'a CardSnapshot> {
    let card_name = format!("bluez_card.{}", mac.replace(':', "_"));
    cards.iter().find(|card| {
        card.name.eq_ignore_ascii_case(&card_name)
            || card
                .device_string
                .as_deref()
                .is_some_and(|s| s.eq_ignore_ascii_case(mac))
    })
}

/// Decides what to do to get A2DP playing on `card`. An active A2DP or headset (HFP/HSP)
/// profile is the user's choice and is left alone; only an "off" card gets activated.
pub fn plan_a2dp_activation(card: Option<&CardSnapshot>) -> A2dpAction {
    let Some(card) = card else {
        return A2dpAction::NoCard;
    };
    if card
        .active_profile
        .as_deref()
        .is_some_and(|p| p.starts_with("a2dp-sink") || p.starts_with("headset-head-unit"))
    {
        return A2dpAction::AlreadyActive;
    }
    PREFERRED_A2DP_PROFILES
        .iter()
        .find(|preferred| {
            card.profiles
                .iter()
                .any(|p| p.name == **preferred && p.available)
        })
        .map(|p| A2dpAction::SetProfile(p.to_string()))
        .unwrap_or(A2dpAction::ConnectProfile)
}

/// One lock per Bluetooth device, shared by every instance that ever handles that device, so a
/// stale instance's card change can never interleave with a new instance's.
pub fn audio_lock_for(mac: &str) -> Arc<tokio::sync::Mutex<()>> {
    static LOCKS: OnceLock<Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>> = OnceLock::new();
    let mut locks = LOCKS
        .get_or_init(|| Mutex::new(HashMap::new()))
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    locks.entry(mac.to_ascii_uppercase()).or_default().clone()
}

/// Blocking audio-system operations. Callers run these inside `spawn_blocking`.
pub trait AudioBackend: Send + Sync + 'static {
    fn cards(&self) -> Vec<CardSnapshot>;
    fn set_card_profile(&self, card_index: u32, profile: &str) -> bool;
    fn connect_a2dp(&self, mac: &str) -> bool;
}

/// The real backend: libpulse (pipewire-pulse) for cards, BlueZ over the system bus for profiles.
pub struct SystemAudioBackend;

impl AudioBackend for SystemAudioBackend {
    fn cards(&self) -> Vec<CardSnapshot> {
        get_card_info_list_sync()
    }

    fn set_card_profile(&self, card_index: u32, profile: &str) -> bool {
        set_card_profile_sync(card_index, profile)
    }

    fn connect_a2dp(&self, mac: &str) -> bool {
        let conn = match Connection::new_system() {
            Ok(c) => c,
            Err(e) => {
                error!("Failed to connect to system D-Bus: {}", e);
                return false;
            }
        };
        let Some(adapter_path) = first_adapter_path(&conn) else {
            error!("No Bluetooth adapter found, cannot connect A2DP for {}", mac);
            return false;
        };
        let device_path = format!("{}/dev_{}", adapter_path, mac.replace(':', "_"));
        info!("Asking BlueZ to connect A2DP for {}", mac);
        let proxy = conn.with_proxy("org.bluez", device_path, Duration::from_secs(10));
        match proxy.method_call::<(), _, _, _>("org.bluez.Device1", "ConnectProfile", (A2DP_SINK_UUID,)) {
            Ok(()) => true,
            Err(e) => {
                warn!("BlueZ ConnectProfile(A2DP) failed for {}: {}", mac, e);
                false
            }
        }
    }
}

fn first_adapter_path(conn: &Connection) -> Option<String> {
    let proxy = conn.with_proxy("org.bluez", "/", Duration::from_secs(5));
    let objects = proxy.get_managed_objects().ok()?;
    let mut adapters: Vec<String> = objects
        .into_iter()
        .filter(|(_, interfaces)| interfaces.contains_key("org.bluez.Adapter1"))
        .map(|(path, _)| path.to_string())
        .collect();
    adapters.sort();
    adapters.into_iter().next()
}

// --- PulseAudio helpers ---

#[derive(Clone, Debug)]
struct OwnedSinkInfo {
    name: Option<String>,
    proplist: Proplist,
    volume: ChannelVolumes,
}

fn pulse_connect() -> Option<(Mainloop, Context)> {
    let mut mainloop = Mainloop::new()?;
    let mut context = Context::new(&mainloop, "LibrePods")?;
    context.connect(None, ContextFlagSet::NOAUTOSPAWN, None).ok()?;
    loop {
        mainloop.iterate(true);
        match context.get_state() {
            libpulse_binding::context::State::Ready => break,
            libpulse_binding::context::State::Failed
            | libpulse_binding::context::State::Terminated => return None,
            _ => {}
        }
    }
    Some((mainloop, context))
}

fn get_card_info_list_sync() -> Vec<CardSnapshot> {
    let (mut mainloop, context) = match pulse_connect() {
        Some(c) => c,
        None => return vec![],
    };

    let introspector = context.introspect();
    let cards: Rc<RefCell<Vec<CardSnapshot>>> = Rc::new(RefCell::new(Vec::new()));
    let op = introspector.get_card_info_list({
        let cards = cards.clone();
        move |result| {
            if let ListResult::Item(item) = result {
                cards.borrow_mut().push(CardSnapshot {
                    index: item.index,
                    name: item.name.as_ref().map(|n| n.to_string()).unwrap_or_default(),
                    device_string: item.proplist.get_str("device.string"),
                    active_profile: item
                        .active_profile
                        .as_ref()
                        .and_then(|p| p.name.as_ref().map(|n| n.to_string())),
                    profiles: item
                        .profiles
                        .iter()
                        .filter_map(|p| {
                            p.name.as_ref().map(|n| ProfileSnapshot {
                                name: n.to_string(),
                                available: p.available,
                            })
                        })
                        .collect(),
                });
            }
        }
    });

    while op.get_state() == OperationState::Running {
        mainloop.iterate(true);
    }
    mainloop.quit(Retval(0));
    cards.take()
}

pub fn set_card_profile_sync(card_index: u32, profile_name: &str) -> bool {
    let (mut mainloop, context) = match pulse_connect() {
        Some(c) => c,
        None => return false,
    };
    let mut introspector = context.introspect();
    let succeeded = Rc::new(RefCell::new(false));
    let op = introspector.set_card_profile_by_index(
        card_index,
        profile_name,
        Some(Box::new({
            let succeeded = succeeded.clone();
            move |success| *succeeded.borrow_mut() = success
        })),
    );
    while op.get_state() == OperationState::Running {
        mainloop.iterate(true);
    }
    mainloop.quit(Retval(0));
    succeeded.take()
}

pub fn get_sink_volume_percent_by_name_sync(sink_name: &str) -> Option<u32> {
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
        mainloop.iterate(true);
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
        mainloop.iterate(true);
    }

    if let Some(info) = sink_info.borrow().as_ref() {
        let channels = info.volume.len();
        let mut new_volumes = ChannelVolumes::default();
        let raw =
            (((target_volume as f64) / 100.0) * Volume::NORMAL.0 as f64).round() as u32;
        new_volumes.set(channels, Volume(raw));

        let op = introspector.set_sink_volume_by_name(sink_name, &new_volumes, None);
        while op.get_state() == OperationState::Running {
            mainloop.iterate(true);
        }
        mainloop.quit(Retval(0));
        true
    } else {
        error!("Sink not found: {}", sink_name);
        false
    }
}

pub async fn get_sink_name_by_mac(mac: &str) -> Option<String> {
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
            mainloop.iterate(true);
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
#[cfg(test)]
mod tests {
    use super::*;

    const MAC: &str = "AA:BB:CC:DD:EE:01";

    fn card(
        index: u32,
        name: &str,
        device_string: Option<&str>,
        active: Option<&str>,
        profiles: &[(&str, bool)],
    ) -> CardSnapshot {
        CardSnapshot {
            index,
            name: name.to_string(),
            device_string: device_string.map(|s| s.to_string()),
            active_profile: active.map(|s| s.to_string()),
            profiles: profiles
                .iter()
                .map(|(name, available)| ProfileSnapshot {
                    name: name.to_string(),
                    available: *available,
                })
                .collect(),
        }
    }

    fn alsa_cards() -> Vec<CardSnapshot> {
        vec![
            card(925, "alsa_card.pci-0000_01_00.1", None, Some("output:hdmi-stereo"), &[]),
            card(926, "alsa_card.pci-0000_00_1f.3", None, Some("output:analog-stereo"), &[]),
        ]
    }

    #[test]
    fn finds_card_by_name() {
        let mut cards = alsa_cards();
        cards.push(card(1043, "bluez_card.AA_BB_CC_DD_EE_01", None, None, &[]));
        assert_eq!(find_bluez_card(&cards, MAC).map(|c| c.index), Some(1043));
    }

    #[test]
    fn finds_card_by_device_string_lowercase() {
        let cards = vec![card(
            7,
            "bluez_card.renamed",
            Some("aa:bb:cc:dd:ee:01"),
            None,
            &[],
        )];
        assert_eq!(find_bluez_card(&cards, MAC).map(|c| c.index), Some(7));
    }

    #[test]
    fn ignores_other_cards_and_returns_none_when_absent() {
        assert_eq!(find_bluez_card(&alsa_cards(), MAC), None);
    }

    #[test]
    fn does_not_match_other_airpods() {
        let cards = vec![card(
            50,
            "bluez_card.11_22_33_44_55_66",
            Some("11:22:33:44:55:66"),
            Some("a2dp-sink"),
            &[("a2dp-sink", true)],
        )];
        assert_eq!(find_bluez_card(&cards, MAC), None);
    }

    #[test]
    fn no_card_means_no_card() {
        assert_eq!(plan_a2dp_activation(None), A2dpAction::NoCard);
    }

    #[test]
    fn active_a2dp_profile_is_left_alone() {
        let c = card(
            1043,
            "bluez_card.AA_BB_CC_DD_EE_01",
            None,
            Some("a2dp-sink"),
            &[("a2dp-sink", true), ("a2dp-sink-sbc_xq", true)],
        );
        assert_eq!(plan_a2dp_activation(Some(&c)), A2dpAction::AlreadyActive);
    }

    #[test]
    fn active_headset_profile_is_left_alone() {
        let c = card(
            1043,
            "bluez_card.AA_BB_CC_DD_EE_01",
            None,
            Some("headset-head-unit-msbc"),
            &[("a2dp-sink-sbc_xq", true), ("headset-head-unit-msbc", true)],
        );
        assert_eq!(plan_a2dp_activation(Some(&c)), A2dpAction::AlreadyActive);
    }

    #[test]
    fn picks_first_available_preferred_profile() {
        let c = card(
            1043,
            "bluez_card.AA_BB_CC_DD_EE_01",
            None,
            Some("off"),
            &[
                ("a2dp-sink", true),
                ("a2dp-sink-sbc", true),
                ("a2dp-sink-sbc_xq", true),
            ],
        );
        assert_eq!(
            plan_a2dp_activation(Some(&c)),
            A2dpAction::SetProfile("a2dp-sink-sbc_xq".to_string())
        );
    }

    #[test]
    fn skips_unavailable_profiles() {
        let c = card(
            1043,
            "bluez_card.AA_BB_CC_DD_EE_01",
            None,
            Some("off"),
            &[("a2dp-sink-sbc_xq", false), ("a2dp-sink-sbc", true)],
        );
        assert_eq!(
            plan_a2dp_activation(Some(&c)),
            A2dpAction::SetProfile("a2dp-sink-sbc".to_string())
        );
    }

    #[test]
    fn connect_profile_when_no_a2dp_available() {
        let c = card(
            1043,
            "bluez_card.AA_BB_CC_DD_EE_01",
            None,
            Some("off"),
            &[("off", true), ("a2dp-sink", false)],
        );
        assert_eq!(plan_a2dp_activation(Some(&c)), A2dpAction::ConnectProfile);
    }
}
