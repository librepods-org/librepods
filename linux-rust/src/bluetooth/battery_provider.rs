//! Publishes the AirPods battery to the desktop through BlueZ's battery-provider API, so KDE's
//! Bluetooth and battery widgets show it (AirPods report battery only over AACP, not HFP).

use crate::bluetooth::aacp::{BatteryComponent, BatteryInfo, BatteryStatus};
use log::{debug, info, warn};
use zbus::fdo::{ObjectManager, ObjectManagerProxy};
use zbus::interface;
use zbus::zvariant::{ObjectPath, OwnedObjectPath};

const ROOT: &str = "/me/kavishdevar/librepods/battery";
const PROVIDER_MANAGER: &str = "org.bluez.BatteryProviderManager1";

/// One number for the whole device: the lower of the two buds (the case is not "the AirPods"),
/// or the headset battery on over-ear models.
pub fn system_battery_percent(batteries: &[BatteryInfo]) -> Option<u8> {
    let usable = |b: &&BatteryInfo| b.status != BatteryStatus::Disconnected && b.level > 0;
    let buds = batteries
        .iter()
        .filter(|b| matches!(b.component, BatteryComponent::Left | BatteryComponent::Right))
        .filter(usable)
        .map(|b| b.level)
        .min();
    buds.or_else(|| {
        batteries
            .iter()
            .filter(|b| b.component == BatteryComponent::Headphone)
            .filter(usable)
            .map(|b| b.level)
            .next()
    })
}

struct BatteryProvider {
    percentage: u8,
    device: OwnedObjectPath,
}

#[interface(name = "org.bluez.BatteryProvider1")]
impl BatteryProvider {
    #[zbus(property)]
    fn percentage(&self) -> u8 {
        self.percentage
    }

    #[zbus(property)]
    fn source(&self) -> &str {
        "LibrePods"
    }

    #[zbus(property)]
    fn device(&self) -> OwnedObjectPath {
        self.device.clone()
    }
}

#[derive(Clone)]
pub struct BatteryReporter {
    connection: zbus::Connection,
    adapter_path: String,
}

fn provider_path(mac: &str) -> String {
    format!("{}/dev_{}", ROOT, mac.replace(':', "_"))
}

impl BatteryReporter {
    /// Registers with BlueZ. Returns `None` (logged) when BlueZ has no battery-provider support.
    pub async fn register() -> Option<Self> {
        match Self::try_register().await {
            Ok(reporter) => {
                info!("Registered battery provider with BlueZ on {}", reporter.adapter_path);
                Some(reporter)
            }
            Err(e) => {
                warn!("Could not register battery provider with BlueZ: {}", e);
                None
            }
        }
    }

    async fn try_register() -> zbus::Result<Self> {
        let connection = zbus::Connection::system().await?;
        let objects = ObjectManagerProxy::builder(&connection)
            .destination("org.bluez")?
            .path("/")?
            .build()
            .await?
            .get_managed_objects()
            .await?;
        let mut adapters: Vec<String> = objects
            .into_iter()
            .filter(|(_, interfaces)| interfaces.keys().any(|i| i.as_str() == PROVIDER_MANAGER))
            .map(|(path, _)| path.to_string())
            .collect();
        adapters.sort();
        let adapter_path = adapters.into_iter().next().ok_or_else(|| {
            zbus::Error::Failure("no adapter offers BatteryProviderManager1".to_string())
        })?;

        // BlueZ reads our objects through ObjectManager as soon as we register.
        connection.object_server().at(ROOT, ObjectManager).await?;
        connection
            .call_method(
                Some("org.bluez"),
                adapter_path.as_str(),
                Some(PROVIDER_MANAGER),
                "RegisterBatteryProvider",
                &(ObjectPath::try_from(ROOT)?,),
            )
            .await?;
        Ok(BatteryReporter {
            connection,
            adapter_path,
        })
    }

    pub async fn update(&self, mac: &str, percent: u8) {
        if let Err(e) = self.try_update(mac, percent).await {
            warn!("Could not publish battery for {}: {}", mac, e);
        }
    }

    async fn try_update(&self, mac: &str, percent: u8) -> zbus::Result<()> {
        let path = provider_path(mac);
        let server = self.connection.object_server();
        match server.interface::<_, BatteryProvider>(path.as_str()).await {
            Ok(iface) => {
                let mut provider = iface.get_mut().await;
                if provider.percentage != percent {
                    provider.percentage = percent;
                    provider.percentage_changed(iface.signal_emitter()).await?;
                    debug!("Published battery {}% for {}", percent, mac);
                }
            }
            Err(_) => {
                let device = OwnedObjectPath::try_from(format!(
                    "{}/dev_{}",
                    self.adapter_path,
                    mac.replace(':', "_")
                ))?;
                server
                    .at(
                        path.as_str(),
                        BatteryProvider {
                            percentage: percent,
                            device,
                        },
                    )
                    .await?;
                info!("Publishing AirPods battery to the desktop: {}%", percent);
            }
        }
        Ok(())
    }

    pub async fn remove(&self, mac: &str) {
        let path = provider_path(mac);
        if let Err(e) = self
            .connection
            .object_server()
            .remove::<BatteryProvider, _>(path.as_str())
            .await
        {
            debug!("No battery provider to remove for {}: {}", mac, e);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bluetooth::aacp::{BatteryComponent, BatteryStatus};

    fn bud(component: BatteryComponent, level: u8, status: BatteryStatus) -> BatteryInfo {
        BatteryInfo {
            component,
            level,
            status,
        }
    }

    #[test]
    fn battery_is_lowest_bud() {
        let batteries = [
            bud(BatteryComponent::Right, 51, BatteryStatus::NotCharging),
            bud(BatteryComponent::Left, 49, BatteryStatus::NotCharging),
            bud(BatteryComponent::Case, 0, BatteryStatus::Disconnected),
        ];
        assert_eq!(system_battery_percent(&batteries), Some(49));
    }

    #[test]
    fn disconnected_bud_is_ignored() {
        let batteries = [
            bud(BatteryComponent::Left, 0, BatteryStatus::Disconnected),
            bud(BatteryComponent::Right, 70, BatteryStatus::NotCharging),
        ];
        assert_eq!(system_battery_percent(&batteries), Some(70));
    }

    #[test]
    fn headset_battery_used_for_max() {
        let batteries = [bud(BatteryComponent::Headphone, 80, BatteryStatus::Charging)];
        assert_eq!(system_battery_percent(&batteries), Some(80));
    }

    #[test]
    fn case_only_is_none() {
        let batteries = [bud(BatteryComponent::Case, 90, BatteryStatus::Charging)];
        assert_eq!(system_battery_percent(&batteries), None);
    }

    #[test]
    fn unknown_status_bud_still_counts() {
        let batteries = [
            bud(BatteryComponent::Left, 96, BatteryStatus::Unknown),
            bud(BatteryComponent::Right, 97, BatteryStatus::NotCharging),
        ];
        assert_eq!(system_battery_percent(&batteries), Some(96));
    }
}
