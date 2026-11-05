use crate::bluetooth::aacp::{
    AACPEvent, BatteryComponent, BatteryInfo, BatteryStatus, ControlCommandIdentifiers,
};
use crate::bluetooth::managers::DeviceManagers;
use crate::devices::enums::{
    AirPodsNoiseControlMode, AirPodsState, DeviceData, DeviceState, DeviceType, NothingAncMode,
    NothingState,
};
use crate::ui::airpods::airpods_view;
use crate::ui::messages::{BluetoothUIMessage, TrayStatus};
use crate::ui::nothing::nothing_view;
use crate::utils::{
    MyTheme, get_app_settings_path, get_devices_path, save_app_settings, upsert_device,
};
use bluer::Address;
use iced::border::Radius;
use iced::overlay::menu;
use iced::widget::button::Style;
use iced::widget::rule::FillMode;
use iced::widget::{
    Space, button, column, combo_box, container, pane_grid, row, rule, scrollable, text,
    text_input, toggler,
};
use iced::{
    Background, Border, Center, Element, Font, Length, Padding, Settings, Size, Subscription, Task,
    Theme, daemon, window,
};
use log::{debug, error};
use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;
use tokio::sync::mpsc::UnboundedReceiver;
use tokio::sync::{Mutex, RwLock};

fn window_icon() -> Option<window::Icon> {
    match window::icon::from_file_data(include_bytes!("../../assets/icon.png"), None) {
        Ok(icon) => Some(icon),
        Err(error) => {
            error!("Failed to decode embedded window icon: {error}");
            None
        }
    }
}

fn format_battery_summary(battery: &[BatteryInfo]) -> String {
    let headphone = battery
        .iter()
        .find(|reading| reading.component == BatteryComponent::Headphone)
        .map(|reading| reading.level);
    if let Some(level) = headphone {
        let charging = battery
            .iter()
            .find(|reading| reading.component == BatteryComponent::Headphone)
            .map(|reading| reading.status == BatteryStatus::Charging)
            .unwrap_or(false);
        format!("􀺹 {}%{}", level, if charging { "\u{1002E6}" } else { "" })
    } else {
        let mut components = Vec::new();
        for (component, icon) in [
            (BatteryComponent::Left, '\u{1018E5}'),
            (BatteryComponent::Right, '\u{1018E8}'),
            (BatteryComponent::Case, '\u{100E6C}'),
        ] {
            if let Some(reading) = battery
                .iter()
                .find(|reading| reading.component == component)
            {
                components.push(format!(
                    "{} {}%{}",
                    icon,
                    reading.level,
                    if reading.status == BatteryStatus::Charging {
                        "\u{1002E6}"
                    } else {
                        ""
                    }
                ));
            }
        }
        if components.is_empty() {
            "Battery unknown".to_string()
        } else {
            components.join(" ")
        }
    }
}

#[derive(Debug, Clone, Copy)]
enum AppSettingUpdate {
    Theme(MyTheme),
    TrayTextMode(bool),
    StemControl(bool),
}

impl AppSettingUpdate {
    fn into_patch(self) -> serde_json::Value {
        match self {
            Self::Theme(theme) => serde_json::json!({"theme": theme}),
            Self::TrayTextMode(is_enabled) => {
                serde_json::json!({"tray_text_mode": is_enabled})
            }
            Self::StemControl(is_enabled) => serde_json::json!({"stem_control": is_enabled}),
        }
    }
}

fn save_setting_update(path: &Path, update: AppSettingUpdate) -> std::io::Result<()> {
    save_app_settings(path, &update.into_patch())
}

#[cfg(target_os = "linux")]
fn window_application_id(flatpak_id: Option<&str>) -> String {
    flatpak_id
        .filter(|application_id| !application_id.is_empty())
        .unwrap_or("librepods")
        .to_owned()
}

fn main_window_settings() -> window::Settings {
    #[cfg(target_os = "linux")]
    let flatpak_id = std::env::var("FLATPAK_ID").ok();
    #[cfg(not(target_os = "linux"))]
    let flatpak_id: Option<String> = None;

    main_window_settings_for_flatpak_id(flatpak_id.as_deref())
}

fn main_window_settings_for_flatpak_id(_flatpak_id: Option<&str>) -> window::Settings {
    let mut settings = window::Settings::default();
    settings.min_size = Some(Size::new(400.0, 300.0));
    settings.visible = true;
    settings.icon = window_icon();
    #[cfg(target_os = "linux")]
    {
        settings.platform_specific.application_id = window_application_id(_flatpak_id);
    }
    settings
}

fn open_main_window() -> (window::Id, Task<Message>) {
    let (id, open) = window::open(main_window_settings());
    (id, open.map(Message::WindowOpened))
}

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
        fonts: vec![
            include_bytes!("../../assets/font/sf_pro.otf")
                .as_slice()
                .into(),
        ],
        default_font: Font::with_name("SF Pro Text"),
        ..Settings::default()
    })
    .run()
}

pub struct App {
    window: Option<window::Id>,
    tray_status: TrayStatus,
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
    UiChannelClosed,
    // ShowNewDialogTab,
    GotPairedDevices(HashMap<String, Address>),
    StartAddDevice(String, Address),
    SelectDeviceType(DeviceType),
    ConfirmAddDevice,
    CancelAddDevice,
    StateChanged(String, DeviceState),
    TrayTextModeChanged(bool), // yes, I know I should add all settings to a struct, but I'm lazy
    StemControlChanged(bool),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LifecycleEvent {
    TrayStatusChanged,
    LastWindowClosed,
    BackendClosed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LifecycleAction {
    Continue,
    OpenWindow,
    Exit,
}

fn lifecycle_action(
    event: LifecycleEvent,
    tray_status: &TrayStatus,
    window_is_open: bool,
) -> LifecycleAction {
    match event {
        LifecycleEvent::BackendClosed => LifecycleAction::Exit,
        LifecycleEvent::LastWindowClosed if !matches!(tray_status, TrayStatus::Available) => {
            LifecycleAction::Exit
        }
        LifecycleEvent::TrayStatusChanged
            if matches!(tray_status, TrayStatus::Unavailable(_)) && !window_is_open =>
        {
            LifecycleAction::OpenWindow
        }
        _ => LifecycleAction::Continue,
    }
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
            let (id, open) = open_main_window();
            (Some(id), open)
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

        let bluetooth_state = BluetoothState::new();

        // let dummy_device_state = DeviceState::AirPods(AirPodsState {
        //     conversation_awareness_enabled: false,
        // });
        // let device_states = HashMap::from([
        //     ("28:2D:7F:C2:05:5B".to_string(), dummy_device_state),
        // ]);

        let device_states = HashMap::new();
        (
            Self {
                window,
                tray_status: TrayStatus::Starting,
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
            },
            Task::batch(vec![open_task, wait_task]),
        )
    }

    fn persist_setting(&self, update: AppSettingUpdate) -> std::io::Result<()> {
        let app_settings_path = get_app_settings_path();
        let settings = update.into_patch();
        debug!(
            "Writing settings to {}: {}",
            app_settings_path.display(),
            settings
        );
        save_setting_update(&app_settings_path, update)
    }

    fn title(&self, _id: window::Id) -> String {
        "LibrePods".to_string()
    }

    fn show_main_window(&mut self) -> Task<Message> {
        if let Some(window_id) = self.window {
            window::gain_focus(window_id)
        } else {
            let (id, open) = open_main_window();
            self.window = Some(id);
            open
        }
    }

    fn apply_lifecycle_event(&mut self, event: LifecycleEvent) -> Task<Message> {
        match lifecycle_action(event, &self.tray_status, self.window.is_some()) {
            LifecycleAction::Continue => Task::none(),
            LifecycleAction::OpenWindow => self.show_main_window(),
            LifecycleAction::Exit => iced::exit(),
        }
    }

    fn update(&mut self, message: Message) -> Task<Message> {
        match message {
            Message::WindowOpened(id) => {
                self.window = Some(id);
                Task::none()
            }
            Message::WindowClosed(id) => {
                if self.window == Some(id) {
                    self.window = None;
                    self.apply_lifecycle_event(LifecycleEvent::LastWindowClosed)
                } else {
                    Task::none()
                }
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
                match self.persist_setting(AppSettingUpdate::Theme(theme)) {
                    Ok(()) => self.selected_theme = theme,
                    Err(error) => error!("Failed to persist theme selection: {error}"),
                }
                Task::none()
            }
            Message::CopyToClipboard(data) => iced::clipboard::write(data),
            Message::BluetoothMessage(ui_message) => {
                match ui_message {
                    BluetoothUIMessage::TrayStatusChanged(status) => {
                        match &status {
                            TrayStatus::Starting => {}
                            TrayStatus::Available => debug!("System tray is available"),
                            TrayStatus::Unavailable(reason) => {
                                error!("System tray is unavailable: {reason}")
                            }
                        }
                        self.tray_status = status;
                        let lifecycle_task =
                            self.apply_lifecycle_event(LifecycleEvent::TrayStatusChanged);
                        let ui_rx = Arc::clone(&self.ui_rx);
                        let wait_task = Task::perform(wait_for_message(ui_rx), |msg| msg);
                        Task::batch(vec![lifecycle_task, wait_task])
                    }
                    BluetoothUIMessage::OpenWindow => {
                        let ui_rx = Arc::clone(&self.ui_rx);
                        let wait_task = Task::perform(wait_for_message(ui_rx), |msg| msg);
                        debug!("Opening main window...");
                        let window_task = self.show_main_window();
                        Task::batch(vec![window_task, wait_task])
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

                        Task::batch(vec![wait_task])
                    }
                    BluetoothUIMessage::DeviceDisconnected(mac) => {
                        let ui_rx = Arc::clone(&self.ui_rx);
                        let wait_task = Task::perform(wait_for_message(ui_rx), |msg| msg);
                        debug!("Device disconnected: {}", mac);

                        self.bluetooth_state
                            .connected_devices
                            .retain(|device| device != &mac);

                        self.device_states.remove(&mac);

                        if matches!(&self.selected_tab, Tab::Device(selected_mac) if selected_mac == &mac)
                        {
                            self.selected_tab = Tab::Device("none".to_string());
                        }

                        Task::batch(vec![wait_task])
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
                                _ => {
                                    debug!("Unhandled Control Command Status: {:?}", status);
                                }
                            },
                            AACPEvent::BatteryInfo(battery_info) => {
                                if let Some(DeviceState::AirPods(state)) =
                                    self.device_states.get_mut(&mac)
                                {
                                    state.battery = battery_info;
                                    debug!("Updated battery info for {}: {:?}", mac, state.battery);
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
                }
            }
            Message::UiChannelClosed => {
                error!("Backend UI channel closed; exiting");
                self.apply_lifecycle_event(LifecycleEvent::BackendClosed)
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
                if let Some((name, addr)) = self.pending_add_device.as_ref()
                    && let Some(type_) = self.selected_device_type.as_ref()
                {
                    let devices_path = get_devices_path();
                    let device_key = addr.to_string();
                    let name = name.clone();
                    let type_ = type_.clone();
                    match upsert_device(&devices_path, &device_key, move |existing| {
                        let information = existing.and_then(|device| {
                            if device.type_ == type_ {
                                device.information
                            } else {
                                None
                            }
                        });
                        DeviceData {
                            name,
                            type_,
                            information,
                        }
                    }) {
                        Ok(_) => {
                            self.pending_add_device = None;
                            self.selected_device_type = None;
                            self.selected_tab = Tab::Device(device_key);
                        }
                        Err(error) => {
                            error!("Failed to persist added device: {error}");
                        }
                    }
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
                match self.persist_setting(AppSettingUpdate::TrayTextMode(is_enabled)) {
                    Ok(()) => self.tray_text_mode = is_enabled,
                    Err(error) => error!("Failed to persist tray text setting: {error}"),
                }
                Task::none()
            }
            Message::StemControlChanged(is_enabled) => {
                match self.persist_setting(AppSettingUpdate::StemControl(is_enabled)) {
                    Ok(()) => self.stem_control = is_enabled,
                    Err(error) => error!("Failed to persist stem control setting: {error}"),
                }
                Task::none()
            }
        }
    }

    fn view(&self, _id: window::Id) -> Element<'_, Message> {
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
                                            format_battery_summary(&state.battery)
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

                            container(
                                column![
                                    appearance_settings_col,
                                    Space::new().height(Length::from(20)),
                                    tray_text_mode_toggle,
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
        window::close_events().map(Message::WindowClosed)
    }
}

async fn wait_for_message(ui_rx: Arc<Mutex<UnboundedReceiver<BluetoothUIMessage>>>) -> Message {
    let mut rx = ui_rx.lock().await;
    match rx.recv().await {
        Some(msg) => Message::BluetoothMessage(msg),
        None => Message::UiChannelClosed,
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

#[cfg(test)]
mod tests {
    use super::{
        AppSettingUpdate, LifecycleAction, LifecycleEvent, Message, format_battery_summary,
        lifecycle_action, save_setting_update, wait_for_message, window_icon,
    };
    use crate::bluetooth::aacp::{BatteryComponent, BatteryInfo, BatteryStatus};
    use crate::ui::messages::TrayStatus;
    use crate::utils::MyTheme;
    use serde_json::json;
    use std::sync::Arc;
    use tempfile::tempdir;
    use tokio::sync::Mutex;
    use tokio::sync::mpsc::unbounded_channel;

    #[test]
    fn embedded_window_icon_decodes() {
        assert!(window_icon().is_some());
    }

    #[test]
    fn battery_summary_omits_components_without_readings() {
        let summary = format_battery_summary(&[
            BatteryInfo {
                component: BatteryComponent::Left,
                level: 80,
                status: BatteryStatus::NotCharging,
            },
            BatteryInfo {
                component: BatteryComponent::Right,
                level: 75,
                status: BatteryStatus::NotCharging,
            },
        ]);

        assert!(summary.contains("80%"));
        assert!(summary.contains("75%"));
        assert!(!summary.contains('\u{100E6C}'));
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn main_window_uses_librepods_application_id() {
        assert_eq!(
            super::main_window_settings_for_flatpak_id(None)
                .platform_specific
                .application_id,
            "librepods"
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn main_window_uses_flatpak_application_id_when_available() {
        assert_eq!(
            super::main_window_settings_for_flatpak_id(Some("me.kavishdevar.librepods"))
                .platform_specific
                .application_id,
            "me.kavishdevar.librepods"
        );
    }

    #[test]
    fn each_ui_action_builds_a_single_key_settings_update() {
        let updates = [
            (AppSettingUpdate::Theme(MyTheme::Nord), "theme"),
            (AppSettingUpdate::TrayTextMode(true), "tray_text_mode"),
            (AppSettingUpdate::StemControl(true), "stem_control"),
        ];

        for (update, expected_key) in updates {
            let patch = update.into_patch();
            let object = patch.as_object().unwrap();
            assert_eq!(object.len(), 1);
            assert!(object.contains_key(expected_key));
        }
    }

    #[test]
    fn ui_setting_update_preserves_other_latest_known_values() {
        let temp = tempdir().unwrap();
        let settings_path = temp.path().join("librepods").join("app_settings.json");
        std::fs::create_dir_all(settings_path.parent().unwrap()).unwrap();
        std::fs::write(
            &settings_path,
            json!({
                "theme": "Dracula",
                "tray_text_mode": true,
                "stem_control": true,
            })
            .to_string(),
        )
        .unwrap();

        save_setting_update(
            &settings_path,
            AppSettingUpdate::Theme(MyTheme::SolarizedDark),
        )
        .unwrap();

        let saved: serde_json::Value =
            serde_json::from_slice(&std::fs::read(settings_path).unwrap()).unwrap();
        assert_eq!(saved["theme"], "SolarizedDark");
        assert!(saved["tray_text_mode"].as_bool().unwrap());
        assert!(saved["stem_control"].as_bool().unwrap());
    }

    #[test]
    fn tray_success_keeps_minimized_startup_hidden() {
        assert_eq!(
            lifecycle_action(
                LifecycleEvent::TrayStatusChanged,
                &TrayStatus::Available,
                false,
            ),
            LifecycleAction::Continue
        );
    }

    #[test]
    fn tray_failure_opens_a_window_when_startup_was_minimized() {
        assert_eq!(
            lifecycle_action(
                LifecycleEvent::TrayStatusChanged,
                &TrayStatus::Unavailable("tray failed".to_string()),
                false,
            ),
            LifecycleAction::OpenWindow
        );
    }

    #[test]
    fn tray_failure_does_not_open_a_second_window() {
        assert_eq!(
            lifecycle_action(
                LifecycleEvent::TrayStatusChanged,
                &TrayStatus::Unavailable("tray failed".to_string()),
                true,
            ),
            LifecycleAction::Continue
        );
    }

    #[test]
    fn last_window_close_exits_when_no_tray_exists() {
        assert_eq!(
            lifecycle_action(
                LifecycleEvent::LastWindowClosed,
                &TrayStatus::Unavailable("tray failed".to_string()),
                false,
            ),
            LifecycleAction::Exit
        );
    }

    #[test]
    fn last_window_close_exits_while_tray_is_still_starting() {
        assert_eq!(
            lifecycle_action(
                LifecycleEvent::LastWindowClosed,
                &TrayStatus::Starting,
                false,
            ),
            LifecycleAction::Exit
        );
    }

    #[test]
    fn last_window_close_keeps_running_when_tray_exists() {
        assert_eq!(
            lifecycle_action(
                LifecycleEvent::LastWindowClosed,
                &TrayStatus::Available,
                false,
            ),
            LifecycleAction::Continue
        );
    }

    #[test]
    fn backend_channel_close_is_terminal() {
        assert_eq!(
            lifecycle_action(LifecycleEvent::BackendClosed, &TrayStatus::Available, false),
            LifecycleAction::Exit
        );
    }

    #[tokio::test]
    async fn closed_ui_channel_produces_terminal_message() {
        let (sender, receiver) = unbounded_channel();
        drop(sender);

        let message = wait_for_message(Arc::new(Mutex::new(receiver))).await;

        assert!(matches!(message, Message::UiChannelClosed));
    }
}
