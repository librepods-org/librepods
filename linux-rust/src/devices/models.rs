//! Which AirPods model we are talking to and what it supports. Mirrors the Android rewrite's
//! `core/devices/AirPods.kt` table; unknown models get every capability so nothing that works
//! today is hidden.

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Capability {
    ListeningMode,
    ConversationAwareness,
    StemConfig,
    HeadGestures,
    LoudSoundReduction,
    Ppe,
    SleepDetection,
    HearingAid,
    AdaptiveAudio,
    AdaptiveVolume,
    SwipeForVolume,
    Hrm,
}

use Capability::*;

const NONE: &[Capability] = &[];
const AIRPODS_3: &[Capability] = &[HeadGestures];
const AIRPODS_4: &[Capability] = &[HeadGestures, SleepDetection, AdaptiveVolume];
const AIRPODS_4_ANC: &[Capability] = &[
    ListeningMode,
    ConversationAwareness,
    HeadGestures,
    AdaptiveAudio,
    SleepDetection,
    AdaptiveVolume,
    StemConfig,
];
const AIRPODS_5_WCC: &[Capability] = &[
    ListeningMode,
    ConversationAwareness,
    HeadGestures,
    AdaptiveAudio,
    SleepDetection,
    AdaptiveVolume,
    StemConfig,
    SwipeForVolume,
];
const PRO_1: &[Capability] = &[ListeningMode];
const PRO_2: &[Capability] = &[
    ListeningMode,
    ConversationAwareness,
    StemConfig,
    LoudSoundReduction,
    SleepDetection,
    HearingAid,
    AdaptiveAudio,
    AdaptiveVolume,
    SwipeForVolume,
    HeadGestures,
];
const PRO_3: &[Capability] = &[
    ListeningMode,
    ConversationAwareness,
    StemConfig,
    LoudSoundReduction,
    SleepDetection,
    HearingAid,
    AdaptiveAudio,
    AdaptiveVolume,
    SwipeForVolume,
    HeadGestures,
    Ppe,
    Hrm,
];
const MAX: &[Capability] = &[ListeningMode];
const MAX_2: &[Capability] = &[
    ListeningMode,
    ConversationAwareness,
    LoudSoundReduction,
    AdaptiveAudio,
    AdaptiveVolume,
];
const ALL: &[Capability] = &[
    ListeningMode,
    ConversationAwareness,
    StemConfig,
    HeadGestures,
    LoudSoundReduction,
    Ppe,
    SleepDetection,
    HearingAid,
    AdaptiveAudio,
    AdaptiveVolume,
    SwipeForVolume,
    Hrm,
];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AirPodsModel {
    AirPods1,
    AirPods2,
    AirPods3,
    AirPods4,
    AirPods4Anc,
    AirPods5,
    AirPods5Wcc,
    AirPodsPro1,
    AirPodsPro2Lightning,
    AirPodsPro2UsbC,
    AirPodsPro3,
    AirPodsMaxLightning,
    AirPodsMaxUsbC,
    AirPodsMax2,
    Unknown,
}

impl AirPodsModel {
    pub fn from_model_number(model_number: &str) -> Self {
        match model_number.trim().to_ascii_uppercase().as_str() {
            "A1523" | "A1722" => Self::AirPods1,
            "A2032" | "A2031" => Self::AirPods2,
            "A2565" | "A2564" => Self::AirPods3,
            "A3053" | "A3050" | "A3054" => Self::AirPods4,
            "A3056" | "A3055" | "A3057" => Self::AirPods4Anc,
            "A3531" | "A3532" | "A3533" => Self::AirPods5,
            "A3439" | "A3440" | "A3441" => Self::AirPods5Wcc,
            "A2084" | "A2083" => Self::AirPodsPro1,
            "A2931" | "A2699" | "A2698" => Self::AirPodsPro2Lightning,
            "A3047" | "A3048" | "A3049" => Self::AirPodsPro2UsbC,
            "A3063" | "A3064" | "A3065" => Self::AirPodsPro3,
            "A2096" => Self::AirPodsMaxLightning,
            "A3184" => Self::AirPodsMaxUsbC,
            "A3454" => Self::AirPodsMax2,
            _ => Self::Unknown,
        }
    }

    pub fn from_ble_model_id(id: u16) -> Self {
        match id {
            0x0220 => Self::AirPods1,
            0x0F20 => Self::AirPods2,
            0x1320 => Self::AirPods3,
            0x1920 => Self::AirPods4,
            0x1B20 => Self::AirPods4Anc,
            0x0E20 => Self::AirPodsPro1,
            0x1420 => Self::AirPodsPro2Lightning,
            0x2420 => Self::AirPodsPro2UsbC,
            0x0A20 | 0x1F20 => Self::AirPodsMaxLightning,
            _ => Self::Unknown,
        }
    }

    pub fn display_name(&self) -> &'static str {
        match self {
            Self::AirPods1 => "AirPods (1st generation)",
            Self::AirPods2 => "AirPods (2nd generation)",
            Self::AirPods3 => "AirPods (3rd generation)",
            Self::AirPods4 => "AirPods 4",
            Self::AirPods4Anc => "AirPods 4 (ANC)",
            Self::AirPods5 => "AirPods 5",
            Self::AirPods5Wcc => "AirPods 5 (WCC)",
            Self::AirPodsPro1 => "AirPods Pro (1st generation)",
            Self::AirPodsPro2Lightning => "AirPods Pro 2 (Lightning)",
            Self::AirPodsPro2UsbC => "AirPods Pro 2 (USB-C)",
            Self::AirPodsPro3 => "AirPods Pro 3",
            Self::AirPodsMaxLightning => "AirPods Max",
            Self::AirPodsMaxUsbC => "AirPods Max (USB-C)",
            Self::AirPodsMax2 => "AirPods Max 2",
            Self::Unknown => "Unknown AirPods",
        }
    }

    pub fn capabilities(&self) -> &'static [Capability] {
        match self {
            Self::AirPods1 | Self::AirPods2 => NONE,
            Self::AirPods3 => AIRPODS_3,
            Self::AirPods4 => AIRPODS_4,
            Self::AirPods4Anc | Self::AirPods5 => AIRPODS_4_ANC,
            Self::AirPods5Wcc => AIRPODS_5_WCC,
            Self::AirPodsPro1 => PRO_1,
            Self::AirPodsPro2Lightning | Self::AirPodsPro2UsbC => PRO_2,
            Self::AirPodsPro3 => PRO_3,
            Self::AirPodsMaxLightning | Self::AirPodsMaxUsbC => MAX,
            Self::AirPodsMax2 => MAX_2,
            Self::Unknown => ALL,
        }
    }

    pub fn has(&self, capability: Capability) -> bool {
        self.capabilities().contains(&capability)
    }

    /// Over-ear models report a single "headphone" battery instead of left/right/case.
    pub fn is_headset(&self) -> bool {
        matches!(
            self,
            Self::AirPodsMaxLightning | Self::AirPodsMaxUsbC | Self::AirPodsMax2
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pro1_from_a2084_supports_only_listening_mode() {
        let model = AirPodsModel::from_model_number("A2084");
        assert_eq!(model, AirPodsModel::AirPodsPro1);
        assert_eq!(model.capabilities(), &[ListeningMode]);
        assert!(!model.has(AdaptiveAudio));
    }

    #[test]
    fn pro2_usbc_has_hearing_aid_and_adaptive() {
        let model = AirPodsModel::from_model_number("A3048");
        assert_eq!(model, AirPodsModel::AirPodsPro2UsbC);
        assert!(model.has(HearingAid) && model.has(AdaptiveAudio));
    }

    #[test]
    fn pro3_adds_ppe_and_hrm() {
        let model = AirPodsModel::from_model_number("A3064");
        assert_eq!(model, AirPodsModel::AirPodsPro3);
        assert!(model.has(Ppe) && model.has(Hrm));
    }

    #[test]
    fn max_is_headset() {
        let model = AirPodsModel::from_model_number("A2096");
        assert_eq!(model, AirPodsModel::AirPodsMaxLightning);
        assert!(model.is_headset());
    }

    #[test]
    fn model_number_is_trimmed_and_case_insensitive() {
        assert_eq!(
            AirPodsModel::from_model_number(" a2084 "),
            AirPodsModel::AirPodsPro1
        );
    }

    #[test]
    fn unknown_model_falls_back_permissively() {
        let model = AirPodsModel::from_model_number("A9999");
        assert_eq!(model, AirPodsModel::Unknown);
        assert_eq!(model.capabilities().len(), 12);
    }

    #[test]
    fn ble_model_ids_map() {
        assert_eq!(AirPodsModel::from_ble_model_id(0x0E20), AirPodsModel::AirPodsPro1);
        assert_eq!(AirPodsModel::from_ble_model_id(0x2420), AirPodsModel::AirPodsPro2UsbC);
        assert_eq!(AirPodsModel::from_ble_model_id(0xBEEF), AirPodsModel::Unknown);
    }
}
