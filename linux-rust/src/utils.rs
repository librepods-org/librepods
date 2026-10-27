use aes::Aes128;
use aes::cipher::Array;
use aes::cipher::{BlockCipherEncrypt, KeyInit};
use crate::bluetooth::aacp::ProximityKeyType;
use crate::devices::airpods::AirPodsInformation;
use crate::devices::enums::{DeviceData, DeviceInformation, DeviceType};
use iced::Theme;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

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

    app_settings_path_in(Path::new(&config_dir), Path::new(&data_dir))
}

/// `config_dir/librepods/app_settings.json`. When it doesn't exist yet, settings an older
/// version left behind are copied there; the old file stays, so older AppImages keep working.
pub fn app_settings_path_in(config_dir: &Path, data_dir: &Path) -> PathBuf {
    let path = config_dir.join("librepods").join("app_settings.json");
    if path.exists() {
        return path;
    }
    let legacy = [
        // 0.1.0 used the data directory when XDG_CONFIG_HOME was unset.
        data_dir.join("librepods").join("app_settings.json"),
        data_dir.join("app_settings.json"),
    ];
    if let Some(old) = legacy.iter().find(|candidate| candidate.exists()) {
        let copied = std::fs::read(old).and_then(|content| {
            if let Some(parent) = path.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let tmp = path.with_extension("json.tmp");
            std::fs::write(&tmp, content)?;
            std::fs::rename(&tmp, &path)
        });
        match copied {
            Ok(()) => log::info!(
                "Copied app settings from {} to {}",
                old.display(),
                path.display()
            ),
            Err(e) => log::warn!("Could not copy app settings from {}: {}", old.display(), e),
        }
    }
    path
}

/// Read-modify-write of one entry in devices.json. Re-reads the file every time so other
/// writers' changes are kept, backs up a corrupt file instead of failing, and replaces the
/// file atomically.
pub fn update_devices_file(
    path: &Path,
    mac: &str,
    update: impl FnOnce(&mut DeviceData),
) -> std::io::Result<()> {
    let mut devices: HashMap<String, DeviceData> = match std::fs::read_to_string(path) {
        Ok(content) => match serde_json::from_str(&content) {
            Ok(devices) => devices,
            Err(e) => {
                log::warn!(
                    "{} is not valid JSON ({}), backing it up and starting fresh",
                    path.display(),
                    e
                );
                std::fs::rename(path, path.with_extension("json.bak"))?;
                HashMap::new()
            }
        },
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => HashMap::new(),
        Err(e) => return Err(e),
    };

    let entry = devices.entry(mac.to_string()).or_insert_with(|| DeviceData {
        name: mac.to_string(),
        type_: DeviceType::AirPods,
        information: None,
    });
    update(entry);

    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let json = serde_json::to_string(&devices).map_err(std::io::Error::other)?;
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, json)?;
    std::fs::rename(&tmp, path)
}

/// Stores freshly received AirPods information, keeping LE keys learned earlier.
pub fn apply_information(data: &mut DeviceData, mut info: AirPodsInformation) {
    if let Some(DeviceInformation::AirPods(existing)) = &data.information {
        info.le_keys = existing.le_keys.clone();
    }
    if !info.name.is_empty() {
        data.name = info.name.clone();
    }
    data.information = Some(DeviceInformation::AirPods(info));
}

/// Stores one LE key, creating the AirPods information entry if it doesn't exist yet.
pub fn apply_le_key(data: &mut DeviceData, key_type: ProximityKeyType, key_hex: &str) {
    if !matches!(data.information, Some(DeviceInformation::AirPods(_))) {
        data.information = Some(DeviceInformation::AirPods(AirPodsInformation {
            name: data.name.clone(),
            ..Default::default()
        }));
    }
    if let Some(DeviceInformation::AirPods(info)) = data.information.as_mut() {
        match key_type {
            ProximityKeyType::Irk => info.le_keys.irk = key_hex.to_string(),
            ProximityKeyType::EncKey => info.le_keys.enc_key = key_hex.to_string(),
        }
    }
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bluetooth::aacp::ProximityKeyType;
    use crate::devices::airpods::AirPodsInformation;
    use crate::devices::enums::{DeviceData, DeviceInformation, DeviceType};
    use std::collections::HashMap;

    const MAC: &str = "AA:BB:CC:DD:EE:01";

    fn read_devices(path: &std::path::Path) -> HashMap<String, DeviceData> {
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
    }

    fn airpods_info(data: &DeviceData) -> &AirPodsInformation {
        match &data.information {
            Some(DeviceInformation::AirPods(info)) => info,
            other => panic!("expected AirPods information, got {:?}", other),
        }
    }

    #[test]
    fn creates_entry_when_file_missing() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("devices.json");
        update_devices_file(&path, MAC, |_| {}).unwrap();
        let devices = read_devices(&path);
        assert_eq!(devices[MAC].type_, DeviceType::AirPods);
    }

    #[test]
    fn preserves_other_devices() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("devices.json");
        let nothing = r#"{"AA:BB:CC:DD:EE:FF":{"name":"Ear (1)","type_":"Nothing","information":null}}"#;
        std::fs::write(&path, nothing).unwrap();
        update_devices_file(&path, MAC, |d| d.name = "Test AirPods Pro".to_string()).unwrap();
        let after: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        let before: serde_json::Value = serde_json::from_str(nothing).unwrap();
        assert_eq!(after["AA:BB:CC:DD:EE:FF"], before["AA:BB:CC:DD:EE:FF"]);
        assert_eq!(after[MAC]["name"], "Test AirPods Pro");
    }

    #[test]
    fn corrupt_file_is_backed_up() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("devices.json");
        std::fs::write(&path, "{not json").unwrap();
        update_devices_file(&path, MAC, |_| {}).unwrap();
        assert_eq!(
            std::fs::read_to_string(dir.path().join("devices.json.bak")).unwrap(),
            "{not json"
        );
        assert_eq!(read_devices(&path).len(), 1);
    }

    #[test]
    fn apply_le_key_creates_information_when_missing() {
        let mut data = DeviceData {
            name: MAC.to_string(),
            type_: DeviceType::AirPods,
            information: None,
        };
        apply_le_key(&mut data, ProximityKeyType::Irk, "00ff");
        assert_eq!(airpods_info(&data).le_keys.irk, "00ff");
    }

    #[test]
    fn app_settings_from_0_1_0_are_copied() {
        let config = tempfile::tempdir().unwrap();
        let data = tempfile::tempdir().unwrap();
        let old = data.path().join("librepods").join("app_settings.json");
        std::fs::create_dir_all(old.parent().unwrap()).unwrap();
        std::fs::write(&old, r#"{"theme":"TokyoNightStorm"}"#).unwrap();

        let path = app_settings_path_in(config.path(), data.path());
        assert_eq!(path, config.path().join("librepods").join("app_settings.json"));
        assert_eq!(
            std::fs::read_to_string(&path).unwrap(),
            r#"{"theme":"TokyoNightStorm"}"#
        );
        assert!(old.exists(), "the 0.1.0 file must stay for the old AppImage");

        // Settings already at the new path are never replaced by stale old ones.
        std::fs::write(&path, r#"{"theme":"Nord"}"#).unwrap();
        app_settings_path_in(config.path(), data.path());
        assert_eq!(std::fs::read_to_string(&path).unwrap(), r#"{"theme":"Nord"}"#);
    }

    #[test]
    fn apply_information_keeps_existing_keys() {
        let mut existing = AirPodsInformation::default();
        existing.le_keys.irk = "aa".to_string();
        existing.le_keys.enc_key = "bb".to_string();
        let mut data = DeviceData {
            name: MAC.to_string(),
            type_: DeviceType::AirPods,
            information: Some(DeviceInformation::AirPods(existing)),
        };
        let incoming = AirPodsInformation {
            name: "Test AirPods Pro".to_string(),
            model_number: "A2084".to_string(),
            ..Default::default()
        };
        apply_information(&mut data, incoming);
        let info = airpods_info(&data);
        assert_eq!(info.le_keys.irk, "aa");
        assert_eq!(info.le_keys.enc_key, "bb");
        assert_eq!(info.model_number, "A2084");
        assert_eq!(data.name, "Test AirPods Pro");
    }
}
