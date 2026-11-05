use super::persistence::migrate_app_settings;
use std::ffi::OsStr;
use std::io;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct XdgPaths {
    pub config_home: PathBuf,
    pub data_home: PathBuf,
    pub devices: PathBuf,
    pub preferences: PathBuf,
    pub app_settings: PathBuf,
    pub legacy_app_settings: PathBuf,
}

fn absolute_path(value: Option<&OsStr>) -> Option<PathBuf> {
    value.map(PathBuf::from).filter(|path| path.is_absolute())
}

fn resolve_base_directory(
    xdg_value: Option<&OsStr>,
    home: Option<&OsStr>,
    home_suffix: &Path,
    variable_name: &str,
) -> io::Result<PathBuf> {
    if let Some(path) = absolute_path(xdg_value) {
        return Ok(path);
    }

    let home = absolute_path(home).ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::NotFound,
            format!("{variable_name} is unset or invalid and HOME is not an absolute path"),
        )
    })?;
    Ok(home.join(home_suffix))
}

pub fn resolve_xdg_paths(
    home: Option<&OsStr>,
    config_home: Option<&OsStr>,
    data_home: Option<&OsStr>,
) -> io::Result<XdgPaths> {
    let config_home =
        resolve_base_directory(config_home, home, Path::new(".config"), "XDG_CONFIG_HOME")?;
    let data_home =
        resolve_base_directory(data_home, home, Path::new(".local/share"), "XDG_DATA_HOME")?;

    Ok(XdgPaths {
        devices: data_home.join("librepods").join("devices.json"),
        preferences: config_home.join("librepods").join("preferences.json"),
        app_settings: config_home.join("librepods").join("app_settings.json"),
        legacy_app_settings: data_home.join("app_settings.json"),
        config_home,
        data_home,
    })
}

fn current_xdg_paths() -> XdgPaths {
    let home = std::env::var_os("HOME");
    let config_home = std::env::var_os("XDG_CONFIG_HOME");
    let data_home = std::env::var_os("XDG_DATA_HOME");

    resolve_xdg_paths(
        home.as_deref(),
        config_home.as_deref(),
        data_home.as_deref(),
    )
    .unwrap_or_else(|error| panic!("cannot resolve LibrePods data paths: {error}"))
}

pub fn get_devices_path() -> PathBuf {
    current_xdg_paths().devices
}

pub fn get_preferences_path() -> PathBuf {
    current_xdg_paths().preferences
}

fn prepare_app_settings_path(paths: XdgPaths) -> PathBuf {
    if let Err(error) = migrate_app_settings(&paths.legacy_app_settings, &paths.app_settings) {
        log::warn!(
            "Failed to migrate app settings from {} to {}: {}",
            paths.legacy_app_settings.display(),
            paths.app_settings.display(),
            error
        );
        if !paths.app_settings.exists() && paths.legacy_app_settings.exists() {
            return paths.legacy_app_settings;
        }
    }
    paths.app_settings
}

pub fn get_app_settings_path() -> PathBuf {
    prepare_app_settings_path(current_xdg_paths())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::OsStr;
    use tempfile::tempdir;

    const HOME: &str = "/home/tester";

    fn resolve(
        home: Option<&str>,
        config_home: Option<&str>,
        data_home: Option<&str>,
    ) -> std::io::Result<XdgPaths> {
        resolve_xdg_paths(
            home.map(OsStr::new),
            config_home.map(OsStr::new),
            data_home.map(OsStr::new),
        )
    }

    #[test]
    fn unset_xdg_values_use_home_defaults() {
        let paths = resolve(Some(HOME), None, None).unwrap();

        assert_eq!(paths.config_home, PathBuf::from(HOME).join(".config"));
        assert_eq!(
            paths.data_home,
            PathBuf::from(HOME).join(".local").join("share")
        );
        assert_eq!(
            paths.devices,
            PathBuf::from(HOME)
                .join(".local")
                .join("share")
                .join("librepods")
                .join("devices.json")
        );
        assert_eq!(
            paths.app_settings,
            PathBuf::from(HOME)
                .join(".config")
                .join("librepods")
                .join("app_settings.json")
        );
    }

    #[test]
    fn empty_xdg_values_use_home_defaults() {
        let paths = resolve(Some(HOME), Some(""), Some("")).unwrap();

        assert_eq!(paths.config_home, PathBuf::from(HOME).join(".config"));
        assert_eq!(
            paths.data_home,
            PathBuf::from(HOME).join(".local").join("share")
        );
    }

    #[test]
    fn relative_xdg_values_use_home_defaults() {
        let paths = resolve(Some(HOME), Some("relative-config"), Some("relative-data")).unwrap();

        assert_eq!(paths.config_home, PathBuf::from(HOME).join(".config"));
        assert_eq!(
            paths.data_home,
            PathBuf::from(HOME).join(".local").join("share")
        );
    }

    #[test]
    fn absolute_xdg_values_are_used_without_home() {
        let paths = resolve(None, Some("/xdg/config"), Some("/xdg/data")).unwrap();

        assert_eq!(paths.config_home, PathBuf::from("/xdg/config"));
        assert_eq!(paths.data_home, PathBuf::from("/xdg/data"));
    }

    #[test]
    fn preferences_are_resolved_below_config_home() {
        let paths = resolve(Some(HOME), Some("/xdg/config"), Some("/xdg/data")).unwrap();

        assert_eq!(
            paths.preferences,
            PathBuf::from("/xdg/config")
                .join("librepods")
                .join("preferences.json")
        );
    }

    #[test]
    fn invalid_home_is_rejected_when_an_xdg_fallback_is_needed() {
        let error = resolve(Some("relative-home"), None, Some("/xdg/data")).unwrap_err();

        assert_eq!(error.kind(), std::io::ErrorKind::NotFound);
    }

    #[test]
    fn legacy_settings_are_migrated_to_the_resolved_config_path() {
        let temp = tempdir().unwrap();
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let data_home = temp.path().join("data");
        let paths = resolve_xdg_paths(
            Some(home.as_os_str()),
            Some(config_home.as_os_str()),
            Some(data_home.as_os_str()),
        )
        .unwrap();
        std::fs::create_dir_all(paths.legacy_app_settings.parent().unwrap()).unwrap();
        std::fs::write(
            &paths.legacy_app_settings,
            br#"{"theme":"Nord","future_setting":true}"#,
        )
        .unwrap();

        migrate_app_settings(&paths.legacy_app_settings, &paths.app_settings).unwrap();

        assert_eq!(
            std::fs::read(&paths.app_settings).unwrap(),
            br#"{"theme":"Nord","future_setting":true}"#
        );
        assert!(!paths.legacy_app_settings.exists());
    }

    #[test]
    fn failed_migration_keeps_legacy_settings_in_use() {
        let temp = tempdir().unwrap();
        let paths = resolve_xdg_paths(
            Some(temp.path().join("home").as_os_str()),
            Some(temp.path().join("config").as_os_str()),
            Some(temp.path().join("data").as_os_str()),
        )
        .unwrap();
        std::fs::create_dir_all(paths.legacy_app_settings.parent().unwrap()).unwrap();
        std::fs::write(&paths.legacy_app_settings, br#"{"theme":"Nord"}"#).unwrap();
        std::fs::create_dir_all(&paths.config_home).unwrap();
        std::fs::write(paths.app_settings.parent().unwrap(), b"not a directory").unwrap();

        let selected_path = prepare_app_settings_path(paths.clone());

        assert_eq!(selected_path, paths.legacy_app_settings);
        assert_eq!(
            std::fs::read(&selected_path).unwrap(),
            br#"{"theme":"Nord"}"#
        );
        assert!(!paths.app_settings.exists());
    }
}
