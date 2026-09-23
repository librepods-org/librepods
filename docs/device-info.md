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

## Push-only

Hosts cannot request this opcode. Opcode `0x004F` does not provide a reliable way to read serials or model numbers from the accessory.

After L2CAP connect, send the [handshake](/docs/AAP%20Definitions.md#handshake) and [host capabilities `0x004D`](/docs/host-capabilities.md) so the accessory is likely to include `0x001D` in the startup burst.

## Parsing on third-party hosts (especially Android)

On some non-Apple stacks the `0x001D` SDU is not always aligned at a fixed byte offset after the six-byte `04 00 04 00` + opcode header:

- The payload may include **length prefixes** or an extra nested `04 00 04 00` header before the UTF-8 string block.
- Fields remain **null-terminated strings** in the order listed above once the string run is found.

Implementations should **scan the SDU** for a plausible sequence of printable UTF-8 strings (name, model, `Apple Inc.`, serial-shaped tokens, version strings) instead of assuming parsing always starts at byte index 6.

A single session may deliver **more than one** `0x001D` packet with different lengths. When duplicates disagree, prefer the frame with the **most complete** set of fields.

## Battery report (`0x0004`)

Unsolicited battery packets may arrive before or after `0x001D`. On some Android L2CAP sessions battery reports are sparse or absent even when `0x001D` was received successfully.
