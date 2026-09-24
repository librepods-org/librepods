# LibrePods HiiT — modifications

LibrePods HiiT is a fork of [LibrePods](https://github.com/kavishdevar/librepods)
(Copyright (C) 2025 LibrePods contributors), distributed under the same
GNU General Public License v3.0 (see `LICENSE`). This file lists the changes
made in the fork, as required by section 5(a) of the GPL.

Changed code is also marked with a `LibrePods HiiT` comment: at the top of
rewritten or new files, and next to the change in the C++ sources.

## 2026-09-24 — Linux app: design system

- New component kit in the shadcn/ui style (`linux/Ui*.qml`), with design
  tokens in `linux/Theme.qml` that follow the system light/dark preference.
- UI font changed to Departure Mono; SF Pro and SF Symbols removed.
- Icons changed to Lucide, rendered in any color by `linux/IconImageProvider.hpp`.
- AirPods product images replaced by original line-art illustrations
  (`linux/assets/illustrations/`), also used for the tray icon.
- `Main.qml`, `SegmentedControl.qml`, `BatteryIndicator.qml`, `PodColumn.qml` and
  `KeysQRDialog.qml` rebuilt on the kit. Behavior changes:
  - the noise control selector keeps following the device after the first click;
  - switches only send commands on user action (`toggled`), not on backend updates;
  - connection status and battery level no longer rely on color alone;
  - the settings page gains a header with a back button (also `Esc`).

## Third-party assets

| Asset | Path | License |
|---|---|---|
| Departure Mono 1.500, © Helena Zhang | `linux/assets/fonts/` | SIL Open Font License 1.1 (`DepartureMono-OFL.txt`) |
| Lucide icons 1.48.0, © Lucide Icons and Contributors | `linux/assets/icons/` | ISC (`LICENSE`) |
