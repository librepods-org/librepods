use crate::bluetooth::aacp::{AACPManager, ControlCommandIdentifiers};
use iced::Alignment::End;
use iced::border::Radius;
use iced::overlay::menu;
use iced::widget::button::Style;
use iced::widget::rule::FillMode;
use iced::widget::{
    Space, button, column, combo_box, container, progress_bar, row, rule, scrollable, text,
    text_input, toggler,
};
use iced::{Background, Border, Center, Color, Length, Padding, Theme};
use log::error;
use std::collections::HashMap;
use std::sync::Arc;
use std::thread;
use tokio::runtime::Runtime;
// use crate::bluetooth::att::ATTManager;
use crate::devices::enums::{AirPodsState, DeviceData, DeviceInformation, DeviceState};
use crate::ui::window::Message;

pub fn airpods_view<'a>(
    mac: &'a str,
    devices_list: &HashMap<String, DeviceData>,
    state: &'a AirPodsState,
    aacp_manager: Arc<AACPManager>,
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

    let listening_mode = container(
        row![
            text("Listening Mode").size(16).style(|theme: &Theme| {
                let mut style = text::Style::default();
                style.color = Some(theme.palette().text);
                style
            }),
            Space::new().width(Length::Fill),
            {
                let state_clone = state.clone();
                let mac = mac.clone();
                // this combo_box doesn't go really well with the design, but I am not writing my own dropdown menu for this
                combo_box(
                    &state.noise_control_state,
                    "Select Listening Mode",
                    Some(&state.noise_control_mode.clone()),
                    {
                        let aacp_manager = aacp_manager.clone();
                        move |selected_mode| {
                            let aacp_manager = aacp_manager.clone();
                            let selected_mode_c = selected_mode.clone();
                            run_async_in_thread(async move {
                                aacp_manager
                                    .send_control_command(
                                        ControlCommandIdentifiers::ListeningMode,
                                        &[selected_mode_c.to_byte()],
                                    )
                                    .await
                                    .expect("Failed to send Noise Control Mode command");
                            });
                            let mut state = state_clone.clone();
                            state.noise_control_mode = selected_mode.clone();
                            Message::StateChanged(mac.to_string(), DeviceState::AirPods(state))
                        }
                    },
                )
                .width(Length::from(200))
                .input_style(|theme: &Theme, _status| text_input::Style {
                    background: Background::Color(theme.palette().primary.scale_alpha(0.2)),
                    border: Border {
                        width: 1.0,
                        color: theme.palette().text.scale_alpha(0.3),
                        radius: Radius::from(4.0),
                    },
                    icon: Default::default(),
                    placeholder: theme.palette().text,
                    value: theme.palette().text,
                    selection: Default::default(),
                })
                .padding(Padding {
                    top: 5.0,
                    bottom: 5.0,
                    left: 10.0,
                    right: 10.0,
                })
                .menu_style(|theme: &Theme| menu::Style {
                    background: Background::Color(theme.palette().background),
                    border: Border {
                        width: 1.0,
                        color: theme.palette().text,
                        radius: Radius::from(4.0),
                    },
                    text_color: theme.palette().text,
                    selected_text_color: theme.palette().text,
                    selected_background: Background::Color(
                        theme.palette().primary.scale_alpha(0.3),
                    ),
                    shadow: Default::default()
                })
            }
        ]
        .align_y(Center),
    )
    .padding(Padding {
        top: 5.0,
        bottom: 5.0,
        left: 18.0,
        right: 18.0,
    })
    .style(|theme: &Theme| {
        let mut style = container::Style::default();
        style.background = Some(Background::Color(theme.palette().primary.scale_alpha(0.1)));
        let mut border = Border::default();
        border.color = theme.palette().primary.scale_alpha(0.5);
        style.border = border.rounded(16);
        style
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

    let off_listening_mode_toggle = {
        let aacp_manager_olm = aacp_manager.clone();
        let mac = mac.clone();
        container(row![
            column![
                text("Off Listening Mode").size(16),
                text("When this is on, AirPods listening modes will include an Off option. Loud sound levels are not reduced when listening mode is set to Off.").size(12).style(
                    |theme: &Theme| {
                        let mut style = text::Style::default();
                        style.color = Some(theme.palette().text.scale_alpha(0.7));
                        style
                    }
                ).width(Length::Fill)
            ].width(Length::Fill),
            toggler(state.allow_off_mode)
                .on_toggle(move |is_enabled| {
                    let aacp_manager = aacp_manager_olm.clone();
                    run_async_in_thread(
                        async move {
                            aacp_manager.send_control_command(
                                ControlCommandIdentifiers::AllowOffOption,
                                if is_enabled { &[0x01] } else { &[0x02] }
                            ).await.expect("Failed to send Off Listening Mode command");
                        }
                    );
                    let mut state = state.clone();
                    state.allow_off_mode = is_enabled;
                    Message::StateChanged(mac.to_string(), DeviceState::AirPods(state))
                })
            .spacing(0)
            .size(20)
        ]
            .align_y(Center)
            .spacing(8)
        )
            .padding(Padding{
                top: 5.0,
                bottom: 5.0,
                left: 18.0,
                right: 18.0,
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
    };

    // ----- Spatial Audio / Head Tracking -----
    // AirPods Max gate motion data behind a ProtectedAccess HID relay that no
    // host can open without Apple's authentication, so head tracking only works
    // on models that use the Pro-style 0x17 stream.
    let is_airpods_max = devices_list
        .get(mac_information.as_str())
        .and_then(|d| d.information.as_ref())
        .and_then(|info| match info {
            DeviceInformation::AirPods(a) => Some(a.model_number.clone()),
            _ => None,
        })
        .map(|m| matches!(m.as_str(), "A2096" | "A3184"))
        .unwrap_or(false);

    let spatial_header = container(text("Spatial Audio").size(18).style(|theme: &Theme| {
        let mut style = text::Style::default();
        style.color = Some(theme.palette().primary);
        style
    }))
    .padding(Padding {
        top: 5.0,
        bottom: 5.0,
        left: 18.0,
        right: 18.0,
    });

    let spatial_audio_col = if is_airpods_max {
        column![
            spatial_header,
            container(
                column![
                    text("Head tracking is not available on AirPods Max.").size(15),
                    text("Apple gates the Max's motion sensor behind an authenticated (ProtectedAccess) HID service, so head orientation can't be read on Linux. Head tracking works on AirPods Pro.")
                        .size(12)
                        .style(|theme: &Theme| {
                            let mut style = text::Style::default();
                            style.color = Some(theme.palette().text.scale_alpha(0.7));
                            style
                        }),
                ]
                .spacing(6)
                .padding(8)
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
            })
        ]
        .spacing(12)
    } else {
        spatial_audio_controls(&mac, state, aacp_manager.clone(), spatial_header)
    };

    let mut information_col = column![];
    if let Some(device) = devices_list.get(mac_information.as_str()) {
        if let Some(DeviceInformation::AirPods(ref airpods_info)) = device.information {
            let info_rows = column![
                row![
                    text("Model Number").size(16).style(|theme: &Theme| {
                        let mut style = text::Style::default();
                        style.color = Some(theme.palette().text);
                        style
                    }),
                    Space::new().width(Length::Fill),
                    text(airpods_info.model_number.clone()).size(16)
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
                    button(text(airpods_info.serial_number.clone()).size(16))
                        .style(|theme: &Theme, _status| {
                            let mut style = Style::default();
                            style.text_color = theme.palette().text;
                            style.background = Some(Background::Color(Color::TRANSPARENT));
                            style
                        })
                        .padding(0)
                        .on_press(Message::CopyToClipboard(airpods_info.serial_number.clone()))
                ],
                row![
                    text("Left Serial Number").size(16).style(|theme: &Theme| {
                        let mut style = text::Style::default();
                        style.color = Some(theme.palette().text);
                        style
                    }),
                    Space::new().width(Length::Fill),
                    button(text(airpods_info.left_serial_number.clone()).size(16))
                        .style(|theme: &Theme, _status| {
                            let mut style = Style::default();
                            style.text_color = theme.palette().text;
                            style.background = Some(Background::Color(Color::TRANSPARENT));
                            style
                        })
                        .padding(0)
                        .on_press(Message::CopyToClipboard(
                            airpods_info.left_serial_number.clone()
                        ))
                ],
                row![
                    text("Right Serial Number").size(16).style(|theme: &Theme| {
                        let mut style = text::Style::default();
                        style.color = Some(theme.palette().text);
                        style
                    }),
                    Space::new().width(Length::Fill),
                    button(text(airpods_info.right_serial_number.clone()).size(16))
                        .style(|theme: &Theme, _status| {
                            let mut style = Style::default();
                            style.text_color = theme.palette().text;
                            style.background = Some(Background::Color(Color::TRANSPARENT));
                            style
                        })
                        .padding(0)
                        .on_press(Message::CopyToClipboard(
                            airpods_info.right_serial_number.clone()
                        ))
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
                container(text("Device Information").size(18).style(|theme: &Theme| {
                    let mut style = text::Style::default();
                    style.color = Some(theme.palette().primary);
                    style
                }))
                .padding(Padding {
                    top: 5.0,
                    bottom: 5.0,
                    left: 18.0,
                    right: 18.0,
                }),
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
            error!(
                "Expected AirPodsInformation for device {}, got something else",
                mac.clone()
            );
        }
    }

    container(scrollable(column![
        rename_input,
        Space::new().height(Length::from(20)),
        listening_mode,
        Space::new().height(Length::from(20)),
        audio_settings_col,
        Space::new().height(Length::from(20)),
        spatial_audio_col,
        Space::new().height(Length::from(20)),
        off_listening_mode_toggle,
        Space::new().height(Length::from(20)),
        information_col
    ]))
    .padding(20)
    .center_x(Length::Fill)
    .height(Length::Fill)
}

/// Builds the interactive Spatial Audio / head-tracking controls (for models
/// that support the Pro-style head-tracking stream).
fn spatial_audio_controls(
    mac: &str,
    state: &AirPodsState,
    aacp_manager: Arc<AACPManager>,
    header: iced::widget::Container<'static, Message>,
) -> iced::widget::Column<'static, Message> {
    let (ht_pitch, ht_yaw, ht_roll) =
        state.head_orientation_degrees().unwrap_or((0.0, 0.0, 0.0));

    let axis = |label: &'static str, value: f32| -> iced::Element<'static, Message> {
        let v = value.clamp(-90.0, 90.0);
        row![
            text(label).size(14).width(Length::from(55)),
            progress_bar(-90.0..=90.0, v)
                .length(Length::Fill)
                .girth(Length::from(10)),
            text(format!("{:+.0}\u{00B0}", value))
                .size(14)
                .width(Length::from(50)),
        ]
        .align_y(Center)
        .spacing(10)
        .into()
    };

    let recenter_msg = {
        let mut s = state.clone();
        s.head_tracking_neutral = s.head_tracking_sample.map(|(o1, o2, o3, _, _)| (o1, o2, o3));
        Message::StateChanged(mac.to_string(), DeviceState::AirPods(s))
    };

    let aacp_manager_ht = aacp_manager.clone();
    let head_tracking_toggle = {
        let mac = mac.to_string();
        let state = state.clone();
        row![
            column![
                text("Head Tracking").size(16),
                text("Streams live head orientation from the AirPods motion sensors. Required for gestures and spatial audio.")
                    .size(12)
                    .style(|theme: &Theme| {
                        let mut style = text::Style::default();
                        style.color = Some(theme.palette().text.scale_alpha(0.7));
                        style
                    })
                    .width(Length::Fill),
            ]
            .width(Length::Fill),
            toggler(state.head_tracking_enabled)
                .on_toggle(move |is_enabled| {
                    let aacp_manager = aacp_manager_ht.clone();
                    run_async_in_thread(async move {
                        if is_enabled {
                            let _ = aacp_manager
                                .send_control_command(ControlCommandIdentifiers::OwnsConnection, &[0x01])
                                .await;
                            let _ = aacp_manager.send_start_head_tracking().await;
                        } else {
                            let _ = aacp_manager.send_stop_head_tracking().await;
                        }
                    });
                    let mut state = state.clone();
                    state.head_tracking_enabled = is_enabled;
                    Message::StateChanged(mac.to_string(), DeviceState::AirPods(state))
                })
                .spacing(0)
                .size(20),
        ]
        .align_y(Center)
        .spacing(8)
    };

    let aacp_manager_hg = aacp_manager.clone();
    let head_gestures_toggle = {
        let mac = mac.to_string();
        let state = state.clone();
        row![
            column![
                text("Head Gestures").size(16),
                text("Nod to play/pause, shake to skip to the next track. (Experimental.)")
                    .size(12)
                    .style(|theme: &Theme| {
                        let mut style = text::Style::default();
                        style.color = Some(theme.palette().text.scale_alpha(0.7));
                        style
                    })
                    .width(Length::Fill),
            ]
            .width(Length::Fill),
            toggler(state.head_gestures_enabled)
                .on_toggle(move |is_enabled| {
                    aacp_manager_hg.set_head_gestures_enabled(is_enabled);
                    let mut state = state.clone();
                    state.head_gestures_enabled = is_enabled;
                    Message::StateChanged(mac.to_string(), DeviceState::AirPods(state))
                })
                .spacing(0)
                .size(20),
        ]
        .align_y(Center)
        .spacing(8)
    };

    let live_label = if state.head_tracking_sample.is_some() {
        "Live orientation"
    } else {
        "Live orientation (enable Head Tracking and move your head)"
    };

    column![
        header,
        container(
            column![
                head_tracking_toggle,
                rule::horizontal(1).style(|theme: &Theme| rule::Style {
                    color: theme.palette().text.scale_alpha(0.2),
                    radius: Radius::from(12),
                    fill_mode: FillMode::Full,
                    snap: false
                }),
                column![
                    text(live_label).size(13).style(|theme: &Theme| {
                        let mut style = text::Style::default();
                        style.color = Some(theme.palette().text.scale_alpha(0.7));
                        style
                    }),
                    axis("Pitch", ht_pitch),
                    axis("Yaw", ht_yaw),
                    axis("Roll", ht_roll),
                    container(
                        button(text("Re-center").size(14))
                            .on_press(recenter_msg)
                            .style(|theme: &Theme, _status| {
                                let mut style = Style::default();
                                style.text_color = theme.palette().text;
                                style.background =
                                    Some(Background::Color(theme.palette().primary.scale_alpha(0.3)));
                                style.border = Border::default().rounded(8.0);
                                style
                            })
                            .padding(8)
                    )
                    .align_x(End),
                ]
                .spacing(8),
                rule::horizontal(1).style(|theme: &Theme| rule::Style {
                    color: theme.palette().text.scale_alpha(0.2),
                    radius: Radius::from(12),
                    fill_mode: FillMode::Full,
                    snap: false
                }),
                head_gestures_toggle,
            ]
            .spacing(8)
            .padding(8)
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
        })
    ]
    .spacing(12)
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
