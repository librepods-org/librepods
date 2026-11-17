//! Client side of the LibrePods IPC socket (see `src/ipc.rs` in the main app).

use futures::SinkExt;
use serde::Deserialize;
use std::io::Write;
use std::path::PathBuf;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
pub struct Battery {
    pub level: u8,
    pub charging: bool,
    #[serde(default = "yes")]
    pub connected: bool,
}

fn yes() -> bool {
    true
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
pub struct Status {
    pub connected: bool,
    /// The AirPods' name (absent from older LibrePods builds).
    #[serde(default)]
    pub name: Option<String>,
    pub headphone: Option<Battery>,
    pub left: Option<Battery>,
    pub right: Option<Battery>,
    pub case: Option<Battery>,
    pub listening_mode: Option<u8>,
    pub allow_off: bool,
    pub conversation_detect: Option<bool>,
}

impl Status {
    /// Lowest battery of the parts currently in use, for the panel label.
    pub fn min_level(&self) -> Option<u8> {
        if let Some(h) = self.headphone {
            return Some(h.level);
        }
        // Ignore a bud resting in the case.
        [self.left, self.right]
            .iter()
            .flatten()
            .filter(|b| b.connected)
            .map(|b| b.level)
            .min()
    }
}

/// Candidate socket paths: native install, then the Flatpak's shared app dir.
fn socket_paths() -> Vec<PathBuf> {
    let dir = std::env::var("XDG_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| std::env::temp_dir());
    vec![
        dir.join("librepods.sock"),
        dir.join("app/me.kavishdevar.librepods/librepods.sock"),
    ]
}

/// `None` means LibrePods isn't running.
#[derive(Debug, Clone)]
pub enum Event {
    Status(Option<Status>),
}

/// Connects to LibrePods and streams status updates, reconnecting as needed.
pub fn status_stream() -> impl futures::Stream<Item = Event> {
    cosmic::iced::stream::channel(16, async |mut out| {
        loop {
            for path in socket_paths() {
                let Ok(mut stream) = tokio::net::UnixStream::connect(&path).await else {
                    continue;
                };
                if stream.write_all(b"subscribe applet\n").await.is_err() {
                    continue;
                }
                let mut lines = BufReader::new(stream).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    if let Ok(status) = serde_json::from_str::<Status>(&line) {
                        let _ = out.send(Event::Status(Some(status))).await;
                    }
                }
                break;
            }
            let _ = out.send(Event::Status(None)).await;
            tokio::time::sleep(Duration::from_secs(3)).await;
        }
    })
}

/// Sends a one-shot command. Starting LibrePods if it isn't running is left to
/// the caller (see [`launch`]).
pub fn send(command: &str) -> bool {
    for path in socket_paths() {
        if let Ok(mut s) = std::os::unix::net::UnixStream::connect(&path) {
            return s.write_all(format!("{}\n", command).as_bytes()).is_ok();
        }
    }
    false
}

/// Opens the LibrePods window, starting the app if needed.
pub fn open_window() {
    if send("open") {
        return;
    }
    let spawn = |cmd: &str, args: &[&str]| {
        std::process::Command::new(cmd)
            .args(args)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            // Reap the child when it exits so it doesn't linger as a zombie.
            .map(|mut child| {
                std::thread::spawn(move || child.wait());
            })
            .is_ok()
    };
    // The panel's PATH may not include ~/.local/bin.
    let local_bin = std::env::var("HOME")
        .map(|h| format!("{}/.local/bin/librepods", h))
        .unwrap_or_default();
    if !spawn("librepods", &[]) && !spawn(&local_bin, &[]) {
        let _ = spawn("flatpak", &["run", "me.kavishdevar.librepods"]);
    }
}
