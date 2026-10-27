//! Desktop notifications (org.freedesktop.Notifications on the session bus): AirPods
//! connected, and low battery, each replacing its own previous notification.

use crate::bluetooth::aacp::{BatteryComponent, BatteryInfo, BatteryStatus};
use log::warn;
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use zbus::zvariant::Value;

/// A bud at or below this level gets one warning.
const LOW_BATTERY: u8 = 20;
/// Back at or above this level (or charging), it may warn again.
const REARM_BATTERY: u8 = 25;
/// How long a notification call may take; a wedged notification server must not hold up
/// the AirPods' event handling.
const NOTIFY_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(2);

#[derive(Clone)]
pub struct Notifier {
    connection: zbus::Connection,
    /// The notification id last shown under each key, so a new one replaces it.
    ids: Arc<Mutex<HashMap<String, u32>>>,
}

impl Notifier {
    pub async fn connect() -> Option<Self> {
        match zbus::connection::Builder::session() {
            Ok(builder) => Self::from_builder(builder, NOTIFY_TIMEOUT).await,
            Err(e) => {
                warn!("Desktop notifications are unavailable: {}", e);
                None
            }
        }
    }

    async fn from_builder(
        builder: zbus::connection::Builder<'static>,
        timeout: std::time::Duration,
    ) -> Option<Self> {
        match builder.method_timeout(timeout).build().await {
            Ok(connection) => Some(Notifier {
                connection,
                ids: Arc::new(Mutex::new(HashMap::new())),
            }),
            Err(e) => {
                warn!("Desktop notifications are unavailable: {}", e);
                None
            }
        }
    }

    /// Shows a notification without waiting for it: the caller's event loop never stalls on
    /// the notification server.
    pub fn show_detached(&self, key: &str, summary: &str, body: &str) {
        let notifier = self.clone();
        let (key, summary, body) = (key.to_string(), summary.to_string(), body.to_string());
        tokio::spawn(async move { notifier.show(&key, &summary, &body).await });
    }

    pub async fn show(&self, key: &str, summary: &str, body: &str) {
        let replaces_id = self
            .ids
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(key)
            .copied()
            .unwrap_or(0);
        let hints: HashMap<&str, Value> = HashMap::new();
        let reply = self
            .connection
            .call_method(
                Some("org.freedesktop.Notifications"),
                "/org/freedesktop/Notifications",
                Some("org.freedesktop.Notifications"),
                "Notify",
                &(
                    "LibrePods",
                    replaces_id,
                    "audio-headphones",
                    summary,
                    body,
                    Vec::<&str>::new(),
                    hints,
                    -1i32,
                ),
            )
            .await;
        match reply.and_then(|message| message.body().deserialize::<u32>()) {
            Ok(id) => {
                self.ids
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner())
                    .insert(key.to_string(), id);
            }
            Err(e) => warn!("Could not show a desktop notification: {}", e),
        }
    }
}

fn level_text(battery: &BatteryInfo) -> String {
    let charging = if battery.status == BatteryStatus::Charging { "⚡" } else { "" };
    format!("{}%{}", battery.level, charging)
}

/// "L 94% · R 95%⚡ · Case 80%" (parts that are disconnected are left out); a headset's
/// single battery is just "80%".
pub fn battery_summary(batteries: &[BatteryInfo]) -> String {
    let present = |component: BatteryComponent| {
        batteries
            .iter()
            .find(|b| b.component == component && b.status != BatteryStatus::Disconnected)
    };
    if let Some(headset) = present(BatteryComponent::Headphone) {
        return level_text(headset);
    }
    [
        (BatteryComponent::Left, "L"),
        (BatteryComponent::Right, "R"),
        (BatteryComponent::Case, "Case"),
    ]
    .iter()
    .filter_map(|(component, label)| present(*component).map(|b| format!("{} {}", label, level_text(b))))
    .collect::<Vec<_>>()
    .join(" · ")
}

/// "Test AirPods Pro: left AirPod at 18%".
pub fn low_battery_text(name: &str, battery: &BatteryInfo) -> String {
    let part = match battery.component {
        BatteryComponent::Left => "left AirPod",
        BatteryComponent::Right => "right AirPod",
        BatteryComponent::Headphone => "AirPods",
        BatteryComponent::Case => "case",
    };
    format!("{}: {} at {}%", name, part, battery.level)
}

/// Warns once per bud when it runs low, and again only after it has charged back up.
#[derive(Default)]
pub struct LowBatteryTracker {
    alerted: HashSet<BatteryComponent>,
}

impl LowBatteryTracker {
    /// The buds (or headset) that just became low.
    pub fn check(&mut self, batteries: &[BatteryInfo]) -> Vec<BatteryComponent> {
        let mut newly_low = Vec::new();
        for battery in batteries {
            if battery.component == BatteryComponent::Case
                || battery.status == BatteryStatus::Disconnected
                || battery.level == 0
            {
                continue;
            }
            if battery.status == BatteryStatus::Charging || battery.level >= REARM_BATTERY {
                self.alerted.remove(&battery.component);
            } else if battery.level <= LOW_BATTERY && self.alerted.insert(battery.component) {
                newly_low.push(battery.component);
            }
        }
        newly_low
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bluetooth::aacp::{BatteryComponent, BatteryInfo, BatteryStatus};

    fn battery(component: BatteryComponent, level: u8, status: BatteryStatus) -> BatteryInfo {
        BatteryInfo {
            component,
            level,
            status,
        }
    }

    /// A notification server that accepts calls and never answers, like a wedged plasmashell.
    struct SilentServer;

    #[zbus::interface(name = "org.freedesktop.Notifications")]
    impl SilentServer {
        #[allow(clippy::too_many_arguments)]
        async fn notify(
            &self,
            _app_name: String,
            _replaces_id: u32,
            _app_icon: String,
            _summary: String,
            _body: String,
            _actions: Vec<String>,
            _hints: HashMap<String, zbus::zvariant::OwnedValue>,
            _expire_timeout: i32,
        ) -> u32 {
            std::future::pending::<()>().await;
            0
        }
    }

    async fn notifier_with_silent_server(
        timeout: std::time::Duration,
    ) -> (zbus::Connection, Notifier) {
        let (server_side, client_side) = tokio::net::UnixStream::pair().unwrap();
        let server = async {
            zbus::connection::Builder::unix_stream(server_side)
                .server(zbus::Guid::generate())
                .unwrap()
                .p2p()
                .serve_at("/org/freedesktop/Notifications", SilentServer)
                .unwrap()
                .build()
                .await
                .unwrap()
        };
        let client = Notifier::from_builder(
            zbus::connection::Builder::unix_stream(client_side).p2p(),
            timeout,
        );
        let (server, notifier) = tokio::join!(server, client);
        (server, notifier.expect("the peer-to-peer client connects"))
    }

    #[tokio::test]
    async fn show_gives_up_when_the_notification_server_hangs() {
        let (_server, notifier) =
            notifier_with_silent_server(std::time::Duration::from_millis(200)).await;
        let shown = tokio::time::timeout(
            std::time::Duration::from_secs(3),
            notifier.show("connect:test", "summary", "body"),
        )
        .await;
        assert!(shown.is_ok(), "show() kept waiting for a server that never answers");
    }

    #[tokio::test]
    async fn show_detached_returns_at_once() {
        let (_server, notifier) =
            notifier_with_silent_server(std::time::Duration::from_secs(30)).await;
        let start = std::time::Instant::now();
        notifier.show_detached("connect:test", "summary", "body");
        assert!(
            start.elapsed() < std::time::Duration::from_millis(50),
            "took {:?}",
            start.elapsed()
        );
    }

    #[test]
    fn battery_summary_formats() {
        use BatteryComponent::*;
        use BatteryStatus::*;
        assert_eq!(
            battery_summary(&[
                battery(Left, 94, NotCharging),
                battery(Right, 95, Charging),
                battery(Case, 0, Disconnected),
            ]),
            "L 94% · R 95%⚡"
        );
        assert_eq!(
            battery_summary(&[
                battery(Left, 50, Unknown),
                battery(Right, 51, NotCharging),
                battery(Case, 80, NotCharging),
            ]),
            "L 50% · R 51% · Case 80%"
        );
        assert_eq!(battery_summary(&[battery(Headphone, 80, NotCharging)]), "80%");
        assert_eq!(battery_summary(&[]), "");
    }

    #[test]
    fn low_battery_alerts_once_and_rearms() {
        use BatteryComponent::*;
        use BatteryStatus::*;
        let mut tracker = LowBatteryTracker::default();
        assert_eq!(tracker.check(&[battery(Left, 19, NotCharging)]), vec![Left]);
        assert_eq!(tracker.check(&[battery(Left, 18, NotCharging)]), vec![]);
        assert_eq!(tracker.check(&[battery(Left, 30, NotCharging)]), vec![]);
        assert_eq!(tracker.check(&[battery(Left, 19, NotCharging)]), vec![Left]);
        // Between 20 % and 25 % nothing re-arms, so a level that wobbles doesn't nag.
        assert_eq!(tracker.check(&[battery(Left, 22, NotCharging)]), vec![]);
        assert_eq!(tracker.check(&[battery(Left, 19, NotCharging)]), vec![]);
        assert_eq!(tracker.check(&[battery(Right, 15, Charging)]), vec![]);
        assert_eq!(tracker.check(&[battery(Case, 10, NotCharging)]), vec![]);
        assert_eq!(tracker.check(&[battery(Right, 0, Disconnected)]), vec![]);
        assert_eq!(tracker.check(&[battery(Headphone, 12, Unknown)]), vec![Headphone]);
    }

    #[test]
    fn low_battery_text_names_the_bud() {
        use BatteryComponent::*;
        use BatteryStatus::*;
        assert_eq!(
            low_battery_text("Test AirPods Pro", &battery(Left, 18, NotCharging)),
            "Test AirPods Pro: left AirPod at 18%"
        );
        assert_eq!(
            low_battery_text("AirPods", &battery(Right, 9, NotCharging)),
            "AirPods: right AirPod at 9%"
        );
        assert_eq!(
            low_battery_text("Max", &battery(Headphone, 15, NotCharging)),
            "Max: AirPods at 15%"
        );
    }
}
