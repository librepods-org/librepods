# AACP opcodes

AACP (Apple Accessory Communication Protocol) uses various opcodes to define different types of actions and commands. Each opcode is a 16-bit integer that specifies the kind of operation being performed. The opcode is sent in little-endian format as part of the AACP packet structure.


| Opcode (Hex) | Destination | Description                                                        |
| ------------ | ----------- | ------------------------------------------------------------------ |
| 0x0001       | Accessory   | Unknown                                                            |
| 0x0004       | Host        | [Battery report](/docs/battery_report.md)                          |
| 0x0006       | Host        | [Ear detection](/docs/ear-detection_report.md)                     |
| 0x0009       | Both        | [Control commands](/docs/control_commands.md)                      |
| 0x000D       | Accessory   | [Audio source req](/docs/audio-source.md)                          |
| 0x000E       | Host        | [Audio source resp](/docs/audio-source.md)                         |
| 0x000F       | Accessory   | [Notification register](/docs/notification-register.md)            |
| 0x0010       | Accessory   | [Smart routing relay](/docs/smart-routing-relay.md#send)           |
| 0x0011       | Host        | [Smart routing response](/docs/smart-routing-relay.md#receive)     |
| 0x0014       | Accessory   | Send connected device MAC                                          |
| 0x0017       | Both        | Multiple things - undocumented                                     |
| 0x0019       | Host        | [Stem press](/docs/stem-press.md)                                  |
| 0x001B       | Accessory   | [Timestamp](/docs/timestamp.md)                                    |
| 0x001D       | Host        | [Device Information](/docs/device-info.md)                         |
| 0x001E       | Accessory   | [Rename device](/docs/rename.md)                                   |
| 0x0022       | Accessory   | Unknown                                                            |
| 0x0029       | Accessory   | [Host capabilities](/docs/host-capabilities.md#another-opcode) (?) |
| 0x002B       | Host        | Paired devices (?)                                                 |
| 0x002D       | Accessory   | [List of connected dev. req](/docs/connected-devices.md#send)      |
| 0x002E       | Host        | [List of connected devices](/docs/connected-devices.md#receive)    |
| 0x0030       | Accessory   | [BLE keys req](/docs/ble-keys.md)                                  |
| 0x0031       | Host        | [BLE keys response](/docs/ble-keys.md)                             |
| 0x004B       | Host        | [Conversation awareness](/docs/conversational-awareness.md)        |
| 0x004D       | Accessory   | [Host capabilities](/docs/host-capabilities.md)                    |
| 0x004F       | Both        | Information req/res (doesn't work, even with apple's DID)          |
| 0x0053       | Both        | [EQ data](/docs/eq.md)                                             |