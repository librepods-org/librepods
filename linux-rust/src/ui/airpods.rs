use crate::bluetooth::aacp::{
    AACPManager, BatteryComponent, BatteryInfo, ControlCommandIdentifiers, EarDetectionStatus,
};
use crate::devices::enums::{AirPodsNoiseControlMode, AirPodsState, DeviceData, DeviceInformation};
use crate::ui::components::{
    self, battery_ring, card, chip, glyph, muted_text, section, segmented, setting_row, tile,
};
use crate::ui::window::{AirPodsChange, Message};
use iced::widget::{Space, button, column, container, row, text, text_input, toggler};
use iced::{Alignment, Background, Border, Color, Element, Length, Theme};
use log::error;
use std::collections::HashMap;
use std::sync::Arc;
use std::thread;
use tokio::runtime::Runtime;

fn mode_label(mode: AirPodsNoiseControlMode) -> &'static str {
    match mode {
        AirPodsNoiseControlMode::Off => "Off",
        AirPodsNoiseControlMode::NoiseCancellation => "Noise Cancellation",
        AirPodsNoiseControlMode::Transparency => "Transparency",
        AirPodsNoiseControlMode::Adaptive => "Adaptive",
    }
}

fn mode_description(mode: AirPodsNoiseControlMode) -> &'static str {
    match mode {
        AirPodsNoiseControlMode::Off => "No noise control. Loud sounds are not reduced.",
        AirPodsNoiseControlMode::NoiseCancellation => "Blocks out sounds around you.",
        AirPodsNoiseControlMode::Transparency => "Lets sounds around you in.",
        AirPodsNoiseControlMode::Adaptive => "Adjusts noise control to your surroundings.",
    }
}

/// "both in ear", "one in ear", "in case", "out of ear".
fn ear_summary(status: &[EarDetectionStatus]) -> (String, bool) {
    let in_ear = status.iter().filter(|s| **s == EarDetectionStatus::InEar).count();
    match in_ear {
        n if n >= 2 => ("both in ear".to_string(), true),
        1 => ("one in ear".to_string(), true),
        _ if status.iter().all(|s| *s == EarDetectionStatus::InCase) && !status.is_empty() => {
            ("in case".to_string(), false)
        }
        _ if status.is_empty() => (String::new(), false),
        _ => ("out of ear".to_string(), false),
    }
}

fn find(battery: &[BatteryInfo], c: BatteryComponent) -> Option<&BatteryInfo> {
    battery.iter().find(|b| b.component == c)
}

fn hero<'a>(state: &'a AirPodsState, last_case: Option<&'a BatteryInfo>) -> Element<'a, Message> {
    let (ear, _) = ear_summary(&state.ear_status);
    let status = if ear.is_empty() {
        "Connected".to_string()
    } else {
        format!("Connected · {ear}")
    };
    let rings: Element<'a, Message> = if let Some(h) = find(&state.battery, BatteryComponent::Headphone) {
        battery_ring("Battery", Some(h), false)
    } else {
        // The buds only know the case battery while they're in it or its lid is
        // open; otherwise show the last value we saw, marked as such.
        let (case, case_stale) = match find(&state.battery, BatteryComponent::Case) {
            Some(c) => (Some(c), false),
            None => (last_case, last_case.is_some()),
        };
        row![
            battery_ring("Left", find(&state.battery, BatteryComponent::Left), false),
            battery_ring("Right", find(&state.battery, BatteryComponent::Right), false),
            battery_ring("Case", case, case_stale),
        ]
        .spacing(16)
        .into()
    };
    let icon = container(text(glyph::AIRPODS).size(44))
        .center_x(84)
        .center_y(84)
        .style(|theme: &Theme| {
            let p = theme.extended_palette();
            iced::widget::container::Style::default()
                .background(p.primary.base.color.scale_alpha(0.15))
                .color(p.primary.base.color)
                .border(Border::default().rounded(20))
        });
    card(
        row![
            icon,
            column![
                text("AIRPODS")
                    .size(11)
                    .font(components::bold())
                    .style(|theme: &Theme| text::Style {
                        color: Some(theme.extended_palette().primary.base.color),
                    }),
                text(state.device_name.clone()).size(24).font(components::bold()),
                // This page is only shown while the AirPods are connected.
                chip(status, Some(Color::from_rgb8(0x2e, 0xc2, 0x7e))),
            ]
            .spacing(6)
            .width(Length::Fill),
            rings,
        ]
        .spacing(20)
        .align_y(Alignment::Center),
    )
    .padding(20)
    .into()
}

fn tiles<'a>(state: &'a AirPodsState) -> Element<'a, Message> {
    let (ear, _) = ear_summary(&state.ear_status);
    row![
        tile("Noise control", mode_label(state.noise_control_mode).to_string()),
        tile(
            "Conversation awareness",
            if state.conversation_awareness_enabled { "On" } else { "Off" }.to_string(),
        ),
        tile(
            "Ear detection",
            if ear.is_empty() { "–".to_string() } else { capitalize(&ear) },
        ),
    ]
    .spacing(12)
    .into()
}

fn capitalize(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
        None => String::new(),
    }
}

fn change<'a>(mac: &str, c: AirPodsChange) -> Message {
    Message::AirPodsChanged(mac.to_string(), c)
}

/// The AirPods overview page.
pub fn airpods_view<'a>(
    mac: &'a str,
    devices_list: &HashMap<String, DeviceData>,
    state: &'a AirPodsState,
    about_expanded: bool,
    last_case: Option<&'a BatteryInfo>,
) -> Element<'a, Message> {
    // Noise control
    let mut modes = vec![];
    if state.allow_off_mode {
        modes.push(AirPodsNoiseControlMode::Off);
    }
    modes.extend([
        AirPodsNoiseControlMode::NoiseCancellation,
        AirPodsNoiseControlMode::Transparency,
        AirPodsNoiseControlMode::Adaptive,
    ]);
    let noise_control = section(
        "Noise control",
        vec![
            column![
                segmented(
                    modes.iter().map(|m| (*m, mode_label(*m).to_string())).collect(),
                    Some(state.noise_control_mode),
                    move |m| change(mac, AirPodsChange::NoiseControlMode(m)),
                ),
                text(mode_description(state.noise_control_mode))
                    .size(12)
                    .style(muted_text),
            ]
            .spacing(8)
            .padding([12, 16])
            .into(),
            setting_row(
                "Allow Off mode",
                Some("Adds an Off option to the listening modes.".to_string()),
                toggler(state.allow_off_mode)
                    .on_toggle(move |v| change(mac, AirPodsChange::AllowOffMode(v)))
                    .size(22),
            ),
        ],
    );

    let audio = section(
        "Audio",
        vec![
            setting_row(
                "Conversation awareness",
                Some("Lowers the volume when you start speaking.".to_string()),
                toggler(state.conversation_awareness_enabled)
                    .on_toggle(move |v| change(mac, AirPodsChange::ConversationAwareness(v)))
                    .size(22),
            ),
            setting_row(
                "Personalized volume",
                Some("Adjusts the volume in response to your environment.".to_string()),
                toggler(state.personalized_volume_enabled)
                    .on_toggle(move |v| change(mac, AirPodsChange::PersonalizedVolume(v)))
                    .size(22),
            ),
        ],
    );

    let about = about_section(mac, devices_list, state, about_expanded);

    column![hero(state, last_case), tiles(state), noise_control, audio, about]
        .spacing(20)
        .padding([20, 24])
        .into()
}

fn copyable<'a>(value: String) -> Element<'a, Message> {
    button(text(value.clone()).size(14))
        .padding(0)
        .style(|theme: &Theme, status| button::Style {
            background: Some(Background::Color(Color::TRANSPARENT)),
            text_color: if matches!(status, button::Status::Hovered) {
                theme.extended_palette().primary.base.color
            } else {
                theme.extended_palette().background.base.text
            },
            ..Default::default()
        })
        .on_press(Message::CopyToClipboard(value))
        .into()
}

fn value_row<'a>(label: &'a str, value: Element<'a, Message>) -> Element<'a, Message> {
    row![text(label).size(14).style(muted_text).width(Length::Fill), value]
        .align_y(Alignment::Center)
        .padding([10, 16])
        .into()
}

fn about_section<'a>(
    mac: &'a str,
    devices_list: &HashMap<String, DeviceData>,
    state: &'a AirPodsState,
    expanded: bool,
) -> Element<'a, Message> {
    let header = button(
        row![
            text("About this device").size(15).font(components::bold()),
            Space::new().width(Length::Fill),
            text(if expanded { "Hide" } else { "Show" }).size(13).style(|theme: &Theme| {
                text::Style {
                    color: Some(theme.extended_palette().primary.base.color),
                }
            }),
        ]
        .align_y(Alignment::Center),
    )
    .padding(0)
    .style(|theme: &Theme, _| button::Style {
        background: None,
        text_color: theme.extended_palette().background.base.text,
        ..Default::default()
    })
    .on_press(Message::ToggleAboutDevice);

    if !expanded {
        return header.into();
    }

    let mut rows: Vec<Element<'a, Message>> = vec![value_row(
        "Name",
        text_input("", &state.device_name)
            .on_input(move |n| change(mac, AirPodsChange::DeviceName(n)))
            .size(14)
            .width(220)
            .align_x(Alignment::End)
            .style(|theme: &Theme, _| text_input::Style {
                background: Background::Color(theme.extended_palette().background.base.color),
                border: Border::default().rounded(8),
                icon: Color::TRANSPARENT,
                placeholder: components::muted(theme),
                value: theme.extended_palette().background.base.text,
                selection: theme.extended_palette().primary.weak.color,
            })
            .into(),
    )];

    match devices_list.get(mac).and_then(|d| d.information.as_ref()) {
        Some(DeviceInformation::AirPods(info)) => {
            rows.push(value_row("Model", text(info.model_number.clone()).size(14).into()));
            rows.push(value_row("Manufacturer", text(info.manufacturer.clone()).size(14).into()));
            rows.push(value_row("Serial number", copyable(info.serial_number.clone())));
            rows.push(value_row("Left bud serial", copyable(info.left_serial_number.clone())));
            rows.push(value_row("Right bud serial", copyable(info.right_serial_number.clone())));
            rows.push(value_row("Firmware", text(info.version1.clone()).size(14).into()));
            rows.push(value_row("Build", text(info.version3.clone()).size(14).into()));
        }
        Some(_) => error!("Expected AirPodsInformation for device {}", mac),
        None => {}
    }

    let mut body = column![];
    let count = rows.len();
    for (i, r) in rows.into_iter().enumerate() {
        body = body.push(r);
        if i + 1 < count {
            body = body.push(
                container(Space::new().height(1)).width(Length::Fill).style(|theme: &Theme| {
                    iced::widget::container::Style::default().background(
                        theme.extended_palette().background.base.text.scale_alpha(0.08),
                    )
                }),
            );
        }
    }
    column![
        header,
        card(body),
        text("Click a serial number to copy it.").size(12).style(muted_text),
    ]
    .spacing(8)
    .into()
}

/// Sends an AirPods setting change to the device.
pub fn send_change(aacp_manager: Arc<AACPManager>, change: &AirPodsChange) {
    let (id, value): (ControlCommandIdentifiers, u8) = match change {
        AirPodsChange::DeviceName(name) => {
            let name = name.clone();
            run_async_in_thread(async move {
                if let Err(e) = aacp_manager.send_rename_packet(&name).await {
                    error!("Failed to rename AirPods: {}", e);
                }
            });
            return;
        }
        AirPodsChange::NoiseControlMode(mode) => (ControlCommandIdentifiers::ListeningMode, mode.to_byte()),
        AirPodsChange::ConversationAwareness(v) => {
            (ControlCommandIdentifiers::ConversationDetectConfig, if *v { 0x01 } else { 0x02 })
        }
        AirPodsChange::PersonalizedVolume(v) => {
            (ControlCommandIdentifiers::AdaptiveVolumeConfig, if *v { 0x01 } else { 0x02 })
        }
        AirPodsChange::AllowOffMode(v) => {
            (ControlCommandIdentifiers::AllowOffOption, if *v { 0x01 } else { 0x02 })
        }
    };
    run_async_in_thread(async move {
        if let Err(e) = aacp_manager.send_control_command(id, &[value]).await {
            error!("Failed to send {:?}: {}", id, e);
        }
    });
}

fn run_async_in_thread<F>(fut: F)
where
    F: Future<Output = ()> + Send + 'static,
{
    thread::spawn(move || {
        let rt = Runtime::new().unwrap();
        rt.block_on(fut);
    });
}
