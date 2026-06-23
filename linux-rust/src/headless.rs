use crate::bluetooth::aacp::{AACPEvent, BatteryComponent, BatteryInfo, BatteryStatus, ControlCommandIdentifiers};
use crate::devices::enums::AirPodsNoiseControlMode;
use crate::ui::messages::BluetoothUIMessage;
use log::info;
use std::collections::{HashMap, HashSet};
use tokio::sync::mpsc::UnboundedReceiver;

fn battery_label(component: BatteryComponent) -> &'static str {
    match component {
        BatteryComponent::Headphone => "Headphones",
        BatteryComponent::Left => "L",
        BatteryComponent::Right => "R",
        BatteryComponent::Case => "Case",
    }
}

fn format_battery(info: &BatteryInfo) -> String {
    match info.status {
        BatteryStatus::Disconnected => format!("{}: disconnected", battery_label(info.component)),
        BatteryStatus::Charging => format!("{}: {}% (charging)", battery_label(info.component), info.level),
        BatteryStatus::NotCharging => format!("{}: {}%", battery_label(info.component), info.level),
    }
}

#[derive(Default)]
struct HeadlessStatus {
    connected: HashSet<String>,
    battery: HashMap<String, Vec<BatteryInfo>>,
    listening_mode: HashMap<String, u8>,
}

/// Consumes `BluetoothUIMessage` in `--no-tray` mode (otherwise only the tray/GUI read this
/// channel) and logs `info!` lines for connection, battery, and noise control mode changes,
/// skipping events that don't change the last-known value.
pub async fn run_headless_console(mut ui_rx: UnboundedReceiver<BluetoothUIMessage>) {
    let mut status = HeadlessStatus::default();
    while let Some(message) = ui_rx.recv().await {
        match message {
            BluetoothUIMessage::DeviceConnected(mac) => {
                if status.connected.insert(mac.clone()) {
                    info!("Connected: {}", mac);
                }
            }
            BluetoothUIMessage::DeviceDisconnected(mac) => {
                if status.connected.remove(&mac) {
                    info!("Disconnected: {}", mac);
                }
                status.battery.remove(&mac);
                status.listening_mode.remove(&mac);
            }
            BluetoothUIMessage::AACPUIEvent(mac, event) => match event {
                AACPEvent::BatteryInfo(battery_info) => {
                    let last = status.battery.entry(mac.clone()).or_default();
                    for component_info in &battery_info {
                        let changed = last
                            .iter()
                            .find(|b| b.component == component_info.component)
                            .map(|b| b != component_info)
                            .unwrap_or(true);
                        if changed {
                            info!("{} battery — {}", mac, format_battery(component_info));
                        }
                    }
                    *last = battery_info;
                }
                AACPEvent::ControlCommand(status_update)
                    if status_update.identifier == ControlCommandIdentifiers::ListeningMode =>
                {
                    let mode = status_update
                        .value
                        .first()
                        .map(AirPodsNoiseControlMode::from_byte)
                        .unwrap_or(AirPodsNoiseControlMode::Transparency);
                    let mode_byte = mode.to_byte();
                    let changed = status.listening_mode.get(&mac) != Some(&mode_byte);
                    if changed {
                        info!("{} noise control mode: {}", mac, mode);
                        status.listening_mode.insert(mac, mode_byte);
                    }
                }
                _ => {}
            },
            BluetoothUIMessage::OpenWindow
            | BluetoothUIMessage::ATTNotification(_, _, _)
            | BluetoothUIMessage::NoOp => {}
        }
    }
}
