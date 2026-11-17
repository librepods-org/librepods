//! Keeps only one LibrePods instance running per user session.
//!
//! When the app is already running (e.g. started minimized from autostart) and
//! the user launches it again from the app launcher, the new process asks the
//! running one to show its window and then exits, instead of starting a second
//! tray icon and a second set of Bluetooth connections.

use log::{debug, warn};
use std::io::Write;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::PathBuf;

const OPEN_WINDOW: &[u8] = b"open\n";

pub fn socket_path() -> PathBuf {
    let dir = std::env::var("XDG_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| std::env::temp_dir());
    // Each Flatpak instance gets a private runtime dir; app/<id> is shared between
    // instances of the same app.
    let dir = match std::env::var("FLATPAK_ID") {
        Ok(id) => dir.join("app").join(id),
        Err(_) => dir,
    };
    dir.join("librepods.sock")
}

pub enum Instance {
    /// This is the only instance; the listener must be kept and served.
    Primary(Option<UnixListener>),
    /// Another instance is running and has been asked to show its window.
    Secondary,
}

pub fn acquire() -> Instance {
    let path = socket_path();
    match UnixListener::bind(&path) {
        Ok(listener) => return Instance::Primary(Some(listener)),
        Err(e) if e.kind() == std::io::ErrorKind::AddrInUse => {}
        Err(e) => {
            // Can't create the socket at all; don't block startup over it.
            warn!("Single-instance socket unavailable ({}); continuing anyway.", e);
            return Instance::Primary(None);
        }
    }

    // The socket exists: either another instance is listening, or it is stale.
    if let Ok(mut stream) = UnixStream::connect(&path) {
        let _ = stream.write_all(OPEN_WINDOW);
        return Instance::Secondary;
    }
    debug!("Removing stale socket {}", path.display());
    let _ = std::fs::remove_file(&path);
    match UnixListener::bind(&path) {
        Ok(listener) => Instance::Primary(Some(listener)),
        Err(e) => {
            warn!("Failed to bind single-instance socket: {}", e);
            Instance::Secondary
        }
    }
}
