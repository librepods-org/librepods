use iced::widget::{column, container, image, row, text, Space};
use iced::{Alignment, Element, Length, Padding, Color, Background, Border};
use crate::ui::window::Message;
use std::sync::Arc;

pub fn popup_view(
    name: String,
    frame: usize,
    frames: Arc<Vec<iced::widget::image::Handle>>,
    battery_l: Option<u8>,
    battery_r: Option<u8>,
    battery_c: Option<u8>,
    charging_l: bool,
    charging_r: bool,
    charging_c: bool,
) -> Element<'static, Message> {
    let img = if frame < frames.len() {
        image(frames[frame].clone())
            .width(Length::Fill)
            .content_fit(iced::ContentFit::Contain)
    } else {
        image(frames[0].clone())
            .width(Length::Fill)
            .content_fit(iced::ContentFit::Contain)
    };

    let format_batt = |b: Option<u8>, charging: bool| -> String {
        match b {
            Some(v) => format!("{}%{}", v, if charging { " \u{26A1}" } else { "" }),
            None => "--%".to_string(),
        }
    };

    let battery_item = |label: &'static str, battery: Option<u8>, charging: bool| {
        column![
            text(label).size(12).color(Color::from_rgb(0.4, 0.4, 0.4)),
            text(format_batt(battery, charging)).size(16).color(Color::BLACK),
        ].align_x(Alignment::Center).spacing(2)
    };

    let battery_row = row![
        battery_item("Left", battery_l, charging_l),
        Space::new().width(30),
        battery_item("Right", battery_r, charging_r),
        Space::new().width(30),
        battery_item("Case", battery_c, charging_c),
    ]
    .align_y(Alignment::Center);

    let inner_card = container(
        column![
            text(name).size(22).color(Color::BLACK),
            Space::new().height(10),
            container(img).height(140).center_x(Length::Fill),
            Space::new().height(10),
            container(battery_row)
                .padding(Padding::from([10, 20]))
                .style(|_| container::Style {
                    background: Some(Background::Color(Color::from_rgba(0.0, 0.0, 0.0, 0.05))),
                    border: Border {
                        color: Color::from_rgba(0.0, 0.0, 0.0, 0.1),
                        width: 1.0,
                        radius: 16.0.into(),
                    },
                    ..Default::default()
                })
        ]
        .align_x(Alignment::Center)
        .padding(20)
    )
    .width(360)
    .style(|_| container::Style {
        background: Some(Background::Color(Color::WHITE)),
        border: Border {
            color: Color::from_rgba(0.0, 0.0, 0.0, 0.15),
            width: 1.0,
            radius: 28.0.into(),
        },
        shadow: iced::Shadow {
            color: Color::from_rgba(0.0, 0.0, 0.0, 0.2),
            offset: iced::Vector::new(0.0, 10.0),
            blur_radius: 30.0,
        },
        ..Default::default()
    });

    container(inner_card)
        .width(Length::Fill)
        .height(Length::Fill)
        .center_x(Length::Fill)
        .center_y(Length::Fill)
        .style(|_| container::Style {
            background: Some(Background::Color(Color::TRANSPARENT)),
            ..Default::default()
        })
        .into()
}
