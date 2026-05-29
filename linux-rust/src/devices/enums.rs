use crate::bluetooth::aacp::BatteryInfo;
use crate::devices::airpods::AirPodsInformation;
use crate::devices::nothing::NothingInformation;
use iced::widget::combo_box;
use serde::{Deserialize, Serialize};
use std::fmt::Display;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum DeviceType {
    AirPods,
    Nothing,
}

impl Display for DeviceType {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DeviceType::AirPods => write!(f, "AirPods"),
            DeviceType::Nothing => write!(f, "Nothing"),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", content = "data")]
pub enum DeviceInformation {
    AirPods(AirPodsInformation),
    Nothing(NothingInformation),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceData {
    pub name: String,
    pub type_: DeviceType,
    pub information: Option<DeviceInformation>,
}

#[derive(Clone, Debug)]
pub enum DeviceState {
    AirPods(AirPodsState),
    Nothing(NothingState),
}

impl Display for DeviceState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DeviceState::AirPods(_) => write!(f, "AirPods State"),
            DeviceState::Nothing(_) => write!(f, "Nothing State"),
        }
    }
}

#[derive(Clone, Debug)]
pub struct AirPodsState {
    pub device_name: String,
    pub model: AirPodsModel,
    pub noise_control_mode: AirPodsNoiseControlMode,
    pub conversation_awareness_enabled: bool,
    pub personalized_volume_enabled: bool,
    pub allow_off_mode: bool,
    pub battery: Vec<BatteryInfo>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum AirPodsNoiseControlMode {
    Off,
    NoiseCancellation,
    Transparency,
    Adaptive,
}

impl Display for AirPodsNoiseControlMode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            AirPodsNoiseControlMode::Off => write!(f, "Off"),
            AirPodsNoiseControlMode::NoiseCancellation => write!(f, "Noise Cancellation"),
            AirPodsNoiseControlMode::Transparency => write!(f, "Transparency"),
            AirPodsNoiseControlMode::Adaptive => write!(f, "Adaptive"),
        }
    }
}

impl AirPodsNoiseControlMode {
    pub fn from_byte(value: &u8) -> Option<Self> {
        match value {
            0x01 => Some(AirPodsNoiseControlMode::Off),
            0x02 => Some(AirPodsNoiseControlMode::NoiseCancellation),
            0x03 => Some(AirPodsNoiseControlMode::Transparency),
            0x04 => Some(AirPodsNoiseControlMode::Adaptive),
            _ => None,
        }
    }
    pub fn to_byte(&self) -> u8 {
        match self {
            AirPodsNoiseControlMode::Off => 0x01,
            AirPodsNoiseControlMode::NoiseCancellation => 0x02,
            AirPodsNoiseControlMode::Transparency => 0x03,
            AirPodsNoiseControlMode::Adaptive => 0x04,
        }
    }
}

/// AirPods hardware model, used for selecting correct device artwork.
/// Mapping ported from linux/enums.h.
#[derive(Clone, Debug, PartialEq)]
pub enum AirPodsModel {
    Unknown,
    AirPods1,
    AirPods2,
    AirPods3,
    AirPods4,
    AirPods4ANC,
    AirPodsPro,
    AirPodsPro2Lightning,
    AirPodsPro2USBC,
    AirPodsMaxLightning,
    AirPodsMaxUSBC,
}

impl AirPodsModel {
    /// Parse a raw model number string into an AirPodsModel.
    /// Source: https://support.apple.com/en-us/109525
    pub fn from_model_number(model_number: &str) -> Self {
        match model_number {
            "A1523" | "A1722" => AirPodsModel::AirPods1,
            "A2032" | "A2031" => AirPodsModel::AirPods2,
            "A2564" | "A2565" => AirPodsModel::AirPods3,
            "A3053" | "A3050" | "A3054" => AirPodsModel::AirPods4,
            "A3055" | "A3056" | "A3057" => AirPodsModel::AirPods4ANC,
            "A2083" | "A2084" => AirPodsModel::AirPodsPro,
            "A2698" | "A2699" | "A2931" => AirPodsModel::AirPodsPro2Lightning,
            "A3047" | "A3048" | "A3049" => AirPodsModel::AirPodsPro2USBC,
            "A2096" => AirPodsModel::AirPodsMaxLightning,
            "A3184" => AirPodsModel::AirPodsMaxUSBC,
            _ => AirPodsModel::Unknown,
        }
    }

    /// Returns (bud_image_filename, case_image_filename) for this model.
    /// Images are in `assets/devices/`.
    pub fn device_images(&self) -> (&'static str, &'static str) {
        match self {
            AirPodsModel::AirPods1 | AirPodsModel::AirPods2 => ("pod.png", "pod_case.png"),
            AirPodsModel::AirPods3 => ("pod3.png", "pod3_case.png"),
            AirPodsModel::AirPods4 | AirPodsModel::AirPods4ANC => ("pod3.png", "pod4_case.png"),
            AirPodsModel::AirPodsPro
            | AirPodsModel::AirPodsPro2Lightning
            | AirPodsModel::AirPodsPro2USBC => ("podpro.png", "podpro_case.png"),
            AirPodsModel::AirPodsMaxLightning | AirPodsModel::AirPodsMaxUSBC => {
                ("podmax.png", "podmax.png") // Max has no separate case image
            }
            AirPodsModel::Unknown => ("pod.png", "pod_case.png"),
        }
    }

    /// Whether this model is an over-ear headphone (AirPods Max)
    /// vs in-ear earbuds. Affects battery layout (single vs L/R/Case).
    pub fn is_over_ear(&self) -> bool {
        matches!(
            self,
            AirPodsModel::AirPodsMaxLightning | AirPodsModel::AirPodsMaxUSBC
        )
    }
}

#[derive(Clone, Debug)]
pub struct NothingState {
    pub anc_mode: NothingAncMode,
    pub anc_mode_state: combo_box::State<NothingAncMode>,
}

#[derive(Clone, Debug)]
pub enum NothingAncMode {
    Off,
    LowNoiseCancellation,
    MidNoiseCancellation,
    HighNoiseCancellation,
    AdaptiveNoiseCancellation,
    Transparency,
}

impl Display for NothingAncMode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            NothingAncMode::Off => write!(f, "Off"),
            NothingAncMode::LowNoiseCancellation => write!(f, "Low Noise Cancellation"),
            NothingAncMode::MidNoiseCancellation => write!(f, "Mid Noise Cancellation"),
            NothingAncMode::HighNoiseCancellation => write!(f, "High Noise Cancellation"),
            NothingAncMode::AdaptiveNoiseCancellation => write!(f, "Adaptive Noise Cancellation"),
            NothingAncMode::Transparency => write!(f, "Transparency"),
        }
    }
}
impl NothingAncMode {
    pub fn from_byte(value: u8) -> Self {
        match value {
            0x03 => NothingAncMode::LowNoiseCancellation,
            0x02 => NothingAncMode::MidNoiseCancellation,
            0x01 => NothingAncMode::HighNoiseCancellation,
            0x04 => NothingAncMode::AdaptiveNoiseCancellation,
            0x07 => NothingAncMode::Transparency,
            0x05 => NothingAncMode::Off,
            _ => NothingAncMode::Off,
        }
    }
    pub fn to_byte(&self) -> u8 {
        match self {
            NothingAncMode::LowNoiseCancellation => 0x03,
            NothingAncMode::MidNoiseCancellation => 0x02,
            NothingAncMode::HighNoiseCancellation => 0x01,
            NothingAncMode::AdaptiveNoiseCancellation => 0x04,
            NothingAncMode::Transparency => 0x07,
            NothingAncMode::Off => 0x05,
        }
    }
}
