# HyperOS and the AAP L2CAP channel

HyperOS ships its own AirPods adapter in `com.xiaomi.bluetooth`
(`/system_ext/app/BluetoothExtension/BluetoothExtension.apk`). After an HFP
connection state change it opens an Apple AAP L2CAP channel on **PSM 0x1001
(4097)** — the same channel LibrePods uses.

logcat, from the adapter's own pid:

```
DevicesTransportHandler: handleConnectL2capMsg AA:BB:CC:DD:EE:FF, 4097
BluetoothSocket: connect(), socket connected. mPort=4097
Connector: createL2capSocket success
```

`root-module-manual/service.sh` gates that adapter off so LibrePods is the only
AAP session holder on the device. This document is what the script is doing and
why, plus the trade-off it carries.

## What gates the adapter

The adapter's connect entry point (`AirCoreManager`) asks a feature-support
helper whether the connected device is a target, and that helper reads a
hardcoded path:

```
/data/user_de/0/com.xiaomi.bluetooth/files/fc_resources/<version>/fc_support_airpods.json
```

The lookup for a given top-level key behaves like this:

| `ConnectL2cap` in the JSON | helper returns | adapter |
|---|---|---|
| key absent (factory default) | 1 → falls back to the built-in table | connects |
| key present, does not contain the local model type | 3 | **blocked** |
| key present, contains the local model type | 2 | connects |

The value must be an array of type strings. An empty array therefore excludes
every model, which is what the script writes — no per-model configuration
needed. The built-in fallback table already lists current AirPods models, which
is why the factory file (with no key at all) lets the adapter through.

`EarDetection` and `NoiseControl` are gated by the same helper against the same
file.

## What the script does

Runs at every boot (so it survives an OTA resetting the file), and:

1. exits immediately unless `ro.miui.ui.version.name` is set;
2. waits up to 60s for the JSON to appear — the adapter creates it on first run,
   which can be after the boot script fires;
3. exits if `"ConnectL2cap"` is already present, which is what makes it
   idempotent (the factory file never has the key);
4. backs the pristine file up once to `fc_support_airpods.json.bak_librepods`;
5. appends `"ConnectL2cap": []`, `"EarDetection": []`, `"NoiseControl": []`,
   restores `bluetooth:bluetooth` / `600` /
   `u:object_r:bluetooth_data_file:s0`, sets the immutable bit, and force-stops
   the adapter so it re-reads the file.

Two device-side details that are easy to get wrong:

- **Never truncate the existing inode.** Writing in place fails with
  `Operation not supported on transport endpoint` on this f2fs setup. Write a
  new file and `mv` it over the old one.
- After `chattr -i`, confirm with `lsattr` that the `i` flag is actually gone
  before writing.

`uninstall.sh` restores the backup through the same write-new-then-rename path
and force-stops the adapter again.

## Trade-off: the native AirPods card stops working

Because `NoiseControl` and `EarDetection` are gated by the same file, patching
it also makes the system answer "not supported" to its own Control Center card,
which then stops rendering. That is not a side effect worth hiding: **with this
script installed, HyperOS's built-in AirPods noise-control card and settings
entry are gone.**

Restoring only `NoiseControl` does not bring the card back in a working state:
the card's commands are encoded and sent over *the adapter's own* AAP session,
so leaving `ConnectL2cap` blocked yields a card that renders but does nothing.
Native UI and native AAP session come as a pair.

## What this does not fix

It is tempting to file this under "fixes the periodic disconnects". The data
does not support that. Two concurrent AAP channels to the same AirPods were
observed staying up for 15 minutes with zero disconnects, so "a second AAP
session makes the AirPods tear down the ACL link" is not true. Coexistence
works.

The script's honest claim is narrower: one fewer process competing for the
channel, and LibrePods as the unambiguous owner of the AAP session.

## Verifying it took effect

- `lsattr` on the JSON shows the `i` flag.
- The adapter logs `onDeviceConnected not target devices` when it is turned
  away, which is visible in logcat.
- The adapter makes no `handleConnectL2capMsg ... 4097` attempts.
