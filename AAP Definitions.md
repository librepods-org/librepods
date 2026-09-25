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

| Components      | Byte value |
|-----------------|------------|
| Headphone*      | 01         |
| Case            | 08         |
| Left            | 04         |
| Right           | 02         |

*The `Headphone` component only exists on over-ear models (AirPods Max). On
earbuds (AirPods Pro/regular) the slot may still be reported with a **level of
`0xFF` (255)**, which means *absent* — treat it as "no such component" and skip
it (otherwise it renders as a bogus "255%").

| Status               | Byte value |
|----------------------|------------|
| Unknown              | 00         |
| Charging             | 01         |
| Discharging          | 02         |
| Disconnected         | 04         |
| Charging (in case)** | 05         |

**`0x05` is reported for an earbud that is **charging inside the case** (the
iPhone shows it charging with its current level). Discovered empirically — it was
previously only handled in the app's parser (`aacp.rs`), not documented here nor
in the Windows daemon's separate parser. Treat `0x01` and `0x05` both as
*charging*.


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

AirPods send conversational awareness packets when the person wearing them start speaking. The packet format is as follows:

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

I have noted all the packets sent to configure what the press and hold of the steam should do. The packets sent are specific to the current state. And are probably overwritten everytime the AirPods are connected to a new (apple) device that is not synced with icloud (i think)... So, for non-Apple device too, the configuration needs to be stored and overwritten everytime the AirPods are connected to the device. That is the only way to keep the configuration.

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

# Starting and Stopping Sensor Streams

Captured from **iOS 26.5.2 ↔ AirPods Pro 3 (firmware 8B41)** with `idevicebtlogger`,
across three sessions. See `windows/docs/aap-packet-discovery.md` for the method.

Sensor streams are started and stopped with the **same `0x17` … `42 0B` frame family as Head
Tracking above** — not with a different mechanism. The frame carries a stream id and a
sampling period, and **a period of zero is the stop**:

```plaintext
04 00 04 00 17 00 00 00 10 00 10 00 08 98 01 42 0b 08 13 10 02 1a 05 01 40 42 0f 00
                              ^^^^^ ^^^^^^^^       ^^^^^          ^^ ^^^^^^^^^^^
                              len   seq varint     stream id      md period µs LE
```

| Field | Meaning |
|---|---|
| length | little-endian; the **real payload byte count**, so it moves with the form and the varint width |
| seq | varint, increments per control frame |
| stream id | `08 <id>` inside the `42 0B` block |
| mode | `1`, `2` or `4` — meaning unresolved |
| period | little-endian u32, **microseconds**; `0` = stop the stream |

## Two forms of the frame

**Mixing them produces a packet that appears in no capture.** Across 24 observed control
frames the correlation is exact, with no exceptions:

| Form | Between the sequence and `42 0B` | Stream id | Length seen |
|---|---|---|---|
| **A** | nothing | bare: `0x10`, `0x12`, `0x13` | `0x10` |
| **B** | `10 02` | bit `0x40` set: `0x50`, `0x52`, `0x53` | `0x11`, `0x12` |

The `10 02` field and the `0x40` bit always travel together. Form B also matches the Head
Tracking stop frame documented above (`08 4E`, type 14 with the bit set).

**Which form appears depends on when the capture started.** Three captures that began
mid-session show only form B. The one capture that recorded a connection from scratch shows
form A for the stream it starts, alongside some form B traffic — but never `0x53`. So a client
establishing its own session should send **form A with the bare data type**.

Observed stream ids:

| Stream id | Period sent | Rate | Carries |
|---|---|---|---|
| `0x13` / `0x53` | `1000000` | 1 Hz | **heart rate** (data type 19) |
| `0x10` / `0x50` | `20000` | 50 Hz | raw PPG (data type 16) |
| `0x12` / `0x52` | `200` | — | the worn-state sensor (data type 18) |

## Worked example — a full heart-rate session

From the capture that recorded the connection from scratch, one clock:

```plaintext
t=364.35  →  17 … 08 13 … period 1000000        start heart rate at 1 Hz
t=364.39  →  17 … 08 10 … period 20000          start raw PPG at 50 Hz
t=365.92  ←  first heart-rate frame                        (1.57 s after the start frame)
   …
t=438.15  →  17 … 08 10 … period 0              stop raw PPG
t=472.02  ←  heart-rate frames still arriving
```

107 heart-rate frames at 1 Hz. Note the stream **outlives the workout**: raw PPG was stopped
34 s before the last heart-rate frame, and iOS had not yet sent the heart-rate stop. A client
must not assume the stream ends when the user ends the activity.

## Notes for `windows/daemon/src/aap.rs`

The original `HR_START` / `HR_STOP` constants were **form A and structurally correct** — same
length, same absent `10 02`, same bare `08 13`, same period. Only the sequence varint differed,
which is expected. An intermediate revision rewrote them as form B on the strength of the
mid-session captures; that was wrong and has been reverted.

Reported symptom that prompted the recheck, on a daemon sending form B into a freshly
established session: raw PPG came up at 50 Hz, heart rate never did.

## Opcode 0x44

Distinct from the above and **not** the mechanism that starts streams. Framing is settled:

```plaintext
04 00 04 00 44 00 04 00 02 00 03 07
            ^^^^^ ^^^^^ ^^^^^^^^^^^
            op    len   payload
```

The length field is confirmed by a 14-byte variant:

```plaintext
04 00 04 00 44 00 0e 00 03 00 02 01 00 00 23 0c 77 6a 00 00 00 00
```

**Payload semantics remain unresolved.** In the 4-byte form the first three bytes were
`02 00 03` in every occurrence across all three captures and only the trailing byte varied
(`01`, `02`, `06`, `07`). It is *not* a sensor id list: `02 00 03 07` occurs without any stream
starting, and `02 00 03 01` / `02 00 03 06` start nothing at all. It does consistently appear
tens of milliseconds *before* the `0x17` control frames at a session change — 20 ms before in
the worked example above — so it reads as session or configuration signalling that brackets a
stream change rather than causing it.

Observed sensors (`field 2` of the `0x17` protobuf):

| Sensor id | Data type | Rate | Notes |
|---|---|---|---|
| 3 | 16 | ~50 Hz | raw PPG samples |
| 3 | 19 | 1 Hz | **heart rate** (see below) |
| 7 | 18 | ~5 Hz | **tracks the worn state** — see below |
| 1, 2 | — | bursty | seen only around reconnection and case transitions |

### Sensor 7 follows the worn state, not audio playback

Worth recording as a method note, because the two captures differ in exactly the confounding
variable and it would otherwise be easy to get this wrong.

| | audio playing | worn | sensor 7 streaming |
|---|---|---|---|
| Capture 1 (workout) | no | entire 93 s — zero ear-detection events | **0.0 → 93.0 s**, i.e. throughout |
| Capture 2 (case) | yes, until ~52 s | until 53.06 s | **0.0 → 52.8 s** |

In capture 2 alone, sensor 7 stopping looks like it tracks playback — the music was stopped
immediately before the buds were removed, so the two events are 260 ms apart and
indistinguishable. Capture 1 separates them: **no audio played at any point, yet sensor 7
streamed for the full 93 s while the buds stayed in the ears.**

So sensor 7 is tied to the buds being worn, not to playback. Its last packet precedes the
first ear-detection state change by 260 ms, which fits a motion or proximity sensor reacting
before the in-ear determination is published.

## Received Heart Rate Data

Sensor 3, protobuf field `0x3a`, inner type `19` (`0x13`), one packet per second:

```plaintext
04 00 04 00 17 00 00 00 10 00 <len> 00 08 <seq> 10 03 3a <n> 08 13 1a 12 01 5D E1 07 00 02 …
                                                            ^^^^^          ^^ ^^ ^^    ^^
                                                            type 19       bpm cf sq  state
                                                                          93 225  7  locked
```

`1a 12` introduces an **18-byte payload**. Offsets below are relative to that payload,
matching `HEART_RATE_BPM_OFFSET` / `HEART_RATE_STATUS_TAIL_OFFSET` in
`windows/daemon/src/hr.rs`:

| Field | Offset | Length | Meaning |
|---|---|---|---|
| subtype | 0 | 1 | `01` |
| **heart rate** | 1 | 1 | BPM, unsigned, direct value |
| confidence | 2 | 1 | `20` while settling, rises to ~`236` once locked |
| counter | 3 | 1 | increments by 1 per reading (confirms 1 Hz) |
| state | 5 | 1 | `1` = acquiring, `2` = locked |
| timestamp | 6 | 6 | little-endian |
| status tail | 15 | 3 | see below |

**The first readings must be discarded.** In the reference capture the sequence opened
169 → 136 → 98 → 93 BPM with `confidence = 20` and `state = 1`, then settled at 91 BPM the
moment `state` flipped to `2` and confidence jumped to 189 — four readings, ~4 s from stream
start. The remaining 58 readings spanned 86–102 BPM, mean 93.1, tracking a plausible curve
for light activity.

The existing status-tail filter in `hr.rs` already does exactly this job. Tails observed:

| Tail | n | In `KNOWN_HEART_RATE_STATUS_TAILS` | `state` |
|---|---|---|---|
| `10 00 00` | 58 | yes | 2 (locked) |
| `10 02 81` | 3 | no | 1 (acquiring) |
| `10 82 81` | 1 | no | 1 (acquiring) |

So the decoder accepted 58/62 and rejected precisely the four settling readings — the tail
filter and a `state == 2` test agree exactly on this capture. `10 02 81` / `10 82 81` are new
variants of the known `20 02 80` / `20 82 80` pair; they should **not** be added to the accept
list, since they mark unlocked readings.

# Case and Charging Transitions

Second capture, same rig (iOS 26.5.2 ↔ AirPods Pro 3, fw 8B41). Flow: **worn with music
playing** → playback stopped → removed from ears → placed in the open case → case
interaction → lid closed → lid reopened → idle. 176 s, 810 AAP packets.

The music phase was not part of the intended test, and playback stopping happened to coincide
with the buds being removed — see the sensor 7 note below for why that nearly produced a wrong
conclusion.

## Charging status: what selects `0x01` versus `0x05`

`## Battery` above already documents `0x05` as *charging (in case)* and advises treating it
and `0x01` alike. This capture supports that advice and adds why it is needed: the two are not
alternative encodings chosen by firmware or model, they are **consecutive states of the same
charging session**, so an implementation that handles only one of them will work for part of
the time and then stop working.

| t (s) | observation |
|---|---|
| 13.1 | baseline — both buds `not-charging` (`0x02`), case `disconnected` (`0x04`) |
| 78.1 | first bud enters case → **`0x05` charging-in-case**, immediately |
| 79.0 | case starts reporting a real level (one transient `0xFF` frame first) |
| 80.3 | second bud enters case → **`0x05`** |
| 106.8 | **both buds flip to `0x01` charging**, simultaneously, ~26 s after insertion |
| 145.9 | both levels have risen by 1 % — charging did occur |

**`0x05` is not a mandatory precursor to `0x01`.** A controlled follow-up refuted the obvious
reading of the table above. One bud was seated in the case with the lid open while the other
stayed in an ear as a control, and nothing was touched for 240 s:

| t (s) | observation |
|---|---|
| 94.2 | bud seated → **`0x01` charging immediately**, no `0x05` at any point; case reporting 60 % |
| 255.6 | cased bud 80 % → 81 %, still `0x01` |
| 305.5 | 81 % → 82 %, still `0x01` |
| 334.5 | worn control bud 79 % → 78 %, `not-charging` throughout, as expected |

So the transition is **not driven by elapsed time** — 240 s hands-off produced no state change
at all — and a bud can report `0x01` from the instant it is seated. Charging genuinely
occurred throughout while `0x01` was reported.

A second controlled run — **both** buds seated, lid open, untouched for 162 s — also produced
`0x01` from the instant each bud was seated and **never once reported `0x05`**. That kills the
number-of-buds explanation.

Across four captures:

| Capture | Battery packets | Components reporting `0x05` |
|---|---|---|
| Workout | 1 | 0 |
| Case — buds seated, **case and lid handled** | 15 | **12** |
| Battery — one bud, hands-off 240 s | 7 | 0 |
| Battery — both buds, hands-off 162 s | 10 | 0 |

**Seating buds in the case does not by itself produce `0x05`.** Three hypotheses are now
refuted: it is not elapsed time (240 s of observation, no change), not the number of buds in
the case, and not whether the case is detected — a bud reported `0x01` while the case was
still sending `255 %` / `disconnected`. The only capture that produced `0x05` is also the only
one in which the case and its lid were physically interacted with, which makes that the
remaining candidate, untested.

The practical consequence is unchanged and is what `## Battery` already advises — **treat
`0x01` and `0x05` both as charging.** The value that appears is not predictable from elapsed
time or from the case's own reported state.

## Ear detection: two values beyond the documented three

`## Ear Detection` above lists `00` in-ear, `01` out-of-ear and `02` in-case. Removing the buds
and casing them produced this sequence of `(primary, secondary)` pairs:

```plaintext
00 01  →  01 01  →  01 04  →  04 04  →  04 01  →  01 01  →  02 01  →  01 02  →  02 02
```

and later, with one bud powered down in the closed case, `02 03`.

So **`03` and `04` are the two values not in that table**. `04` appears only while a bud is in
motion between resting states and never at rest, so it reads as a transitional value alongside
the documented `01`. `03` was seen at rest, on the bud that had dropped off the link — it
behaves as *disconnected* rather than as a position.

# Opcode Names (from the apple-wireshark dissector)

The Lua dissectors at [github.com/pabloaul/apple-wireshark](https://github.com/pabloaul/apple-wireshark)
name most of the AACP message types. Installing them (`plugins/` into the Wireshark plugin
directory) makes Wireshark label these automatically, and its `rtbuddy.proto` gives the
SensorDataWX protobuf schema — see the sensor-stream section above.

Validated against three captures here: the RTBuddy length field matched the real payload on
100% of 4073 frames.

| Opcode | Name | Covered elsewhere in this document |
|---|---|---|
| `0x01` | Capabilities Request | |
| `0x02` | Capabilities | |
| `0x04` | Battery Info | `## Battery` |
| `0x06` | Ear Detection | `## Ear Detection` |
| `0x08` | Bud Role | |
| `0x09` | Control / Listen Mode | `## Noise Control` |
| `0x0C` | MAC Address | |
| `0x0D` | Audio Source Request | |
| `0x0E` | Audio Source | |
| `0x0F` | Set Notification Filter | `# Requesting notifications` |
| `0x17` | BuddyCommand | sensor streams, above |
| `0x1A` | Rename | `## Renaming AirPods` |
| `0x1B` | Timestamp | carries an ISO 8601 local date-time string |
| `0x1D` | Information | `## Metadata` |
| `0x1F` | Notify Session State? | |
| `0x22` | **Case Info Request** | |
| `0x23` | **Case Info** | |
| `0x24` | Send Device Info? | |
| `0x29` | Set Country Code | |
| `0x2B` | Stream State Info | |
| `0x2D` | Connected Devices Request | |
| `0x2E` | Connected Devices | carries Bluetooth addresses |
| `0x44` | **Send Smart Routing 2.0 Info** | see correction below |
| `0x4B` | Conversational Awareness | `## Conversational Awareness` |
| `0x4C` | Adaptive Volume Message | |
| `0x4D` | Set Features | `# Setting specific features` |
| `0x4E` | Feature ProxCard Status Update | |
| `0x4F` | Unified Accessory Restore Protocol | firmware/asset transfer; the dissector has a separate `uarp` plugin |
| `0x52` | Source Context | |
| `0x53` | Personal Medical Equipment Config | `## Headphone Accomodation` |
| `0x54` | Set Band Edges | |
| `0x55` | Unknown | |
| `0x58` | Hi-res audio | |
| `0x59` | **Dynamic End Of Charge** | |

## Correction: `0x44` is not sensor-related

An earlier revision of this document described `0x44` as sensor subscription, on the strength
of it appearing shortly before stream changes. The dissector names it **Send Smart Routing 2.0
Info** — audio routing. That fits the observations better than the sensor reading ever did: it
appears at session changes and around audio state changes, and its payload never correlated
with any stream starting.

## Leads for the open case/charging question

Two names are worth following up on the unresolved `0x01` vs `0x05` charging question above:

- **`0x22` / `0x23` — Case Info Request / Case Info.** Direct case state, not inferred from
  the battery packet.
- **`0x59` — Dynamic End Of Charge.** Present in the capture where `0x05` appeared and absent
  from the hands-off captures where it did not. That is a correlation worth testing, not a
  conclusion.

> **Note for anyone sharing captures:** `0x1D` transmits the device serial number and the
> user-assigned device name in plaintext, and `0x2E` / `0x0C` / `0x0E` carry Bluetooth
> addresses. A raw `.pklg` is personally identifying even with MAC addresses stripped from
> the HCI layer. Publish derived protocol facts, not capture files.

## Undocumented control command ids (opcode `0x09`)

| Id | Direction | Value(s) seen | Notes |
|---|---|---|---|
| `0x0B` | phone → buds | `0x3C`, `0x96` (60, 150) | sent while worn, before any workout or case interaction |
| `0x38` | buds → phone | `0x52` | emitted twice, once while worn and once after entering the case |
| `0x3B` | phone → buds | `0x01` | 260 ms before the `0x05` → `0x01` charging flip |
| `0x1A` `0x32` `0x3D` | phone → buds | `0x0E`, `0x01`, `0x01` | always sent as a trio during the reconnection handshake |

# LICENSE

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
along with this program. If not, see <https://www.gnu.org/licenses/>.
