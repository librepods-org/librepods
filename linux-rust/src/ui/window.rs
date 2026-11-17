use crate::bluetooth::aacp::{
    AACPEvent, BatteryComponent, BatteryInfo, BatteryStatus, ControlCommandIdentifiers,
};
use crate::bluetooth::managers::DeviceManagers;
use crate::devices::enums::{
    AirPodsNoiseControlMode, AirPodsState, DeviceData, DeviceState, DeviceType, NothingAncMode,
    NothingState,
};
use crate::ui::airpods::airpods_view;
use crate::ui::messages::BluetoothUIMessage;
use crate::ui::nothing::nothing_view;
use crate::utils::{MyTheme, get_app_settings_path, get_devices_path, is_autostart_enabled, set_autostart};
use bluer::{Address};
use iced::border::Radius;
use iced::overlay::menu;
use iced::widget::button::Style;
use iced::widget::rule::FillMode;
use iced::widget::{
    Space, button, column, combo_box, container, image, pane_grid, row, rule, scrollable, text,
    text_input, toggler
};
use crate::ui::components;
use iced::{Background, Border, Center, Element, Font, Length, Size, Subscription, Task, Theme, daemon, window, Settings, Program};
use log::{debug, error};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
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
        // On Wayland this becomes the xdg app_id; it must match the .desktop file
        // basename so COSMIC (and other compositors) can show the right icon/name
        // in the dock and app switcher.
        id: Some(APP_ID.to_string()),
        fonts: vec![include_bytes!("../../assets/font/sf_pro.otf").as_slice().into()],
        default_font: Font::with_name("SF Pro Text"),
        ..Settings::default()
    })
    .run()
}

pub const APP_ID: &str = "me.kavishdevar.librepods";

fn main_window_settings() -> window::Settings {
    let mut settings = window::Settings::default();
    settings.min_size = Some(Size::new(400.0, 300.0));
    // Embed the icon: a relative path is resolved against the current working
    // directory at runtime, which is never the source tree for an installed app.
    settings.icon =
        window::icon::from_file_data(include_bytes!("../../assets/icon.png"), None).ok();
    settings.platform_specific.application_id = APP_ID.to_string();
    settings
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
    start_on_login: bool,
    /// Whether the "About this device" card is expanded.
    about_expanded: bool,
    /// Last case battery seen per device, shown while the case is out of reach.
    last_case: HashMap<String, BatteryInfo>,
    /// Devices a "Connect" request is in progress for, and the last error per device.
    connecting: std::collections::HashSet<String>,
    connect_errors: HashMap<String, String>,
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
    /// A single user change to an AirPods setting. Applied to the current state so
    /// a stale snapshot from the last render can't overwrite newer values reported
    /// by the device (upstream issue #423).
    AirPodsChanged(String, AirPodsChange),
    TrayTextModeChanged(bool), // yes, I know I should add all settings to a struct, but I'm lazy
    StemControlChanged(bool),
    StartOnLoginChanged(bool),
    ToggleAboutDevice,
    /// Connect a paired device, e.g. AirPods that are currently with an iPhone.
    ConnectDevice(String),
    ConnectFinished(String, Result<(), String>),
}

#[derive(Clone, Debug)]
pub enum AirPodsChange {
    DeviceName(String),
    NoiseControlMode(AirPodsNoiseControlMode),
    ConversationAwareness(bool),
    PersonalizedVolume(bool),
    AllowOffMode(bool),
}

fn noise_control_modes(allow_off: bool) -> combo_box::State<AirPodsNoiseControlMode> {
    let mut modes = vec![
        AirPodsNoiseControlMode::Transparency,
        AirPodsNoiseControlMode::NoiseCancellation,
        AirPodsNoiseControlMode::Adaptive,
    ];
    if allow_off {
        modes.insert(0, AirPodsNoiseControlMode::Off);
    }
    combo_box::State::new(modes)
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
            let settings = main_window_settings();
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
            .unwrap_or(MyTheme::System);
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
                panes,
                selected_tab: Tab::Device("none".to_string()),
                theme_state: combo_box::State::new(vec![
                    MyTheme::System,
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
                start_on_login: is_autostart_enabled(),
                about_expanded: false,
                last_case: HashMap::new(),
                connecting: Default::default(),
                connect_errors: HashMap::new(),
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
                self.window = Some(id);
                Task::none()
            }
            Message::WindowClosed(id) => {
                if self.window == Some(id) {
                    self.window = None;
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
                        if let Some(window_id) = self.window {
                            Task::batch(vec![window::gain_focus(window_id), wait_task])
                        } else {
                            let settings = main_window_settings();
                            let (new_window_task, open_task) = window::open(settings);
                            self.window = Some(new_window_task);
                            Task::batch(vec![open_task.map(Message::WindowOpened), wait_task])
                        }
                    }
                    BluetoothUIMessage::DeviceConnected(mac) => {
                        let ui_rx = Arc::clone(&self.ui_rx);
                        let wait_task = Task::perform(wait_for_message(ui_rx), |msg| msg);
                        self.connecting.remove(&mac);
                        self.connect_errors.remove(&mac);
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
                                    if e.kind() != std::io::ErrorKind::NotFound { error!("Failed to read devices file: {}", e); }
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
                                            if e.kind() != std::io::ErrorKind::NotFound { error!("Failed to read devices file: {}", e); }
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
                                    ear_status: state.ear_detection_status.clone(),
                                }));
                                if let Some(case) = state.battery_info.iter().find(|b| b.component == BatteryComponent::Case) {
                                    self.last_case.insert(mac.clone(), BatteryInfo { status: BatteryStatus::Disconnected, ..case.clone() });
                                }
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
                                    if let Some(case) = battery_info.iter().find(|b| b.component == BatteryComponent::Case) {
                                        self.last_case.insert(mac.clone(), BatteryInfo { status: BatteryStatus::Disconnected, ..case.clone() });
                                    }
                                    state.battery = battery_info;
                                    debug!("Updated battery info for {}: {:?}", mac, state.battery);
                                }
                            }
                            AACPEvent::EarDetection(_, new_status) => {
                                if let Some(DeviceState::AirPods(state)) =
                                    self.device_states.get_mut(&mac)
                                {
                                    state.ear_status = new_status;
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
                        if e.kind() != std::io::ErrorKind::NotFound { error!("Failed to read devices file: {}", e); }
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
            Message::ConnectDevice(mac) => {
                let Ok(addr) = mac.parse::<Address>() else {
                    return Task::none();
                };
                self.connect_errors.remove(&mac);
                self.connecting.insert(mac.clone());
                Task::perform(
                    async move { crate::bluetooth::discovery::connect_device(addr).await },
                    move |result| Message::ConnectFinished(mac.clone(), result),
                )
            }
            Message::ConnectFinished(mac, result) => {
                self.connecting.remove(&mac);
                if let Err(e) = result {
                    self.connect_errors.insert(mac, e);
                }
                Task::none()
            }
            Message::ToggleAboutDevice => {
                self.about_expanded = !self.about_expanded;
                Task::none()
            }
            Message::AirPodsChanged(mac, change) => {
                // Send the change to the AirPods here, not in the view: buttons build
                // their message while rendering, so the view must stay side-effect free.
                let aacp = self
                    .device_managers
                    .blocking_read()
                    .get(&mac)
                    .and_then(|m| m.get_aacp());
                match aacp {
                    Some(aacp) => crate::ui::airpods::send_change(aacp, &change),
                    None => error!("No AACP manager for {}; change not sent", mac),
                }
                if let AirPodsChange::DeviceName(name) = &change {
                    save_device_name(&mac, name);
                    crate::ipc::set_device_name(name.clone());
                }
                if let Some(DeviceState::AirPods(state)) = self.device_states.get_mut(&mac) {
                    match change {
                        AirPodsChange::DeviceName(name) => state.device_name = name,
                        AirPodsChange::NoiseControlMode(mode) => state.noise_control_mode = mode,
                        AirPodsChange::ConversationAwareness(v) => {
                            state.conversation_awareness_enabled = v
                        }
                        AirPodsChange::PersonalizedVolume(v) => state.personalized_volume_enabled = v,
                        AirPodsChange::AllowOffMode(v) => {
                            state.allow_off_mode = v;
                            state.noise_control_state = noise_control_modes(v);
                        }
                    }
                }
                Task::none()
            }
            Message::StateChanged(mac, state) => {
                self.device_states.insert(mac.clone(), state);
                // if airpods, update the noise control state combo box based on allow off mode
                let type_ = {
                    let devices_json =
                        std::fs::read_to_string(get_devices_path()).unwrap_or_else(|e| {
                            if e.kind() != std::io::ErrorKind::NotFound { error!("Failed to read devices file: {}", e); }
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
                });
                debug!(
                    "Writing settings to {}: {}",
                    app_settings_path.to_str().unwrap(),
                    settings
                );
                std::fs::write(app_settings_path, settings.to_string()).ok();
                Task::none()
            }
            Message::StartOnLoginChanged(is_enabled) => {
                match set_autostart(is_enabled) {
                    Ok(()) => self.start_on_login = is_enabled,
                    Err(e) => log::error!("Failed to update autostart entry: {}", e),
                }
                Task::none()
            }
            Message::StemControlChanged(is_enabled) => {
                self.stem_control = is_enabled;
                let app_settings_path = get_app_settings_path();
                let settings = serde_json::json!({
                    "theme": self.selected_theme,
                    "tray_text_mode": self.tray_text_mode,
                    "stem_control": self.stem_control,
                });
                debug!(
                    "Writing settings to {}: {}",
                    app_settings_path.to_str().unwrap(),
                    settings
                );
                std::fs::write(app_settings_path, settings.to_string()).ok();
                Task::none()
            }
        }
    }

    fn view(&self, _id: window::Id) -> Element<'_, Message> {
        let devices_json = std::fs::read_to_string(get_devices_path()).unwrap_or_else(|e| {
            if e.kind() != std::io::ErrorKind::NotFound { error!("Failed to read devices file: {}", e); }
            "{}".to_string()
        });
        let devices_list: HashMap<String, DeviceData> = serde_json::from_str(&devices_json)
            .unwrap_or_else(|e| {
                error!("Deserialization failed: {}", e);
                HashMap::new()
            });
        let pane_grid = pane_grid::PaneGrid::new(&self.panes, |_pane_id, pane, _is_maximized| {
            match pane {
                Pane::Sidebar => pane_grid::Content::new(row![
                    self.sidebar(&devices_list),
                    rule::vertical(1).style(|theme: &Theme| rule::Style {
                        color: theme.extended_palette().background.base.text.scale_alpha(0.08),
                        radius: Radius::from(0.0),
                        fill_mode: FillMode::Full,
                        snap: true,
                    }),
                ]),
                Pane::Content => {
                    let (title, body): (String, Element<'_, Message>) = match &self.selected_tab {
                        Tab::Device(id) if id == "none" => (
                            "Overview".to_string(),
                            container(
                                column![
                                    text(components::glyph::AIRPODS).size(48).style(components::muted_text),
                                    text("No device selected").size(16).font(components::bold()),
                                    text("Connect your AirPods and they'll appear in the sidebar.")
                                        .size(13)
                                        .style(components::muted_text),
                                ]
                                .spacing(8)
                                .align_x(Center),
                            )
                            .center_x(Length::Fill)
                            .center_y(Length::Fill)
                            .into(),
                        ),
                        Tab::Device(id) => ("Overview".to_string(), self.device_page(id, &devices_list)),
                        Tab::Settings => ("Settings".to_string(), self.settings_page()),
                        Tab::AddDevice => ("Add device".to_string(), self.add_device_view(&devices_list)),
                    };
                    pane_grid::Content::new(column![
                        components::page_header(title, None),
                        scrollable(body).height(Length::Fill),
                    ])
                }
            }
        })
            .width(Length::Fill)
            .height(Length::Fill)
            .on_resize(20, Message::Resized);

        container(pane_grid).into()
    }

    fn theme(&self, _id: window::Id) -> Option<Theme> {
        self.selected_theme.to_theme()
    }

    fn subscription(&self) -> Subscription<Message> {
        window::close_events().map(Message::WindowClosed)
    }
}

impl App {
    /// Current name: the live one from the AirPods (updated on rename), falling
    /// back to the saved one.
    fn device_name(&self, mac: &str, devices_list: &HashMap<String, DeviceData>) -> Option<String> {
        match self.device_states.get(mac) {
            Some(DeviceState::AirPods(state)) if !state.device_name.is_empty() => {
                Some(state.device_name.clone())
            }
            _ => devices_list.get(mac).map(|d| d.name.clone()),
        }
    }

    fn connected_device_name(&self, devices_list: &HashMap<String, DeviceData>) -> Option<String> {
        self.bluetooth_state
            .connected_devices
            .iter()
            .find_map(|mac| self.device_name(mac, devices_list))
    }

    /// Short battery summary for the sidebar, e.g. "L 99% · R 98% · Case 31%".
    fn battery_summary(&self, mac: &str) -> String {
        match self.device_states.get(mac) {
            Some(DeviceState::AirPods(state)) => {
                let b = &state.battery;
                let level = |c: BatteryComponent| {
                    b.iter().find(|x| x.component == c).map(|x| format!("{}%", x.level))
                };
                if let Some(h) = level(BatteryComponent::Headphone) {
                    return h;
                }
                let parts: Vec<String> = [("L", BatteryComponent::Left), ("R", BatteryComponent::Right), ("Case", BatteryComponent::Case)]
                    .iter()
                    .filter_map(|(n, c)| level(*c).map(|l| format!("{n} {l}")))
                    .collect();
                if parts.is_empty() { "Connected".to_string() } else { parts.join(" · ") }
            }
            _ => "Connected".to_string(),
        }
    }

    fn sidebar(&self, devices_list: &HashMap<String, DeviceData>) -> Element<'_, Message> {
        let connected_name = self.connected_device_name(devices_list);
        let header = row![
            image(image::Handle::from_bytes(include_bytes!("../../assets/icon.png").as_slice()))
                .width(32)
                .height(32),
            column![
                text("LibrePods").size(16).font(components::bold()),
                text(connected_name.clone().unwrap_or_else(|| "No device connected".to_string()))
                    .size(12)
                    .style(components::muted_text),
            ]
            .spacing(1),
        ]
        .spacing(10)
        .align_y(Center)
        .padding([4, 6]);

        let mut devices = column![
            text("DEVICES").size(11).font(components::bold()).style(components::muted_text),
        ]
        .spacing(4);
        let mut devices_vec: Vec<(&String, &DeviceData)> = devices_list.iter().collect();
        devices_vec.sort_by(|a, b| a.1.name.cmp(&b.1.name));
        for (mac, device) in devices_vec {
            let connected = self.bluetooth_state.connected_devices.contains(mac);
            let icon = match device.type_ {
                DeviceType::AirPods => components::glyph::AIRPODS,
                _ => components::glyph::HEADPHONES,
            };
            devices = devices.push(components::nav_item(
                icon,
                self.device_name(mac, devices_list).unwrap_or_else(|| device.name.clone()),
                Some(if connected { self.battery_summary(mac) } else { "Not connected".to_string() }),
                self.selected_tab == Tab::Device(mac.clone()),
                Message::SelectTab(Tab::Device(mac.clone())),
            ));
        }
        if devices_list.is_empty() {
            devices = devices.push(
                text("Connect your AirPods to get started.").size(12).style(components::muted_text),
            );
        }

        let connected = connected_name.is_some();
        let footer = column![
            row![
                container(Space::new().width(8).height(8)).style(move |_: &Theme| {
                    container::Style::default()
                        .background(if connected {
                            iced::Color::from_rgb8(0x2e, 0xc2, 0x7e)
                        } else {
                            iced::Color::from_rgb8(0x9a, 0x9a, 0x9a)
                        })
                        .border(Border::default().rounded(4))
                }),
                text(if connected { "Connected" } else { "Not connected" }).size(12).font(components::bold()),
            ]
            .spacing(8)
            .align_y(Center),
            text(format!("v{}", env!("CARGO_PKG_VERSION"))).size(11).style(components::muted_text),
        ]
        .spacing(4)
        .padding([4, 6]);

        column![
            header,
            Space::new().height(16),
            devices,
            Space::new().height(Length::Fill),
            components::nav_item(
                components::glyph::GEAR,
                "Settings".to_string(),
                None,
                self.selected_tab == Tab::Settings,
                Message::SelectTab(Tab::Settings),
            ),
            Space::new().height(8),
            footer,
        ]
        .padding(12)
        .into()
    }

    fn device_page<'a>(&'a self, id: &'a str, devices_list: &HashMap<String, DeviceData>) -> Element<'a, Message> {
        let centered = |msg: &'a str| -> Element<'a, Message> {
            container(text(msg).size(15).style(components::muted_text))
                .center_x(Length::Fill)
                .padding(40)
                .into()
        };
        match devices_list.get(id).map(|d| d.type_.clone()) {
            Some(DeviceType::AirPods) => match self.device_states.get(id) {
                Some(DeviceState::AirPods(state)) => {
                    airpods_view(id, devices_list, state, self.about_expanded, self.last_case.get(id))
                }
                _ if self.bluetooth_state.connected_devices.iter().any(|m| m == id) => {
                    centered("Connecting to your AirPods…")
                }
                _ => self.not_connected_card(id, devices_list),
            },
            Some(DeviceType::Nothing) => {
                let device_managers = self.device_managers.blocking_read();
                match (self.device_states.get(id), device_managers.get(id).and_then(|m| m.get_att())) {
                    (Some(DeviceState::Nothing(state)), Some(att)) => {
                        nothing_view(id, devices_list, state, att).into()
                    }
                    _ => centered("Waiting for the device to connect…"),
                }
            }
            None => centered("Unsupported device"),
        }
    }

    /// Shown when saved AirPods aren't connected, e.g. after switching to an iPhone.
    fn not_connected_card<'a>(&'a self, mac: &'a str, devices_list: &HashMap<String, DeviceData>) -> Element<'a, Message> {
        let name = self.device_name(mac, devices_list).unwrap_or_else(|| "AirPods".to_string());
        let connecting = self.connecting.contains(mac);
        let mut info = column![
            text(name).size(22).font(components::bold()),
            components::chip("Not connected".to_string(), Some(iced::Color::from_rgb8(0x9a, 0x9a, 0x9a))),
            text("They may be connected to another device, like your iPhone. Take them out of the case and connect them here.")
                .size(13)
                .style(components::muted_text),
        ]
        .spacing(8)
        .width(Length::Fill);
        if let Some(err) = self.connect_errors.get(mac) {
            info = info.push(text(err.clone()).size(13).style(|theme: &Theme| text::Style {
                color: Some(theme.extended_palette().danger.base.color),
            }));
        }
        let connect = button(
            text(if connecting { "Connecting…" } else { "Connect" })
                .size(14)
                .align_x(Center)
                .width(Length::Fill),
        )
        .width(140)
        .padding([8, 16])
        .style(|theme: &Theme, status| {
            let p = theme.extended_palette();
            let bg = match status {
                button::Status::Hovered => p.primary.strong.color,
                button::Status::Disabled => p.primary.weak.color,
                _ => p.primary.base.color,
            };
            button::Style {
                background: Some(Background::Color(bg)),
                text_color: p.primary.base.text,
                border: Border::default().rounded(10),
                ..Default::default()
            }
        })
        .on_press_maybe((!connecting).then(|| Message::ConnectDevice(mac.to_string())));
        column![
            components::card(
                row![
                    container(text(components::glyph::AIRPODS).size(44).style(components::muted_text))
                        .center_x(84)
                        .center_y(84),
                    info,
                    connect,
                ]
                .spacing(20)
                .align_y(Center),
            )
            .padding(20),
        ]
        .padding([20, 24])
        .into()
    }

    fn settings_page(&self) -> Element<'_, Message> {
        let theme_choice = match self.selected_theme {
            t @ (MyTheme::System | MyTheme::Light | MyTheme::Dark) => Some(t),
            _ => None,
        };
        column![
            text("Everything applies instantly.").size(13).style(components::muted_text),
            components::section("General", vec![
                components::setting_row(
                    "Start on login",
                    Some("Launch LibrePods minimized to the tray when you log in.".to_string()),
                    toggler(self.start_on_login).on_toggle(Message::StartOnLoginChanged).size(22),
                ),
            ]),
            components::section("Appearance", vec![
                components::setting_row(
                    "Colors",
                    None,
                    container(components::segmented(
                        vec![
                            (MyTheme::System, "System".to_string()),
                            (MyTheme::Light, "Light".to_string()),
                            (MyTheme::Dark, "Dark".to_string()),
                        ],
                        theme_choice,
                        Message::ThemeSelected,
                    ))
                    .width(260),
                ),
                components::setting_row(
                    "More themes",
                    Some("Dracula, Nord, Catppuccin, Tokyo Night and more.".to_string()),
                    combo_box(
                        &self.theme_state,
                        "Select theme",
                        Some(&self.selected_theme),
                        Message::ThemeSelected,
                    )
                    .width(200),
                ),
            ]),
            components::section("Tray", vec![
                components::setting_row(
                    "Show battery as text",
                    Some("Use a number instead of a ring for the tray icon.".to_string()),
                    toggler(self.tray_text_mode).on_toggle(Message::TrayTextModeChanged).size(22),
                ),
            ]),
            components::section("Controls", vec![
                components::setting_row(
                    "Stem press track control",
                    Some("Double press for next track, triple press for previous. Turn off if your desktop already handles AirPods media buttons.".to_string()),
                    toggler(self.stem_control).on_toggle(Message::StemControlChanged).size(22),
                ),
            ]),
        ]
        .spacing(20)
        .padding([20, 24])
        .into()
    }

    /// Kept from the previous UI; not reachable yet (no "add device" entry point).
    fn add_device_view(&self, devices_list: &HashMap<String, DeviceData>) -> Element<'_, Message> {
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
        .into()
    }
}

/// Saves a renamed device to devices.json so the new name survives restarts.
fn save_device_name(mac: &str, name: &str) {
    let path = get_devices_path();
    let Ok(json) = std::fs::read_to_string(&path) else { return };
    let Ok(mut devices) = serde_json::from_str::<HashMap<String, DeviceData>>(&json) else { return };
    if let Some(device) = devices.get_mut(mac) {
        device.name = name.to_string();
        if let Ok(updated) = serde_json::to_string(&devices)
            && let Err(e) = std::fs::write(&path, updated)
        {
            error!("Failed to save device name: {}", e);
        }
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
