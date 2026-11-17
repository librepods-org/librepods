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
    acquire_at(&socket_path())
}

fn acquire_at(path: &std::path::Path) -> Instance {
    match UnixListener::bind(path) {
        Ok(listener) => return Instance::Primary(Some(listener)),
        Err(e) if e.kind() == std::io::ErrorKind::AddrInUse => {}
        Err(e) => {
            // Can't create the socket at all; don't block startup over it.
            warn!("Single-instance socket unavailable ({}); continuing anyway.", e);
            return Instance::Primary(None);
        }
    }

    // The socket exists: either another instance is listening, or it is stale.
    if let Ok(mut stream) = UnixStream::connect(path) {
        let _ = stream.write_all(OPEN_WINDOW);
        return Instance::Secondary;
    }
    debug!("Removing stale socket {}", path.display());
    let _ = std::fs::remove_file(path);
    match UnixListener::bind(path) {
        Ok(listener) => Instance::Primary(Some(listener)),
        Err(e) => {
            warn!("Failed to bind single-instance socket: {}", e);
            Instance::Secondary
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;

    fn temp_socket(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("librepods-test-{}-{}", name, std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("librepods.sock");
        let _ = std::fs::remove_file(&path);
        path
    }

    #[test]
    fn second_instance_asks_first_to_open_window() {
        let path = temp_socket("second");
        let Instance::Primary(Some(listener)) = acquire_at(&path) else {
            panic!("first launch should be primary");
        };
        assert!(matches!(acquire_at(&path), Instance::Secondary));
        let (mut conn, _) = listener.accept().unwrap();
        let mut buf = [0u8; 5];
        conn.read_exact(&mut buf).unwrap();
        assert_eq!(&buf, OPEN_WINDOW);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn stale_socket_is_replaced() {
        let path = temp_socket("stale");
        drop(UnixListener::bind(&path).unwrap()); // leaves the file, nobody listening
        assert!(path.exists());
        assert!(matches!(acquire_at(&path), Instance::Primary(Some(_))));
        let _ = std::fs::remove_file(&path);
    }
}
