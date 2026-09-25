/*++

Module Name:

    MicPipe.h

Abstract:

    LibrePods hi-res microphone bridge (Phase 2). A global ring buffer that
    user mode fills with decoded PCM over an IOCTL, and the ACX capture stream
    engine drains one packet per notification tick (see StreamEngine.cpp
    ProcessPacket). Exposed to user mode through a control device
    (\\.\LibrePodsMic).

    PCM format: mono, 16-bit, 44100 or 48000 Hz (whatever the client opens the
    capture endpoint with — see Capture_AllocateSupportedFormats).

Environment:

    Kernel mode

--*/

#pragma once

#include <ntddk.h>
#include <wdf.h>

//
// IOCTL: user mode pushes raw PCM bytes into the mic ring buffer.
// Value (precomputed for the user-mode side): 0x0022A000.
//   CTL_CODE(FILE_DEVICE_UNKNOWN, 0x800, METHOD_BUFFERED, FILE_WRITE_DATA)
//
#define IOCTL_LIBREPODS_MIC_WRITE_PCM \
    CTL_CODE(FILE_DEVICE_UNKNOWN, 0x800, METHOD_BUFFERED, FILE_WRITE_DATA)

//
// IOCTL: user mode reads a capture-activity counter (ULONG). It advances every
// time an app pulls a capture packet, so the tray can tell when the mic is
// actually being recorded and auto-enable/disable the hi-res stream.
// Value (precomputed): 0x00226004.
//   CTL_CODE(FILE_DEVICE_UNKNOWN, 0x801, METHOD_BUFFERED, FILE_READ_DATA)
//
#define IOCTL_LIBREPODS_MIC_STATUS \
    CTL_CODE(FILE_DEVICE_UNKNOWN, 0x801, METHOD_BUFFERED, FILE_READ_DATA)

EXTERN_C_START

//
// Initialize the global ring buffer. Call once, early (device add), before any
// read/write. Idempotent.
//
VOID
MicPipeInit(
    VOID
);

//
// Create the control device (\Device\LibrePodsMic + \DosDevices\LibrePodsMic)
// that exposes IOCTL_LIBREPODS_MIC_WRITE_PCM. Driver-scoped and created once;
// safe to call again (returns STATUS_SUCCESS if already created). Best-effort:
// a failure here must not fail device add (the mic still enumerates, just with
// no user-mode feed yet).
//
NTSTATUS
MicPipeCreateControlDevice(
    _In_ WDFDEVICE Parent
);

//
// Append PCM bytes to the ring (called from the IOCTL handler, PASSIVE_LEVEL).
// On overflow the oldest bytes are dropped so we always keep the newest audio.
//
VOID
MicPipeWrite(
    _In_reads_bytes_(Len) PVOID Data,
    _In_                  ULONG Len
);

//
// Fill Out with Len bytes from the ring (called from ProcessPacket, up to
// DISPATCH_LEVEL). Any underrun is zero-filled (silence).
//
VOID
MicPipeRead(
    _Out_writes_bytes_(Len) PVOID Out,
    _In_                    ULONG Len
);

//
// Publish how many bytes the capture side pulls per tick (called from
// CStreamEngine::AllocateRtPackets). The ring's latency trim keeps at least two
// of these buffered, so a client that captures in large chunks doesn't underrun
// on every read.
//
VOID
MicPipeSetPacketSize(
    _In_ ULONG PacketSize
);

EXTERN_C_END
