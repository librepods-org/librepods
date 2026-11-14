use crate::bluetooth::aacp::{AACPManager, ControlCommandIdentifiers};
use iced::Alignment::End;
use iced::border::Radius;
use iced::overlay::menu;
use iced::widget::button::Style;
use iced::widget::rule::FillMode;
use iced::widget::{
    Space, button, column, combo_box, container, row, rule, scrollable, text, text_input,
    toggler,
};
use iced::{Background, Border, Center, Color, Element, Length, Padding, Theme};
use log::error;
use std::collections::HashMap;
use std::sync::Arc;
use std::thread;
use tokio::runtime::Runtime;
// use crate::bluetooth::att::ATTManager;
use crate::devices::enums::{
    AirPodsNoiseControlMode, AirPodsState, DeviceData, DeviceInformation, DeviceState,
};
use crate::devices::models::{Capability, supports};
use crate::ui::window::{Message, forget_device_button};

pub fn airpods_view<'a>(
    mac: &'a str,
    devices_list: &HashMap<String, DeviceData>,
    state: &'a AirPodsState,
    aacp_manager: Arc<AACPManager>,
    // att_manager: Arc<ATTManager>
) -> iced::widget::Container<'a, Message> {
    let mac = mac.to_string();
    // order: name, noise control, press and hold config, call controls (not sure if why it might be needed, adding it just in case), audio (personalized volume, conversational awareness, adaptive audio slider), connection settings, microphone, head gestures (not adding this), off listening mode, device information

    // listening mode settings are disabled if the model is known not to have them
    let has_listening_mode = supports(state.model, Capability::ListeningMode) != Some(false);

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
            if !has_listening_mode {
                // a combo_box can't be disabled, a text_input without on_input is
                let disabled: Element<'a, Message> = text_input("Not supported", "")
                    .width(Length::from(200))
                    .padding(Padding {
                        top: 5.0,
                        bottom: 5.0,
                        left: 10.0,
                        right: 10.0,
                    })
                    .style(|theme: &Theme, _status| text_input::Style {
                        background: Background::Color(theme.palette().primary.scale_alpha(0.05)),
                        border: Border {
                            width: 1.0,
                            color: theme.palette().text.scale_alpha(0.1),
                            radius: Radius::from(4.0),
                        },
                        icon: Default::default(),
                        placeholder: theme.palette().text.scale_alpha(0.4),
                        value: theme.palette().text.scale_alpha(0.4),
                        selection: Default::default(),
                    })
                    .into();
                disabled
            } else {
                let state_clone = state.clone();
                let mac = mac.clone();
                // this combo_box doesn't go really well with the design, but I am not writing my own dropdown menu for this
                let enabled: Element<'a, Message> = combo_box(
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
                    shadow: Default::default(),
                })
                .into();
                enabled
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

    let auto_connect_toggle = {
        let mac = mac.clone();
        container(row![
            column![
                text("Auto-connect").size(16),
                text("Automatically reconnect when AirPods become available.").size(12).style(
                    |theme: &Theme| {
                        let mut style = text::Style::default();
                        style.color = Some(theme.palette().text.scale_alpha(0.7));
                        style
                    }
                ).width(Length::Fill)
            ].width(Length::Fill),
            toggler(state.auto_connect)
                .on_toggle(move |is_enabled| {
                    Message::AutoConnectChanged(mac.clone(), is_enabled)
                })
                .spacing(0)
                .size(20)
        ]
            .align_y(Center)
            .spacing(8)
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
            })
    };

    let connection_settings_col = column![
        container(
            text("Connection").size(18).style(|theme: &Theme| {
                let mut style = text::Style::default();
                style.color = Some(theme.palette().primary);
                style
            })
        )
        .padding(Padding {
            top: 5.0,
            bottom: 5.0,
            left: 18.0,
            right: 18.0,
        }),
        auto_connect_toggle,
        Space::new().height(Length::from(10)),
        forget_device_button(&mac).width(Length::Fill)
    ];

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
                        {
                            let t = toggler(state.personalized_volume_enabled.unwrap_or(false))
                                .spacing(0)
                                .size(20);
                            if state.personalized_volume_enabled.is_some() { t.on_toggle({
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
                                    state.personalized_volume_enabled = Some(is_enabled);
                                    Message::StateChanged(mac, DeviceState::AirPods(state))
                                }
                            }) } else { t }
                        }
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
                        {
                          let t = toggler(state.conversation_awareness_enabled.unwrap_or(false))
                            .spacing(0)
                            .size(20);
                          if state.conversation_awareness_enabled.is_some() {
                            t.on_toggle(move |is_enabled| {
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
                              state.conversation_awareness_enabled = Some(is_enabled);
                              Message::StateChanged(mac_audio.to_string(), DeviceState::AirPods(state))
                            })
                          } else { t }
                        }
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
            {
              let t = toggler(state.allow_off_mode.unwrap_or(false))
                  .spacing(0)
                  .size(20);
              if state.allow_off_mode.is_some() && has_listening_mode {
                t.on_toggle(move |is_enabled| {
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
                    state.allow_off_mode = Some(is_enabled);
                    Message::StateChanged(mac.to_string(), DeviceState::AirPods(state))
                })
              } else { t }
            }
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

    let one_bud_anc_toggle = {
        let aacp_manager_oba = aacp_manager.clone();
        let mac = mac.clone();
        container(row![
            column![
                text("Noise Cancellation with One AirPod").size(16),
                text("Allow noise cancellation when only one AirPod is in your ear.").size(12).style(
                    |theme: &Theme| {
                        let mut style = text::Style::default();
                        style.color = Some(theme.palette().text.scale_alpha(0.7));
                        style
                    }
                ).width(Length::Fill)
            ].width(Length::Fill),
            {
              let t = toggler(state.one_bud_anc_enabled.unwrap_or(false))
                  .spacing(0)
                  .size(20);
              if state.one_bud_anc_enabled.is_some() && has_listening_mode {
                t.on_toggle(move |is_enabled| {
                    let aacp_manager = aacp_manager_oba.clone();
                    run_async_in_thread(
                        async move {
                            aacp_manager.send_control_command(
                                ControlCommandIdentifiers::OneBudAncMode,
                                if is_enabled { &[0x01] } else { &[0x02] }
                            ).await.expect("Failed to send One Bud ANC Mode command");
                        }
                    );
                    let mut state = state.clone();
                    state.one_bud_anc_enabled = Some(is_enabled);
                    Message::StateChanged(mac.to_string(), DeviceState::AirPods(state))
                })
              } else { t }
            }
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

    let press_hold_col = {
        let mask = state.press_hold_modes.unwrap_or(0);
        let selected = mask.count_ones();
        let mut modes_row = row![].spacing(8);
        for mode in AirPodsNoiseControlMode::available(state.allow_off_mode, state.model) {
            let is_on = mask & mode.config_bit() != 0;
            let enabled = has_listening_mode;
            let mut mode_button = button(text(mode.to_string()).size(14))
                .padding(Padding {
                    top: 6.0,
                    bottom: 6.0,
                    left: 14.0,
                    right: 14.0,
                })
                .style(move |theme: &Theme, _status| {
                    let palette = theme.palette();
                    let alpha = if enabled { 1.0 } else { 0.4 };
                    let mut style = Style::default();
                    let mut border = Border::default();
                    if is_on {
                        style.background =
                            Some(Background::Color(palette.primary.scale_alpha(alpha)));
                        border.color = palette.text.scale_alpha(alpha);
                    } else {
                        style.background =
                            Some(Background::Color(palette.primary.scale_alpha(0.1 * alpha)));
                        border.color = palette.primary.scale_alpha(0.3 * alpha);
                    }
                    border.width = 1.0;
                    style.border = border.rounded(12);
                    style.text_color = palette.text.scale_alpha(alpha);
                    style
                });
            // like on iOS, the last two modes can't be deselected
            let locked = !enabled || (is_on && selected == 2);
            if !locked {
                let aacp_manager = aacp_manager.clone();
                let new_mask = AirPodsNoiseControlMode::toggle_press_hold(mask, &mode, !is_on);
                // a cycle needs at least two modes, until then it is only a draft
                if new_mask.count_ones() >= 2 {
                    mode_button = mode_button.on_press_with({
                        let mac = mac.clone();
                        move || {
                            let aacp_manager = aacp_manager.clone();
                            run_async_in_thread(async move {
                                aacp_manager
                                    .send_control_command(
                                        ControlCommandIdentifiers::ListeningModeConfigs,
                                        &[new_mask],
                                    )
                                    .await
                                    .expect("Failed to send Listening Mode Configs command");
                            });
                            Message::PressHoldModesChanged(mac.to_string(), new_mask)
                        }
                    });
                } else {
                    mode_button = mode_button
                        .on_press(Message::PressHoldModesChanged(mac.to_string(), new_mask));
                }
            }
            modes_row = modes_row.push(mode_button);
        }
        let modes_col = modes_row.wrap();
        let description = if has_listening_mode && selected < 2 {
            "These AirPods do not report this setting. Select at least two modes to set it."
        } else {
            "Listening modes that pressing and holding the stem switches between."
        };
        column![
            container(
                text("Press and Hold").size(18).style(|theme: &Theme| {
                    let mut style = text::Style::default();
                    style.color = Some(theme.palette().primary);
                    style
                })
            )
            .padding(Padding {
                top: 5.0,
                bottom: 5.0,
                left: 18.0,
                right: 18.0,
            }),
            container(
                column![
                    text(description).size(12).style(|theme: &Theme| {
                        let mut style = text::Style::default();
                        style.color = Some(theme.palette().text.scale_alpha(0.7));
                        style
                    }),
                    modes_col
                ]
                .spacing(8)
            )
            .padding(Padding {
                top: 10.0,
                bottom: 10.0,
                left: 18.0,
                right: 18.0,
            })
            .width(Length::Fill)
            .style(|theme: &Theme| {
                let mut style = container::Style::default();
                style.background =
                    Some(Background::Color(theme.palette().primary.scale_alpha(0.1)));
                let mut border = Border::default();
                border.color = theme.palette().primary.scale_alpha(0.5);
                style.border = border.rounded(16);
                style
            })
        ]
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

    let content = column![
        rename_input,
        Space::new().height(Length::from(20)),
        listening_mode,
        Space::new().height(Length::from(20)),
        press_hold_col,
        Space::new().height(Length::from(20)),
        one_bud_anc_toggle,
        Space::new().height(Length::from(20)),
        audio_settings_col,
        Space::new().height(Length::from(20)),
        off_listening_mode_toggle,
        Space::new().height(Length::from(20)),
        connection_settings_col,
        Space::new().height(Length::from(20)),
        information_col
    ];

    // the page is longer than the default window
    container(scrollable(content.padding(20)))
    .center_x(Length::Fill)
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
