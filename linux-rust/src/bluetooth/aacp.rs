use crate::bluetooth::aacp_parse;
use crate::devices::models::AirPodsModel;
use crate::devices::enums::DeviceData;
use crate::devices::settings::{firmware_major, trim_value};
use crate::utils::{apply_information, apply_le_key, get_devices_path, update_devices_file};
use bluer::{
    Address, AddressType, Error, Result,
    l2cap::{SeqPacket, Socket, SocketAddr},
};
use log::{debug, error, info};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use tokio::sync::{Mutex, mpsc};
use tokio::task::JoinSet;
use tokio::time::{Instant, sleep};

const PSM: u16 = 0x1001;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const POLL_INTERVAL: Duration = Duration::from_millis(200);
const HEADER_BYTES: [u8; 4] = [0x04, 0x00, 0x04, 0x00];

pub mod opcodes {
    pub const CAPABILITIES: u8 = 0x02;
    pub const BUD_ROLE: u8 = 0x08;
    pub const SET_FEATURE_FLAGS: u8 = 0x4D;
    pub const REQUEST_NOTIFICATIONS: u8 = 0x0F;
    pub const BATTERY_INFO: u8 = 0x04;
    pub const CONTROL_COMMAND: u8 = 0x09;
    pub const EAR_DETECTION: u8 = 0x06;
    pub const CONVERSATION_AWARENESS: u8 = 0x4B;
    pub const INFORMATION: u8 = 0x1D;
    pub const RENAME: u8 = 0x1A;
    pub const PROXIMITY_KEYS_REQ: u8 = 0x30;
    pub const PROXIMITY_KEYS_RSP: u8 = 0x31;
    pub const STEM_PRESS: u8 = 0x19;
    pub const HEADPHONE_ACCOMMODATION: u8 = 0x53;
    pub const CUSTOM_EQ: u8 = 0x63;
    pub const CONNECTED_DEVICES: u8 = 0x2E;
    pub const AUDIO_SOURCE: u8 = 0x0E;
    pub const SMART_ROUTING: u8 = 0x10;
    pub const SMART_ROUTING_RESP: u8 = 0x11;
    pub const SEND_CONNECTED_MAC: u8 = 0x14;
    pub const HEADTRACKING: u8 = 0x17;
    pub const TIPI_3: u8 = 0x0C;
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct ControlCommandStatus {
    pub identifier: ControlCommandIdentifiers,
    pub value: Vec<u8>,
}

/// Every known AACP control-command identifier (opcode 0x09). Names follow the Android app.
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ControlCommandIdentifiers {
    MicMode = 0x01,
    Scan = 0x02,
    Reset = 0x03,
    BasicDoubleTapMode = 0x04,
    ButtonSendMode = 0x05,
    OwnsConnection = 0x06,
    TapInterval = 0x07,
    BudRole = 0x08,
    DebugGetData = 0x09,
    EarDetectionConfig = 0x0A,
    JitterBuffer = 0x0B,
    DoubleTapMode = 0x0C,
    ListeningMode = 0x0D,
    HeartRateMonitor1 = 0x0E,
    HeartRateMonitor2 = 0x0F,
    SwitchControl = 0x11,
    VoiceTrigger = 0x12,
    DoapMode = 0x13,
    SingleClickMode = 0x14,
    DoubleClickMode = 0x15,
    ClickHoldMode = 0x16,
    DoubleClickInterval = 0x17,
    ClickHoldInterval = 0x18,
    ListeningModeConfigs = 0x1A,
    OneBudAncMode = 0x1B,
    CrownRotationDirection = 0x1C,
    AutoAnswerMode = 0x1E,
    ChimeVolume = 0x1F,
    AutomaticConnectionConfig = 0x20,
    HfpUplinkMode = 0x22,
    VolumeSwipeInterval = 0x23,
    CallManagementConfig = 0x24,
    VolumeSwipeMode = 0x25,
    AdaptiveVolumeConfig = 0x26,
    SoftwareMuteConfig = 0x27,
    ConversationDetectConfig = 0x28,
    Ssl = 0x29,
    HearingAid = 0x2C,
    AutoAncStrength = 0x2E,
    HpsGainSwipe = 0x2F,
    HrmState = 0x30,
    InCaseToneConfig = 0x31,
    SiriMultitoneConfig = 0x32,
    HearingAssistConfig = 0x33,
    AllowOffOption = 0x34,
    SleepDetectionConfig = 0x35,
    AllowAutoConnect = 0x36,
    PpeToggleConfig = 0x37,
    PpeCapLevelConfig = 0x38,
    StemConfig = 0x39,
    AllowTemporaryManagedPairing = 0x3A,
    DynamicEndOfCharge = 0x3B,
    SystemSiriMode = 0x3C,
    HearingAidGeneric = 0x3D,
    UplinkEqBud = 0x3E,
    UplinkEqSource = 0x3F,
    InCaseToneVolume = 0x40,
    DisableButtonInput = 0x41,
    ExtendedHoldAndRelease = 0x42,
}

impl ControlCommandIdentifiers {
    pub const ALL: &'static [ControlCommandIdentifiers] = &[
        Self::MicMode,
        Self::Scan,
        Self::Reset,
        Self::BasicDoubleTapMode,
        Self::ButtonSendMode,
        Self::OwnsConnection,
        Self::TapInterval,
        Self::BudRole,
        Self::DebugGetData,
        Self::EarDetectionConfig,
        Self::JitterBuffer,
        Self::DoubleTapMode,
        Self::ListeningMode,
        Self::HeartRateMonitor1,
        Self::HeartRateMonitor2,
        Self::SwitchControl,
        Self::VoiceTrigger,
        Self::DoapMode,
        Self::SingleClickMode,
        Self::DoubleClickMode,
        Self::ClickHoldMode,
        Self::DoubleClickInterval,
        Self::ClickHoldInterval,
        Self::ListeningModeConfigs,
        Self::OneBudAncMode,
        Self::CrownRotationDirection,
        Self::AutoAnswerMode,
        Self::ChimeVolume,
        Self::AutomaticConnectionConfig,
        Self::HfpUplinkMode,
        Self::VolumeSwipeInterval,
        Self::CallManagementConfig,
        Self::VolumeSwipeMode,
        Self::AdaptiveVolumeConfig,
        Self::SoftwareMuteConfig,
        Self::ConversationDetectConfig,
        Self::Ssl,
        Self::HearingAid,
        Self::AutoAncStrength,
        Self::HpsGainSwipe,
        Self::HrmState,
        Self::InCaseToneConfig,
        Self::SiriMultitoneConfig,
        Self::HearingAssistConfig,
        Self::AllowOffOption,
        Self::SleepDetectionConfig,
        Self::AllowAutoConnect,
        Self::PpeToggleConfig,
        Self::PpeCapLevelConfig,
        Self::StemConfig,
        Self::AllowTemporaryManagedPairing,
        Self::DynamicEndOfCharge,
        Self::SystemSiriMode,
        Self::HearingAidGeneric,
        Self::UplinkEqBud,
        Self::UplinkEqSource,
        Self::InCaseToneVolume,
        Self::DisableButtonInput,
        Self::ExtendedHoldAndRelease,
    ];

    pub fn from_u8(value: u8) -> Option<Self> {
        match value {
            0x01 => Some(Self::MicMode),
            0x02 => Some(Self::Scan),
            0x03 => Some(Self::Reset),
            0x04 => Some(Self::BasicDoubleTapMode),
            0x05 => Some(Self::ButtonSendMode),
            0x06 => Some(Self::OwnsConnection),
            0x07 => Some(Self::TapInterval),
            0x08 => Some(Self::BudRole),
            0x09 => Some(Self::DebugGetData),
            0x0A => Some(Self::EarDetectionConfig),
            0x0B => Some(Self::JitterBuffer),
            0x0C => Some(Self::DoubleTapMode),
            0x0D => Some(Self::ListeningMode),
            0x0E => Some(Self::HeartRateMonitor1),
            0x0F => Some(Self::HeartRateMonitor2),
            0x11 => Some(Self::SwitchControl),
            0x12 => Some(Self::VoiceTrigger),
            0x13 => Some(Self::DoapMode),
            0x14 => Some(Self::SingleClickMode),
            0x15 => Some(Self::DoubleClickMode),
            0x16 => Some(Self::ClickHoldMode),
            0x17 => Some(Self::DoubleClickInterval),
            0x18 => Some(Self::ClickHoldInterval),
            0x1A => Some(Self::ListeningModeConfigs),
            0x1B => Some(Self::OneBudAncMode),
            0x1C => Some(Self::CrownRotationDirection),
            0x1E => Some(Self::AutoAnswerMode),
            0x1F => Some(Self::ChimeVolume),
            0x20 => Some(Self::AutomaticConnectionConfig),
            0x22 => Some(Self::HfpUplinkMode),
            0x23 => Some(Self::VolumeSwipeInterval),
            0x24 => Some(Self::CallManagementConfig),
            0x25 => Some(Self::VolumeSwipeMode),
            0x26 => Some(Self::AdaptiveVolumeConfig),
            0x27 => Some(Self::SoftwareMuteConfig),
            0x28 => Some(Self::ConversationDetectConfig),
            0x29 => Some(Self::Ssl),
            0x2C => Some(Self::HearingAid),
            0x2E => Some(Self::AutoAncStrength),
            0x2F => Some(Self::HpsGainSwipe),
            0x30 => Some(Self::HrmState),
            0x31 => Some(Self::InCaseToneConfig),
            0x32 => Some(Self::SiriMultitoneConfig),
            0x33 => Some(Self::HearingAssistConfig),
            0x34 => Some(Self::AllowOffOption),
            0x35 => Some(Self::SleepDetectionConfig),
            0x36 => Some(Self::AllowAutoConnect),
            0x37 => Some(Self::PpeToggleConfig),
            0x38 => Some(Self::PpeCapLevelConfig),
            0x39 => Some(Self::StemConfig),
            0x3A => Some(Self::AllowTemporaryManagedPairing),
            0x3B => Some(Self::DynamicEndOfCharge),
            0x3C => Some(Self::SystemSiriMode),
            0x3D => Some(Self::HearingAidGeneric),
            0x3E => Some(Self::UplinkEqBud),
            0x3F => Some(Self::UplinkEqSource),
            0x40 => Some(Self::InCaseToneVolume),
            0x41 => Some(Self::DisableButtonInput),
            0x42 => Some(Self::ExtendedHoldAndRelease),
            _ => None,
        }
    }
}

impl std::fmt::Display for ControlCommandIdentifiers {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let name = match self {
            ControlCommandIdentifiers::MicMode => "Mic Mode",
            ControlCommandIdentifiers::Scan => "Scan",
            ControlCommandIdentifiers::Reset => "Reset",
            ControlCommandIdentifiers::BasicDoubleTapMode => "Basic Double Tap Mode",
            ControlCommandIdentifiers::ButtonSendMode => "Button Send Mode",
            ControlCommandIdentifiers::OwnsConnection => "Owns Connection",
            ControlCommandIdentifiers::TapInterval => "Tap Interval",
            ControlCommandIdentifiers::BudRole => "Bud Role",
            ControlCommandIdentifiers::DebugGetData => "Debug Get Data",
            ControlCommandIdentifiers::EarDetectionConfig => "Ear Detection Config",
            ControlCommandIdentifiers::JitterBuffer => "Jitter Buffer",
            ControlCommandIdentifiers::DoubleTapMode => "Double Tap Mode",
            ControlCommandIdentifiers::ListeningMode => "Listening Mode",
            ControlCommandIdentifiers::HeartRateMonitor1 => "Heart Rate Monitor 1",
            ControlCommandIdentifiers::HeartRateMonitor2 => "Heart Rate Monitor 2",
            ControlCommandIdentifiers::SwitchControl => "Switch Control",
            ControlCommandIdentifiers::VoiceTrigger => "Voice Trigger",
            ControlCommandIdentifiers::DoapMode => "DOAP Mode",
            ControlCommandIdentifiers::SingleClickMode => "Single Click Mode",
            ControlCommandIdentifiers::DoubleClickMode => "Double Click Mode",
            ControlCommandIdentifiers::ClickHoldMode => "Click Hold Mode",
            ControlCommandIdentifiers::DoubleClickInterval => "Double Click Interval",
            ControlCommandIdentifiers::ClickHoldInterval => "Click Hold Interval",
            ControlCommandIdentifiers::ListeningModeConfigs => "Listening Mode Configs",
            ControlCommandIdentifiers::OneBudAncMode => "One Bud ANC Mode",
            ControlCommandIdentifiers::CrownRotationDirection => "Crown Rotation Direction",
            ControlCommandIdentifiers::AutoAnswerMode => "Auto Answer Mode",
            ControlCommandIdentifiers::ChimeVolume => "Chime Volume",
            ControlCommandIdentifiers::AutomaticConnectionConfig => "Automatic Connection Config",
            ControlCommandIdentifiers::HfpUplinkMode => "HFP Uplink Mode",
            ControlCommandIdentifiers::VolumeSwipeInterval => "Volume Swipe Interval",
            ControlCommandIdentifiers::CallManagementConfig => "Call Management Config",
            ControlCommandIdentifiers::VolumeSwipeMode => "Volume Swipe Mode",
            ControlCommandIdentifiers::AdaptiveVolumeConfig => "Adaptive Volume Config",
            ControlCommandIdentifiers::SoftwareMuteConfig => "Software Mute Config",
            ControlCommandIdentifiers::ConversationDetectConfig => "Conversation Detect Config",
            ControlCommandIdentifiers::Ssl => "SSL",
            ControlCommandIdentifiers::HearingAid => "Hearing Aid",
            ControlCommandIdentifiers::AutoAncStrength => "Auto ANC Strength",
            ControlCommandIdentifiers::HpsGainSwipe => "HPS Gain Swipe",
            ControlCommandIdentifiers::HrmState => "HRM State",
            ControlCommandIdentifiers::InCaseToneConfig => "In Case Tone Config",
            ControlCommandIdentifiers::SiriMultitoneConfig => "Siri Multitone Config",
            ControlCommandIdentifiers::HearingAssistConfig => "Hearing Assist Config",
            ControlCommandIdentifiers::AllowOffOption => "Allow Off Option",
            ControlCommandIdentifiers::SleepDetectionConfig => "Sleep Detection Config",
            ControlCommandIdentifiers::AllowAutoConnect => "Allow Auto Connect",
            ControlCommandIdentifiers::PpeToggleConfig => "PPE Toggle Config",
            ControlCommandIdentifiers::PpeCapLevelConfig => "PPE Cap Level Config",
            ControlCommandIdentifiers::StemConfig => "Stem Config",
            ControlCommandIdentifiers::AllowTemporaryManagedPairing => "Allow Temporary Managed Pairing",
            ControlCommandIdentifiers::DynamicEndOfCharge => "Dynamic End Of Charge",
            ControlCommandIdentifiers::SystemSiriMode => "System Siri Mode",
            ControlCommandIdentifiers::HearingAidGeneric => "Hearing Aid Generic",
            ControlCommandIdentifiers::UplinkEqBud => "Uplink EQ Bud",
            ControlCommandIdentifiers::UplinkEqSource => "Uplink EQ Source",
            ControlCommandIdentifiers::InCaseToneVolume => "In Case Tone Volume",
            ControlCommandIdentifiers::DisableButtonInput => "Disable Button Input",
            ControlCommandIdentifiers::ExtendedHoldAndRelease => "Extended Hold And Release",
        };
        write!(f, "{}", name)
    }
}

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Hash)]
pub enum ProximityKeyType {
    Irk = 0x01,
    EncKey = 0x04,
}

impl ProximityKeyType {
    fn from_u8(value: u8) -> Option<Self> {
        match value {
            0x01 => Some(Self::Irk),
            0x04 => Some(Self::EncKey),
            _ => None,
        }
    }
}

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StemPressType {
    SinglePress = 0x05,
    DoublePress = 0x06,
    TriplePress = 0x07,
    LongPress = 0x08,
}

impl StemPressType {
    fn from_u8(value: u8) -> Option<Self> {
        match value {
            0x05 => Some(Self::SinglePress),
            0x06 => Some(Self::DoublePress),
            0x07 => Some(Self::TriplePress),
            0x08 => Some(Self::LongPress),
            _ => None,
        }
    }
}

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StemPressBudType {
    Left = 0x01,
    Right = 0x02,
}

impl StemPressBudType {
    fn from_u8(value: u8) -> Option<Self> {
        match value {
            0x01 => Some(Self::Left),
            0x02 => Some(Self::Right),
            _ => None,
        }
    }
}

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AudioSourceType {
    None = 0x00,
    Call = 0x01,
    Media = 0x02,
}

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum BatteryComponent {
    Headphone = 1,
    Left = 4,
    Right = 2,
    Case = 8,
}

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BatteryStatus {
    Unknown = 0,
    Charging = 1,
    NotCharging = 2,
    Disconnected = 4,
    OptimizedCharging = 5,
}

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EarDetectionStatus {
    InEar = 0x00,
    OutOfEar = 0x01,
    InCase = 0x02,
    Disconnected = 0x03,
}

impl AudioSourceType {
    fn from_u8(value: u8) -> Option<Self> {
        match value {
            0x00 => Some(Self::None),
            0x01 => Some(Self::Call),
            0x02 => Some(Self::Media),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AudioSource {
    pub mac: String,
    pub r#type: AudioSourceType,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BatteryInfo {
    pub component: BatteryComponent,
    pub level: u8,
    pub status: BatteryStatus,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConnectedDevice {
    pub mac: String,
    pub info1: u8,
    pub info2: u8,
    pub r#type: Option<String>,
}

#[derive(Debug, Clone)]
pub enum AACPEvent {
    BatteryInfo(Vec<BatteryInfo>),
    ControlCommand(ControlCommandStatus),
    EarDetection(Vec<EarDetectionStatus>, Vec<EarDetectionStatus>),
    ConversationalAwareness(u8),
    ProximityKeys(Vec<(u8, Vec<u8>)>),
    AudioSource(AudioSource),
    ConnectedDevices(Vec<ConnectedDevice>, Vec<ConnectedDevice>),
    OwnershipToFalseRequest,
    StemPress(StemPressType, StemPressBudType),
    CustomEq(aacp_parse::CustomEq),
    HeadphoneAccommodation(aacp_parse::Accommodation),
    /// Sent for every Information packet, once the model and firmware are known.
    DeviceInfo {
        model: AirPodsModel,
        firmware_major: Option<u32>,
    },
}

#[derive(Clone, Default, Serialize, Deserialize)]
pub struct AirPodsLEKeys {
    pub irk: String,
    pub enc_key: String,
}

/// The LE keys let anyone track the AirPods and decrypt their adverts: never print them whole.
impl std::fmt::Debug for AirPodsLEKeys {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let redact = |key: &str| {
            if key.is_empty() {
                "<none>".to_string()
            } else {
                format!("{}…", key.chars().take(4).collect::<String>())
            }
        };
        f.debug_struct("AirPodsLEKeys")
            .field("irk", &redact(&self.irk))
            .field("enc_key", &redact(&self.enc_key))
            .finish()
    }
}

/// Key types and sizes only, for logging.
fn describe_proximity_keys(keys: &[(u8, Vec<u8>)]) -> String {
    keys.iter()
        .map(|(key_type, data)| {
            let name = match ProximityKeyType::from_u8(*key_type) {
                Some(ProximityKeyType::Irk) => "IRK".to_string(),
                Some(ProximityKeyType::EncKey) => "ENC_KEY".to_string(),
                None => format!("type {:#04x}", key_type),
            };
            format!("{} ({} bytes)", name, data.len())
        })
        .collect::<Vec<_>>()
        .join(", ")
}

pub struct AACPManagerState {
    pub sender: Option<mpsc::Sender<Vec<u8>>>,
    pub control_command_status_list: Vec<ControlCommandStatus>,
    pub control_command_subscribers:
        HashMap<ControlCommandIdentifiers, Vec<mpsc::UnboundedSender<Vec<u8>>>>,
    pub owns: bool,
    pub old_connected_devices: Vec<ConnectedDevice>,
    pub connected_devices: Vec<ConnectedDevice>,
    pub audio_source: Option<AudioSource>,
    pub battery_info: Vec<BatteryInfo>,
    pub conversational_awareness_status: u8,
    pub old_ear_detection_status: Vec<EarDetectionStatus>,
    pub ear_detection_status: Vec<EarDetectionStatus>,
    pub bud_role: Option<aacp_parse::BudRole>,
    pub capabilities: Vec<(u8, Vec<u8>)>,
    pub model: Option<AirPodsModel>,
    pub firmware_major: Option<u32>,
    pub custom_eq: Option<aacp_parse::CustomEq>,
    pub accommodation: Option<aacp_parse::Accommodation>,
    event_tx: Option<mpsc::UnboundedSender<AACPEvent>>,
    pub airpods_mac: Option<Address>,
}

impl AACPManagerState {
    pub(crate) fn new() -> Self {
        AACPManagerState {
            sender: None,
            control_command_status_list: Vec::new(),
            control_command_subscribers: HashMap::new(),
            owns: false,
            old_connected_devices: Vec::new(),
            connected_devices: Vec::new(),
            audio_source: None,
            battery_info: Vec::new(),
            conversational_awareness_status: 0,
            old_ear_detection_status: Vec::new(),
            ear_detection_status: Vec::new(),
            bud_role: None,
            capabilities: Vec::new(),
            model: None,
            firmware_major: None,
            custom_eq: None,
            accommodation: None,
            event_tx: None,
            airpods_mac: None,
        }
    }
}

#[derive(Clone)]
pub struct AACPManager {
    pub state: Arc<Mutex<AACPManagerState>>,
    tasks: Arc<Mutex<JoinSet<()>>>,
    connected: Arc<AtomicBool>,
}

impl AACPManager {
    pub fn new() -> Self {
        AACPManager {
            state: Arc::new(Mutex::new(AACPManagerState::new())),
            tasks: Arc::new(Mutex::new(JoinSet::new())),
            connected: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Whether the L2CAP channel is up and its receive loop is still running.
    pub fn is_connected(&self) -> bool {
        self.connected.load(Ordering::SeqCst)
    }

    /// Stops the send/receive loops and drops the channel.
    pub async fn disconnect(&self) {
        let mut tasks = self.tasks.lock().await;
        tasks.abort_all();
        while tasks.join_next().await.is_some() {}
        drop(tasks);
        self.state.lock().await.sender = None;
        self.connected.store(false, Ordering::SeqCst);
    }

    pub async fn connect(&mut self, addr: Address) -> bool {
        info!("AACPManager connecting to {} on PSM {:#06X}...", addr, PSM);
        let target_sa = SocketAddr::new(addr, AddressType::BrEdr, PSM);

        {
            let mut state = self.state.lock().await;
            state.airpods_mac = Some(addr);
        }

        let socket = match Socket::new_seq_packet() {
            Ok(s) => s,
            Err(e) => {
                error!("Failed to create L2CAP socket: {}", e);
                return false;
            }
        };

        let seq_packet =
            match tokio::time::timeout(CONNECT_TIMEOUT, socket.connect(target_sa)).await {
                Ok(Ok(s)) => Arc::new(s),
                Ok(Err(e)) => {
                    error!("L2CAP connect failed: {}", e);
                    return false;
                }
                Err(_) => {
                    error!("L2CAP connect timed out");
                    return false;
                }
            };

        // Wait for connection to be fully established
        let start = Instant::now();
        loop {
            match seq_packet.peer_addr() {
                Ok(peer) if peer.cid != 0 => break,
                Ok(_) => { /* still waiting */ }
                Err(e) => {
                    if e.raw_os_error() == Some(107) {
                        // ENOTCONN
                        error!("Peer has disconnected during connection setup.");
                        return false;
                    }
                    error!("Error getting peer address: {}", e);
                }
            }
            if start.elapsed() >= CONNECT_TIMEOUT {
                error!("Timed out waiting for L2CAP connection to be fully established.");
                return false;
            }
            sleep(POLL_INTERVAL).await;
        }

        info!("L2CAP connection established with {}", addr);

        let (tx, rx) = mpsc::channel(128);

        let manager_clone = self.clone();
        {
            let mut state = self.state.lock().await;
            state.sender = Some(tx);
        }

        self.connected.store(true, Ordering::SeqCst);
        let mut tasks = self.tasks.lock().await;
        tasks.spawn(recv_thread(manager_clone, seq_packet.clone()));
        tasks.spawn(send_thread(rx, seq_packet));
        true
    }

    async fn send_packet(&self, data: &[u8]) -> Result<()> {
        let state = self.state.lock().await;
        if let Some(sender) = &state.sender {
            sender.send(data.to_vec()).await.map_err(|e| {
                error!("Failed to send packet to channel: {}", e);
                Error::from(std::io::Error::new(
                    std::io::ErrorKind::NotConnected,
                    "L2CAP send channel closed",
                ))
            })
        } else {
            error!("Cannot send packet, sender is not available.");
            Err(Error::from(std::io::Error::new(
                std::io::ErrorKind::NotConnected,
                "L2CAP stream not connected",
            )))
        }
    }

    async fn send_data_packet(&self, data: &[u8]) -> Result<()> {
        let packet = [HEADER_BYTES.as_slice(), data].concat();
        self.send_packet(&packet).await
    }

    /// Installs the event channel. The AirPods often send their Information packets before
    /// it exists, so a model learned by then is passed on at once.
    pub async fn set_event_channel(&self, tx: mpsc::UnboundedSender<AACPEvent>) {
        let mut state = self.state.lock().await;
        if let Some(model) = state.model {
            let _ = tx.send(AACPEvent::DeviceInfo {
                model,
                firmware_major: state.firmware_major,
            });
        }
        state.event_tx = Some(tx);
    }

    pub async fn subscribe_to_control_command(
        &self,
        identifier: ControlCommandIdentifiers,
        tx: mpsc::UnboundedSender<Vec<u8>>,
    ) {
        let mut state = self.state.lock().await;
        state
            .control_command_subscribers
            .entry(identifier)
            .or_default()
            .push(tx);
        // send initial value if available
        if let Some(status) = state
            .control_command_status_list
            .iter()
            .find(|s| s.identifier == identifier)
        {
            let _ = state
                .control_command_subscribers
                .get(&identifier)
                .unwrap()
                .last()
                .unwrap()
                .send(status.value.clone());
        }
    }

    pub async fn receive_packet(&self, packet: &[u8]) {
        if !packet.starts_with(&HEADER_BYTES) {
            debug!(
                "Received packet does not start with expected header: {}",
                hex::encode(packet)
            );
            return;
        }
        if packet.len() < 5 {
            debug!("Received packet too short: {}", hex::encode(packet));
            return;
        }

        let opcode = packet[4];
        let payload = &packet[4..];

        match opcode {
            opcodes::BATTERY_INFO => {
                let Some(batteries) = aacp_parse::parse_battery(payload) else {
                    error!("Malformed Battery Info packet: {}", hex::encode(payload));
                    return;
                };
                let mut state = self.state.lock().await;
                state.battery_info = batteries.clone();
                if let Some(ref tx) = state.event_tx {
                    let _ = tx.send(AACPEvent::BatteryInfo(batteries));
                }
                info!("Received Battery Info: {:?}", state.battery_info);
            }
            opcodes::CONTROL_COMMAND => {
                if payload.len() < 7 {
                    error!("Control Command packet too short: {}", hex::encode(payload));
                    return;
                }
                let identifier_byte = payload[2];
                let value_bytes = &payload[3..7];
                let value = trim_value(value_bytes);

                if let Some(identifier) = ControlCommandIdentifiers::from_u8(identifier_byte) {
                    let status = ControlCommandStatus {
                        identifier,
                        value: value.clone(),
                    };
                    let mut state = self.state.lock().await;
                    if let Some(existing) = state
                        .control_command_status_list
                        .iter_mut()
                        .find(|s| s.identifier == identifier)
                    {
                        existing.value = value.clone();
                    } else {
                        state.control_command_status_list.push(status.clone());
                    }
                    if identifier == ControlCommandIdentifiers::OwnsConnection {
                        state.owns = value_bytes[0] != 0;
                    }
                    if let Some(subscribers) = state.control_command_subscribers.get(&identifier) {
                        for sub in subscribers {
                            let _ = sub.send(value.clone());
                        }
                    }
                    if let Some(ref tx) = state.event_tx {
                        let _ = tx.send(AACPEvent::ControlCommand(status));
                    }
                    info!(
                        "Received Control Command: {:?}, value: {}",
                        identifier,
                        hex::encode(&value)
                    );
                } else {
                    debug!(
                        "Unknown Control Command identifier: {:#04x}, value: {}",
                        identifier_byte,
                        hex::encode(&value)
                    );
                }
            }
            opcodes::EAR_DETECTION => {
                let Some(statuses) = aacp_parse::parse_ear_detection(payload) else {
                    error!("Malformed Ear Detection packet: {}", hex::encode(payload));
                    return;
                };
                let pair = statuses;
                let statuses = statuses.to_vec();
                let mut state = self.state.lock().await;
                state.old_ear_detection_status = state.ear_detection_status.clone();
                state.ear_detection_status = statuses.clone();

                if let Some(ref tx) = state.event_tx {
                    debug!(
                        "Sending Ear Detection event: old: {:?}, new: {:?}",
                        state.old_ear_detection_status, statuses
                    );
                    let _ = tx.send(AACPEvent::EarDetection(
                        state.old_ear_detection_status.clone(),
                        statuses,
                    ));
                }
                let (left, right) = aacp_parse::ear_left_right(pair, state.bud_role);
                info!(
                    "Received Ear Detection Status: {:?} (left: {:?}, right: {:?})",
                    state.ear_detection_status, left, right
                );
            }
            opcodes::CONVERSATION_AWARENESS => {
                if packet.len() == 10 {
                    let status = packet[9];
                    let mut state = self.state.lock().await;
                    state.conversational_awareness_status = status;
                    if let Some(ref tx) = state.event_tx {
                        let _ = tx.send(AACPEvent::ConversationalAwareness(status));
                    }
                    info!("Received Conversation Awareness: {}", status);
                } else {
                    info!(
                        "Received Conversation Awareness packet with unexpected length: {}",
                        packet.len()
                    );
                }
            }
            opcodes::INFORMATION => {
                let Some(info) = aacp_parse::parse_information(payload) else {
                    error!("Malformed Information packet: {}", hex::encode(payload));
                    return;
                };
                let model = AirPodsModel::from_model_number(&info.model_number);
                let firmware_major = firmware_major(&info.version3);
                let airpods_mac = {
                    let mut state = self.state.lock().await;
                    if state.model.is_none() {
                        info!(
                            "Detected {} ({}), firmware major {:?}, capabilities: {:?}",
                            model.display_name(),
                            info.model_number,
                            firmware_major,
                            model.capabilities()
                        );
                    }
                    state.model = Some(model);
                    state.firmware_major = firmware_major;
                    if let Some(ref tx) = state.event_tx {
                        let _ = tx.send(AACPEvent::DeviceInfo {
                            model,
                            firmware_major,
                        });
                    }
                    state.airpods_mac
                };
                if let Some(mac) = airpods_mac {
                    let info_clone = info.clone();
                    persist_device(mac, move |d| apply_information(d, info_clone)).await;
                }
                info!("Received Information: {:?}", info);
            }

            opcodes::PROXIMITY_KEYS_RSP => {
                if payload.len() < 4 {
                    error!(
                        "Proximity Keys Response packet too short: {}",
                        hex::encode(payload)
                    );
                    return;
                }
                let key_count = payload[2] as usize;
                debug!("Proximity Keys Response contains {} keys.", key_count);
                let mut offset = 3;
                let mut keys = Vec::new();
                for _ in 0..key_count {
                    if offset + 3 >= payload.len() {
                        error!(
                            "Proximity Keys Response packet too short while parsing keys: {}",
                            hex::encode(payload)
                        );
                        return;
                    }
                    let key_type = payload[offset];
                    let key_length = payload[offset + 2] as usize;
                    offset += 4;
                    if offset + key_length > payload.len() {
                        error!(
                            "Proximity Keys Response packet too short for key data: {}",
                            hex::encode(payload)
                        );
                        return;
                    }
                    let key_data = payload[offset..offset + key_length].to_vec();
                    keys.push((key_type, key_data));
                    offset += key_length;
                }
                info!(
                    "Received Proximity Keys Response: {}",
                    describe_proximity_keys(&keys)
                );
                let airpods_mac = self.state.lock().await.airpods_mac;
                if let Some(mac) = airpods_mac {
                    let typed_keys: Vec<(ProximityKeyType, String)> = keys
                        .iter()
                        .filter_map(|(kt, kd)| {
                            ProximityKeyType::from_u8(*kt).map(|kt| (kt, hex::encode(kd)))
                        })
                        .collect();
                    persist_device(mac, move |d| {
                        for (key_type, key_hex) in &typed_keys {
                            apply_le_key(d, *key_type, key_hex);
                        }
                    })
                    .await;
                }
            }
            opcodes::STEM_PRESS => {
                if payload.len() < 4 {
                    error!("Stem Press packet too short: {}", hex::encode(payload));
                    return;
                }
                let press_type = StemPressType::from_u8(payload[2]);
                let bud_type = StemPressBudType::from_u8(payload[3]);
                if let (Some(press), Some(bud)) = (press_type, bud_type) {
                    info!("Received Stem Press: {:?} on {:?}", press, bud);
                    let state = self.state.lock().await;
                    if let Some(ref tx) = state.event_tx {
                        let _ = tx.send(AACPEvent::StemPress(press, bud));
                    }
                } else {
                    error!(
                        "Invalid Stem Press packet - type: {:?}, bud: {:?}",
                        press_type, bud_type
                    );
                }
            }
            opcodes::AUDIO_SOURCE => {
                if payload.len() < 9 {
                    error!("Audio Source packet too short: {}", hex::encode(payload));
                    return;
                }
                let mac = format!(
                    "{:02X}:{:02X}:{:02X}:{:02X}:{:02X}:{:02X}",
                    payload[7], payload[6], payload[5], payload[4], payload[3], payload[2]
                );
                let typ = AudioSourceType::from_u8(payload[8]).unwrap_or(AudioSourceType::None);
                let audio_source = AudioSource { mac, r#type: typ };
                let mut state = self.state.lock().await;
                state.audio_source = Some(audio_source.clone());
                if let Some(ref tx) = state.event_tx {
                    let _ = tx.send(AACPEvent::AudioSource(audio_source));
                }
                info!("Received Audio Source: {:?}", state.audio_source);
            }
            opcodes::CONNECTED_DEVICES => {
                let Some(devices) = aacp_parse::parse_connected_devices(payload) else {
                    error!("Malformed Connected Devices packet: {}", hex::encode(payload));
                    return;
                };
                let mut state = self.state.lock().await;
                state.old_connected_devices = state.connected_devices.clone();
                state.connected_devices = devices.clone();
                if let Some(ref tx) = state.event_tx {
                    let _ = tx.send(AACPEvent::ConnectedDevices(
                        state.old_connected_devices.clone(),
                        devices,
                    ));
                }
                info!("Received Connected Devices: {:?}", state.connected_devices);
            }
            opcodes::SMART_ROUTING_RESP => {
                let Some(packet_string) = aacp_parse::parse_smart_routing_response(payload) else {
                    error!("Malformed Smart Routing Response: {}", hex::encode(payload));
                    return;
                };
                info!("Received Smart Routing Response: {}", packet_string);
                if packet_string.contains("SetOwnershipToFalse") {
                    info!("Received OwnershipToFalse request");
                    if let Some(ref tx) = self.state.lock().await.event_tx {
                        let _ = tx.send(AACPEvent::OwnershipToFalseRequest);
                    }
                }
            }
            opcodes::BUD_ROLE => {
                let Some(role) = aacp_parse::parse_bud_role(payload) else {
                    error!("Malformed Bud Role packet: {}", hex::encode(payload));
                    return;
                };
                self.state.lock().await.bud_role = Some(role);
                info!("Received Bud Role: {:?}", role);
            }
            opcodes::CAPABILITIES => {
                let Some(capabilities) = aacp_parse::parse_capabilities(payload) else {
                    error!("Malformed Capabilities packet: {}", hex::encode(payload));
                    return;
                };
                info!(
                    "Received Capabilities: {:02x?}",
                    capabilities.iter().map(|(id, _)| *id).collect::<Vec<_>>()
                );
                self.state.lock().await.capabilities = capabilities;
            }
            opcodes::CUSTOM_EQ => {
                let Some(eq) = aacp_parse::parse_custom_eq(payload) else {
                    debug!("Ignoring unrecognized Custom EQ packet: {}", hex::encode(payload));
                    return;
                };
                let mut state = self.state.lock().await;
                state.custom_eq = Some(eq);
                if let Some(ref tx) = state.event_tx {
                    let _ = tx.send(AACPEvent::CustomEq(eq));
                }
                info!("Received Custom EQ: {:?}", eq);
            }
            opcodes::HEADPHONE_ACCOMMODATION => {
                let Some(accommodation) = aacp_parse::parse_accommodation(payload) else {
                    debug!(
                        "Ignoring unrecognized Headphone Accommodation packet: {}",
                        hex::encode(payload)
                    );
                    return;
                };
                let mut state = self.state.lock().await;
                state.accommodation = Some(accommodation);
                if let Some(ref tx) = state.event_tx {
                    let _ = tx.send(AACPEvent::HeadphoneAccommodation(accommodation));
                }
                info!("Received Headphone Accommodation: {:?}", accommodation);
            }
            _ => debug!("Received unknown packet with opcode {:#04x}", opcode),
        }
    }

    pub async fn send_notification_request(&self) -> Result<()> {
        let opcode = [opcodes::REQUEST_NOTIFICATIONS, 0x00];
        let data = [0xFF, 0xFF, 0xFF, 0xFF];
        let packet = [opcode.as_slice(), data.as_slice()].concat();
        self.send_data_packet(&packet).await
    }

    pub async fn send_set_feature_flags_packet(&self) -> Result<()> {
        let opcode = [opcodes::SET_FEATURE_FLAGS, 0x00];
        // let data = [0xD7, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00];
        let data = [0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]; // adaptive volume is actually useful, seeing if it works
        let packet = [opcode.as_slice(), data.as_slice()].concat();
        self.send_data_packet(&packet).await
    }

    pub async fn send_handshake(&self) -> Result<()> {
        let packet = [
            0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00,
        ];
        self.send_packet(&packet).await
    }

    pub async fn send_proximity_keys_request(
        &self,
        key_types: Vec<ProximityKeyType>,
    ) -> Result<()> {
        let opcode = [opcodes::PROXIMITY_KEYS_REQ, 0x00];
        let mut data = Vec::with_capacity(2);
        data.push(key_types.iter().fold(0u8, |acc, kt| acc | (*kt as u8)));
        data.push(0x00);
        let packet = [opcode.as_slice(), data.as_slice()].concat();
        self.send_data_packet(&packet).await
    }

    /// Renames the AirPods; a name that is empty or too long is rejected without sending.
    pub async fn send_rename_packet(&self, name: &str) -> Result<()> {
        let Some(payload) = aacp_parse::rename_payload(name) else {
            return Err(Error::from(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "AirPods names must be 1 to 255 bytes long",
            )));
        };
        let packet = [[opcodes::RENAME, 0x00].as_slice(), payload.as_slice()].concat();
        self.send_data_packet(&packet).await
    }

    pub async fn send_custom_eq(&self, eq: aacp_parse::CustomEq) -> Result<()> {
        let payload = aacp_parse::custom_eq_payload(eq);
        let packet = [[opcodes::CUSTOM_EQ, 0x00].as_slice(), payload.as_slice()].concat();
        self.send_data_packet(&packet).await
    }

    pub async fn send_headphone_accommodation(
        &self,
        accommodation: &aacp_parse::Accommodation,
    ) -> Result<()> {
        let payload = aacp_parse::accommodation_payload(accommodation);
        let packet = [
            [opcodes::HEADPHONE_ACCOMMODATION, 0x00].as_slice(),
            payload.as_slice(),
        ]
        .concat();
        self.send_data_packet(&packet).await
    }

    pub async fn send_control_command(
        &self,
        identifier: ControlCommandIdentifiers,
        value: &[u8],
    ) -> Result<()> {
        let opcode = [opcodes::CONTROL_COMMAND, 0x00];
        let mut data = vec![identifier as u8];
        for i in 0..4 {
            data.push(value.get(i).copied().unwrap_or(0));
        }
        let packet = [opcode.as_slice(), data.as_slice()].concat();
        self.send_data_packet(&packet).await
    }

    pub async fn send_media_information_new_device(
        &self,
        self_mac_address: &str,
        target_mac_address: &str,
    ) -> Result<()> {
        let opcode = [opcodes::SMART_ROUTING, 0x00];
        let mut buffer = Vec::with_capacity(112);
        let target_mac_bytes: Vec<u8> = target_mac_address
            .split(':')
            .map(|s| u8::from_str_radix(s, 16).unwrap())
            .collect();
        buffer.extend_from_slice(&target_mac_bytes.iter().rev().cloned().collect::<Vec<u8>>());

        buffer.extend_from_slice(&[0x68, 0x00]);
        buffer.extend_from_slice(&[0x01, 0xE5, 0x4A]);
        buffer.extend_from_slice(b"playingApp");
        buffer.push(0x42);
        buffer.extend_from_slice(b"NA");
        buffer.push(0x52);
        buffer.extend_from_slice(b"hostStreamingState");
        buffer.push(0x42);
        buffer.extend_from_slice(b"NO");
        buffer.push(0x49);
        buffer.extend_from_slice(b"btAddress");
        buffer.push(0x51);
        buffer.extend_from_slice(self_mac_address.as_bytes());
        buffer.push(0x46);
        buffer.extend_from_slice(b"btName");
        buffer.push(0x43);
        buffer.extend_from_slice(b"Mac");
        buffer.push(0x58);
        buffer.extend_from_slice(b"otherDevice");
        buffer.extend_from_slice(b"AudioCategory");
        buffer.extend_from_slice(&[0x30, 0x64]);

        let packet = [opcode.as_slice(), buffer.as_slice()].concat();
        self.send_data_packet(&packet).await
    }

    pub async fn send_hijack_request(&self, target_mac_address: &str) -> Result<()> {
        let opcode = [opcodes::SMART_ROUTING, 0x00];
        let mut buffer = Vec::with_capacity(106);
        let target_mac_bytes: Vec<u8> = target_mac_address
            .split(':')
            .map(|s| u8::from_str_radix(s, 16).unwrap())
            .collect();
        buffer.extend_from_slice(&target_mac_bytes.iter().rev().cloned().collect::<Vec<u8>>());
        buffer.extend_from_slice(&[0x62, 0x00]);
        buffer.extend_from_slice(&[0x01, 0xE5]);
        buffer.push(0x4A);
        buffer.extend_from_slice(b"localscore");
        buffer.extend_from_slice(&[0x30, 0x64]);
        buffer.push(0x46);
        buffer.extend_from_slice(b"reason");
        buffer.push(0x48);
        buffer.extend_from_slice(b"Hijackv2");
        buffer.push(0x51);
        buffer.extend_from_slice(b"audioRoutingScore");
        buffer.extend_from_slice(&[0x31, 0x2D, 0x01, 0x5F]);
        buffer.extend_from_slice(b"audioRoutingSetOwnershipToFalse");
        buffer.push(0x01);
        buffer.push(0x4B);
        buffer.extend_from_slice(b"remotescore");
        buffer.push(0xA5);

        while buffer.len() < 106 {
            buffer.push(0x00);
        }

        let packet = [opcode.as_slice(), buffer.as_slice()].concat();
        self.send_data_packet(&packet).await
    }

    pub async fn send_media_information(
        &self,
        self_mac_address: &str,
        target_mac_address: &str,
        streaming_state: bool,
    ) -> Result<()> {
        let opcode = [opcodes::SMART_ROUTING, 0x00];
        let mut buffer = Vec::with_capacity(138);
        let target_mac_bytes: Vec<u8> = target_mac_address
            .split(':')
            .map(|s| u8::from_str_radix(s, 16).unwrap())
            .collect();
        buffer.extend_from_slice(&target_mac_bytes.iter().rev().cloned().collect::<Vec<u8>>());
        buffer.extend_from_slice(&[0x82, 0x00]);
        buffer.extend_from_slice(&[0x01, 0xE5, 0x4A]);
        buffer.extend_from_slice(b"PlayingApp");
        buffer.push(0x56);
        buffer.extend_from_slice(b"com.google.ios.youtube");
        buffer.push(0x52);
        buffer.extend_from_slice(b"HostStreamingState");
        buffer.push(0x42);
        buffer.extend_from_slice(if streaming_state { b"YES" } else { b"NO" });
        buffer.push(0x49);
        buffer.extend_from_slice(b"btAddress");
        buffer.push(0x51);
        buffer.extend_from_slice(self_mac_address.as_bytes());
        buffer.extend_from_slice(b"btName");
        buffer.push(0x43);
        buffer.extend_from_slice(b"Mac");
        buffer.push(0x58);
        buffer.extend_from_slice(b"otherDevice");
        buffer.extend_from_slice(b"AudioCategory");
        buffer.extend_from_slice(&[0x31, 0x2D, 0x01]);

        while buffer.len() < 138 {
            buffer.push(0x00);
        }
        let packet = [opcode.as_slice(), buffer.as_slice()].concat();
        self.send_data_packet(&packet).await
    }

    pub async fn send_smart_routing_show_ui(&self, target_mac_address: &str) -> Result<()> {
        let opcode = [opcodes::SMART_ROUTING, 0x00];
        let mut buffer = Vec::with_capacity(134);
        let target_mac_bytes: Vec<u8> = target_mac_address
            .split(':')
            .map(|s| u8::from_str_radix(s, 16).unwrap())
            .collect();
        buffer.extend_from_slice(&target_mac_bytes.iter().rev().cloned().collect::<Vec<u8>>());
        buffer.extend_from_slice(&[0x7E, 0x00]);
        buffer.extend_from_slice(&[0x01, 0xE6, 0x5B]);
        buffer.extend_from_slice(b"SmartRoutingKeyShowNearbyUI");
        buffer.push(0x01);
        buffer.push(0x4A);
        buffer.extend_from_slice(b"localscore");
        buffer.extend_from_slice(&[0x31, 0x2D]);
        buffer.push(0x01);
        buffer.push(0x46);
        buffer.extend_from_slice(b"reasonHhijackv2");
        buffer.push(0x51);
        buffer.extend_from_slice(b"audioRoutingScore");
        buffer.push(0xA2);
        buffer.push(0x5F);
        buffer.extend_from_slice(b"audioRoutingSetOwnershipToFalse");
        buffer.push(0x01);
        buffer.push(0x4B);
        buffer.extend_from_slice(b"remotescore");
        buffer.push(0xA2);

        while buffer.len() < 134 {
            buffer.push(0x00);
        }

        let packet = [opcode.as_slice(), buffer.as_slice()].concat();
        self.send_data_packet(&packet).await
    }

    pub async fn send_hijack_reversed(&self, target_mac_address: &str) -> Result<()> {
        let opcode = [opcodes::SMART_ROUTING, 0x00];
        let mut buffer = Vec::with_capacity(97);
        let target_mac_bytes: Vec<u8> = target_mac_address
            .split(':')
            .map(|s| u8::from_str_radix(s, 16).unwrap())
            .collect();
        buffer.extend_from_slice(&target_mac_bytes.iter().rev().cloned().collect::<Vec<u8>>());
        buffer.extend_from_slice(&[0x59, 0x00]);
        buffer.extend_from_slice(&[0x01, 0xE3]);
        buffer.push(0x5F);
        buffer.extend_from_slice(b"audioRoutingSetOwnershipToFalse");
        buffer.push(0x01);
        buffer.push(0x59);
        buffer.extend_from_slice(b"audioRoutingShowReverseUI");
        buffer.push(0x01);
        buffer.push(0x46);
        buffer.extend_from_slice(b"reason");
        buffer.push(0x53);
        buffer.extend_from_slice(b"ReverseBannerTapped");

        while buffer.len() < 97 {
            buffer.push(0x00);
        }

        let packet = [opcode.as_slice(), buffer.as_slice()].concat();
        self.send_data_packet(&packet).await
    }

    pub async fn send_add_tipi_device(
        &self,
        self_mac_address: &str,
        target_mac_address: &str,
    ) -> Result<()> {
        let opcode = [opcodes::SMART_ROUTING, 0x00];
        let mut buffer = Vec::with_capacity(86);
        let target_mac_bytes: Vec<u8> = target_mac_address
            .split(':')
            .map(|s| u8::from_str_radix(s, 16).unwrap())
            .collect();
        buffer.extend_from_slice(&target_mac_bytes.iter().rev().cloned().collect::<Vec<u8>>());
        buffer.extend_from_slice(&[0x4E, 0x00]);
        buffer.extend_from_slice(&[0x01, 0xE5]);
        buffer.extend_from_slice(&[0x48, 0x69]);
        buffer.extend_from_slice(b"idleTime");
        buffer.extend_from_slice(&[0x08, 0x47]);
        buffer.extend_from_slice(b"newTipi");
        buffer.extend_from_slice(&[0x01, 0x49]);
        buffer.extend_from_slice(b"btAddress");
        buffer.push(0x51);
        buffer.extend_from_slice(self_mac_address.as_bytes());
        buffer.push(0x46);
        buffer.extend_from_slice(b"btName");
        buffer.push(0x43);
        buffer.extend_from_slice(b"Mac");
        buffer.push(0x50);
        buffer.extend_from_slice(b"nearbyAudioScore");
        buffer.push(0x0E);

        let packet = [opcode.as_slice(), buffer.as_slice()].concat();
        self.send_data_packet(&packet).await
    }

    pub async fn send_some_packet(&self) -> Result<()> {
        self.send_data_packet(&[0x29, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF])
            .await
    }
}

/// Applies `update` to this device's entry in devices.json off the async runtime.
async fn persist_device(mac: Address, update: impl FnOnce(&mut DeviceData) + Send + 'static) {
    let result = tokio::task::spawn_blocking(move || {
        update_devices_file(&get_devices_path(), &mac.to_string(), update)
    })
    .await;
    match result {
        Ok(Ok(())) => {}
        Ok(Err(e)) => error!("Failed to save devices: {}", e),
        Err(e) => error!("Failed to save devices: {}", e),
    }
}

async fn recv_thread(manager: AACPManager, sp: Arc<SeqPacket>) {
    let mut buf = vec![0u8; 1024];
    loop {
        match sp.recv(&mut buf).await {
            Ok(0) => {
                info!("Remote closed the connection.");
                break;
            }
            Ok(n) => {
                let data = &buf[..n];
                debug!("Received {} bytes: {}", n, hex::encode(data));
                manager.receive_packet(data).await;
            }
            Err(e) => {
                debug!("Read error: {}", e);
                info!("We have probably disconnected.");
                break;
            }
        }
    }
    info!(
        "Clearing connection state (owns, connected devices, control commands, ear detection, audio source)."
    );
    clear_connection_state(&mut *manager.state.lock().await);
    manager.connected.store(false, Ordering::SeqCst);
}

/// Forgets everything learned over a connection that has ended, so nothing acts on stale data.
fn clear_connection_state(state: &mut AACPManagerState) {
    state.sender = None;
    state.owns = false;
    state.connected_devices.clear();
    state.control_command_status_list.clear();
    state.ear_detection_status.clear();
    state.old_ear_detection_status.clear();
    state.audio_source = None;
    state.bud_role = None;
    state.capabilities.clear();
    state.model = None;
    state.firmware_major = None;
    state.custom_eq = None;
    state.accommodation = None;
}

async fn send_thread(mut rx: mpsc::Receiver<Vec<u8>>, sp: Arc<SeqPacket>) {
    while let Some(data) = rx.recv().await {
        let mut attempts = 0;
        loop {
          match sp.send(&data).await {
            Ok(_) => {
              debug!("Sent {} bytes: {}", data.len(), hex::encode(&data));
              break;
            }
            Err(e) if e.raw_os_error() == Some(107) && attempts < 10 => {
              attempts += 1;
              sleep(Duration::from_millis(100)).await;
            }
            Err(e) => {
              error!("Failed to send data: {}", e);
              return;
            }
          }
        }
    }
    info!("Send thread finished.");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn le_keys_debug_is_redacted() {
        let keys = AirPodsLEKeys {
            irk: "00112233445566778899aabbccddeeff".to_string(),
            enc_key: "ffeeddccbbaa99887766554433221100".to_string(),
        };
        let shown = format!("{:?}", keys);
        assert!(!shown.contains("00112233445566778899"), "{shown}");
        assert!(!shown.contains("ffeeddccbbaa99887766"), "{shown}");
        assert!(shown.contains("0011…"), "{shown}");
    }

    #[test]
    fn proximity_keys_summary_hides_key_bytes() {
        let summary = describe_proximity_keys(&[(1, vec![0xcc; 16]), (4, vec![0x89; 16])]);
        assert!(!summary.contains("cccc"));
        assert_eq!(summary, "IRK (16 bytes), ENC_KEY (16 bytes)");
    }

    #[test]
    fn every_identifier_round_trips() {
        for id in ControlCommandIdentifiers::ALL {
            assert_eq!(ControlCommandIdentifiers::from_u8(*id as u8), Some(*id));
        }
    }

    #[test]
    fn android_ids_are_known() {
        use ControlCommandIdentifiers::*;
        assert_eq!(ControlCommandIdentifiers::from_u8(0x37), Some(PpeToggleConfig));
        assert_eq!(ControlCommandIdentifiers::from_u8(0x3B), Some(DynamicEndOfCharge));
        assert_eq!(ControlCommandIdentifiers::from_u8(0x08), Some(BudRole));
    }

    #[test]
    fn catalog_has_no_duplicate_bytes() {
        let bytes: std::collections::HashSet<u8> = ControlCommandIdentifiers::ALL
            .iter()
            .map(|id| *id as u8)
            .collect();
        assert_eq!(bytes.len(), ControlCommandIdentifiers::ALL.len());
    }

    #[test]
    fn clearing_connection_state_forgets_model_and_role() {
        let mut state = AACPManagerState::new();
        state.model = Some(crate::devices::models::AirPodsModel::AirPodsPro1);
        state.bud_role = Some(aacp_parse::BudRole::LeftPrimary);
        state.capabilities = vec![(0x01, vec![0x05])];
        clear_connection_state(&mut state);
        assert!(state.model.is_none());
        assert!(state.bud_role.is_none());
        assert!(state.capabilities.is_empty());
    }

    /// An Information packet as the AirPods send it (header included).
    fn information_packet(model_number: &str, version3: &str) -> Vec<u8> {
        let mut packet = vec![0x04, 0x00, 0x04, 0x00, 0x1D, 0x00, 0xAA, 0xBB];
        packet.extend_from_slice(
            format!(
                "X\0Y\0Test AirPods Pro\0{model_number}\0Apple Inc.\0SER\0v1\0v2\01.0.0\0upd\0LS\0RS\0{version3}\0"
            )
            .as_bytes(),
        );
        packet
    }

    #[tokio::test]
    async fn event_channel_learns_a_model_reported_before_it_existed() {
        // The A2084 sends its Information packets right after the handshake, before the
        // device's event loop is listening; the model must still reach the tray.
        let aacp = AACPManager::new();
        aacp.receive_packet(&information_packet("A2084", "6619472")).await;
        let (tx, mut rx) = mpsc::unbounded_channel();
        aacp.set_event_channel(tx).await;
        match rx.try_recv() {
            Ok(AACPEvent::DeviceInfo {
                model,
                firmware_major,
            }) => {
                assert_eq!(model, AirPodsModel::AirPodsPro1);
                assert_eq!(firmware_major, Some(6));
            }
            other => panic!("expected the known model, got {:?}", other),
        }
    }

    #[tokio::test]
    async fn event_channel_replays_nothing_before_information() {
        let aacp = AACPManager::new();
        let (tx, mut rx) = mpsc::unbounded_channel();
        aacp.set_event_channel(tx).await;
        assert!(rx.try_recv().is_err());
    }

    #[test]
    fn clearing_connection_state_forgets_eq_and_accommodation() {
        let mut state = AACPManagerState::new();
        state.custom_eq = Some(aacp_parse::CustomEq { enabled: true, low: 1, mid: 2, high: 3 });
        state.accommodation = Some(aacp_parse::Accommodation {
            phone: true,
            media: false,
            bands: [50.0; 8],
        });
        state.firmware_major = Some(6);
        clear_connection_state(&mut state);
        assert!(state.custom_eq.is_none());
        assert!(state.accommodation.is_none());
        assert!(state.firmware_major.is_none());
    }

    #[test]
    fn clearing_connection_state_forgets_ear_and_ownership() {
        let mut state = AACPManagerState::new();
        state.owns = true;
        state.ear_detection_status = vec![EarDetectionStatus::InEar, EarDetectionStatus::InEar];
        state.old_ear_detection_status = vec![EarDetectionStatus::OutOfEar];
        state.audio_source = Some(AudioSource {
            mac: "AA:BB:CC:DD:EE:02".to_string(),
            r#type: AudioSourceType::Media,
        });
        clear_connection_state(&mut state);
        assert!(!state.owns);
        assert!(state.ear_detection_status.is_empty());
        assert!(state.old_ear_detection_status.is_empty());
        assert!(state.audio_source.is_none());
        assert!(state.sender.is_none());
    }
}
