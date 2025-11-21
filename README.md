![LibrePods Banner](/imgs/banner.png)

## What is LibrePods?

LibrePods unlocks Apple's exclusive AirPods features on non-Apple devices. Get access to noise control modes, adaptive transparency, ear detection, hearing aid, customized transparency mode, battery status, and more - all the premium features you paid for but Apple locked to their ecosystem.

## Device Compatibility

| Status | Device                | Features                                                   |
| ------ | --------------------- | ---------------------------------------------------------- |
| ✅      | AirPods Pro (2nd Gen) | Fully supported and tested                                 |
| ✅      | AirPods Pro (3rd Gen) | Fully supported (except heartrate monitoring)              |
| ✅      | AirPods Max           | Fully supported (client shows unsupported features)        |
| ⚠️      | Other AirPods models  | Basic features (battery status, ear detection) should work |

Most features should work with any AirPods. Currently, I've only got AirPods Pro 2 to test with. But, I believe the protocol remains the same for all other AirPods (based on analysis of the bluetooth stack on macOS).

## Key Features

- **Noise Control Modes**: Easily switch between noise control modes without having to reach out to your AirPods to long press
- **Ear Detection**: Controls your music automatically when you put your AirPods in or take them out, and switch to phone speaker when you take them out
- **Battery Status**: Accurate battery levels
- **Head Gestures**: Answer calls just by nodding your head
- **Conversational Awareness**: Volume automatically lowers when you speak
- **Hearing Aid\***
- **Customize Transparency Mode\***
- **Multi-device connectivity\*** (upto 2 devices)
- **Other customizations**:
  - Rename your AirPods
  - Customize long-press actions
  - All accessibility settings
  - And more!

&ast; Features marked with an asterisk require the VendorID to be change to that of Apple.

## Platform Support

### Linux
for the old version see the [Linux README](/linux/README.md). (doesn't have many features, maintainer didn't have time to work on it)

new version in development ([#241](https://github.com/kavishdevar/librepods/pull/241))

![new version](https://github.com/user-attachments/assets/86b3c871-89a8-4e49-861a-5119de1e1d28)

### Android

#### Screenshots

|                                                                                        |                                                   |                                                                             |
| -------------------------------------------------------------------------------------- | ------------------------------------------------- | --------------------------------------------------------------------------- |
| ![Settings 1](/android/imgs/settings-1.png)                                            | ![Settings 2](/android/imgs/settings-2.png)       | ![Debug Screen](/android/imgs/debug.png)                                    |
| ![Battery Notification and QS Tile for NC Mode](/android/imgs/notification-and-qs.png) | ![Popup](/android/imgs/popup.png)                 | ![Head Tracking and Gestures](/android/imgs/head-tracking-and-gestures.png) |
| ![Long Press Configuration](/android/imgs/long-press.png)                              | ![Widget](/android/imgs/widget.png)               | ![Customizations 1](/android/imgs/customizations-1.png)                     |
| ![Customizations 2](/android/imgs/customizations-2.png)                                | ![accessibility](/android/imgs/accessibility.png) | ![transparency](/android/imgs/transparency.png)                             |
| ![hearing-aid](/android/imgs/hearing-aid.png)                                          | ![hearing-test](/android/imgs/hearing-test.png)   | ![hearing-aid-adjustments](/android/imgs/hearing-aid-adjustments.png)       |


here's a very unprofessional demo video

https://github.com/user-attachments/assets/43911243-0576-4093-8c55-89c1db5ea533

#### Root Requirement

If you are using ColorOS/OxygenOS 16, you don't need root except for customizing transparency mode, setting up hearing aid, and use Bluetooth Multipoint. Changing ANC, conversational awareness, ear detection, and other customizations will work without root. For everyone else:

> [!CAUTION]
> **You must have a rooted device with Xposed to use LibrePods on Android.** This is due to a [bug in the Android Bluetooth stack](https://issuetracker.google.com/issues/371713238). Please upvote the issue by clicking the '+1' icon on the IssueTracker page. I don't know a fix for Android versions <13 either. So, this needs a phone running A13+.
> 
> There are **no exceptions** to the root requirement until Google/your OEM figures out a fix.

Until then, you must xposed. I used to provide a non-xposed method too, where the module used overlayfs to replace the bluetooth library with a locally patched one, but that was broken due to how various devices handled overlayfs and a patched library. With xposed, you can also enable the DID hook enabling a few extra features.

## Changing VendorID in the DID profile to that of Apple

Turns out, if you change the VendorID in DID Profile to that of Apple, you get access to several special features!

You can do this on Linux by editing the DeviceID in `/etc/bluetooth/main.conf`. Add this line to the config file `DeviceID = bluetooth:004C:0000:0000`. For android you can enable the `act as Apple device` setting in the app's settings.

### Multi-device Connectivity

Upto two devices can be simultaneously connected to AirPods, for audio and control both. Seamless connection switching. The same notification shows up on Apple device when Android takes over the AirPods as if it were an Apple device ("Move to iPhone"). Android also shows a popup when the other device takes over.

### Accessibility Settings and Hearing Aid

Accessibility settings like customizing transparency mode (amplification, balance, tone, conversation boost, and ambient noise reduction), and loud sound reduction can be configured.

All hearing aid customizations can be done from Android (linux soon), including setting the audiogram result. The app doesn't provide a way to take a hearing test because it requires much more precision. It is much better to use an already available audiogram result. 

#### A few notes

- Due to recent AirPods' firmware upgrades, you must enable `Off listening mode` to switch to `Off`. This is because in this mode, loud sounds are not reduced.

- If you have take both AirPods out, the app will automatically switch to the phone speaker. But, Android might keep on trying to connect to the AirPods because the phone is still connected to them, just the A2DP profile is not connected. The app tries to disconnect the A2DP profile as soon as it detects that Android has connected again if they're not in the ear.

- When renaming your AirPods through the app, you'll need to re-pair them with your phone for the name change to take effect. This is a limitation of how Bluetooth device naming works on Android.

- If you want the AirPods icon and battery status to show in Android Settings app, install the app as a system app by using the root module.

## Supporters

A huge thank you to everyone supporting the project!
- @davdroman
- @tedsalmon
- @wiless
- @SmartMsg
- @lunaroyster
- @ressiwage

## Special thanks
- @tyalie for making the first documentation on the protocol! ([tyalie/AAP-Protocol-Definition](https://github.com/tyalie/AAP-Protocol-Defintion))
- @rithvikvibhu and folks over at lagrangepoint for helping with the hearing aid feature ([gist](https://gist.github.com/rithvikvibhu/45e24bbe5ade30125f152383daf07016))
- @devnoname120 for helping with the first root patch
- @timgromeyer for making the first version of the linux app
- @hackclub for hosting [High Seas](https://highseas.hackclub.com) and [Low Skies](low-skies.hackclub.com)!

## Star History

<a href="https://www.star-history.com/#kavishdevar/librepods&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=kavishdevar/librepods&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=kavishdevar/librepods&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=kavishdevar/librepods&type=date&legend=top-left" />
 </picture>
</a>

# License

LibrePods - AirPods liberated from Apple’s ecosystem
Copyright (C) 2025 LibrePods contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.

All trademarks, logos, and brand names are the property of their respective owners. Use of them does not imply any affiliation with or endorsement by them. All AirPods images, symbols, and the SF Pro font are the property of Apple Inc.
