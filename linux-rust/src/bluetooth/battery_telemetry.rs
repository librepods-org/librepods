//! Addressed, timestamped battery telemetry; independent of the audio link.
use serde::Serialize;
use std::io::Write;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::{
    collections::BTreeMap,
    fs, io,
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};

pub const FRESH_MS: u64 = 150_000;
pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Sample {
    pub percentage: u8,
    pub charging: bool,
    pub observed_at: u64,
}
#[derive(Debug, Default)]
pub struct Telemetry {
    // device -> component -> source -> latest reading. Unknown means only that
    // source can no longer report this component; another source may still know it.
    data: BTreeMap<String, BTreeMap<String, BTreeMap<String, Sample>>>,
    last_write: u64,
}
impl Telemetry {
    pub fn update(&mut self, device: &str, source: &str, component: &str, byte: u8, now: u64) {
        let sources = self
            .data
            .entry(device.to_owned())
            .or_default()
            .entry(component.to_owned())
            .or_default();
        if byte == 255 || byte & 127 > 100 {
            sources.remove(source);
        } else {
            sources.insert(
                source.to_owned(),
                Sample {
                    percentage: byte & 127,
                    charging: byte & 128 != 0,
                    observed_at: now,
                },
            );
        }
    }
    pub fn component(&self, device: &str, component: &str, now: u64) -> Option<Sample> {
        self.data
            .get(device)?
            .get(component)?
            .values()
            .filter(|sample| sample.observed_at <= now && now - sample.observed_at < FRESH_MS)
            .max_by_key(|sample| sample.observed_at)
            .copied()
    }
    pub fn snapshot(&self, now: u64) -> BTreeMap<String, BTreeMap<String, Sample>> {
        self.data
            .iter()
            .filter_map(|(device, parts)| {
                let values: BTreeMap<_, _> = parts
                    .iter()
                    .filter_map(|(part, _)| {
                        self.component(device, part, now)
                            .map(|sample| (part.clone(), sample))
                    })
                    .collect();
                (!values.is_empty()).then_some((device.clone(), values))
            })
            .collect()
    }
    pub fn needs_battery_refresh(&self, now: u64) -> bool {
        // Refresh the case before consumers' 30-second visibility timeout.
        // Earbuds keep their longer interval; stop probing after ten minutes.
        self.data.values().any(|parts| {
            parts.iter().any(|(component, sources)| {
                let stale_ms = if component == "case" { 15_000 } else { 60_000 };
                let last = sources.values().map(|sample| sample.observed_at).max();
                last.is_some_and(|at| now >= at && (stale_ms..600_000).contains(&(now - at)))
            })
        })
    }

    pub fn publish(&mut self, now: u64) -> io::Result<()> {
        // At most one write per second even when many duplicate adverts arrive.
        if now >= self.last_write && now - self.last_write < 1000 {
            return Ok(());
        }
        let Some(runtime) = std::env::var_os("XDG_RUNTIME_DIR") else {
            return Ok(());
        };
        let dir = PathBuf::from(runtime).join("librepods");
        fs::create_dir_all(&dir)?;
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o700))?;
        let path = dir.join("battery.json");
        let temporary = dir.join(format!("battery.{}.tmp", std::process::id()));
        let body =
            serde_json::to_vec(&serde_json::json!({"version":1,"devices":self.snapshot(now)}))?;
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&temporary)?;
        let result = (|| {
            file.write_all(&body)?;
            fs::rename(&temporary, &path)
        })();
        if result.is_err() {
            let _ = fs::remove_file(&temporary);
        }
        result?;
        self.last_write = now;
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn closed_case_and_worn_bud_merge_without_erasing_each_other() {
        let mut t = Telemetry::default();
        t.update("A", "earbuds", "left", 80, 1000);
        t.update("A", "earbuds", "right", 255, 1000);
        t.update("A", "case", "left", 255, 1100);
        t.update("A", "case", "right", 128 + 65, 1100);
        t.update("A", "case", "case", 98, 1100);
        let s = t.snapshot(1100);
        assert_eq!(s["A"]["left"].percentage, 80);
        assert!(!s["A"]["left"].charging);
        assert_eq!(s["A"]["right"].percentage, 65);
        assert!(s["A"]["right"].charging);
        assert_eq!(s["A"]["case"].percentage, 98);
        assert!(!s.contains_key("B"));
    }
    #[test]
    fn refresh_only_for_recently_seen_components_with_silent_updates() {
        let mut t = Telemetry::default();
        t.update("A", "earbuds", "right", 90, 1000);
        assert!(t.needs_battery_refresh(61000));
        t.update("A", "case", "left", 200, 1000);
        assert!(!t.needs_battery_refresh(60999));
        assert!(t.needs_battery_refresh(61000));
        assert!(!t.needs_battery_refresh(601000));
        assert!(!t.needs_battery_refresh(999));
    }
    #[test]
    fn case_refresh_precedes_short_visibility_timeout() {
        let mut t = Telemetry::default();
        t.update("A", "case", "case", 128 + 96, 1000);
        assert!(!t.needs_battery_refresh(15999));
        assert!(t.needs_battery_refresh(16000));
        t.update("A", "case", "case", 128 + 96, 16000);
        assert!(!t.needs_battery_refresh(16000));
        assert!(!t.needs_battery_refresh(616000));
    }
    #[test]
    fn fresh_case_does_not_suppress_stale_earbud_refresh() {
        let mut t = Telemetry::default();
        t.update("A", "earbuds", "left", 100, 1000);
        t.update("A", "earbuds", "right", 100, 1000);
        t.update("A", "case", "case", 100, 61000);
        assert!(t.needs_battery_refresh(61000));
        t.update("A", "earbuds", "left", 100, 61000);
        assert!(t.needs_battery_refresh(61000));
        t.update("A", "earbuds", "right", 100, 61000);
        assert!(!t.needs_battery_refresh(61000));
        t.update("A", "case", "case", 100, 661000);
        assert!(!t.needs_battery_refresh(661000));
    }

    #[test]
    fn source_withdrawal_and_freshness_are_per_component() {
        let mut t = Telemetry::default();
        t.update("A", "earbuds", "left", 70, 1000);
        t.update("A", "case", "left", 128 + 71, 1100);
        assert!(t.snapshot(1100)["A"]["left"].charging);
        t.update("A", "case", "left", 255, 1200);
        assert!(!t.snapshot(1200)["A"]["left"].charging);
        t.update("A", "earbuds", "right", 128 + 80, 2000);
        let s = t.snapshot(1000 + FRESH_MS);
        assert!(!s["A"].contains_key("left"));
        assert!(s["A"].contains_key("right"));
        assert!(t.snapshot(2000 + FRESH_MS).is_empty());
        assert!(t.snapshot(999).is_empty());
    }
}
