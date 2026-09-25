//! A2DP recovery: after the hi-res mic puts the AirPods into their bidirectional
//! "call" mode, A2DP playback degrades to mono/right until the audio link is
//! re-established. This toggles the AirPods' A2DP service off then on — the
//! programmatic equivalent of disconnecting + reconnecting them — to restore
//! stereo, without a full Bluetooth restart.

use std::mem::{size_of, zeroed};

use windows_sys::Win32::Devices::Bluetooth::{
    BLUETOOTH_DEVICE_INFO, BLUETOOTH_DEVICE_SEARCH_PARAMS, BLUETOOTH_FIND_RADIO_PARAMS,
    BluetoothFindDeviceClose, BluetoothFindFirstDevice, BluetoothFindFirstRadio,
    BluetoothFindNextDevice, BluetoothFindRadioClose, BluetoothSetServiceState,
};
use windows_sys::Win32::Foundation::{CloseHandle, HANDLE};
use windows_sys::core::GUID;

// AudioSink (A2DP) — the service the AirPods actually expose for audio (0x110B).
// (0x110D AdvancedAudioDistribution isn't an installed service here -> ERROR 1060.)
const A2DP_SERVICE: GUID = GUID {
    data1: 0x0000_110B,
    data2: 0x0000,
    data3: 0x1000,
    data4: [0x80, 0x00, 0x00, 0x80, 0x5F, 0x9B, 0x34, 0xFB],
};

const BLUETOOTH_SERVICE_DISABLE: u32 = 0x00;
const BLUETOOTH_SERVICE_ENABLE: u32 = 0x01;

fn find_device(mac: u64) -> Option<BLUETOOTH_DEVICE_INFO> {
    unsafe {
        let mut params: BLUETOOTH_DEVICE_SEARCH_PARAMS = zeroed();
        params.dwSize = size_of::<BLUETOOTH_DEVICE_SEARCH_PARAMS>() as u32;
        params.fReturnAuthenticated = 1;
        params.fReturnRemembered = 1;
        params.fReturnConnected = 1;
        params.fReturnUnknown = 1;

        let mut info: BLUETOOTH_DEVICE_INFO = zeroed();
        info.dwSize = size_of::<BLUETOOTH_DEVICE_INFO>() as u32;
        let h = BluetoothFindFirstDevice(&params, &mut info);
        if h.is_null() {
            return None;
        }
        let mut found = None;
        loop {
            if info.Address.Anonymous.ullLong == mac {
                found = Some(info);
                break;
            }
            info.dwSize = size_of::<BLUETOOTH_DEVICE_INFO>() as u32;
            if BluetoothFindNextDevice(h, &mut info) == 0 {
                break;
            }
        }
        BluetoothFindDeviceClose(h);
        found
    }
}

/// Reconnect the AirPods' A2DP service (disable then enable) to restore stereo.
/// Returns the (disable, enable) Win32 result codes: 0 = success, 5 = access
/// denied (needs elevation), 0xFFFFFFFF = device not found, 0xFFFFFFFE = no radio.
pub fn reset(mac: u64) -> (u32, u32) {
    unsafe {
        let info = match find_device(mac) {
            Some(i) => i,
            None => return (0xFFFF_FFFF, 0),
        };
        let mut rparams: BLUETOOTH_FIND_RADIO_PARAMS = zeroed();
        rparams.dwSize = size_of::<BLUETOOTH_FIND_RADIO_PARAMS>() as u32;
        let mut radio: HANDLE = std::ptr::null_mut();
        let hfind = BluetoothFindFirstRadio(&rparams, &mut radio);
        if hfind.is_null() {
            return (0xFFFF_FFFE, 0);
        }

        // Let the mic-stop transition settle so the AirPods have left mic mode
        // before we touch A2DP.
        std::thread::sleep(std::time::Duration::from_millis(150));
        let d = BluetoothSetServiceState(radio, &info, &A2DP_SERVICE, BLUETOOTH_SERVICE_DISABLE);
        // Let the A2DP link tear down before reconnecting, else it re-establishes
        // mid-teardown (mono/crackle) and needs another try. (The reconnect
        // handshake after ENABLE is Windows/BT — we can't speed that part up.)
        std::thread::sleep(std::time::Duration::from_millis(1000));
        let e = BluetoothSetServiceState(radio, &info, &A2DP_SERVICE, BLUETOOTH_SERVICE_ENABLE);
        // ENABLE returns immediately but the A2DP reconnect handshake runs after
        // it in the BT stack; wait for it so the caller's "restored" card lands
        // when the stereo is actually back (not before).
        std::thread::sleep(std::time::Duration::from_millis(1500));

        CloseHandle(radio);
        BluetoothFindRadioClose(hfind);
        (d, e)
    }
}
