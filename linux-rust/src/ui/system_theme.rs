//! Follows the desktop's light/dark preference (XDG desktop portal
//! `org.freedesktop.appearance color-scheme`), used by the "System" theme.
//!
//! iced can follow the system theme itself, but on Wayland its value is reset
//! from winit (which doesn't know the desktop preference) whenever a window
//! opens, so the window could stay light while COSMIC is dark. Tracking the
//! portal ourselves and choosing Dark/Light explicitly avoids that.

use dbus::arg::{RefArg, Variant};
use std::time::Duration;

/// Maps the portal's `color-scheme` value: 1 = prefer dark, 2 = prefer light,
/// 0 = no preference.
pub fn scheme_is_dark(value: u32) -> Option<bool> {
    match value {
        1 => Some(true),
        2 => Some(false),
        _ => None,
    }
}

/// Unwraps nested variants (`ReadOne` returns `v`; the deprecated `Read`
/// returns `v` inside `v`).
fn variant_u32(value: &dyn RefArg) -> Option<u32> {
    if let Some(v) = value.as_u64() {
        return u32::try_from(v).ok();
    }
    value.as_iter()?.next().and_then(variant_u32)
}

/// Reads the current preference. `None` if the portal isn't available or the
/// desktop has no preference.
pub fn read() -> Option<bool> {
    let conn = dbus::blocking::Connection::new_session().ok()?;
    let proxy = conn.with_proxy(
        "org.freedesktop.portal.Desktop",
        "/org/freedesktop/portal/desktop",
        Duration::from_secs(2),
    );
    let (value,): (Variant<Box<dyn RefArg>>,) = proxy
        .method_call(
            "org.freedesktop.portal.Settings",
            "ReadOne",
            ("org.freedesktop.appearance", "color-scheme"),
        )
        .ok()?;
    variant_u32(&value).and_then(scheme_is_dark)
}

/// Emits the dark/light preference whenever it changes.
pub fn changes() -> impl futures::Stream<Item = bool> {
    iced::stream::channel(4, async |mut out: futures::channel::mpsc::Sender<bool>| {
        use futures::SinkExt;
        let mut last = None;
        loop {
            let current = tokio::task::spawn_blocking(read).await.ok().flatten();
            if let Some(dark) = current
                && last != Some(dark)
            {
                last = Some(dark);
                if out.send(dark).await.is_err() {
                    break;
                }
            }
            tokio::time::sleep(Duration::from_millis(1500)).await;
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_portal_values() {
        assert_eq!(scheme_is_dark(1), Some(true));
        assert_eq!(scheme_is_dark(2), Some(false));
        assert_eq!(scheme_is_dark(0), None);
        assert_eq!(scheme_is_dark(7), None);
    }

    #[test]
    fn unwraps_nested_variants() {
        let inner: Box<dyn RefArg> = Box::new(1u32);
        assert_eq!(variant_u32(&inner), Some(1));
        let nested: Box<dyn RefArg> = Box::new(Variant(Box::new(2u32) as Box<dyn RefArg>));
        assert_eq!(variant_u32(&nested), Some(2));
        let text: Box<dyn RefArg> = Box::new("dark".to_string());
        assert_eq!(variant_u32(&text), None);
    }
}

#[cfg(test)]
mod live_tests {
    /// Needs a desktop session: `cargo test -- --ignored reads_live_portal --nocapture`
    #[test]
    #[ignore]
    fn reads_live_portal() {
        let value = super::read();
        eprintln!("portal color scheme: {:?}", value.map(|d| if d { "dark" } else { "light" }));
        assert!(value.is_some(), "portal not available");
    }
}
