# AirPods 5 on Android

Capability review: 2026-09-20. Model capabilities determine the settings menu;
advertised Apple features do not by themselves prove Android protocol support.

| Setting | AirPods 5 | AirPods 5 with Wireless Charging Case |
| --- | --- | --- |
| ANC, transparency, adaptive audio, off | Yes | Yes |
| Personalized volume and conversation awareness | Yes | Yes |
| Press-and-hold settings and head gestures | Yes | Yes |
| Pause media when falling asleep | Yes | Yes |
| Three-band custom EQ | Yes | Yes |
| Volume swipe | No | Yes |
| Pro custom transparency, loud sound reduction, hearing aid, hearing protection, heart rate | No | No |

The Android implementation uses existing AACP controls for these settings. Sleep
detection was missing from the AirPods 5 capability lists and is now exposed.
Custom EQ uses AACP opcode 0x63; it is separate from automatic Adaptive EQ.
These controls still require functional testing on the connected firmware.

Apple identity emulation changes the phone's Bluetooth identity. It does not add
Pro capabilities or implement Siri, Apple Live Translation, or Find My on Android.
The separate connection fix skips the unused ATT channel for known models
without hearing features, including AirPods 5 once these models are registered. Changing emulation requires a Bluetooth
restart to reload the hook.

Unknown device information no longer defaults to Pro 2/Pro 3. The About section
shows the recognized generation and variant. Main-screen and connection-popup
artwork is shared with AirPods 4 (see [artwork sources](airpods-4-artwork.md)) and
is not used to determine capabilities.

## Sources

- [Apple AirPods 5 specifications](https://www.apple.com/airpods-5/specs/)
- [Listening mode settings](https://support.apple.com/guide/airpods/adjust-listening-mode-settings-dev6d977ff21/27/web/27)
- [Sleep detection and custom EQ](https://support.apple.com/en-is/108764)
- [Pro custom transparency](https://support.apple.com/guide/airpods/use-and-customize-transparency-mode-dev966f5f818/27/web/27)

## Device acceptance checks

For A3441, confirm the About section identifies the wireless charging case variant,
sleep detection and EQ are visible, and Accessibility shows volume swipe but no
custom transparency. Confirm the same model capabilities with Apple identity
emulation enabled and disabled. Test setting changes and readback, including EQ
and adaptive strength, before treating those controls as verified on this firmware.
Retest playback stability after installing the APK; menu correctness alone does
not establish Bluetooth stability or successful setting application.
