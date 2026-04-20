# AAP Definitions (As per AirPods Pro 2 (USB-C) Firmware 7A305)

AAP runs on top of L2CAP, with a PSM of 0x1001 or 4097.

# Handshake
This packet is necessary to establish a connection with the AirPods. Or else, the AirPods will not respond to any packets.

```plaintext
00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00
```

# Setting specific features for AirPods Pro 2

> *may work for airpods 4 anc also, not tested*

Since apple likes to wall off some features behind specific OS versions, and apple silicon devices, some packets are necessary to enable these features.

I captured the following packet only accidentally, because Apple being Apple decided to hide *this* and *the handshake* from packetlogger, but sometimes it shows up.

*Captured using PacketLogger on an Intel Mac running macOS Sequoia 15.0.1*
```plaintext
04 00 04 00 4d 00 ff 00 00 00 00 00 00 00
```

This packet enables conversational awareness when playing audio. (CA works without this packet only when no audio is playing)

It also enables the Adaptive Transparency feature. (We can set Adaptive Transparency, but it doesn't respond with the same packet See [Noise Cancellation](#changing-noise-control))

# Requesting notifications

This packet is necessary to receive notifications from the AirPods like ear detection, noise control mode, conversational awareness, battery status, etc.

*Captured using PacketLogger on an Intel Mac running macOS Sequoia 15.0.1*
```plaintext
04 00 04 00 0F 00 FF FF FE FF
```

This packet also works.

```plaintext
04 00 04 00 0F 00 FF FF FF FF
```

# Notifications

## Battery

AirPods occasionally send battery status packets. The packet format is as follows:

```plaintext
04 00 04 00 04 00 [battery count] ([component] 01 [level] [status] 01) times the battery count
```

| Components | Byte value |
|------------|------------|
| Case       | 08         |
| Left       | 04         |
| Right      | 02         |

| Status       | Byte value |
|------------- |------------|
| Unknown      | 00         |
| Charging     | 01         |
| Discharging  | 02         |
| Disconnected | 04         |


Example packet from AirPods Pro 2

```plaintext
04 00 04 00 04 00 03 02 01 64 02 01 04 01 63 01 01 08 01 11 02 01
```

| Byte      | Interpretation                     |
|-----------|------------------------------------|
| 7th byte  | Battery Count - 3                  |
| 8th byte  | Battery type - Left                |
| 9th byte  | Spacer, value = 0x01               |
| 10th byte | Battery level 100%                 |
| 11th byte | Battery status - Discharging       |
| 12th byte | Battery component end value = 0x01 |
| 13th byte | Battery type - Right               |
| 14th byte | Spacer, value = 0x01               |
| 15th byte | Battery level 99%                  |
| 16th byte | Battery status - Charging          |
| 17th byte | Battery component end value = 0x01 |
| 18th byte | Battery type - Case                |
| 19th byte | Spacer, value = 0x01               |
| 20th byte | Battery level 17%                  |
| 21st byte | Battery status - Discharging       |
| 22nd byte | Battery component end value = 0x01 |

## Noise Control

The AirPods Pro 2 send noise control packets when the noise control mode is changed (either by a stem long press or by the connected device, see [Changing noise control](#changing-noise-control)). The packet format is as follows:

```plaintext
04 00 04 00 09 00 0D [mode] 00 00 00
```

| Noise Control Mode    | Byte value |
|-----------------------|------------|
| Off                   | 01         |
| Noise Cancellation    | 02         |
| Transparency          | 03         |
| Adaptive Transparency | 04         |

## Ear Detection

AirPods send ear detection packets when the ear detection status changes. The packet format is as follows:
```plaintext
04 00 04 00 06 00 [primary pod] [secondary pod]
```

If primary is removed, mic will be changed and the secondary will be the new primary, so the primary will be the one in the ear, and the packet will be sent again.

| Pod Status | Byte value |
|------------|------------|
| In Ear     | 00         |
| Out of Ear | 01         |
| In Case    | 02         |

## Conversational Awareness

AirPods send conversational awareness packets when the person wearing them starts speaking. The packet format is as follows:

```plaintext
04 00 04 00 4B 00 02 00 01 [level]
```

| Level Byte Value    | Meaning                                                 |
|---------------------|---------------------------------------------------------|
| 01/02               | Person Started Speaking; greatly reduce volume          |
| 03                  | Person Stopped Speaking; increase volume back to normal |
| Intermediate values | Intermediate volume levels                              |
| 08/09               | Normal Volume                                           |
### Reading Conversational Awareness State

After requesting notifications, the AirPods send a packet indicating the current state of Conversational Awareness (CA). This packet is only sent once after notifications are requested, not when the CA state is changed.

The packet format is:

```plaintext
04 00 04 00 09 00 28 [status] 00 00 00
```

- `[status]` is a single byte at offset 7 (zero-based), immediately after the header.
    - `0x01` — Conversational Awareness is **enabled**
    - `0x02` — Conversational Awareness is **disabled**
    - Any other value — Unknown/undetermined state

**Example:**
```plaintext
04 00 04 00 09 00 28 01 00 00 00
```
Here, `01` at the 8th byte (offset 7) means CA is enabled.

## Metadata

This packet contains device information like name, model number, etc. The packet format is:

```plaintext
04 00 04 00 1d [strings...]
```

The strings are null-terminated UTF-8 strings in the following order:

1. Bluetooth advertising name (varies in length)
2. Model number 
3. Manufacturer
4. Serial number
5. Firmware version
6. Firmware version 2 (the exact same as before??)
7. Software version   (1.0.0 why would we need it?)
8. App identifier     (com.apple.accessory.updater.app.71 what?)
9. Serial number 1
10. Serial number 2
11. Unknown numeric value
12. Encrypted data
13. Additional encrypted data

Example packet:
```plaintext
040004001d0002d5000400416972506f64732050726f004133303438004170706c6520496e632e0051584e524848595850360036312e313836383034303030323030303030302e323731330036312e313836383034303030323030303030302e3237313300312e302e3000636f6d2e6170706c652e6163636573736f72792e757064617465722e6170702e3731004859394c5432454632364a59004833504c5748444a32364b3000363335373533360089312a6567a5400f84a3ca234947efd40b90d78436ae5946748d70273e66066a2589300035333935303630363400```

The packet contains device identification and version information followed by some encrypted data whose format is not known.
```

# Writing to the AirPods

## Changing Noise Control

We can send a packet to change the noise control mode. The packet format is as follows:

```plaintext
04 00 04 00 09 00 0D [mode] 00 00 00
```

| Noise Control Mode    | Byte value |
|-----------------------|------------|
| Off                   | 01         |
| Noise Cancellation    | 02         |
| Transparency          | 03         |
| Adaptive Transparency | 04         |

The airpods will respond with the same packet after the mode has been changed.

> But if your airpods support Adaptive Transparency, and you haven't sent that [special packet](#setting-specific-features-for-airpods-pro-2) to enable it, the airpods will respond with the same packet but with a different mode (like 0x02).

## Renaming AirPods

We can send a packet to rename the AirPods. The packet format is as follows:

```plaintext
04 00 04 00 1A 00 01 [size] 00 [name]
```

## Toggle case charging sounds

> *This feature is only for cases with a speaker, i.e. the AirPods Pro 2 and the new AirPods 4. Tested only on AirPods Pro 2*

We can send a packet to toggle if sounds should be played when the case is connected to a charger. The packet format is as follows:

```plaintext
12 3A 00 01 00 08 [setting]
```

| Byte Value | Sound |
|------------|-------|
| 00         | On    |
| 01         | Off   |

## Toggle Conversational Awareness

> *This feature is only for AirPods Pro 2 and the new AirPods 4 with ANC. Tested only on AirPods Pro 2*

We can send a packet to toggle Conversational Awareness. If enabled, the AirPods will switch to Transparency mode when the person wearing them starts speaking (and sends packet for notifying the device to reduce volume). The packet format is as follows:

```plaintext
04 00 04 00 09 00 28 [setting] 00 00 00
```

| Byte Value | C.A. |
|------------|------|
| 01         | On   |
| 02         | Off  |

## Adaptive Audio Noise

> *This feature is only for AirPods Pro 2 and the new AirPods 4 with ANC. Tested only on AirPods Pro 2*

The new firmware `7A305` for app2 has a new feature called Adaptive Audio Noise. This allows us to control how much noise is passed through the AirPods when the noise control mode is set to Adaptive. The packet format is as follows:

```plaintext
04 00 04 00 09 00 2E [level] 00 00 00
```

The level can be any value between 0 and 100, 0 to allow maximum noise (i.e. minimum noise filtering), and 100 to filter out more noise.

> This feature is only effective when the noise control mode is set to Adaptive.

*I find it quite funny how I have greater control over the noise control on the AirPods on non-Apple devices than on Apple devices, becuase on Apple Devices, there are just 3 options More Noise (0), Midway through (50), and Less Noise (100), but here I can set any value between 0 and 100.*

## Accessiblity Settings

## Headphone Accomodation
```
04 00 04 00 53 00 84 00 02 02 [Phone] [Media]
[EQ1][EQ2][EQ3][EQ4][EQ5][EQ6][EQ7][EQ8]
duplicated thrice for some reason
```

| Data                | Type          | Value range                 |
|---------------------|---------------|-----------------------------|
| Phone               | Decimal       | 1 (Enabled) or 2 (Disabled) |
| Media               | Decimal       | 1 (Enabled) or 2 (Disabled) |
| EQ                  | Little Endian | 0 to 100                    |

## Customize Transparency mode

```
12 18 00 [enabled]
<left bud>
[EQ1][EQ2][EQ3][EQ4][EQ5][EQ6][EQ7][EQ8]
[Amplification]
[Tone]
[Conversation Boost]
[Ambient Noise Reduction]
<repeat for right bud>
```


All values are formatted as IEEE 754 floats in little endian order.
| Data                    | Type          | Range |
|-------------------------|---------------|-------|
| Enabled                 | IEEE754 Float | 0/1   |
| EQ                      | IEEE754 Float | 0-100 |
| Amplification           | IEEE754 Float | 0-2   |
| Tone                    | IEEE754 Float | 0-2   |
| Conversation Boost      | IEEE754 Float | 0/1   |
| Ambient Noise Reduction | IEEE754 Float | 0-1   |
| Ambient Noise Reduction | IEEE754 Float | 0-1   |

> [!IMPORTANT]
> Also send the [Headphone Accomodation](#headphone-accomodation) after this.


## Configure Stem Long Press

I have noted all the packets sent to configure what the press and hold of the steam should do. The packets sent are specific to the current state. And are probably overwritten everytime the AirPods are connected to a new (apple) device that is not synced with icloud (i think)... So, for non-Apple devices too, the configuration needs to be stored and overwritten everytime the AirPods are connected to the device. That is the only way to keep the configuration.

This is also the only way to control the configuration as the previous state needs to be known, and then the new state can be set. 

The packets sent (based on the previous states) are as follows:

<details>
<summary>Toggling Adaptive</summary>

<code>04 00 04 00 09 00 1A 0B 00 00 00</code> - Turns on Adaptive from O and ANC  
<code>04 00 04 00 09 00 1A 0D 00 00 00</code> - Turns on Adaptive from O and T  
<code>04 00 04 00 09 00 1A 0E 00 00 00</code> - Turns on Adaptive from T and ANC  
<code>04 00 04 00 09 00 1A 0F 00 00 00</code> - Turns on Adaptive from O, T, ANC  

<code>04 00 04 00 09 00 1A 03 00 00 00</code> - Turns off Adaptive from O and ANC (and Adaptive)  
<code>04 00 04 00 09 00 1A 05 00 00 00</code> - Turns off Adaptive from O and T (and Adaptive)  
<code>04 00 04 00 09 00 1A 06 00 00 00</code> - Turns off Adaptive from T and ANC (and Adaptive)  
<code>04 00 04 00 09 00 1A 07 00 00 00</code> - Turns off Adaptive from O, T, ANC (and Adaptive)  

</details>

<details>
<summary>Toggling Transparency</summary>

<code>04 00 04 00 09 00 1A 07 00 00 00</code> - Turns on Transparency from O and ANC  
<code>04 00 04 00 09 00 1A 0D 00 00 00</code> - Turns on Transparency from O and Adaptive  
<code>04 00 04 00 09 00 1A 0E 00 00 00</code> - Turns on Transparency from Adaptive, and ANC  
<code>04 00 04 00 09 00 1A 0F 00 00 00</code> - Turns on Transparency from O and Adaptive and ANC  

<code>04 00 04 00 09 00 1A 03 00 00 00</code> - Turns off Transparency from O and ANC (and Transparency)  
<code>04 00 04 00 09 00 1A 09 00 00 00</code> - Turns off Transparency from O and Adaptive (and Transparency)  
<code>04 00 04 00 09 00 1A 0A 00 00 00</code> - Turns off Transparency from Adaptive, and ANC (and Transparency)  
<code>04 00 04 00 09 00 1A 0B 00 00 00</code> - Turns off Transparency from O and Adaptive and ANC (and Transparency)  

</details>

<details>
<summary>Toggling ANC</summary>

<code>04 00 04 00 09 00 1A 07 00 00 00</code> - Turns on ANC from O, and Transparency  
<code>04 00 04 00 09 00 1A 0B 00 00 00</code> - Turns on ANC from O, and Adaptive  
<code>04 00 04 00 09 00 1A 0E 00 00 00</code> - Turns on ANC from Adaptive, and Transparency  
<code>04 00 04 00 09 00 1A 0F 00 00 00</code> - Turns on ANC from O and Adaptive and Transparency  

<code>04 00 04 00 09 00 1A 05 00 00 00</code> - Turns off ANC from O and Transparency (and ANC)  
<code>04 00 04 00 09 00 1A 09 00 00 00</code> - Turns off ANC from O and Adaptive (and ANC)  
<code>04 00 04 00 09 00 1A 0C 00 00 00</code> - Turns off ANC from Adaptive, and Transparency (and ANC)  
<code>04 00 04 00 09 00 1A 0D 00 00 00</code> - Turns off ANC from O and Adaptive and Transparency (and ANC)  

</details>

<details>
<summary>Toggling O</summary>

<code>04 00 04 00 09 00 1A 07 00 00 00</code> - Turns on O from Transparency, and ANC  
<code>04 00 04 00 09 00 1A 0B 00 00 00</code> - Turns on O from Adaptive, and ANC  
<code>04 00 04 00 09 00 1A 0D 00 00 00</code> - Turns on O from Transparency, and Adaptive  
<code>04 00 04 00 09 00 1A 0F 00 00 00</code> - Turns on O from Transparency, and Adaptive, and ANC  

<code>04 00 04 00 09 00 1A 06 00 00 00</code> - Turns off O from Transparency, and ANC (and O)  
<code>04 00 04 00 09 00 1A 0A 00 00 00</code> - Turns off O from Adaptive, and ANC (and O)  
<code>04 00 04 00 09 00 1A 0C 00 00 00</code> - Turns off O from Transparency, and Adaptive (and O)  
<code>04 00 04 00 09 00 1A 0E 00 00 00</code> - Turns off O from Transparency, and Adaptive, and ANC (and O)  

</details>

> *i do hate apple for not hardcoding these, like there are literally only 4^2 - ${\binom{4}{1}}$ - $\binom{4}{2}$*

# Head Tracking

## Start Tracking

This packet initiates head tracking. When sent, the AirPods begin streaming head tracking data (e.g. orientation and acceleration) for live plotting and analysis.

```plaintext
04 00 04 00 17 00 00 00 10 00 10 00 08 A1 02 42 0B 08 0E 10 02 1A 05 01 40 9C 00 00
```

## Stop Tracking

This packet stops the head tracking data stream.

```plaintext
04 00 04 00 17 00 00 00 10 00 11 00 08 7E 10 02 42 0B 08 4E 10 02 1A 05 01 00 00 00 00
```
## Received Head Tracking Sensor Data

Once tracking is active, the AirPods stream sensor packets with the following common structure:
  
| Field                    | Offset | Length (bytes) |
|--------------------------|--------|----------------|
| orientation 1            | 43     | 2              |
| orientation 2            | 45     | 2              |
| orientation 3            | 47     | 2              |
| Horizontal Acceleration  | 51     | 2              |
| Vertical Acceleration    | 53     | 2              |
