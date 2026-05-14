use crate::bluetooth::aacp::{
    AACPEvent, BatteryComponent, BatteryStatus, ControlCommandIdentifiers,
};
use crate::bluetooth::managers::DeviceManagers;
use crate::devices::enums::{
    AirPodsNoiseControlMode, AirPodsState, DeviceData, DeviceState, DeviceType, NothingAncMode,
    NothingState,
};
use crate::ui::airpods::airpods_view;
use crate::ui::messages::BluetoothUIMessage;
use crate::ui::nothing::nothing_view;
use crate::utils::{MyTheme, get_app_settings_path, get_devices_path};
use bluer::{Address};
use iced::border::Radius;
use iced::overlay::menu;
use iced::widget::button::Style;
use iced::widget::rule::FillMode;
use iced::widget::{
    Space, button, column, combo_box, container, pane_grid, row, rule, scrollable, text,
    text_input, toggler
};
use iced::{Background, Border, Center, Element, Font, Length, Padding, Size, Subscription, Task, Theme, daemon, window, Settings};
use log::{debug, error, info};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::mpsc::UnboundedReceiver;
use tokio::sync::{Mutex, RwLock};

pub fn start_ui(
    ui_rx: UnboundedReceiver<BluetoothUIMessage>,
    start_minimized: bool,
    device_managers: Arc<RwLock<HashMap<String, DeviceManagers>>>,
    // stem_control: Arc<AtomicBool>,
) -> iced::Result {
    let ui_rx = Arc::new(Mutex::new(ui_rx));

    // not sure if this is a good idea
    daemon(
        move || {
            App::new(
                Arc::clone(&ui_rx),
                start_minimized,
                Arc::clone(&device_managers),
                // Arc::clone(&stem_control),
            )
        },
        App::update,
        App::view,
    )
    .subscription(App::subscription)
    .theme(App::theme)
    .title(App::title)
    .settings(Settings {
        id: Some("librepods".to_string()),
        fonts: vec![include_bytes!("../../assets/font/sf_pro.otf").as_slice().into()],
        default_font: Font::with_name("SF Pro Text"),
        ..Settings::default()
    })
    .run()
}

pub struct App {
    window: Option<window::Id>,
    panes: pane_grid::State<Pane>,
    selected_tab: Tab,
    theme_state: combo_box::State<MyTheme>,
    selected_theme: MyTheme,
    ui_rx: Arc<Mutex<UnboundedReceiver<BluetoothUIMessage>>>,
    bluetooth_state: BluetoothState,
    paired_devices: HashMap<String, Address>,
    device_states: HashMap<String, DeviceState>,
    device_managers: Arc<RwLock<HashMap<String, DeviceManagers>>>,
    pending_add_device: Option<(String, Address)>,
    device_type_state: combo_box::State<DeviceType>,
    selected_device_type: Option<DeviceType>,
    tray_text_mode: bool,
    stem_control: bool,
    show_popup: bool,

    // Popup State
    popup_window: Option<window::Id>,
    popup_frame: usize,
    popup_last_tick: Option<std::time::Instant>,
    popup_suppressed_until: Option<std::time::Instant>,
    is_in_ear: bool,
    popup_mac: Option<String>,
    popup_battery_l: Option<u8>,
    popup_battery_r: Option<u8>,
    popup_battery_c: Option<u8>,
    popup_charging_l: bool,
    popup_charging_r: bool,
    popup_charging_c: bool,
    popup_name: String,
    main_window: Option<window::Id>,
    animation_frames: Arc<Vec<iced::widget::image::Handle>>,
}

pub struct BluetoothState {
    connected_devices: Vec<String>,
}

impl BluetoothState {
    pub fn new() -> Self {
        Self {
            connected_devices: Vec::new(),
        }
    }
}

#[derive(Debug, Clone)]
pub enum Message {
    WindowOpened(window::Id),
    WindowClosed(window::Id),
    Resized(pane_grid::ResizeEvent),
    SelectTab(Tab),
    ThemeSelected(MyTheme),
    CopyToClipboard(String),
    BluetoothMessage(BluetoothUIMessage),
    // ShowNewDialogTab,
    GotPairedDevices(HashMap<String, Address>),
    StartAddDevice(String, Address),
    SelectDeviceType(DeviceType),
    ConfirmAddDevice,
    CancelAddDevice,
    StateChanged(String, DeviceState),
    TrayTextModeChanged(bool), // yes, I know I should add all settings to a struct, but I'm lazy
    StemControlChanged(bool),
    ShowPopupChanged(bool),
    Tick,
    ClosePopup,
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub enum Tab {
    Device(String),
    Settings,
    AddDevice,
}

#[derive(Clone, Copy)]
pub enum Pane {
    Sidebar,
    Content,
}

impl App {
    pub fn new(
        ui_rx: Arc<Mutex<UnboundedReceiver<BluetoothUIMessage>>>,
        start_minimized: bool,
        device_managers: Arc<RwLock<HashMap<String, DeviceManagers>>>,
        // stem_control: Arc<AtomicBool>,
    ) -> (Self, Task<Message>) {
        let (mut panes, first_pane) = pane_grid::State::new(Pane::Sidebar);
        let split = panes.split(pane_grid::Axis::Vertical, first_pane, Pane::Content);
        panes.resize(split.unwrap().1, 0.2);


        let wait_task = Task::perform(wait_for_message(Arc::clone(&ui_rx)), |msg| msg);

        let (window, open_task) = if start_minimized {
            (None, Task::none())
        } else {
            let mut settings = window::Settings::default();
            settings.min_size = Some(Size::new(400.0, 300.0));
            settings.icon = window::icon::from_file("assets/icon.png").ok();
            let (id, open) = window::open(settings);
            (Some(id), open.map(Message::WindowOpened))
        };

        let app_settings_path = get_app_settings_path();
        let settings = std::fs::read_to_string(&app_settings_path)
            .ok()
            .and_then(|s| serde_json::from_str::<serde_json::Value>(&s).ok());
        let selected_theme = settings
            .clone()
            .and_then(|v| v.get("theme").cloned())
            .and_then(|t| serde_json::from_value(t).ok())
            .unwrap_or(MyTheme::Dark);
        let tray_text_mode = settings
            .clone()
            .and_then(|v| v.get("tray_text_mode").cloned())
            .and_then(|ttm| serde_json::from_value(ttm).ok())
            .unwrap_or(false);
        let stem_control = settings
            .clone()
            .and_then(|v| v.get("stem_control").cloned())
            .and_then(|s| serde_json::from_value(s).ok())
            .unwrap_or(false);
        let show_popup = settings
            .clone()
            .and_then(|v| v.get("show_popup").cloned())
            .and_then(|s| serde_json::from_value(s).ok())
            .unwrap_or(true);

        let bluetooth_state = BluetoothState::new();

        // let dummy_device_state = DeviceState::AirPods(AirPodsState {
        //     conversation_awareness_enabled: false,
        // });
        // let device_states = HashMap::from([
        //     ("28:2D:7F:C2:05:5B".to_string(), dummy_device_state),
        // ]);

        let device_states = HashMap::new();

        let mut frames = Vec::new();
        for i in 1..=180 {
            frames.push(iced::widget::image::Handle::from_path(format!("assets/animations/popup/frame_{:03}.png", i)));
        }        let ui_rx_clone = Arc::clone(&ui_rx);
        let (main_window, tasks) = if start_minimized {
            (None, vec![Task::perform(wait_for_message(ui_rx_clone), |msg| msg)])
        } else {
            let (id, open) = window::open(window::Settings {
                size: Size::new(800.0, 600.0),
                ..Default::default()
            });
            (Some(id), vec![Task::perform(wait_for_message(ui_rx_clone), |msg| msg), open.map(Message::WindowOpened)])
        };

        (
            Self {
                window: main_window,
                main_window,
                panes,
                selected_tab: Tab::Device("none".to_string()),
                theme_state: combo_box::State::new(vec![
                    MyTheme::Light,
                    MyTheme::Dark,
                    MyTheme::Dracula,
                    MyTheme::Nord,
                    MyTheme::SolarizedLight,
                    MyTheme::SolarizedDark,
                    MyTheme::GruvboxLight,
                    MyTheme::GruvboxDark,
                    MyTheme::CatppuccinLatte,
                    MyTheme::CatppuccinFrappe,
                    MyTheme::CatppuccinMacchiato,
                    MyTheme::CatppuccinMocha,
                    MyTheme::TokyoNight,
                    MyTheme::TokyoNightStorm,
                    MyTheme::TokyoNightLight,
                    MyTheme::KanagawaWave,
                    MyTheme::KanagawaDragon,
                    MyTheme::KanagawaLotus,
                    MyTheme::Moonfly,
                    MyTheme::Nightfly,
                    MyTheme::Oxocarbon,
                    MyTheme::Ferra,
                ]),
                selected_theme,
                ui_rx,
                bluetooth_state,
                paired_devices: HashMap::new(),
                device_states,
                pending_add_device: None,
                device_type_state: combo_box::State::new(vec![DeviceType::Nothing]),
                selected_device_type: None,
                device_managers,
                tray_text_mode,
                stem_control,
                show_popup,
                popup_window: None,
                popup_frame: 0,
                popup_last_tick: None,
                popup_suppressed_until: None,
                is_in_ear: false,
                popup_mac: None,
                popup_battery_l: None,
                popup_battery_r: None,
                popup_battery_c: None,
                popup_charging_l: false,
                popup_charging_r: false,
                popup_charging_c: false,
                popup_name: "AirPods".to_string(),
                animation_frames: Arc::new(frames),
            },
            Task::batch(vec![open_task, wait_task]),
        )
    }

    fn title(&self, _id: window::Id) -> String {
        "LibrePods".to_string()
    }

    fn update(&mut self, message: Message) -> Task<Message> {
        match message {
            Message::WindowOpened(id) => {
                if self.window.is_none() {
                    self.window = Some(id);
                } else if self.main_window.is_none() {
                    self.main_window = Some(id);
                }
                Task::none()
            }
            Message::WindowClosed(id) => {
                if self.window == Some(id) {
                    self.window = None;
                }
                if self.main_window == Some(id) {
                    self.main_window = None;
                }
                if self.popup_window == Some(id) {
                    self.popup_window = None;
                }
                Task::none()
            }
            Message::Resized(event) => {
                self.panes.resize(event.split, event.ratio);
                Task::none()
            }
            Message::SelectTab(tab) => {
                self.selected_tab = tab;
                Task::none()
            }
            Message::ThemeSelected(theme) => {
                self.selected_theme = theme;
                let app_settings_path = get_app_settings_path();
                let settings = serde_json::json!({
                    "theme": self.selected_theme,
                    "tray_text_mode": self.tray_text_mode,
                    "stem_control": self.stem_control,
                    "show_popup": self.show_popup,
                });
                debug!(
                    "Writing settings to {}: {}",
                    app_settings_path.to_str().unwrap(),
                    settings
                );
                std::fs::write(app_settings_path, settings.to_string()).ok();
                Task::none()
            }
            Message::CopyToClipboard(data) => iced::clipboard::write(data),
            Message::BluetoothMessage(ui_message) => {
                match ui_message {
                    BluetoothUIMessage::NoOp => {
                        let ui_rx = Arc::clone(&self.ui_rx);

                        Task::perform(wait_for_message(ui_rx), |msg| msg)
                    }
                    BluetoothUIMessage::OpenWindow => {
                        let ui_rx = Arc::clone(&self.ui_rx);
                        let wait_task = Task::perform(wait_for_message(ui_rx), |msg| msg);
                        debug!("Opening main window...");
                        if let Some(window_id) = self.main_window {
                            Task::batch(vec![window::gain_focus(window_id), wait_task])
                        } else {
                            let mut settings = window::Settings::default();
                            settings.min_size = Some(Size::new(400.0, 300.0));
                            settings.icon = window::icon::from_file("assets/icon.png").ok();
                            let (new_window_task, open_task) = window::open(settings);
                            self.main_window = Some(new_window_task);
                            Task::batch(vec![open_task.map(Message::WindowOpened), wait_task])
                        }
                    }
                    BluetoothUIMessage::DeviceConnected(mac) => {
                        let ui_rx = Arc::clone(&self.ui_rx);
                        let wait_task = Task::perform(wait_for_message(ui_rx), |msg| msg);
                        debug!(
                            "Device connected: {}. Adding to connected devices list",
                            mac
                        );
                        let mut already_connected = false;
                        for device in &self.bluetooth_state.connected_devices {
                            if device == &mac {
                                already_connected = true;
                                break;
                            }
                        }
                        if !already_connected {
                            self.bluetooth_state.connected_devices.push(mac.clone());
                        }

                        // self.device_states.insert(mac.clone(), DeviceState::AirPods(AirPodsState {
                        //     conversation_awareness_enabled: false,
                        // }));

                        let type_ = {
                            let devices_json = std::fs::read_to_string(get_devices_path())
                                .unwrap_or_else(|e| {
                                    error!("Failed to read devices file: {}", e);
                                    "{}".to_string()
                                });
                            let devices_list: HashMap<String, DeviceData> =
                                serde_json::from_str(&devices_json).unwrap_or_else(|e| {
                                    error!("Deserialization failed: {}", e);
                                    HashMap::new()
                                });
                            devices_list.get(&mac).map(|d| d.type_.clone())
                        };
                        match type_ {
                            Some(DeviceType::AirPods) => {
                                let device_managers = self.device_managers.blocking_read();
                                let device_manager = device_managers.get(&mac).unwrap();
                                let aacp_manager = device_manager.get_aacp().unwrap();
                                let aacp_manager_state = aacp_manager.state.clone();
                                let state = aacp_manager_state.blocking_lock();
                                debug!("AACP manager found for AirPods device {}", mac);
                                let device_name = {
                                    let devices_json = std::fs::read_to_string(get_devices_path())
                                        .unwrap_or_else(|e| {
                                            error!("Failed to read devices file: {}", e);
                                            "{}".to_string()
                                        });
                                    let devices_list: HashMap<String, DeviceData> =
                                        serde_json::from_str(&devices_json).unwrap_or_else(|e| {
                                            error!("Deserialization failed: {}", e);
                                            HashMap::new()
                                        });
                                    devices_list
                                        .get(&mac)
                                        .map(|d| d.name.clone())
                                        .unwrap_or_else(|| "Unknown Device".to_string())
                                };
                                self.device_states.insert(mac.clone(), DeviceState::AirPods(AirPodsState {
                                    device_name,
                                    battery: state.battery_info.clone(),
                                    noise_control_mode: state.control_command_status_list.iter().find_map(|status| {
                                        if status.identifier == ControlCommandIdentifiers::ListeningMode {
                                            status.value.first().map(AirPodsNoiseControlMode::from_byte)
                                        } else {
                                            None
                                        }
                                    }).unwrap_or(AirPodsNoiseControlMode::Transparency),
                                    noise_control_state: combo_box::State::new(
                                        {
                                            let mut modes = vec![
                                                AirPodsNoiseControlMode::Transparency,
                                                AirPodsNoiseControlMode::NoiseCancellation,
                                                AirPodsNoiseControlMode::Adaptive
                                            ];
                                            if state.control_command_status_list.iter().any(|status| {
                                                status.identifier == ControlCommandIdentifiers::AllowOffOption &&
                                                matches!(status.value.as_slice(), [0x01])
                                            }) {
                                                modes.insert(0, AirPodsNoiseControlMode::Off);
                                            }
                                            modes
                                        }
                                    ),
                                    conversation_awareness_enabled: state.control_command_status_list.iter().any(|status| {
                                        status.identifier == ControlCommandIdentifiers::ConversationDetectConfig &&
                                        matches!(status.value.as_slice(), [0x01])
                                    }),
                                    personalized_volume_enabled: state.control_command_status_list.iter().any(|status| {
                                        status.identifier == ControlCommandIdentifiers::AdaptiveVolumeConfig &&
                                        matches!(status.value.as_slice(), [0x01])
                                    }),
                                    allow_off_mode: state.control_command_status_list.iter().any(|status| {
                                        status.identifier == ControlCommandIdentifiers::AllowOffOption &&
                                        matches!(status.value.as_slice(), [0x01])
                                    }),
                                    auto_anc_strength: state.control_command_status_list.iter().find_map(|status| {
                                        if status.identifier == ControlCommandIdentifiers::AutoAncStrength {
                                            status.value.first().copied()
                                        } else {
                                            None
                                        }
                                    }).unwrap_or(50),
                                }));
                            }
                            Some(DeviceType::Nothing) => {
                                self.device_states.insert(
                                    mac.clone(),
                                    DeviceState::Nothing(NothingState {
                                        anc_mode: NothingAncMode::Off,
                                        anc_mode_state: combo_box::State::new(vec![
                                            NothingAncMode::Off,
                                            NothingAncMode::Transparency,
                                            NothingAncMode::AdaptiveNoiseCancellation,
                                            NothingAncMode::LowNoiseCancellation,
                                            NothingAncMode::MidNoiseCancellation,
                                            NothingAncMode::HighNoiseCancellation,
                                        ]),
                                    }),
                                );
                            }
                            _ => {}
                        }

                        // Trigger popup on device connection (most reliable path)
                        let mut tasks = vec![wait_task];
                        if self.show_popup && self.popup_window.is_none() {
                            let now = std::time::Instant::now();
                            let is_suppressed = self.popup_suppressed_until.map(|until| now < until).unwrap_or(false);
                            if !is_suppressed {
                                info!("Triggering popup on device connect for {}", mac);
                                let device_name = {
                                    let devices_json = std::fs::read_to_string(get_devices_path())
                                        .unwrap_or_else(|e| {
                                            error!("Failed to read devices file: {}", e);
                                            "{}".to_string()
                                        });
                                    let devices_list: HashMap<String, DeviceData> =
                                        serde_json::from_str(&devices_json).unwrap_or_else(|e| {
                                            error!("Deserialization failed: {}", e);
                                            HashMap::new()
                                        });
                                    devices_list.get(&mac).map(|d| d.name.clone()).unwrap_or_else(|| "AirPods".to_string())
                                };
                                self.popup_mac = Some(mac.clone());
                                self.popup_name = device_name;
                                self.popup_battery_l = None;
                                self.popup_battery_r = None;
                                self.popup_battery_c = None;
                                self.popup_charging_l = false;
                                self.popup_charging_r = false;
                                self.popup_charging_c = false;
                                self.popup_frame = 0;
                                self.popup_last_tick = Some(std::time::Instant::now());

                                let mut settings = window::Settings::default();
                                settings.size = Size::new(400.0, 300.0);
                                settings.decorations = false;
                                settings.transparent = true;
                                settings.level = window::Level::AlwaysOnTop;

                                let (id, open) = window::open(settings);
                                self.popup_window = Some(id);
                                tasks.push(open.map(Message::WindowOpened));
                            }
                        }
                        Task::batch(tasks)
                    }
                    BluetoothUIMessage::DeviceDisconnected(mac) => {
                        let ui_rx = Arc::clone(&self.ui_rx);
                        let wait_task = Task::perform(wait_for_message(ui_rx), |msg| msg);
                        debug!("Device disconnected: {}", mac);

                        self.bluetooth_state
                            .connected_devices
                            .retain(|device| device != &mac);

                        self.device_states.remove(&mac);
                        self.is_in_ear = false;
                        self.popup_suppressed_until = None;

                        if matches!(&self.selected_tab, Tab::Device(selected_mac) if selected_mac == &mac) {
                            self.selected_tab = Tab::Device("none".to_string());
                        }

                        let mut tasks = vec![wait_task];
                        if let Some(id) = self.main_window {
                            info!("Closing main window due to disconnect.");
                            self.main_window = None;
                            tasks.push(window::close(id));
                        }

                        Task::batch(tasks)
                    }
                    BluetoothUIMessage::AACPUIEvent(mac, event) => {
                        let ui_rx = Arc::clone(&self.ui_rx);
                        let wait_task = Task::perform(wait_for_message(ui_rx), |msg| msg);
                        debug!("AACP UI Event for {}: {:?}", mac, event);
                        match event {
                            AACPEvent::ControlCommand(status) => match status.identifier {
                                ControlCommandIdentifiers::ListeningMode => {
                                    let mode = status
                                        .value
                                        .first()
                                        .map(AirPodsNoiseControlMode::from_byte)
                                        .unwrap_or(AirPodsNoiseControlMode::Transparency);
                                    if let Some(DeviceState::AirPods(state)) =
                                        self.device_states.get_mut(&mac)
                                    {
                                        state.noise_control_mode = mode;
                                    }
                                }
                                ControlCommandIdentifiers::ConversationDetectConfig => {
                                    let is_enabled = match status.value.as_slice() {
                                        [0x01] => true,
                                        [0x02] => false,
                                        _ => {
                                            error!(
                                                "Unknown Conversation Detect Config value: {:?}",
                                                status.value
                                            );
                                            false
                                        }
                                    };
                                    if let Some(DeviceState::AirPods(state)) =
                                        self.device_states.get_mut(&mac)
                                    {
                                        state.conversation_awareness_enabled = is_enabled;
                                    }
                                }
                                ControlCommandIdentifiers::AdaptiveVolumeConfig => {
                                    let is_enabled = match status.value.as_slice() {
                                        [0x01] => true,
                                        [0x02] => false,
                                        _ => {
                                            error!(
                                                "Unknown Adaptive Volume Config value: {:?}",
                                                status.value
                                            );
                                            false
                                        }
                                    };
                                    if let Some(DeviceState::AirPods(state)) =
                                        self.device_states.get_mut(&mac)
                                    {
                                        state.personalized_volume_enabled = is_enabled;
                                    }
                                }
                                ControlCommandIdentifiers::AllowOffOption => {
                                    let is_enabled = match status.value.as_slice() {
                                        [0x01] => true,
                                        [0x02] => false,
                                        _ => {
                                            error!(
                                                "Unknown Allow Off Option value: {:?}",
                                                status.value
                                            );
                                            false
                                        }
                                    };
                                    if let Some(DeviceState::AirPods(state)) =
                                        self.device_states.get_mut(&mac)
                                    {
                                        state.allow_off_mode = is_enabled;
                                        state.noise_control_state = combo_box::State::new({
                                            let mut modes = vec![
                                                AirPodsNoiseControlMode::Transparency,
                                                AirPodsNoiseControlMode::NoiseCancellation,
                                                AirPodsNoiseControlMode::Adaptive,
                                            ];
                                            if is_enabled {
                                                modes.insert(0, AirPodsNoiseControlMode::Off);
                                            }
                                            modes
                                        });
                                    }
                                }
                                ControlCommandIdentifiers::AutoAncStrength => {
                                    let strength = status.value.first().copied().unwrap_or(50);
                                    if let Some(DeviceState::AirPods(state)) =
                                        self.device_states.get_mut(&mac)
                                    {
                                        state.auto_anc_strength = strength;
                                    }
                                }
                                _ => {
                                    debug!("Unhandled Control Command Status: {:?}", status);
                                }
                            },
                            AACPEvent::BatteryInfo(battery_info) => {
                                if let Some(DeviceState::AirPods(state)) =
                                    self.device_states.get_mut(&mac)
                                {
                                    let old_case_open = state.is_case_open();
                                    state.update_battery(&battery_info);
                                    let new_case_open = state.is_case_open();
                                    
                                    debug!("Battery update for {}: Case open: {} -> {}", mac, old_case_open, new_case_open);

                                     // Trigger popup if case is open and setting is enabled
                                     let now = std::time::Instant::now();
                                     let is_suppressed = self.popup_suppressed_until.map(|until| now < until).unwrap_or(false);

                                     // Reset suppression if case is closed
                                     if !new_case_open && old_case_open {
                                         self.popup_suppressed_until = None;
                                     }

                                     let mut tasks = vec![wait_task];

                                     if self.show_popup && new_case_open && self.popup_window.is_none() && !is_suppressed && !self.is_in_ear {
                                         info!("Triggering popup for {} (Case is open)", mac);
                                         let (bl, br, bc) = state.get_battery_levels();
                                         let (cl, cr, cc) = state.get_charging_statuses();
                                         
                                         self.popup_mac = Some(mac.clone());
                                         self.popup_name = state.device_name.clone();
                                         self.popup_battery_l = bl;
                                         self.popup_battery_r = br;
                                         self.popup_battery_c = bc;
                                         self.popup_charging_l = cl;
                                         self.popup_charging_r = cr;
                                         self.popup_charging_c = cc;
                                         self.popup_frame = 0;
                                         self.popup_last_tick = Some(std::time::Instant::now());

                                         let mut settings = window::Settings::default();
                                         settings.size = Size::new(400.0, 300.0);
                                         settings.decorations = false;
                                         settings.transparent = true;
                                         settings.level = window::Level::AlwaysOnTop;
                                         
                                         let (id, open) = window::open(settings);
                                         self.popup_window = Some(id);
                                         tasks.push(open.map(Message::WindowOpened));
                                     }

                                     // Smart Open Main Window: If in ear and case just closed
                                     if self.is_in_ear && !new_case_open && old_case_open && self.main_window.is_none() {
                                         info!("AirPods in ear and case closed, opening main window.");
                                         let mut settings = window::Settings::default();
                                         settings.size = Size::new(800.0, 600.0);
                                         settings.icon = window::icon::from_file("assets/icon.png").ok();
                                         let (id, open) = window::open(settings);
                                         self.main_window = Some(id);
                                         tasks.push(open.map(Message::WindowOpened));
                                     }
                                    
                                    // Update popup data if it's already open
                                    if let Some(_popup_id) = self.popup_window {
                                        if self.popup_mac.as_ref() == Some(&mac) {
                                            let (bl, br, bc) = state.get_battery_levels();
                                            let (cl, cr, cc) = state.get_charging_statuses();
                                            self.popup_battery_l = bl;
                                            self.popup_battery_r = br;
                                            self.popup_battery_c = bc;
                                            self.popup_charging_l = cl;
                                            self.popup_charging_r = cr;
                                            self.popup_charging_c = cc;
                                            // Reset timer so popup stays visible after battery update
                                            self.popup_last_tick = Some(std::time::Instant::now());
                                            info!("Updated popup battery: L={:?} R={:?} C={:?}", bl, br, bc);
                                        }
                                    }

                                    return Task::batch(tasks);
                                }
                            }
                            AACPEvent::EarDetection(_, new_status) => {
                                debug!("UI received EarDetection status for {}: {:?}", mac, new_status);
                                use crate::bluetooth::aacp::EarDetectionStatus;
                                self.is_in_ear = new_status.iter().any(|s| *s == EarDetectionStatus::InEar);

                                // Close popup if any bud is in ear
                                if self.popup_window.is_some() && self.is_in_ear {
                                    info!("AirPods detected in ear, closing popup.");
                                    if let Some(id) = self.popup_window {
                                        self.popup_window = None;
                                        self.popup_suppressed_until = Some(std::time::Instant::now() + std::time::Duration::from_secs(10));
                                        return Task::batch(vec![wait_task, window::close(id)]);
                                    }
                                }
                            }
                            _ => {}
                        }
                        Task::batch(vec![wait_task])
                    }
                    BluetoothUIMessage::ATTNotification(mac, handle, value) => {
                        debug!(
                            "ATT Notification for {}: handle=0x{:04X}, value={:?}",
                            mac, handle, value
                        );

                        // TODO: Handle Nothing's ANC Mode changes here

                        let ui_rx = Arc::clone(&self.ui_rx);
                        let wait_task = Task::perform(wait_for_message(ui_rx), |msg| msg);
                        Task::batch(vec![wait_task])
                    }
                    BluetoothUIMessage::ShowPopup { mac, battery_l, battery_r, battery_c, charging_l, charging_r, charging_c } => {
                        let ui_rx = Arc::clone(&self.ui_rx);
                        let wait_task = Task::perform(wait_for_message(ui_rx), |msg| msg);

                        let now = std::time::Instant::now();
                        let is_suppressed = self.popup_suppressed_until.map(|until| now < until).unwrap_or(false);

                        if !self.show_popup || self.popup_window.is_some() || is_suppressed {
                            return Task::batch(vec![wait_task]);
                        }

                        self.popup_mac = Some(mac.clone());
                        self.popup_name = {
                            let devices_json = std::fs::read_to_string(get_devices_path())
                                .unwrap_or_else(|e| {
                                    error!("Failed to read devices file: {}", e);
                                    "{}".to_string()
                                });
                            let devices_list: HashMap<String, DeviceData> =
                                serde_json::from_str(&devices_json).unwrap_or_else(|e| {
                                    error!("Deserialization failed: {}", e);
                                    HashMap::new()
                                });
                            devices_list.get(&mac).map(|d| d.name.clone()).unwrap_or_else(|| "AirPods".to_string())
                        };
                        self.popup_battery_l = battery_l;
                        self.popup_battery_r = battery_r;
                        self.popup_battery_c = battery_c;
                        self.popup_charging_l = charging_l;
                        self.popup_charging_r = charging_r;
                        self.popup_charging_c = charging_c;

                        if self.popup_window.is_some() && battery_l.is_none() && battery_r.is_none() && battery_c.is_none() {
                            let id = self.popup_window.take().unwrap();
                            return window::close(id);
                        }

                        if self.popup_window.is_none() {
                            info!("Opening new popup window...");
                            let mut settings = window::Settings::default();
                            settings.size = Size::new(400.0, 300.0);
                            settings.decorations = false;
                            settings.transparent = true;
                            settings.level = window::Level::AlwaysOnTop;
                            
                            let (id, open) = window::open(settings);
                            self.popup_window = Some(id);
                            self.popup_frame = 0;
                            self.popup_last_tick = Some(std::time::Instant::now());
                            Task::batch(vec![wait_task, open.map(Message::WindowOpened)])
                        } else {
                            self.popup_last_tick = Some(std::time::Instant::now());
                            Task::batch(vec![wait_task])
                        }
                    }
                }
            }
            // Message::ShowNewDialogTab => {
            //     debug!("switching to Add Device tab");
            //     self.selected_tab = Tab::AddDevice;
            //     Task::perform(load_paired_devices(), Message::GotPairedDevices)
            // }
            Message::GotPairedDevices(map) => {
                self.paired_devices = map;
                Task::none()
            }
            Message::StartAddDevice(name, addr) => {
                self.pending_add_device = Some((name, addr));
                self.selected_device_type = None;
                Task::none()
            }
            Message::SelectDeviceType(device_type) => {
                self.selected_device_type = Some(device_type);
                Task::none()
            }
            Message::ConfirmAddDevice => {
                if let Some((name, addr)) = self.pending_add_device.take()
                    && let Some(type_) = self.selected_device_type.take()
                {
                    let devices_path = get_devices_path();
                    let devices_json = std::fs::read_to_string(&devices_path).unwrap_or_else(|e| {
                        error!("Failed to read devices file: {}", e);
                        "{}".to_string()
                    });
                    let mut devices_list: HashMap<String, DeviceData> =
                        serde_json::from_str(&devices_json).unwrap_or_else(|e| {
                            error!("Deserialization failed: {}", e);
                            HashMap::new()
                        });
                    devices_list.insert(
                        addr.to_string(),
                        DeviceData {
                            name,
                            type_: type_.clone(),
                            information: None,
                        },
                    );
                    let updated_json = serde_json::to_string(&devices_list).unwrap_or_else(|e| {
                        error!("Serialization failed: {}", e);
                        "{}".to_string()
                    });
                    if let Err(e) = std::fs::write(&devices_path, updated_json) {
                        error!("Failed to write devices file: {}", e);
                    }
                    self.selected_tab = Tab::Device(addr.to_string());
                }
                Task::none()
            }
            Message::CancelAddDevice => {
                self.pending_add_device = None;
                self.selected_device_type = None;
                Task::none()
            }
            Message::StateChanged(mac, state) => {
                self.device_states.insert(mac.clone(), state);
                // if airpods, update the noise control state combo box based on allow off mode
                let type_ = {
                    let devices_json =
                        std::fs::read_to_string(get_devices_path()).unwrap_or_else(|e| {
                            error!("Failed to read devices file: {}", e);
                            "{}".to_string()
                        });
                    let devices_list: HashMap<String, DeviceData> =
                        serde_json::from_str(&devices_json).unwrap_or_else(|e| {
                            error!("Deserialization failed: {}", e);
                            HashMap::new()
                        });
                    devices_list.get(&mac).map(|d| d.type_.clone())
                };
                if let Some(DeviceType::AirPods) = type_
                    && let Some(DeviceState::AirPods(state)) = self.device_states.get_mut(&mac)
                {
                    state.noise_control_state = combo_box::State::new({
                        let mut modes = vec![
                            AirPodsNoiseControlMode::Transparency,
                            AirPodsNoiseControlMode::NoiseCancellation,
                            AirPodsNoiseControlMode::Adaptive,
                        ];
                        if state.allow_off_mode {
                            modes.insert(0, AirPodsNoiseControlMode::Off);
                        }
                        modes
                    });
                }
                Task::none()
            }
            Message::TrayTextModeChanged(is_enabled) => {
                self.tray_text_mode = is_enabled;
                let app_settings_path = get_app_settings_path();
                let settings = serde_json::json!({
                    "theme": self.selected_theme,
                    "tray_text_mode": self.tray_text_mode,
                    "stem_control": self.stem_control,
                    "show_popup": self.show_popup,
                });
                debug!(
                    "Writing settings to {}: {}",
                    app_settings_path.to_str().unwrap(),
                    settings
                );
                std::fs::write(app_settings_path, settings.to_string()).ok();
                Task::none()
            }
            Message::StemControlChanged(is_enabled) => {
                self.stem_control = is_enabled;
                let app_settings_path = get_app_settings_path();
                let settings = serde_json::json!({
                    "theme": self.selected_theme,
                    "tray_text_mode": self.tray_text_mode,
                    "stem_control": self.stem_control,
                    "show_popup": self.show_popup,
                });
                debug!(
                    "Writing settings to {}: {}",
                    app_settings_path.to_str().unwrap(),
                    settings
                );
                std::fs::write(app_settings_path, settings.to_string()).ok();
                Task::none()
            }
            Message::ShowPopupChanged(is_enabled) => {
                self.show_popup = is_enabled;
                let app_settings_path = get_app_settings_path();
                let settings = serde_json::json!({
                    "theme": self.selected_theme,
                    "tray_text_mode": self.tray_text_mode,
                    "stem_control": self.stem_control,
                    "show_popup": self.show_popup,
                });
                debug!(
                    "Writing settings to {}: {}",
                    app_settings_path.to_str().unwrap(),
                    settings
                );
                std::fs::write(app_settings_path, settings.to_string()).ok();
                Task::none()
            }
            Message::Tick => {
                self.popup_frame = (self.popup_frame + 1) % self.animation_frames.len();
                // Close popup if it has been open for 20 seconds without any update
                if let Some(last_tick) = self.popup_last_tick {
                    if last_tick.elapsed().as_secs() > 10 {
                        if let Some(id) = self.popup_window {
                            self.popup_window = None;
                            return window::close(id);
                        }
                    }
                }
                Task::none()
            }
            Message::ClosePopup => {
                if let Some(id) = self.popup_window {
                    self.popup_window = None;
                    return window::close(id);
                }
                Task::none()
            }
        }
    }

    fn view(&self, _id: window::Id) -> Element<'_, Message> {
        if Some(_id) == self.popup_window {
            return crate::ui::popup::popup_view(
                self.popup_name.clone(),
                self.popup_frame,
                self.animation_frames.clone(),
                self.popup_battery_l,
                self.popup_battery_r,
                self.popup_battery_c,
                self.popup_charging_l,
                self.popup_charging_r,
                self.popup_charging_c,
            );
        }

        let devices_json = std::fs::read_to_string(get_devices_path()).unwrap_or_else(|e| {
            error!("Failed to read devices file: {}", e);
            "{}".to_string()
        });
        let devices_list: HashMap<String, DeviceData> = serde_json::from_str(&devices_json)
            .unwrap_or_else(|e| {
                error!("Deserialization failed: {}", e);
                HashMap::new()
            });
        let pane_grid = pane_grid::PaneGrid::new(&self.panes, |_pane_id, pane, _is_maximized| {
            match pane {
                Pane::Sidebar => {
                    let create_tab_button = |tab: Tab, label: &str, mac_addr: &str, connected: bool| -> Element<'_, Message> {
                        let label = label.to_string() + if connected { " 􀉣" } else { "" };
                        let is_selected = self.selected_tab == tab;
                        let col = column![
                            text(label).size(16),
                            text({
                                if connected {
                                    let mac = match tab {
                                        Tab::Device(ref mac) => mac.as_str(),
                                        _ => "",
                                    };

                                    match self.device_states.get(mac) {
                                        Some(DeviceState::AirPods(state)) => {
                                            let b = &state.battery;
                                            let headphone = b.iter().find(|x| x.component == BatteryComponent::Headphone)
                                                .map(|x| x.level);
                                            // if headphones is not None, use only that
                                            if let Some(level) = headphone {
                                                let charging = b.iter().find(|x| x.component == BatteryComponent::Headphone)
                                                    .map(|x| x.status == BatteryStatus::Charging).unwrap_or(false);
                                                format!(
                                                    "􀺹 {}%{}",
                                                    level, if charging {"\u{1002E6}"} else {""}
                                                )
                                            } else {
                                                let format_batt = |comp: BatteryComponent| -> String {
                                                    let batt = b.iter().find(|x| x.component == comp);
                                                    match batt {
                                                        Some(x) if x.status != BatteryStatus::Disconnected => {
                                                            let charging = if x.status == BatteryStatus::Charging { "\u{1002E6}" } else { "" };
                                                            format!("{}%{}", x.level, charging)
                                                        }
                                                        _ => "--%".to_string()
                                                    }
                                                };
                                                
                                                format!(
                                                    "\u{1018E5} {} \u{1018E8} {} \u{100E6C} {}",
                                                    format_batt(BatteryComponent::Left),
                                                    format_batt(BatteryComponent::Right),
                                                    format_batt(BatteryComponent::Case)
                                                )
                                            }
                                        }
                                        _ => "Connected".to_string(),
                                    }
                                } else {
                                    mac_addr.to_string()
                                }
                            }).size(12)
                        ];
                        let content = container(col)
                            .padding(8);
                        let style = move |theme: &Theme, _status| {
                            if is_selected {
                                let mut style = Style::default()
                                    .with_background(theme.palette().primary);
                                let mut border = Border::default();
                                border.color = theme.palette().text;
                                style.border = border.rounded(12);
                                style
                            } else {
                                let mut style = Style::default()
                                    .with_background(theme.palette().primary.scale_alpha(0.1));
                                let mut border = Border::default();
                                border.color = theme.palette().primary.scale_alpha(0.1);
                                style.border = border.rounded(8);
                                style.text_color = theme.palette().text;
                                style
                            }
                        };
                        button(content)
                            .style(style)
                            .padding(5)
                            .on_press(Message::SelectTab(tab))
                            .width(Length::Fill)
                            .into()
                    };

                    let create_settings_button = || -> Element<'_, Message> {
                        let label = "Settings".to_string();
                        let is_selected = self.selected_tab == Tab::Settings;
                        let col = column![text(label).size(16)];
                        let content = container(col)
                            .padding(8);
                        let style = move |theme: &Theme, _status| {
                            if is_selected {
                                let mut style = Style::default()
                                    .with_background(theme.palette().primary);
                                let mut border = Border::default();
                                border.color = theme.palette().text;
                                style.border = border.rounded(12);
                                style
                            } else {
                                let mut style = Style::default()
                                    .with_background(theme.palette().primary.scale_alpha(0.1));
                                let mut border = Border::default();
                                border.color = theme.palette().primary.scale_alpha(0.1);
                                style.border = border.rounded(8);
                                style.text_color = theme.palette().text;
                                style
                            }
                        };
                        button(content)
                            .style(style)
                            .padding(5)
                            .on_press(Message::SelectTab(Tab::Settings))
                            .width(Length::Fill)
                            .into()
                    };

                    let mut devices = column!().spacing(4);
                    let mut devices_vec: Vec<(String, DeviceData)> = devices_list.clone().into_iter().collect();
                    devices_vec.sort_by(|a, b| a.1.name.cmp(&b.1.name));
                    for (mac, device) in devices_vec {
                        let name = device.name.clone();
                        let tab_button = create_tab_button(
                            Tab::Device(mac.clone()),
                            &name,
                            &mac,
                            self.bluetooth_state.connected_devices.contains(&mac)
                        );
                        devices = devices.push(tab_button);
                    }

                    let settings = create_settings_button();

                    let content = column![
                        row![
                            text("Devices").size(18),
                            // Removing until I actually add support for devices other than AirPods
                            // Space::new().width(Length::Fill),
                            // button(
                            //     container(text("+").size(18)).center_x(Length::Fill).center_y(Length::Fill)
                            // )
                            //     .style(
                            //         |theme: &Theme, _status| {
                            //             let mut style = Style::default();
                            //             style.text_color = theme.palette().text;
                            //             style.background = Some(Background::Color(theme.palette().primary.scale_alpha(0.1)));
                            //             style.border = Border {
                            //                 width: 1.0,
                            //                 color: theme.palette().primary.scale_alpha(0.1),
                            //                 radius: Radius::from(8.0),
                            //             };
                            //             style
                            //         }
                            //     )
                            //     .padding(0)
                            //     .width(Length::from(28))
                            //     .height(Length::from(28))
                            //     .on_press(Message::ShowNewDialogTab)
                        ]
                        .align_y(Center)
                        .padding(4),
                        Space::new().height(Length::from(8)),
                        devices,
                        Space::new().height(Length::Fill),
                        settings
                    ]
                        .padding(12);
                    pane_grid::Content::new(
                        row![
                            content,
                            rule::vertical(1).style(
                                |theme: &Theme| {
                                    rule::Style{
                                        color: theme.palette().primary.scale_alpha(0.2),
                                        radius: Radius::from(8.0),
                                        fill_mode: FillMode::Full,
                                        snap: false
                                    }
                                }
                            )
                        ]
                    )
                }

                Pane::Content => {
                    let device_managers = self.device_managers.blocking_read();
                    let content = match &self.selected_tab {
                        Tab::Device(id) => {
                            if id == "none" {
                                container(
                                    text("Select a device".to_string()).size(16)
                                )
                                    .center_x(Length::Fill)
                                    .center_y(Length::Fill)
                            } else {
                                let device_type = devices_list.get(id).map(|d| d.type_.clone());
                                let device_state = self.device_states.get(id);
                                debug!("Rendering device view for {}: type={:?}, state={:?}", id, device_type, device_state);
                                match device_type {
                                    Some(DeviceType::AirPods) => {

                                        device_state.as_ref().and_then(|state| {
                                            match state {
                                                DeviceState::AirPods(state) => {
                                                    device_managers.get(id).and_then(|managers| {
                                                        managers.get_aacp().map(|aacp_manager| airpods_view(
                                                                    id,
                                                                    &devices_list,
                                                                    state,
                                                                    aacp_manager.clone()
                                                                ))
                                                    })
                                                }
                                                _ => None,
                                            }
                                        }).unwrap_or_else(|| {
                                            container(
                                                text("Required managers or state not available for this AirPods device").size(16)
                                            )
                                                .center_x(Length::Fill)
                                                .center_y(Length::Fill)
                                        })
                                    }
                                    Some(DeviceType::Nothing) => {
                                        if let Some(DeviceState::Nothing(state)) = device_state {
                                            if let Some(device_managers) = device_managers.get(id) {
                                                if let Some(att_manager) = device_managers.get_att() {
                                                    nothing_view(id, &devices_list, state, att_manager.clone())
                                                } else {
                                                    error!("No ATT manager found for Nothing device {}", id);
                                                    container(
                                                        text("No valid ATT manager found for this Nothing device").size(16)
                                                    )
                                                        .center_x(Length::Fill)
                                                        .center_y(Length::Fill)
                                                }
                                            } else {
                                                error!("No manager found for Nothing device {}", id);
                                                container(
                                                    text("No manager found for this Nothing device").size(16)
                                                )
                                                    .center_x(Length::Fill)
                                                    .center_y(Length::Fill)
                                            }
                                        } else {
                                            container(
                                                text("No state available for this Nothing device").size(16)
                                            )
                                                .center_x(Length::Fill)
                                                .center_y(Length::Fill)
                                        }
                                    }
                                    _ => {
                                        container(text("Unsupported device").size(16))
                                            .center_x(Length::Fill)
                                            .center_y(Length::Fill)
                                    }
                                }
                            }
                        }
                        Tab::Settings => {
                            let tray_text_mode_toggle = container(
                                row![
                                    column![
                                        text("Use text in tray").size(16),
                                        text("Use text for battery status in tray instead of a progress bar.").size(12).style(
                                            |theme: &Theme| {
                                                let mut style = text::Style::default();
                                                style.color = Some(theme.palette().text.scale_alpha(0.7));
                                                style
                                            }
                                        ).width(Length::Fill)
                                    ].width(Length::Fill),
                                    toggler(self.tray_text_mode)
                                        .on_toggle(move |is_enabled| {
                                            Message::TrayTextModeChanged(is_enabled)
                                        })
                                    .spacing(0)
                                    .size(20)
                                    ]
                                        .align_y(Center)
                                        .spacing(12)
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
                                    .align_y(Center);

                            let appearance_settings_col = column![
                                container(
                                    text("Appearance").size(20).style(
                                        |theme: &Theme| {
                                            let mut style = text::Style::default();
                                            style.color = Some(theme.palette().primary);
                                            style
                                        }
                                    )
                                )
                                .padding(Padding{
                                    top: 0.0,
                                    bottom: 0.0,
                                    left: 18.0,
                                    right: 18.0,
                                }),
                                container(
                                    row![
                                        text("Theme")
                                            .size(16),
                                        Space::new().width(Length::Fill),
                                        combo_box(
                                            &self.theme_state,
                                            "Select theme",
                                            Some(&self.selected_theme),
                                            Message::ThemeSelected
                                        )
                                        .input_style(
                                            |theme: &Theme, _status| {
                                                text_input::Style {
                                                    background: Background::Color(theme.palette().primary.scale_alpha(0.2)),
                                                    border: Border {
                                                        width: 1.0,
                                                        color: theme.palette().text.scale_alpha(0.3),
                                                        radius: Radius::from(4.0)
                                                    },
                                                    icon: Default::default(),
                                                    placeholder: theme.palette().text,
                                                    value: theme.palette().text,
                                                    selection: Default::default(),
                                                }
                                            }
                                        )
                                        .menu_style(
                                            |theme: &Theme| {
                                                menu::Style {
                                                    background: Background::Color(theme.palette().background),
                                                    border: Border {
                                                        width: 1.0,
                                                        color: theme.palette().text,
                                                        radius: Radius::from(4.0)
                                                    },
                                                    text_color: theme.palette().text,
                                                    selected_text_color: theme.palette().text,
                                                    selected_background: Background::Color(theme.palette().primary.scale_alpha(0.3)),
                                                    shadow: Default::default()
                                                }
                                            }
                                        )
                                        .padding(Padding{
                                            top: 5.0,
                                            bottom: 5.0,
                                            left: 10.0,
                                            right: 10.0,
                                        })
                                        .width(Length::from(200))
                                    ]
                                    .align_y(Center)
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
                                ]
                                .spacing(12);

                            let stem_control_value = self.stem_control;
                            let stem_control_toggle = container(
                                row![
                                    column![
                                        text("Stem press track control").size(16),
                                        text("Double press = next track, triple press = previous track. Disable if your environment handles AirPods AVRCP commands natively.").size(12).style(
                                            |theme: &Theme| {
                                                let mut style = text::Style::default();
                                                style.color = Some(theme.palette().text.scale_alpha(0.7));
                                                style
                                            }
                                        ).width(Length::Fill)
                                    ].width(Length::Fill),
                                    toggler(stem_control_value)
                                        .on_toggle(move |is_enabled| {
                                            Message::StemControlChanged(is_enabled)
                                        })
                                    .spacing(0)
                                    .size(20)
                                    ]
                                        .align_y(Center)
                                        .spacing(12)
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
                                    .align_y(Center);

                            let controls_settings_col = column![
                                container(
                                    text("Controls").size(20).style(
                                        |theme: &Theme| {
                                            let mut style = text::Style::default();
                                            style.color = Some(theme.palette().primary);
                                            style
                                        }
                                    )
                                )
                                .padding(Padding{
                                    top: 0.0,
                                    bottom: 0.0,
                                    left: 18.0,
                                    right: 18.0,
                                }),
                                stem_control_toggle
                            ]
                            .spacing(12);
                            
                            let show_popup_toggle = container(
                                row![
                                    column![
                                        text("Show AirPods Pop-up").size(16),
                                        text("Show a 3D animation and battery levels when AirPods case is opened nearby.").size(12).style(
                                            |theme: &Theme| {
                                                let mut style = text::Style::default();
                                                style.color = Some(theme.palette().text.scale_alpha(0.7));
                                                style
                                            }
                                        ).width(Length::Fill)
                                    ].width(Length::Fill),
                                    toggler(self.show_popup)
                                        .on_toggle(move |is_enabled| {
                                            Message::ShowPopupChanged(is_enabled)
                                        })
                                    .spacing(0)
                                    .size(20)
                                ]
                                .align_y(Center)
                                .spacing(12)
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

                            container(
                                column![
                                    appearance_settings_col,
                                    Space::new().height(Length::from(20)),
                                    tray_text_mode_toggle,
                                    Space::new().height(Length::from(20)),
                                    show_popup_toggle,
                                    Space::new().height(Length::from(20)),
                                    controls_settings_col,
                                ]
                            )
                                .padding(20)
                                .width(Length::Fill)
                                .height(Length::Fill)
                        },
                        Tab::AddDevice => {
                            container(
                                column![
                                    text("Pick a paired device to add:").size(18),
                                    Space::new().height(Length::from(10)),
                                    {
                                        let mut list_col = column![].spacing(12);
                                        for device in self.paired_devices.clone() {
                                            if !devices_list.contains_key(&device.1.to_string()) {
                                                let mut item_col = column![].spacing(8);
                                                let mut row_elements = vec![
                                                    column![
                                                        text(device.0.to_string()).size(16),
                                                        text(device.1.to_string()).size(12)
                                                    ].into(),
                                                    Space::new().height(Length::Fill).into(),
                                                ];
                                                if !matches!(&self.pending_add_device, Some((_, addr)) if addr == &device.1) {
                                                    row_elements.push(
                                                        button(
                                                            text("Add").size(14).width(120).align_y(Center).align_x(Center)
                                                        )
                                                            .style(
                                                                |theme: &Theme, _status| {
                                                                    let mut style = Style::default();
                                                                    style.text_color = theme.palette().text;
                                                                    style.background = Some(Background::Color(theme.palette().primary.scale_alpha(0.5)));
                                                                    style.border = Border {
                                                                        width: 1.0,
                                                                        color: theme.palette().primary,
                                                                        radius: Radius::from(8.0),
                                                                    };
                                                                    style
                                                                }
                                                            )
                                                            .padding(8)
                                                            .on_press(Message::StartAddDevice(device.0.clone(), device.1))
                                                            .into()
                                                    );
                                                }
                                                item_col = item_col.push(row(row_elements).align_y(Center));

                                                if let Some((_, pending_addr)) = &self.pending_add_device
                                                    && pending_addr == &device.1 {
                                                        item_col = item_col.push(
                                                            row![
                                                                text("Device Type:").size(16),
                                                                Space::new().width(Length::Fill),
                                                                combo_box(
                                                                    &self.device_type_state,
                                                                    "Select device type",
                                                                    self.selected_device_type.as_ref(),
                                                                    Message::SelectDeviceType
                                                                )
                                                                    .input_style(
                                                                        |theme: &Theme, _status| {
                                                                            text_input::Style {
                                                                                background: Background::Color(theme.palette().background),
                                                                                border: Border {
                                                                                    width: 1.0,
                                                                                    color: theme.palette().text,
                                                                                    radius: Radius::from(8.0),
                                                                                },
                                                                                icon: Default::default(),
                                                                                placeholder: theme.palette().text.scale_alpha(0.5),
                                                                                value: theme.palette().text,
                                                                                selection: theme.palette().primary
                                                                            }
                                                                        }
                                                                    )
                                                                    .menu_style(
                                                                        |theme: &Theme| {
                                                                            menu::Style {
                                                                                background: Background::Color(theme.palette().background),
                                                                                border: Border {
                                                                                    width: 1.0,
                                                                                    color: theme.palette().text,
                                                                                    radius: Radius::from(8.0)
                                                                                },
                                                                                text_color: theme.palette().text,
                                                                                selected_text_color: theme.palette().text,
                                                                                selected_background: Background::Color(theme.palette().primary.scale_alpha(0.3)),
                                                                                shadow: Default::default()
                                                                            }
                                                                        }
                                                                    )
                                                                    .width(Length::from(200))
                                                            ]
                                                        );
                                                        item_col = item_col.push(
                                                            row![
                                                                Space::new().width(Length::Fill),
                                                                button(text("Cancel").size(16).width(Length::Fill).center())
                                                                    .on_press(Message::CancelAddDevice)
                                                                    .style(|theme: &Theme, _status| {
                                                                        let mut style = Style::default();
                                                                        style.background = Some(Background::Color(theme.palette().primary.scale_alpha(0.1)));
                                                                        style.text_color = theme.palette().text;
                                                                        style.border = Border::default().rounded(8.0);
                                                                        style
                                                                    })
                                                                    .width(Length::from(120))
                                                                    .padding(4),
                                                                Space::new().width(Length::from(20)),
                                                                button(text("Add Device").size(16).width(Length::Fill).center())
                                                                    .on_press(Message::ConfirmAddDevice)
                                                                    .style(|theme: &Theme, _status| {
                                                                        let mut style = Style::default();
                                                                        style.background = Some(Background::Color(theme.palette().primary.scale_alpha(0.3)));
                                                                        style.text_color = theme.palette().text;
                                                                        style.border = Border::default().rounded(8.0);
                                                                        style
                                                                    })
                                                                    .width(Length::from(120))
                                                                    .padding(4),
                                                            ]
                                                            .align_y(Center)
                                                            .width(Length::Fill)
                                                        );
                                                    }
                                                list_col = list_col.push(
                                                    container(item_col)
                                                        .padding(8)
                                                        .style(
                                                            |theme: &Theme| {
                                                                let mut style = container::Style::default();
                                                                style.background = Some(Background::Color(theme.palette().primary.scale_alpha(0.1)));
                                                                let mut border = Border::default();
                                                                border.color = theme.palette().text;
                                                                style.border = border.rounded(8);
                                                                style
                                                            }
                                                        )
                                                );
                                            }
                                        }
                                        if self.paired_devices.iter().all(|device| devices_list.contains_key(&device.1.to_string())) && self.pending_add_device.is_none() {
                                            list_col = list_col.push(
                                                container(
                                                    text("No new paired devices found. All paired devices are already added.").size(16)
                                                )
                                                .width(Length::Fill)
                                            );
                                        }
                                        scrollable(list_col)
                                            .height(Length::Fill)
                                            .width(Length::Fill)
                                    }
                                ]
                            )
                            .padding(20)
                            .height(Length::Fill)
                            .width(Length::Fill)
                        }
                    };

                    pane_grid::Content::new(content)
                }
            }
        })
            .width(Length::Fill)
            .height(Length::Fill)
            .on_resize(20, Message::Resized);

        container(pane_grid).into()
    }

    fn theme(&self, _id: window::Id) -> Theme {
        self.selected_theme.into()
    }

    fn subscription(&self) -> Subscription<Message> {
        let mut subs = vec![
            window::close_events().map(Message::WindowClosed)
        ];
        if self.popup_window.is_some() {
            subs.push(iced::time::every(std::time::Duration::from_millis(33)).map(|_| Message::Tick));
        }
        Subscription::batch(subs)
    }
}

async fn wait_for_message(ui_rx: Arc<Mutex<UnboundedReceiver<BluetoothUIMessage>>>) -> Message {
    let mut rx = ui_rx.lock().await;
    match rx.recv().await {
        Some(msg) => Message::BluetoothMessage(msg),
        None => {
            error!("UI message channel closed");
            Message::BluetoothMessage(BluetoothUIMessage::NoOp)
        }
    }
}

// async fn load_paired_devices() -> HashMap<String, Address> {
//     let mut devices = HashMap::new();
//
//     let session = Session::new().await.ok().unwrap();
//     let adapter = session.default_adapter().await.ok().unwrap();
//     let addresses = adapter.device_addresses().await.ok().unwrap();
//     for addr in addresses {
//         let device = adapter.device(addr).ok().unwrap();
//         let paired = device.is_paired().await.ok().unwrap();
//         if paired {
//             let name = device
//                 .name()
//                 .await
//                 .ok()
//                 .flatten()
//                 .unwrap_or_else(|| "Unknown".to_string());
//             devices.insert(name, addr);
//         }
//     }
//
//     devices
// }
