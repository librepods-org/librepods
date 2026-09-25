//! System output volume via Core Audio (WASAPI IAudioEndpointVolume). The daemon
//! owns volume so it's the single arbiter — it reports it in the Snapshot, serves
//! the tray's volume commands, and ducks it for Conversational Awareness without
//! an IPC round-trip. Controls the default render device (the AirPods when they
//! are the active output). This is Windows audio, independent of the AAP driver.

use windows::core::Interface;
use windows::Win32::Media::Audio::Endpoints::IAudioEndpointVolume;
use windows::Win32::Media::Audio::{
    AudioSessionStateActive, IAudioSessionControl2, IAudioSessionManager2, IMMDeviceEnumerator,
    MMDeviceEnumerator, eConsole, eRender,
};
use windows::Win32::System::Com::{
    CLSCTX_ALL, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx,
};

/// Join the process MTA for the calling thread. Idempotent-safe (a second call
/// returns S_FALSE). Every daemon thread that touches volume must call this.
pub fn init() {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
    }
}

fn endpoint() -> windows::core::Result<IAudioEndpointVolume> {
    unsafe {
        let enumerator: IMMDeviceEnumerator =
            CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
        let device = enumerator.GetDefaultAudioEndpoint(eRender, eConsole)?;
        device.Activate(CLSCTX_ALL, None)
    }
}

/// Current master volume of the default output (0..=100), or None if it fails.
pub fn get() -> Option<u8> {
    let vol = endpoint().ok()?;
    let level = unsafe { vol.GetMasterVolumeLevelScalar().ok()? };
    Some((level * 100.0).round().clamp(0.0, 100.0) as u8)
}

pub fn set(percent: u8) {
    if let Ok(vol) = endpoint() {
        let level = percent.min(100) as f32 / 100.0;
        unsafe {
            let _ = vol.SetMasterVolumeLevelScalar(level, std::ptr::null());
        }
    }
}

pub fn step(delta: i32) {
    if let Some(cur) = get() {
        set((cur as i32 + delta).clamp(0, 100) as u8);
    }
}

pub fn is_muted() -> bool {
    endpoint()
        .and_then(|v| unsafe { v.GetMute() })
        .map(|b| b.as_bool())
        .unwrap_or(false)
}

pub fn toggle_mute() {
    if let Ok(vol) = endpoint() {
        let new = !unsafe { vol.GetMute() }.map(|b| b.as_bool()).unwrap_or(false);
        unsafe {
            let _ = vol.SetMute(new, std::ptr::null());
        }
    }
}

/// True if any app is actively rendering audio on the default output — the
/// broad "is this host playing something" test.
///
/// `media::is_playing()` only sees apps that register a System Media Transport
/// Controls session: browsers, Spotify, media players. Teams, Zoom, Discord,
/// games and most VoIP clients never do, so they read as silence there. This
/// asks Core Audio directly instead: enumerate the render sessions on the
/// endpoint and look for one in the Active state.
///
/// **The system-sounds session is skipped on purpose.** That is Apple's tier 3
/// ("Background Audio / System Sounds - ignored unless manually triggered"), and
/// Core Audio hands us exactly that distinction via
/// `IAudioSessionControl2::IsSystemSoundsSession`. A Windows notification chime
/// must not count as "this host owns the audio", or we would fight the phone
/// over a ding of our own.
///
/// Note this deliberately reads the *default* endpoint, not the AirPods
/// specifically: once the buds drop, Windows moves those sessions to the
/// fallback device, and "something of ours is still playing" is what we need to
/// know. Returns false on any COM failure — never claim ownership on a guess.
pub fn any_render_active() -> bool {
    unsafe {
        let Ok(enumerator) = CoCreateInstance::<_, IMMDeviceEnumerator>(
            &MMDeviceEnumerator,
            None,
            CLSCTX_ALL,
        ) else {
            return false;
        };
        let Ok(device) = enumerator.GetDefaultAudioEndpoint(eRender, eConsole) else {
            return false;
        };
        let Ok(manager) = device.Activate::<IAudioSessionManager2>(CLSCTX_ALL, None) else {
            return false;
        };
        let Ok(sessions) = manager.GetSessionEnumerator() else {
            return false;
        };
        let count = sessions.GetCount().unwrap_or(0);
        for i in 0..count {
            let Ok(session) = sessions.GetSession(i) else {
                continue;
            };
            if session.GetState() != Ok(AudioSessionStateActive) {
                continue;
            }
            // Skip the system-sounds session (tier 3).
            if let Ok(s2) = session.cast::<IAudioSessionControl2>() {
                if s2.IsSystemSoundsSession().is_ok() {
                    continue;
                }
            }
            return true;
        }
        false
    }
}

/// Conversational Awareness volume ducking. The AirPods only *signal* the state
/// (status byte of the 0x4B event) — the host lowers/restores the media volume.
/// Mirrors the app's MediaController::handle_conversational_awareness.
#[derive(Default)]
pub struct ConvDuck {
    original: Option<u8>,
    started: bool,
}

impl ConvDuck {
    /// Apply a Conversational Awareness status, Apple-style (aggressive): the
    /// media drops to a low background level so you focus on the conversation
    /// (the AirPods add the transparency/voice boost themselves). Mirrors iOS /
    /// LibrePods PR #655: 1 = start (→25%), 2 = reduce (→15%), 3 = partial
    /// (→min(original,25)), 4/6/7 = end (→restore original).
    pub fn on_status(&mut self, status: u8) {
        match status {
            1 => {
                let cur = get().unwrap_or(0);
                if !self.started {
                    self.original = Some(cur);
                    self.started = true;
                }
                if self.original.unwrap_or(cur) > 25 {
                    set(25);
                }
            }
            2 => {
                if let Some(orig) = self.original {
                    if orig > 15 {
                        set(15);
                    }
                }
            }
            3 => {
                if self.started {
                    if let Some(orig) = self.original {
                        set(orig.min(25));
                    }
                }
            }
            4 | 6 | 7 => self.restore(),
            _ => {}
        }
    }

    /// Restore the pre-duck volume immediately (e.g. the user turned CA off while
    /// it was mid-duck — no end event will come, so we'd be stuck low otherwise).
    pub fn restore(&mut self) {
        if self.started {
            if let Some(orig) = self.original {
                set(orig);
            }
            self.started = false;
            self.original = None;
        }
    }
}
