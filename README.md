![LibrePods Banner](/imgs/banner.png)

[![XDA Thread](https://img.shields.io/badge/XDA_Forums-Thread-orange)](https://xdaforums.com/t/app-root-for-now-airpodslikenormal-unlock-apple-exclusive-airpods-features-on-android.4707585/)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/kavishdevar/librepods)](https://github.com/kavishdevar/librepods/releases/latest)
[![GitHub all releases](https://img.shields.io/github/downloads/kavishdevar/librepods/total)](https://github.com/kavishdevar/librepods/releases)
[![GitHub stars](https://img.shields.io/github/stars/kavishdevar/librepods)](https://github.com/kavishdevar/librepods/stargazers)
[![GitHub issues](https://img.shields.io/github/issues/kavishdevar/librepods)](https://github.com/kavishdevar/librepods/issues)
[![GitHub license](https://img.shields.io/github/license/kavishdevar/librepods)](https://github.com/kavishdevar/librepods/blob/main/LICENSE)
[![GitHub contributors](https://img.shields.io/github/contributors/kavishdevar/librepods)](https://github.com/kavishdevar/librepods/graphs/contributors)


## What is LibrePods?

LibrePods unlocks Apple's exclusive AirPods features on non-Apple devices. Get access to noise control modes, adaptive transparency, ear detection, battery status, and more - all the premium features you paid for but Apple locked to their ecosystem.

## Device Compatibility

| Status | Device | Features |
|--------|--------|----------|
| ✅ | AirPods Pro (2nd Gen) | Fully supported and tested |
| ⚠️ | Other AirPods models | Basic features (battery status, ear detection) should work |

Most features should work with any AirPods. Currently, testing is only performed with AirPods Pro 2.

## Key Features

- **Noise Control Modes**: Easily switch between noise control modes without having to reach out to your AirPods to long press
- **Google Assistant Integration**: Control your AirPods with voice commands like "Hey Google, turn on noise cancellation"
- **Ear Detection**: Controls your music automatically when you put your AirPods in or take them out, and switch to phone speaker when you take them out
- **Battery Status**: Accurate battery levels
- **Head Gestures**: Answer calls just by nodding your head
- **Conversational Awareness**: Volume automatically lowers when you speak
- **Other customizations**:
  - Rename your AirPods
  - Customize long-press actions
  - Few accessibility features
  - And more!

See our [pinned issue](https://github.com/kavishdevar/librepods/issues/20) for a complete feature list and roadmap.

## Platform Support

### Linux

The Linux version runs as a system tray app. Connect your AirPods and enjoy:

- Battery monitoring
- Automatic Ear detection
- Conversational Awareness
- Switching Noise Control modes
- Device renaming

> [!NOTE]
> Work in progress, but core functionality is stable and usable.

For installation and detailed info, see the [Linux README](/linux/README.md).

### Android

#### Screenshots

| | | |
|-------------------|-------------------|-------------------|
| ![Settings 1](/android/imgs/settings-1.png) | ![Settings 2](/android/imgs/settings-2.png) | ![Debug Screen](/android/imgs/debug.png) |
| ![Battery Notification and QS Tile for NC Mode](/android/imgs/notification-and-qs.png) | ![Popup](/android/imgs/popup.png) | ![Head Tracking and Gestures](/android/imgs/head-tracking-and-gestures.png) |
| ![Long Press Configuration](/android/imgs/long-press.png) | ![Widget](/android/imgs/widget.png) | ![Customizations 1](/android/imgs/customizations-1.png) |
| ![Customizations 2](/android/imgs/customizations-2.png) | ![audio-popup](/android/imgs/audio-connected-island.png) | |

#### Root Requirement

> [!CAUTION]
> **You must have a rooted device to use LibrePods on Android.** This is due to a [bug in the Android Bluetooth stack](https://issuetracker.google.com/issues/371713238). Please upvote the issue by clicking the '+1' icon on the IssueTracker page.
> 
> There are **no exceptions** to the root requirement until Google merges the fix.

#### Installation Methods

##### Method 1: Xposed Module (Recommended)
This method is less intrusive and should be tried first:

1. Install LSPosed, or another Xposed provider on your rooted device
2. Download the LibrePods app from the releases section, and install it.
3. Enable the Xposed module for the bluetooth app in your Xposed manager
4. Follow the instructions in the app to set up the module.
5. Open the app and connect your AirPods

##### Method 2: Root Module (Backup Option)
If the Xposed method doesn't work for you:

1. Download the `btl2capfix.zip` module from the releases section
2. Install it using your preferred root manager (KernelSU, Apatch, or Magisk).
3. Reboot your device
4. Connect your AirPods

##### Method 3: Patching it yourself
If you prefer to patch the Bluetooth stack yourself, follow these steps:

1. Look for the library in use by running `lsof | grep libbluetooth`
2. Find the library path (e.g., `/system/lib64/libbluetooth_jni.so`)
3. Find the `l2c_fcr_chk_chan_modes` function in the library
4. Patch the function to always return `1` (true)
5. Repack the library and push it back to the device. You can do this by creating a root module yourself.
6. Reboot your device

If you're unfamiliar with these steps, search for tutorials online or ask in Android rooting communities.

#### A few notes

- Due to recent AirPods' firmware upgrades, you must enable `Off listening mode` to switch to `Off`. This is because in this mode, louds sounds are not reduced!

- If you have take both AirPods out, the app will automatically switch to the phone speaker. But, Android might keep on trying to connect to the AirPods because the phone is still connected to them, just the A2DP profile is not connected. The app tries to disconnect the A2DP profile as soon as it detects that Android has connected again if they're not in the ear.

- When renaming your AirPods through the app, you'll need to re-pair them with your phone for the name change to take effect. This is a limitation of how Bluetooth device naming works on Android.

## Google Assistant Integration

LibrePods supports voice control through Google Assistant shortcuts. You can control your AirPods using natural voice commands:

### Available Voice Commands

**Noise Control:**
- "Hey Google, turn on noise cancellation"
- "Hey Google, turn on transparency mode"
- "Hey Google, turn on adaptive transparency"
- "Hey Google, turn off noise control"

**Conversational Awareness:**
- "Hey Google, turn on conversational awareness"
- "Hey Google, turn off conversational awareness"

### Requirements
- LibrePods service must be running
- AirPods must be connected
- Google Assistant enabled on your device

For detailed setup and troubleshooting, see the [Google Assistant Shortcuts Guide](docs/google-assistant-shortcuts.md).

## Development Resources

For developers interested in the protocol details, check out the [AAP Definitions](/AAP%20Definitions.md) documentation.

## CrossDevice Stuff

> [!IMPORTANT]
> This feature is still in early development and might not work as expected. No support is provided for this feature yet.

### Features in Development

- **Battery Status Sync**: Get battery status on any device when you connect your AirPods to one of them
- **Cross-device Controls**: Control your AirPods from either device when connected to one
- **Automatic Device Switching**: Seamlessly switch between Linux and Android devices based on active audio sources

Check out the demo below:

https://github.com/user-attachments/assets/d08f8a51-cd52-458b-8e55-9b44f4d5f3ab

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=kavishdevar/librepods&type=Date)](https://star-history.com/#kavishdevar/librepods&Date)

# License

LibrePods - AirPods liberated from Apple’s ecosystem
Copyright (C) 2025 LibrePods contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published
by the Free Software Foundation, either version 3 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program over [here](/LICENSE). If not, see <https://www.gnu.org/licenses/>.

All trademarks, logos, and brand names are the property of their respective owners. Use of them does not imply any affiliation with or endorsement by them. All AirPods images, symbols, and the SF Pro font are the property of Apple Inc.
