use crate::bluetooth::att::{ATTHandles, ATTManager};
use crate::devices::enums::{DeviceData, DeviceInformation, DeviceState, NothingState};
use crate::ui::window::Message;
use iced::border::Radius;
use iced::overlay::menu;
use iced::widget::combo_box;
use iced::widget::text_input;
use iced::widget::{Space, column, container, row, text};
use iced::{Background, Border, Length, Theme};
use std::collections::HashMap;
use std::sync::Arc;
use std::thread;
use tokio::runtime::Runtime;

pub fn nothing_view<'a>(
    mac: &'a str,
    devices_list: &HashMap<String, DeviceData>,
    state: &'a NothingState,
    att_manager: Arc<ATTManager>,
) -> iced::widget::Container<'a, Message> {
    let mut information_col = iced::widget::column![];
    let mac = mac.to_string();
    if let Some(device) = devices_list.get(mac.as_str())
        && let Some(DeviceInformation::Nothing(ref nothing_info)) = device.information
    {
        information_col = information_col
            .push(text("Device Information").size(18).style(|theme: &Theme| {
                let mut style = text::Style::default();
                style.color = Some(theme.palette().primary);
                style
            }))
            .push(Space::with_height(iced::Length::from(10)))
            .push(iced::widget::row![
                text("Serial Number").size(16).style(|theme: &Theme| {
                    let mut style = text::Style::default();
                    style.color = Some(theme.palette().text);
                    style
                }),
                Space::with_width(Length::Fill),
                text(nothing_info.serial_number.clone()).size(16)
            ])
            .push(iced::widget::row![
                text("Firmware Version").size(16).style(|theme: &Theme| {
                    let mut style = text::Style::default();
                    style.color = Some(theme.palette().text);
                    style
                }),
                Space::with_width(Length::Fill),
                text(nothing_info.firmware_version.clone()).size(16)
            ]);
    }

    let noise_control_mode = container(
        row![
            text("Noise Control Mode").size(16).style(|theme: &Theme| {
                let mut style = text::Style::default();
                style.color = Some(theme.palette().text);
                style
            }),
            Space::with_width(Length::Fill),
            {
                let state_clone = state.clone();
                let mac = mac.clone();
                let att_manager_clone = att_manager.clone();
                combo_box(
                    &state.anc_mode_state,
                    "Select Noise Control Mode",
                    Some(&state.anc_mode.clone()),
                    {
                        move |selected_mode| {
                            let att_manager = att_manager_clone.clone();
                            let selected_mode_c = selected_mode.clone();
                            let mac_s = mac.clone();
                            run_async_in_thread(async move {
                                if let Err(e) = att_manager
                                    .write(
                                        ATTHandles::NothingEverything,
                                        &[
                                            0x55,
                                            0x60,
                                            0x01,
                                            0x0F,
                                            0xF0,
                                            0x03,
                                            0x00,
                                            0x00,
                                            0x01,
                                            selected_mode_c.to_byte(),
                                            0x00,
                                            0x00,
                                            0x00,
                                        ],
                                    )
                                    .await
                                {
                                    log::error!(
                                        "Failed to set noise cancellation mode for device {}: {}",
                                        mac_s,
                                        e
                                    );
                                }
                            });
                            let mut state = state_clone.clone();
                            state.anc_mode = selected_mode.clone();
                            Message::StateChanged(mac.to_string(), DeviceState::Nothing(state))
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
                .padding(iced::Padding {
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
                })
            }
        ]
        .align_y(iced::Alignment::Center),
    )
    .padding(iced::Padding {
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

    container(column![
        noise_control_mode,
        Space::with_height(Length::from(20)),
        container(information_col)
            .style(|theme: &Theme| {
                let mut style = container::Style::default();
                style.background =
                    Some(Background::Color(theme.palette().primary.scale_alpha(0.1)));
                let mut border = Border::default();
                border.color = theme.palette().text;
                style.border = border.rounded(20);
                style
            })
            .padding(20)
    ])
    .padding(20)
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
