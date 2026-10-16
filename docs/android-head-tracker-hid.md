# Android system head-tracker bridge

LibrePods already receives AirPods motion packets over AACP opcode `0x17`. Android's system
spatializer cannot consume those packets directly: on supported devices it obtains head pose from a
dynamic `TYPE_HEAD_TRACKER` sensor. A rooted bridge can expose the AirPods stream as the standardized
Android head-tracker HID device through `/dev/uhid`.

This is the system-wide route. An app-owned stereo spatializer remains outside LibrePods' scope.

## Preconditions

All of these conditions are required:

- Android 13 or newer;
- root access to create and service a `/dev/uhid` device;
- an OEM build with the HID dynamic-sensor sub-HAL enabled;
- an OEM spatializer implementation that advertises head-tracking support;
- the identity Bluetooth address for the active AirPods audio route.

The bridge must surface each failed precondition independently. Creating a HID device does not add
head-tracking support to an OEM audio effect that lacks it.

## HID v1 report contract

The virtual device is an application collection on Sensors page `0x20`, usage Other:Custom `0xE1`.
It uses three reports:

- report 2, read-only: the 23-byte ASCII sensor description `#AndroidHeadTracker#1.0` and the
  16-byte persistent unique ID;
- report 1, read/write feature: reporting state, power state, and report interval;
- report 1, input: three int16 rotation-vector axes, three int16 angular-velocity axes, and one
  uint8 discontinuity counter.

For Bluetooth route association, the persistent unique ID is eight zero bytes, ASCII `BT`, then the
six identity-address bytes. Input reports are emitted only while the host has selected Full Power,
All Events, and a nonzero report interval. The bridge must honor the host's requested interval in the
descriptor's 10–100 ms range and support the protocol's required 50 Hz reporting rate.

## `/dev/uhid` lifecycle

1. Open one nonblocking `/dev/uhid` file descriptor for the AirPods route.
2. Write one complete `UHID_CREATE2` event with the report descriptor and Bluetooth bus identity.
3. Read kernel events continuously. Reply to every `UHID_GET_REPORT` and `UHID_SET_REPORT` request
   with the matching request ID; persist accepted feature-report values.
4. After `UHID_START`, send `UHID_INPUT2` reports only when the feature state enables reporting.
5. On AirPods disconnect, audio-route change, service shutdown, or helper failure, write
   `UHID_DESTROY` and close the descriptor.

UHID events cannot be split across writes, and synchronous feature requests cannot be ignored: the
kernel blocks the sensor driver while waiting for their replies.

## Pose conversion boundary

Current LibrePods parsing yields three raw orientation words and two acceleration words. The AOSP
report requires a right-handed rotation vector in radians and angular velocity in radians/second.
The bridge therefore needs a separately testable pose converter that:

- validates the AACP sensor-frame discriminator before reading fixed offsets;
- calibrates the neutral reference frame and increments the discontinuity counter on recalibration;
- converts the verified AirPods axes into Android's X (left-to-right), Y (back-to-front), and Z
  (neck-to-top) frame;
- derives angular velocity from consecutive timestamped poses when the accessory payload does not
  provide a complete gyro vector;
- clamps rotation magnitude to π and angular velocity to the HID descriptor's declared range.

The raw-axis mapping must be verified against captured motion before enabling the root bridge. A
two-angle visualizer is not sufficient evidence for a system sensor's three-axis coordinate contract.

## Verification

The implementation is ready for device testing only after parser/conversion unit tests and a native
UHID feature-report test pass. On a rooted target, verify the sensor first with `dumpsys sensorservice`,
then verify route association and head-tracking availability through Android's audio service. A real
spatializer listening test is the final UI/device check; creating a sensor node alone is not an
end-to-end result.
