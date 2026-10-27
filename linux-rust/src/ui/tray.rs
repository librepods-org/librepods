// use ksni::TrayMethods; // provides the spawn method

use ab_glyph::{Font, ScaleFont};
use ksni::{Icon, ToolTip};
use tokio::sync::mpsc::UnboundedSender;

use crate::bluetooth::aacp::{BatteryStatus, ControlCommandIdentifiers};
use crate::devices::models::{AirPodsModel, Capability};
use crate::devices::settings::{ListeningMode, listening_modes};
use crate::prefs::load_app_settings;
use crate::ui::messages::BluetoothUIMessage;
use crate::utils::get_app_settings_path;

#[derive(Debug)]
pub struct MyTray {
    pub conversation_detect_enabled: Option<bool>,
    pub battery_headphone: Option<u8>,
    pub battery_headphone_status: Option<BatteryStatus>,
    pub battery_l: Option<u8>,
    pub battery_l_status: Option<BatteryStatus>,
    pub battery_r: Option<u8>,
    pub battery_r_status: Option<BatteryStatus>,
    pub battery_c: Option<u8>,
    pub battery_c_status: Option<BatteryStatus>,
    pub connected: bool,
    pub listening_mode: Option<u8>,
    pub allow_off_option: Option<u8>,
    /// Known once the AirPods send their Information packet.
    pub model: Option<AirPodsModel>,
    pub command_tx: Option<UnboundedSender<(ControlCommandIdentifiers, Vec<u8>)>>,
    pub ui_tx: Option<UnboundedSender<BluetoothUIMessage>>,
}

/// The listening modes the tray offers for these AirPods (the permissive set until the model
/// is known).
pub fn tray_modes(model: Option<AirPodsModel>, allow_off: Option<u8>) -> Vec<ListeningMode> {
    listening_modes(
        model.unwrap_or(AirPodsModel::Unknown),
        allow_off.map(|value| value == 0x01),
    )
}

impl MyTray {
    /// "L: 94% R: 95%⚡ C: -", as in the tooltip.
    fn battery_line(&self) -> String {
        let format_component =
            |label: &str, level: Option<u8>, status: Option<BatteryStatus>| -> String {
                match status {
                    Some(BatteryStatus::Disconnected) => format!("{}: -", label),
                    _ => {
                        let pct = level.map(|b| format!("{}%", b)).unwrap_or("?".to_string());
                        let suffix = if status == Some(BatteryStatus::Charging) {
                            "⚡"
                        } else {
                            ""
                        };
                        format!("{}: {}{}", label, pct, suffix)
                    }
                }
            };
        if let Some(level) = self.battery_headphone {
            return format_component("AirPods", Some(level), self.battery_headphone_status);
        }
        let l = format_component("L", self.battery_l, self.battery_l_status);
        let r = format_component("R", self.battery_r, self.battery_r_status);
        let c = format_component("C", self.battery_c, self.battery_c_status);
        format!("{} {} {}", l, r, c)
    }

    /// Conversation Detection is offered when the model supports it, or (before the model is
    /// known) once the AirPods have reported the setting.
    fn shows_conversation_detection(&self) -> bool {
        match self.model {
            Some(model) => model.has(Capability::ConversationAwareness),
            None => self.conversation_detect_enabled.is_some(),
        }
    }
}

impl ksni::Tray for MyTray {
    fn id(&self) -> String {
        env!("CARGO_PKG_NAME").into()
    }
    fn title(&self) -> String {
        "AirPods".into()
    }
    fn icon_pixmap(&self) -> Vec<Icon> {
        let text = {
            let mut levels: Vec<u8> = Vec::new();
            if let Some(h) = self.battery_headphone {
                if self.battery_headphone_status != Some(BatteryStatus::Disconnected) {
                    levels.push(h);
                }
            } else {
                if let Some(l) = self.battery_l
                    && self.battery_l_status != Some(BatteryStatus::Disconnected)
                {
                    levels.push(l);
                }
                if let Some(r) = self.battery_r
                    && self.battery_r_status != Some(BatteryStatus::Disconnected)
                {
                    levels.push(r);
                }
                // if let Some(c) = self.battery_c {
                //     if self.battery_c_status != Some(BatteryStatus::Disconnected) {
                //         levels.push(c);
                //     }
                // }
            }
            let min_battery = levels.iter().min().copied();
            if let Some(b) = min_battery {
                format!("{}", b)
            } else {
                "?".to_string()
            }
        };
        let any_bud_charging = matches!(self.battery_l_status, Some(BatteryStatus::Charging))
            || matches!(self.battery_r_status, Some(BatteryStatus::Charging));
        let text_mode = load_app_settings(&get_app_settings_path()).tray_text_mode;
        let icon = generate_icon(&text, text_mode, any_bud_charging);
        vec![icon]
    }
    fn tool_tip(&self) -> ToolTip {
        ToolTip {
            icon_name: "".to_string(),
            icon_pixmap: vec![],
            title: "Battery Status".to_string(),
            description: self.battery_line(),
        }
    }
    fn menu(&self) -> Vec<ksni::MenuItem<Self>> {
        use ksni::menu::*;
        let options: Vec<(&'static str, u8)> = tray_modes(self.model, self.allow_off_option)
            .into_iter()
            .map(|mode| (mode.label(), mode.byte()))
            .collect();
        let selected = self
            .listening_mode
            .and_then(|mode| options.iter().position(|&(_, val)| val == mode))
            .unwrap_or(0);
        let mut items: Vec<ksni::MenuItem<Self>> = Vec::new();
        if self.connected {
            items.push(
                StandardItem {
                    label: self.battery_line(),
                    enabled: false,
                    ..Default::default()
                }
                .into(),
            );
        }
        items.push(
            StandardItem {
                label: "Open Window".into(),
                icon_name: "window-new".into(),
                activate: Box::new(|this: &mut Self| {
                    if let Some(tx) = &this.ui_tx {
                        let _ = tx.send(BluetoothUIMessage::OpenWindow);
                    }
                }),
                ..Default::default()
            }
            .into(),
        );
        if !options.is_empty() {
            let options_clone = options.clone();
            items.push(
                RadioGroup {
                    selected,
                    select: Box::new(move |this: &mut Self, current| {
                        if let Some(tx) = &this.command_tx
                            && let Some(&(_, value)) = options_clone.get(current)
                        {
                            let _ = tx.send((ControlCommandIdentifiers::ListeningMode, vec![value]));
                        }
                    }),
                    options: options
                        .into_iter()
                        .map(|(label, _)| RadioItem {
                            label: label.into(),
                            ..Default::default()
                        })
                        .collect(),
                    ..Default::default()
                }
                .into(),
            );
        }
        if self.shows_conversation_detection() {
            items.push(MenuItem::Separator);
            items.push(
                CheckmarkItem {
                    label: "Conversation Detection".into(),
                    checked: self.conversation_detect_enabled.unwrap_or(false),
                    enabled: self.conversation_detect_enabled.is_some(),
                    activate: Box::new(|this: &mut Self| {
                        if let Some(tx) = &this.command_tx
                            && let Some(is_enabled) = this.conversation_detect_enabled
                        {
                            let new_state = !is_enabled;
                            let value = if !new_state { 0x02 } else { 0x01 };
                            let _ = tx.send((
                                ControlCommandIdentifiers::ConversationDetectConfig,
                                vec![value],
                            ));
                            this.conversation_detect_enabled = Some(new_state);
                        }
                    }),
                    ..Default::default()
                }
                .into(),
            );
        }
        items.push(MenuItem::Separator);
        items.push(
            StandardItem {
                label: "Exit".into(),
                icon_name: "application-exit".into(),
                activate: Box::new(|_| std::process::exit(0)),
                ..Default::default()
            }
            .into(),
        );
        items
    }
}

fn generate_icon(text: &str, text_mode: bool, charging: bool) -> Icon {
    use ab_glyph::{FontRef, PxScale};
    use image::{ImageBuffer, Rgba};
    use imageproc::drawing::draw_text_mut;

    let width = 64;
    let height = 64;

    let mut img = ImageBuffer::from_fn(width, height, |_, _| Rgba([0u8, 0u8, 0u8, 0u8]));

    let font_data = include_bytes!("../../assets/font/DejaVuSans.ttf");
    let font = match FontRef::try_from_slice(font_data) {
        Ok(f) => f,
        Err(_) => {
            return Icon {
                width: width as i32,
                height: height as i32,
                data: vec![0u8; (width * height * 4) as usize],
            };
        }
    };
    if !text_mode {
        let percentage = text.parse::<f32>().unwrap_or(0.0) / 100.0;

        let center_x = width as f32 / 2.0;
        let center_y = height as f32 / 2.0;
        let inner_radius = 22.0;
        let outer_radius = 28.0;

        // ring background
        for y in 0..height {
            for x in 0..width {
                let dx = x as f32 - center_x;
                let dy = y as f32 - center_y;
                let dist = (dx * dx + dy * dy).sqrt();
                if dist > inner_radius && dist <= outer_radius {
                    img.put_pixel(x, y, Rgba([128u8, 128u8, 128u8, 255u8]));
                }
            }
        }

        // ring
        for y in 0..height {
            for x in 0..width {
                let dx = x as f32 - center_x;
                let dy = y as f32 - center_y;
                let dist = (dx * dx + dy * dy).sqrt();
                if dist > inner_radius && dist <= outer_radius {
                    let angle = dy.atan2(dx);
                    let angle_from_top =
                        (angle + std::f32::consts::PI / 2.0).rem_euclid(2.0 * std::f32::consts::PI);
                    if angle_from_top <= percentage * 2.0 * std::f32::consts::PI {
                        img.put_pixel(x, y, Rgba([0u8, 255u8, 0u8, 255u8]));
                    }
                }
            }
        }
        if charging {
            let emoji = "⚡";
            let scale = PxScale::from(48.0);
            let color = Rgba([0u8, 255u8, 0u8, 255u8]);
            let scaled_font = font.as_scaled(scale);
            let mut emoji_width = 0.0;
            for c in emoji.chars() {
                let glyph_id = font.glyph_id(c);
                emoji_width += scaled_font.h_advance(glyph_id);
            }
            let x = ((width as f32 - emoji_width) / 2.0).max(0.0) as i32;
            let y = ((height as f32 - scale.y) / 2.0).max(0.0) as i32;
            draw_text_mut(&mut img, color, x, y, scale, &font, emoji);
        }
    } else {
        // battery text
        let scale = PxScale::from(48.0);
        let color = if charging {
            Rgba([0u8, 255u8, 0u8, 255u8])
        } else {
            Rgba([255u8, 255u8, 255u8, 255u8])
        };

        let scaled_font = font.as_scaled(scale);
        let mut text_width = 0.0;
        for c in text.chars() {
            let glyph_id = font.glyph_id(c);
            text_width += scaled_font.h_advance(glyph_id);
        }
        let x = ((width as f32 - text_width) / 2.0).max(0.0) as i32;
        let y = ((height as f32 - scale.y) / 2.0).max(0.0) as i32;

        draw_text_mut(&mut img, color, x, y, scale, &font, text);
    }

    let mut data = Vec::with_capacity((width * height * 4) as usize);
    for pixel in img.pixels() {
        data.push(pixel[3]);
        data.push(pixel[0]);
        data.push(pixel[1]);
        data.push(pixel[2]);
    }

    Icon {
        width: width as i32,
        height: height as i32,
        data,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::devices::models::AirPodsModel;
    use crate::devices::settings::ListeningMode::*;

    #[test]
    fn tray_modes_follow_model() {
        assert_eq!(tray_modes(None, None), vec![NoiseCancellation, Transparency, Adaptive]);
        assert_eq!(
            tray_modes(Some(AirPodsModel::AirPodsPro1), None),
            vec![Off, NoiseCancellation, Transparency]
        );
        assert_eq!(
            tray_modes(Some(AirPodsModel::AirPodsPro2UsbC), Some(0x01)),
            vec![Off, NoiseCancellation, Transparency, Adaptive]
        );
        assert_eq!(
            tray_modes(Some(AirPodsModel::AirPodsPro2UsbC), Some(0x02)),
            vec![NoiseCancellation, Transparency, Adaptive]
        );
        assert!(tray_modes(Some(AirPodsModel::AirPods2), Some(0x01)).is_empty());
    }
}
