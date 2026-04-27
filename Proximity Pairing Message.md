# Bluetooth Low Energy (BLE) - Apple Proximity Pairing Message

This document describes how the AirPods BLE "Proximity Pairing Message" is parsed and interpreted in the application. This message is broadcast by Apple devices (such as AirPods) and contains key information about the device's state, battery, and other properties.

## Overview

When scanning for BLE devices, the application looks for manufacturer data with Apple's ID (`0x004C`). If the data starts with `0x07`, it is identified as a Proximity Pairing Message. The message contains various fields, each representing a specific property of the AirPods.

## Proximity Pairing Message Structure

| Byte Index | Field Name              | Description                                             | Example Value(s)         |
|------------|-------------------------|---------------------------------------------------------|--------------------------|
| 0          | Prefix                  | Message type (should be `0x07` for proximity pairing)   | `0x07`                   |
| 1          | Length                  | Length of the message                                   | `0x12`                   |
| 2          | Pairing Mode            | `0x01` = Paired, `0x00` = Pairing mode                 | `0x01`, `0x00`           |
| 3-4        | Device Model            | Big-endian: [3]=high, [4]=low                          | `0x0E20` (AirPods Pro)   |
| 5          | Status                  | Bitfield, see below                                    | `0x62`                   |
| 6          | Pods Battery Byte       | Nibbles for left/right pod battery                     | `0xA7`                   |
| 7          | Flags & Case Battery    | Upper nibble: case battery, lower: flags               | `0xB3`                   |
| 8          | Lid Indicator           | Bits for lid state and open counter                    | `0x09`                   |
| 9          | Device Color            | Color code                                             | `0x02`                   |
| 10         | Connection State        | Enum, see below                                        | `0x04`                   |
| 11-26      | Encrypted Payload       | 16 bytes, not parsed                                   |                          |

## Field Details

### Device Model

| Value (hex) | Model Name                |
|-------------|--------------------------|
| 0x0220      | AirPods 1st Gen          |
| 0x0F20      | AirPods 2nd Gen          |
| 0x1320      | AirPods 3rd Gen          |
| 0x1920      | AirPods 4th Gen          |
| 0x1B20      | AirPods 4th Gen (ANC)    |
| 0x0A20      | AirPods Max              |
| 0x1F20      | AirPods Max (USB-C)      |
| 0x2D20      | AirPods Max 2nd Gen      |
| 0x0E20      | AirPods Pro              |
| 0x1420      | AirPods Pro 2nd Gen      |
| 0x2420      | AirPods Pro 2nd Gen (USB-C) |

### Status Byte (Bitfield)

| Bit | Meaning                        | Value if Set |
|-----|--------------------------------|-------------|
| 0   | Right Pod In Ear (XOR logic)   | true        |
| 1   | Right Pod In Ear (XOR logic)   | true        |
| 2   | Both Pods In Case              | true        |
| 3   | Left Pod In Ear (XOR logic)    | true        |
| 4   | One Pod In Case                | true        |
| 5   | Primary Pod (1=Left, 0=Right)  | true/false  |
| 6   | This Pod In Case               | true        |

### Ear Detection Logic

The in-ear detection uses XOR logic based on:
- Whether the right pod is primary (`areValuesFlipped`)
- Whether this pod is in the case (`isThisPodInTheCase`)

```cpp
bool xorFactor = areValuesFlipped ^ deviceInfo.isThisPodInTheCase;
deviceInfo.isLeftPodInEar = xorFactor ? (status & 0x08) != 0 : (status & 0x02) != 0;  // Bit 3 or 1
deviceInfo.isRightPodInEar = xorFactor ? (status & 0x02) != 0 : (status & 0x08) != 0; // Bit 1 or 3
```

### Primary Pod

Determined by bit 5 of the status byte:
- `1` = Left pod is primary
- `0` = Right pod is primary

This affects:
1. Battery level interpretation (which nibble corresponds to which pod)
2. Microphone assignment
3. Ear detection logic

### Microphone Status

The active microphone is determined by:
```cpp
deviceInfo.isLeftPodMicrophone = primaryLeft ^ deviceInfo.isThisPodInTheCase;
deviceInfo.isRightPodMicrophone = !primaryLeft ^ deviceInfo.isThisPodInTheCase;
```

### Pods Battery Byte

- Upper nibble: one pod battery (depends on primary)
- Lower nibble: other pod battery

| Value | Meaning         |
|-------|----------------|
| 0x0-0x9 | 0-90% (x10)  |
| 0xA-0xE | 100%         |
| 0xF     | Not available|

### Flags & Case Battery Byte

- Upper nibble: case battery (same encoding as pods)
- Lower nibble: flags

#### Flags (Lower Nibble)

| Bit | Meaning                  |
|-----|--------------------------|
| 0   | Right Pod Charging (XOR) |
| 1   | Left Pod Charging (XOR)  |
| 2   | Case Charging            |

### Lid Indicator

| Bits | Meaning                |
|------|------------------------|
| 0-2  | Lid Open Counter       |
| 3    | Lid State (0=Open, 1=Closed) |

### Device Color

| Value | Color        |
|-------|-------------|
| 0x00  | White       |
| 0x01  | Black       |
| 0x02  | Red         |
| 0x03  | Blue        |
| 0x04  | Pink        |
| 0x05  | Gray        |
| 0x06  | Silver      |
| 0x07  | Gold        |
| 0x08  | Rose Gold   |
| 0x09  | Space Gray  |
| 0x0A  | Dark Blue   |
| 0x0B  | Light Blue  |
| 0x0C  | Yellow      |
| 0x0D+ | Unknown     |

### Connection State

| Value | State         |
|-------|--------------|
| 0x00  | Disconnected |
| 0x04  | Idle         |
| 0x05  | Music        |
| 0x06  | Call         |
| 0x07  | Ringing      |
| 0x09  | Hanging Up   |
| 0xFF  | Unknown      |

## Example Message

| Byte Index | Example Value | Description                |
|------------|--------------|----------------------------|
| 0          | 0x07         | Proximity Pairing Message  |
| 1          | 0x12         | Length                     |
| 2          | 0x01         | Paired                     |
| 3-4        | 0x0E 0x20    | AirPods Pro                |
| 5          | 0x62         | Status                     |
| 6          | 0xA7         | Pods Battery               |
| 7          | 0xB3         | Flags & Case Battery       |
| 8          | 0x09         | Lid Indicator              |
| 9          | 0x02         | Device Color               |
| 10         | 0x04         | Connection State (Idle)    |

---

For further details, see [`BleManager`](linux/ble/blemanager.cpp) and [`BleScanner`](linux/ble/blescanner.cpp).