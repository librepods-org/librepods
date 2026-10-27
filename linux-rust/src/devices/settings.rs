//! What the app shows and sends for each AirPods model: listening-mode rules, the catalog of
//! control commands (opcode 0x09) with their gating, and the byte codecs for each kind of row.

use crate::bluetooth::aacp::ControlCommandIdentifiers;
use crate::devices::models::{AirPodsModel, Capability};
use crate::prefs::StemActions;

/// The firmware major version: the first character of the Information packet's version3.
pub fn firmware_major(version3: &str) -> Option<u32> {
    version3.trim().chars().next()?.to_digit(10)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ListeningMode {
    Off,
    NoiseCancellation,
    Transparency,
    Adaptive,
}

use ListeningMode::{Adaptive, NoiseCancellation, Off, Transparency};

/// The order a press-and-hold cycle walks through the modes.
const CYCLE_ORDER: [ListeningMode; 4] = [Off, NoiseCancellation, Transparency, Adaptive];

impl ListeningMode {
    /// The value of control 0x0D.
    pub fn byte(self) -> u8 {
        match self {
            Off => 0x01,
            NoiseCancellation => 0x02,
            Transparency => 0x03,
            Adaptive => 0x04,
        }
    }

    pub fn from_byte(b: u8) -> Option<Self> {
        match b {
            0x01 => Some(Off),
            0x02 => Some(NoiseCancellation),
            0x03 => Some(Transparency),
            0x04 => Some(Adaptive),
            _ => None,
        }
    }

    /// This mode's bit in the press-and-hold cycle (control 0x1A).
    pub fn cycle_bit(self) -> u8 {
        match self {
            Off => 0x01,
            NoiseCancellation => 0x02,
            Transparency => 0x04,
            Adaptive => 0x08,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Off => "Off",
            NoiseCancellation => "Noise Cancellation",
            Transparency => "Transparency",
            Adaptive => "Adaptive",
        }
    }

    pub fn cli_name(self) -> &'static str {
        match self {
            Off => "off",
            NoiseCancellation => "anc",
            Transparency => "transparency",
            Adaptive => "adaptive",
        }
    }
}

/// The listening modes this model offers. Models from before Adaptive Audio always have Off;
/// newer ones offer it only while control 0x34 (Allow Off Option) is on.
pub fn listening_modes(model: AirPodsModel, allow_off: Option<bool>) -> Vec<ListeningMode> {
    if !model.has(Capability::ListeningMode) {
        return Vec::new();
    }
    let adaptive = model.has(Capability::AdaptiveAudio);
    let mut modes = Vec::with_capacity(4);
    if allow_off == Some(true) || !adaptive {
        modes.push(Off);
    }
    modes.push(NoiseCancellation);
    modes.push(Transparency);
    if adaptive {
        modes.push(Adaptive);
    }
    modes
}

/// Apple's out-of-the-box press-and-hold cycle for this model.
pub fn default_cycle(model: AirPodsModel) -> u8 {
    let mut cycle = NoiseCancellation.cycle_bit() | Transparency.cycle_bit();
    if model.has(Capability::AdaptiveAudio) {
        cycle |= Adaptive.cycle_bit();
    }
    cycle
}

/// Adds or removes one mode from the cycle; a cycle always keeps at least two modes.
pub fn toggle_cycle_mode(current: u8, mode: ListeningMode) -> u8 {
    let bit = mode.cycle_bit();
    if current & bit == 0 {
        return current | bit;
    }
    let cleared = current & !bit;
    if (cleared & 0x0F).count_ones() >= 2 {
        cleared
    } else {
        current
    }
}

/// The mode a press-and-hold moves to: the next one after `current` that is both in the cycle
/// and offered by the model.
pub fn next_in_cycle(
    current: Option<ListeningMode>,
    cycle: u8,
    available: &[ListeningMode],
) -> Option<ListeningMode> {
    let start = current
        .and_then(|mode| CYCLE_ORDER.iter().position(|m| *m == mode))
        .map_or(0, |index| index + 1);
    (0..CYCLE_ORDER.len())
        .map(|step| CYCLE_ORDER[(start + step) % CYCLE_ORDER.len()])
        .find(|mode| cycle & mode.cycle_bit() != 0 && available.contains(mode))
}

/// A control value as the AirPods report it: trailing zero bytes dropped, all zeros → `[0]`.
pub fn trim_value(value: &[u8]) -> Vec<u8> {
    match value.iter().rposition(|&b| b != 0) {
        Some(last) => value[..=last].to_vec(),
        None => vec![0],
    }
}

pub fn encode_toggle(on: bool) -> Vec<u8> {
    vec![if on { 0x01 } else { 0x02 }]
}

pub fn decode_toggle(value: &[u8]) -> Option<bool> {
    match value.first() {
        Some(0x01) => Some(true),
        Some(0x02) => Some(false),
        _ => None,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Section {
    Audio,
    PressAndHold,
    Calls,
    Microphone,
    Connection,
    Battery,
    Accessibility,
    Advanced,
}

#[derive(Debug)]
pub struct ChoiceOption {
    pub label: &'static str,
    pub value: &'static [u8],
}

#[derive(Debug)]
pub enum ControlKind {
    /// On `01`, off `02`.
    Toggle,
    Choice(&'static [ChoiceOption]),
    /// One value byte in `0..=max`, followed by `suffix`.
    Slider { max: u8, suffix: &'static [u8] },
    /// `[right, left]`, each one of the options' single-byte values.
    PerBud(&'static [ChoiceOption]),
}

#[derive(Debug)]
pub struct ControlSpec {
    pub id: ControlCommandIdentifiers,
    pub section: Section,
    pub label: &'static str,
    pub description: &'static str,
    pub kind: ControlKind,
}

const fn option(label: &'static str, value: &'static [u8]) -> ChoiceOption {
    ChoiceOption { label, value }
}

const PRESS_AND_HOLD: &[ChoiceOption] = &[option("Noise Control", &[0x01]), option("Siri", &[0x05])];
const CALL_CONTROLS: &[ChoiceOption] = &[
    option("Press once to mute, twice to hang up", &[0x00, 0x03]),
    option("Press once to hang up, twice to mute", &[0x00, 0x02]),
];
const MICROPHONE: &[ChoiceOption] = &[
    option("Automatic", &[0x00]),
    option("Always Right", &[0x01]),
    option("Always Left", &[0x02]),
];
const PRESS_SPEED: &[ChoiceOption] = &[
    option("Default", &[0x00]),
    option("Slower", &[0x01]),
    option("Slowest", &[0x02]),
];
const SWIPE_SPEED: &[ChoiceOption] = &[
    option("Default", &[0x01]),
    option("Longer", &[0x02]),
    option("Longest", &[0x03]),
];
const CROWN_DIRECTION: &[ChoiceOption] = &[option("Default", &[0x02]), option("Reversed", &[0x01])];

use ControlCommandIdentifiers as Id;

/// Every control the page draws from data, in page order. Listening mode (0x0D), adaptive
/// strength (0x2E), the press-and-hold cycle (0x1A) and stem forwarding (0x39) have their own UI.
static CATALOG: &[ControlSpec] = &[
    ControlSpec {
        id: Id::ConversationDetectConfig,
        section: Section::Audio,
        label: "Conversation Awareness",
        description: "Lowers media volume and reduces background noise when you start speaking to other people.",
        kind: ControlKind::Toggle,
    },
    ControlSpec {
        id: Id::AdaptiveVolumeConfig,
        section: Section::Audio,
        label: "Personalized Volume",
        description: "Adjusts the volume of media in response to your environment.",
        kind: ControlKind::Toggle,
    },
    ControlSpec {
        id: Id::SleepDetectionConfig,
        section: Section::Audio,
        label: "Pause media when falling asleep",
        description: "",
        kind: ControlKind::Toggle,
    },
    ControlSpec {
        id: Id::AllowOffOption,
        section: Section::Audio,
        label: "Off Listening Mode",
        description: "When this is on, AirPods listening modes will include an Off option. Loud sound levels are not reduced when the listening mode is set to Off.",
        kind: ControlKind::Toggle,
    },
    ControlSpec {
        id: Id::ClickHoldMode,
        section: Section::PressAndHold,
        label: "Press and Hold",
        description: "",
        kind: ControlKind::PerBud(PRESS_AND_HOLD),
    },
    ControlSpec {
        id: Id::CallManagementConfig,
        section: Section::Calls,
        label: "Call Controls",
        description: "",
        kind: ControlKind::Choice(CALL_CONTROLS),
    },
    ControlSpec {
        id: Id::MicMode,
        section: Section::Microphone,
        label: "Microphone",
        description: "",
        kind: ControlKind::Choice(MICROPHONE),
    },
    ControlSpec {
        id: Id::EarDetectionConfig,
        section: Section::Connection,
        label: "Automatic Ear Detection",
        description: "Pauses media when you take an AirPod out and resumes it when you put it back.",
        kind: ControlKind::Toggle,
    },
    ControlSpec {
        id: Id::AutomaticConnectionConfig,
        section: Section::Connection,
        label: "Connect to this device automatically",
        description: "When enabled, AirPods will try to connect to this device automatically. Else, they will try to autoconnect only when last connected.",
        kind: ControlKind::Toggle,
    },
    ControlSpec {
        id: Id::DynamicEndOfCharge,
        section: Section::Battery,
        label: "Optimized Charge Limit",
        description: "AirPods learn your daily usage and charge to an optimized limit to preserve battery lifespan. May not affect older AirPods or firmware.",
        kind: ControlKind::Toggle,
    },
    ControlSpec {
        id: Id::DoubleClickInterval,
        section: Section::Accessibility,
        label: "Press Speed",
        description: "Adjust the speed required to press two or three times on your AirPods.",
        kind: ControlKind::Choice(PRESS_SPEED),
    },
    ControlSpec {
        id: Id::ClickHoldInterval,
        section: Section::Accessibility,
        label: "Press and Hold Duration",
        description: "Adjust the duration required to press and hold on your AirPods.",
        kind: ControlKind::Choice(PRESS_SPEED),
    },
    ControlSpec {
        id: Id::OneBudAncMode,
        section: Section::Accessibility,
        label: "Noise Cancellation with Single AirPod",
        description: "Allow AirPods to be put in noise cancellation mode when only one AirPod is in your ear.",
        kind: ControlKind::Toggle,
    },
    ControlSpec {
        id: Id::ChimeVolume,
        section: Section::Accessibility,
        label: "Tone Volume",
        description: "Adjust the tone volume of sound effects played by AirPods.",
        kind: ControlKind::Slider { max: 100, suffix: &[0x50] },
    },
    ControlSpec {
        id: Id::VolumeSwipeMode,
        section: Section::Accessibility,
        label: "Volume Control",
        description: "Adjust the volume by swiping up or down on the stem.",
        kind: ControlKind::Toggle,
    },
    ControlSpec {
        id: Id::VolumeSwipeInterval,
        section: Section::Accessibility,
        label: "Volume Swipe Speed",
        description: "To prevent unintended volume adjustments, select preferred wait time between swipes.",
        kind: ControlKind::Choice(SWIPE_SPEED),
    },
    ControlSpec {
        id: Id::CrownRotationDirection,
        section: Section::Accessibility,
        label: "Digital Crown Direction",
        description: "",
        kind: ControlKind::Choice(CROWN_DIRECTION),
    },
    ControlSpec {
        id: Id::VoiceTrigger,
        section: Section::Advanced,
        label: "\"Hey Siri\"",
        description: "Lets the AirPods listen for “Hey Siri” (used by Apple devices).",
        kind: ControlKind::Toggle,
    },
    ControlSpec {
        id: Id::InCaseToneConfig,
        section: Section::Advanced,
        label: "Case Tones",
        description: "Sounds from charging cases with a speaker.",
        kind: ControlKind::Toggle,
    },
    ControlSpec {
        id: Id::InCaseToneVolume,
        section: Section::Advanced,
        label: "Case Tone Volume",
        description: "",
        kind: ControlKind::Slider { max: 100, suffix: &[] },
    },
];

fn is_shown(id: ControlCommandIdentifiers, model: AirPodsModel, firmware_major: Option<u32>) -> bool {
    let in_ear_listening = model.has(Capability::ListeningMode) && !model.is_headset();
    match id {
        Id::ConversationDetectConfig => model.has(Capability::ConversationAwareness),
        Id::AdaptiveVolumeConfig => model.has(Capability::AdaptiveVolume),
        Id::SleepDetectionConfig => model.has(Capability::SleepDetection),
        Id::AllowOffOption => {
            model.has(Capability::LoudSoundReduction) && firmware_major.is_some_and(|fw| fw >= 8)
        }
        Id::ClickHoldMode | Id::OneBudAncMode => in_ear_listening,
        Id::VolumeSwipeMode | Id::VolumeSwipeInterval => model.has(Capability::SwipeForVolume),
        Id::CrownRotationDirection => model.is_headset(),
        _ => true,
    }
}

/// The catalog rows this model shows, in page order.
pub fn visible_controls(model: AirPodsModel, firmware_major: Option<u32>) -> Vec<&'static ControlSpec> {
    CATALOG
        .iter()
        .filter(|spec| is_shown(spec.id, model, firmware_major))
        .collect()
}

/// Which option the reported value is; `None` for a value the catalog doesn't know.
pub fn selected_choice(options: &[ChoiceOption], value: &[u8]) -> Option<usize> {
    let value = trim_value(value);
    options
        .iter()
        .position(|option| trim_value(option.value) == value)
}

pub fn slider_value(value: &[u8]) -> Option<u8> {
    value.first().copied()
}

pub fn encode_slider(max: u8, suffix: &[u8], v: u8) -> Vec<u8> {
    let mut bytes = vec![v.min(max)];
    bytes.extend_from_slice(suffix);
    bytes
}

/// Per-bud controls carry the right bud first.
pub fn encode_per_bud(left: u8, right: u8) -> Vec<u8> {
    vec![right, left]
}

/// `(left, right)` from a per-bud value.
pub fn per_bud_values(value: &[u8]) -> (Option<u8>, Option<u8>) {
    (value.get(1).copied(), value.first().copied())
}

/// The three-band custom EQ (opcode 0x63) needs firmware 9 or later.
pub fn shows_custom_eq(firmware_major: Option<u32>) -> bool {
    firmware_major.is_some_and(|fw| fw >= 9)
}

/// Stem actions show on models that can forward presses, and wherever some are already set,
/// so they can always be turned back off.
pub fn shows_stem_actions(model: AirPodsModel, actions: &StemActions) -> bool {
    model.has(Capability::StemConfig) || !actions.is_all_native()
}

/// The name to send for a rename, or `None` when there is nothing to do.
pub fn rename_request(current: &str, draft: &str) -> Option<String> {
    let name = draft.trim();
    (!name.is_empty() && name != current.trim()).then(|| name.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bluetooth::aacp::ControlCommandIdentifiers as Id;
    use crate::devices::models::AirPodsModel;
    use crate::prefs::{StemAction, StemActions};

    fn ids(model: AirPodsModel, firmware: Option<u32>) -> Vec<Id> {
        visible_controls(model, firmware).iter().map(|spec| spec.id).collect()
    }

    fn spec(id: Id) -> &'static ControlSpec {
        visible_controls(AirPodsModel::Unknown, Some(99))
            .into_iter()
            .chain(visible_controls(AirPodsModel::AirPodsMaxLightning, Some(99)))
            .find(|spec| spec.id == id)
            .expect("control is in the catalog")
    }

    fn options(id: Id) -> &'static [ChoiceOption] {
        match spec(id).kind {
            ControlKind::Choice(options) | ControlKind::PerBud(options) => options,
            _ => panic!("{:?} is not a choice", id),
        }
    }

    #[test]
    fn pro1_listening_modes_offer_off_without_adaptive() {
        assert_eq!(
            listening_modes(AirPodsModel::AirPodsPro1, None),
            vec![Off, NoiseCancellation, Transparency]
        );
    }

    #[test]
    fn pro2_off_needs_allow_off() {
        assert_eq!(
            listening_modes(AirPodsModel::AirPodsPro2UsbC, None),
            vec![NoiseCancellation, Transparency, Adaptive]
        );
        assert_eq!(
            listening_modes(AirPodsModel::AirPodsPro2UsbC, Some(false)),
            vec![NoiseCancellation, Transparency, Adaptive]
        );
        assert_eq!(
            listening_modes(AirPodsModel::AirPodsPro2UsbC, Some(true)),
            vec![Off, NoiseCancellation, Transparency, Adaptive]
        );
    }

    #[test]
    fn models_without_listening_mode_have_none() {
        assert!(listening_modes(AirPodsModel::AirPods2, Some(true)).is_empty());
    }

    #[test]
    fn listening_mode_bytes() {
        assert_eq!(
            [Off, NoiseCancellation, Transparency, Adaptive].map(ListeningMode::byte),
            [0x01, 0x02, 0x03, 0x04]
        );
        assert_eq!(
            [Off, NoiseCancellation, Transparency, Adaptive].map(ListeningMode::cycle_bit),
            [0x01, 0x02, 0x04, 0x08]
        );
        assert_eq!(ListeningMode::from_byte(0x03), Some(Transparency));
        assert_eq!(ListeningMode::from_byte(0x05), None);
        assert_eq!(Adaptive.cli_name(), "adaptive");
        assert_eq!(NoiseCancellation.label(), "Noise Cancellation");
    }

    #[test]
    fn cycle_toggle_keeps_two_modes() {
        assert_eq!(toggle_cycle_mode(0x06, Transparency), 0x06);
        assert_eq!(toggle_cycle_mode(0x07, Off), 0x06);
        assert_eq!(toggle_cycle_mode(0x06, Off), 0x07);
        assert_eq!(toggle_cycle_mode(0x0E, Adaptive), 0x06);
    }

    #[test]
    fn next_in_cycle_skips_unselected() {
        let available = [Off, NoiseCancellation, Transparency];
        assert_eq!(next_in_cycle(Some(NoiseCancellation), 0x06, &available), Some(Transparency));
        assert_eq!(next_in_cycle(Some(Transparency), 0x06, &available), Some(NoiseCancellation));
        assert_eq!(next_in_cycle(Some(Off), 0x06, &available), Some(NoiseCancellation));
        assert_eq!(next_in_cycle(None, 0x07, &available), Some(Off));
        assert_eq!(next_in_cycle(Some(Transparency), 0x08, &available), None);
    }

    #[test]
    fn default_cycle_per_model() {
        assert_eq!(default_cycle(AirPodsModel::AirPodsPro1), 0x06);
        assert_eq!(default_cycle(AirPodsModel::AirPodsPro2UsbC), 0x0E);
    }

    #[test]
    fn catalog_gates_pro1() {
        let shown = ids(AirPodsModel::AirPodsPro1, Some(6));
        for id in [
            Id::ClickHoldMode,
            Id::CallManagementConfig,
            Id::MicMode,
            Id::EarDetectionConfig,
            Id::AutomaticConnectionConfig,
            Id::DynamicEndOfCharge,
            Id::DoubleClickInterval,
            Id::ClickHoldInterval,
            Id::OneBudAncMode,
            Id::ChimeVolume,
            Id::VoiceTrigger,
            Id::InCaseToneConfig,
            Id::InCaseToneVolume,
        ] {
            assert!(shown.contains(&id), "{:?} missing for Pro 1", id);
        }
        for id in [
            Id::ConversationDetectConfig,
            Id::AdaptiveVolumeConfig,
            Id::AllowOffOption,
            Id::SleepDetectionConfig,
            Id::VolumeSwipeMode,
            Id::VolumeSwipeInterval,
            Id::CrownRotationDirection,
        ] {
            assert!(!shown.contains(&id), "{:?} must be hidden on Pro 1", id);
        }
    }

    #[test]
    fn catalog_gates_pro2_by_firmware() {
        let fw8 = ids(AirPodsModel::AirPodsPro2UsbC, Some(8));
        for id in [
            Id::AllowOffOption,
            Id::ConversationDetectConfig,
            Id::VolumeSwipeMode,
            Id::VolumeSwipeInterval,
            Id::SleepDetectionConfig,
            Id::AdaptiveVolumeConfig,
        ] {
            assert!(fw8.contains(&id), "{:?} missing for Pro 2 on firmware 8", id);
        }
        assert!(!ids(AirPodsModel::AirPodsPro2UsbC, Some(7)).contains(&Id::AllowOffOption));
        assert!(!ids(AirPodsModel::AirPodsPro2UsbC, None).contains(&Id::AllowOffOption));
    }

    #[test]
    fn catalog_gates_headsets() {
        let shown = ids(AirPodsModel::AirPodsMaxLightning, None);
        assert!(shown.contains(&Id::CrownRotationDirection));
        assert!(!shown.contains(&Id::ClickHoldMode));
        assert!(!shown.contains(&Id::OneBudAncMode));
    }

    #[test]
    fn catalog_ids_are_unique() {
        let all: Vec<Id> = CATALOG.iter().map(|spec| spec.id).collect();
        let unique: std::collections::HashSet<Id> = all.iter().copied().collect();
        assert_eq!(unique.len(), all.len());
    }

    #[test]
    fn choice_matches_trimmed_value() {
        let calls = options(Id::CallManagementConfig);
        assert_eq!(selected_choice(calls, &[0x00, 0x03]), Some(0));
        assert_eq!(selected_choice(calls, &[0x00, 0x02]), Some(1));
        assert_eq!(selected_choice(calls, &[0x00, 0x01]), None);
        let mic = options(Id::MicMode);
        assert_eq!(selected_choice(mic, &[0x00]), Some(0));
        assert_eq!(selected_choice(mic, &[0x02]), Some(2));
        assert_eq!(selected_choice(mic, &[0x07]), None);
        assert_eq!(selected_choice(options(Id::DoubleClickInterval), &[0x00]), Some(0));
    }

    #[test]
    fn chime_volume_encodes_with_suffix() {
        let ControlKind::Slider { max, suffix } = spec(Id::ChimeVolume).kind else {
            panic!("tone volume is a slider");
        };
        assert_eq!(encode_slider(max, suffix, 120), vec![100, 0x50]);
        assert_eq!(encode_slider(max, suffix, 30), vec![30, 0x50]);
        assert_eq!(slider_value(&[0x50, 0x50]), Some(80));
        assert_eq!(slider_value(&[]), None);
    }

    #[test]
    fn per_bud_layout_is_right_then_left() {
        assert_eq!(encode_per_bud(0x05, 0x01), vec![0x01, 0x05]);
        assert_eq!(per_bud_values(&[0x01, 0x05]), (Some(0x05), Some(0x01)));
        assert_eq!(per_bud_values(&[0x01]), (None, Some(0x01)));
    }

    #[test]
    fn toggle_codec() {
        assert_eq!(encode_toggle(true), vec![0x01]);
        assert_eq!(encode_toggle(false), vec![0x02]);
        assert_eq!(decode_toggle(&[0x01]), Some(true));
        assert_eq!(decode_toggle(&[0x02]), Some(false));
        assert_eq!(decode_toggle(&[0x00]), None);
        assert_eq!(decode_toggle(&[]), None);
    }

    #[test]
    fn trim_matches_receive_path() {
        assert_eq!(trim_value(&[3, 0, 0, 0]), vec![3]);
        assert_eq!(trim_value(&[0, 3, 0, 0]), vec![0, 3]);
        assert_eq!(trim_value(&[0, 0, 0, 0]), vec![0]);
        assert_eq!(trim_value(&[]), vec![0]);
    }

    #[test]
    fn firmware_major_parses_first_digit() {
        assert_eq!(firmware_major("6619472"), Some(6));
        assert_eq!(firmware_major("8A356"), Some(8));
        assert_eq!(firmware_major(""), None);
        assert_eq!(firmware_major("A1"), None);
    }

    #[test]
    fn custom_eq_needs_firmware_9() {
        assert!(shows_custom_eq(Some(9)));
        assert!(!shows_custom_eq(Some(8)));
        assert!(!shows_custom_eq(None));
    }

    #[test]
    fn stem_actions_shown_when_supported_or_configured() {
        let default = StemActions::default();
        assert!(!shows_stem_actions(AirPodsModel::AirPodsPro1, &default));
        assert!(shows_stem_actions(AirPodsModel::AirPodsPro2UsbC, &default));
        let configured = StemActions {
            left_double: StemAction::NextTrack,
            ..StemActions::default()
        };
        assert!(shows_stem_actions(AirPodsModel::AirPodsPro1, &configured));
    }

    #[test]
    fn rename_request_rules() {
        assert_eq!(rename_request("A", "A"), None);
        assert_eq!(rename_request("A", "  "), None);
        assert_eq!(rename_request("A", " B "), Some("B".to_string()));
        assert_eq!(rename_request("A", " A "), None);
    }
}
