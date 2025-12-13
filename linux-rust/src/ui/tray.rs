// use ksni::TrayMethods; // provides the spawn method

use ab_glyph::{Font, ScaleFont};
use ksni::{Icon, ToolTip};
use tokio::sync::mpsc::UnboundedSender;

use crate::bluetooth::aacp::{BatteryStatus, ControlCommandIdentifiers};
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
    pub command_tx: Option<UnboundedSender<(ControlCommandIdentifiers, Vec<u8>)>>,
    pub ui_tx: Option<UnboundedSender<BluetoothUIMessage>>,
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
                    && self.battery_l_status != Some(BatteryStatus::Disconnected) {
                        levels.push(l);
                    }
                if let Some(r) = self.battery_r
                    && self.battery_r_status != Some(BatteryStatus::Disconnected) {
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
        let app_settings_path = get_app_settings_path();
        let settings = std::fs::read_to_string(&app_settings_path)
            .ok()
            .and_then(|s| serde_json::from_str::<serde_json::Value>(&s).ok());
        let text_mode = settings.clone()
            .and_then(|v| v.get("tray_text_mode").cloned())
            .and_then(|ttm| serde_json::from_value(ttm).ok())
            .unwrap_or(false);
        let icon = generate_icon(&text, text_mode, any_bud_charging);
        vec![icon]
    }
    fn tool_tip(&self) -> ToolTip {
        let format_component = |label: &str, level: Option<u8>, status: Option<BatteryStatus>| -> String {
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

        let l = format_component("L", self.battery_l, self.battery_l_status);
        let r = format_component("R", self.battery_r, self.battery_r_status);
        let c = format_component("C", self.battery_c, self.battery_c_status);

        ToolTip {
            icon_name: "".to_string(),
            icon_pixmap: vec![],
            title: "Battery Status".to_string(),
            description: format!("{} {} {}", l, r, c),
        }
    }
    fn menu(&self) -> Vec<ksni::MenuItem<Self>> {
        use ksni::menu::*;
        let allow_off = self.allow_off_option == Some(0x01);
        let options = if allow_off {
            vec![
                ("Off", 0x01),
                ("Noise Cancellation", 0x02),
                ("Transparency", 0x03),
                ("Adaptive", 0x04),
            ]
        } else {
            vec![
                ("Noise Cancellation", 0x02),
                ("Transparency", 0x03),
                ("Adaptive", 0x04),
            ]
        };
        let selected = self.listening_mode.and_then(|mode| {
            options.iter().position(|&(_, val)| val == mode)
        }).unwrap_or(0);
        let options_clone = options.clone();
        vec![
            StandardItem {
                label: "Open Window".into(),
                icon_name: "window-new".into(),
                activate: Box::new(|this: &mut Self| {
                    if let Some(tx) = &this.ui_tx {
                        let _ = tx.send(BluetoothUIMessage::OpenWindow);
                    }
                }),
                ..Default::default()
            }.into(),
            RadioGroup {
                selected,
                select: Box::new(move |this: &mut Self, current| {
                    if let Some(tx) = &this.command_tx {
                        let value = options_clone.get(current).map(|&(_, val)| val).unwrap_or(0x02);
                        let _ = tx.send((ControlCommandIdentifiers::ListeningMode, vec![value]));
                    }
                }),
                options: options.into_iter().map(|(label, _)| RadioItem {
                    label: label.into(),
                    ..Default::default()
                }).collect(),
                ..Default::default()
            }
            .into(),
            MenuItem::Separator,
            CheckmarkItem {
                label: "Conversation Detection".into(),
                checked: self.conversation_detect_enabled.unwrap_or(false),
                enabled: self.conversation_detect_enabled.is_some(),
                activate: Box::new(|this: &mut Self| {
                    if let Some(tx) = &this.command_tx
                        && let Some(is_enabled) = this.conversation_detect_enabled {
                            let new_state = !is_enabled;
                            let value = if !new_state { 0x02 } else { 0x01 };
                            let _ = tx.send((ControlCommandIdentifiers::ConversationDetectConfig, vec![value]));
                            this.conversation_detect_enabled = Some(new_state);
                        }
                }),
                ..Default::default()
            }
            .into(),
            StandardItem {
                label: "Exit".into(),
                icon_name: "application-exit".into(),
                activate: Box::new(|_| std::process::exit(0)),
                ..Default::default()
            }
            .into(),
        ]
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
                    let angle_from_top = (angle + std::f32::consts::PI / 2.0).rem_euclid(2.0 * std::f32::consts::PI);
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