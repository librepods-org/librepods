use crate::bluetooth::aacp::{
    AACPManager, BatteryComponent, BatteryStatus, ControlCommandIdentifiers,
};
use crate::bluetooth::managers::DeviceManagers;
use crate::devices::enums::{
    AirPodsState, DeviceData, DeviceState, DeviceType, NothingAncMode, NothingState,
};
use crate::bluetooth::aacp_parse::{Accommodation, CustomEq};
use crate::devices::commands::stem_forwarding_mask;
use crate::devices::settings::{decode_toggle, rename_request, trim_value};
use crate::ui::airpods::{DEFAULT_ACCOMMODATION, DEFAULT_CUSTOM_EQ};
use crate::ui::airpods::airpods_view;
use crate::ui::messages::BluetoothUIMessage;
use crate::ui::nothing::nothing_view;
use crate::prefs::{DevicePrefs, PrefsStore, load_app_settings, save_app_settings};
use crate::utils::{MyTheme, get_app_settings_path, get_devices_path, update_devices_file};
use bluer::{Address};
use iced::border::Radius;
use iced::overlay::menu;
use iced::widget::button::Style;
use iced::widget::rule::FillMode;
use iced::widget::{
    Space, button, column, combo_box, container, pane_grid, row, rule, scrollable, text,
    text_input, toggler
};
use iced::{Background, Border, Center, Element, Font, Length, Padding, Size, Subscription, Task, Theme, daemon, window, Settings, Program};
use log::{debug, error, warn};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::sync::mpsc::UnboundedReceiver;
use tokio::sync::{Mutex, RwLock};

pub fn start_ui(
    ui_rx: UnboundedReceiver<BluetoothUIMessage>,
    start_minimized: bool,
    device_managers: Arc<RwLock<HashMap<String, DeviceManagers>>>,
    prefs: PrefsStore,
) -> iced::Result {
    let ui_rx = Arc::new(Mutex::new(ui_rx));

    // not sure if this is a good idea
    daemon(
        move || {
            App::new(
                Arc::clone(&ui_rx),
                start_minimized,
                Arc::clone(&device_managers),
                prefs.clone(),
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
    notify_on_connect: bool,
    notify_low_battery: bool,
    prefs: PrefsStore,
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
    /// Set an AirPods control (mac, id, value): shown at once, then sent.
    SetControl(String, ControlCommandIdentifiers, Vec<u8>),
    /// A slider moving: shown only.
    ControlDraft(String, ControlCommandIdentifiers, Vec<u8>),
    /// A slider released: send the value it shows.
    SendControl(String, ControlCommandIdentifiers),
    NameDraft(String, String),
    SubmitName(String),
    ToggleAdvanced(String),
    /// Whether sending something (named) to the AirPods worked.
    CommandSent(String, bool),
    TrayTextModeChanged(bool), // yes, I know I should add all settings to a struct, but I'm lazy
    NotifyOnConnectChanged(bool),
    NotifyLowBatteryChanged(bool),
    /// The custom EQ changed on the page; an on/off change is sent at once, bands on release.
    CustomEqDraft(String, CustomEq),
    SendCustomEq(String),
    /// Headphone accommodation changed; phone/media changes are sent at once, bands on release.
    AccommodationDraft(String, Accommodation),
    SendAccommodation(String),
    /// Save these preferences now (toggles, pickers).
    SetPrefs(String, DevicePrefs),
    /// Preferences being edited (sliders, command text): shown only.
    PrefsDraft(String, DevicePrefs),
    /// Save the preferences being edited.
    SavePrefs(String),
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
        prefs: PrefsStore,
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
            settings.icon = window::icon::from_file("../../assets/icon.png").ok();
            let (id, open) = window::open(settings);
            (Some(id), open.map(Message::WindowOpened))
        };

        let app_settings = load_app_settings(&get_app_settings_path());
        let selected_theme = app_settings.theme;
        let tray_text_mode = app_settings.tray_text_mode;
        let notify_on_connect = app_settings.notify_on_connect;
        let notify_low_battery = app_settings.notify_low_battery;

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
                notify_on_connect,
                notify_low_battery,
                prefs,
            },
            Task::batch(vec![open_task, wait_task]),
        )
    }

    fn title(&self, _id: window::Id) -> String {
        "LibrePods".to_string()
    }

    fn aacp_for(&self, mac: &str) -> Option<Arc<AACPManager>> {
        self.device_managers
            .blocking_read()
            .get(mac)
            .and_then(|managers| managers.get_aacp())
    }

    /// Sends one control command to these AirPods in the background.
    fn send_control(
        &self,
        mac: &str,
        id: ControlCommandIdentifiers,
        value: Vec<u8>,
    ) -> Task<Message> {
        let Some(aacp_manager) = self.aacp_for(mac) else {
            warn!("AirPods {} are not connected; not sending {:?}", mac, id);
            return Task::none();
        };
        debug!("Sending {:?} = {} to {}", id, hex::encode(&value), mac);
        Task::perform(
            async move { aacp_manager.send_control_command(id, &value).await.is_ok() },
            move |ok| Message::CommandSent(format!("{:?}", id), ok),
        )
    }

    fn send_custom_eq(&self, mac: &str, eq: CustomEq) -> Task<Message> {
        let Some(aacp_manager) = self.aacp_for(mac) else {
            warn!("AirPods {} are not connected; not sending the equalizer", mac);
            return Task::none();
        };
        Task::perform(
            async move { aacp_manager.send_custom_eq(eq).await.is_ok() },
            |ok| Message::CommandSent("Custom EQ".to_string(), ok),
        )
    }

    fn send_accommodation(&self, mac: &str, accommodation: Accommodation) -> Task<Message> {
        let Some(aacp_manager) = self.aacp_for(mac) else {
            warn!("AirPods {} are not connected; not sending headphone accommodation", mac);
            return Task::none();
        };
        Task::perform(
            async move {
                aacp_manager
                    .send_headphone_accommodation(&accommodation)
                    .await
                    .is_ok()
            },
            |ok| Message::CommandSent("Headphone Accommodation".to_string(), ok),
        )
    }

    /// Stores this device's preferences; when its stem actions now need different presses
    /// forwarded, tells the AirPods.
    fn save_prefs(&self, mac: &str, prefs: DevicePrefs) -> Task<Message> {
        let before = self.prefs.device(mac);
        if let Err(e) = self.prefs.update(mac, |stored| *stored = prefs.clone()) {
            error!("Failed to save preferences for {}: {}", mac, e);
            return Task::none();
        }
        match stem_config_update(&before, &prefs) {
            Some(mask) => {
                self.send_control(mac, ControlCommandIdentifiers::StemConfig, mask)
            }
            None => Task::none(),
        }
    }

    /// Saves this window's settings on top of the file, so settings it doesn't show survive.
    fn persist_app_settings(&self) {
        let path = get_app_settings_path();
        let mut settings = load_app_settings(&path);
        settings.theme = self.selected_theme;
        settings.tray_text_mode = self.tray_text_mode;
        settings.notify_on_connect = self.notify_on_connect;
        settings.notify_low_battery = self.notify_low_battery;
        debug!("Writing settings to {}: {:?}", path.display(), settings);
        if let Err(e) = save_app_settings(&path, &settings) {
            error!("Failed to save app settings: {}", e);
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
                self.persist_app_settings();
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
                            let mut settings = window::Settings::default();
                            settings.min_size = Some(Size::new(400.0, 300.0));
                            settings.icon = window::icon::from_file("../../assets/icon.png").ok();
                            let (new_window_task, open_task) = window::open(settings);
                            self.window = Some(new_window_task);
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
                            Some(DeviceType::AirPods) => match self.aacp_for(&mac) {
                                Some(aacp_manager) => {
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
                                    let state = AirPodsState::from_aacp(
                                        &aacp_manager.state.blocking_lock(),
                                        device_name,
                                        self.prefs.device(&mac),
                                    );
                                    self.device_states.insert(mac.clone(), DeviceState::AirPods(state));
                                }
                                None => error!("No AACP manager for AirPods {}; not showing its page", mac),
                            },
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

                        if matches!(&self.selected_tab, Tab::Device(selected_mac) if selected_mac == &mac) {
                            self.selected_tab = Tab::Device("none".to_string());
                        }

                        Task::batch(vec![wait_task])
                    }
                    BluetoothUIMessage::AACPUIEvent(mac, event) => {
                        let ui_rx = Arc::clone(&self.ui_rx);
                        let wait_task = Task::perform(wait_for_message(ui_rx), |msg| msg);
                        debug!("AACP UI Event for {}: {:?}", mac, event);
                        if let Some(DeviceState::AirPods(state)) = self.device_states.get_mut(&mac) {
                            state.apply_event(&event);
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
                self.device_states.insert(mac, state);
                Task::none()
            }
            Message::SetControl(mac, id, value) => {
                let task = self.send_control(&mac, id, value.clone());
                if let Some(DeviceState::AirPods(state)) = self.device_states.get_mut(&mac) {
                    state.controls.insert(id, trim_value(&value));
                    match id {
                        ControlCommandIdentifiers::EarDetectionConfig => {
                            if let Some(on) = decode_toggle(&value) {
                                state.prefs.ear_detection = on;
                                if let Err(e) = self.prefs.update(&mac, |p| p.ear_detection = on) {
                                    error!("Failed to save ear detection preference: {}", e);
                                }
                            }
                        }
                        ControlCommandIdentifiers::ListeningModeConfigs => {
                            if let Some(&cycle) = value.first() {
                                state.prefs.listening_mode_cycle = Some(cycle);
                                if let Err(e) =
                                    self.prefs.update(&mac, |p| p.listening_mode_cycle = Some(cycle))
                                {
                                    error!("Failed to save listening mode cycle: {}", e);
                                }
                            }
                        }
                        _ => {}
                    }
                }
                task
            }
            Message::ControlDraft(mac, id, value) => {
                if let Some(DeviceState::AirPods(state)) = self.device_states.get_mut(&mac) {
                    state.controls.insert(id, trim_value(&value));
                }
                Task::none()
            }
            Message::SendControl(mac, id) => {
                let value = match self.device_states.get(&mac) {
                    Some(DeviceState::AirPods(state)) => state.controls.get(&id).cloned(),
                    _ => None,
                };
                match value {
                    Some(value) => self.send_control(&mac, id, value),
                    None => Task::none(),
                }
            }
            Message::NameDraft(mac, draft) => {
                if let Some(DeviceState::AirPods(state)) = self.device_states.get_mut(&mac) {
                    state.name_draft = draft;
                }
                Task::none()
            }
            Message::SubmitName(mac) => {
                let aacp_manager = self.aacp_for(&mac);
                let Some(DeviceState::AirPods(state)) = self.device_states.get_mut(&mac) else {
                    return Task::none();
                };
                let Some(name) = rename_request(&state.device_name, &state.name_draft) else {
                    state.name_draft = state.device_name.clone();
                    return Task::none();
                };
                let Some(aacp_manager) = aacp_manager else {
                    warn!("AirPods {} are not connected; not renaming", mac);
                    return Task::none();
                };
                state.device_name = name.clone();
                state.name_draft = name.clone();
                Task::perform(
                    async move {
                        if let Err(e) = aacp_manager.send_rename_packet(&name).await {
                            error!("Failed to rename the AirPods: {}", e);
                            return false;
                        }
                        let saved = tokio::task::spawn_blocking(move || {
                            update_devices_file(&get_devices_path(), &mac, |d| d.name = name)
                        })
                        .await;
                        matches!(saved, Ok(Ok(())))
                    },
                    |ok| Message::CommandSent("Rename".to_string(), ok),
                )
            }
            Message::ToggleAdvanced(mac) => {
                if let Some(DeviceState::AirPods(state)) = self.device_states.get_mut(&mac) {
                    state.show_advanced = !state.show_advanced;
                }
                Task::none()
            }
            Message::CommandSent(what, ok) => {
                if !ok {
                    warn!("Sending {} to the AirPods failed", what);
                }
                Task::none()
            }
            Message::TrayTextModeChanged(is_enabled) => {
                self.tray_text_mode = is_enabled;
                self.persist_app_settings();
                Task::none()
            }
            Message::NotifyOnConnectChanged(is_enabled) => {
                self.notify_on_connect = is_enabled;
                self.persist_app_settings();
                Task::none()
            }
            Message::NotifyLowBatteryChanged(is_enabled) => {
                self.notify_low_battery = is_enabled;
                self.persist_app_settings();
                Task::none()
            }
            Message::CustomEqDraft(mac, eq) => {
                let Some(DeviceState::AirPods(state)) = self.device_states.get_mut(&mac) else {
                    return Task::none();
                };
                let switched = state.custom_eq.unwrap_or(DEFAULT_CUSTOM_EQ).enabled != eq.enabled;
                state.custom_eq = Some(eq);
                if switched {
                    self.send_custom_eq(&mac, eq)
                } else {
                    Task::none()
                }
            }
            Message::SendCustomEq(mac) => match self.device_states.get(&mac) {
                Some(DeviceState::AirPods(state)) => {
                    let eq = state.custom_eq.unwrap_or(DEFAULT_CUSTOM_EQ);
                    self.send_custom_eq(&mac, eq)
                }
                _ => Task::none(),
            },
            Message::AccommodationDraft(mac, accommodation) => {
                let Some(DeviceState::AirPods(state)) = self.device_states.get_mut(&mac) else {
                    return Task::none();
                };
                let before = state.accommodation.unwrap_or(DEFAULT_ACCOMMODATION);
                let switched =
                    before.phone != accommodation.phone || before.media != accommodation.media;
                state.accommodation = Some(accommodation);
                if switched {
                    self.send_accommodation(&mac, accommodation)
                } else {
                    Task::none()
                }
            }
            Message::SendAccommodation(mac) => match self.device_states.get(&mac) {
                Some(DeviceState::AirPods(state)) => {
                    let accommodation = state.accommodation.unwrap_or(DEFAULT_ACCOMMODATION);
                    self.send_accommodation(&mac, accommodation)
                }
                _ => Task::none(),
            },
            Message::SetPrefs(mac, prefs) => {
                if let Some(DeviceState::AirPods(state)) = self.device_states.get_mut(&mac) {
                    state.prefs = prefs.clone();
                }
                self.save_prefs(&mac, prefs)
            }
            Message::PrefsDraft(mac, prefs) => {
                if let Some(DeviceState::AirPods(state)) = self.device_states.get_mut(&mac) {
                    state.prefs = prefs;
                }
                Task::none()
            }
            Message::SavePrefs(mac) => match self.device_states.get(&mac) {
                Some(DeviceState::AirPods(state)) => {
                    let prefs = state.prefs.clone();
                    self.save_prefs(&mac, prefs)
                }
                _ => Task::none(),
            },
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
                                                let left  = b.iter().find(|x| x.component == BatteryComponent::Left)
                                                    .map(|x| x.level).unwrap_or_default();
                                                let right = b.iter().find(|x| x.component == BatteryComponent::Right)
                                                    .map(|x| x.level).unwrap_or_default();
                                                let case  = b.iter().find(|x| x.component == BatteryComponent::Case)
                                                    .map(|x| x.level).unwrap_or_default();
                                                let left_charging = b.iter().find(|x| x.component == BatteryComponent::Left)
                                                    .map(|x| x.status == BatteryStatus::Charging).unwrap_or(false);
                                                let right_charging = b.iter().find(|x| x.component == BatteryComponent::Right)
                                                    .map(|x| x.status == BatteryStatus::Charging).unwrap_or(false);
                                                let case_charging = b.iter().find(|x| x.component == BatteryComponent::Case)
                                                    .map(|x| x.status == BatteryStatus::Charging).unwrap_or(false);
                                                format!(
                                                    "\u{1018E5} {}%{} \u{1018E8} {}%{} \u{100E6C} {}%{}",
                                                    left, if left_charging {"\u{1002E6}"} else {""}, right, if right_charging {"\u{1002E6}"} else {""}, case, if case_charging {"\u{1002E6}"} else {""}
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
                                    Some(DeviceType::AirPods) => match device_state {
                                        Some(DeviceState::AirPods(state)) => {
                                            airpods_view(id, &devices_list, state)
                                        }
                                        _ => container(text("These AirPods are not connected").size(16))
                                            .center_x(Length::Fill)
                                            .center_y(Length::Fill),
                                    },
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

                            let notifications_settings_col = column![
                                container(
                                    text("Notifications").size(20).style(
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
                                settings_toggle(
                                    "Notify when AirPods connect",
                                    "Shows the battery levels when your AirPods connect.",
                                    self.notify_on_connect,
                                    Message::NotifyOnConnectChanged,
                                ),
                                settings_toggle(
                                    "Low battery notifications",
                                    "Warns once when an AirPod drops to 20% or below.",
                                    self.notify_low_battery,
                                    Message::NotifyLowBatteryChanged,
                                ),
                            ]
                            .spacing(12);

                            container(
                                column![
                                    appearance_settings_col,
                                    Space::new().height(Length::from(20)),
                                    tray_text_mode_toggle,
                                    Space::new().height(Length::from(20)),
                                    notifications_settings_col,
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

/// The 0x39 value to send when a preferences change alters which presses must be forwarded.
fn stem_config_update(old: &DevicePrefs, new: &DevicePrefs) -> Option<Vec<u8>> {
    let before = stem_forwarding_mask(&old.stem_actions);
    let after = stem_forwarding_mask(&new.stem_actions);
    (before != after).then(|| vec![after])
}

fn settings_toggle<'a>(
    label: &'a str,
    description: &'a str,
    value: bool,
    on_toggle: fn(bool) -> Message,
) -> Element<'a, Message> {
    container(
        row![
            column![
                text(label).size(16),
                text(description)
                    .size(12)
                    .style(|theme: &Theme| {
                        let mut style = text::Style::default();
                        style.color = Some(theme.palette().text.scale_alpha(0.7));
                        style
                    })
                    .width(Length::Fill)
            ]
            .width(Length::Fill),
            toggler(value).on_toggle(on_toggle).spacing(0).size(20)
        ]
        .align_y(Center)
        .spacing(12),
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
    })
    .into()
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
    use super::*;
    use crate::prefs::{DevicePrefs, StemAction};

    #[test]
    fn changing_stem_actions_recomputes_forwarding() {
        let default = DevicePrefs::default();
        let mut next = DevicePrefs::default();
        next.stem_actions.left_double = StemAction::NextTrack;
        assert_eq!(stem_config_update(&default, &next), Some(vec![0x02]));
        assert_eq!(stem_config_update(&next, &next.clone()), None);
        let mut with_command = next.clone();
        with_command.stem_actions.right_double = StemAction::Command("notify-send hi".to_string());
        assert_eq!(stem_config_update(&next, &with_command), None);
        assert_eq!(stem_config_update(&next, &default), Some(vec![0x00]));
    }
}
