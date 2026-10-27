//! Controls the running LibrePods from a terminal. The app serves a small session D-Bus
//! interface (`me.kavishdevar.librepods.Control1`); `librepods ctl …` calls it. The same bus
//! name keeps a second launch from starting a second LibrePods.

use crate::bluetooth::aacp::{AACPManager, AACPManagerState, ControlCommandIdentifiers};
use crate::bluetooth::managers::DeviceManagers;
use crate::devices::commands::cycle_listening_mode;
use crate::devices::enums::{DeviceData, DeviceType};
use crate::devices::models::{AirPodsModel, Capability};
use crate::devices::settings::{ListeningMode, decode_toggle, encode_toggle, listening_modes};
use crate::prefs::PrefsStore;
use crate::ui::messages::BluetoothUIMessage;
use crate::utils::get_devices_path;
use log::{info, warn};
use serde_json::{Map, Value, json};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::RwLock;
use tokio::sync::mpsc::UnboundedSender;
use zbus::fdo;
use zbus::interface;

pub const BUS_NAME: &str = "me.kavishdevar.librepods";
pub const OBJECT_PATH: &str = "/me/kavishdevar/librepods";
const INTERFACE: &str = "me.kavishdevar.librepods.Control1";
/// How long `ctl` waits for the running app.
const CTL_TIMEOUT: Duration = Duration::from_secs(5);
/// How long a second launch waits before giving up on the running app.
const HANDOVER_TIMEOUT: Duration = Duration::from_secs(3);

#[derive(clap::Subcommand, Debug)]
pub enum CtlCommand {
    /// List the saved AirPods and whether each is connected.
    Devices,
    /// Show battery, listening mode and every reported setting as JSON.
    Status,
    /// Set the listening mode: off, anc, transparency, adaptive, or next.
    Mode { mode: String },
    /// Turn Conversation Awareness on or off.
    Ca { state: String },
    /// Send a raw control command, for testing: hex id and 1–4 hex value bytes.
    Control { id: String, value: String },
}

#[derive(clap::Args, Debug)]
pub struct CtlArgs {
    #[command(subcommand)]
    pub command: CtlCommand,
    /// Which AirPods, when more than one pair is connected.
    #[arg(long, global = true)]
    pub mac: Option<String>,
}

/// The AirPods a request is for: the requested ones (if connected), else the only ones.
pub fn resolve_target(connected: &[String], requested: Option<&str>) -> Result<String, String> {
    match requested {
        Some(mac) => connected
            .iter()
            .find(|candidate| candidate.eq_ignore_ascii_case(mac))
            .cloned()
            .ok_or_else(|| format!("AirPods {} are not connected", mac)),
        None => match connected {
            [] => Err("no AirPods connected".to_string()),
            [only] => Ok(only.clone()),
            _ => Err("several AirPods connected; pass --mac".to_string()),
        },
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ModeArg {
    Mode(ListeningMode),
    Next,
}

pub fn parse_mode_arg(s: &str) -> Option<ModeArg> {
    match s.trim().to_ascii_lowercase().as_str() {
        "off" => Some(ModeArg::Mode(ListeningMode::Off)),
        "anc" | "nc" | "noise-cancellation" => Some(ModeArg::Mode(ListeningMode::NoiseCancellation)),
        "transparency" | "t" => Some(ModeArg::Mode(ListeningMode::Transparency)),
        "adaptive" => Some(ModeArg::Mode(ListeningMode::Adaptive)),
        "next" => Some(ModeArg::Next),
        _ => None,
    }
}

/// Only modes the model offers may be set from the command line.
pub fn mode_for_request(
    model: AirPodsModel,
    allow_off: Option<bool>,
    mode: ListeningMode,
) -> Result<ListeningMode, String> {
    if listening_modes(model, allow_off).contains(&mode) {
        Ok(mode)
    } else {
        Err(format!("{} can't use {}", model.display_name(), mode.label()))
    }
}

/// A raw control command: hex identifier and 1–4 hex value bytes.
pub fn parse_control_args(id: &str, value: &str) -> Result<(u8, Vec<u8>), String> {
    let id_hex = id.trim().trim_start_matches("0x").trim_start_matches("0X");
    let id = u8::from_str_radix(id_hex, 16).map_err(|_| format!("`{}` is not a hex control id", id))?;
    let value_hex = value.trim().trim_start_matches("0x").trim_start_matches("0X");
    let value = hex::decode(value_hex).map_err(|_| format!("`{}` is not hex bytes", value))?;
    if value.is_empty() || value.len() > 4 {
        return Err("a control value is 1 to 4 bytes, like 01 or 5050".to_string());
    }
    Ok((id, value))
}

fn reported(state: &AACPManagerState, id: ControlCommandIdentifiers) -> Option<&[u8]> {
    state
        .control_command_status_list
        .iter()
        .find(|status| status.identifier == id)
        .map(|status| status.value.as_slice())
}

/// Everything `ctl status` shows, as JSON.
pub fn status_json(mac: &str, name: &str, state: &AACPManagerState) -> String {
    let battery: Vec<Value> = state
        .battery_info
        .iter()
        .map(|b| {
            json!({
                "component": format!("{:?}", b.component),
                "level": b.level,
                "status": format!("{:?}", b.status),
            })
        })
        .collect();
    let controls: Map<String, Value> = state
        .control_command_status_list
        .iter()
        .map(|status| {
            (
                format!("{:#04x}", status.identifier as u8),
                Value::String(hex::encode(&status.value)),
            )
        })
        .collect();
    let listening_mode = reported(state, ControlCommandIdentifiers::ListeningMode)
        .and_then(|value| value.first().copied())
        .and_then(ListeningMode::from_byte)
        .map(ListeningMode::cli_name);
    json!({
        "mac": mac,
        "name": name,
        "model": state.model.map(|model| model.display_name()),
        "firmwareMajor": state.firmware_major,
        "battery": battery,
        "listeningMode": listening_mode,
        "controls": controls,
    })
    .to_string()
}

fn saved_airpods() -> Vec<(String, String)> {
    let devices: HashMap<String, DeviceData> = std::fs::read_to_string(get_devices_path())
        .ok()
        .and_then(|json| serde_json::from_str(&json).ok())
        .unwrap_or_default();
    let mut airpods: Vec<(String, String)> = devices
        .into_iter()
        .filter(|(_, data)| data.type_ == DeviceType::AirPods)
        .map(|(mac, data)| (mac, data.name))
        .collect();
    airpods.sort();
    airpods
}

struct ControlService {
    device_managers: Arc<RwLock<HashMap<String, DeviceManagers>>>,
    prefs: PrefsStore,
    ui_tx: Option<UnboundedSender<BluetoothUIMessage>>,
}

impl ControlService {
    async fn connected(&self) -> Vec<(String, Arc<AACPManager>)> {
        let mut connected: Vec<(String, Arc<AACPManager>)> = self
            .device_managers
            .read()
            .await
            .iter()
            .filter_map(|(mac, managers)| {
                managers
                    .get_aacp()
                    .filter(|aacp| aacp.is_connected())
                    .map(|aacp| (mac.clone(), aacp))
            })
            .collect();
        connected.sort_by(|a, b| a.0.cmp(&b.0));
        connected
    }

    async fn target(&self, mac: &str) -> fdo::Result<(String, Arc<AACPManager>)> {
        let connected = self.connected().await;
        let macs: Vec<String> = connected.iter().map(|(mac, _)| mac.clone()).collect();
        let requested = (!mac.trim().is_empty()).then_some(mac.trim());
        let mac = resolve_target(&macs, requested).map_err(fdo::Error::Failed)?;
        connected
            .into_iter()
            .find(|(candidate, _)| *candidate == mac)
            .ok_or_else(|| fdo::Error::Failed(format!("AirPods {} are not connected", mac)))
    }
}

fn send_failed(e: impl std::fmt::Display) -> fdo::Error {
    fdo::Error::Failed(format!("could not send to the AirPods: {}", e))
}

#[interface(name = "me.kavishdevar.librepods.Control1")]
impl ControlService {
    async fn devices(&self) -> Vec<(String, String, bool)> {
        let connected: Vec<String> = self.connected().await.into_iter().map(|(mac, _)| mac).collect();
        saved_airpods()
            .into_iter()
            .map(|(mac, name)| {
                let is_connected = connected.contains(&mac);
                (mac, name, is_connected)
            })
            .collect()
    }

    async fn status(&self, mac: String) -> fdo::Result<String> {
        let (mac, aacp) = self.target(&mac).await?;
        let name = saved_airpods()
            .into_iter()
            .find(|(saved, _)| *saved == mac)
            .map_or_else(|| mac.clone(), |(_, name)| name);
        let state = aacp.state.lock().await;
        Ok(status_json(&mac, &name, &state))
    }

    async fn set_listening_mode(&self, mac: String, mode: String) -> fdo::Result<()> {
        let request = parse_mode_arg(&mode).ok_or_else(|| {
            fdo::Error::Failed(format!(
                "unknown listening mode `{}`; use off, anc, transparency, adaptive or next",
                mode
            ))
        })?;
        let (mac, aacp) = self.target(&mac).await?;
        match request {
            ModeArg::Next => {
                cycle_listening_mode(&aacp, &self.prefs.device(&mac))
                    .await
                    .ok_or_else(|| fdo::Error::Failed("no listening mode to switch to".to_string()))?;
            }
            ModeArg::Mode(mode) => {
                let (model, allow_off) = {
                    let state = aacp.state.lock().await;
                    (
                        state.model.unwrap_or(AirPodsModel::Unknown),
                        reported(&state, ControlCommandIdentifiers::AllowOffOption).and_then(decode_toggle),
                    )
                };
                let mode = mode_for_request(model, allow_off, mode).map_err(fdo::Error::Failed)?;
                info!("ctl: setting listening mode to {}", mode.label());
                aacp.send_control_command(ControlCommandIdentifiers::ListeningMode, &[mode.byte()])
                    .await
                    .map_err(send_failed)?;
            }
        }
        Ok(())
    }

    async fn set_conversation_awareness(&self, mac: String, on: bool) -> fdo::Result<()> {
        let (_, aacp) = self.target(&mac).await?;
        let model = aacp.state.lock().await.model.unwrap_or(AirPodsModel::Unknown);
        if !model.has(Capability::ConversationAwareness) {
            return Err(fdo::Error::Failed(format!(
                "{} have no Conversation Awareness",
                model.display_name()
            )));
        }
        aacp.send_control_command(ControlCommandIdentifiers::ConversationDetectConfig, &encode_toggle(on))
            .await
            .map_err(send_failed)
    }

    async fn set_control(&self, mac: String, id: u8, value: Vec<u8>) -> fdo::Result<()> {
        let identifier = ControlCommandIdentifiers::from_u8(id)
            .ok_or_else(|| fdo::Error::Failed(format!("unknown control id {:#04x}", id)))?;
        if value.is_empty() || value.len() > 4 {
            return Err(fdo::Error::Failed("a control value is 1 to 4 bytes".to_string()));
        }
        let (_, aacp) = self.target(&mac).await?;
        info!("ctl: sending {:?} = {}", identifier, hex::encode(&value));
        aacp.send_control_command(identifier, &value)
            .await
            .map_err(send_failed)
    }

    /// Shows the running app's window (a second launch calls this instead of starting).
    async fn open_window(&self) -> fdo::Result<()> {
        match &self.ui_tx {
            Some(ui_tx) => ui_tx
                .send(BluetoothUIMessage::OpenWindow)
                .map_err(|_| fdo::Error::Failed("the window is not available".to_string())),
            None => Err(fdo::Error::Failed("LibrePods is running without a window".to_string())),
        }
    }
}

/// Offers the control interface on the session bus. `None` (logged) when the bus is
/// unavailable or another LibrePods already holds the name.
pub async fn serve(
    device_managers: Arc<RwLock<HashMap<String, DeviceManagers>>>,
    prefs: PrefsStore,
    ui_tx: Option<UnboundedSender<BluetoothUIMessage>>,
) -> Option<zbus::Connection> {
    let service = ControlService {
        device_managers,
        prefs,
        ui_tx,
    };
    let connection = async {
        zbus::connection::Builder::session()?
            .serve_at(OBJECT_PATH, service)?
            .name(BUS_NAME)?
            .build()
            .await
    };
    match connection.await {
        Ok(connection) => {
            info!("Serving {} on the session bus", BUS_NAME);
            Some(connection)
        }
        Err(e) => {
            warn!("Could not offer {} on the session bus: {}", BUS_NAME, e);
            None
        }
    }
}

/// A session-bus connection whose calls give up after `timeout`, so a stuck LibrePods can't
/// make `ctl` or a second launch hang.
async fn control_connection(
    builder: zbus::connection::Builder<'static>,
    timeout: Duration,
) -> zbus::Result<zbus::Connection> {
    builder.method_timeout(timeout).build().await
}

/// Asks the running LibrePods to show its window.
async fn open_window_on(connection: &zbus::Connection) -> Result<(), String> {
    call(connection, "OpenWindow", &()).await.map(|_| ())
}

async fn running_instance(connection: &zbus::Connection) -> Result<bool, String> {
    let dbus = fdo::DBusProxy::new(connection)
        .await
        .map_err(|e| format!("could not reach the session bus: {}", e))?;
    let name = zbus::names::BusName::try_from(BUS_NAME).map_err(|e| e.to_string())?;
    dbus.name_has_owner(name)
        .await
        .map_err(|e| format!("could not reach the session bus: {}", e))
}

fn describe(e: zbus::Error) -> String {
    match e {
        zbus::Error::MethodError(_, Some(message), _) => message,
        other => other.to_string(),
    }
}

async fn call<B>(connection: &zbus::Connection, method: &str, body: &B) -> Result<zbus::Message, String>
where
    B: serde::Serialize + zbus::zvariant::DynamicType,
{
    connection
        .call_method(Some(BUS_NAME), OBJECT_PATH, Some(INTERFACE), method, body)
        .await
        .map_err(describe)
}

async fn ctl(args: CtlArgs) -> Result<String, String> {
    let builder = zbus::connection::Builder::session()
        .map_err(|e| format!("could not reach the session bus: {}", e))?;
    let connection = control_connection(builder, CTL_TIMEOUT)
        .await
        .map_err(|e| format!("could not reach the session bus: {}", e))?;
    if !running_instance(&connection).await? {
        return Err("LibrePods is not running".to_string());
    }
    let mac = args.mac.unwrap_or_default();
    match args.command {
        CtlCommand::Devices => {
            let reply = call(&connection, "Devices", &()).await?;
            let devices: Vec<(String, String, bool)> =
                reply.body().deserialize().map_err(|e| e.to_string())?;
            let devices: Vec<Value> = devices
                .into_iter()
                .map(|(mac, name, connected)| json!({"mac": mac, "name": name, "connected": connected}))
                .collect();
            serde_json::to_string_pretty(&devices).map_err(|e| e.to_string())
        }
        CtlCommand::Status => {
            let reply = call(&connection, "Status", &(mac,)).await?;
            let status: String = reply.body().deserialize().map_err(|e| e.to_string())?;
            let status: Value = serde_json::from_str(&status).map_err(|e| e.to_string())?;
            serde_json::to_string_pretty(&status).map_err(|e| e.to_string())
        }
        CtlCommand::Mode { mode } => {
            if parse_mode_arg(&mode).is_none() {
                return Err(format!(
                    "unknown listening mode `{}`; use off, anc, transparency, adaptive or next",
                    mode
                ));
            }
            call(&connection, "SetListeningMode", &(mac, mode)).await?;
            Ok(String::new())
        }
        CtlCommand::Ca { state } => {
            let on = match state.trim().to_ascii_lowercase().as_str() {
                "on" => true,
                "off" => false,
                _ => return Err(format!("`{}`: use on or off", state)),
            };
            call(&connection, "SetConversationAwareness", &(mac, on)).await?;
            Ok(String::new())
        }
        CtlCommand::Control { id, value } => {
            let (id, value) = parse_control_args(&id, &value)?;
            call(&connection, "SetControl", &(mac, id, value)).await?;
            Ok(String::new())
        }
    }
}

/// Runs `librepods ctl …` against the running app. Returns the process exit code.
pub fn run_ctl(args: CtlArgs) -> i32 {
    let runtime = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
        Ok(runtime) => runtime,
        Err(e) => {
            eprintln!("librepods ctl: {}", e);
            return 1;
        }
    };
    match runtime.block_on(ctl(args)) {
        Ok(output) => {
            if !output.is_empty() {
                println!("{}", output);
            }
            0
        }
        Err(message) => {
            eprintln!("librepods ctl: {}", message);
            1
        }
    }
}

/// If LibrePods is already running, returns true so this launch exits instead of starting a
/// second instance; with `show_window` (an ordinary launch, not autostart) it first asks the
/// running one to show its window. Every step gives up after a few seconds.
pub fn hand_over_to_running_instance(show_window: bool) -> bool {
    let Ok(runtime) = tokio::runtime::Builder::new_current_thread().enable_all().build() else {
        return false;
    };
    runtime.block_on(async {
        let connect = async {
            let builder = zbus::connection::Builder::session().ok()?;
            control_connection(builder, HANDOVER_TIMEOUT).await.ok()
        };
        let Ok(Some(connection)) = tokio::time::timeout(HANDOVER_TIMEOUT, connect).await else {
            return false;
        };
        if !matches!(running_instance(&connection).await, Ok(true)) {
            return false;
        }
        if !show_window {
            info!("LibrePods is already running; not starting another");
            return true;
        }
        match open_window_on(&connection).await {
            Ok(()) => info!("LibrePods is already running; showed its window"),
            Err(e) => warn!("LibrePods is already running ({}); not starting another", e),
        }
        true
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bluetooth::aacp::{AACPManagerState, ControlCommandIdentifiers as Id, ControlCommandStatus};
    use crate::devices::models::AirPodsModel;
    use crate::devices::settings::ListeningMode;

    const A: &str = "AA:BB:CC:DD:EE:01";

    /// A LibrePods that accepts calls and never answers, like a wedged instance.
    struct StuckApp;

    #[zbus::interface(name = "me.kavishdevar.librepods.Control1")]
    impl StuckApp {
        async fn open_window(&self) {
            std::future::pending::<()>().await
        }
    }

    #[tokio::test]
    async fn asking_a_stuck_app_for_its_window_gives_up() {
        let (server_side, client_side) = tokio::net::UnixStream::pair().unwrap();
        let server = async {
            zbus::connection::Builder::unix_stream(server_side)
                .server(zbus::Guid::generate())
                .unwrap()
                .p2p()
                .serve_at(OBJECT_PATH, StuckApp)
                .unwrap()
                .build()
                .await
                .unwrap()
        };
        let client = control_connection(
            zbus::connection::Builder::unix_stream(client_side).p2p(),
            Duration::from_millis(200),
        );
        let (_server, client) = tokio::join!(server, client);
        let client = client.unwrap();
        let asked = tokio::time::timeout(Duration::from_secs(3), open_window_on(&client)).await;
        assert!(matches!(asked, Ok(Err(_))), "asking a stuck app hung: {:?}", asked);
    }
    const B: &str = "11:22:33:44:55:66";

    #[test]
    fn resolve_target_rules() {
        let one = vec![A.to_string()];
        assert_eq!(resolve_target(&one, None), Ok(A.to_string()));
        assert_eq!(resolve_target(&[], None), Err("no AirPods connected".to_string()));
        let two = vec![A.to_string(), B.to_string()];
        assert!(resolve_target(&two, None).unwrap_err().contains("--mac"));
        assert_eq!(resolve_target(&two, Some(B)), Ok(B.to_string()));
        assert_eq!(resolve_target(&one, Some(&A.to_lowercase())), Ok(A.to_string()));
        assert!(resolve_target(&one, Some(B)).is_err());
    }

    #[test]
    fn mode_arg_parsing() {
        for name in ["anc", "nc", "noise-cancellation", "ANC"] {
            assert_eq!(parse_mode_arg(name), Some(ModeArg::Mode(ListeningMode::NoiseCancellation)), "{name}");
        }
        assert_eq!(parse_mode_arg("t"), Some(ModeArg::Mode(ListeningMode::Transparency)));
        assert_eq!(parse_mode_arg("transparency"), Some(ModeArg::Mode(ListeningMode::Transparency)));
        assert_eq!(parse_mode_arg("off"), Some(ModeArg::Mode(ListeningMode::Off)));
        assert_eq!(parse_mode_arg("adaptive"), Some(ModeArg::Mode(ListeningMode::Adaptive)));
        assert_eq!(parse_mode_arg("next"), Some(ModeArg::Next));
        assert_eq!(parse_mode_arg("loud"), None);
    }

    #[test]
    fn ctl_rejects_mode_the_model_lacks() {
        use ListeningMode::*;
        assert!(mode_for_request(AirPodsModel::AirPodsPro1, None, Adaptive).is_err());
        assert_eq!(mode_for_request(AirPodsModel::AirPodsPro1, None, Off), Ok(Off));
        assert!(mode_for_request(AirPodsModel::AirPodsPro2UsbC, None, Off).is_err());
        assert_eq!(mode_for_request(AirPodsModel::AirPodsPro2UsbC, Some(true), Off), Ok(Off));
        assert!(mode_for_request(AirPodsModel::AirPods2, Some(true), NoiseCancellation).is_err());
    }

    #[test]
    fn parse_control_args_hex() {
        assert_eq!(parse_control_args("0d", "02"), Ok((0x0D, vec![0x02])));
        assert_eq!(parse_control_args("0x1F", "5050"), Ok((0x1F, vec![0x50, 0x50])));
        assert!(parse_control_args("zz", "00").is_err());
        assert!(parse_control_args("0d", "0102030405").is_err());
        assert!(parse_control_args("0d", "").is_err());
        assert!(parse_control_args("0d", "abc").is_err());
    }

    #[test]
    fn status_json_shape() {
        let mut state = AACPManagerState::new();
        state.model = Some(AirPodsModel::AirPodsPro1);
        state.firmware_major = Some(6);
        state.control_command_status_list = vec![
            ControlCommandStatus { identifier: Id::ListeningMode, value: vec![0x03] },
            ControlCommandStatus { identifier: Id::ChimeVolume, value: vec![0x50, 0x50] },
            ControlCommandStatus { identifier: Id::JitterBuffer, value: vec![0x07] },
        ];
        let status: serde_json::Value =
            serde_json::from_str(&status_json(A, "Test AirPods Pro", &state)).unwrap();
        assert_eq!(status["mac"], A);
        assert_eq!(status["name"], "Test AirPods Pro");
        assert_eq!(status["model"], "AirPods Pro (1st generation)");
        assert_eq!(status["firmwareMajor"], 6);
        assert_eq!(status["listeningMode"], "transparency");
        assert_eq!(status["controls"]["0x0d"], "03");
        assert_eq!(status["controls"]["0x1f"], "5050");
        assert_eq!(status["controls"]["0x0b"], "07");
        assert!(status["battery"].as_array().is_some_and(|b| b.is_empty()));

        let unknown = AACPManagerState::new();
        let status: serde_json::Value = serde_json::from_str(&status_json(A, A, &unknown)).unwrap();
        assert!(status["model"].is_null());
        assert!(status["listeningMode"].is_null());
    }
}
