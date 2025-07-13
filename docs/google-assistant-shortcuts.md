# Google Assistant Shortcuts for LibrePods

LibrePods now supports Google Assistant shortcuts to control your AirPods features through voice commands.

## Available Commands

### Noise Control Modes
- **"Hey Google, turn on noise cancellation"** - Activates noise cancellation mode
- **"Hey Google, turn on transparency mode"** - Activates transparency mode  
- **"Hey Google, turn on adaptive transparency"** - Activates adaptive transparency mode
- **"Hey Google, turn off noise control"** - Turns off noise control (off mode)

### Conversational Awareness
- **"Hey Google, turn on conversational awareness"** - Enables conversational awareness
- **"Hey Google, turn off conversational awareness"** - Disables conversational awareness

## Setup Requirements

1. **LibrePods Service Running**: The LibrePods service must be active
2. **AirPods Connected**: Your AirPods must be connected to the device
3. **Google Assistant**: Google Assistant must be enabled and configured on your device

## How It Works

When you use one of the voice commands:

1. Google Assistant recognizes the command and matches it to a LibrePods shortcut
2. The shortcut launches the `ShortcutHandlerActivity` 
3. The activity communicates with the `AirPodsService` to send the appropriate command
4. Your AirPods change to the requested mode
5. A confirmation toast message is shown

## Technical Implementation

The shortcuts are implemented using Android's shortcut framework rather than Google Assistant App Actions, making them compatible with sideloaded apps that aren't on the Google Play Store.

### Files Involved:
- `res/xml/shortcuts.xml` - Defines the available shortcuts
- `ShortcutHandlerActivity.kt` - Handles shortcut intents
- `AndroidManifest.xml` - Registers the shortcuts and handler activity

### Shortcut Actions:
- `me.kavishdevar.librepods.SHORTCUT_NOISE_CONTROL` - For noise control mode changes
- `me.kavishdevar.librepods.SHORTCUT_CONVERSATIONAL_AWARENESS` - For conversational awareness toggle

## Troubleshooting

If shortcuts don't work:

1. Ensure LibrePods service is running in the background
2. Check that your AirPods are properly connected
3. Verify Google Assistant can access app shortcuts
4. Try saying the exact command phrases listed above
5. Check the device logs for any error messages from `ShortcutHandler`