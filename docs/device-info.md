---
opcode: 0x001D
title: Device Information
description: Information about AirPods, such as model, firmware version, and serial number. This can not be requested from the accessory; it is only sent by the accessory to the host upon connection.
---

## Device information

The device information packet is sent by the accessory to the host upon connection. It contains various details about the AirPods, including model number, software version, and serial number.

Each `null` indicates the start of a new string field.

The data is in this order:
- Name
- Model number
- Manufacturer (always "Apple Inc.")
- Serial number
- Version 1
- Version 2
- Hardware revision (?) (I have `1.0.0`)
- Updater app version (?) (I have `com.apple.accessory.updater.app.71`)
- Serial number (Left Bud)
- Serial number (Right Bud)
- Version (?) (I have `8454371`)
- A few more bytes, I don't know what they are

