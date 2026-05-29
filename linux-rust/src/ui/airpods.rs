use crate::bluetooth::aacp::{AACPManager, BatteryComponent, BatteryStatus, ControlCommandIdentifiers};
use iced::Alignment::End;
use iced::border::Radius;
use iced::widget::button::Style;
use iced::widget::image;
use iced::widget::rule::FillMode;
use iced::widget::{
    Space, button, column, container, row, rule, scrollable, text, text_input, toggler,
};
use iced::{Background, Border, Center, Color, Length, Padding, Theme};
use log::error;
use std::collections::HashMap;
use std::sync::Arc;
use std::thread;
use tokio::runtime::Runtime;
// use crate::bluetooth::att::ATTManager;
use crate::devices::enums::{
    AirPodsNoiseControlMode, AirPodsState, DeviceData, DeviceInformation, DeviceState,
};
use crate::ui::window::Message;

// Embed the listening mode icons at compile time from the Android assets
const ICON_NOISE_CANCELLATION: &[u8] =
    include_bytes!("../../assets/icons/noise_cancellation.png");
const ICON_TRANSPARENCY: &[u8] = include_bytes!("../../assets/icons/transparency.png");
const ICON_ADAPTIVE: &[u8] = include_bytes!("../../assets/icons/adaptive.png");

// Embed device images at compile time from the legacy Qt assets
const IMG_POD: &[u8] = include_bytes!("../../assets/devices/pod.png");
const IMG_POD_CASE: &[u8] = include_bytes!("../../assets/devices/pod_case.png");
const IMG_POD3: &[u8] = include_bytes!("../../assets/devices/pod3.png");
const IMG_POD3_CASE: &[u8] = include_bytes!("../../assets/devices/pod3_case.png");
const IMG_POD4_CASE: &[u8] = include_bytes!("../../assets/devices/pod4_case.png");
const IMG_PODPRO: &[u8] = include_bytes!("../../assets/devices/podpro.png");
const IMG_PODPRO_CASE: &[u8] = include_bytes!("../../assets/devices/podpro_case.png");
const IMG_PODMAX: &[u8] = include_bytes!("../../assets/devices/podmax.png");

/// Get the embedded bytes for a device image filename.
fn device_image_bytes(filename: &str) -> &'static [u8] {
    match filename {
        "pod.png" => IMG_POD,
        "pod_case.png" => IMG_POD_CASE,
        "pod3.png" => IMG_POD3,
        "pod3_case.png" => IMG_POD3_CASE,
        "pod4_case.png" => IMG_POD4_CASE,
        "podpro.png" => IMG_PODPRO,
        "podpro_case.png" => IMG_PODPRO_CASE,
        "podmax.png" => IMG_PODMAX,
        _ => IMG_POD,
    }
}

/// Battery level color: green >50%, yellow 20-50%, red <20%.
fn battery_color(level: u8) -> Color {
    if level <= 20 {
        Color::from_rgb(1.0, 0.27, 0.23) // #FF453A — red
    } else if level <= 50 {
        Color::from_rgb(1.0, 0.84, 0.04) // #FFD60A — yellow
    } else {
        Color::from_rgb(0.19, 0.82, 0.35) // #30D158 — green
    }
}

/// Build a single battery column: device image + battery bar + percentage.
fn battery_column<'a>(
    img_bytes: &'static [u8],
    label: &'a str,
    level: Option<u8>,
    status: Option<BatteryStatus>,
    img_width: f32,
) -> iced::Element<'a, Message> {
    let is_disconnected = status == Some(BatteryStatus::Disconnected);
    let is_charging = status == Some(BatteryStatus::Charging);
    let opacity = if is_disconnected { 0.35 } else { 1.0 };

    let device_img = container(
        image(image::Handle::from_bytes(img_bytes))
            .width(Length::Fixed(img_width))
    )
    .center_x(Length::Fill);

    // Bar width and height constants
    let bar_total_width = 80.0_f32;
    let bar_height = 8.0_f32;

    let bar_and_text: iced::Element<'a, Message> = if is_disconnected {
        // Disconnected: show empty dimmed bar + label with "–"
        column![
            // Empty dimmed battery bar
            container(Space::new())
                .width(Length::Fixed(bar_total_width))
                .height(bar_height)
                .style(move |theme: &Theme| {
                    let mut s = container::Style::default();
                    s.background = Some(Background::Color(
                        theme.palette().text.scale_alpha(0.08),
                    ));
                    s.border = Border::default().rounded(4);
                    s
                }),
            // Label + "–"
            row![
                text(label).size(12).style(move |theme: &Theme| {
                    let mut s = text::Style::default();
                    s.color = Some(theme.palette().text.scale_alpha(0.35));
                    s
                }),
                Space::new().width(Length::Fill),
                text("–").size(14).style(move |theme: &Theme| {
                    let mut s = text::Style::default();
                    s.color = Some(theme.palette().text.scale_alpha(0.35));
                    s
                })
            ]
            .width(Length::Fixed(bar_total_width))
            .align_y(Center)
        ]
        .spacing(4)
        .align_x(iced::Alignment::Center)
        .into()
    } else if let Some(lvl) = level {
        let color = battery_color(lvl);
        let bar_fill = (lvl as f32 / 100.0).clamp(0.02, 1.0);

        let charging_indicator: iced::Element<'a, Message> = if is_charging {
            text(" ⚡").size(12).style(move |_theme: &Theme| {
                let mut s = text::Style::default();
                s.color = Some(Color::from_rgb(0.19, 0.82, 0.35));
                s
            }).into()
        } else {
            Space::new().into()
        };

        column![
            // Battery bar
            container(
                container(Space::new())
                    .width(Length::FillPortion((bar_fill * 100.0) as u16))
                    .height(bar_height)
                    .style(move |_theme: &Theme| {
                        let mut s = container::Style::default();
                        s.background = Some(Background::Color(color));
                        s.border = Border::default().rounded(4);
                        s
                    })
            )
            .width(Length::Fixed(bar_total_width))
            .height(bar_height)
            .style(move |theme: &Theme| {
                let mut s = container::Style::default();
                s.background = Some(Background::Color(
                    theme.palette().text.scale_alpha(0.1),
                ));
                s.border = Border::default().rounded(4);
                s
            }),
            // Label + percentage
            row![
                text(label).size(12).style(move |theme: &Theme| {
                    let mut s = text::Style::default();
                    s.color = Some(theme.palette().text.scale_alpha(0.55));
                    s
                }),
                Space::new().width(Length::Fill),
                text(format!("{}%", lvl)).size(14),
                charging_indicator
            ]
            .width(Length::Fixed(bar_total_width))
            .align_y(Center)
        ]
        .spacing(4)
        .align_x(iced::Alignment::Center)
        .into()
    } else {
        Space::new().into()
    };

    container(
        column![
            device_img,
            Space::new().height(8),
            bar_and_text
        ]
        .align_x(iced::Alignment::Center)
    )
    .style(move |_theme: &Theme| {
        let mut s = container::Style::default();
        s.text_color = Some(Color::WHITE.scale_alpha(opacity));
        s
    })
    .center_x(Length::Fill)
    .into()
}

/// Build the full battery view section with device images and battery indicators.
fn battery_view<'a>(state: &'a AirPodsState) -> iced::Element<'a, Message> {
    let (bud_img_name, case_img_name) = state.model.device_images();
    let bud_bytes = device_image_bytes(bud_img_name);
    let case_bytes = device_image_bytes(case_img_name);

    let battery = &state.battery;

    // Check for headphone-only (AirPods Max)
    let headphone = battery.iter().find(|b| b.component == BatteryComponent::Headphone);

    if state.model.is_over_ear() || headphone.is_some() {
        // AirPods Max: single headphone display
        let hp = headphone;
        let level = hp.map(|b| b.level);
        let status = hp.map(|b| b.status);

        container(
            battery_column(bud_bytes, "", level, status, 80.0)
        )
        .center_x(Length::Fill)
        .padding(Padding {
            top: 12.0,
            bottom: 12.0,
            left: 20.0,
            right: 20.0,
        })
        .into()
    } else {
        // Earbuds: L + R + Case
        let left = battery.iter().find(|b| b.component == BatteryComponent::Left);
        let right = battery.iter().find(|b| b.component == BatteryComponent::Right);
        let case = battery.iter().find(|b| b.component == BatteryComponent::Case);

        let left_level = left.map(|b| b.level);
        let left_status = left.map(|b| b.status);
        let right_level = right.map(|b| b.level);
        let right_status = right.map(|b| b.status);
        let case_level = case.map(|b| b.level);
        let case_status = case.map(|b| b.status);

        container(
            row![
                battery_column(bud_bytes, "L", left_level, left_status, 48.0),
                battery_column(bud_bytes, "R", right_level, right_status, 48.0),
                battery_column(case_bytes, "Case", case_level, case_status, 60.0)
            ]
            .spacing(24)
            .align_y(iced::Alignment::End)
        )
        .center_x(Length::Fill)
        .padding(Padding {
            top: 12.0,
            bottom: 12.0,
            left: 20.0,
            right: 20.0,
        })
        .into()
    }
}

/// Build a single segmented button for a listening mode.
fn listening_mode_button<'a>(
    mode: AirPodsNoiseControlMode,
    is_selected: bool,
    icon_bytes: Option<&'static [u8]>,
    label: &'a str,
    mac: String,
) -> iced::Element<'a, Message> {
    let icon_element: iced::Element<'a, Message> = if let Some(bytes) = icon_bytes {
        image(image::Handle::from_bytes(bytes))
            .width(28)
            .height(28)
            .into()
    } else {
        // "Off" mode uses a unicode power symbol instead of a PNG icon
        text("\u{23FB}")
            .size(22)
            .align_x(Center)
            .style(move |theme: &Theme| {
                let mut style = text::Style::default();
                style.color = Some(if is_selected {
                    theme.palette().primary
                } else {
                    theme.palette().text.scale_alpha(0.6)
                });
                style
            })
            .into()
    };

    let label_text = text(label).size(11).align_x(Center).style(
        move |theme: &Theme| {
            let mut style = text::Style::default();
            style.color = Some(if is_selected {
                theme.palette().primary
            } else {
                theme.palette().text.scale_alpha(0.7)
            });
            style
        },
    );

    let content = column![icon_element, label_text]
        .spacing(4)
        .align_x(Center)
        .width(Length::Fill);

    button(content)
        .padding(Padding {
            top: 10.0,
            bottom: 8.0,
            left: 4.0,
            right: 4.0,
        })
        .width(Length::Fill)
        .style(move |theme: &Theme, _status| {
            let mut style = Style::default();
            if is_selected {
                style.background =
                    Some(Background::Color(theme.palette().primary.scale_alpha(0.15)));
                style.border = Border {
                    width: 1.5,
                    color: theme.palette().primary.scale_alpha(0.5),
                    radius: Radius::from(12.0),
                };
            } else {
                style.background = Some(Background::Color(Color::TRANSPARENT));
                style.border = Border {
                    width: 1.0,
                    color: theme.palette().text.scale_alpha(0.1),
                    radius: Radius::from(12.0),
                };
            }
            style.text_color = theme.palette().text;
            style
        })
        // Only send a message — side effects (AACP command) are handled in update()
        .on_press(Message::SetListeningMode(mac, mode))
        .into()
}

pub fn airpods_view<'a>(
    mac: &'a str,
    devices_list: &HashMap<String, DeviceData>,
    state: &'a AirPodsState,
    aacp_manager: Arc<AACPManager>,
    show_serials: bool,
    show_device_info: bool,
    show_off_listening_mode: bool,
    // att_manager: Arc<ATTManager>
) -> iced::widget::Container<'a, Message> {
    let mac = mac.to_string();
    // order: name, noise control, press and hold config, call controls (not sure if why it might be needed, adding it just in case), audio (personalized volume, conversational awareness, adaptive audio slider), connection settings, microphone, head gestures (not adding this), off listening mode, device information

    let aacp_manager_for_rename = aacp_manager.clone();
    let rename_input = container(
        row![
            Space::new().width(10),
            text("Name").size(16).style(|theme: &Theme| {
                let mut style = text::Style::default();
                style.color = Some(theme.palette().text);
                style
            }),
            Space::new().width(Length::Fill),
            text_input("", &state.device_name)
                .padding(Padding {
                    top: 5.0,
                    bottom: 5.0,
                    left: 10.0,
                    right: 10.0,
                })
                .style(|theme: &Theme, _status| {
                    text_input::Style {
                        background: Background::Color(Color::TRANSPARENT),
                        border: Default::default(),
                        icon: Default::default(),
                        placeholder: theme.palette().text.scale_alpha(0.7),
                        value: theme.palette().text,
                        selection: Default::default(),
                    }
                })
                .align_x(End)
                .on_input({
                    let mac = mac.clone();
                    let state = state.clone();
                    move |new_name| {
                        let aacp_manager = aacp_manager_for_rename.clone();
                        run_async_in_thread({
                            let new_name = new_name.clone();
                            async move {
                                aacp_manager
                                    .send_rename_packet(&new_name)
                                    .await
                                    .expect("Failed to send rename packet");
                            }
                        });
                        let mut state = state.clone();
                        state.device_name = new_name.clone();
                        Message::StateChanged(mac.to_string(), DeviceState::AirPods(state))
                    }
                })
        ]
        .align_y(Center),
    )
    .padding(Padding {
        top: 5.0,
        bottom: 5.0,
        left: 10.0,
        right: 10.0,
    })
    .style(|theme: &Theme| {
        let mut style = container::Style::default();
        style.background = Some(Background::Color(theme.palette().primary.scale_alpha(0.1)));
        let mut border = Border::default();
        border.color = theme.palette().primary.scale_alpha(0.5);
        style.border = border.rounded(16);
        style
    });

    // --- Segmented listening mode control ---
    let mut mode_buttons: Vec<iced::Element<'a, Message>> = Vec::new();

    // Conditionally include "Off" based on the app setting
    if show_off_listening_mode {
        mode_buttons.push(listening_mode_button(
            AirPodsNoiseControlMode::Off,
            state.noise_control_mode == AirPodsNoiseControlMode::Off,
            None,
            "Off",
            mac.clone(),
        ));
    }

    mode_buttons.push(listening_mode_button(
        AirPodsNoiseControlMode::NoiseCancellation,
        state.noise_control_mode == AirPodsNoiseControlMode::NoiseCancellation,
        Some(ICON_NOISE_CANCELLATION),
        "Noise Cancellation",
        mac.clone(),
    ));

    mode_buttons.push(listening_mode_button(
        AirPodsNoiseControlMode::Transparency,
        state.noise_control_mode == AirPodsNoiseControlMode::Transparency,
        Some(ICON_TRANSPARENCY),
        "Transparency",
        mac.clone(),
    ));

    mode_buttons.push(listening_mode_button(
        AirPodsNoiseControlMode::Adaptive,
        state.noise_control_mode == AirPodsNoiseControlMode::Adaptive,
        Some(ICON_ADAPTIVE),
        "Adaptive",
        mac.clone(),
    ));

    let listening_mode = container(
        column![
            container(
                text("Listening Mode").size(18).style(|theme: &Theme| {
                    let mut style = text::Style::default();
                    style.color = Some(theme.palette().primary);
                    style
                })
            )
            .padding(Padding {
                top: 0.0,
                bottom: 4.0,
                left: 4.0,
                right: 4.0,
            }),
            container(
                row(mode_buttons).spacing(6)
            )
            .padding(Padding {
                top: 4.0,
                bottom: 4.0,
                left: 4.0,
                right: 4.0,
            })
            .style(|theme: &Theme| {
                let mut style = container::Style::default();
                style.background =
                    Some(Background::Color(theme.palette().primary.scale_alpha(0.05)));
                let mut border = Border::default();
                border.color = theme.palette().primary.scale_alpha(0.3);
                style.border = border.rounded(16);
                style
            })
        ]
    )
    .padding(Padding {
        top: 5.0,
        bottom: 5.0,
        left: 14.0,
        right: 14.0,
    });

    let mac_audio = mac.clone();
    let mac_information = mac.clone();

    let audio_settings_col = column![
        container(
            text("Audio Settings").size(18).style(
                |theme: &Theme| {
                    let mut style = text::Style::default();
                    style.color = Some(theme.palette().primary);
                    style
                }
            )
        )
        .padding(Padding{
            top: 5.0,
            bottom: 5.0,
            left: 18.0,
            right: 18.0,
        }),

        container(
            column![
                {
                    let aacp_manager_pv = aacp_manager.clone();
                    row![
                        column![
                            text("Personalized Volume").size(16),
                            text("Adjusts the volume in response to your environment.").size(12).style(
                                |theme: &Theme| {
                                    let mut style = text::Style::default();
                                    style.color = Some(theme.palette().text.scale_alpha(0.7));
                                    style
                                }
                            ).width(Length::Fill),
                        ].width(Length::Fill),
                        toggler(state.personalized_volume_enabled)
                            .on_toggle(
                            {
                                let mac = mac_audio.clone();
                                let state = state.clone();
                                move |is_enabled| {
                                    let aacp_manager = aacp_manager_pv.clone();
                                    let mac = mac.clone();
                                    run_async_in_thread(
                                        async move {
                                            aacp_manager.send_control_command(
                                                ControlCommandIdentifiers::AdaptiveVolumeConfig,
                                                if is_enabled { &[0x01] } else { &[0x02] }
                                            ).await.expect("Failed to send Personalized Volume command");
                                        }
                                    );
                                    let mut state = state.clone();
                                    state.personalized_volume_enabled = is_enabled;
                                    Message::StateChanged(mac, DeviceState::AirPods(state))
                                }
                            }
                        )
                        .spacing(0)
                        .size(20)
                    ]
                    .align_y(Center)
                    .spacing(8)
                },
                rule::horizontal(1).style(
                    |theme: &Theme| {
                        rule::Style {
                            color: theme.palette().text.scale_alpha(0.2),
                            radius: Radius::from(12),
                            fill_mode: FillMode::Full,
                            snap: false
                        }
                    }
                ),
                {
                    let aacp_manager_conv_detect = aacp_manager.clone();
                    row![
                        column![
                            text("Conversation Awareness").size(16),
                            text("Lowers the volume of your audio when it detects that you are speaking.").size(12).style(
                                |theme: &Theme| {
                                    let mut style = text::Style::default();
                                    style.color = Some(theme.palette().text.scale_alpha(0.7));
                                    style
                                }
                            ).width(Length::Fill),
                        ].width(Length::Fill),
                        toggler(state.conversation_awareness_enabled)
                            .on_toggle(move |is_enabled| {
                                let aacp_manager = aacp_manager_conv_detect.clone();
                                run_async_in_thread(
                                    async move {
                                        aacp_manager.send_control_command(
                                            ControlCommandIdentifiers::ConversationDetectConfig,
                                            if is_enabled { &[0x01] } else { &[0x02] }
                                        ).await.expect("Failed to send Conversation Awareness command");
                                    }
                                );
                                let mut state = state.clone();
                                state.conversation_awareness_enabled = is_enabled;
                                Message::StateChanged(mac_audio.to_string(), DeviceState::AirPods(state))
                            })
                        .spacing(0)
                        .size(20)
                    ]
                    .align_y(Center)
                    .spacing(8)
                }
            ]
                .spacing(4)
                .padding(8)
        )
        .padding(Padding{
            top: 5.0,
            bottom: 5.0,
            left: 10.0,
            right: 10.0,
        })
        .style(
            |theme: &Theme| {
                let mut style = container::Style::default();
                style.background = Some(Background::Color(theme.palette().primary.scale_alpha(0.1)));
                let mut border = Border::default();
                border.color = theme.palette().primary.scale_alpha(0.5);
                style.border = border.rounded(16);
                style
            }
        )
    ];

    let mut information_col = column![];
    if let Some(device) = devices_list.get(mac_information.as_str()) {
        if let Some(DeviceInformation::AirPods(ref airpods_info)) = device.information {
            let chevron = if show_device_info { "\u{25be}" } else { "\u{25b8}" };
            let header = button(
                row![
                    text(format!("{} Device Information", chevron)).size(18).style(|theme: &Theme| {
                        let mut style = text::Style::default();
                        style.color = Some(theme.palette().primary);
                        style
                    }),
                ]
                .align_y(iced::Alignment::Center)
            )
            .style(|_theme: &Theme, _status| {
                let mut style = Style::default();
                style.background = Some(Background::Color(Color::TRANSPARENT));
                style.text_color = Color::TRANSPARENT;
                style
            })
            .padding(Padding {
                top: 5.0,
                bottom: 5.0,
                left: 18.0,
                right: 18.0,
            })
            .on_press(Message::ToggleDeviceInfo);

            if show_device_info {
                let serial_display = |serial: String| -> String {
                    if show_serials { serial } else { "\u{2022}\u{2022}\u{2022}\u{2022}\u{2022}\u{2022}\u{2022}\u{2022}\u{2022}\u{2022}".to_string() }
                };
                let eye_icon = if show_serials { "\u{1f441}" } else { "\u{25c9}" };

                let info_rows = column![
                    row![
                        text("Model Number").size(16).style(|theme: &Theme| {
                            let mut style = text::Style::default();
                            style.color = Some(theme.palette().text);
                            style
                        }),
                        Space::new().width(Length::Fill),
                        text(match airpods_info.friendly_model_name() {
                            Some(name) => format!("{} ({})", airpods_info.model_number, name),
                            None => airpods_info.model_number.clone(),
                        }).size(16)
                    ],
                    row![
                        text("Manufacturer").size(16).style(|theme: &Theme| {
                            let mut style = text::Style::default();
                            style.color = Some(theme.palette().text);
                            style
                        }),
                        Space::new().width(Length::Fill),
                        text(airpods_info.manufacturer.clone()).size(16)
                    ],
                    row![
                        text("Serial Number").size(16).style(|theme: &Theme| {
                            let mut style = text::Style::default();
                            style.color = Some(theme.palette().text);
                            style
                        }),
                        Space::new().width(Length::Fill),
                        button(
                            row![
                                text(serial_display(airpods_info.serial_number.clone())).size(16),
                                text(eye_icon).size(14),
                            ].spacing(6).align_y(iced::Alignment::Center)
                        )
                            .style(|theme: &Theme, _status| {
                                let mut style = Style::default();
                                style.text_color = theme.palette().text;
                                style.background = Some(Background::Color(Color::TRANSPARENT));
                                style
                            })
                            .padding(0)
                            .on_press(Message::ToggleSerialVisibility)
                    ],
                    row![
                        text("Left Serial Number").size(16).style(|theme: &Theme| {
                            let mut style = text::Style::default();
                            style.color = Some(theme.palette().text);
                            style
                        }),
                        Space::new().width(Length::Fill),
                        button(
                            row![
                                text(serial_display(airpods_info.left_serial_number.clone())).size(16),
                                text(eye_icon).size(14),
                            ].spacing(6).align_y(iced::Alignment::Center)
                        )
                            .style(|theme: &Theme, _status| {
                                let mut style = Style::default();
                                style.text_color = theme.palette().text;
                                style.background = Some(Background::Color(Color::TRANSPARENT));
                                style
                            })
                            .padding(0)
                            .on_press(Message::ToggleSerialVisibility)
                    ],
                    row![
                        text("Right Serial Number").size(16).style(|theme: &Theme| {
                            let mut style = text::Style::default();
                            style.color = Some(theme.palette().text);
                            style
                        }),
                        Space::new().width(Length::Fill),
                        button(
                            row![
                                text(serial_display(airpods_info.right_serial_number.clone())).size(16),
                                text(eye_icon).size(14),
                            ].spacing(6).align_y(iced::Alignment::Center)
                        )
                            .style(|theme: &Theme, _status| {
                                let mut style = Style::default();
                                style.text_color = theme.palette().text;
                                style.background = Some(Background::Color(Color::TRANSPARENT));
                                style
                            })
                            .padding(0)
                            .on_press(Message::ToggleSerialVisibility)
                    ],
                    row![
                        text("Version 1").size(16).style(|theme: &Theme| {
                            let mut style = text::Style::default();
                            style.color = Some(theme.palette().text);
                            style
                        }),
                        Space::new().width(Length::Fill),
                        text(airpods_info.version1.clone()).size(16)
                    ],
                    row![
                        text("Version 2").size(16).style(|theme: &Theme| {
                            let mut style = text::Style::default();
                            style.color = Some(theme.palette().text);
                            style
                        }),
                        Space::new().width(Length::Fill),
                        text(airpods_info.version2.clone()).size(16)
                    ],
                    row![
                        text("Version 3").size(16).style(|theme: &Theme| {
                            let mut style = text::Style::default();
                            style.color = Some(theme.palette().text);
                            style
                        }),
                        Space::new().width(Length::Fill),
                        text(airpods_info.version3.clone()).size(16)
                    ]
                ]
                .spacing(4)
                .padding(8);

                information_col = column![
                    header,
                    container(info_rows)
                        .padding(Padding {
                            top: 5.0,
                            bottom: 5.0,
                            left: 10.0,
                            right: 10.0,
                        })
                        .style(|theme: &Theme| {
                            let mut style = container::Style::default();
                            style.background =
                                Some(Background::Color(theme.palette().primary.scale_alpha(0.1)));
                            let mut border = Border::default();
                            border.color = theme.palette().primary.scale_alpha(0.5);
                            style.border = border.rounded(16);
                            style
                        })
                ];
            } else {
                information_col = column![header];
            }
        } else {
            error!(
                "Expected AirPodsInformation for device {}, got something else",
                mac.clone()
            );
        }
    }

    // Battery view with device images
    let battery_section = battery_view(state);

    let content = container(column![
        rename_input,
        Space::new().height(Length::from(10)),
        battery_section,
        Space::new().height(Length::from(10)),
        listening_mode,
        Space::new().height(Length::from(20)),
        audio_settings_col,
        Space::new().height(Length::from(20)),
        information_col
    ])
    .padding(20)
    .center_x(Length::Fill);

    container(scrollable(content).height(Length::Fill))
        .height(Length::Fill)
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
