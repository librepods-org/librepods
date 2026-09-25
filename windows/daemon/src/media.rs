//! Media auto-pause/resume via the Windows System Media Transport Controls
//! (SMTC) — the same session API every media app registers with. This is how we
//! pause playback when the AirPods leave your ears (the Apple/MagicPods
//! behaviour) WITHOUT touching the A2DP profile: Windows manages A2DP itself, so
//! unlike the Linux build there is no profile to toggle. We simply pause/resume
//! whatever app currently owns the system media session (Spotify, a browser, …).

use windows::Media::Control::{
    GlobalSystemMediaTransportControlsSession as Session,
    GlobalSystemMediaTransportControlsSessionManager as SessionManager,
    GlobalSystemMediaTransportControlsSessionPlaybackStatus as PlaybackStatus,
};
use windows::Win32::System::Com::{COINIT_MULTITHREADED, CoInitializeEx};

/// Initialize COM (MTA) for the calling thread — required before any SMTC call.
/// Call once, from the thread that will drive the media calls.
pub fn init() {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
    }
}

fn current_session() -> windows::core::Result<Session> {
    // RequestAsync + GetCurrentSession are both quick; block on the async op.
    let manager = SessionManager::RequestAsync()?.get()?;
    manager.GetCurrentSession()
}

/// True if the system's current media session is actively playing. False when
/// there is no session or the call fails.
pub fn is_playing() -> bool {
    (|| -> windows::core::Result<bool> {
        let status = current_session()?.GetPlaybackInfo()?.PlaybackStatus()?;
        Ok(status == PlaybackStatus::Playing)
    })()
    .unwrap_or(false)
}

/// Pause the current media session. Returns true if the control was accepted.
pub fn pause() -> bool {
    (|| -> windows::core::Result<bool> { Ok(current_session()?.TryPauseAsync()?.get()?) })()
        .unwrap_or(false)
}

/// Resume the current media session. Returns true if the control was accepted.
pub fn play() -> bool {
    (|| -> windows::core::Result<bool> { Ok(current_session()?.TryPlayAsync()?.get()?) })()
        .unwrap_or(false)
}
