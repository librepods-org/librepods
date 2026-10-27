//! Per-device behaviour preferences (`preferences.json`, keyed by MAC) and app-wide settings
//! (`app_settings.json`). Both files are re-read on every access, tolerate hand edits and files
//! from older or newer versions, keep keys they don't know, and are replaced atomically.

use crate::utils::{MyTheme, get_preferences_path};
use log::{debug, info, warn};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

/// Serializes read-modify-write cycles of each file within this process.
static PREFS_WRITE: Mutex<()> = Mutex::new(());
static APP_SETTINGS_WRITE: Mutex<()> = Mutex::new(());

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum Bud {
    Left,
    Right,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum PressType {
    Single,
    Double,
    Triple,
    Long,
}

/// What a stem press does. `Native` leaves the press to the AirPods themselves.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(tag = "action", content = "command", rename_all = "camelCase")]
pub enum StemAction {
    #[default]
    Native,
    PlayPause,
    NextTrack,
    PreviousTrack,
    CycleListeningMode,
    Command(String),
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct StemActions {
    pub left_single: StemAction,
    pub right_single: StemAction,
    pub left_double: StemAction,
    pub right_double: StemAction,
    pub left_triple: StemAction,
    pub right_triple: StemAction,
    pub left_long: StemAction,
    pub right_long: StemAction,
}

impl StemActions {
    pub fn get(&self, bud: Bud, press: PressType) -> &StemAction {
        match (bud, press) {
            (Bud::Left, PressType::Single) => &self.left_single,
            (Bud::Right, PressType::Single) => &self.right_single,
            (Bud::Left, PressType::Double) => &self.left_double,
            (Bud::Right, PressType::Double) => &self.right_double,
            (Bud::Left, PressType::Triple) => &self.left_triple,
            (Bud::Right, PressType::Triple) => &self.right_triple,
            (Bud::Left, PressType::Long) => &self.left_long,
            (Bud::Right, PressType::Long) => &self.right_long,
        }
    }

    pub fn set(&mut self, bud: Bud, press: PressType, action: StemAction) {
        let slot = match (bud, press) {
            (Bud::Left, PressType::Single) => &mut self.left_single,
            (Bud::Right, PressType::Single) => &mut self.right_single,
            (Bud::Left, PressType::Double) => &mut self.left_double,
            (Bud::Right, PressType::Double) => &mut self.right_double,
            (Bud::Left, PressType::Triple) => &mut self.left_triple,
            (Bud::Right, PressType::Triple) => &mut self.right_triple,
            (Bud::Left, PressType::Long) => &mut self.left_long,
            (Bud::Right, PressType::Long) => &mut self.right_long,
        };
        *slot = action;
    }

    pub fn is_all_native(&self) -> bool {
        [
            &self.left_single,
            &self.right_single,
            &self.left_double,
            &self.right_double,
            &self.left_triple,
            &self.right_triple,
            &self.left_long,
            &self.right_long,
        ]
        .iter()
        .all(|action| **action == StemAction::Native)
    }
}

/// One AirPods pair's behaviour settings. Defaults reproduce the app's behaviour before Stage 3.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct DevicePrefs {
    pub auto_connect: bool,
    pub ear_detection: bool,
    pub disconnect_when_not_wearing: bool,
    pub conversation_volume: u8,
    pub conversation_reduced_volume: u8,
    pub conversation_relative: bool,
    pub conversation_pause_music: bool,
    pub takeover_on_media_start: bool,
    pub takeover_from_music: bool,
    pub takeover_from_call: bool,
    /// The press-and-hold listening-mode cycle (0x1A bitmask) the user chose here, if any.
    pub listening_mode_cycle: Option<u8>,
    pub stem_actions: StemActions,
    /// Keys written by other versions of the app, kept as they are.
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl Default for DevicePrefs {
    fn default() -> Self {
        DevicePrefs {
            auto_connect: false,
            ear_detection: true,
            disconnect_when_not_wearing: true,
            conversation_volume: 25,
            conversation_reduced_volume: 15,
            conversation_relative: false,
            conversation_pause_music: false,
            takeover_on_media_start: true,
            takeover_from_music: true,
            takeover_from_call: false,
            listening_mode_cycle: None,
            stem_actions: StemActions::default(),
            extra: Map::new(),
        }
    }
}

/// The keys of `object` that `T` accepts one at a time; a bad value drops only its own key.
fn accepted_keys<T: DeserializeOwned>(object: &Map<String, Value>) -> Map<String, Value> {
    object
        .iter()
        .filter(|(key, value)| {
            let single = Map::from_iter([((*key).clone(), (*value).clone())]);
            serde_json::from_value::<T>(Value::Object(single)).is_ok()
        })
        .map(|(key, value)| (key.clone(), value.clone()))
        .collect()
}

/// One device's preferences. A value this version can't read (a typo, a number out of range,
/// a newer build's stem action) falls back to its default on its own; everything else is kept.
fn device_from_value(mac: &str, value: Value) -> DevicePrefs {
    let Value::Object(mut object) = value else {
        debug!("Preferences for {} are not an object; using defaults", mac);
        return DevicePrefs::default();
    };
    if let Some(Value::Object(slots)) = object.get("stemActions") {
        let accepted = accepted_keys::<StemActions>(slots);
        object.insert("stemActions".to_string(), Value::Object(accepted));
    }
    let accepted = accepted_keys::<DevicePrefs>(&object);
    if accepted.len() != object.len() {
        let ignored: Vec<&String> = object.keys().filter(|key| !accepted.contains_key(*key)).collect();
        debug!("Preferences for {}: ignoring unreadable {:?}", mac, ignored);
    }
    serde_json::from_value(Value::Object(accepted)).unwrap_or_default()
}

/// Writes only what a change touched: values the change left alone stay exactly as they were in
/// the file, even ones this version can't read, and untouched defaults are not added.
fn merge_changes(raw: Value, before: &Value, after: &Value) -> Value {
    if before == after {
        return raw;
    }
    match (raw, before, after) {
        (Value::Object(mut raw), Value::Object(before), Value::Object(after)) => {
            for (key, after_value) in after {
                let before_value = before.get(key).unwrap_or(&Value::Null);
                if before_value == after_value {
                    continue;
                }
                let merged = match raw.remove(key) {
                    Some(raw_value) => merge_changes(raw_value, before_value, after_value),
                    None => after_value.clone(),
                };
                raw.insert(key.clone(), merged);
            }
            Value::Object(raw)
        }
        (_, _, after) => after.clone(),
    }
}

/// Every device entry in a preferences file. A bad entry gets the defaults; the others are kept.
pub fn parse_prefs(json: &str) -> HashMap<String, DevicePrefs> {
    match serde_json::from_str::<Value>(json) {
        Ok(Value::Object(entries)) => entries
            .into_iter()
            .map(|(mac, value)| {
                let prefs = device_from_value(&mac, value);
                (mac, prefs)
            })
            .collect(),
        Ok(_) => HashMap::new(),
        Err(e) => {
            log::debug!("Preferences file is not valid JSON: {}", e);
            HashMap::new()
        }
    }
}

/// Writes `value` next to `path` and renames it into place, so readers never see half a file.
fn write_json_atomically(path: &Path, value: &Value) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let json = serde_json::to_string_pretty(value).map_err(std::io::Error::other)?;
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, json)?;
    std::fs::rename(&tmp, path)
}

#[derive(Debug, Clone)]
pub struct PrefsStore {
    path: PathBuf,
}

impl PrefsStore {
    pub fn new(path: PathBuf) -> Self {
        PrefsStore { path }
    }

    pub fn at_default_path() -> Self {
        Self::new(get_preferences_path())
    }

    /// This device's preferences, read fresh from disk; defaults when it has none.
    pub fn device(&self, mac: &str) -> DevicePrefs {
        std::fs::read_to_string(&self.path)
            .ok()
            .and_then(|content| serde_json::from_str::<Value>(&content).ok())
            .and_then(|mut file| file.get_mut(mac).map(Value::take))
            .map(|value| device_from_value(mac, value))
            .unwrap_or_default()
    }

    /// Changes one device's entry; every other entry is written back exactly as it was read.
    pub fn update(&self, mac: &str, f: impl FnOnce(&mut DevicePrefs)) -> std::io::Result<()> {
        let _guard = PREFS_WRITE.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let mut entries = match std::fs::read_to_string(&self.path) {
            Ok(content) => match serde_json::from_str::<Value>(&content) {
                Ok(Value::Object(entries)) => entries,
                _ => {
                    warn!(
                        "{} is not a valid preferences file; backing it up and starting fresh",
                        self.path.display()
                    );
                    std::fs::rename(&self.path, self.path.with_extension("json.bak"))?;
                    Map::new()
                }
            },
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Map::new(),
            Err(e) => return Err(e),
        };
        let raw = entries.remove(mac).unwrap_or_else(|| Value::Object(Map::new()));
        let before = device_from_value(mac, raw.clone());
        let mut after = before.clone();
        f(&mut after);
        let before = serde_json::to_value(&before).map_err(std::io::Error::other)?;
        let after = serde_json::to_value(&after).map_err(std::io::Error::other)?;
        entries.insert(mac.to_string(), merge_changes(raw, &before, &after));
        write_json_atomically(&self.path, &Value::Object(entries))
    }
}

/// App-wide settings. The keys match the files earlier versions wrote.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct AppSettings {
    pub theme: MyTheme,
    pub tray_text_mode: bool,
    /// Pre-Stage 3 global "stem press track control"; moved into per-device stem actions.
    pub stem_control: bool,
    pub notify_on_connect: bool,
    pub notify_low_battery: bool,
}

impl Default for AppSettings {
    fn default() -> Self {
        AppSettings {
            theme: MyTheme::Dark,
            tray_text_mode: false,
            stem_control: false,
            notify_on_connect: true,
            notify_low_battery: true,
        }
    }
}

fn field<T: DeserializeOwned>(file: &Value, key: &str, default: T) -> T {
    file.get(key)
        .cloned()
        .and_then(|value| serde_json::from_value(value).ok())
        .unwrap_or(default)
}

/// Reads each setting on its own, so one bad or missing value doesn't reset the others.
pub fn load_app_settings(path: &Path) -> AppSettings {
    let file = std::fs::read_to_string(path)
        .ok()
        .and_then(|content| serde_json::from_str::<Value>(&content).ok())
        .unwrap_or(Value::Null);
    let defaults = AppSettings::default();
    AppSettings {
        theme: field(&file, "theme", defaults.theme),
        tray_text_mode: field(&file, "tray_text_mode", defaults.tray_text_mode),
        stem_control: field(&file, "stem_control", defaults.stem_control),
        notify_on_connect: field(&file, "notify_on_connect", defaults.notify_on_connect),
        notify_low_battery: field(&file, "notify_low_battery", defaults.notify_low_battery),
    }
}

/// Saves the settings, keeping any keys this version doesn't know about.
pub fn save_app_settings(path: &Path, settings: &AppSettings) -> std::io::Result<()> {
    let _guard = APP_SETTINGS_WRITE
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let mut file = match std::fs::read_to_string(path)
        .ok()
        .and_then(|content| serde_json::from_str::<Value>(&content).ok())
    {
        Some(Value::Object(file)) => file,
        _ => Map::new(),
    };
    if let Value::Object(known) = serde_json::to_value(settings).map_err(std::io::Error::other)? {
        file.extend(known);
    }
    write_json_atomically(path, &Value::Object(file))
}

/// Turns the old global "stem press track control" into the same per-device actions (double
/// press = next, triple = previous), for every listed AirPods that has no actions of its own.
/// Returns whether `settings` changed and must be saved. Keeps the old setting while there are
/// no AirPods to move it to, or if any write fails, so a later start can try again.
pub fn migrate_legacy_stem_control(
    settings: &mut AppSettings,
    store: &PrefsStore,
    macs: &[String],
) -> bool {
    if !settings.stem_control || macs.is_empty() {
        return false;
    }
    let mut all_saved = true;
    for mac in macs {
        let result = store.update(mac, |prefs| {
            if prefs.stem_actions.is_all_native() {
                for bud in [Bud::Left, Bud::Right] {
                    prefs.stem_actions.set(bud, PressType::Double, StemAction::NextTrack);
                    prefs.stem_actions.set(bud, PressType::Triple, StemAction::PreviousTrack);
                }
            }
        });
        if let Err(e) = result {
            warn!("Could not move stem press control to {}: {}", mac, e);
            all_saved = false;
        }
    }
    if !all_saved {
        return false;
    }
    settings.stem_control = false;
    info!("Moved the stem press track control setting to per-device stem actions");
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::utils::MyTheme;
    use serde_json::json;

    const MAC: &str = "AA:BB:CC:DD:EE:01";
    const OTHER: &str = "11:22:33:44:55:66";

    fn store_in(dir: &tempfile::TempDir) -> (PrefsStore, std::path::PathBuf) {
        let path = dir.path().join("preferences.json");
        (PrefsStore::new(path.clone()), path)
    }

    #[test]
    fn missing_prefs_file_gives_defaults() {
        let dir = tempfile::tempdir().unwrap();
        let (store, _) = store_in(&dir);
        let prefs = store.device(MAC);
        assert_eq!(prefs, DevicePrefs::default());
        assert!(!prefs.auto_connect);
        assert!(prefs.ear_detection);
        assert!(prefs.disconnect_when_not_wearing);
        assert!(prefs.takeover_on_media_start);
        assert!(prefs.takeover_from_music);
        assert!(!prefs.takeover_from_call);
        assert_eq!(prefs.conversation_volume, 25);
        assert_eq!(prefs.conversation_reduced_volume, 15);
        assert!(!prefs.conversation_relative);
        assert!(!prefs.conversation_pause_music);
        assert_eq!(prefs.listening_mode_cycle, None);
        assert!(prefs.stem_actions.is_all_native());
    }

    #[test]
    fn stage2_auto_connect_entry_still_reads() {
        let dir = tempfile::tempdir().unwrap();
        let (store, path) = store_in(&dir);
        std::fs::write(&path, format!(r#"{{"{MAC}":{{"autoConnect":true}}}}"#)).unwrap();
        let prefs = store.device(MAC);
        assert!(prefs.auto_connect);
        assert!(prefs.ear_detection);
    }

    #[test]
    fn bad_entry_does_not_lose_others() {
        let prefs = parse_prefs(r#"{"A":{"autoConnect":"yes"},"B":{"earDetection":false}}"#);
        assert_eq!(prefs.get("A"), Some(&DevicePrefs::default()));
        assert_eq!(prefs.get("B").map(|p| p.ear_detection), Some(false));
        assert_eq!(prefs.get("B").map(|p| p.disconnect_when_not_wearing), Some(true));
        assert!(parse_prefs("[1, 2]").is_empty());
        assert!(parse_prefs("not json").is_empty());
    }

    #[test]
    fn unknown_keys_survive_update() {
        let dir = tempfile::tempdir().unwrap();
        let (store, path) = store_in(&dir);
        std::fs::write(
            &path,
            format!(r#"{{"{MAC}":{{"futureKey":7}},"{OTHER}":{{"autoConnect":true,"alsoFuture":"x"}}}}"#),
        )
        .unwrap();
        store.update(MAC, |p| p.ear_detection = false).unwrap();
        let file: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!(file[MAC]["futureKey"], 7);
        assert_eq!(file[MAC]["earDetection"], false);
        assert_eq!(file[OTHER], json!({"autoConnect": true, "alsoFuture": "x"}));
        assert!(!dir.path().join("preferences.json.tmp").exists());
        assert!(!store.device(MAC).ear_detection);
    }

    #[test]
    fn corrupt_prefs_are_backed_up_on_write() {
        let dir = tempfile::tempdir().unwrap();
        let (store, path) = store_in(&dir);
        std::fs::write(&path, "{oops").unwrap();
        store.update(MAC, |p| p.auto_connect = true).unwrap();
        assert_eq!(
            std::fs::read_to_string(dir.path().join("preferences.json.bak")).unwrap(),
            "{oops"
        );
        assert!(store.device(MAC).auto_connect);
    }

    #[test]
    fn one_bad_field_keeps_the_rest() {
        let dir = tempfile::tempdir().unwrap();
        let (store, path) = store_in(&dir);
        std::fs::write(
            &path,
            format!(r#"{{"{MAC}":{{"autoConnect":true,"conversationVolume":300,"earDetection":false,"stemActions":{{"leftDouble":{{"action":"futureAction"}},"rightTriple":{{"action":"nextTrack"}}}}}}}}"#),
        )
        .unwrap();
        let prefs = store.device(MAC);
        assert!(prefs.auto_connect);
        assert!(!prefs.ear_detection);
        assert_eq!(prefs.conversation_volume, 25);
        assert_eq!(prefs.stem_actions.right_triple, StemAction::NextTrack);
        assert_eq!(prefs.stem_actions.left_double, StemAction::Native);
    }

    #[test]
    fn bad_and_unknown_values_survive_an_unrelated_write() {
        let dir = tempfile::tempdir().unwrap();
        let (store, path) = store_in(&dir);
        std::fs::write(
            &path,
            format!(r#"{{"{MAC}":{{"autoConnect":true,"conversationVolume":300,"futureKey":1,"stemActions":{{"leftDouble":{{"action":"futureAction"}},"rightTriple":{{"action":"nextTrack"}}}}}}}}"#),
        )
        .unwrap();
        store.update(MAC, |p| p.takeover_from_call = true).unwrap();
        let file: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!(file[MAC]["takeoverFromCall"], true);
        assert_eq!(file[MAC]["autoConnect"], true);
        assert_eq!(file[MAC]["conversationVolume"], 300);
        assert_eq!(file[MAC]["futureKey"], 1);
        assert_eq!(file[MAC]["stemActions"]["leftDouble"], json!({"action": "futureAction"}));
        assert_eq!(file[MAC]["stemActions"]["rightTriple"], json!({"action": "nextTrack"}));
    }

    #[test]
    fn changing_one_stem_slot_keeps_the_others() {
        let dir = tempfile::tempdir().unwrap();
        let (store, path) = store_in(&dir);
        std::fs::write(
            &path,
            format!(r#"{{"{MAC}":{{"stemActions":{{"leftDouble":{{"action":"futureAction"}},"rightTriple":{{"action":"nextTrack"}}}}}}}}"#),
        )
        .unwrap();
        store
            .update(MAC, |p| p.stem_actions.left_long = StemAction::PlayPause)
            .unwrap();
        let file: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        let slots = &file[MAC]["stemActions"];
        assert_eq!(slots["leftLong"], json!({"action": "playPause"}));
        assert_eq!(slots["leftDouble"], json!({"action": "futureAction"}));
        assert_eq!(slots["rightTriple"], json!({"action": "nextTrack"}));
    }

    #[test]
    fn only_changed_settings_are_written() {
        let dir = tempfile::tempdir().unwrap();
        let (store, path) = store_in(&dir);
        store.update(MAC, |p| p.auto_connect = true).unwrap();
        let file: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!(file[MAC], json!({"autoConnect": true}));
    }

    #[test]
    fn stem_action_serde_shape() {
        assert_eq!(
            serde_json::to_value(StemAction::Command("notify-send hi".to_string())).unwrap(),
            json!({"action": "command", "command": "notify-send hi"})
        );
        assert_eq!(
            serde_json::to_value(StemAction::Native).unwrap(),
            json!({"action": "native"})
        );
        assert_eq!(
            serde_json::from_value::<StemAction>(json!({"action": "nextTrack"})).unwrap(),
            StemAction::NextTrack
        );
    }

    #[test]
    fn app_settings_defaults_and_partial_file() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("app_settings.json");
        let missing = load_app_settings(&path);
        assert_eq!(missing.theme, MyTheme::Dark);
        assert!(!missing.tray_text_mode);
        assert!(!missing.stem_control);
        assert!(missing.notify_on_connect);
        assert!(missing.notify_low_battery);

        std::fs::write(&path, r#"{"theme":"TokyoNightStorm","tray_text_mode":"bad"}"#).unwrap();
        let partial = load_app_settings(&path);
        assert_eq!(partial.theme, MyTheme::TokyoNightStorm);
        assert!(!partial.tray_text_mode);
        assert!(partial.notify_on_connect);

        let changed = AppSettings {
            tray_text_mode: true,
            notify_on_connect: false,
            ..partial
        };
        save_app_settings(&path, &changed).unwrap();
        assert_eq!(load_app_settings(&path), changed);
        let file: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!(file["tray_text_mode"], true);
        assert_eq!(file["theme"], "TokyoNightStorm");
        assert!(!dir.path().join("app_settings.json.tmp").exists());
    }

    #[test]
    fn legacy_stem_control_becomes_next_previous() {
        let dir = tempfile::tempdir().unwrap();
        let (store, _) = store_in(&dir);
        store
            .update(OTHER, |p| p.stem_actions.right_long = StemAction::PlayPause)
            .unwrap();
        let mut settings = AppSettings {
            stem_control: true,
            ..AppSettings::default()
        };
        let macs = [MAC.to_string(), OTHER.to_string()];
        assert!(migrate_legacy_stem_control(&mut settings, &store, &macs));

        let actions = store.device(MAC).stem_actions;
        assert_eq!(actions.left_double, StemAction::NextTrack);
        assert_eq!(actions.right_double, StemAction::NextTrack);
        assert_eq!(actions.left_triple, StemAction::PreviousTrack);
        assert_eq!(actions.right_triple, StemAction::PreviousTrack);
        assert_eq!(actions.left_single, StemAction::Native);
        assert_eq!(actions.right_long, StemAction::Native);
        // A device the user already customized keeps its own actions.
        let other = store.device(OTHER).stem_actions;
        assert_eq!(other.right_long, StemAction::PlayPause);
        assert_eq!(other.left_double, StemAction::Native);

        assert!(!settings.stem_control);
        assert!(!migrate_legacy_stem_control(&mut settings, &store, &macs));
    }

    #[test]
    fn stem_actions_get_set_by_bud_and_press() {
        let mut actions = StemActions::default();
        actions.set(Bud::Right, PressType::Triple, StemAction::NextTrack);
        assert_eq!(actions.right_triple, StemAction::NextTrack);
        assert_eq!(actions.get(Bud::Right, PressType::Triple), &StemAction::NextTrack);
        assert_eq!(actions.get(Bud::Left, PressType::Triple), &StemAction::Native);
        assert!(!actions.is_all_native());
    }
}
