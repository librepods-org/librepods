use crate::bluetooth::aacp::AACPEvent;

#[derive(Debug, Clone)]
pub enum BluetoothUIMessage {
    OpenWindow,
    DeviceConnected(String),               // mac
    DeviceDisconnected(String),            // mac
    AACPUIEvent(String, AACPEvent),        // mac, event
    ATTNotification(String, u16, Vec<u8>), // mac, handle, data
    ShowPopup {
        mac: String,
        battery_l: Option<u8>,
        battery_r: Option<u8>,
        battery_c: Option<u8>,
        charging_l: bool,
        charging_r: bool,
        charging_c: bool,
    },
    NoOp,
}
