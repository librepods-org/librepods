//! Auto-rename the virtual microphone to the connected device's name (e.g.
//! "AirPods Pro de Pedro", "Beats Fit Pro"), so calls/recordings show that.
//!
//! Renaming an audio endpoint writes to HKLM and needs admin, but the tray runs
//! unelevated. So `install.ps1` registers an elevated, on-demand scheduled task
//! ("LibrePods Rename Mic") that runs `rename-mic.ps1` with highest privileges. We
//! drop the desired name in a file and trigger the task via `schtasks /run`,
//! which runs it elevated WITHOUT a UAC prompt. `rename-mic.ps1` is idempotent —
//! it does nothing (no audio-service restart) when the mic is already named
//! correctly, so firing this on every launch is cheap.

use std::os::windows::process::CommandExt;
use std::process::Command;

const TASK_NAME: &str = "LibrePods Rename Mic";
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// Best-effort, non-blocking: publish `dev_name` and kick the elevated rename
/// task. Never blocks the tray or surfaces errors — if the task isn't installed,
/// an elevated `rename-mic.ps1 "<name>"` still works.
pub fn apply(dev_name: &str) {
    let name = dev_name.trim().to_string();
    if name.is_empty() {
        return;
    }
    std::thread::spawn(move || {
        let la = match std::env::var("LOCALAPPDATA") {
            Ok(la) => la,
            Err(_) => return,
        };
        let dir = format!("{la}\\LibrePods");
        let _ = std::fs::create_dir_all(&dir);
        // Publish the name for the elevated task to read.
        if std::fs::write(format!("{dir}\\micname.txt"), &name).is_err() {
            return;
        }
        // Fire the on-demand elevated task (registered by install.ps1; no UAC).
        let _ = Command::new("schtasks")
            .args(["/run", "/tn", TASK_NAME])
            .creation_flags(CREATE_NO_WINDOW)
            .status();
    });
}
