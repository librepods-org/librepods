//! BLE advertisement watcher: detect the AirPods nearby (their Apple
//! proximity-pairing advertisement) so the daemon can prompt "connect?" before
//! it opens the AAP session. Reads Apple manufacturer data from LE advertisements to detect the AirPods.
//!
//! Scanning is PASSIVE (listen only, no scan requests) and only runs while
//! `should_scan` is true — i.e. while disconnected — so the 2.4 GHz radio never
//! contends with the AirPods' A2DP audio while you're listening (which caused
//! static spikes, esp. on combo cards with poor coexistence).

use windows::Devices::Bluetooth::Advertisement::{
    BluetoothLEAdvertisementReceivedEventArgs, BluetoothLEAdvertisementWatcher,
    BluetoothLEScanningMode,
};
use windows::Foundation::TypedEventHandler;
use windows::Storage::Streams::DataReader;
use windows::Win32::System::Com::{CoInitializeEx, COINIT_MULTITHREADED};

const APPLE_COMPANY_ID: u16 = 0x004C;
/// Apple manufacturer-data message type for proximity pairing (AirPods / Beats).
const PROXIMITY_PAIRING: u8 = 0x07;

/// Watch for AirPods proximity advertisements, calling `on_nearby` on each — but
/// only scan while `should_scan()` is true. Blocks (keeps COM + the watcher
/// alive), so run it on its own thread.
pub fn watch_nearby(
    on_nearby: impl Fn() + Send + Sync + 'static,
    should_scan: impl Fn() -> bool + Send + 'static,
) {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
    }
    let watcher = match setup(on_nearby) {
        Ok(w) => w,
        Err(_) => return,
    };
    let mut scanning = false;
    loop {
        let want = should_scan();
        if want && !scanning {
            scanning = watcher.Start().is_ok();
        } else if !want && scanning {
            let _ = watcher.Stop();
            scanning = false;
        }
        std::thread::sleep(std::time::Duration::from_secs(2));
    }
}

fn setup(
    on_nearby: impl Fn() + Send + Sync + 'static,
) -> windows::core::Result<BluetoothLEAdvertisementWatcher> {
    let watcher = BluetoothLEAdvertisementWatcher::new()?;
    watcher.SetScanningMode(BluetoothLEScanningMode::Passive)?;
    let handler = TypedEventHandler::new(
        move |_s: &Option<BluetoothLEAdvertisementWatcher>,
              args: &Option<BluetoothLEAdvertisementReceivedEventArgs>| {
            if let Some(args) = args.as_ref() {
                if is_airpods(args) {
                    on_nearby();
                }
            }
            Ok(())
        },
    );
    watcher.Received(&handler)?;
    Ok(watcher)
}

/// True if this advertisement is an Apple proximity-pairing message (AirPods /
/// Beats). (Not IRK-resolved — any nearby AirPods matches; fine for one user.)
fn is_airpods(args: &BluetoothLEAdvertisementReceivedEventArgs) -> bool {
    let Ok(adv) = args.Advertisement() else {
        return false;
    };
    let Ok(mfg) = adv.ManufacturerData() else {
        return false;
    };
    let size = mfg.Size().unwrap_or(0);
    for i in 0..size {
        let Ok(md) = mfg.GetAt(i) else { continue };
        if md.CompanyId().unwrap_or(0) != APPLE_COMPANY_ID {
            continue;
        }
        if let Ok(buf) = md.Data() {
            let len = buf.Length().unwrap_or(0) as usize;
            if let Ok(reader) = DataReader::FromBuffer(&buf) {
                let mut bytes = vec![0u8; len];
                if reader.ReadBytes(&mut bytes).is_ok() && bytes.first() == Some(&PROXIMITY_PAIRING) {
                    return true;
                }
            }
        }
    }
    false
}
