pub mod aacp;
pub(crate) mod apple_advertisement;
pub mod att;
pub mod battery_telemetry;
pub mod case_battery;
pub(crate) mod discovery;
pub mod le;
pub mod managers;

/// Respect the user's radio switch instead of repeatedly trying to override it.
pub(crate) async fn wait_for_adapter_power(adapter: &bluer::Adapter) -> bluer::Result<()> {
    if adapter.is_powered().await? {
        return Ok(());
    }
    log::info!("Bluetooth is off; waiting for the adapter to be enabled");
    while !adapter.is_powered().await? {
        tokio::time::sleep(std::time::Duration::from_secs(1)).await;
    }
    Ok(())
}
