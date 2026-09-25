//! Locate the paired AirPods and return their 48-bit Bluetooth address.

use std::mem::{size_of, zeroed};
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};

use windows_sys::Win32::Devices::Bluetooth::{
    BLUETOOTH_DEVICE_INFO, BLUETOOTH_DEVICE_SEARCH_PARAMS, BLUETOOTH_FIND_RADIO_PARAMS,
    BLUETOOTH_RADIO_INFO, BluetoothFindDeviceClose, BluetoothFindFirstDevice,
    BluetoothFindFirstRadio, BluetoothFindNextDevice, BluetoothFindNextRadio, BluetoothFindRadioClose,
    BluetoothGetRadioInfo, BluetoothSetServiceState,
};
use windows_sys::Win32::Foundation::{CloseHandle, HANDLE};
use windows_sys::core::GUID;

// Classic-audio service GUIDs — A2DP AudioSink + Handsfree.
const AUDIO_SINK: GUID = GUID {
    data1: 0x0000_110b, data2: 0, data3: 0x1000,
    data4: [0x80, 0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb],
};
const HANDSFREE: GUID = GUID {
    data1: 0x0000_111e, data2: 0, data3: 0x1000,
    data4: [0x80, 0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb],
};

/// Real Bluetooth connect/disconnect of the AirPods' AUDIO by toggling their audio
/// services on the local radio — distinct from releasing our AAP control session.
/// `connect = false` disconnects (Windows drops the device); `true` reconnects.
/// Returns true if at least one service state was set. May require the device to be
/// paired and, on some systems, elevation.
pub fn set_audio_connected(mac: u64, connect: bool) -> bool {
    unsafe {
        let dev = match device_info(mac) {
            Some(d) => d,
            None => return false,
        };
        let flags: u32 = if connect { 1 } else { 0 }; // ENABLE / DISABLE
        // Try EVERY radio, not just the first.
        //
        // This machine has two (a disabled Realtek and the live Intel), and
        // `BluetoothFindFirstRadio` hands back whichever the enumerator lists
        // first — which need not be the one the AirPods are paired through. Every
        // `set_audio_connected` in the log had failed, on every session, which is
        // exactly what aiming these calls at the wrong radio looks like. Walking
        // the list costs a handle per radio and makes the call correct on any
        // machine with more than one adapter (docking stations, USB dongles
        // alongside a built-in).
        //
        // Enable BOTH A2DP (stereo output) and Handsfree so at least one audio
        // endpoint always comes up — enabling only A2DP left the user with NO sound
        // when A2DP alone failed to connect (no HFP fallback).
        let mut rparams: BLUETOOTH_FIND_RADIO_PARAMS = zeroed();
        rparams.dwSize = size_of::<BLUETOOTH_FIND_RADIO_PARAMS>() as u32;
        let mut hradio: HANDLE = std::ptr::null_mut();
        let hfind = BluetoothFindFirstRadio(&rparams, &mut hradio);
        if hfind.is_null() {
            return false;
        }
        // Best codes seen across the radios: a success anywhere beats a failure.
        let (mut best_a, mut best_b) = (u32::MAX, u32::MAX);
        let mut any = false;
        loop {
            let a = BluetoothSetServiceState(hradio, &dev, &AUDIO_SINK, flags);
            let b = BluetoothSetServiceState(hradio, &dev, &HANDSFREE, flags);
            CloseHandle(hradio);
            if a < best_a { best_a = a; }
            if b < best_b { best_b = b; }
            any |= a == 0 || b == 0;
            hradio = std::ptr::null_mut();
            if BluetoothFindNextRadio(hfind, &mut hradio) == 0 {
                break;
            }
        }
        BluetoothFindRadioClose(hfind);
        LAST_SERVICE_CODES.store(((best_a as u64) << 32) | best_b as u64, Ordering::Relaxed);
        any // ERROR_SUCCESS on at least one service, on at least one radio
    }
}

/// Win32 result of the last `set_audio_connected` call, as (AudioSink, Handsfree).
///
/// The bool that `set_audio_connected` returns collapses two Win32 codes into
/// "something worked", which left the reclaim campaign logging `= false` with no
/// way to tell a device that is merely busy mid-handoff (retry!) from one that is
/// gone (give up) or an access-denied (needs elevation). Read this right after a
/// call to log what actually happened. 0 = ERROR_SUCCESS.
static LAST_SERVICE_CODES: AtomicU64 = AtomicU64::new(0);

pub fn last_service_codes() -> (u32, u32) {
    let v = LAST_SERVICE_CODES.load(Ordering::Relaxed);
    ((v >> 32) as u32, v as u32)
}

/// Windows' own record of a paired device: its friendly name and Class of Device.
/// Authoritative when it exists — it is what Windows shows the user — but it only
/// covers devices paired to THIS machine, so a phone sharing the AirPods with us
/// usually is not in here.
pub fn paired_device(addr: u64) -> Option<(String, u32)> {
    unsafe { device_info(addr).map(|d| (utf16_name(&d.szName), d.ulClassofDevice)) }
}

/// Map a Bluetooth Class of Device to the icon kind the UI understands.
/// Major device class is bits 8..12; the minor class refines Computer and Wearable.
pub fn kind_from_cod(cod: u32) -> &'static str {
    let major = (cod >> 8) & 0x1F;
    let minor = (cod >> 2) & 0x3F;
    match major {
        0x01 => match minor {
            0x03 | 0x04 => "laptop", // laptop / handheld PC
            _ => "pc",
        },
        0x02 => "phone",
        0x04 => "audio",
        0x07 => match minor {
            0x02 => "watch",
            _ => "unknown",
        },
        _ => "unknown",
    }
}

/// Every local Bluetooth radio address. Needed to read the AirPods' "connected
/// devices" list (AAP 0x2E): an address in it that is NOT one of ours is another
/// host sharing the buds with us. Returns all of them because a machine can have
/// more than one adapter and we do not know which one the buds are paired through.
pub fn local_radio_addresses() -> Vec<u64> {
    unsafe {
        let mut out = Vec::new();
        let mut rparams: BLUETOOTH_FIND_RADIO_PARAMS = zeroed();
        rparams.dwSize = size_of::<BLUETOOTH_FIND_RADIO_PARAMS>() as u32;
        let mut hradio: HANDLE = std::ptr::null_mut();
        let hfind = BluetoothFindFirstRadio(&rparams, &mut hradio);
        if hfind.is_null() {
            return out;
        }
        loop {
            let mut info: BLUETOOTH_RADIO_INFO = zeroed();
            info.dwSize = size_of::<BLUETOOTH_RADIO_INFO>() as u32;
            if BluetoothGetRadioInfo(hradio, &mut info) == 0 {
                out.push(info.address.Anonymous.ullLong);
            }
            CloseHandle(hradio);
            hradio = std::ptr::null_mut();
            if BluetoothFindNextRadio(hfind, &mut hradio) == 0 {
                break;
            }
        }
        BluetoothFindRadioClose(hfind);
        out
    }
}

/// Serializes the audio-service toggles, and lets a newer request cancel an older
/// one that is still queued behind it.
///
/// `BluetoothSetServiceState` blocks for seconds, so two unsynchronized calls
/// race: a DISABLE issued *before* an ENABLE can complete *after* it, which
/// leaves the AirPods connected on AAP with their audio services switched off —
/// no sound, and nothing left to notice or undo it. Observed in daemon.log:
///
///   08:47:49 cmd received: Disconnect          -> queues DISABLE
///   08:47:50 ble: AirPods nearby -> auto-connecting -> queues ENABLE
///   08:47:56 bt: auto-connect audio = true     <- ENABLE done
///   08:48:01 bt: disconnect audio = true       <- DISABLE done 4 s LATER
///
/// One gate plus a monotonic intent counter fixes the ordering: the newest
/// request wins and every request it overtook is dropped before it can run.
static AUDIO_GATE: Mutex<()> = Mutex::new(());
static AUDIO_INTENT: AtomicU64 = AtomicU64::new(0);

/// Ask the OS to connect/disconnect the AirPods' audio, serialized against every
/// other such request. Returns `None` when a newer request superseded this one
/// while it waited for the gate (nothing was sent), else `Some(ok)` from
/// `set_audio_connected`. Blocks — call it off the session thread.
pub fn request_audio(mac: u64, connect: bool) -> Option<bool> {
    let mine = AUDIO_INTENT.fetch_add(1, Ordering::SeqCst) + 1;
    let _gate = AUDIO_GATE.lock().unwrap_or_else(|e| e.into_inner());
    if AUDIO_INTENT.load(Ordering::SeqCst) != mine {
        return None; // someone newer queued behind us while we waited — they win
    }
    Some(set_audio_connected(mac, connect))
}

/// True if Windows currently holds a **classic link** to this device.
///
/// The `BLUETOOTH_DEVICE_SEARCH_PARAMS` flags are OR'd filters, so asking only
/// for `fReturnConnected` returns only currently-connected devices. This is NOT
/// what `find_airpods()` does: it also passes `fReturnRemembered`, so it matches
/// a merely *paired* device and can never be used as a liveness check.
pub fn is_connected(mac: u64) -> bool {
    unsafe {
        let mut params: BLUETOOTH_DEVICE_SEARCH_PARAMS = zeroed();
        params.dwSize = size_of::<BLUETOOTH_DEVICE_SEARCH_PARAMS>() as u32;
        params.fReturnConnected = 1;
        let mut info: BLUETOOTH_DEVICE_INFO = zeroed();
        info.dwSize = size_of::<BLUETOOTH_DEVICE_INFO>() as u32;
        let h = BluetoothFindFirstDevice(&params, &mut info);
        if h.is_null() {
            return false;
        }
        let mut connected = false;
        loop {
            if info.Address.Anonymous.ullLong == mac && info.fConnected != 0 {
                connected = true;
                break;
            }
            info.dwSize = size_of::<BLUETOOTH_DEVICE_INFO>() as u32;
            if BluetoothFindNextDevice(h, &mut info) == 0 {
                break;
            }
        }
        BluetoothFindDeviceClose(h);
        connected
    }
}

/// Look up a paired device's `BLUETOOTH_DEVICE_INFO` by its 48-bit address.
unsafe fn device_info(mac: u64) -> Option<BLUETOOTH_DEVICE_INFO> {
    let mut params: BLUETOOTH_DEVICE_SEARCH_PARAMS = zeroed();
    params.dwSize = size_of::<BLUETOOTH_DEVICE_SEARCH_PARAMS>() as u32;
    params.fReturnAuthenticated = 1;
    params.fReturnRemembered = 1;
    params.fReturnConnected = 1;
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

fn utf16_name(buf: &[u16]) -> String {
    let end = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
    String::from_utf16_lossy(&buf[..end])
}

/// Name patterns of Apple-chip audio devices that speak the AAP protocol — not
/// just AirPods but Beats too (Powerbeats, Beats Fit Pro, Studio Buds, Solo,
/// Studio3, Flex…), which share the same H1/W1 chip and endpoint. Matched
/// case-insensitively against the paired device's name.
const AAP_NAME_HINTS: &[&str] = &["airpod", "beats"];

/// (address, display name) of the first paired device whose name matches a known
/// AAP device (AirPods / Beats). The Windows " - Find My" suffix is stripped for
/// display.
pub fn find_airpods() -> Option<(u64, String)> {
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
            let name = utf16_name(&info.szName);
            let lname = name.to_lowercase();
            if AAP_NAME_HINTS.iter().any(|h| lname.contains(h)) {
                let clean = name
                    .trim_end_matches("- Find My")
                    .trim_end_matches(" -")
                    .trim()
                    .to_string();
                found = Some((info.Address.Anonymous.ullLong, clean));
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
