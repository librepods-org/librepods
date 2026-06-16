>[!IMPORTANT]
Development paused due to lack of time until June 2026 (JEE Advanced). PRs and issues might not be responded to until then.

---

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="./imgs/banner-dark.png" />
  <source media="(prefers-color-scheme: light)" srcset="./imgs/banner.png" />
  <img alt="LibrePods" src="./imgs/banner.png" />
</picture>

<div align="center" style="margin: 20px 0px;">
<a href="https://github.com/kavishdevar/librepods/releases/latest">
  <img src="https://img.shields.io/github/downloads/kavishdevar/librepods/total?label=GitHub%20Downloads" />
</a>
<a href="https://github.com/kavishdevar/librepods/actions/workflows/ci-android.yml">
  <img src="https://github.com/kavishdevar/librepods/actions/workflows/ci-android.yml/badge.svg" />
</a>
<a href="https://github.com/kavishdevar/librepods/actions/workflows/ci-linux-rust.yml">
  <img src="https://github.com/kavishdevar/librepods/actions/workflows/ci-linux-rust.yml/badge.svg" />
</a>
<a href="https://github.com/kavishdevar/librepods/issues">
  <img src="https://img.shields.io/github/issues/kavishdevar/librepods" />  
</a>
<a href="https://discord.gg/HhG4ycVum4">
  <img src="https://img.shields.io/discord/1441416992027574375?logoColor=white&color=5865F2&label=Discord" />
</a>
</div>

# What is LibrePods?

LibrePods allows you to use AirPods features that are exclusive to Apple devices. It implements the proprietary protocol used to exchange data between AirPods and Apple devices, enabling features like changing noise control modes, fast ear detection, accurate battery status, head gestures, conversational awareness, and more on non-Apple platforms.

# Feature availability

| Feature                                                     | Linux | Android |
| ----------------------------------------------------------- | ----- | ------- |
| Changing Listening Mode                                     | ✅     | ✅       |
| Ear detection                                               | ✅     | ✅       |
| Battery status                                              | ✅     | ✅       |
| Renaming AirPods <details><summary>Note for Android</summary>On Android, you need to re-pair your AirPods after renaming them because Android might not use the latest name.</details>                                            | ✅     | ✅       |
| Loud Sound Reduction                                        | 🔴     | ⚪       |
| Head Gestures                                               | ⛔     | ✅       |
| Conversational Awareness                                    | ✅     | ✅       |
| Automatically connect to AirPods                            | ✅     | ✅       |
| Hearing Aid                                                 | 🔴     | ⚪       |
| Transparency Mode customization                             | 🔴     | ⚪       |
| Multi-device connectivity (Bluetooth Multipoint; 2 devices only) | ⚪     | ⚪       |
| <details><summary>Other accessibility configs (click to expand)</summary><ul><li>Press speed</li><li>Press and Hold duration</li><li>Noise Cancellation with single AirPod</li><li>Volume control on swipe</li><li>Volume swipe speed</li></ul></details>       | 🔴     | ✅       |
| <details><summary>Other general configs</summary><ul><li>Press and Hold to cycle between listening modes/invoke digital assistant (invoking digital assistant needs a recent firmware)</li><li>Configure call controls</li><li>Personalized volume</li><li>Loud Sound Reduction (needs <a href="#vendorid-spoofing">VendorID spoofing</a>)</li><li>Microphone side</li><li>Pause media when falling asleep (needs a recent firmware)</li><li>Enable <code>Off listening mode</code> to switch to <code>Off</code></li></ul></details>                   | 🔴     | ✅       |
| [Head-tracked Spatial Audio](#spatial-audio)                | ❓     | ❓       |
| [Heart Rate Monitoring](#heart-rate-monitoring)             | ⛔     | 🔴       |
| [Find My](#find-my)                                         | ❓     | ❓       |
| [High quality two-way audio](#high-quality-two-way-audio)   | 🔴     | 🔴       |

| Symbol | Meaning                                                             |
| ------ | ------------------------------------------------------------------- |
| ✅     | Implemented and works well                                          |
| ⚪     | Needs [VendorID spoofing](#vendorid-spoofing); use at your own risk |
| 🔴     | Not implemented yet; planned                                        |
| ⛔     | Will not be implemented                                             |
| ❓     | Unknown                                                             |

## Find My

The following features related to Find My are planned, but require further RE and might need root on Android:

- Add your AirPods to the Find My network
- Play sound through charging case to find it
- Notify when leaving behind
- Toggle case charging sounds

## Spatial Audio

The app does not currently provide head tracking information to Android for the OS to perform HRTF. This has not been explored completely, and it might need root. 

Spatializing stereo sound is beyond this project's scope and will never be available. Many OEMs have an implementation of their own for this.

## Heart Rate Monitoring (AirPods Pro 3 and later)
This is being worked upon, check the #⁠reverse-engineering channel on the LibrePods Discord server for more information. If it is ever implemented, it will most likely need root on Android.

## High quality two-way audio
On iOS/iPadOS, you can continue using A2DP while AirPods send the audio stream from its microphone over AACP. 

Since this needs deeper integration with audio on Android, it will most likely need root.

# Installation

- [**Android**](/android/README.md)
- [**Linux**](/linux/README.md)

# VendorID Spoofing

Turns out, if you change the VendorID in DID Profile to that of Apple, you get access to several special features!

You can do this on Linux by editing the DeviceID in `/etc/bluetooth/main.conf`. Add this line to the config file `DeviceID = bluetooth:004C:0000:0000`. For android you can enable the `act as Apple device` setting in the app's settings (shown only when Xposed is available and LibrePods module is enabled).

## Multi-device Connectivity

Upto two devices can be simultaneously connected to AirPods, for audio and control both. Seamless connection switching. The same notification shows up on Apple device when Android takes over the AirPods as if it were an Apple device ("Move to iPhone"). Android also shows a popup when the other device takes over.

## Accessibility Settings and Hearing Aid

Accessibility settings like customizing transparency mode (amplification, balance, tone, conversation boost, and ambient noise reduction), and loud sound reduction can be configured.

All hearing aid customizations can be done from Android (linux soon), including setting the audiogram result. The app doesn't provide a way to take a hearing test because it requires much more precision. It is much better to use an already available audiogram result. 

# Protocol and Reverse Engineering

Please refer to the Wireshark dissector plugin by Nojus ([@pabloaul](https://github.com/pabloaul)) for more information on the protocols used: [pabloaul/apple-wireshark](https://github.com/pabloaul/apple-wireshark)

The dissector had not been used in LibrePods for most of the implementation; I had reverse engineered the protocol myself before this dissector was made. But many (future) features including two-way high quality audio and spatial audio would not have been possible without their RE efforts!

# Use of AI

## Android app

These parts of the app were completely AI-generated: 
- Head Gestures - all of it, including logic and the UI
- The offset setup with r2+the xposed module (both versions)
- Troubleshooter and LogCollector

Rest everything- the background service, the Bluetooth manager classes (AACP and ATT), the entire UI, even the smallest components were written manually.

Some parts of the UI components were borrowed from [Kyant0's demo app](https://github.com/Kyant0/AndroidLiquidGlass/tree/master/catalog), which is licensed under [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

## Linux (rewrite)

The `aacp.rs` and the `att.rs` files were translated from Kotlin to Rust with AI. Some parts of the `media_controller.rs` file, mainly the pulse integration, was also AI-generated.

# Supporters

A huge thank you to everyone supporting the project!

<table>
  <tr>
    <td align="center">
      <a href="https://github.com/davdroman">
        <img src="https://github.com/davdroman.png?size=48" width="48" height="48"alt="davdroman"/><br />
        @davdroman
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/tedsalmon">
        <img src="https://github.com/tedsalmon.png?size=48" width="48" height="48"alt="tedsalmon"/><br />
        @tedsalmon
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/wiless">
        <img src="https://github.com/wiless.png?size=48" width="48" height="48"alt="wiless"/><br />
        @wiless
      </a>
    </td>
  </tr>
  <tr>
    <td align="center">
      <a href="https://github.com/SmartMsg">
        <img src="https://github.com/SmartMsg.png?size=48" width="48" height="48"alt="SmartMsg"/><br />
        @SmartMsg
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/lunaroyster">
        <img src="https://github.com/lunaroyster.png?size=48" width="48" height="48"alt="lunaroyster"/><br />
        @lunaroyster
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/ressiwage">
        <img src="https://github.com/ressiwage.png?size=48" width="48" height="48"alt="ressiwage"/><br />
        @ressiwage
      </a>
    </td>
  </tr>
  <tr>
    <td align="center">
      <a href="https://github.com/kkjdroid">
        <img src="https://github.com/kkjdroid.png?size=48" width="48" height="48"alt="kkjdroid"/><br />
        @kkjdroid
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/CitrusJoules">
        <img src="https://github.com/CitrusJoules.png?size=48" width="48" height="48"alt="CitrusJoules"/><br />
        @CitrusJoules
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/DanielReyesDev">
        <img src="https://github.com/DanielReyesDev.png?size=48" width="48" height="48"alt="DanielReyesDev"/><br />
        @DanielReyesDev
      </a>
    </td>
  </tr>
  <tr>
    <td align="center">
      <a href="https://github.com/sumitduster">
        <img src="https://github.com/sumitduster.png?size=48" width="48" height="48"alt="sumitduster"/><br />
        @sumitduster
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/GrifTheDev">
        <img src="https://github.com/GrifTheDev.png?size=48" width="48" height="48"alt="GrifTheDev"/><br />
        @GrifTheDev
      </a>
    </td>
  </tr>
</table>

# Special Thanks
- @tyalie for making the first documentation on the protocol! ([tyalie/AAP-Protocol-Definition](https://github.com/tyalie/AAP-Protocol-Defintion))
- @rithvikvibhu and folks over at lagrangepoint for helping with the hearing aid feature ([gist](https://gist.github.com/rithvikvibhu/45e24bbe5ade30125f152383daf07016))
- @devnoname120 for helping with the first root patch
- @timgromeyer for making the first version of the linux app
- @hackclub for hosting [High Seas](https://highseas.hackclub.com) and [Low Skies](https://low-skies.hackclub.com)!
- Of course, everyone who has contributed to the project in any way, including by testing, sharing feedback, or just showing interest!

# Alternates for other platforms:
- CAPod - A companion app for AirPods on Android. ([play store](https://play.google.com/store/apps/details?id=eu.darken.capod) | [source code](https://github.com/d4rken-org/capod)). Use this if you're using Android version 16 QPR3 or below and are not rooted.
- MagicPods for Steam Deck ([website](https://magicpods.app/steamdeck/))
- MagicPods - if you're looking for "LibrePods for Windows"  ([ms store](https://apps.microsoft.com/store/detail/9P6SKKFKSHKM) [installer](https://magicpods.app/installer/MagicPods.appinstaller) | [website](https://magicpods.app/))

# Star History

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

