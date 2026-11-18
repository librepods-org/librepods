//! Low-battery desktop notifications.

use crate::ipc::{Battery, Status};
use log::{info, warn};
use std::collections::HashMap;
use tokio::sync::watch;

/// Notify when a component drops to these levels (highest first).
pub(crate) const THRESHOLDS: [u8; 2] = [20, 10];
/// Re-arm once a component is charging or back above this level.
const RESET_ABOVE: u8 = 25;

/// Decides when to notify. Pure logic, separate from D-Bus, so it can be tested.
#[derive(Debug, Default)]
pub(crate) struct LowBatteryTracker {
    /// Lowest threshold already notified, per component.
    notified: HashMap<&'static str, u8>,
}

impl LowBatteryTracker {
    /// Returns the components that just crossed a threshold, with their level.
    pub(crate) fn check(&mut self, status: &Status) -> Vec<(&'static str, u8)> {
        if !status.connected {
            self.notified.clear();
            return vec![];
        }
        let parts: [(&'static str, Option<Battery>); 4] = [
            ("AirPods", status.headphone),
            ("Left", status.left),
            ("Right", status.right),
            ("Case", status.case),
        ];
        let mut alerts = vec![];
        for (label, battery) in parts {
            let Some(b) = battery else { continue };
            if b.charging || b.level > RESET_ABOVE {
                self.notified.remove(label);
                continue;
            }
            // A bud resting in the case isn't in use, and a disconnected case
            // value is only the last one we saw.
            if !b.connected {
                continue;
            }
            let Some(&crossed) = THRESHOLDS.iter().filter(|&&t| b.level <= t).min() else {
                continue;
            };
            if self.notified.get(label).is_none_or(|&prev| crossed < prev) {
                self.notified.insert(label, crossed);
                alerts.push((label, b.level));
            }
        }
        alerts
    }
}

/// Builds the notification text, e.g. ("Satya's AirPods Pro battery low",
/// "Left 18% · Right 20%").
pub(crate) fn message(name: Option<&str>, alerts: &[(&str, u8)]) -> (String, String) {
    let summary = format!("{} battery low", name.unwrap_or("AirPods"));
    let body = alerts
        .iter()
        .map(|(label, level)| format!("{label} {level}%"))
        .collect::<Vec<_>>()
        .join(" · ");
    (summary, body)
}

fn enabled() -> bool {
    std::fs::read_to_string(crate::utils::get_app_settings_path())
        .ok()
        .and_then(|s| serde_json::from_str::<serde_json::Value>(&s).ok())
        .and_then(|v| v.get("low_battery_notifications").and_then(|b| b.as_bool()))
        .unwrap_or(true)
}

fn send(summary: String, body: String) {
    use dbus::arg::{PropMap, Variant};
    let result = (|| -> Result<(), dbus::Error> {
        let conn = dbus::blocking::Connection::new_session()?;
        let proxy = conn.with_proxy(
            "org.freedesktop.Notifications",
            "/org/freedesktop/Notifications",
            std::time::Duration::from_secs(5),
        );
        let mut hints = PropMap::new();
        hints.insert("urgency".into(), Variant(Box::new(1u8)));
        hints.insert(
            "desktop-entry".into(),
            Variant(Box::new("me.kavishdevar.librepods".to_string())),
        );
        let _: (u32,) = proxy.method_call(
            "org.freedesktop.Notifications",
            "Notify",
            (
                "LibrePods",
                0u32,
                "battery-low",
                summary.as_str(),
                body.as_str(),
                Vec::<String>::new(),
                hints,
                -1i32,
            ),
        )?;
        Ok(())
    })();
    if let Err(e) = result {
        warn!("Could not show low-battery notification: {}", e);
    }
}

/// Watches the shared status and notifies on low battery.
pub async fn run(mut rx: watch::Receiver<Status>) {
    let mut tracker = LowBatteryTracker::default();
    loop {
        let status = rx.borrow_and_update().clone();
        let alerts = tracker.check(&status);
        if !alerts.is_empty() && enabled() {
            let (summary, body) = message(status.name.as_deref(), &alerts);
            info!("{}: {}", summary, body);
            tokio::task::spawn_blocking(move || send(summary, body));
        }
        if rx.changed().await.is_err() {
            break;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bud(level: u8) -> Option<Battery> {
        Some(Battery { level, charging: false, connected: true })
    }

    fn status(left: Option<Battery>, right: Option<Battery>, case: Option<Battery>) -> Status {
        Status { connected: true, left, right, case, ..Default::default() }
    }

    #[test]
    fn notifies_once_per_threshold() {
        let mut t = LowBatteryTracker::default();
        assert!(t.check(&status(bud(50), bud(50), None)).is_empty());
        assert_eq!(t.check(&status(bud(20), bud(50), None)), vec![("Left", 20)]);
        assert!(t.check(&status(bud(19), bud(50), None)).is_empty(), "no repeat at 19%");
        assert!(t.check(&status(bud(15), bud(50), None)).is_empty());
        assert_eq!(t.check(&status(bud(10), bud(50), None)), vec![("Left", 10)]);
        assert!(t.check(&status(bud(5), bud(50), None)).is_empty(), "nothing below the last threshold");
    }

    #[test]
    fn jumping_straight_to_critical_notifies_once() {
        let mut t = LowBatteryTracker::default();
        assert_eq!(t.check(&status(bud(8), None, None)), vec![("Left", 8)]);
        assert!(t.check(&status(bud(7), None, None)).is_empty());
    }

    #[test]
    fn rearms_after_charging_or_recovering() {
        let mut t = LowBatteryTracker::default();
        assert_eq!(t.check(&status(bud(18), None, None)).len(), 1);
        let charging = Some(Battery { level: 18, charging: true, connected: true });
        assert!(t.check(&status(charging, None, None)).is_empty());
        assert_eq!(t.check(&status(bud(18), None, None)), vec![("Left", 18)], "re-armed by charging");
        assert!(t.check(&status(bud(30), None, None)).is_empty());
        assert_eq!(t.check(&status(bud(20), None, None)), vec![("Left", 20)], "re-armed above 25%");
    }

    #[test]
    fn hysteresis_keeps_quiet_between_20_and_25() {
        let mut t = LowBatteryTracker::default();
        assert_eq!(t.check(&status(bud(20), None, None)).len(), 1);
        assert!(t.check(&status(bud(23), None, None)).is_empty());
        assert!(t.check(&status(bud(20), None, None)).is_empty(), "flapping around 20% must not spam");
    }

    #[test]
    fn ignores_buds_in_case_and_stale_case() {
        let mut t = LowBatteryTracker::default();
        let in_case = Some(Battery { level: 5, charging: false, connected: false });
        assert!(t.check(&status(in_case, None, in_case)).is_empty());
    }

    #[test]
    fn reports_several_components_together() {
        let mut t = LowBatteryTracker::default();
        let alerts = t.check(&status(bud(15), bud(9), bud(19)));
        assert_eq!(alerts, vec![("Left", 15), ("Right", 9), ("Case", 19)]);
        let (summary, body) = message(Some("Satya's AirPods Pro"), &alerts);
        assert_eq!(summary, "Satya's AirPods Pro battery low");
        assert_eq!(body, "Left 15% · Right 9% · Case 19%");
    }

    #[test]
    fn disconnect_resets_everything() {
        let mut t = LowBatteryTracker::default();
        assert_eq!(t.check(&status(bud(15), None, None)).len(), 1);
        assert!(t.check(&Status::default()).is_empty());
        assert_eq!(t.check(&status(bud(15), None, None)).len(), 1, "new connection notifies again");
    }

    #[test]
    fn headphones_use_their_own_label() {
        let mut t = LowBatteryTracker::default();
        let s = Status { connected: true, headphone: bud(10), ..Default::default() };
        assert_eq!(t.check(&s), vec![("AirPods", 10)]);
        assert_eq!(message(None, &[("AirPods", 10)]).0, "AirPods battery low");
    }
}
