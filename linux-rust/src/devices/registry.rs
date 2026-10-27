//! Keeps at most one live instance per device MAC. A new connection for a MAC whose old
//! instance is dead first shuts the old one down completely, so stale instances can never act
//! on the audio system alongside the new one.

use log::{debug, info};
use std::collections::HashMap;
use std::future::Future;
use std::sync::Arc;
use tokio::sync::{Mutex, watch};

pub trait ManagedDevice: Send + 'static {
    fn is_alive(&self) -> bool;
    fn shutdown(self) -> impl Future<Output = ()> + Send;
}

enum Slot<D> {
    /// A connection attempt is running; the number identifies which one.
    Connecting(u64),
    Live(D),
    /// A disconnected instance is still shutting down; flips to `true` when it is done.
    ShuttingDown(watch::Receiver<bool>),
}

struct Inner<D> {
    slots: HashMap<String, Slot<D>>,
    next_generation: u64,
}

pub struct DeviceRegistry<D: ManagedDevice> {
    inner: Arc<Mutex<Inner<D>>>,
}

impl<D: ManagedDevice> Clone for DeviceRegistry<D> {
    fn clone(&self) -> Self {
        DeviceRegistry {
            inner: self.inner.clone(),
        }
    }
}

impl<D: ManagedDevice> DeviceRegistry<D> {
    pub fn new() -> Self {
        DeviceRegistry {
            inner: Arc::new(Mutex::new(Inner {
                slots: HashMap::new(),
                next_generation: 0,
            })),
        }
    }

    /// Creates a device for `mac` with `factory` unless a live or connecting one already exists.
    /// Returns whether the new device was registered.
    pub async fn connect_with<F, Fut>(&self, mac: &str, factory: F) -> bool
    where
        F: FnOnce() -> Fut + Send,
        Fut: Future<Output = Option<D>> + Send,
    {
        let (generation, stale) = loop {
            let mut inner = self.inner.lock().await;
            match inner.slots.get(mac) {
                Some(Slot::Connecting(_)) => {
                    debug!("Already connecting to {}, ignoring", mac);
                    return false;
                }
                Some(Slot::Live(device)) if device.is_alive() => {
                    debug!("{} already has a live instance, ignoring", mac);
                    return false;
                }
                Some(Slot::ShuttingDown(done)) => {
                    let mut done = done.clone();
                    drop(inner);
                    debug!("Waiting for the old instance of {} to finish shutting down", mac);
                    if done.wait_for(|finished| *finished).await.is_err() {
                        // The shutdown went away without finishing; don't wait on it forever.
                        let mut inner = self.inner.lock().await;
                        if matches!(inner.slots.get(mac), Some(Slot::ShuttingDown(_))) {
                            inner.slots.remove(mac);
                        }
                    }
                    continue;
                }
                _ => {}
            }
            inner.next_generation += 1;
            let generation = inner.next_generation;
            let stale = match inner
                .slots
                .insert(mac.to_string(), Slot::Connecting(generation))
            {
                Some(Slot::Live(device)) => Some(device),
                _ => None,
            };
            break (generation, stale);
        };

        if let Some(old) = stale {
            info!("Replacing dead instance for {}", mac);
            old.shutdown().await;
        }

        let device = factory().await;

        let mut inner = self.inner.lock().await;
        let still_ours =
            matches!(inner.slots.get(mac), Some(Slot::Connecting(g)) if *g == generation);
        match device {
            None => {
                if still_ours {
                    inner.slots.remove(mac);
                }
                false
            }
            Some(device) if still_ours => {
                inner.slots.insert(mac.to_string(), Slot::Live(device));
                true
            }
            Some(device) => {
                drop(inner);
                info!("{} disconnected while connecting, shutting the new instance down", mac);
                device.shutdown().await;
                false
            }
        }
    }

    /// Shuts down and removes the device for `mac`. Returns whether there was one. A connect
    /// for the same MAC that arrives meanwhile waits until the shutdown has finished.
    pub async fn disconnect(&self, mac: &str) -> bool {
        let mut inner = self.inner.lock().await;
        match inner.slots.remove(mac) {
            Some(Slot::Live(device)) => {
                let (done_tx, done_rx) = watch::channel(false);
                inner
                    .slots
                    .insert(mac.to_string(), Slot::ShuttingDown(done_rx));
                drop(inner);
                device.shutdown().await;
                let mut inner = self.inner.lock().await;
                if matches!(inner.slots.get(mac), Some(Slot::ShuttingDown(_))) {
                    inner.slots.remove(mac);
                }
                drop(inner);
                let _ = done_tx.send(true);
                true
            }
            Some(Slot::ShuttingDown(done)) => {
                // Another disconnect is already handling it.
                inner.slots.insert(mac.to_string(), Slot::ShuttingDown(done));
                false
            }
            Some(Slot::Connecting(_)) => true,
            None => false,
        }
    }

    #[cfg(test)]
    pub async fn is_live(&self, mac: &str) -> bool {
        matches!(
            self.inner.lock().await.slots.get(mac),
            Some(Slot::Live(device)) if device.is_alive()
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
    use std::time::Duration;
    use tokio::time::sleep;

    const MAC: &str = "AA:BB:CC:DD:EE:01";

    #[derive(Clone, Default)]
    struct Counters {
        factory_calls: Arc<AtomicUsize>,
        shutdowns: Arc<AtomicUsize>,
        old_shutdown_done: Arc<AtomicBool>,
    }

    struct FakeDevice {
        alive: Arc<AtomicBool>,
        counters: Counters,
        shutdown_delay: Duration,
    }

    impl ManagedDevice for FakeDevice {
        fn is_alive(&self) -> bool {
            self.alive.load(Ordering::SeqCst)
        }

        fn shutdown(self) -> impl std::future::Future<Output = ()> + Send {
            async move {
                sleep(self.shutdown_delay).await;
                self.counters.shutdowns.fetch_add(1, Ordering::SeqCst);
                self.counters.old_shutdown_done.store(true, Ordering::SeqCst);
            }
        }
    }

    fn device(counters: &Counters, alive: bool, shutdown_delay: Duration) -> FakeDevice {
        counters.factory_calls.fetch_add(1, Ordering::SeqCst);
        FakeDevice {
            alive: Arc::new(AtomicBool::new(alive)),
            counters: counters.clone(),
            shutdown_delay,
        }
    }

    #[tokio::test]
    async fn second_connect_for_live_device_is_ignored() {
        let registry = DeviceRegistry::new();
        let c = Counters::default();
        assert!(registry.connect_with(MAC, || async { Some(device(&c, true, Duration::ZERO)) }).await);
        assert!(!registry.connect_with(MAC, || async { Some(device(&c, true, Duration::ZERO)) }).await);
        assert_eq!(c.factory_calls.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn concurrent_connects_create_one_device() {
        let registry = DeviceRegistry::new();
        let c = Counters::default();
        let slow = || async {
            sleep(Duration::from_millis(50)).await;
            Some(device(&c, true, Duration::ZERO))
        };
        let (a, b) = tokio::join!(registry.connect_with(MAC, slow), registry.connect_with(MAC, slow));
        assert!(a ^ b);
        assert_eq!(c.factory_calls.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn dead_device_is_shut_down_and_replaced() {
        let registry = DeviceRegistry::new();
        let c = Counters::default();
        assert!(registry.connect_with(MAC, || async { Some(device(&c, false, Duration::ZERO)) }).await);
        assert!(registry.connect_with(MAC, || async { Some(device(&c, true, Duration::ZERO)) }).await);
        assert_eq!(c.shutdowns.load(Ordering::SeqCst), 1);
        assert_eq!(c.factory_calls.load(Ordering::SeqCst), 2);
    }

    #[tokio::test]
    async fn replacement_waits_for_old_shutdown() {
        let registry = DeviceRegistry::new();
        let c = Counters::default();
        assert!(
            registry
                .connect_with(MAC, || async { Some(device(&c, false, Duration::from_millis(50))) })
                .await
        );
        let replaced = registry
            .connect_with(MAC, || async {
                assert!(
                    c.old_shutdown_done.load(Ordering::SeqCst),
                    "new device created before the old one finished shutting down"
                );
                Some(device(&c, true, Duration::ZERO))
            })
            .await;
        assert!(replaced);
    }

    #[tokio::test]
    async fn connect_after_disconnect_waits_for_old_shutdown() {
        let registry = DeviceRegistry::new();
        let c = Counters::default();
        registry
            .connect_with(MAC, || async { Some(device(&c, true, Duration::from_millis(50))) })
            .await;
        let disconnecting = {
            let registry = registry.clone();
            tokio::spawn(async move { registry.disconnect(MAC).await })
        };
        sleep(Duration::from_millis(10)).await; // the old instance is mid-shutdown
        let connected = registry
            .connect_with(MAC, || async {
                assert!(
                    c.old_shutdown_done.load(Ordering::SeqCst),
                    "new device created while the old one was still shutting down"
                );
                Some(device(&c, true, Duration::ZERO))
            })
            .await;
        disconnecting.await.unwrap();
        assert!(connected);
        assert!(registry.is_live(MAC).await);
    }

    #[tokio::test]
    async fn disconnect_shuts_down_live_device() {
        let registry = DeviceRegistry::new();
        let c = Counters::default();
        registry.connect_with(MAC, || async { Some(device(&c, true, Duration::ZERO)) }).await;
        assert!(registry.disconnect(MAC).await);
        assert_eq!(c.shutdowns.load(Ordering::SeqCst), 1);
        assert!(!registry.is_live(MAC).await);
    }

    #[tokio::test]
    async fn disconnect_while_connecting_shuts_down_the_new_device() {
        let registry = DeviceRegistry::new();
        let c = Counters::default();
        let connecting = {
            let registry = registry.clone();
            let c = c.clone();
            tokio::spawn(async move {
                registry
                    .connect_with(MAC, || async {
                        sleep(Duration::from_millis(100)).await;
                        Some(device(&c, true, Duration::ZERO))
                    })
                    .await
            })
        };
        sleep(Duration::from_millis(20)).await;
        registry.disconnect(MAC).await;
        connecting.await.unwrap();
        assert!(!registry.is_live(MAC).await);
        assert_eq!(c.shutdowns.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn failed_factory_leaves_no_slot() {
        let registry = DeviceRegistry::<FakeDevice>::new();
        let c = Counters::default();
        assert!(
            !registry
                .connect_with(MAC, || async {
                    c.factory_calls.fetch_add(1, Ordering::SeqCst);
                    None
                })
                .await
        );
        assert!(!registry.is_live(MAC).await);
        registry.connect_with(MAC, || async { Some(device(&c, true, Duration::ZERO)) }).await;
        assert_eq!(c.factory_calls.load(Ordering::SeqCst), 2);
        assert!(registry.is_live(MAC).await);
    }
}
