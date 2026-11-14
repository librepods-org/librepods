use crate::bluetooth::aacp::BatteryInfo;
use crate::devices::airpods::AirPodsInformation;
use crate::devices::models::{AirPodsModel, Capability, supports};
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

impl DeviceData {
    pub fn airpods_model(&self) -> Option<&'static AirPodsModel> {
        match &self.information {
            Some(DeviceInformation::AirPods(info)) => {
                AirPodsModel::from_model_number(&info.model_number)
            }
            _ => None,
        }
    }
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
    pub noise_control_mode: AirPodsNoiseControlMode,
    pub noise_control_state: combo_box::State<AirPodsNoiseControlMode>,
    pub conversation_awareness_enabled: Option<bool>,
    pub personalized_volume_enabled: Option<bool>,
    pub allow_off_mode: Option<bool>,
    pub battery: Vec<BatteryInfo>,
    pub auto_connect: bool,
    pub model: Option<&'static AirPodsModel>,
    // listening modes the long press cycles through, None if unknown
    pub press_hold_modes: Option<u8>,
}

#[derive(Clone, Debug)]
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
    // modes to offer in the UI, unknown values (None) keep the mode visible
    pub fn available(allow_off: Option<bool>, model: Option<&AirPodsModel>) -> Vec<Self> {
        let mut modes = vec![];
        if allow_off != Some(false) {
            modes.push(AirPodsNoiseControlMode::Off);
        }
        modes.push(AirPodsNoiseControlMode::Transparency);
        modes.push(AirPodsNoiseControlMode::NoiseCancellation);
        if supports(model, Capability::AdaptiveAudio) != Some(false) {
            modes.push(AirPodsNoiseControlMode::Adaptive);
        }
        modes
    }
    // bit of this mode in the ListeningModeConfigs control command
    pub fn config_bit(&self) -> u8 {
        match self {
            AirPodsNoiseControlMode::Off => 0x01,
            AirPodsNoiseControlMode::NoiseCancellation => 0x02,
            AirPodsNoiseControlMode::Transparency => 0x04,
            AirPodsNoiseControlMode::Adaptive => 0x08,
        }
    }
    pub fn toggle_press_hold(mask: u8, mode: &Self, enabled: bool) -> u8 {
        if enabled {
            mask | mode.config_bit()
        } else {
            mask & !mode.config_bit()
        }
    }
    pub fn from_byte(value: &u8) -> Self {
        match value {
            0x01 => AirPodsNoiseControlMode::Off,
            0x02 => AirPodsNoiseControlMode::NoiseCancellation,
            0x03 => AirPodsNoiseControlMode::Transparency,
            0x04 => AirPodsNoiseControlMode::Adaptive,
            _ => AirPodsNoiseControlMode::Off,
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn press_hold_mask() {
        let mask = AirPodsNoiseControlMode::NoiseCancellation.config_bit()
            | AirPodsNoiseControlMode::Transparency.config_bit();
        assert_eq!(mask, 0x06);
        let with_off =
            AirPodsNoiseControlMode::toggle_press_hold(mask, &AirPodsNoiseControlMode::Off, true);
        assert_eq!(with_off, 0x07);
        let without_anc = AirPodsNoiseControlMode::toggle_press_hold(
            with_off,
            &AirPodsNoiseControlMode::NoiseCancellation,
            false,
        );
        assert_eq!(without_anc, 0x05);
        assert_eq!(
            AirPodsNoiseControlMode::toggle_press_hold(0, &AirPodsNoiseControlMode::Adaptive, true),
            0x08
        );
    }
}
