use bluer::l2cap::{SeqPacket, Socket, SocketAddr};
use bluer::{Address, AddressType, Error, Result};
use hex;
use log::{debug, error, info};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{Mutex, mpsc};
use tokio::task::JoinSet;
use tokio::time::{Duration, Instant, sleep};

const PSM_ATT: u16 = 0x001F;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const POLL_INTERVAL: Duration = Duration::from_millis(200);

const OPCODE_ERROR_RESPONSE: u8 = 0x01;
const OPCODE_READ_REQUEST: u8 = 0x0A;
const OPCODE_WRITE_REQUEST: u8 = 0x12;
const OPCODE_HANDLE_VALUE_NTF: u8 = 0x1B;
const OPCODE_WRITE_RESPONSE: u8 = 0x13;
const RESPONSE_TIMEOUT: u64 = 5000;

/// A response to a request: Ok(payload) on success, Err(ATT error code) when the device refused.
type ResponseResult = std::result::Result<Vec<u8>, u8>;

/// What an incoming ATT PDU means, decided without touching any shared state.
#[derive(Debug, PartialEq, Eq)]
enum Pdu {
    Notification { handle: u16, value: Vec<u8> },
    WriteResponse,
    Error { code: u8 },
    Response(Vec<u8>),
    Ignored,
}

fn classify_pdu(data: &[u8]) -> Pdu {
    match data.first() {
        None => Pdu::Ignored,
        Some(&OPCODE_HANDLE_VALUE_NTF) if data.len() >= 3 => Pdu::Notification {
            handle: (data[1] as u16) | ((data[2] as u16) << 8),
            value: data[3..].to_vec(),
        },
        Some(&OPCODE_HANDLE_VALUE_NTF) => Pdu::Ignored,
        Some(&OPCODE_WRITE_RESPONSE) => Pdu::WriteResponse,
        // [0x01, request opcode, handle lsb, handle msb, error code]
        Some(&OPCODE_ERROR_RESPONSE) => Pdu::Error {
            code: data.get(4).copied().unwrap_or(0),
        },
        Some(_) => Pdu::Response(data[1..].to_vec()),
    }
}

#[repr(u16)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ATTHandles {
    AirPodsTransparency = 0x18,
    AirPodsLoudSoundReduction = 0x1B,
    AirPodsHearingAid = 0x2A,
    NothingEverything = 0x8002,
    NothingEverythingRead = 0x8005, // for some reason, and not the same as the write handle
}

#[repr(u16)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ATTCCCDHandles {
    Transparency = ATTHandles::AirPodsTransparency as u16 + 1,
    LoudSoundReduction = ATTHandles::AirPodsLoudSoundReduction as u16 + 1,
    HearingAid = ATTHandles::AirPodsHearingAid as u16 + 1,
}

impl From<ATTHandles> for ATTCCCDHandles {
    fn from(handle: ATTHandles) -> Self {
        match handle {
            ATTHandles::AirPodsTransparency => ATTCCCDHandles::Transparency,
            ATTHandles::AirPodsLoudSoundReduction => ATTCCCDHandles::LoudSoundReduction,
            ATTHandles::AirPodsHearingAid => ATTCCCDHandles::HearingAid,
            ATTHandles::NothingEverything => panic!("No CCCD for NothingEverything handle"), // we don't request it
            ATTHandles::NothingEverythingRead => panic!("No CCD for NothingEverythingRead handle"), // it sends notifications without CCCD
        }
    }
}

struct ATTManagerState {
    sender: Option<mpsc::Sender<Vec<u8>>>,
    listeners: HashMap<u16, Vec<mpsc::UnboundedSender<Vec<u8>>>>,
}

impl ATTManagerState {
    fn new() -> Self {
        ATTManagerState {
            sender: None,
            listeners: HashMap::new(),
        }
    }
}

#[derive(Clone)]
pub struct ATTManager {
    state: Arc<Mutex<ATTManagerState>>,
    response_rx: Arc<Mutex<mpsc::UnboundedReceiver<ResponseResult>>>,
    response_tx: mpsc::UnboundedSender<ResponseResult>,
    tasks: Arc<Mutex<JoinSet<()>>>,
}

impl ATTManager {
    pub fn new() -> Self {
        let (tx, rx) = mpsc::unbounded_channel();
        ATTManager {
            state: Arc::new(Mutex::new(ATTManagerState::new())),
            response_rx: Arc::new(Mutex::new(rx)),
            response_tx: tx,
            tasks: Arc::new(Mutex::new(JoinSet::new())),
        }
    }

    pub async fn connect(&mut self, addr: Address) -> Result<()> {
        info!(
            "ATTManager connecting to {} on PSM {:#06X}...",
            addr, PSM_ATT
        );
        let target_sa = SocketAddr::new(addr, AddressType::BrEdr, PSM_ATT);

        let socket = Socket::new_seq_packet()?;
        let seq_packet_result =
            tokio::time::timeout(CONNECT_TIMEOUT, socket.connect(target_sa)).await;
        let seq_packet = match seq_packet_result {
            Ok(Ok(s)) => Arc::new(s),
            Ok(Err(e)) => {
                error!("L2CAP connect failed: {}", e);
                return Err(e.into());
            }
            Err(_) => {
                error!("L2CAP connect timed out");
                return Err(Error::from(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    "Connection timeout",
                )));
            }
        };

        // Wait for connection to be fully established
        let start = Instant::now();
        loop {
            match seq_packet.peer_addr() {
                Ok(peer) if peer.cid != 0 => break,
                Ok(_) => {}
                Err(e) => {
                    if e.raw_os_error() == Some(107) {
                        // ENOTCONN
                        error!("Peer has disconnected during connection setup.");
                        return Err(e.into());
                    }
                    error!("Error getting peer address: {}", e);
                }
            }
            if start.elapsed() >= CONNECT_TIMEOUT {
                error!("Timed out waiting for L2CAP connection to be fully established.");
                return Err(Error::from(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    "Connection timeout",
                )));
            }
            sleep(POLL_INTERVAL).await;
        }

        info!("L2CAP connection established with {}", addr);

        let (tx, rx) = mpsc::channel(128);
        let state = ATTManagerState::new();
        {
            let mut s = self.state.lock().await;
            *s = state;
            s.sender = Some(tx);
        }

        let manager_clone = self.clone();
        let mut tasks = self.tasks.lock().await;
        tasks.spawn(recv_thread(manager_clone, seq_packet.clone()));
        tasks.spawn(send_thread(rx, seq_packet));

        Ok(())
    }

    pub async fn register_listener(&self, handle: ATTHandles, tx: mpsc::UnboundedSender<Vec<u8>>) {
        let mut state = self.state.lock().await;
        state.listeners.entry(handle as u16).or_default().push(tx);
    }

    pub async fn enable_notifications(&self, handle: ATTHandles) -> Result<()> {
        self.write_cccd(handle.into(), &[0x01, 0x00]).await
    }

    pub async fn read(&self, handle: ATTHandles) -> Result<Vec<u8>> {
        let lsb = (handle as u16 & 0xFF) as u8;
        let msb = ((handle as u16 >> 8) & 0xFF) as u8;
        let pdu = vec![OPCODE_READ_REQUEST, lsb, msb];
        self.send_packet(&pdu).await?;
        self.read_response().await
    }

    pub async fn write(&self, handle: ATTHandles, value: &[u8]) -> Result<()> {
        let lsb = (handle as u16 & 0xFF) as u8;
        let msb = ((handle as u16 >> 8) & 0xFF) as u8;
        let mut pdu = vec![OPCODE_WRITE_REQUEST, lsb, msb];
        pdu.extend_from_slice(value);
        self.send_packet(&pdu).await?;
        self.read_response().await?;
        Ok(())
    }

    async fn write_cccd(&self, handle: ATTCCCDHandles, value: &[u8]) -> Result<()> {
        let lsb = (handle as u16 & 0xFF) as u8;
        let msb = ((handle as u16 >> 8) & 0xFF) as u8;
        let mut pdu = vec![OPCODE_WRITE_REQUEST, lsb, msb];
        pdu.extend_from_slice(value);
        self.send_packet(&pdu).await?;
        self.read_response().await?;
        Ok(())
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

    async fn read_response(&self) -> Result<Vec<u8>> {
        debug!("Waiting for response...");
        let mut rx = self.response_rx.lock().await;
        match tokio::time::timeout(Duration::from_millis(RESPONSE_TIMEOUT), rx.recv()).await {
            Ok(Some(Ok(resp))) => Ok(resp),
            Ok(Some(Err(code))) => Err(Error::from(std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("ATT error 0x{:02x}", code),
            ))),
            Ok(None) => Err(Error::from(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "Response channel closed",
            ))),
            Err(_) => Err(Error::from(std::io::Error::new(
                std::io::ErrorKind::TimedOut,
                "Response timeout",
            ))),
        }
    }
}

async fn recv_thread(manager: ATTManager, sp: Arc<SeqPacket>) {
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
                match classify_pdu(data) {
                    Pdu::Notification { handle, value } => {
                        let state = manager.state.lock().await;
                        if let Some(listeners) = state.listeners.get(&handle) {
                            for listener in listeners {
                                let _ = listener.send(value.clone());
                            }
                        }
                    }
                    Pdu::WriteResponse => {
                        let _ = manager.response_tx.send(Ok(vec![]));
                    }
                    Pdu::Error { code } => {
                        debug!("ATT error response, code 0x{:02x}", code);
                        let _ = manager.response_tx.send(Err(code));
                    }
                    Pdu::Response(payload) => {
                        let _ = manager.response_tx.send(Ok(payload));
                    }
                    Pdu::Ignored => {}
                }
            }
            Err(e) => {
                error!("read error: {}", e);
                break;
            }
        }
    }
    let mut state = manager.state.lock().await;
    state.sender = None;
}

async fn send_thread(mut rx: mpsc::Receiver<Vec<u8>>, sp: Arc<SeqPacket>) {
    while let Some(data) = rx.recv().await {
        if let Err(e) = sp.send(&data).await {
            error!("Failed to send data: {}", e);
            break;
        }
        debug!("Sent {} bytes: {}", data.len(), hex::encode(&data));
    }
    info!("send thread finished.");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn error_response_is_classified_with_code() {
        // Error for a Write Request (0x12) on handle 0x0002, error code 0x06
        assert_eq!(
            classify_pdu(&[0x01, 0x12, 0x02, 0x00, 0x06]),
            Pdu::Error { code: 0x06 }
        );
    }

    #[test]
    fn short_error_response_does_not_panic() {
        assert_eq!(classify_pdu(&[0x01]), Pdu::Error { code: 0 });
    }

    #[test]
    fn read_response_is_data() {
        assert_eq!(
            classify_pdu(&[0x0B, 0xAA, 0xBB]),
            Pdu::Response(vec![0xAA, 0xBB])
        );
    }

    #[test]
    fn write_response_is_empty_success() {
        assert_eq!(classify_pdu(&[0x13]), Pdu::WriteResponse);
    }

    #[test]
    fn notification_is_routed_by_handle() {
        assert_eq!(
            classify_pdu(&[0x1B, 0x18, 0x00, 0x01, 0x02]),
            Pdu::Notification {
                handle: 0x0018,
                value: vec![0x01, 0x02]
            }
        );
    }
}
