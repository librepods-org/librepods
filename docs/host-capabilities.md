---
opcode: 0x004D
title: Host capabilities (feature flags)
description: Sent by the host to the accessory after the initial L2CAP handshake so the accessory emits the full startup notification burst.
---

## Overview

After the [handshake](/docs/AAP%20Definitions.md#handshake) on PSM `0x1001`, third-party hosts should send opcode **`0x004D`** before (or shortly before) [requesting notifications](/docs/AAP%20Definitions.md#requesting-notifications). The accessory uses this to decide which host-side features are supported and often follows with a burst of unsolicited packets (for example paired-device metadata `0x002B` and [device information](/docs/device-info.md) `0x001D`).

This opcode is **not** a poll for device info; it only advertises host capability. Device information remains push-only on `0x001D`.

## Packet shape

Typical framing uses the usual AACP prefix `04 00 04 00`, little-endian opcode `4D 00`, then a capability bitmask or feature bytes.

### Observed on macOS (PacketLogger)

Documented in [AAP Definitions](/docs/AAP%20Definitions.md#setting-specific-features-for-airpods-pro-2):

```plaintext
04 00 04 00 4d 00 ff 00 00 00 00 00 00 00
```

### Observed in LibrePods (Android)

The Android app sends a longer payload starting with `D7` (see `AACPManager.createSetFeatureFlagsPacket()`).

### Other third-party clients

Some Android clients use a shorter capability byte (for example `FF`) with the same opcode. Behavior can vary by accessory firmware; if the startup burst is thin (short `0x002B` only, no `0x001D`), verify that `0x004D` is sent and that the host is not discarding early inbound data.

## Recommended connection order (third-party hosts)

1. Open L2CAP to PSM `0x1001` on a bonded device.
2. Send handshake `0x0001`.
3. Wait roughly **100–350 ms** (some Android stacks are timing-sensitive).
4. Send **`0x004D`** host capabilities.
5. Wait roughly **100–350 ms**.
6. Send **`0x000F`** notification register.
7. Read and process inbound packets continuously; **`0x001D` may arrive before step 6 completes**.

## Client pitfalls

- **Do not drain or drop the socket receive queue** immediately after connect. The accessory may already have queued `0x001D` or `0x002B` during the handshake burst.
- **Do not rely on `0x004F`** to fetch serials or model data; it does not behave like a device-info read even with Apple Device ID spoofing.
- On Android, L2CAP to AACP may require stack-specific socket construction; see [Google issue 371713238](https://issuetracker.google.com/issues/371713238) and [capod#215](https://github.com/d4rken-org/capod/issues/215).

## Related opcodes

| Opcode | Direction (typical) | Notes |
| ------ | ------------------- | ----- |
| `0x0029` | Host → accessory (?) | Also associated with host capabilities in some captures; less common in open-source clients than `0x004D`. |
| `0x002B` | Accessory → host | Often appears in the startup burst after capabilities. |
| `0x001D` | Accessory → host | [Device information](/docs/device-info.md). |
