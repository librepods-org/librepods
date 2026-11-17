//! Reusable UI building blocks: cards, setting rows, segmented buttons, chips,
//! summary tiles, battery rings, page headers and sidebar items.
//!
//! The layout (sidebar with app header and status footer, overview "hero" card
//! with battery rings, grouped settings cards, segmented controls) is inspired by
//! the MousePods desktop app by Matteo Gaudioso (https://github.com/starman-tech/mousepods).
//! No MousePods code is used.

use crate::bluetooth::aacp::{BatteryInfo, BatteryStatus};
use iced::font::Weight;
use iced::widget::{Space, button, canvas, column, container, row, rule, stack, text};
use iced::{
    Alignment, Background, Border, Color, Element, Font, Length, Point, Radians, Rectangle,
    Renderer, Theme, mouse,
};

pub const RADIUS: f32 = 14.0;

/// Glyphs from the bundled SF Pro font (SF Symbols private-use code points).
pub mod glyph {
    // Picked by rendering the font's glyphs; see the battery-ring and sidebar uses.
    pub const AIRPODS: &str = "\u{100E38}";
    pub const HEADPHONES: &str = "\u{100EB9}";
    pub const GEAR: &str = "\u{100EBC}";
    pub const BOLT: &str = "\u{1002E6}";
}

pub fn font(weight: Weight) -> Font {
    Font {
        weight,
        ..Font::with_name("SF Pro Text")
    }
}

/// Emphasis font. The bundled SF Pro is a single static Regular (400) face, so
/// asking for Semibold/Bold makes the text engine substitute another family
/// (wrong-looking digits in the battery rings). Stay on the shipped weight and
/// get emphasis from size and color instead.
pub fn bold() -> Font {
    font(Weight::Normal)
}

/// Secondary text color.
pub fn muted(theme: &Theme) -> Color {
    theme.extended_palette().background.base.text.scale_alpha(0.62)
}

pub fn muted_text(theme: &Theme) -> text::Style {
    text::Style {
        color: Some(muted(theme)),
    }
}

pub fn card_style(theme: &Theme) -> container::Style {
    let p = theme.extended_palette();
    container::Style::default()
        .background(p.background.weak.color)
        .border(Border::default().rounded(RADIUS))
        .color(p.background.weak.text)
}

pub fn card<'a, M: 'a>(content: impl Into<Element<'a, M>>) -> container::Container<'a, M> {
    container(content).style(card_style).width(Length::Fill)
}

fn divider<'a, M: 'a>() -> Element<'a, M> {
    rule::horizontal(1)
        .style(|theme: &Theme| rule::Style {
            color: theme.extended_palette().background.base.text.scale_alpha(0.08),
            radius: 0.0.into(),
            fill_mode: rule::FillMode::Full,
            snap: true,
        })
        .into()
}

/// A titled group of rows inside one card, separated by thin dividers.
pub fn section<'a, M: 'a>(title: &'a str, rows: Vec<Element<'a, M>>) -> Element<'a, M> {
    let mut body = column![];
    let count = rows.len();
    for (i, r) in rows.into_iter().enumerate() {
        body = body.push(r);
        if i + 1 < count {
            body = body.push(divider());
        }
    }
    column![text(title).size(15).font(bold()), card(body)]
        .spacing(8)
        .into()
}

/// Title + optional subtitle on the left, a control on the right.
pub fn setting_row<'a, M: 'a>(
    title: impl text::IntoFragment<'a>,
    subtitle: Option<String>,
    control: impl Into<Element<'a, M>>,
) -> Element<'a, M> {
    let mut labels = column![text(title).size(15)].spacing(2).width(Length::Fill);
    if let Some(sub) = subtitle {
        labels = labels.push(text(sub).size(12).style(muted_text));
    }
    row![labels, control.into()]
        .spacing(16)
        .align_y(Alignment::Center)
        .padding([12, 16])
        .into()
}

/// A row of mutually exclusive buttons, like a GNOME/COSMIC "linked" control.
pub fn segmented<'a, T, M>(
    options: Vec<(T, String)>,
    selected: Option<T>,
    on_select: impl Fn(T) -> M,
) -> Element<'a, M>
where
    T: Copy + PartialEq + 'a,
    M: Clone + 'a,
{
    let buttons = options.into_iter().map(|(value, label)| {
        let is_selected = selected == Some(value);
        button(text(label).size(13).align_x(Alignment::Center).width(Length::Fill))
            .width(Length::Fill)
            .padding([6, 10])
            .style(move |theme: &Theme, status| {
                let p = theme.extended_palette();
                let (bg, fg) = if is_selected {
                    (Some(p.primary.base.color), p.primary.base.text)
                } else if matches!(status, button::Status::Hovered) {
                    (Some(p.background.strong.color), p.background.base.text)
                } else {
                    (None, p.background.base.text)
                };
                button::Style {
                    background: bg.map(Background::Color),
                    text_color: fg,
                    border: Border::default().rounded(RADIUS - 5.0),
                    ..Default::default()
                }
            })
            .on_press(on_select(value))
            .into()
    });
    container(row(buttons).spacing(2))
        .padding(3)
        .style(|theme: &Theme| {
            container::Style::default()
                .background(theme.extended_palette().background.base.color)
                .border(Border::default().rounded(RADIUS - 2.0))
        })
        .into()
}

/// A small rounded label, optionally with a colored status dot.
pub fn chip<'a, M: 'a>(label: String, dot: Option<Color>) -> Element<'a, M> {
    let mut content = row![].spacing(6).align_y(Alignment::Center);
    if let Some(color) = dot {
        content = content.push(
            container(Space::new().width(8).height(8)).style(move |_: &Theme| {
                container::Style::default()
                    .background(color)
                    .border(Border::default().rounded(4))
            }),
        );
    }
    content = content.push(text(label).size(12));
    container(content)
        .padding([4, 10])
        .style(|theme: &Theme| {
            container::Style::default()
                .background(theme.extended_palette().background.strong.color)
                .border(Border::default().rounded(999))
        })
        .into()
}

/// A summary tile: small label over a value.
pub fn tile<'a, M: 'a>(label: &'a str, value: String) -> Element<'a, M> {
    card(
        column![
            text(label).size(12).style(muted_text),
            text(value).size(15).font(bold()),
        ]
        .spacing(2),
    )
    .padding([12, 16])
    .into()
}

/// Ring gauge for one battery component.
struct Ring {
    fraction: f32,
    color_kind: RingColor,
    dimmed: bool,
}

#[derive(Clone, Copy)]
enum RingColor {
    Good,
    Low,
    Critical,
    Unknown,
}

impl<M> canvas::Program<M> for Ring {
    type State = ();

    fn draw(
        &self,
        _state: &(),
        renderer: &Renderer,
        theme: &Theme,
        bounds: Rectangle,
        _cursor: mouse::Cursor,
    ) -> Vec<canvas::Geometry> {
        let p = theme.extended_palette();
        let mut frame = canvas::Frame::new(renderer, bounds.size());
        let stroke_width = 6.0;
        let center = Point::new(bounds.width / 2.0, bounds.height / 2.0);
        let radius = bounds.width.min(bounds.height) / 2.0 - stroke_width / 2.0;

        let track = canvas::Path::circle(center, radius);
        frame.stroke(
            &track,
            canvas::Stroke::default()
                .with_width(stroke_width)
                .with_color(p.background.strong.color),
        );

        if self.fraction > 0.0 {
            let mut color = match self.color_kind {
                RingColor::Good => p.success.base.color,
                RingColor::Low => p.warning.base.color,
                RingColor::Critical => p.danger.base.color,
                RingColor::Unknown => p.background.strong.color,
            };
            if self.dimmed {
                color = color.scale_alpha(0.45);
            }
            let start = -std::f32::consts::FRAC_PI_2;
            let arc = canvas::path::Arc {
                center,
                radius,
                start_angle: Radians(start),
                end_angle: Radians(start + self.fraction * std::f32::consts::TAU),
            };
            frame.stroke(
                &canvas::Path::new(|b| b.arc(arc)),
                canvas::Stroke::default()
                    .with_width(stroke_width)
                    .with_color(color)
                    .with_line_cap(canvas::LineCap::Round),
            );
        }
        vec![frame.into_geometry()]
    }
}

/// Battery ring with the percentage inside and a label underneath. `None` shows
/// an empty ring with "–" (unknown). A bud resting in the case is dimmed, and so
/// is a `last_known` value (e.g. the case while its lid is closed).
pub fn battery_ring<'a, M: 'a>(
    label: &'a str,
    battery: Option<&BatteryInfo>,
    last_known: bool,
) -> Element<'a, M> {
    const SIZE: f32 = 64.0;
    let (fraction, color_kind, dimmed, center_label) = match battery {
        Some(b) => {
            let kind = match b.level {
                0..=10 => RingColor::Critical,
                11..=20 => RingColor::Low,
                _ => RingColor::Good,
            };
            let charging = if b.status == BatteryStatus::Charging && !last_known {
                glyph::BOLT
            } else {
                ""
            };
            (
                b.level as f32 / 100.0,
                kind,
                last_known || (b.status == BatteryStatus::Disconnected && label != "Case"),
                format!("{}%{}", b.level, charging),
            )
        }
        None => (0.0, RingColor::Unknown, false, "–".to_string()),
    };
    let caption = match battery {
        Some(_) if last_known => format!("{label} · last known"),
        Some(b) if b.status == BatteryStatus::Disconnected && label != "Case" => {
            format!("{label} · in case")
        }
        _ => label.to_string(),
    };
    column![
        stack![
            canvas(Ring {
                fraction,
                color_kind,
                dimmed,
            })
            .width(SIZE)
            .height(SIZE),
            container(text(center_label).size(13).font(bold()))
                .center_x(SIZE)
                .center_y(SIZE),
        ],
        text(caption).size(12).style(muted_text),
    ]
    .spacing(6)
    .align_x(Alignment::Center)
    .into()
}

/// Page title bar with an optional element on the right.
pub fn page_header<'a, M: 'a>(
    title: String,
    right: Option<Element<'a, M>>,
) -> Element<'a, M> {
    let mut bar = row![text(title).size(20).font(bold()), Space::new().width(Length::Fill)]
        .align_y(Alignment::Center)
        .padding([14, 24]);
    if let Some(r) = right {
        bar = bar.push(r);
    }
    column![bar, divider()].into()
}

/// Sidebar navigation item.
pub fn nav_item<'a, M: Clone + 'a>(
    icon: &'a str,
    label: String,
    sublabel: Option<String>,
    selected: bool,
    on_press: M,
) -> Element<'a, M> {
    let mut labels = column![text(label).size(14)].spacing(1);
    if let Some(sub) = sublabel {
        labels = labels.push(text(sub).size(11).style(muted_text));
    }
    button(
        row![text(icon).size(16).width(22), labels]
            .spacing(8)
            .align_y(Alignment::Center),
    )
    .width(Length::Fill)
    .padding([8, 10])
    .style(move |theme: &Theme, status| {
        let p = theme.extended_palette();
        let bg = if selected {
            Some(p.background.strong.color)
        } else if matches!(status, button::Status::Hovered) {
            Some(p.background.weak.color)
        } else {
            None
        };
        button::Style {
            background: bg.map(Background::Color),
            text_color: p.background.base.text,
            border: Border::default().rounded(10),
            ..Default::default()
        }
    })
    .on_press(on_press)
    .into()
}
