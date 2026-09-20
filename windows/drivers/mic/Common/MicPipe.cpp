/*++

Module Name:

    MicPipe.cpp

Abstract:

    Implementation of the LibrePods hi-res microphone bridge. See MicPipe.h.

    Design: a single global byte ring buffer guarded by a spin lock (the virtual
    mic is single-instance). User mode writes decoded PCM via the control device
    IOCTL; the ACX capture stream engine reads a packet per tick. No WPP tracing
    here (keeps the file self-contained).

Environment:

    Kernel mode

--*/

#include "MicPipe.h"

//
// Ring capacity @ 48 kHz mono 16-bit (96000 B/s), so 0x8000 is ~341 ms. Lives in
// the driver's non-paged image data, so it's safe to touch at DISPATCH_LEVEL.
//
// The two sides run on independent clocks with no drift correction between them:
// the write side is the decoded Bluetooth uplink, delivered on the AirPods' own
// schedule, and the read side is Windows' ACX capture timer (see
// CStreamEngine::ScheduleNextPass). With "drop the oldest byte on a hard overflow"
// as the only backpressure, a write side that is even marginally the faster of the
// two fills the ring within seconds and then sits pinned at full — turning what is
// meant to be a small jitter cushion into a fixed delay equal to the ring's whole
// capacity. That is what the old 0x20000 (~1.36 s) ring did to mic latency.
//
// So the capacity now bounds only the WORST case; steady-state latency is bounded
// by the active trim below. Keep it comfortably above the high watermark all the
// same: it still has to swallow a whole priming write on top of the buffered audio
// without hard-dropping.
//
#define MIC_RING_BYTES 0x8000u

//
// Active trim. When a write pushes the backlog past the high watermark, jump the
// tail forward to the target instead of waiting for a hard overflow. This is what
// actually bounds latency — one deliberate skip beats a slow creep into a
// permanent delay.
//
#define MIC_RING_HIGH_WATERMARK 0x1800u  // ~64 ms
#define MIC_RING_TARGET_BYTES   0x0C00u  // ~32 ms

static UCHAR      g_Ring[MIC_RING_BYTES];
static ULONG      g_Head;   // next write index
static ULONG      g_Tail;   // next read index
static ULONG      g_Count;  // bytes currently buffered
static KSPIN_LOCK g_Lock;
static BOOLEAN    g_Inited = FALSE;

static WDFDEVICE  g_ControlDevice = NULL;

// Advances on every capture pull (MicPipeRead). The tray polls it to tell when
// an app is actually recording from the mic, and auto-enables/disables the
// hi-res stream accordingly.
static volatile LONG g_ReadTick = 0;

// Bytes the capture timer pulls per tick, published by the stream engine once the
// client's packets are allocated (MicPipeSetPacketSize); 0 until then. The trim
// levels are raised to clear it, because a target below one packet would make
// every single read underrun into silence.
static volatile LONG g_PacketSize = 0;

EXTERN_C_START

VOID
MicPipeInit(
    VOID
)
{
    if (g_Inited) {
        return;
    }
    KeInitializeSpinLock(&g_Lock);
    g_Head = g_Tail = g_Count = 0;
    g_Inited = TRUE;
}

VOID
MicPipeWrite(
    _In_reads_bytes_(Len) PVOID Data,
    _In_                  ULONG Len
)
{
    KIRQL  irql;
    PUCHAR src = (PUCHAR)Data;
    ULONG  i;

    if (!g_Inited || Data == NULL || Len == 0) {
        return;
    }

    KeAcquireSpinLock(&g_Lock, &irql);
    for (i = 0; i < Len; i++) {
        if (g_Count == MIC_RING_BYTES) {
            // Full: drop the oldest byte so the newest audio always wins.
            g_Tail = (g_Tail + 1u) % MIC_RING_BYTES;
            g_Count--;
        }
        g_Ring[g_Head] = src[i];
        g_Head = (g_Head + 1u) % MIC_RING_BYTES;
        g_Count++;
    }

    //
    // Active trim: the two sides run on independent clocks (see MIC_RING_BYTES),
    // so don't let the backlog quietly grow. Past the high watermark, drop back to
    // the target in one step. That skip is audible if you time it right, but it
    // keeps steady-state latency near the target instead of drifting up to the
    // ring's capacity and staying there.
    //
    {
        ULONG target = MIC_RING_TARGET_BYTES;
        ULONG high   = MIC_RING_HIGH_WATERMARK;
        ULONG packet = (ULONG)InterlockedCompareExchange(&g_PacketSize, 0, 0);

        // Keep at least two capture packets buffered: a client that pulls 40 ms at
        // a time would otherwise underrun on every read at a 32 ms target.
        if (packet != 0) {
            if (target < 2u * packet) {
                target = 2u * packet;
            }
            if (high < target + packet) {
                high = target + packet;
            }
        }

        // A packet larger than the ring can hold this way: leave it to the
        // hard-overflow path above rather than trim to a nonsensical level.
        if (high < MIC_RING_BYTES && g_Count > high) {
            // Trim whole samples only — this is the one place that could shift the
            // ring by half a 16-bit frame and garble everything from then on.
            ULONG excess = (g_Count - target) & ~1u;
            g_Tail = (g_Tail + excess) % MIC_RING_BYTES;
            g_Count -= excess;
        }
    }

    KeReleaseSpinLock(&g_Lock, irql);
}

VOID
MicPipeSetPacketSize(
    _In_ ULONG PacketSize
)
{
    InterlockedExchange(&g_PacketSize, (LONG)PacketSize);
}

VOID
MicPipeRead(
    _Out_writes_bytes_(Len) PVOID Out,
    _In_                    ULONG Len
)
{
    KIRQL  irql;
    PUCHAR dst = (PUCHAR)Out;
    ULONG  i;

    if (Out == NULL || Len == 0) {
        return;
    }
    // A capture is pulling data: mark activity so the tray can auto-enable.
    InterlockedIncrement(&g_ReadTick);
    if (!g_Inited) {
        RtlZeroMemory(Out, Len);
        return;
    }

    KeAcquireSpinLock(&g_Lock, &irql);
    for (i = 0; i < Len; i++) {
        if (g_Count == 0) {
            dst[i] = 0;  // underrun -> silence
        } else {
            dst[i] = g_Ring[g_Tail];
            g_Tail = (g_Tail + 1u) % MIC_RING_BYTES;
            g_Count--;
        }
    }
    KeReleaseSpinLock(&g_Lock, irql);
}

//
// IOCTL handler: copy the pushed PCM into the ring.
//
static VOID
MicPipe_EvtIoDeviceControl(
    _In_ WDFQUEUE   Queue,
    _In_ WDFREQUEST Request,
    _In_ size_t     OutputBufferLength,
    _In_ size_t     InputBufferLength,
    _In_ ULONG      IoControlCode
)
{
    NTSTATUS  status = STATUS_INVALID_DEVICE_REQUEST;
    ULONG_PTR info = 0;

    UNREFERENCED_PARAMETER(Queue);
    UNREFERENCED_PARAMETER(OutputBufferLength);

    if (IoControlCode == IOCTL_LIBREPODS_MIC_WRITE_PCM && InputBufferLength > 0) {
        PVOID  buf = NULL;
        size_t len = 0;
        status = WdfRequestRetrieveInputBuffer(Request, 1, &buf, &len);
        if (NT_SUCCESS(status)) {
            MicPipeWrite(buf, (ULONG)len);
            info = len;
        }
    } else if (IoControlCode == IOCTL_LIBREPODS_MIC_STATUS) {
        PVOID  buf = NULL;
        size_t len = 0;
        status = WdfRequestRetrieveOutputBuffer(Request, sizeof(LONG), &buf, &len);
        if (NT_SUCCESS(status)) {
            *(LONG *)buf = InterlockedCompareExchange(&g_ReadTick, 0, 0);
            info = sizeof(LONG);
        }
    }

    WdfRequestCompleteWithInformation(Request, status, info);
}

NTSTATUS
MicPipeCreateControlDevice(
    _In_ WDFDEVICE Parent
)
{
    NTSTATUS             status;
    PWDFDEVICE_INIT      init = NULL;
    WDFDEVICE            ctl = NULL;
    WDFQUEUE             queue;
    WDF_IO_QUEUE_CONFIG  qCfg;

    // SYSTEM: all, Builtin Admins: RWX, Everyone: RW (so a non-elevated app can
    // open \\.\LibrePodsMic and push audio).
    DECLARE_CONST_UNICODE_STRING(sddl,
        L"D:P(A;;GA;;;SY)(A;;GRGWGX;;;BA)(A;;GRGW;;;WD)");
    DECLARE_CONST_UNICODE_STRING(ntName,  L"\\Device\\LibrePodsMic");
    DECLARE_CONST_UNICODE_STRING(symLink, L"\\DosDevices\\LibrePodsMic");

    // Driver-scoped, created once. Survives PnP device remove/re-add.
    if (g_ControlDevice != NULL) {
        return STATUS_SUCCESS;
    }

    init = WdfControlDeviceInitAllocate(WdfDeviceGetDriver(Parent), &sddl);
    if (init == NULL) {
        return STATUS_INSUFFICIENT_RESOURCES;
    }

    WdfDeviceInitSetDeviceType(init, FILE_DEVICE_UNKNOWN);
    WdfDeviceInitSetIoType(init, WdfDeviceIoBuffered);

    // Single audio source: only one process may feed the mic at a time. Two
    // writers interleaving in the ring sound like static, so refuse a second
    // open (a stuck feeder is freed on its process exit / a driver reload).
    WdfDeviceInitSetExclusive(init, TRUE);

    status = WdfDeviceInitAssignName(init, &ntName);
    if (!NT_SUCCESS(status)) {
        WdfDeviceInitFree(init);
        return status;
    }

    status = WdfDeviceCreate(&init, WDF_NO_OBJECT_ATTRIBUTES, &ctl);
    if (!NT_SUCCESS(status)) {
        WdfDeviceInitFree(init);  // WdfDeviceCreate only consumes init on success
        return status;
    }

    status = WdfDeviceCreateSymbolicLink(ctl, &symLink);
    if (!NT_SUCCESS(status)) {
        WdfObjectDelete(ctl);
        return status;
    }

    WDF_IO_QUEUE_CONFIG_INIT_DEFAULT_QUEUE(&qCfg, WdfIoQueueDispatchParallel);
    qCfg.EvtIoDeviceControl = MicPipe_EvtIoDeviceControl;
    status = WdfIoQueueCreate(ctl, &qCfg, WDF_NO_OBJECT_ATTRIBUTES, &queue);
    if (!NT_SUCCESS(status)) {
        WdfObjectDelete(ctl);
        return status;
    }

    WdfControlFinishInitializing(ctl);
    g_ControlDevice = ctl;
    return STATUS_SUCCESS;
}

EXTERN_C_END
