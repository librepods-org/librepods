# Control Commands

AACP uses opcode `9` for control commands. opcodes are 16 bit integers that specify the kind of action being done. The length of a control command is fixed to 7 bytes + 4 bytes header (`04 00 04 00`)

An AACP packet is formated as:

`04 00 04 00 [opcode, little endian] [data]`

So, our control commands becomes

```
04 00 04 00 09 00 [identifier] [data1] [data2] [data3] [data4]
```

Bytes that are not used are set to `0x00`. From what I've observed, the `data3` and `data4` are never used, and hence always zero. And, the `data2` is usually used when the configuration can be different for the two buds: like, to change the long press mode. Or, if there can be two "state" variables for the same feature: like the Hearing Aid feature.

## Identifiers and details 

| Command identifier | Description                                    |
| ------------------ | ---------------------------------------------- |
| 0x01               | Mic Mode                                       |
| 0x05               | Button Send Mode                               |
| 0x06               | Owns connection                                |
| 0x0A               | Ear Detection                                  |
| 0x12               | VoiceTrigger for Siri                          |
| 0x14               | SingleClickMode                                |
| 0x15               | DoubleClickMode                                |
| 0x16               | ClickHoldMode                                  |
| 0x17               | DoubleClickInterval                            |
| 0x18               | ClickHoldInterval                              |
| 0x1A               | ListeningModeConfigs                           |
| 0x1B               | OneBudANCMode                                  |
| 0x1C               | CrownRotationDirection                         |
| 0x0D               | ListeningMode                                  |
| 0x1E               | AutoAnswerMode                                 |
| 0x1F               | Chime Volume                                   |
| 0x20               | Connect Automatically                          |
| 0x23               | VolumeSwipeInterval                            |
| 0x24               | Call Management Config                         |
| 0x25               | VolumeSwipeMode                                |
| 0x26               | Adaptive Volume Config                         |
| 0x27               | Software Mute config                           |
| 0x28               | Conversation Detect config                     |
| 0x29               | SSL                                            |
| 0x2C               | Hearing Aid Enrolled and Hearing Aid Enabled   |
| 0x2E               | AutoANC Strength                               |
| 0x2F               | HPS Gain Swipe                                 |
| 0x30               | HRM enable/disable state                       |
| 0x31               | In Case Tone config                            |
| 0x32               | Siri Multitone config                          |
| 0x33               | Hearing Assist config                          |
| 0x34               | Allow Off Option for Listening Mode config     |
| 0x35               | Sleep Detection config                         |
| 0x36               | Allow Auto Connect                             |
| 0x37               | PPE Toggle config                              |
| 0x38               | Personal Protective Equipment Cap Level config |
| 0x39               | Raw Gestures config                            |
| 0x3A               | Temporary Pairing Config                       |
| 0x3B               | Dynamic End of Charge config                   |
| 0x3C               | System Siri message config                     |
| 0x3D               | Hearing Aid Generic config                     |
| 0x3E               | Uplink EQ Bud config                           |
| 0x3F               | Uplink EQ Source config                        |
| 0x40               | In Case Tone Volume                            |
| 0x41               | Disable Button Input config                    |

## Command Details

### 0x01 - Mic Mode
Format: Single value (1 byte)
Values: `0x00` = Automatic, `0x01` = Right, `0x02` = Left.

### 0x05 - Button Send Mode
Format: Single value (1 byte)
Additional notes: Logged as "Set Button Send Mode: %d". May involve context updates for button handling.

### 0x06 - Owns connection
Format: Single value (1 byte)
Values: `0x01` = own, `0x00` = doesn't own.

### 0x0A - Ear Detection
Format: Single value (1 byte)
Values: `0x01` = enabled, `0x02` = disabled.

### 0x12 - VoiceTrigger for Siri
Format: Single value (1 byte)
Values: `0x01` = enabled, `0x02` = disabled.

### 0x14 - SingleClickMode
Format: Single value (1 byte)

### 0x15 - DoubleClickMode
Format: Single value (1 byte)

### 0x16 - ClickHoldMode
Format: Two values (2 bytes; First byte = right bud, Second byte = left bud).  
Values: `0x01` = Noise control, `0x05` = Siri.  

### 0x17 - DoubleClickInterval
Format: Single value (1 byte)
Values: 0x00 = Default, `0x01` = Slower, `0x02` = Slowest.  

### 0x18 - ClickHoldInterval
Format: Single value (1 byte)
Values: 0x00 = Default, `0x01` = Slower, `0x02` = Slowest.  

### 0x1A - ListeningModeConfigs
Format: Single value (1 byte)
Values: Bitmask, Off mode = `0x01`, ANC=`0x02`, Transparency = 0x04, Adaptive = `0x08`.  

### 0x1B - OneBudANCMode
Format: Single value (1 byte)
Values: `0x01` = enabled, `0x02` = disabled

### 0x1C - CrownRotationDirection
Format: Single value (1 byte)
Values: `0x01` = reversed, `0x02` = default.  

### 0x0D - ListeningMode
Format: Single value (1 byte)
Values: 1 = Off, 2 = noise cancellation, 3 = transparency, 4 = adaptive.  

### 0x1E - AutoAnswerMode
Format: Single value (1 byte)

### 0x1F - Chime Volume
Format: Single value (1 byte)
Values: 0 to 100.  

### 0x20 - Connect Automatically
Format: Single value (1 byte)
Values: `0x01` = enabled, `0x02` = disabled.

### 0x23 - VolumeSwipeInterval
Format: Single value (1 byte)
Values: 0x00 = Default, `0x01` = Longer, `0x02` = Longest.  

### 0x24 - Call Management Config
Format: Single value (1 byte)

### 0x25 - VolumeSwipeMode
Format: Single value (1 byte)
Values: `0x01` = enabled, `0x02` = disabled

### 0x26 - Adaptive Volume Config
Format: Single value (1 byte)
Values: `0x01` = enabled, `0x02` = disabled

### 0x27 - Software Mute config
Format: Single value (1 byte)

### 0x28 - Conversation Detect config
Format: Single value (1 byte)
Values: `0x01` = enabled, `0x02` = disabled

### 0x29 - SSL
Format: Single value (1 byte)

### 0x2C - Hearing Aid Enrolled and Hearing Aid Enabled
Format: Two values (2 bytes; First byte - enrolled, Second byte = enabled)
Values: `0x01` = enabled, `0x02` = disabled

### 0x2E - AutoANC Strength
Format: Single value (1 byte)
Values: 0 to 100.  

### 0x2F - HPS Gain Swipe (swipe to adjust amplification)
Format: Single value (1 byte)

### 0x30 - HRM enable/disable state
Format: Single value (1 byte)

### 0x31 - In Case Tone config
Format: Single value (1 byte)
Values: `0x01` = enabled, `0x02` = disabled

### 0x32 - Siri Multitone config
Format: Single value (1 byte)

### 0x33 - Hearing Assist config
Format: Single value (1 byte)
Values: `0x01` = enabled, `0x02` = disabled

### 0x34 - Allow Off Option for Listening Mode config
Format: Single value (1 byte)
Values: `0x01` = enabled, `0x02` = disabled

### 0x35 - Sleep Detection config
Format: Single value (1 byte)
Values: `0x01` = enabled, `0x02` = disabled

### 0x36 - Allow Auto Connect
Format: Single value (1 byte)
Values: `0x01` = allow, `0x02` = disallow

### 0x37 - PPE Toggle config
Format: Single value (1 byte)

### 0x38 - Personal Protective Equipment Cap Level config
Format: Single value (1 byte)

### 0x39 - Raw Gestures config
Format: Single value (1 byte)
Values: Bitmask, single press = `0x01`, double press = `0x02`, triple press = `0x04`, long press = `0x08`.  

### 0x3A - Temporary Pairing Config
Format: Single value (1 byte)
Values: `0x01` = Temporary, `0x02` = Permanent

### 0x3B - Dynamic End of Charge config
Format: Single value (1 byte)

### 0x3C - System Siri message config
Format: Single value (1 byte)

### 0x3D - Hearing Aid Generic config
Format: Single value (1 byte)

### 0x3E - Uplink EQ Bud config
Format: Single value (1 byte)

### 0x3F - Uplink EQ Source config
Format: Single value (1 byte)

### 0x40 - In Case Tone Volume
Format: Single value (1 byte)
Values: 0 to 100.  

### 0x41 - Disable Button Input config
Format: Single value (1 byte)

> [!NOTE]
> - These identifiers have been extracted from the iOS 19.1 Beta (23B5044l)'s bluetooth stack. 
> - I have already added the ranges of values a command takes that I know of. Feel free to experiment by sending the packets for which the range/values are not given here.
