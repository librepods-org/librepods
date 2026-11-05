# Qt D-Bus drainage regression

These tests exercise the production `BluetoothMonitor`, not a reimplementation
of its private methods. A separate Python process supplies a BlueZ-compatible
ObjectManager and Properties interface on a private D-Bus daemon. No real system
bus, Bluetooth adapter, device configuration, or user service is modified.

## What is checked

- A slow BlueZ property reply alone does not cause persistent paired socket
  backpressure, and the real monitor eventually recognizes the device.
- A bounded burst of unrelated `PropertiesChanged` dictionaries from another
  service must not accumulate in the monitor's receive queue.
- Non-device `PropertiesChanged` broadcasts from BlueZ itself must also be
  excluded before Qt queues their dictionaries.
- Genuine Device1 connect/disconnect/reconnect notifications retain their order,
  device address, and name; the filter must not drop them.

The observer samples the monitor's actual Unix socket receive queue and the
matching daemon socket send queue by reciprocal inode pair and daemon PID.
Missing or ambiguous pairs fail measurement rather than defaulting to zero.
A public-CLI fault test removes an observable daemon peer row from the external
`ss` response and verifies that the observer rejects that unavailable data.
A persistent combination of
at least 32768 unread client bytes and 65536 pending daemon bytes fails the
regression. Successful runs must also finish sending the fixture load and
process genuine device notifications and bus replies; an idle or broken fixture
is not a passing result.

Filtering the interface only inside `onPropertiesChanged` is too late: messages
are already queued for that callback. The subscription must restrict both the
BlueZ sender and the Device1 argument before delivery. Slow synchronous property
calls are intentionally retained so this test isolates admission/backpressure,
rather than silently masking the bug with a different client implementation.

## Execution

Compilation and execution run in GitHub Actions. The legacy Qt workflow builds
the probe, runs all scenarios on the distribution Qt and the reporter's Qt
6.11.2, then builds the full Qt application and uploads both diagnostics and
executables. To run it on an already-pushed ref:

```sh
gh workflow run ci-linux.yml --repo OWNER/librepods --ref BRANCH
gh run list --repo OWNER/librepods --branch BRANCH
gh run watch RUN_ID --repo OWNER/librepods --exit-status
```

The workflow uses `linux/tests/CMakeLists.txt` for a standalone Qt Core/DBus
probe and `/usr/bin/python3` for the distro's `dbus`/GLib modules. Scratch sockets
are placed under `TMPDIR`, and every test-owned process is terminated before
that scratch directory is removed. `linux/tests/results` contains queue samples,
framework versions, and public-notification observations and is ignored by Git.

## Limits of the evidence

This is an amplified, bounded synthetic traffic test for the backpressure class
reported in [#817](https://github.com/librepods-org/librepods/issues/817). It is
not a capture or unique diagnosis of the reporter's three-day incident. The
private daemon's outgoing limit is raised only in the test fixture to observe
backpressure before disconnection; production broker limits are untouched.
The test does not recreate dbus-broker's shared-UID quota accounting, prove a Qt
reader-thread deadlock, or cover every possible main-thread stall. The original
binary and pre-recovery thread backtraces are still needed to identify the
incident's exact trigger.
