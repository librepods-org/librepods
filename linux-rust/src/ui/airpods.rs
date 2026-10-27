//! The AirPods page: every control this model supports, drawn from the catalog in
//! `devices::settings`, plus the name and the device information.

use crate::bluetooth::aacp::ControlCommandIdentifiers;
use crate::bluetooth::aacp_parse::{Accommodation, CustomEq};
use crate::devices::enums::{AirPodsState, DeviceData, DeviceInformation};
use crate::devices::models::Capability;
use crate::devices::settings::{
    ChoiceOption, ControlKind, ControlSpec, ListeningMode, Section, decode_toggle,
    default_cycle, encode_per_bud, encode_slider, encode_toggle, listening_modes, per_bud_values,
    rename_request, selected_choice, shows_custom_eq, shows_stem_actions, slider_value,
    toggle_cycle_mode, visible_controls,
};
use crate::prefs::{Bud, DevicePrefs, PressType, StemAction};
use crate::ui::window::Message;
use iced::Alignment::End;
use iced::border::Radius;
use iced::widget::button::Style;
use iced::widget::rule::FillMode;
use iced::widget::{
    Space, button, checkbox, column, container, pick_list, row, rule, scrollable, slider, text,
    text_input, toggler,
};
use iced::{Background, Border, Center, Color, Element, Length, Padding, Theme};
use log::error;
use std::collections::HashMap;

/// Catalog sections in page order.
const SECTIONS: [Section; 8] = [
    Section::Audio,
    Section::PressAndHold,
    Section::Calls,
    Section::Microphone,
    Section::Connection,
    Section::Battery,
    Section::Accessibility,
    Section::Advanced,
];

/// Press-and-hold value for a bud the AirPods haven't reported: Apple's default, Noise Control.
const DEFAULT_PRESS_AND_HOLD: u8 = 0x01;

fn section_title(section: Section) -> &'static str {
    match section {
        Section::Audio => "Audio",
        Section::PressAndHold => "Press and Hold",
        Section::Calls => "Calls",
        Section::Microphone => "Microphone",
        Section::Connection => "Connection",
        Section::Battery => "Battery",
        Section::Accessibility => "Accessibility",
        Section::Advanced => "Advanced",
    }
}

/// One pick-list entry: the label shown and the bytes it sends.
#[derive(Debug, Clone, PartialEq)]
struct ChoiceItem {
    label: &'static str,
    value: &'static [u8],
}

impl std::fmt::Display for ChoiceItem {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.label)
    }
}

impl From<&ChoiceOption> for ChoiceItem {
    fn from(option: &ChoiceOption) -> Self {
        ChoiceItem {
            label: option.label,
            value: option.value,
        }
    }
}

/// What a picker shows when the AirPods' value matches no option.
fn placeholder_for(value: Option<&[u8]>) -> String {
    match value {
        Some(value) => format!("Unknown ({})", hex::encode(value)),
        None => "Not reported".to_string(),
    }
}

pub fn airpods_view<'a>(
    mac: &'a str,
    devices_list: &HashMap<String, DeviceData>,
    state: &'a AirPodsState,
) -> iced::widget::Container<'a, Message> {
    let controls = visible_controls(state.model_or_unknown(), state.firmware_major);
    let mut page = column![name_row(mac, state)].spacing(20);
    if let Some(listening) = listening_mode_section(mac, state) {
        page = page.push(listening);
    }

    for section in SECTIONS {
        let mut rows: Vec<Element<'a, Message>> = if section == Section::PressAndHold {
            cycle_rows(mac, state)
        } else {
            Vec::new()
        };
        rows.extend(
            controls
                .iter()
                .filter(|spec| spec.section == section)
                .map(|spec| control_row(mac, spec, state)),
        );
        rows.extend(trailing_rows(mac, state, section));
        if !rows.is_empty() {
            page = page.push(if section == Section::Advanced {
                advanced_section(mac, state, rows)
            } else {
                section_card(section_title(section), rows)
            });
        }
        if section == Section::PressAndHold
            && let Some(stem) = stem_actions_section(mac, state)
        {
            page = page.push(stem);
        }
    }

    if let Some(information) = information_section(mac, devices_list) {
        page = page.push(information);
    }

    container(scrollable(container(page).padding(20).width(Length::Fill)))
        .width(Length::Fill)
        .height(Length::Fill)
}

fn name_row<'a>(mac: &'a str, state: &'a AirPodsState) -> Element<'a, Message> {
    let on_input = {
        let mac = mac.to_string();
        move |draft| Message::NameDraft(mac.clone(), draft)
    };
    let rename = rename_request(&state.device_name, &state.name_draft)
        .map(|_| Message::SubmitName(mac.to_string()));
    let input = text_input("AirPods name", &state.name_draft)
        .on_input(on_input)
        .on_submit(Message::SubmitName(mac.to_string()))
        .padding(Padding {
            top: 5.0,
            bottom: 5.0,
            left: 10.0,
            right: 10.0,
        })
        .style(|theme: &Theme, _status| text_input::Style {
            background: Background::Color(Color::TRANSPARENT),
            border: Default::default(),
            icon: Default::default(),
            placeholder: theme.palette().text.scale_alpha(0.7),
            value: theme.palette().text,
            selection: theme.palette().primary.scale_alpha(0.5),
        })
        .align_x(End);
    let rename_button = button(text("Rename").size(14))
        .on_press_maybe(rename)
        .padding(Padding {
            top: 4.0,
            bottom: 4.0,
            left: 10.0,
            right: 10.0,
        })
        .style(|theme: &Theme, _status| {
            let mut style = Style::default();
            style.text_color = theme.palette().text;
            style.background = Some(Background::Color(theme.palette().primary.scale_alpha(0.3)));
            style.border = Border::default().rounded(8.0);
            style
        });
    card(
        row![
            text("Name").size(16),
            Space::new().width(Length::Fill),
            input,
            rename_button
        ]
        .align_y(Center)
        .spacing(8),
    )
}

fn control_row<'a>(
    mac: &'a str,
    spec: &'static ControlSpec,
    state: &'a AirPodsState,
) -> Element<'a, Message> {
    let id = spec.id;
    let reported = state.controls.get(&id).map(Vec::as_slice);
    match &spec.kind {
        ControlKind::Toggle => {
            let value = reported.and_then(decode_toggle);
            // The app's own ear-detection preference stands in until the AirPods report theirs.
            let value = if id == ControlCommandIdentifiers::EarDetectionConfig {
                value.unwrap_or(state.prefs.ear_detection)
            } else {
                value.unwrap_or(false)
            };
            let mac = mac.to_string();
            toggle_row(spec.label, spec.description, value, move |on| {
                Message::SetControl(mac.clone(), id, encode_toggle(on))
            })
        }
        ControlKind::Choice(options) => {
            let items: Vec<ChoiceItem> = options.iter().map(ChoiceItem::from).collect();
            let selected = reported
                .and_then(|value| selected_choice(options, value))
                .and_then(|index| items.get(index).cloned());
            let mac = mac.to_string();
            let picker = pick_list(items, selected, move |item: ChoiceItem| {
                Message::SetControl(mac.clone(), id, item.value.to_vec())
            })
            .placeholder(placeholder_for(reported))
            .width(Length::Fixed(280.0));
            labeled_row(spec.label, spec.description, picker)
        }
        ControlKind::Slider { max, suffix } => {
            let (max, suffix) = (*max, *suffix);
            let value = reported.and_then(slider_value).map(|v| v.min(max));
            let draft_mac = mac.to_string();
            let control = slider(0..=max, value.unwrap_or(max / 2), move |v| {
                Message::ControlDraft(draft_mac.clone(), id, encode_slider(max, suffix, v))
            })
            .on_release(Message::SendControl(mac.to_string(), id))
            .width(Length::Fixed(200.0));
            let shown = value.map_or_else(|| "—".to_string(), |v| v.to_string());
            labeled_row(
                spec.label,
                spec.description,
                row![control, text(shown).size(14).width(Length::Fixed(32.0))]
                    .spacing(8)
                    .align_y(Center),
            )
        }
        ControlKind::PerBud(options) => {
            let (left, right) = reported.map(per_bud_values).unwrap_or((None, None));
            let items: Vec<ChoiceItem> = options.iter().map(ChoiceItem::from).collect();
            let item_for = |byte: Option<u8>| {
                byte.and_then(|b| options.iter().find(|option| option.value == [b].as_slice()))
                    .map(ChoiceItem::from)
            };
            let first_byte = |item: &ChoiceItem| {
                item.value.first().copied().unwrap_or(DEFAULT_PRESS_AND_HOLD)
            };
            let other_right = right.unwrap_or(DEFAULT_PRESS_AND_HOLD);
            let other_left = left.unwrap_or(DEFAULT_PRESS_AND_HOLD);
            let left_mac = mac.to_string();
            let right_mac = mac.to_string();
            let left_picker = pick_list(items.clone(), item_for(left), move |item: ChoiceItem| {
                Message::SetControl(left_mac.clone(), id, encode_per_bud(first_byte(&item), other_right))
            })
            .placeholder(placeholder_for(left.map(|b| [b]).as_ref().map(|b| b.as_slice())))
            .width(Length::Fixed(160.0));
            let right_picker = pick_list(items, item_for(right), move |item: ChoiceItem| {
                Message::SetControl(right_mac.clone(), id, encode_per_bud(other_left, first_byte(&item)))
            })
            .placeholder(placeholder_for(right.map(|b| [b]).as_ref().map(|b| b.as_slice())))
            .width(Length::Fixed(160.0));
            labeled_row(
                spec.label,
                spec.description,
                column![
                    row![text("Left").size(14).width(Length::Fixed(44.0)), left_picker].align_y(Center),
                    row![text("Right").size(14).width(Length::Fixed(44.0)), right_picker].align_y(Center),
                ]
                .spacing(6),
            )
        }
    }
}

/// The custom EQ before the AirPods report one: off and flat.
pub const DEFAULT_CUSTOM_EQ: CustomEq = CustomEq {
    enabled: false,
    low: 50,
    mid: 50,
    high: 50,
};

/// Headphone accommodation before the AirPods report it: off, every band in the middle.
pub const DEFAULT_ACCOMMODATION: Accommodation = Accommodation {
    phone: false,
    media: false,
    bands: [50.0; 8],
};

fn listening_modes_for(state: &AirPodsState) -> Vec<ListeningMode> {
    let allow_off = state
        .controls
        .get(&ControlCommandIdentifiers::AllowOffOption)
        .and_then(|value| decode_toggle(value));
    listening_modes(state.model_or_unknown(), allow_off)
}

fn current_listening_mode(state: &AirPodsState) -> Option<ListeningMode> {
    state
        .controls
        .get(&ControlCommandIdentifiers::ListeningMode)
        .and_then(|value| value.first().copied())
        .and_then(ListeningMode::from_byte)
}

fn muted_text<'a>(content: &'a str) -> iced::widget::Text<'a> {
    text(content).size(12).style(|theme: &Theme| {
        let mut style = text::Style::default();
        style.color = Some(theme.palette().text.scale_alpha(0.7));
        style
    })
}

fn listening_mode_section<'a>(mac: &'a str, state: &'a AirPodsState) -> Option<Element<'a, Message>> {
    let modes = listening_modes_for(state);
    if modes.is_empty() {
        return None;
    }
    let current = current_listening_mode(state);
    let mut buttons = row![].spacing(8);
    for mode in modes {
        let selected = current == Some(mode);
        buttons = buttons.push(
            button(text(mode.label()).size(14).width(Length::Fill).align_x(Center))
                .width(Length::Fill)
                .padding(8)
                .on_press(Message::SetControl(
                    mac.to_string(),
                    ControlCommandIdentifiers::ListeningMode,
                    vec![mode.byte()],
                ))
                .style(move |theme: &Theme, _status| {
                    let mut style = Style::default();
                    style.text_color = theme.palette().text;
                    style.background = Some(Background::Color(if selected {
                        theme.palette().primary.scale_alpha(0.6)
                    } else {
                        theme.palette().primary.scale_alpha(0.15)
                    }));
                    style.border = Border::default().rounded(12.0);
                    style
                }),
        );
    }
    let mut rows: Vec<Element<'a, Message>> = vec![buttons.into()];
    if state.model_or_unknown().has(Capability::AdaptiveAudio)
        && current == Some(ListeningMode::Adaptive)
    {
        let id = ControlCommandIdentifiers::AutoAncStrength;
        let value = state
            .controls
            .get(&id)
            .and_then(|value| slider_value(value))
            .map_or(50, |v| v.min(100));
        let draft_mac = mac.to_string();
        let control = slider(0..=100u8, value, move |v| {
            Message::ControlDraft(draft_mac.clone(), id, vec![v])
        })
        .on_release(Message::SendControl(mac.to_string(), id));
        rows.push(
            column![
                text("Adaptive noise filtering").size(16),
                row![muted_text("Let in more sound"), control, muted_text("Reduce more noise")]
                    .spacing(8)
                    .align_y(Center),
            ]
            .spacing(4)
            .into(),
        );
    }
    Some(section_card("Listening Mode", rows))
}

fn mode_description(mode: ListeningMode) -> &'static str {
    match mode {
        ListeningMode::Off => "Turns off noise management",
        ListeningMode::NoiseCancellation => "Blocks out external sounds",
        ListeningMode::Transparency => "Lets in external sounds",
        ListeningMode::Adaptive => "Dynamically adjust external noise",
    }
}

/// Which listening modes a press and hold cycles through. A mode that can't be removed
/// (only two left) can't be unticked.
fn cycle_rows<'a>(mac: &'a str, state: &'a AirPodsState) -> Vec<Element<'a, Message>> {
    let modes = listening_modes_for(state);
    if modes.is_empty() {
        return Vec::new();
    }
    let id = ControlCommandIdentifiers::ListeningModeConfigs;
    let mask = state
        .controls
        .get(&id)
        .and_then(|value| value.first().copied())
        .filter(|mask| mask & 0x0F != 0)
        .or(state.prefs.listening_mode_cycle)
        .unwrap_or_else(|| default_cycle(state.model_or_unknown()));
    let mut list = column![text("Press and hold cycles through").size(16)].spacing(6);
    for mode in modes {
        let next = toggle_cycle_mode(mask, mode);
        let on_toggle = (next != mask).then(|| {
            let mac = mac.to_string();
            move |_checked: bool| Message::SetControl(mac.clone(), id, vec![next])
        });
        list = list.push(
            row![
                checkbox(mask & mode.cycle_bit() != 0).on_toggle_maybe(on_toggle),
                column![text(mode.label()).size(14), muted_text(mode_description(mode))]
            ]
            .spacing(8)
            .align_y(Center),
        );
    }
    vec![list.into()]
}

#[derive(Debug, Clone, Copy, PartialEq)]
enum StemChoice {
    Native,
    PlayPause,
    NextTrack,
    PreviousTrack,
    CycleListeningMode,
    Command,
}

const STEM_CHOICES: [StemChoice; 6] = [
    StemChoice::Native,
    StemChoice::PlayPause,
    StemChoice::NextTrack,
    StemChoice::PreviousTrack,
    StemChoice::CycleListeningMode,
    StemChoice::Command,
];

impl std::fmt::Display for StemChoice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            StemChoice::Native => "AirPods default",
            StemChoice::PlayPause => "Play/Pause",
            StemChoice::NextTrack => "Next track",
            StemChoice::PreviousTrack => "Previous track",
            StemChoice::CycleListeningMode => "Cycle listening mode",
            StemChoice::Command => "Run command…",
        })
    }
}

fn stem_choice(action: &StemAction) -> StemChoice {
    match action {
        StemAction::Native => StemChoice::Native,
        StemAction::PlayPause => StemChoice::PlayPause,
        StemAction::NextTrack => StemChoice::NextTrack,
        StemAction::PreviousTrack => StemChoice::PreviousTrack,
        StemAction::CycleListeningMode => StemChoice::CycleListeningMode,
        StemAction::Command(_) => StemChoice::Command,
    }
}

/// The action for a picked choice; picking "Run command…" again keeps the command typed so far.
fn stem_action_for(choice: StemChoice, current: &StemAction) -> StemAction {
    match choice {
        StemChoice::Native => StemAction::Native,
        StemChoice::PlayPause => StemAction::PlayPause,
        StemChoice::NextTrack => StemAction::NextTrack,
        StemChoice::PreviousTrack => StemAction::PreviousTrack,
        StemChoice::CycleListeningMode => StemAction::CycleListeningMode,
        StemChoice::Command => match current {
            StemAction::Command(command) => StemAction::Command(command.clone()),
            _ => StemAction::Command(String::new()),
        },
    }
}

const PRESS_LABELS: [(PressType, &str); 4] = [
    (PressType::Single, "Press once"),
    (PressType::Double, "Press twice"),
    (PressType::Triple, "Press three times"),
    (PressType::Long, "Press and hold"),
];

fn bud_label(bud: Bud) -> &'static str {
    match bud {
        Bud::Left => "Left",
        Bud::Right => "Right",
    }
}

fn stem_actions_section<'a>(mac: &'a str, state: &'a AirPodsState) -> Option<Element<'a, Message>> {
    if !shows_stem_actions(state.model_or_unknown(), &state.prefs.stem_actions) {
        return None;
    }
    let mut rows: Vec<Element<'a, Message>> = Vec::new();
    for (press, label) in PRESS_LABELS {
        let picker = |bud: Bud| {
            let prefs = state.prefs.clone();
            let mac = mac.to_string();
            pick_list(
                STEM_CHOICES,
                Some(stem_choice(state.prefs.stem_actions.get(bud, press))),
                move |choice: StemChoice| {
                    let mut prefs = prefs.clone();
                    let action = stem_action_for(choice, prefs.stem_actions.get(bud, press));
                    prefs.stem_actions.set(bud, press, action);
                    Message::SetPrefs(mac.clone(), prefs)
                },
            )
            .width(Length::Fixed(200.0))
        };
        let mut entry = column![labeled_row(
            label,
            "",
            column![
                row![text("Left").size(14).width(Length::Fixed(44.0)), picker(Bud::Left)].align_y(Center),
                row![text("Right").size(14).width(Length::Fixed(44.0)), picker(Bud::Right)].align_y(Center),
            ]
            .spacing(6),
        )]
        .spacing(6);
        for bud in [Bud::Left, Bud::Right] {
            if let StemAction::Command(command) = state.prefs.stem_actions.get(bud, press) {
                let prefs = state.prefs.clone();
                let draft_mac = mac.to_string();
                let input = text_input("Command to run, then press Enter", command)
                    .on_input(move |typed| {
                        let mut prefs = prefs.clone();
                        prefs.stem_actions.set(bud, press, StemAction::Command(typed));
                        Message::PrefsDraft(draft_mac.clone(), prefs)
                    })
                    .on_submit(Message::SavePrefs(mac.to_string()))
                    .padding(6);
                entry = entry.push(
                    row![muted_text(bud_label(bud)).width(Length::Fixed(44.0)), input]
                        .spacing(8)
                        .align_y(Center),
                );
            }
        }
        rows.push(entry.into());
    }
    Some(
        column![
            heading("Stem Actions"),
            container(muted_text(
                "Presses set here are handled by this computer instead of the AirPods."
            ))
            .padding(Padding {
                top: 0.0,
                bottom: 0.0,
                left: 18.0,
                right: 18.0,
            }),
            rows_card(rows)
        ]
        .spacing(4)
        .into(),
    )
}

fn trailing_rows<'a>(mac: &'a str, state: &'a AirPodsState, section: Section) -> Vec<Element<'a, Message>> {
    match section {
        Section::Audio => {
            let mut rows = equalizer_rows(mac, state);
            rows.extend(speaking_rows(mac, state));
            rows
        }
        Section::Connection => connection_pref_rows(mac, state),
        Section::Accessibility => accommodation_rows(mac, state),
        _ => Vec::new(),
    }
}

fn equalizer_rows<'a>(mac: &'a str, state: &'a AirPodsState) -> Vec<Element<'a, Message>> {
    if !shows_custom_eq(state.firmware_major) {
        return Vec::new();
    }
    let eq = state.custom_eq.unwrap_or(DEFAULT_CUSTOM_EQ);
    let toggle_mac = mac.to_string();
    let mut rows = vec![toggle_row(
        "Equalizer",
        "A three-band equalizer applied by the AirPods. 50 is flat.",
        eq.enabled,
        move |on| Message::CustomEqDraft(toggle_mac.clone(), CustomEq { enabled: on, ..eq }),
    )];
    let bands: [(&'static str, u8, fn(&mut CustomEq, u8)); 3] = [
        ("Low", eq.low, |eq, v| eq.low = v),
        ("Mid", eq.mid, |eq, v| eq.mid = v),
        ("High", eq.high, |eq, v| eq.high = v),
    ];
    for (label, value, set) in bands {
        let draft_mac = mac.to_string();
        let control = slider(0..=100u8, value, move |v| {
            let mut changed = eq;
            set(&mut changed, v);
            Message::CustomEqDraft(draft_mac.clone(), changed)
        })
        .on_release(Message::SendCustomEq(mac.to_string()))
        .width(Length::Fixed(200.0));
        rows.push(labeled_row(
            label,
            "",
            row![control, text(value.to_string()).size(14).width(Length::Fixed(32.0))]
                .spacing(8)
                .align_y(Center),
        ));
    }
    rows
}

/// A toggle for one of this app's own preferences (saved at once, nothing sent).
fn prefs_toggle<'a>(
    mac: &'a str,
    prefs: &DevicePrefs,
    label: &'a str,
    description: &'a str,
    value: bool,
    set: fn(&mut DevicePrefs, bool),
) -> Element<'a, Message> {
    let prefs = prefs.clone();
    let mac = mac.to_string();
    toggle_row(label, description, value, move |on| {
        let mut prefs = prefs.clone();
        set(&mut prefs, on);
        Message::SetPrefs(mac.clone(), prefs)
    })
}

/// A 0–100 % slider for one of this app's own preferences, saved on release.
fn prefs_percent<'a>(
    mac: &'a str,
    prefs: &DevicePrefs,
    label: &'a str,
    value: u8,
    set: fn(&mut DevicePrefs, u8),
) -> Element<'a, Message> {
    let value = value.min(100);
    let draft = prefs.clone();
    let draft_mac = mac.to_string();
    let control = slider(0..=100u8, value, move |v| {
        let mut prefs = draft.clone();
        set(&mut prefs, v);
        Message::PrefsDraft(draft_mac.clone(), prefs)
    })
    .on_release(Message::SavePrefs(mac.to_string()))
    .width(Length::Fixed(200.0));
    labeled_row(
        label,
        "",
        row![control, text(format!("{}%", value)).size(14).width(Length::Fixed(40.0))]
            .spacing(8)
            .align_y(Center),
    )
}

fn speaking_rows<'a>(mac: &'a str, state: &'a AirPodsState) -> Vec<Element<'a, Message>> {
    if !state.model_or_unknown().has(Capability::ConversationAwareness) {
        return Vec::new();
    }
    let prefs = &state.prefs;
    vec![
        column![
            text("When you speak").size(16),
            muted_text("What this computer does when Conversation Awareness hears you talking.")
        ]
        .into(),
        prefs_percent(mac, prefs, "Lower media to", prefs.conversation_volume, |p, v| {
            p.conversation_volume = v
        }),
        prefs_percent(
            mac,
            prefs,
            "Lower further to",
            prefs.conversation_reduced_volume,
            |p, v| p.conversation_reduced_volume = v,
        ),
        prefs_toggle(
            mac,
            prefs,
            "Relative to current volume",
            "Lower to a share of your volume instead of a fixed level.",
            prefs.conversation_relative,
            |p, on| p.conversation_relative = on,
        ),
        prefs_toggle(
            mac,
            prefs,
            "Pause media instead",
            "Pause playback while you speak instead of lowering it.",
            prefs.conversation_pause_music,
            |p, on| p.conversation_pause_music = on,
        ),
    ]
}

fn connection_pref_rows<'a>(mac: &'a str, state: &'a AirPodsState) -> Vec<Element<'a, Message>> {
    let prefs = &state.prefs;
    vec![
        prefs_toggle(
            mac,
            prefs,
            "Disconnect audio when not wearing",
            "Turns the AirPods' audio off when both are out of your ears, and back on when you put one in.",
            prefs.disconnect_when_not_wearing,
            |p, on| p.disconnect_when_not_wearing = on,
        ),
        prefs_toggle(
            mac,
            prefs,
            "Connect when nearby (BLE)",
            "Connect these AirPods to this computer when they are out of the case and not connected to anything.",
            prefs.auto_connect,
            |p, on| p.auto_connect = on,
        ),
        prefs_toggle(
            mac,
            prefs,
            "Take over when media starts playing here",
            "Switch the AirPods to this computer when you start playing something with an AirPod in your ear.",
            prefs.takeover_on_media_start,
            |p, on| p.takeover_on_media_start = on,
        ),
        prefs_toggle(
            mac,
            prefs,
            "…even if they are playing music for another device",
            "",
            prefs.takeover_from_music,
            |p, on| p.takeover_from_music = on,
        ),
        prefs_toggle(
            mac,
            prefs,
            "…even during a call on another device",
            "",
            prefs.takeover_from_call,
            |p, on| p.takeover_from_call = on,
        ),
    ]
}

fn accommodation_rows<'a>(mac: &'a str, state: &'a AirPodsState) -> Vec<Element<'a, Message>> {
    let current = state.accommodation.unwrap_or(DEFAULT_ACCOMMODATION);
    let phone_mac = mac.to_string();
    let media_mac = mac.to_string();
    let mut rows = vec![
        column![
            text("Headphone Accommodation (experimental)").size(16),
            muted_text(
                "Boosts soft sounds for phone calls and media. Nothing is sent until you change something here."
            )
        ]
        .into(),
        toggle_row("Phone calls", "", current.phone, move |on| {
            Message::AccommodationDraft(phone_mac.clone(), Accommodation { phone: on, ..current })
        }),
        toggle_row("Media", "", current.media, move |on| {
            Message::AccommodationDraft(media_mac.clone(), Accommodation { media: on, ..current })
        }),
    ];
    if current.phone || current.media {
        for (index, band) in current.bands.iter().copied().enumerate() {
            let draft_mac = mac.to_string();
            let control = slider(0.0..=100.0f32, band.clamp(0.0, 100.0), move |v| {
                let mut changed = current;
                if let Some(slot) = changed.bands.get_mut(index) {
                    *slot = v;
                }
                Message::AccommodationDraft(draft_mac.clone(), changed)
            })
            .step(1.0f32)
            .on_release(Message::SendAccommodation(mac.to_string()))
            .width(Length::Fixed(200.0));
            rows.push(
                row![
                    text(format!("Band {}", index + 1)).size(14).width(Length::Fill),
                    control,
                    text(format!("{:.0}", band)).size(14).width(Length::Fixed(32.0))
                ]
                .spacing(8)
                .align_y(Center)
                .into(),
            );
        }
    }
    rows
}

fn label_column<'a>(label: &'a str, description: &'a str) -> iced::widget::Column<'a, Message> {
    let mut labels = column![text(label).size(16)];
    if !description.is_empty() {
        labels = labels.push(
            text(description)
                .size(12)
                .style(|theme: &Theme| {
                    let mut style = text::Style::default();
                    style.color = Some(theme.palette().text.scale_alpha(0.7));
                    style
                })
                .width(Length::Fill),
        );
    }
    labels.width(Length::Fill)
}

fn toggle_row<'a>(
    label: &'a str,
    description: &'a str,
    value: bool,
    on_toggle: impl Fn(bool) -> Message + 'a,
) -> Element<'a, Message> {
    row![
        label_column(label, description),
        toggler(value).on_toggle(on_toggle).spacing(0).size(20)
    ]
    .align_y(Center)
    .spacing(8)
    .into()
}

fn labeled_row<'a>(
    label: &'a str,
    description: &'a str,
    control: impl Into<Element<'a, Message>>,
) -> Element<'a, Message> {
    row![label_column(label, description), control.into()]
        .align_y(Center)
        .spacing(12)
        .into()
}

fn divider<'a>() -> Element<'a, Message> {
    rule::horizontal(1)
        .style(|theme: &Theme| rule::Style {
            color: theme.palette().text.scale_alpha(0.2),
            radius: Radius::from(12),
            fill_mode: FillMode::Full,
            snap: false,
        })
        .into()
}

fn heading<'a>(title: &'a str) -> Element<'a, Message> {
    container(text(title).size(18).style(|theme: &Theme| {
        let mut style = text::Style::default();
        style.color = Some(theme.palette().primary);
        style
    }))
    .padding(Padding {
        top: 5.0,
        bottom: 5.0,
        left: 18.0,
        right: 18.0,
    })
    .into()
}

fn card<'a>(content: impl Into<Element<'a, Message>>) -> Element<'a, Message> {
    container(content)
        .padding(Padding {
            top: 5.0,
            bottom: 5.0,
            left: 10.0,
            right: 10.0,
        })
        .width(Length::Fill)
        .style(|theme: &Theme| {
            let mut style = container::Style::default();
            style.background = Some(Background::Color(theme.palette().primary.scale_alpha(0.1)));
            let mut border = Border::default();
            border.color = theme.palette().primary.scale_alpha(0.5);
            style.border = border.rounded(16);
            style
        })
        .into()
}

fn rows_card<'a>(rows: Vec<Element<'a, Message>>) -> Element<'a, Message> {
    let mut body = column![].spacing(8).padding(8);
    for (index, row) in rows.into_iter().enumerate() {
        if index > 0 {
            body = body.push(divider());
        }
        body = body.push(row);
    }
    card(body)
}

fn section_card<'a>(title: &'a str, rows: Vec<Element<'a, Message>>) -> Element<'a, Message> {
    column![heading(title), rows_card(rows)].spacing(4).into()
}

fn advanced_section<'a>(
    mac: &'a str,
    state: &'a AirPodsState,
    rows: Vec<Element<'a, Message>>,
) -> Element<'a, Message> {
    let toggle = button(text(if state.show_advanced { "Hide" } else { "Show" }).size(14))
        .on_press(Message::ToggleAdvanced(mac.to_string()))
        .padding(Padding {
            top: 4.0,
            bottom: 4.0,
            left: 10.0,
            right: 10.0,
        })
        .style(|theme: &Theme, _status| {
            let mut style = Style::default();
            style.text_color = theme.palette().text;
            style.background = Some(Background::Color(theme.palette().primary.scale_alpha(0.2)));
            style.border = Border::default().rounded(8.0);
            style
        });
    let mut section = column![
        row![
            heading(section_title(Section::Advanced)),
            Space::new().width(Length::Fill),
            toggle
        ]
        .align_y(Center)
    ]
    .spacing(4);
    if state.show_advanced {
        section = section
            .push(
                container(text("These may not be supported by your AirPods.").size(12)).padding(
                    Padding {
                        top: 0.0,
                        bottom: 0.0,
                        left: 18.0,
                        right: 18.0,
                    },
                ),
            )
            .push(rows_card(rows));
    }
    section.into()
}

fn information_section<'a>(
    mac: &str,
    devices_list: &HashMap<String, DeviceData>,
) -> Option<Element<'a, Message>> {
    let device = devices_list.get(mac)?;
    let Some(DeviceInformation::AirPods(info)) = &device.information else {
        if device.information.is_some() {
            error!("Expected AirPodsInformation for device {}, got something else", mac);
        }
        return None;
    };
    let plain = |label: &'static str, value: String| -> Element<'a, Message> {
        row![text(label).size(16), Space::new().width(Length::Fill), text(value).size(16)].into()
    };
    let copyable = |label: &'static str, value: String| -> Element<'a, Message> {
        row![
            text(label).size(16),
            Space::new().width(Length::Fill),
            button(text(value.clone()).size(16))
                .style(|theme: &Theme, _status| {
                    let mut style = Style::default();
                    style.text_color = theme.palette().text;
                    style.background = Some(Background::Color(Color::TRANSPARENT));
                    style
                })
                .padding(0)
                .on_press(Message::CopyToClipboard(value))
        ]
        .into()
    };
    let rows = column![
        plain("Model Number", info.model_number.clone()),
        plain("Manufacturer", info.manufacturer.clone()),
        copyable("Serial Number", info.serial_number.clone()),
        copyable("Left Serial Number", info.left_serial_number.clone()),
        copyable("Right Serial Number", info.right_serial_number.clone()),
        plain("Version 1", info.version1.clone()),
        plain("Version 2", info.version2.clone()),
        plain("Version 3", info.version3.clone()),
    ]
    .spacing(4)
    .padding(8);
    Some(column![heading("Device Information"), card(rows)].spacing(4).into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bluetooth::aacp::ControlCommandIdentifiers as Id;
    use crate::devices::models::AirPodsModel;
    use crate::prefs::DevicePrefs;

    const MAC: &str = "AA:BB:CC:DD:EE:01";

    fn sample_state() -> AirPodsState {
        AirPodsState {
            device_name: "Test AirPods Pro".to_string(),
            name_draft: "Test AirPods Pro".to_string(),
            battery: vec![],
            // Includes values the catalog doesn't know: call config 00 01, mic mode 07.
            controls: HashMap::from([
                (Id::CallManagementConfig, vec![0x00, 0x01]),
                (Id::MicMode, vec![0x07]),
                (Id::ChimeVolume, vec![0x50, 0x50]),
                (Id::ClickHoldMode, vec![0x01, 0x05]),
                (Id::ListeningMode, vec![0x02]),
            ]),
            model: None,
            firmware_major: None,
            custom_eq: None,
            accommodation: None,
            prefs: DevicePrefs::default(),
            show_advanced: false,
        }
    }

    #[test]
    fn view_builds_for_every_model() {
        use AirPodsModel::*;
        let models = [
            None,
            Some(AirPods1),
            Some(AirPods2),
            Some(AirPods3),
            Some(AirPods4),
            Some(AirPods4Anc),
            Some(AirPods5),
            Some(AirPods5Wcc),
            Some(AirPodsPro1),
            Some(AirPodsPro2Lightning),
            Some(AirPodsPro2UsbC),
            Some(AirPodsPro3),
            Some(AirPodsMaxLightning),
            Some(AirPodsMaxUsbC),
            Some(AirPodsMax2),
            Some(Unknown),
        ];
        for model in models {
            for show_advanced in [false, true] {
                let mut state = sample_state();
                state.model = model;
                state.firmware_major = Some(9);
                state.show_advanced = show_advanced;
                let _ = airpods_view(MAC, &HashMap::new(), &state);
            }
        }
    }

    #[test]
    fn view_builds_with_every_section_in_use() {
        use crate::bluetooth::aacp_parse::{Accommodation, CustomEq};
        use crate::prefs::StemAction;
        let mut configured = sample_state();
        configured.model = Some(AirPodsModel::AirPodsPro1);
        configured.firmware_major = Some(9);
        configured.prefs.stem_actions.left_long = StemAction::Command("x".to_string());
        configured.prefs.stem_actions.right_double = StemAction::NextTrack;
        configured.custom_eq = Some(CustomEq { enabled: true, low: 20, mid: 50, high: 80 });
        configured.accommodation = Some(Accommodation { phone: true, media: false, bands: [50.0; 8] });
        configured.controls.insert(Id::ListeningModeConfigs, vec![0x07]);
        let _ = airpods_view(MAC, &HashMap::new(), &configured);

        let mut adaptive = sample_state();
        adaptive.model = Some(AirPodsModel::AirPodsPro2UsbC);
        adaptive.firmware_major = Some(8);
        adaptive.controls.insert(Id::ListeningMode, vec![0x04]);
        adaptive.controls.insert(Id::AutoAncStrength, vec![30]);
        let _ = airpods_view(MAC, &HashMap::new(), &adaptive);
    }
}
