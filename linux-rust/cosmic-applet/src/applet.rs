use crate::ipc::{self, Battery, Status};
use cosmic::app::{Core, Task};
use cosmic::applet::{menu_button, padded_control};
use cosmic::iced::window::Id;
use cosmic::iced::{Alignment, Length, Rectangle, Subscription};
use cosmic::surface::action::{app_popup, destroy_popup};
use cosmic::widget::{Space, button, column, divider, icon, row, space, text, toggler};
use cosmic::Element;

pub const APP_ID: &str = "me.kavishdevar.librepods.CosmicApplet";

const HEADPHONES_ICON: &str = "audio-headphones-symbolic";

/// Listening modes as understood by the AirPods (see `ListeningMode` in the app).
const MODES: [(u8, &str); 4] = [
    (0x01, "Off"),
    (0x02, "Noise Cancellation"),
    (0x03, "Transparency"),
    (0x04, "Adaptive"),
];

#[derive(Default)]
pub struct Applet {
    core: Core,
    popup: Option<Id>,
    /// `None` while LibrePods isn't running.
    status: Option<Status>,
}

#[derive(Debug, Clone)]
pub enum Message {
    Ipc(ipc::Event),
    PopupClosed(Id),
    Surface(cosmic::surface::Action<Message>),
    SetListeningMode(u8),
    SetConversationDetect(bool),
    OpenWindow,
}

impl cosmic::Application for Applet {
    type Executor = cosmic::executor::Default;
    type Flags = ();
    type Message = Message;
    const APP_ID: &'static str = APP_ID;

    fn core(&self) -> &Core {
        &self.core
    }

    fn core_mut(&mut self) -> &mut Core {
        &mut self.core
    }

    fn init(core: Core, _flags: ()) -> (Self, Task<Message>) {
        (
            Applet {
                core,
                ..Default::default()
            },
            Task::none(),
        )
    }

    fn on_close_requested(&self, id: Id) -> Option<Message> {
        Some(Message::PopupClosed(id))
    }

    fn subscription(&self) -> Subscription<Message> {
        Subscription::run(ipc::status_stream).map(Message::Ipc)
    }

    fn update(&mut self, message: Message) -> Task<Message> {
        match message {
            Message::Ipc(ipc::Event::Status(status)) => self.status = status,
            Message::PopupClosed(id) => {
                if self.popup == Some(id) {
                    self.popup = None;
                }
            }
            Message::Surface(a) => return cosmic::task::message(cosmic::Action::Surface(a)),
            Message::SetListeningMode(mode) => {
                ipc::send(&format!("listening_mode {}", mode));
            }
            Message::SetConversationDetect(enabled) => {
                ipc::send(&format!("conversation_detect {}", u8::from(enabled)));
            }
            Message::OpenWindow => {
                ipc::open_window();
                if let Some(id) = self.popup.take() {
                    return cosmic::task::message(cosmic::Action::Surface(destroy_popup(id)));
                }
            }
        }
        Task::none()
    }

    fn view(&self) -> Element<'_, Message> {
        let have_popup = self.popup;
        let on_press = move |offset: cosmic::iced::Vector, bounds: Rectangle| {
            if let Some(id) = have_popup {
                Message::Surface(destroy_popup(id))
            } else {
                Message::Surface(app_popup::<Applet>(
                    |_| Default::default(),
                    move |state: &mut Applet| {
                        let new_id = Id::unique();
                        state.popup = Some(new_id);
                        let mut settings = state.core.applet.get_popup_settings(
                            state.core.main_window_id().unwrap(),
                            new_id,
                            None,
                            None,
                            None,
                        );
                        settings.positioner.anchor_rect = Rectangle {
                            x: (bounds.x - offset.x) as i32,
                            y: (bounds.y - offset.y) as i32,
                            width: bounds.width as i32,
                            height: bounds.height as i32,
                        };
                        settings
                    },
                    Some(Box::new(|state: &Applet| {
                        Element::from(state.core.applet.popup_container(state.popup_view()))
                            .map(cosmic::Action::App)
                    })),
                ))
            }
        };

        // Panel button: headphones icon, plus the lowest battery % when connected
        // on a horizontal panel.
        let label = self
            .status
            .as_ref()
            .filter(|s| s.connected && self.core.applet.is_horizontal())
            .and_then(Status::min_level)
            .map(|l| format!("{}%", l));
        let btn = match label {
            Some(label) => {
                let size = self.core.applet.suggested_size(true).0;
                let (pad_major, pad_minor) = self.core.applet.suggested_padding(true);
                button::custom(
                    row![
                        icon::from_name(HEADPHONES_ICON).symbolic(true).size(size),
                        self.core.applet.text(label),
                    ]
                    .spacing(4)
                    .align_y(Alignment::Center),
                )
                .padding([pad_minor, pad_major])
                .class(cosmic::theme::Button::AppletIcon)
            }
            None => self.core.applet.icon_button(HEADPHONES_ICON),
        }
        .on_press_with_rectangle(on_press);

        let tooltip = match &self.status {
            None => "LibrePods is not running".to_string(),
            Some(s) if !s.connected => "AirPods not connected".to_string(),
            Some(s) => battery_summary(s),
        };
        let content = self.core.applet.applet_tooltip::<Message>(
            btn,
            tooltip,
            self.popup.is_some(),
            Message::Surface,
            None,
        );
        // Let the panel size the applet to its content: with the battery label the
        // button is wider than a plain icon, and a fixed-size window clips it.
        self.core.applet.autosize_window(content).into()
    }

    fn view_window(&self, _id: Id) -> Element<'_, Message> {
        "".into()
    }

    fn style(&self) -> Option<cosmic::iced::theme::Style> {
        Some(cosmic::applet::style())
    }
}

impl Applet {
    fn popup_view(&self) -> Element<'_, Message> {
        let mut content = column![].padding([8, 0]).spacing(4);

        let Some(status) = self.status.as_ref() else {
            return content
                .push(padded_control(text::body("LibrePods is not running")))
                .push(padded_control(divider::horizontal::default()))
                .push(menu_button(text::body("Start LibrePods")).on_press(Message::OpenWindow))
                .into();
        };

        if !status.connected {
            return content
                .push(padded_control(text::heading("AirPods")))
                .push(padded_control(text::body("Not connected")))
                .push(padded_control(divider::horizontal::default()))
                .push(menu_button(text::body("Open LibrePods")).on_press(Message::OpenWindow))
                .into();
        }

        content = content.push(padded_control(text::heading("AirPods")));
        let parts = [
            ("Headphones", status.headphone),
            ("Left", status.left),
            ("Right", status.right),
            ("Case", status.case),
        ];
        for (name, battery) in parts {
            if let Some(b) = battery {
                content = content.push(padded_control(
                    row![
                        text::body(name),
                        space::horizontal(),
                        text::body(format_battery(b, name == "Case")),
                    ]
                    .align_y(Alignment::Center),
                ));
            }
        }

        if let Some(current) = status.listening_mode {
            content = content
                .push(padded_control(divider::horizontal::default()))
                .push(padded_control(text::caption_heading("Noise Control")));
            for (mode, name) in MODES {
                if mode == 0x01 && !status.allow_off {
                    continue;
                }
                let selected = mode == current;
                content = content.push(
                    menu_button(
                        row![
                            text::body(name),
                            space::horizontal(),
                            if selected {
                                Element::from(
                                    icon::from_name("object-select-symbolic").symbolic(true).size(16),
                                )
                            } else {
                                Element::from(Space::new().width(Length::Fixed(16.0)))
                            },
                        ]
                        .align_y(Alignment::Center),
                    )
                    .on_press(Message::SetListeningMode(mode)),
                );
            }
        }

        if let Some(enabled) = status.conversation_detect {
            content = content
                .push(padded_control(divider::horizontal::default()))
                .push(padded_control(
                    row![
                        text::body("Conversation Awareness").width(Length::Fill),
                        toggler(enabled).on_toggle(Message::SetConversationDetect),
                    ]
                    .align_y(Alignment::Center),
                ));
        }

        content
            .push(padded_control(divider::horizontal::default()))
            .push(menu_button(text::body("Open LibrePods")).on_press(Message::OpenWindow))
            .into()
    }
}

fn format_battery(b: Battery, is_case: bool) -> String {
    if b.charging {
        format!("{}% (charging)", b.level)
    } else if !b.connected && !is_case {
        format!("{}% (in case)", b.level)
    } else {
        format!("{}%", b.level)
    }
}

/// Short one-line tooltip, e.g. "L 93% · R 93% · Case 31%". Charging details are
/// in the popup; long tooltips get clipped by the panel.
fn battery_summary(s: &Status) -> String {
    let parts: Vec<String> = [
        ("", s.headphone),
        ("L ", s.left),
        ("R ", s.right),
        ("Case ", s.case),
    ]
    .iter()
    .filter_map(|(n, b)| b.map(|b| format!("{}{}%", n, b.level)))
    .collect();
    if parts.is_empty() {
        "AirPods connected".to_string()
    } else {
        parts.join(" · ")
    }
}
