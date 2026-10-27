//! What the app sends or does on the user's behalf: stem-press actions, cycling the listening
//! mode, and the stored AirPods configuration re-applied on every connect.

use crate::bluetooth::aacp::{
    AACPManager, ControlCommandIdentifiers, ControlCommandStatus, StemPressBudType, StemPressType,
};
use crate::devices::models::AirPodsModel;
use crate::devices::settings::{
    ListeningMode, decode_toggle, default_cycle, listening_modes, next_in_cycle,
};
use crate::prefs::{Bud, DevicePrefs, PressType, StemAction, StemActions};
use log::{debug, error, info, warn};
use std::process::{Command, Stdio};

const PRESS_BITS: [(PressType, u8); 4] = [
    (PressType::Single, 0x01),
    (PressType::Double, 0x02),
    (PressType::Triple, 0x04),
    (PressType::Long, 0x08),
];

/// The 0x39 bitmask: the press types the AirPods must forward to us because at least one bud
/// has its own action for them.
pub fn stem_forwarding_mask(actions: &StemActions) -> u8 {
    PRESS_BITS
        .iter()
        .filter(|(press, _)| {
            [Bud::Left, Bud::Right]
                .iter()
                .any(|bud| *actions.get(*bud, *press) != StemAction::Native)
        })
        .fold(0, |mask, (_, bit)| mask | bit)
}

/// The user's own choices that the AirPods may have forgotten, to send on every connect.
/// Nothing the user didn't choose is ever sent.
pub fn reapply_on_connect(prefs: &DevicePrefs) -> Vec<(ControlCommandIdentifiers, Vec<u8>)> {
    let mut commands = Vec::new();
    if let Some(cycle) = prefs.listening_mode_cycle {
        if (cycle & 0x0F).count_ones() >= 2 {
            commands.push((ControlCommandIdentifiers::ListeningModeConfigs, vec![cycle]));
        } else {
            warn!("Ignoring stored listening-mode cycle {:#04x}: it needs two modes", cycle);
        }
    }
    let mask = stem_forwarding_mask(&prefs.stem_actions);
    if mask != 0 {
        commands.push((ControlCommandIdentifiers::StemConfig, vec![mask]));
    }
    commands
}

/// What to do for a forwarded press. A bud left on `Native` gets what the AirPods would have
/// done themselves; forwarding is per press type, so its press arrives here too.
pub fn effective_stem_action(actions: &StemActions, bud: Bud, press: PressType) -> StemAction {
    match actions.get(bud, press) {
        StemAction::Native => match press {
            PressType::Single => StemAction::PlayPause,
            PressType::Double => StemAction::NextTrack,
            PressType::Triple => StemAction::PreviousTrack,
            PressType::Long => StemAction::CycleListeningMode,
        },
        action => action.clone(),
    }
}

pub fn press_from_aacp(press: StemPressType) -> PressType {
    match press {
        StemPressType::SinglePress => PressType::Single,
        StemPressType::DoublePress => PressType::Double,
        StemPressType::TriplePress => PressType::Triple,
        StemPressType::LongPress => PressType::Long,
    }
}

pub fn bud_from_aacp(bud: StemPressBudType) -> Bud {
    match bud {
        StemPressBudType::Left => Bud::Left,
        StemPressBudType::Right => Bud::Right,
    }
}

fn reported(controls: &[ControlCommandStatus], id: ControlCommandIdentifiers) -> Option<&[u8]> {
    controls
        .iter()
        .find(|status| status.identifier == id)
        .map(|status| status.value.as_slice())
}

/// The mode a press-and-hold cycle moves to next. The cycle is what the AirPods reported, else
/// the user's stored choice, else Apple's default for the model.
pub fn cycle_target(
    model: AirPodsModel,
    controls: &[ControlCommandStatus],
    stored_cycle: Option<u8>,
) -> Option<ListeningMode> {
    let current = reported(controls, ControlCommandIdentifiers::ListeningMode)
        .and_then(|value| value.first().copied())
        .and_then(ListeningMode::from_byte);
    let allow_off = reported(controls, ControlCommandIdentifiers::AllowOffOption).and_then(decode_toggle);
    let cycle = reported(controls, ControlCommandIdentifiers::ListeningModeConfigs)
        .and_then(|value| value.first().copied())
        .filter(|cycle| cycle & 0x0F != 0)
        .or(stored_cycle)
        .unwrap_or_else(|| default_cycle(model));
    next_in_cycle(current, cycle, &listening_modes(model, allow_off))
}

/// Moves the AirPods to the next listening mode in their cycle. Returns the mode sent.
pub async fn cycle_listening_mode(aacp: &AACPManager, prefs: &DevicePrefs) -> Option<ListeningMode> {
    let (model, controls) = {
        let state = aacp.state.lock().await;
        (
            state.model.unwrap_or(AirPodsModel::Unknown),
            state.control_command_status_list.clone(),
        )
    };
    let Some(mode) = cycle_target(model, &controls, prefs.listening_mode_cycle) else {
        debug!("No listening mode to cycle to");
        return None;
    };
    info!("Cycling listening mode to {}", mode.label());
    if let Err(e) = aacp
        .send_control_command(ControlCommandIdentifiers::ListeningMode, &[mode.byte()])
        .await
    {
        error!("Failed to set listening mode: {}", e);
        return None;
    }
    Some(mode)
}

/// Starts a user's stem-action command and returns at once. A detached thread reaps it.
pub fn run_command(command: &str) {
    let command = command.trim();
    if command.is_empty() {
        warn!("This stem action has no command to run");
        return;
    }
    let spawned = Command::new("sh")
        .arg("-c")
        .arg(command)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn();
    match spawned {
        Ok(mut child) => {
            info!("Running stem action command: {}", command);
            let command = command.to_string();
            std::thread::spawn(move || match child.wait() {
                Ok(status) if !status.success() => {
                    warn!("Stem action command `{}` exited with {}", command, status)
                }
                Ok(_) => {}
                Err(e) => warn!("Could not wait for stem action command `{}`: {}", command, e),
            });
        }
        Err(e) => warn!("Could not run stem action command `{}`: {}", command, e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bluetooth::aacp::{ControlCommandIdentifiers as Id, ControlCommandStatus};
    use crate::devices::models::AirPodsModel;
    use crate::devices::settings::ListeningMode;
    use crate::prefs::{Bud, DevicePrefs, PressType, StemAction, StemActions};
    use std::time::{Duration, Instant};

    fn status(identifier: Id, value: &[u8]) -> ControlCommandStatus {
        ControlCommandStatus {
            identifier,
            value: value.to_vec(),
        }
    }

    #[test]
    fn stem_mask_per_press_type() {
        let single = StemActions {
            left_single: StemAction::PlayPause,
            ..StemActions::default()
        };
        assert_eq!(stem_forwarding_mask(&single), 0x01);
        let triple = StemActions {
            right_triple: StemAction::Command("x".to_string()),
            ..StemActions::default()
        };
        assert_eq!(stem_forwarding_mask(&triple), 0x04);
        let double_and_long = StemActions {
            left_double: StemAction::NextTrack,
            right_long: StemAction::CycleListeningMode,
            ..StemActions::default()
        };
        assert_eq!(stem_forwarding_mask(&double_and_long), 0x0A);
        assert_eq!(stem_forwarding_mask(&StemActions::default()), 0x00);
    }

    #[test]
    fn nothing_reapplied_by_default() {
        assert!(reapply_on_connect(&DevicePrefs::default()).is_empty());
    }

    #[test]
    fn stored_cycle_and_stem_mask_are_reapplied() {
        let prefs = DevicePrefs {
            listening_mode_cycle: Some(0x07),
            stem_actions: StemActions {
                left_double: StemAction::NextTrack,
                ..StemActions::default()
            },
            ..DevicePrefs::default()
        };
        assert_eq!(
            reapply_on_connect(&prefs),
            vec![
                (Id::ListeningModeConfigs, vec![0x07]),
                (Id::StemConfig, vec![0x02]),
            ]
        );
    }

    #[test]
    fn invalid_stored_cycle_is_not_reapplied() {
        let prefs = DevicePrefs {
            listening_mode_cycle: Some(0x02),
            ..DevicePrefs::default()
        };
        assert!(reapply_on_connect(&prefs).is_empty());
    }

    #[test]
    fn native_presses_map_to_native_equivalents() {
        let default = StemActions::default();
        assert_eq!(effective_stem_action(&default, Bud::Left, PressType::Single), StemAction::PlayPause);
        assert_eq!(effective_stem_action(&default, Bud::Left, PressType::Double), StemAction::NextTrack);
        assert_eq!(effective_stem_action(&default, Bud::Right, PressType::Triple), StemAction::PreviousTrack);
        assert_eq!(
            effective_stem_action(&default, Bud::Right, PressType::Long),
            StemAction::CycleListeningMode
        );
        let custom = StemActions {
            left_double: StemAction::PlayPause,
            ..StemActions::default()
        };
        assert_eq!(effective_stem_action(&custom, Bud::Left, PressType::Double), StemAction::PlayPause);
        assert_eq!(effective_stem_action(&custom, Bud::Right, PressType::Double), StemAction::NextTrack);
    }

    #[test]
    fn aacp_press_and_bud_mapping() {
        use crate::bluetooth::aacp::{StemPressBudType, StemPressType};
        assert_eq!(press_from_aacp(StemPressType::SinglePress), PressType::Single);
        assert_eq!(press_from_aacp(StemPressType::TriplePress), PressType::Triple);
        assert_eq!(press_from_aacp(StemPressType::LongPress), PressType::Long);
        assert_eq!(bud_from_aacp(StemPressBudType::Left), Bud::Left);
        assert_eq!(bud_from_aacp(StemPressBudType::Right), Bud::Right);
    }

    #[test]
    fn cycle_target_uses_reported_stored_or_default() {
        let pro1 = AirPodsModel::AirPodsPro1;
        let anc = [status(Id::ListeningMode, &[0x02])];
        assert_eq!(cycle_target(pro1, &anc, None), Some(ListeningMode::Transparency));
        let transparency = [status(Id::ListeningMode, &[0x03])];
        assert_eq!(cycle_target(pro1, &transparency, Some(0x07)), Some(ListeningMode::Off));
        let reported = [
            status(Id::ListeningMode, &[0x02]),
            status(Id::ListeningModeConfigs, &[0x03]),
        ];
        assert_eq!(cycle_target(pro1, &reported, Some(0x06)), Some(ListeningMode::Off));
        assert_eq!(cycle_target(AirPodsModel::AirPods2, &anc, None), None);
    }

    #[test]
    fn run_command_does_not_wait() {
        let start = Instant::now();
        run_command("sleep 5");
        assert!(start.elapsed() < Duration::from_millis(200), "took {:?}", start.elapsed());
        run_command("");
        run_command("   ");
        run_command("/nonexistent/librepods-test-command");
    }
}
