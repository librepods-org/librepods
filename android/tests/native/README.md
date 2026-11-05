# Native SDP regression boundary on fix-linux-bugs

This is a focused adaptation of the #873 SDK fixture, not a merge of the different
Android platform baseline. The production translation unit is compiled unchanged.
The integration implementation uses system-property offsets rather than an ELF
symbol resolver or Kotlin NativeBridge. The harness maps a real Bluetooth-named
ELF, derives exported SDK offsets with dladdr, supplies them through the unavailable
external Android property API, and invokes native_init and its returned callback.
Only Android logging/properties and external hook/Bluetooth SDK boundaries are replaced.
No private original-function pointer is assigned by the test.

Cases: single_registration and the eight-row forwarding_contract characterize
existing one-call behavior; disabled_vendor asserts no SDP replacement with the
legacy disabled offset policy, plus normal SDK registration/status/handle/input
identity. null_record is the first new regression: the baseline wrapper dereferences
the null record in a log argument before forwarding the SDK call.

All instrumented targets have fatal UBSan and ASan. SDK attempts are distinct from
successful non-primary record allocations. Replacement/original aliases are rejected.
These tests do not execute OEM binaries or LSPosed inline patching, nor prove the
original Xiaomi process-death root cause. Run configuration, compilation and CTest
only in GitHub Actions; never on the editing server.
