## Root Requirement

LibrePods *may* require root depending on your device/OS and what features you want access to:

- Features requiring the VendorID hook ([the features marked with an asterisk here](https://github.com/kavishdevar/librepods#key-features)) will always require root regardless of your device/OS.
- On **ColorOS/OxygenOS 16 and realme UI 7.0** and **Pixel devices on Android 16 QPR3** (with the latest Google Play system update), LibrePods does not need root for most features.
- On other devices, LibrePods needs root because of a bug in the Android Bluetooth stack Fluoride/non-compliance of Apple with Bluetooth standards. You must have Xposed installed for the app to workaround this bug and connect to AirPods. [This issue is being tracked here](https://issuetracker.google.com/issues/371713238). **Please do not comment on the issue thread.** The issue has already been resolved and should be available in **Android 17** for all devices.

> [!IMPORTANT]
> This workaround with Xposed is not guaranteed to work on all devices.


## Installation

### Google Play Store

If you are using a supported device/OS combination, you can install LibrePods from the Google Play Store. You can use the VendorID hook features with root even from the Play Store version.

<a href="https://play.google.com/store/apps/details?id=me.kavishdevar.librepods"><img width="170" alt="GetItOnGooglePlay_Badge_Web_color_English" src="https://github.com/user-attachments/assets/2948308f-af92-443f-94d9-ee381c3a6ccc"/></a>

### GitHub Releases

If you need xposed because of the [root requirement](#root-requirement), you will have to use the apk/zip from the [GitHub releases](https://github.com/kavishdevar/librepods/releases/latest).

### As a system app (root module)

If you want LibrePods to have privileged Bluetooth permissions to 
- show battery status in the system settings and widgets
- show AirPods icon in the system settings (xposed is also currently required for this)
- switch audio to phone speakers when you are not wearing your AirPods

you can install the root module. This is optional and only provides extra features, but it is not required for the app to work.

> [!IMPORTANT]
> When using the root module, do not install the Play Store version. There might be issues because of the signature mismatch between the Play Store version and the root module.

## Nightly/Development Builds

Want to try the latest features before they're officially released? You can grab nightly builds from the [latest nightly release](https://github.com/kavishdevar/librepods/releases?q=nightly).

> [!WARNING]
> These builds are automatically generated from the latest code and may contain new features and bug fixes that haven't been included in a stable release yet. However, please note that they may also be less stable than official releases, so use them at your own risk.

## Screenshots

|                                                                                 |                                            |                                                                      |
| ------------------------------------------------------------------------------- | ------------------------------------------ | -------------------------------------------------------------------- |
| ![Settings 1](./imgs/settings-1.png)                                            | ![Settings 2](./imgs/settings-2.png)       | ![Head Tracking and Gestures](./imgs/head-tracking-and-gestures.png) |
| ![Long Press Configuration](./imgs/long-press.png)                              | ![Customizations 1](./imgs/customizations-1.png)                                | ![accessibility](./imgs/accessibility.png) |
| ![transparency](./imgs/transparency.png)                                        | ![hearing-aid](./imgs/hearing-aid.png)     | ![hearing-test](./imgs/hearing-test.png)   |
| ![hearing-aid-adjustments](./imgs/hearing-aid-adjustments.png)                  | ![Battery Notification and QS Tile for NC Mode](./imgs/notification-and-qs.png) | ![Widget](./imgs/widget.png)               |


here's a very unprofessional demo video

https://github.com/user-attachments/assets/43911243-0576-4093-8c55-89c1db5ea533

### Troubleshooting steps for common errors
- Ensure the correct scope is set in LSPosed/Vector.
- Ensure there is no root-hiding module preventing the hook from loading on the Bluetooth app.
- Restart your phone after confirming the scope.

### A few notes

- Due to recent AirPods' firmware upgrades, you must enable `Off listening mode` to switch to `Off`. This is because in this mode, loud sounds are not reduced.

- When renaming your AirPods through the app, you'll need to re-pair them with your phone for the name change to take effect. This is a limitation of how Bluetooth device naming works on Android.
