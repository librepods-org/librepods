use crate::devices::enums::DeviceData;
use serde_json::{Map, Value};
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use tempfile::NamedTempFile;

static SETTINGS_WRITE_LOCK: Mutex<()> = Mutex::new(());
static DEVICES_WRITE_LOCK: Mutex<()> = Mutex::new(());

fn parent_directory(path: &Path) -> &Path {
    path.parent()
        .filter(|parent| !parent.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."))
}

fn ensure_secure_parent(path: &Path) -> io::Result<PathBuf> {
    let parent = parent_directory(path);
    let parent_existed = parent.exists();

    #[cfg(unix)]
    {
        use std::os::unix::fs::{DirBuilderExt, PermissionsExt};

        let mut builder = fs::DirBuilder::new();
        builder.recursive(true).mode(0o700).create(parent)?;
        if !parent_existed {
            fs::set_permissions(parent, fs::Permissions::from_mode(0o700))?;
        }
    }
    #[cfg(not(unix))]
    fs::create_dir_all(parent)?;

    Ok(parent.to_path_buf())
}

fn temporary_file(path: &Path) -> io::Result<NamedTempFile> {
    let parent = ensure_secure_parent(path)?;
    let temp = NamedTempFile::new_in(parent)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        temp.as_file()
            .set_permissions(fs::Permissions::from_mode(0o600))?;
    }
    Ok(temp)
}

fn sync_parent_directory(path: &Path) -> io::Result<()> {
    File::open(parent_directory(path))?.sync_all()
}

fn atomic_write_with_sync<SyncParent>(
    path: &Path,
    contents: &[u8],
    sync_parent: SyncParent,
) -> io::Result<()>
where
    SyncParent: FnOnce(&Path) -> io::Result<()>,
{
    let mut temp = temporary_file(path)?;
    temp.write_all(contents)?;
    temp.as_file_mut().sync_all()?;
    temp.persist(path).map_err(|error| error.error)?;
    sync_parent(path)
}

fn atomic_write(path: &Path, contents: &[u8]) -> io::Result<()> {
    atomic_write_with_sync(path, contents, sync_parent_directory)
}

fn atomic_write_noclobber_with_sync<SyncParent>(
    path: &Path,
    contents: &[u8],
    sync_parent: SyncParent,
) -> io::Result<()>
where
    SyncParent: FnOnce(&Path) -> io::Result<()>,
{
    let mut temp = temporary_file(path)?;
    temp.write_all(contents)?;
    temp.as_file_mut().sync_all()?;
    temp.persist_noclobber(path).map_err(|error| error.error)?;
    sync_parent(path)
}

pub(super) fn migrate_app_settings(legacy_path: &Path, settings_path: &Path) -> io::Result<()> {
    let _guard = SETTINGS_WRITE_LOCK
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    migrate_app_settings_with_sync(legacy_path, settings_path, sync_parent_directory)
}

fn migrate_app_settings_with_sync<SyncParent>(
    legacy_path: &Path,
    settings_path: &Path,
    mut sync_parent: SyncParent,
) -> io::Result<()>
where
    SyncParent: FnMut(&Path) -> io::Result<()>,
{
    if settings_path.exists() || !legacy_path.exists() {
        return Ok(());
    }

    let contents = match fs::read(legacy_path) {
        Ok(contents) => contents,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error),
    };
    match atomic_write_noclobber_with_sync(settings_path, &contents, &mut sync_parent) {
        Ok(()) => {
            fs::remove_file(legacy_path)?;
            sync_parent(legacy_path)
        }
        Err(error) if error.kind() == io::ErrorKind::AlreadyExists && settings_path.is_file() => {
            Ok(())
        }
        Err(error) => Err(error),
    }
}

fn invalid_json(error: serde_json::Error) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, error)
}

fn read_settings_object(path: &Path) -> io::Result<Map<String, Value>> {
    let contents = match fs::read(path) {
        Ok(contents) => contents,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Map::new()),
        Err(error) => return Err(error),
    };
    match serde_json::from_slice::<Value>(&contents).map_err(invalid_json)? {
        Value::Object(settings) => Ok(settings),
        _ => Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "persisted app settings must be a JSON object",
        )),
    }
}

pub fn save_app_settings(path: &Path, updates: &Value) -> io::Result<()> {
    let updates = updates.as_object().ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            "app settings updates must be a JSON object",
        )
    })?;
    let _guard = SETTINGS_WRITE_LOCK
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let mut persisted = read_settings_object(path)?;
    persisted.extend(updates.clone());
    let json = serde_json::to_vec(&Value::Object(persisted)).map_err(invalid_json)?;
    atomic_write(path, &json)
}

fn read_devices(path: &Path) -> io::Result<HashMap<String, DeviceData>> {
    let contents = match fs::read(path) {
        Ok(contents) => contents,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(HashMap::new()),
        Err(error) => return Err(error),
    };
    serde_json::from_slice(&contents).map_err(invalid_json)
}

fn write_devices(path: &Path, devices: &HashMap<String, DeviceData>) -> io::Result<()> {
    let json = serde_json::to_vec(devices).map_err(invalid_json)?;
    atomic_write(path, &json)
}

pub fn load_devices(path: &Path) -> io::Result<HashMap<String, DeviceData>> {
    read_devices(path)
}

pub fn save_devices(path: &Path, devices: &HashMap<String, DeviceData>) -> io::Result<()> {
    let _guard = DEVICES_WRITE_LOCK
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    write_devices(path, devices)
}

pub fn upsert_device<F>(path: &Path, key: &str, update: F) -> io::Result<DeviceData>
where
    F: FnOnce(Option<DeviceData>) -> DeviceData,
{
    let _guard = DEVICES_WRITE_LOCK
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let mut devices = read_devices(path)?;
    let updated = update(devices.remove(key));
    devices.insert(key.to_string(), updated.clone());
    write_devices(path, &devices)?;
    Ok(updated)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::devices::enums::{DeviceData, DeviceInformation, DeviceType};
    use crate::devices::nothing::NothingInformation;
    use serde_json::json;
    use std::collections::HashMap;
    #[cfg(unix)]
    use std::os::unix::fs::{MetadataExt, PermissionsExt};
    use tempfile::tempdir;

    fn nothing_device(name: &str, serial_number: &str, firmware_version: &str) -> DeviceData {
        DeviceData {
            name: name.to_string(),
            type_: DeviceType::Nothing,
            information: Some(DeviceInformation::Nothing(NothingInformation {
                serial_number: serial_number.to_string(),
                firmware_version: firmware_version.to_string(),
            })),
        }
    }

    #[test]
    fn atomic_write_syncs_the_parent_after_the_replacement_is_visible() {
        let temp = tempdir().unwrap();
        let settings_path = temp.path().join("librepods").join("app_settings.json");
        std::fs::create_dir_all(settings_path.parent().unwrap()).unwrap();
        std::fs::write(&settings_path, b"old").unwrap();
        let mut sync_calls = Vec::new();

        atomic_write_with_sync(&settings_path, b"new", |synced_path| {
            assert_eq!(synced_path, settings_path);
            assert_eq!(std::fs::read(synced_path).unwrap(), b"new");
            sync_calls.push(synced_path.to_path_buf());
            Ok(())
        })
        .unwrap();

        assert_eq!(sync_calls, vec![settings_path]);
    }

    #[test]
    fn migration_syncs_the_destination_then_the_legacy_deletion() {
        let temp = tempdir().unwrap();
        let legacy_path = temp.path().join("legacy").join("app_settings.json");
        let settings_path = temp.path().join("config").join("app_settings.json");
        std::fs::create_dir_all(legacy_path.parent().unwrap()).unwrap();
        std::fs::write(&legacy_path, br#"{"theme":"Nord"}"#).unwrap();
        let mut observations = Vec::new();

        migrate_app_settings_with_sync(&legacy_path, &settings_path, |synced_path| {
            observations.push((
                synced_path.to_path_buf(),
                settings_path.exists(),
                legacy_path.exists(),
            ));
            Ok(())
        })
        .unwrap();

        assert_eq!(
            observations,
            vec![
                (settings_path.clone(), true, true),
                (legacy_path.clone(), true, false),
            ]
        );
    }

    #[test]
    fn first_settings_save_creates_private_directories_and_file() {
        let temp = tempdir().unwrap();
        let settings_path = temp
            .path()
            .join("config")
            .join("librepods")
            .join("app_settings.json");

        save_app_settings(&settings_path, &json!({"theme": "Dark"})).unwrap();

        let saved: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&settings_path).unwrap()).unwrap();
        assert_eq!(saved, json!({"theme": "Dark"}));
        #[cfg(unix)]
        {
            assert_eq!(
                std::fs::metadata(settings_path.parent().unwrap())
                    .unwrap()
                    .permissions()
                    .mode()
                    & 0o777,
                0o700
            );
            assert_eq!(
                std::fs::metadata(&settings_path)
                    .unwrap()
                    .permissions()
                    .mode()
                    & 0o777,
                0o600
            );
        }
    }

    #[test]
    fn settings_save_preserves_unknown_persisted_keys() {
        let temp = tempdir().unwrap();
        let settings_path = temp.path().join("librepods").join("app_settings.json");
        std::fs::create_dir_all(settings_path.parent().unwrap()).unwrap();
        std::fs::write(
            &settings_path,
            json!({
                "theme": "Dark",
                "future_setting": {"mode": "automatic"}
            })
            .to_string(),
        )
        .unwrap();

        save_app_settings(&settings_path, &json!({"theme": "Light"})).unwrap();

        let saved: serde_json::Value =
            serde_json::from_slice(&std::fs::read(settings_path).unwrap()).unwrap();
        assert_eq!(
            saved,
            json!({
                "theme": "Light",
                "future_setting": {"mode": "automatic"}
            })
        );
    }

    #[test]
    fn invalid_persisted_settings_are_not_destroyed() {
        let temp = tempdir().unwrap();
        let settings_path = temp.path().join("librepods").join("app_settings.json");
        std::fs::create_dir_all(settings_path.parent().unwrap()).unwrap();
        std::fs::write(&settings_path, b"not valid json").unwrap();

        let error = save_app_settings(&settings_path, &json!({"theme": "Light"})).unwrap_err();

        assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
        assert_eq!(std::fs::read(settings_path).unwrap(), b"not valid json");
    }

    #[test]
    fn settings_save_replaces_the_file_instead_of_truncating_it() {
        let temp = tempdir().unwrap();
        let settings_path = temp.path().join("librepods").join("app_settings.json");
        save_app_settings(&settings_path, &json!({"theme": "Dark"})).unwrap();
        #[cfg(unix)]
        let original_inode = std::fs::metadata(&settings_path).unwrap().ino();

        save_app_settings(&settings_path, &json!({"theme": "Light"})).unwrap();

        #[cfg(unix)]
        assert_ne!(
            std::fs::metadata(&settings_path).unwrap().ino(),
            original_inode
        );
    }

    #[test]
    fn devices_save_round_trips_a_real_device_data_map() {
        let temp = tempdir().unwrap();
        let devices_path = temp
            .path()
            .join("data")
            .join("librepods")
            .join("devices.json");
        let devices = HashMap::from([(
            "AA:BB:CC:DD:EE:FF".to_string(),
            nothing_device("Ear", "SN-1", "1.0.0"),
        )]);

        save_devices(&devices_path, &devices).unwrap();
        let saved = load_devices(&devices_path).unwrap();

        let saved_device = saved.get("AA:BB:CC:DD:EE:FF").unwrap();
        assert_eq!(saved_device.name, "Ear");
        assert_eq!(saved_device.type_, DeviceType::Nothing);
        let Some(DeviceInformation::Nothing(information)) = &saved_device.information else {
            panic!("expected Nothing device information");
        };
        assert_eq!(information.serial_number, "SN-1");
        assert_eq!(information.firmware_version, "1.0.0");
    }

    #[test]
    fn device_upsert_reads_latest_map_and_preserves_unrelated_devices() {
        let temp = tempdir().unwrap();
        let devices_path = temp.path().join("librepods").join("devices.json");
        let initial = HashMap::from([(
            "target".to_string(),
            nothing_device("Original", "SN-1", "1.0.0"),
        )]);
        save_devices(&devices_path, &initial).unwrap();
        let mut latest = load_devices(&devices_path).unwrap();
        latest.insert(
            "unrelated".to_string(),
            nothing_device("Keep me", "SN-2", "2.0.0"),
        );
        save_devices(&devices_path, &latest).unwrap();

        upsert_device(&devices_path, "target", |current| {
            let mut current = current.unwrap();
            current.name = "Updated".to_string();
            current
        })
        .unwrap();

        let saved = load_devices(&devices_path).unwrap();
        assert_eq!(saved.get("target").unwrap().name, "Updated");
        assert_eq!(saved.get("unrelated").unwrap().name, "Keep me");
    }
}
