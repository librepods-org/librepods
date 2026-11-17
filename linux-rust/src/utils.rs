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

    let entry = format!(
        "[Desktop Entry]\n\
         Type=Application\n\
         Name=LibrePods\n\
         Comment=AirPods battery and controls in the system tray\n\
         Icon=me.kavishdevar.librepods\n\
         Exec={} --start-minimized\n\
         Terminal=false\n\
         X-GNOME-Autostart-enabled=true\n",
        exec
    );
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(&path, entry)
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
