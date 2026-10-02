# English / Korean localization

## Changes

- App settings show one Language row with the current choice. Tapping it opens
  a single-selection dialog; selecting a language applies it immediately.
- System default, English (Eng), and Korean are available. Android's
  `LocaleManager` persists the choice and shares it with system app-language settings.
- Navigation state survives activity recreation so changing language keeps the
  user on the settings screen.
- Korean terminology was reviewed against the controls: noise control, the Off
  option, conversational-awareness volume, swipe intervals, and reconnection.
- User-facing errors, troubleshooting steps, permission explanations, confirmation
  dialogs, equalizer labels, gesture results, and the existing privacy text use
  string resources. Diagnostics after the localized “Details” label, exception
  text supplied by Android, filenames, and exported logs retain their original text.
- Log counts use plural resources. Display dates follow the active locale;
  machine-readable log filenames retain their fixed format.
- Notification channel names and active service/battery notifications refresh
  when the app locale changes. The disabled connection-error notification stays disabled.

## Automated checks

From `android`, run:

```powershell
.\tools\check-localization.ps1
.\gradlew.bat :app:assembleFossDebug --console=plain
```

The resource check validates every translatable English string/plural against
Korean, including duplicate keys, missing/empty translations and format arguments.
The APK build compiles resources and Kotlin and packages native libraries.

## Device checks before merging

These require a device or emulator and are not implied by a successful build.

| Scenario | Expected result |
| --- | --- |
| Language row in both themes, without premium or connected AirPods | One accessible row showing the current choice |
| Open picker; tap current language, cancel, outside, or Back | Dismiss without changing the locale |
| Select Korean, then English | UI changes immediately; settings screen remains open |
| Restart app; change language from Android app settings | Selected language remains consistent with system settings |
| Choose System default, then change system language | App follows the system locale |
| Large font and TalkBack | Language rows remain readable and announce selected radio state |
| Save/read/delete log failure | Localized explanation; diagnostic details remain intact |
| Zero, one, multiple logs | Correct count and plural wording in each language |
| Switch language while background service runs | Notification labels refresh; connection stays intact |
| Denied call permission / connection timeout | Appropriate localized error; no false success message |

The repository's SDK/NDK compatibility changes predate this localization work;
keep them out of an upstream localization-only PR if the target branch does not
already contain them. No PR is published by these local checks.
