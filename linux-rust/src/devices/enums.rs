use crate::bluetooth::aacp::{
    AACPEvent, AACPManagerState, BatteryInfo, ControlCommandIdentifiers,
};
use crate::bluetooth::aacp_parse::{Accommodation, CustomEq};
use crate::devices::models::AirPodsModel;
use crate::prefs::DevicePrefs;
use std::collections::HashMap;
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

/// What the AirPods page shows: the AirPods' reported settings plus this app's own preferences.
#[derive(Clone, Debug)]
pub struct AirPodsState {
    pub device_name: String,
    /// The name field's text, sent only on submit.
    pub name_draft: String,
    pub battery: Vec<BatteryInfo>,
    /// Every control value the AirPods reported (or the user just set), keyed by identifier.
    pub controls: HashMap<ControlCommandIdentifiers, Vec<u8>>,
    pub model: Option<AirPodsModel>,
    pub firmware_major: Option<u32>,
    pub custom_eq: Option<CustomEq>,
    pub accommodation: Option<Accommodation>,
    pub prefs: DevicePrefs,
    pub show_advanced: bool,
}

impl AirPodsState {
    pub fn from_aacp(state: &AACPManagerState, name: String, prefs: DevicePrefs) -> Self {
        AirPodsState {
            device_name: name.clone(),
            name_draft: name,
            battery: state.battery_info.clone(),
            controls: state
                .control_command_status_list
                .iter()
                .map(|status| (status.identifier, status.value.clone()))
                .collect(),
            model: state.model,
            firmware_major: state.firmware_major,
            custom_eq: state.custom_eq,
            accommodation: state.accommodation,
            prefs,
            show_advanced: false,
        }
    }

    pub fn apply_event(&mut self, event: &AACPEvent) {
        match event {
            AACPEvent::ControlCommand(status) => {
                self.controls.insert(status.identifier, status.value.clone());
            }
            AACPEvent::BatteryInfo(battery) => self.battery = battery.clone(),
            AACPEvent::DeviceInfo {
                model,
                firmware_major,
            } => {
                self.model = Some(*model);
                self.firmware_major = *firmware_major;
            }
            AACPEvent::CustomEq(eq) => self.custom_eq = Some(*eq),
            AACPEvent::HeadphoneAccommodation(accommodation) => {
                self.accommodation = Some(*accommodation)
            }
            _ => {}
        }
    }

    /// The model, or the permissive `Unknown` until the Information packet arrives.
    pub fn model_or_unknown(&self) -> AirPodsModel {
        self.model.unwrap_or(AirPodsModel::Unknown)
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
    use crate::bluetooth::aacp::{
        AACPEvent, AACPManagerState, BatteryComponent, BatteryStatus, ControlCommandIdentifiers as Id,
        ControlCommandStatus,
    };
    use crate::bluetooth::aacp_parse::CustomEq;
    use crate::devices::models::AirPodsModel;
    use crate::prefs::DevicePrefs;

    fn status(identifier: Id, value: &[u8]) -> ControlCommandStatus {
        ControlCommandStatus {
            identifier,
            value: value.to_vec(),
        }
    }

    #[test]
    fn state_from_aacp_and_events() {
        let mut aacp = AACPManagerState::new();
        aacp.control_command_status_list = vec![
            status(Id::ListeningMode, &[0x02]),
            status(Id::ChimeVolume, &[0x50, 0x50]),
        ];
        let mut state =
            AirPodsState::from_aacp(&aacp, "Test AirPods Pro".to_string(), DevicePrefs::default());
        assert_eq!(state.controls.get(&Id::ListeningMode), Some(&vec![0x02]));
        assert_eq!(state.controls.get(&Id::ChimeVolume), Some(&vec![0x50, 0x50]));
        assert_eq!(state.device_name, "Test AirPods Pro");
        assert_eq!(state.name_draft, "Test AirPods Pro");
        assert_eq!(state.model, None);
        assert_eq!(state.model_or_unknown(), AirPodsModel::Unknown);

        state.apply_event(&AACPEvent::ControlCommand(status(Id::ListeningMode, &[0x03])));
        assert_eq!(state.controls.get(&Id::ListeningMode), Some(&vec![0x03]));

        state.apply_event(&AACPEvent::DeviceInfo {
            model: AirPodsModel::AirPodsPro1,
            firmware_major: Some(6),
        });
        assert_eq!(state.model, Some(AirPodsModel::AirPodsPro1));
        assert_eq!(state.firmware_major, Some(6));

        let eq = CustomEq { enabled: true, low: 40, mid: 50, high: 60 };
        state.apply_event(&AACPEvent::CustomEq(eq));
        assert_eq!(state.custom_eq, Some(eq));

        let battery = vec![BatteryInfo {
            component: BatteryComponent::Left,
            level: 94,
            status: BatteryStatus::NotCharging,
        }];
        state.apply_event(&AACPEvent::BatteryInfo(battery.clone()));
        assert_eq!(state.battery, battery);
    }
}
