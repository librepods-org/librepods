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
    Space, button, column, combo_box, container, image, row, rule, scrollable, text,
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
    settings.size = Size::new(DEFAULT_WIDTH, 640.0);
    settings.min_size = Some(Size::new(360.0, 420.0));
    // Embed the icon: a relative path is resolved against the current working
    // directory at runtime, which is never the source tree for an installed app.
    settings.icon =
        window::icon::from_file_data(include_bytes!("../../assets/icon.png"), None).ok();
    settings.platform_specific.application_id = APP_ID.to_string();
    settings
}

pub struct App {
    window: Option<window::Id>,
    /// Current window width, for the compact layout.
    window_width: f32,
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
    low_battery_notifications: bool,
    call_audio: crate::utils::CallAudio,
    start_on_login: bool,
    /// Whether the "About this device" card is expanded.
    about_expanded: bool,
    /// Last case battery seen per device, shown while the case is out of reach.
    /// Last live case battery per device, saved across restarts.
    saved_cases: HashMap<String, crate::utils::SavedCase>,
    /// Devices a "Connect" request is in progress for, and the last error per device.
    connecting: std::collections::HashSet<String>,
    connect_errors: HashMap<String, String>,
    /// Saved devices (devices.json), cached instead of re-read on every redraw.
    devices: HashMap<String, DeviceData>,
    /// Rename being typed: sent once typing pauses or Enter is pressed.
    pending_rename: Option<(String, String)>,
    rename_generation: u64,
    /// Pairing helper: `None` idle, `Some(None)` searching, `Some(Some(result))` done.
    pairing: Option<Option<Result<String, String>>>,
    /// Desktop dark/light preference from the XDG portal, for the "System" theme.
    system_dark: Option<bool>,
    /// Bluetooth adapter state: `Some(false)` = off or no adapter, `None` = not known yet.
    bluetooth_on: Option<bool>,
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
    WindowResized(f32),
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
    LowBatteryNotificationsChanged(bool),
    CallAudioChanged(crate::utils::CallAudio),
    StartOnLoginChanged(bool),
    ToggleAboutDevice,
    /// Connect a paired device, e.g. AirPods that are currently with an iPhone.
    ConnectDevice(String),
    ConnectFinished(String, Result<(), String>),
    /// AirPods state read off the UI thread when a device connects.
    AirPodsSnapshot(String, Option<AirPodsState>),
    /// Background work finished; nothing to update.
    Ignore,
    /// The name field changed (not sent to the AirPods yet).
    RenameEdited(String, String),
    /// Send the pending rename: `Some(generation)` from the typing pause timer,
    /// `None` on Enter.
    RenameCommit(Option<u64>),
    /// The status shared with the tray and applet (includes data from BLE
    /// broadcasts, such as the case battery, that the AACP connection lacks).
    SharedStatus(crate::ipc::Status),
    StartPairing,
    SystemDarkChanged(bool),
    BluetoothPowered(bool),
    PairingFinished(Result<String, String>),
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
    Pair,
    AddDevice,
}

/// Sidebar width, and the icon-only width used in narrow windows.
const SIDEBAR_WIDTH: f32 = 220.0;
const DEFAULT_WIDTH: f32 = 920.0;
const SIDEBAR_COMPACT: f32 = 64.0;
/// Below this window width, the sidebar shows icons only and cards stack.
const COMPACT_BELOW: f32 = 720.0;

impl App {
    pub fn new(
        ui_rx: Arc<Mutex<UnboundedReceiver<BluetoothUIMessage>>>,
        start_minimized: bool,
        device_managers: Arc<RwLock<HashMap<String, DeviceManagers>>>,
        // stem_control: Arc<AtomicBool>,
    ) -> (Self, Task<Message>) {


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
        let low_battery_notifications = settings
            .clone()
            .and_then(|v| v.get("low_battery_notifications").cloned())
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
        (
            Self {
                window,
                window_width: DEFAULT_WIDTH,
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
                low_battery_notifications,
                call_audio: crate::utils::load_call_audio(),
                start_on_login: is_autostart_enabled(),
                about_expanded: false,
                saved_cases: crate::utils::load_last_cases(),
                connecting: Default::default(),
                connect_errors: HashMap::new(),
                devices: crate::utils::load_devices(),
                pending_rename: None,
                rename_generation: 0,
                pairing: None,
                system_dark: crate::ui::system_theme::read(),
                bluetooth_on: None,
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
            Message::WindowResized(width) => {
                self.window_width = width;
                Task::none()
            }
            Message::SelectTab(tab) => {
                self.selected_tab = tab;
                Task::none()
            }
            Message::ThemeSelected(theme) => {
                self.selected_theme = theme;
                self.save_settings();
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

                        // A newly paired device is saved when it first connects.
                        if !self.devices.contains_key(&mac) {
                            self.devices = crate::utils::load_devices();
                        }
                        let type_ = self.devices.get(&mac).map(|d| d.type_.clone());
                        match type_ {
                            Some(DeviceType::AirPods) => {
                                let name = self.devices.get(&mac).map(|d| d.name.clone())
                                    .unwrap_or_else(|| "AirPods".to_string());
                                let managers = Arc::clone(&self.device_managers);
                                let mac_for_msg = mac.clone();
                                return Task::batch(vec![
                                    wait_task,
                                    Task::perform(
                                        airpods_snapshot(managers, mac.clone(), name),
                                        move |state| Message::AirPodsSnapshot(mac_for_msg.clone(), state),
                                    ),
                                ]);
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
                        // First connection of new AirPods: they're saved to devices.json
                        // shortly after connecting, so pick them up on their first event.
                        if !self.device_states.contains_key(&mac)
                            && self.bluetooth_state.connected_devices.contains(&mac)
                        {
                            self.devices = crate::utils::load_devices();
                            if let Some(d) = self.devices.get(&mac)
                                && d.type_ == DeviceType::AirPods
                            {
                                let managers = Arc::clone(&self.device_managers);
                                let (name, m) = (d.name.clone(), mac.clone());
                                return Task::batch(vec![
                                    wait_task,
                                    Task::perform(airpods_snapshot(managers, mac.clone(), name), move |st| {
                                        Message::AirPodsSnapshot(m.clone(), st)
                                    }),
                                ]);
                            }
                        }
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
                                if let Some(case) = battery_info.iter().find(|b| b.component == BatteryComponent::Case) {
                                    self.remember_case(&mac, case);
                                }
                                if let Some(DeviceState::AirPods(state)) =
                                    self.device_states.get_mut(&mac)
                                {
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
                    self.devices = crate::utils::load_devices();
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
            Message::Ignore => Task::none(),
            Message::BluetoothPowered(on) => {
                self.bluetooth_on = Some(on);
                Task::none()
            }
            Message::SystemDarkChanged(dark) => {
                debug!("Desktop color scheme changed: {}", if dark { "dark" } else { "light" });
                self.system_dark = Some(dark);
                Task::none()
            }
            Message::StartPairing => {
                self.pairing = Some(None);
                Task::perform(crate::bluetooth::discovery::pair_new_airpods(), Message::PairingFinished)
            }
            Message::PairingFinished(result) => {
                self.pairing = Some(Some(result));
                Task::none()
            }
            Message::SharedStatus(status) => {
                let mac = self.bluetooth_state.connected_devices.iter().find(|m| {
                    self.devices.get(*m).is_some_and(|d| d.type_ == DeviceType::AirPods)
                }).cloned();
                if let (Some(mac), Some(case)) = (mac, status.case) {
                    let info = BatteryInfo {
                        component: BatteryComponent::Case,
                        level: case.level,
                        status: if !case.connected {
                            BatteryStatus::Disconnected
                        } else if case.charging {
                            BatteryStatus::Charging
                        } else {
                            BatteryStatus::NotCharging
                        },
                    };
                    self.remember_case(&mac, &info);
                    // Only fill in a live case reading the AirPods didn't give us directly.
                    if case.connected
                        && let Some(DeviceState::AirPods(state)) = self.device_states.get_mut(&mac)
                        && !state.battery.iter().any(|b| b.component == BatteryComponent::Case)
                    {
                        state.battery.push(info);
                    }
                }
                Task::none()
            }
            Message::RenameEdited(mac, name) => {
                if let Some(DeviceState::AirPods(state)) = self.device_states.get_mut(&mac) {
                    state.device_name = name.clone();
                }
                self.pending_rename = Some((mac, name));
                self.rename_generation += 1;
                let generation = self.rename_generation;
                Task::perform(
                    async { tokio::time::sleep(std::time::Duration::from_millis(800)).await },
                    move |_| Message::RenameCommit(Some(generation)),
                )
            }
            Message::RenameCommit(generation) => {
                if generation.is_some_and(|g| g != self.rename_generation) {
                    return Task::none(); // more typing happened since
                }
                match self.pending_rename.take() {
                    Some((mac, name)) if !name.trim().is_empty() => self.update(
                        Message::AirPodsChanged(mac, AirPodsChange::DeviceName(name.trim().to_string())),
                    ),
                    _ => Task::none(),
                }
            }
            Message::AirPodsSnapshot(mac, state) => {
                match state {
                    Some(state) if self.bluetooth_state.connected_devices.contains(&mac) => {
                        if let Some(case) = state.battery.iter().find(|b| b.component == BatteryComponent::Case) {
                            self.remember_case(&mac, &case);
                        }
                        self.device_states.insert(mac, DeviceState::AirPods(state));
                    }
                    Some(_) => debug!("Ignoring snapshot for {}: no longer connected", mac),
                    None => error!("AirPods {} connected but no AACP manager was found", mac),
                }
                Task::none()
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
                {
                    let managers = Arc::clone(&self.device_managers);
                    let (mac, change) = (mac.clone(), change.clone());
                    crate::utils::spawn_bg(async move {
                        let aacp = managers.read().await.get(&mac).and_then(|m| m.get_aacp());
                        match aacp {
                            Some(aacp) => crate::ui::airpods::send_change(aacp, &change),
                            None => error!("No AACP manager for {}; change not sent", mac),
                        }
                    });
                }
                if let AirPodsChange::DeviceName(name) = &change {
                    save_device_name(&mac, name);
                    if let Some(d) = self.devices.get_mut(&mac) {
                        d.name = name.clone();
                    }
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
                let type_ = self.devices.get(&mac).map(|d| d.type_.clone());
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
                self.save_settings();
                Task::none()
            }
            Message::CallAudioChanged(choice) => {
                self.call_audio = choice;
                self.save_settings();
                Task::none()
            }
            Message::LowBatteryNotificationsChanged(enabled) => {
                self.low_battery_notifications = enabled;
                self.save_settings();
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
                self.save_settings();
                Task::none()
            }
        }
    }

    fn view(&self, _id: window::Id) -> Element<'_, Message> {
        let devices_list = &self.devices;
        let compact = self.is_compact();
        let (title, body): (String, Element<'_, Message>) = match &self.selected_tab {
            Tab::Device(id) if id == "none" => (
                "Overview".to_string(),
                container(
                    column![
                        text(components::glyph::AIRPODS).size(48).style(components::muted_text),
                        text("No device selected").size(16).font(components::bold()),
                        text("Connect your AirPods and they'll appear in the sidebar.")
                            .size(13)
                            .style(components::muted_text)
                            .align_x(Center),
                    ]
                    .spacing(8)
                    .align_x(Center),
                )
                .center_x(Length::Fill)
                .padding(40)
                .into(),
            ),
            Tab::Device(id) => ("Overview".to_string(), self.device_page(id, devices_list)),
            Tab::Settings => ("Settings".to_string(), self.settings_page()),
            Tab::Pair => ("Pair new AirPods".to_string(), self.pair_page()),
            Tab::AddDevice => ("Add device".to_string(), self.add_device_view(devices_list)),
        };
        row![
            container(self.sidebar(devices_list))
                .width(if compact { SIDEBAR_COMPACT } else { SIDEBAR_WIDTH })
                .height(Length::Fill),
            rule::vertical(1).style(|theme: &Theme| rule::Style {
                color: theme.extended_palette().background.base.text.scale_alpha(0.08),
                radius: Radius::from(0.0),
                fill_mode: FillMode::Full,
                snap: true,
            }),
            column![
                components::page_header(title, None),
                scrollable(body).height(Length::Fill),
            ]
            .width(Length::Fill),
        ]
        .into()
    }

    fn theme(&self, _id: window::Id) -> Option<Theme> {
        match (self.selected_theme, self.system_dark) {
            // Choose explicitly; see ui::system_theme for why not to leave it to iced.
            (MyTheme::System, Some(true)) => Some(Theme::Dark),
            (MyTheme::System, Some(false)) => Some(Theme::Light),
            _ => self.selected_theme.to_theme(),
        }
    }

    fn subscription(&self) -> Subscription<Message> {
        Subscription::batch([
            window::close_events().map(Message::WindowClosed),
            Subscription::run(shared_status_stream),
            window::resize_events().map(|(_, size)| Message::WindowResized(size.width)),
            Subscription::run(crate::ui::system_theme::changes).map(Message::SystemDarkChanged),
            Subscription::run(bluetooth_power_stream).map(Message::BluetoothPowered),
        ])
    }
}

impl App {
    /// Saves a live case reading (not one the AirPods merely remember).
    fn remember_case(&mut self, mac: &str, case: &BatteryInfo) {
        if case.status == BatteryStatus::Disconnected {
            return;
        }
        let now = crate::utils::now_unix();
        let reading = crate::utils::SavedCase {
            level: case.level,
            charging: case.status == BatteryStatus::Charging,
            at: now,
        };
        let unchanged = self.saved_cases.get(mac).is_some_and(|prev| {
            prev.level == reading.level && prev.charging == reading.charging && now.saturating_sub(prev.at) < 60
        });
        if !unchanged {
            self.saved_cases.insert(mac.to_string(), reading);
            crate::utils::save_last_cases(&self.saved_cases);
        }
    }

    fn is_compact(&self) -> bool {
        self.window_width < COMPACT_BELOW
    }

    /// Writes app settings, keeping keys this window doesn't know about.
    fn save_settings(&self) {
        let path = get_app_settings_path();
        let mut settings = std::fs::read_to_string(&path)
            .ok()
            .and_then(|s| serde_json::from_str::<serde_json::Value>(&s).ok())
            .filter(|v| v.is_object())
            .unwrap_or_else(|| serde_json::json!({}));
        merge_settings(&mut settings, serde_json::json!({
            "theme": self.selected_theme,
            "tray_text_mode": self.tray_text_mode,
            "stem_control": self.stem_control,
            "low_battery_notifications": self.low_battery_notifications,
            "call_audio": self.call_audio,
        }));
        debug!("Writing settings to {}: {}", path.display(), settings);
        if let Err(e) = std::fs::write(&path, settings.to_string()) {
            error!("Failed to save settings: {}", e);
        }
    }

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
        let compact = self.is_compact();
        let connected_name = self.connected_device_name(devices_list);
        let icon = image(image::Handle::from_bytes(include_bytes!("../../assets/icon.png").as_slice()))
            .width(32)
            .height(32);
        let header: Element<'_, Message> = if compact {
            container(icon).center_x(Length::Fill).into()
        } else {
            row![
                icon,
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
            .padding([4, 6])
            .into()
        };

        let mut devices = column![].spacing(4);
        if !compact {
            devices = devices.push(
                text("DEVICES").size(11).font(components::bold()).style(components::muted_text),
            );
        }
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
                compact,
            ));
        }
        if devices_list.is_empty() && !compact {
            devices = devices.push(
                text("Connect your AirPods to get started.").size(12).style(components::muted_text),
            );
        }

        // Device status is already on each device item; the footer only warns
        // about Bluetooth being off, which nothing else shows.
        let warning_style = |theme: &Theme| text::Style {
            color: Some(theme.extended_palette().warning.base.color),
        };
        let bluetooth_off = self.bluetooth_on == Some(false);
        let mut footer = column![].spacing(4).padding(if compact { [4, 0] } else { [4, 6] });
        if bluetooth_off {
            footer = footer.push(if compact {
                Element::from(container(text("\u{26A0}").size(16).style(warning_style)).center_x(Length::Fill))
            } else {
                text("\u{26A0} Bluetooth is off").size(12).style(warning_style).into()
            });
        }
        if !compact {
            footer = footer.push(
                text(format!("LibrePods v{}", env!("CARGO_PKG_VERSION"))).size(11).style(components::muted_text),
            );
        }

        column![
            header,
            Space::new().height(16),
            devices,
            Space::new().height(8),
            components::nav_item(
                "+",
                "Pair new AirPods".to_string(),
                None,
                self.selected_tab == Tab::Pair,
                Message::SelectTab(Tab::Pair),
                compact,
            ),
            Space::new().height(Length::Fill),
            components::nav_item(
                components::glyph::GEAR,
                "Settings".to_string(),
                None,
                self.selected_tab == Tab::Settings,
                Message::SelectTab(Tab::Settings),
                compact,
            ),
            Space::new().height(8),
            footer,
        ]
        .padding(if compact { 8 } else { 12 })
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
                    let last_case = self.saved_cases.get(id).map(|c| {
                        (
                            BatteryInfo { component: BatteryComponent::Case, level: c.level, status: BatteryStatus::Disconnected },
                            crate::utils::format_age(crate::utils::now_unix().saturating_sub(c.at)),
                        )
                    });
                    airpods_view(id, devices_list, state, self.about_expanded, last_case, self.is_compact())
                }
                _ if self.bluetooth_state.connected_devices.iter().any(|m| m == id) => {
                    centered("Connecting to your AirPods…")
                }
                _ => self.not_connected_card(id, devices_list),
            },
            Some(DeviceType::Nothing) => {
                // Never block the UI thread on the managers lock; retry on the next redraw.
                let att = self.device_managers.try_read().ok().and_then(|m| m.get(id).and_then(|m| m.get_att()));
                match (self.device_states.get(id), att) {
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
        let icon = container(text(components::glyph::AIRPODS).size(44).style(components::muted_text))
            .center_x(84)
            .center_y(84);
        // Narrow windows: stack, so the name and hint keep their width.
        let content: Element<'_, Message> = if self.is_compact() {
            column![icon, info, connect].spacing(14).into()
        } else {
            row![icon, info, connect].spacing(20).align_y(Center).into()
        };
        column![components::card(content).padding(20)]
            .padding(if self.is_compact() { [16, 12] } else { [20, 24] })
            .into()
    }

    fn pair_page(&self) -> Element<'_, Message> {
        let searching = matches!(self.pairing, Some(None));
        let steps = [
            "1. If the AirPods are connected to your iPhone, turn off Bluetooth on the iPhone.",
            "2. Put both AirPods in the case and open the lid near this computer.",
            "3. Hold the button on the back of the case until the light flashes white.",
            "4. Press Start pairing.",
        ];
        let mut instructions = column![].spacing(6);
        for step in steps {
            instructions = instructions.push(text(step).size(14));
        }
        let start = button(
            text(if searching { "Searching…" } else { "Start pairing" })
                .size(14)
                .align_x(Center)
                .width(Length::Fill),
        )
        .width(160)
        .padding([8, 16])
        .style(|theme: &Theme, status| {
            let p = theme.extended_palette();
            button::Style {
                background: Some(Background::Color(match status {
                    button::Status::Hovered => p.primary.strong.color,
                    button::Status::Disabled => p.primary.weak.color,
                    _ => p.primary.base.color,
                })),
                text_color: p.primary.base.text,
                border: Border::default().rounded(10),
                ..Default::default()
            }
        })
        .on_press_maybe((!searching).then_some(Message::StartPairing));
        let mut body = column![
            components::card(column![instructions, start].spacing(16)).padding(20),
        ]
        .spacing(12);
        let status: Option<(String, bool)> = match &self.pairing {
            Some(None) => Some(("Searching for AirPods in pairing mode (up to a minute)…".to_string(), true)),
            Some(Some(Ok(name))) => Some((format!("{name} paired. They'll appear in the sidebar once connected."), true)),
            Some(Some(Err(e))) => Some((e.clone(), false)),
            None => None,
        };
        if let Some((msg, ok)) = status {
            body = body.push(text(msg).size(13).style(move |theme: &Theme| text::Style {
                color: Some(if ok {
                    components::muted(theme)
                } else {
                    theme.extended_palette().danger.base.color
                }),
            }));
        }
        body = body.push(
            text("LibrePods pairs over classic Bluetooth (BR/EDR). Pairing through the AirPods' \"Find My\" identity is what usually makes pairing fail on Linux.")
                .size(12)
                .style(components::muted_text),
        );
        body.padding(if self.is_compact() { [16, 12] } else { [20, 24] }).into()
    }

    fn settings_page(&self) -> Element<'_, Message> {
        let compact = self.is_compact();
        fn wide_row<'a>(compact: bool, title: &'a str, sub: Option<String>, control: Element<'a, Message>) -> Element<'a, Message> {
            if compact {
                components::stacked_row(title, sub, control)
            } else {
                components::setting_row(title, sub, control)
            }
        }
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
                wide_row(
                    compact,
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
                    .width(if compact { Length::Fill } else { Length::Fixed(260.0) })
                    .into(),
                ),
                wide_row(
                    compact,
                    "More themes",
                    Some("Dracula, Nord, Catppuccin, Tokyo Night and more.".to_string()),
                    combo_box(
                        &self.theme_state,
                        "Select theme",
                        Some(&self.selected_theme),
                        Message::ThemeSelected,
                    )
                    .width(if compact { Length::Fill } else { Length::Fixed(200.0) })
                    .into(),
                ),
            ]),
            components::section("Calls (advanced)", {
                let mut rows = vec![wide_row(
                    compact,
                    "Call audio",
                    Some(
                        match self.call_audio {
                            crate::utils::CallAudio::Auto => "Recommended. Keeps the system's call codec. LibrePods still fixes calls that drop to music mode mid-call.",
                            crate::utils::CallAudio::Reliable => "mSBC, applied when a call starts. Doesn't work on every Bluetooth adapter and can leave calls silent.",
                            crate::utils::CallAudio::Quality => "The best codec your system offers (LC3 on recent PipeWire), applied when a call starts.",
                            crate::utils::CallAudio::Compatible => "CVSD (phone quality), applied when a call starts. Doesn't work on every Bluetooth adapter and can leave calls silent.",
                        }
                        .to_string(),
                    ),
                    container(components::segmented(
                        vec![
                            (crate::utils::CallAudio::Auto, "Automatic".to_string()),
                            (crate::utils::CallAudio::Reliable, "Reliable".to_string()),
                            (crate::utils::CallAudio::Quality, "Quality".to_string()),
                            (crate::utils::CallAudio::Compatible, "Compatible".to_string()),
                        ],
                        Some(self.call_audio),
                        Message::CallAudioChanged,
                    ))
                    .width(if compact { Length::Fill } else { Length::Fixed(380.0) })
                    .into(),
                )];
                // Tell the user when their choice was found not to work.
                if let Some(target) = self.call_audio.profile()
                    && crate::media_controller::silent_call_profiles().iter().any(|p| p == target)
                {
                    rows.push(
                        container(
                            text("This option didn't work on your AirPods during the last call, so LibrePods isn't applying it. Choose it again to retry, or use Automatic.")
                                .size(12)
                                .style(|theme: &Theme| text::Style {
                                    color: Some(theme.extended_palette().warning.base.color),
                                }),
                        )
                        .padding([0, 16])
                        .into(),
                    );
                }
                rows.push(
                    container(
                        text("Changes apply from the next call; a call in progress isn't switched.")
                            .size(12)
                            .style(components::muted_text),
                    )
                    .padding(iced::Padding { top: 0.0, right: 16.0, bottom: 12.0, left: 16.0 })
                    .into(),
                );
                rows
            }),
            components::section("Notifications", vec![
                components::setting_row(
                    "Low battery",
                    Some("Notify when an AirPod or the case drops to 20% and 10%.".to_string()),
                    toggler(self.low_battery_notifications)
                        .on_toggle(Message::LowBatteryNotificationsChanged)
                        .size(22),
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
        .padding(if compact { [16, 12] } else { [20, 24] })
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

/// Reports whether a Bluetooth adapter exists and is powered, on change.
fn bluetooth_power_stream() -> impl futures::Stream<Item = bool> {
    iced::stream::channel(4, async |mut out: futures::channel::mpsc::Sender<bool>| {
        use futures::SinkExt;
        let mut last = None;
        // Keep one BlueZ session: a new one every poll made BlueZ log an
        // "Adv Monitor app disconnected" and re-process its monitors each time.
        let mut session: Option<bluer::Session> = None;
        loop {
            if session.is_none() {
                session = bluer::Session::new().await.ok();
            }
            let on = match &session {
                Some(s) => match s.default_adapter().await {
                    Ok(adapter) => adapter.is_powered().await.unwrap_or(false),
                    Err(_) => false,
                },
                None => false,
            };
            if last != Some(on) {
                last = Some(on);
                if out.send(on).await.is_err() {
                    break;
                }
            }
            tokio::time::sleep(std::time::Duration::from_secs(3)).await;
        }
    })
}

/// Streams the shared status into the window. Waits for the Bluetooth side to
/// start, since the window can open first.
fn shared_status_stream() -> impl futures::Stream<Item = Message> {
    iced::stream::channel(8, async |mut out: futures::channel::mpsc::Sender<Message>| {
        use futures::SinkExt;
        let mut rx = loop {
            if let Some(rx) = crate::ipc::global_status() {
                break rx;
            }
            tokio::time::sleep(std::time::Duration::from_millis(500)).await;
        };
        loop {
            let status = rx.borrow_and_update().clone();
            if out.send(Message::SharedStatus(status)).await.is_err() || rx.changed().await.is_err() {
                break;
            }
        }
    })
}

/// Builds the window's AirPods state from the AACP manager, off the UI thread.
async fn airpods_snapshot(
    managers: Arc<RwLock<HashMap<String, DeviceManagers>>>,
    mac: String,
    device_name: String,
) -> Option<AirPodsState> {
    let aacp = managers.read().await.get(&mac).and_then(|m| m.get_aacp())?;
    let state = aacp.state.lock().await;
    let flag = |id: ControlCommandIdentifiers| {
        state
            .control_command_status_list
            .iter()
            .any(|s| s.identifier == id && matches!(s.value.as_slice(), [0x01]))
    };
    let allow_off = flag(ControlCommandIdentifiers::AllowOffOption);
    Some(AirPodsState {
        device_name,
        battery: state.battery_info.clone(),
        noise_control_mode: state
            .control_command_status_list
            .iter()
            .find_map(|s| {
                (s.identifier == ControlCommandIdentifiers::ListeningMode)
                    .then(|| s.value.first().map(AirPodsNoiseControlMode::from_byte))
                    .flatten()
            })
            .unwrap_or(AirPodsNoiseControlMode::Transparency),
        noise_control_state: noise_control_modes(allow_off),
        conversation_awareness_enabled: flag(ControlCommandIdentifiers::ConversationDetectConfig),
        personalized_volume_enabled: flag(ControlCommandIdentifiers::AdaptiveVolumeConfig),
        allow_off_mode: allow_off,
        ear_status: state.ear_detection_status.clone(),
    })
}

/// Overwrites the keys in `update` inside `settings`, leaving other keys alone.
fn merge_settings(settings: &mut serde_json::Value, update: serde_json::Value) {
    if let (Some(target), serde_json::Value::Object(src)) = (settings.as_object_mut(), update) {
        target.extend(src);
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

#[cfg(test)]
mod tests {
    use super::merge_settings;
    use serde_json::json;

    #[test]
    fn merging_settings_keeps_unknown_keys() {
        let mut settings = json!({"theme": "Dark", "future_option": 42});
        merge_settings(&mut settings, json!({"theme": "System", "stem_control": true}));
        assert_eq!(settings, json!({"theme": "System", "stem_control": true, "future_option": 42}));
    }
}
