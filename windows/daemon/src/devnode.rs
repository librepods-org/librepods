//! Unattended recovery of the AAP driver devnode.
//!
//! When the devnode is stranded in **Code 38** (`CM_PROB_DRIVER_FAILED_PRIOR_UNLOAD`)
//! every `Driver::open()` fails and the daemon can do nothing about it on its own:
//! restarting a devnode needs admin, and we run unelevated (the tray/app spawns us).
//!
//! Code 38 means the previous driver instance never unloaded — and it cannot
//! unload while any process still holds the exclusive device handle. That is
//! recoverable without a reboot: release the handle, restart the devnode. Only
//! the second half needs privileges.
//!
//! So we use the same escape hatch as the mic rename (see `rename.rs`):
//! `install.ps1` registers an on-demand scheduled task ("LibrePods Fix Driver",
//! RunLevel Highest) around `fix-driver.ps1`, and we fire it with `schtasks /run`
//! — elevated, no UAC prompt. The script re-checks the devnode itself and exits
//! without touching anything when it is healthy, so a spurious trigger is cheap
//! and safe; the decision to *act* lives where the privileges are.

use std::os::windows::process::CommandExt;
use std::process::Command;
use std::sync::Mutex;
use std::time::{Duration, Instant};

const TASK_NAME: &str = "LibrePods Fix Driver";
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// Never ask for a recovery more often than this. Restarting the devnode takes
/// seconds and tears the audio down with it — hammering it would be worse than
/// the wedge we are recovering from.
const COOLDOWN: Duration = Duration::from_secs(120);

static LAST_FIRED: Mutex<Option<Instant>> = Mutex::new(None);

/// Ask the elevated task to recover the devnode. Non-blocking. Returns false
/// when the cooldown is still running (nothing was fired), so the caller can
/// stay quiet in the log instead of repeating itself.
pub fn request_recovery() -> bool {
    {
        let mut last = LAST_FIRED.lock().unwrap_or_else(|e| e.into_inner());
        if last.is_some_and(|t| t.elapsed() < COOLDOWN) {
            return false;
        }
        *last = Some(Instant::now());
    }
    std::thread::spawn(|| {
        // If the task isn't registered (an install predating it), this just fails
        // quietly — the cockpit's `fixdriver` is still the manual path.
        let _ = Command::new("schtasks")
            .args(["/run", "/tn", TASK_NAME])
            .creation_flags(CREATE_NO_WINDOW)
            .status();
    });
    true
}
