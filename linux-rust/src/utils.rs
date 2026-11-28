use aes::Aes128;
use aes::cipher::Array;
use aes::cipher::{BlockCipherEncrypt, KeyInit};
use iced::Theme;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

pub fn get_devices_path() -> PathBuf {
    let data_dir = std::env::var("XDG_DATA_HOME")
        .unwrap_or_else(|_| format!("{}/.local/share", std::env::var("HOME").unwrap_or_default()));
    PathBuf::from(data_dir)
        .join("librepods")
        .join("devices.json")
}

pub fn get_preferences_path() -> PathBuf {
    let config_dir = std::env::var("XDG_CONFIG_HOME")
        .unwrap_or_else(|_| format!("{}/.config", std::env::var("HOME").unwrap_or_default()));
    PathBuf::from(config_dir)
        .join("librepods")
        .join("preferences.json")
}

pub fn get_app_settings_path() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_default();

    let config_dir = std::env::var("XDG_CONFIG_HOME")
        .unwrap_or_else(|_| format!("{}/.config", home));

    let data_dir = std::env::var("XDG_DATA_HOME")
        .unwrap_or_else(|_| format!("{}/.local/share", home));

    let new_path = PathBuf::from(&config_dir)
        .join("librepods")
        .join("app_settings.json");

    let old_path = PathBuf::from(&data_dir)
        .join("app_settings.json");

    // migrate if needed
    if old_path.exists() && !new_path.exists() {
        if let Some(parent) = new_path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }

        if std::fs::copy(&old_path, &new_path).is_ok() {
            let _ = std::fs::remove_file(&old_path);
        }
    }

    new_path
}

fn e(key: &[u8; 16], data: &[u8; 16]) -> [u8; 16] {
    let mut swapped_key = *key;
    swapped_key.reverse();
    let mut swapped_data = *data;
    swapped_data.reverse();
    let cipher = Aes128::new(&Array::from(swapped_key));
    let mut block = Array::from(swapped_data);
    cipher.encrypt_block(&mut block);
    let mut result: [u8; 16] = block.into();
    result.reverse();
    result
}

pub fn ah(k: &[u8; 16], r: &[u8; 3]) -> [u8; 3] {
    let mut r_padded = [0u8; 16];
    r_padded[..3].copy_from_slice(r);
    let encrypted = e(k, &r_padded);
    let mut hash = [0u8; 3];
    hash.copy_from_slice(&encrypted[..3]);
    hash
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub enum MyTheme {
    /// Follow the desktop's light/dark preference (e.g. COSMIC, GNOME, KDE).
    System,
    Light,
    Dark,
    Dracula,
    Nord,
    SolarizedLight,
    SolarizedDark,
    GruvboxLight,
    GruvboxDark,
    CatppuccinLatte,
    CatppuccinFrappe,
    CatppuccinMacchiato,
    CatppuccinMocha,
    TokyoNight,
    TokyoNightStorm,
    TokyoNightLight,
    KanagawaWave,
    KanagawaDragon,
    KanagawaLotus,
    Moonfly,
    Nightfly,
    Oxocarbon,
    Ferra,
}

impl std::fmt::Display for MyTheme {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::System => "System",
            Self::Light => "Light",
            Self::Dark => "Dark",
            Self::Dracula => "Dracula",
            Self::Nord => "Nord",
            Self::SolarizedLight => "Solarized Light",
            Self::SolarizedDark => "Solarized Dark",
            Self::GruvboxLight => "Gruvbox Light",
            Self::GruvboxDark => "Gruvbox Dark",
            Self::CatppuccinLatte => "Catppuccin Latte",
            Self::CatppuccinFrappe => "Catppuccin Frappé",
            Self::CatppuccinMacchiato => "Catppuccin Macchiato",
            Self::CatppuccinMocha => "Catppuccin Mocha",
            Self::TokyoNight => "Tokyo Night",
            Self::TokyoNightStorm => "Tokyo Night Storm",
            Self::TokyoNightLight => "Tokyo Night Light",
            Self::KanagawaWave => "Kanagawa Wave",
            Self::KanagawaDragon => "Kanagawa Dragon",
            Self::KanagawaLotus => "Kanagawa Lotus",
            Self::Moonfly => "Moonfly",
            Self::Nightfly => "Nightfly",
            Self::Oxocarbon => "Oxocarbon",
            Self::Ferra => "Ferra",
        })
    }
}

impl From<MyTheme> for Theme {
    fn from(my_theme: MyTheme) -> Self {
        match my_theme {
            // Only used as a fallback; `MyTheme::to_theme` returns `None` for System.
            MyTheme::System => Theme::Dark,
            MyTheme::Light => Theme::Light,
            MyTheme::Dark => Theme::Dark,
            MyTheme::Dracula => Theme::Dracula,
            MyTheme::Nord => Theme::Nord,
            MyTheme::SolarizedLight => Theme::SolarizedLight,
            MyTheme::SolarizedDark => Theme::SolarizedDark,
            MyTheme::GruvboxLight => Theme::GruvboxLight,
            MyTheme::GruvboxDark => Theme::GruvboxDark,
            MyTheme::CatppuccinLatte => Theme::CatppuccinLatte,
            MyTheme::CatppuccinFrappe => Theme::CatppuccinFrappe,
            MyTheme::CatppuccinMacchiato => Theme::CatppuccinMacchiato,
            MyTheme::CatppuccinMocha => Theme::CatppuccinMocha,
            MyTheme::TokyoNight => Theme::TokyoNight,
            MyTheme::TokyoNightStorm => Theme::TokyoNightStorm,
            MyTheme::TokyoNightLight => Theme::TokyoNightLight,
            MyTheme::KanagawaWave => Theme::KanagawaWave,
            MyTheme::KanagawaDragon => Theme::KanagawaDragon,
            MyTheme::KanagawaLotus => Theme::KanagawaLotus,
            MyTheme::Moonfly => Theme::Moonfly,
            MyTheme::Nightfly => Theme::Nightfly,
            MyTheme::Oxocarbon => Theme::Oxocarbon,
            MyTheme::Ferra => Theme::Ferra,
        }
    }
}

impl MyTheme {
    /// Returns `None` for [`MyTheme::System`], which tells iced to follow the
    /// system color scheme (read from the XDG desktop portal on Linux).
    pub fn to_theme(self) -> Option<Theme> {
        match self {
            MyTheme::System => None,
            other => Some(other.into()),
        }
    }
}

/// Path of the XDG autostart entry, honoured by COSMIC, GNOME, KDE and most other
/// desktops.
pub fn get_autostart_path() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_default();
    // Inside Flatpak, XDG_CONFIG_HOME points into the sandbox (~/.var/app/...), which
    // the host session never reads. The manifest grants `xdg-config/autostart`, which
    // is mounted at the host path, so write there instead. (The Background portal
    // would be the alternative, but COSMIC's portal backend doesn't implement it.)
    let config_dir = if std::env::var("FLATPAK_ID").is_ok() {
        format!("{}/.config", home)
    } else {
        std::env::var("XDG_CONFIG_HOME").unwrap_or_else(|_| format!("{}/.config", home))
    };
    PathBuf::from(config_dir)
        .join("autostart")
        .join("me.kavishdevar.librepods.desktop")
}

pub fn is_autostart_enabled() -> bool {
    get_autostart_path().exists()
}

/// Creates or removes the XDG autostart entry. The entry starts LibrePods
/// minimized to the tray.
pub fn set_autostart(enabled: bool) -> std::io::Result<()> {
    let path = get_autostart_path();
    if !enabled {
        return match std::fs::remove_file(&path) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        };
    }

    let exec = if let Ok(id) = std::env::var("FLATPAK_ID") {
        format!("flatpak run {}", id)
    } else if let Ok(appimage) = std::env::var("APPIMAGE") {
        desktop_exec_quote(&appimage)
    } else {
        let exe = std::env::current_exe()?;
        desktop_exec_quote(&exe.to_string_lossy())
    };

    let entry = autostart_entry(&exec);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(&path, entry)
}

/// The XDG autostart entry, starting LibrePods minimized to the tray.
fn autostart_entry(exec: &str) -> String {
    format!(
        "[Desktop Entry]\n\
         Type=Application\n\
         Name=LibrePods\n\
         Comment=AirPods battery and controls in the system tray\n\
         Icon=me.kavishdevar.librepods\n\
         Exec={} --start-minimized\n\
         Terminal=false\n\
         X-GNOME-Autostart-enabled=true\n",
        exec
    )
}

/// Quotes a path for the `Exec=` key of a desktop entry if needed.
fn desktop_exec_quote(s: &str) -> String {
    if s.chars().any(|c| c.is_whitespace() || "\"'\\$`".contains(c)) {
        let escaped = s
            .replace('\\', "\\\\")
            .replace('"', "\\\"")
            .replace('$', "\\$")
            .replace('`', "\\`");
        format!("\"{}\"", escaped)
    } else {
        s.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::ah;

    /// Bluetooth Core Spec, Vol 3, Part H, D.7 (ah random address hash):
    /// IRK ec0234a357c8ad05341010a60a397d9b, prand 708194 -> hash 0dfbaa.
    #[test]
    fn autostart_entry_starts_minimized() {
        let entry = super::autostart_entry("\"/opt/My Apps/librepods\"");
        assert!(entry.starts_with("[Desktop Entry]\n"));
        assert!(entry.contains("\nExec=\"/opt/My Apps/librepods\" --start-minimized\n"));
        assert!(entry.contains("\nIcon=me.kavishdevar.librepods\n"));
        assert!(entry.contains("\nType=Application\n"));
    }

    #[test]
    fn call_audio_maps_to_profiles_and_serializes() {
        use super::CallAudio::*;
        assert_eq!(Auto.profile(), None);
        assert_eq!(Reliable.profile(), Some("headset-head-unit-msbc"));
        assert_eq!(Quality.profile(), Some("headset-head-unit"));
        assert_eq!(Compatible.profile(), Some("headset-head-unit-cvsd"));
        assert_eq!(serde_json::to_string(&Reliable).unwrap(), "\"reliable\"");
        assert_eq!(serde_json::from_str::<super::CallAudio>("\"compatible\"").unwrap(), Compatible);
        assert_eq!(super::CallAudio::default(), Auto);
    }

    #[test]
    fn formats_ages() {
        use super::format_age;
        assert_eq!(format_age(0), "just now");
        assert_eq!(format_age(59), "just now");
        assert_eq!(format_age(60), "1 min ago");
        assert_eq!(format_age(3599), "59 min ago");
        assert_eq!(format_age(3600), "1 h ago");
        assert_eq!(format_age(86_399), "23 h ago");
        assert_eq!(format_age(86_400), "1 day ago");
        assert_eq!(format_age(3 * 86_400), "3 days ago");
    }

    #[test]
    fn saved_case_round_trips_as_json() {
        let mut cases = std::collections::HashMap::new();
        cases.insert("AA:BB:CC:DD:EE:FF".to_string(), super::SavedCase { level: 24, charging: true, at: 1_700_000_000 });
        let json = serde_json::to_string(&cases).unwrap();
        let back: std::collections::HashMap<String, super::SavedCase> = serde_json::from_str(&json).unwrap();
        assert_eq!(back, cases);
    }

    #[test]
    fn quotes_desktop_exec_paths() {
        use super::desktop_exec_quote as q;
        assert_eq!(q("/usr/bin/librepods"), "/usr/bin/librepods");
        assert_eq!(q("/home/me/My Apps/LibrePods.AppImage"), "\"/home/me/My Apps/LibrePods.AppImage\"");
        assert_eq!(q("/a/$HOME/b"), "\"/a/\\$HOME/b\"");
    }

    #[test]
    fn ah_matches_bluetooth_spec_vector() {
        let mut irk: [u8; 16] = hex::decode("ec0234a357c8ad05341010a60a397d9b").unwrap().try_into().unwrap();
        let mut r = [0x70, 0x81, 0x94];
        let mut expected = [0x0d, 0xfb, 0xaa];
        // `ah` works on little-endian (over-the-air) byte order.
        irk.reverse();
        r.reverse();
        expected.reverse();
        assert_eq!(ah(&irk, &r), expected);
    }
}

/// Runs a future on a shared background tokio runtime. Used for fire-and-forget
/// work started from the UI thread (e.g. sending a command to the AirPods),
/// instead of creating a new runtime and thread for every click.
pub fn spawn_bg<F>(fut: F)
where
    F: std::future::Future<Output = ()> + Send + 'static,
{
    static RUNTIME: std::sync::OnceLock<tokio::runtime::Runtime> = std::sync::OnceLock::new();
    RUNTIME
        .get_or_init(|| {
            tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .thread_name("librepods-bg")
                .enable_all()
                .build()
                .expect("failed to start background runtime")
        })
        .spawn(fut);
}

/// Loads the saved devices (devices.json). Missing file means no devices yet.
pub fn load_devices() -> std::collections::HashMap<String, crate::devices::enums::DeviceData> {
    match std::fs::read_to_string(get_devices_path()) {
        Ok(json) => serde_json::from_str(&json).unwrap_or_else(|e| {
            log::error!("Failed to parse devices file: {}", e);
            Default::default()
        }),
        Err(e) => {
            if e.kind() != std::io::ErrorKind::NotFound {
                log::error!("Failed to read devices file: {}", e);
            }
            Default::default()
        }
    }
}

/// Last case battery seen per device, kept across restarts: the case only
/// reports while a bud is in it or its lid is open, so most of the time this is
/// all we have (your iPhone shows a remembered value too).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct SavedCase {
    pub level: u8,
    pub charging: bool,
    /// Unix time (seconds) when it was seen.
    pub at: u64,
}

fn last_case_path() -> PathBuf {
    let state_dir = std::env::var("XDG_STATE_HOME").unwrap_or_else(|_| {
        format!("{}/.local/state", std::env::var("HOME").unwrap_or_default())
    });
    PathBuf::from(state_dir).join("librepods").join("last_case.json")
}

pub fn load_last_cases() -> std::collections::HashMap<String, SavedCase> {
    std::fs::read_to_string(last_case_path())
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

pub fn save_last_cases(cases: &std::collections::HashMap<String, SavedCase>) {
    let path = last_case_path();
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    if let Ok(json) = serde_json::to_string(cases)
        && let Err(e) = std::fs::write(&path, json)
    {
        log::warn!("Could not save last case battery: {}", e);
    }
}

pub fn now_unix() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// "just now", "5 min ago", "3 h ago", "2 days ago".
pub fn format_age(seconds: u64) -> String {
    match seconds {
        0..=59 => "just now".to_string(),
        60..=3599 => format!("{} min ago", seconds / 60),
        3600..=86_399 => format!("{} h ago", seconds / 3600),
        86_400..=172_799 => "1 day ago".to_string(),
        _ => format!("{} days ago", seconds / 86_400),
    }
}

/// Which Bluetooth call (headset) profile to use while an app uses the
/// AirPods' microphone. Works on any desktop: it switches the PipeWire /
/// PulseAudio card profile.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CallAudio {
    /// Keep whatever PipeWire picks (its best codec, e.g. LC3).
    #[default]
    Auto,
    /// mSBC: wideband and mature; the safest choice if calls drop out.
    Reliable,
    /// The best codec PipeWire offers (LC3 on recent PipeWire).
    Quality,
    /// CVSD: narrowband, works with everything.
    Compatible,
}

impl CallAudio {
    /// The PipeWire card profile to switch to, or `None` to leave it alone.
    pub fn profile(self) -> Option<&'static str> {
        match self {
            CallAudio::Auto => None,
            CallAudio::Reliable => Some("headset-head-unit-msbc"),
            CallAudio::Quality => Some("headset-head-unit"),
            CallAudio::Compatible => Some("headset-head-unit-cvsd"),
        }
    }
}

/// The saved "Call audio" preference (app settings key `call_audio`).
pub fn load_call_audio() -> CallAudio {
    std::fs::read_to_string(get_app_settings_path())
        .ok()
        .and_then(|s| serde_json::from_str::<serde_json::Value>(&s).ok())
        .and_then(|v| v.get("call_audio").cloned())
        .and_then(|v| serde_json::from_value(v).ok())
        .unwrap_or_default()
}
