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
    pub noise_control_mode: AirPodsNoiseControlMode,
    pub noise_control_state: combo_box::State<AirPodsNoiseControlMode>,
    pub conversation_awareness_enabled: bool,
    pub personalized_volume_enabled: bool,
    pub allow_off_mode: bool,
    pub battery: Vec<BatteryInfo>,
    // Spatial audio / head tracking
    pub head_tracking_enabled: bool,
    pub head_gestures_enabled: bool,
    /// Latest raw head-tracking sample: (orientation1, orientation2, orientation3,
    /// horizontal_accel, vertical_accel).
    pub head_tracking_sample: Option<(i16, i16, i16, i16, i16)>,
    /// Calibration baseline (orientation1, orientation2, orientation3) for re-centering.
    pub head_tracking_neutral: Option<(i16, i16, i16)>,
}

impl AirPodsState {
    /// Calibrated (pitch, yaw, roll) in degrees from the latest sample, relative
    /// to the neutral baseline. Returns None if no sample yet.
    pub fn head_orientation_degrees(&self) -> Option<(f32, f32, f32)> {
        let (o1, o2, o3, _, _) = self.head_tracking_sample?;
        let (n1, n2, n3) = self.head_tracking_neutral.unwrap_or((0, 0, 0));
        let o1n = (o1 - n1) as f32;
        let o2n = (o2 - n2) as f32;
        let o3n = (o3 - n3) as f32;
        // Matches the head-tracking reference: orientation pair maps to pitch/yaw
        // over a ~+-32000 range scaled to +-180 degrees; orientation1 ~ roll/twist.
        let pitch = (o2n + o3n) / 2.0 / 32000.0 * 180.0;
        let yaw = (o2n - o3n) / 2.0 / 32000.0 * 180.0;
        let roll = o1n / 32000.0 * 180.0;
        Some((pitch, yaw, roll))
    }
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
