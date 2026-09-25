#pragma once

#include "SimPeakMeter.h"

#define HNSTIME_PER_MILLISECOND 10000

#define MAX_PACKET_COUNT 2

#define DEFAULT_FREQUENCY 220
#define LOOPBACK_FREQUENCY 500

// Stream callbacks shared between Capture and Render

VOID
EvtStreamDestroy(
    _In_ WDFOBJECT Object
);

PAGED_CODE_SEG
NTSTATUS
EvtStreamGetHwLatency(
    _In_ ACXSTREAM Stream,
    _Out_ ULONG* FifoSize,
    _Out_ ULONG* Delay
);

PAGED_CODE_SEG
NTSTATUS
EvtStreamAllocateRtPackets(
    _In_ ACXSTREAM Stream,
    _In_ ULONG PacketCount,
    _In_ ULONG PacketSize,
    _Out_ PACX_RTPACKET* Packets
);

PAGED_CODE_SEG
VOID
EvtStreamFreeRtPackets(
    _In_ ACXSTREAM Stream,
    _In_ PACX_RTPACKET Packets,
    _In_ ULONG PacketCount
);

PAGED_CODE_SEG
NTSTATUS
EvtStreamPrepareHardware(
    _In_ ACXSTREAM Stream
);

PAGED_CODE_SEG
NTSTATUS
EvtStreamReleaseHardware(
    _In_ ACXSTREAM Stream
);

PAGED_CODE_SEG
NTSTATUS
EvtStreamRun(
    _In_ ACXSTREAM Stream
);

PAGED_CODE_SEG
NTSTATUS
EvtStreamPause(
    _In_ ACXSTREAM Stream
);

PAGED_CODE_SEG
NTSTATUS
EvtStreamAssignDrmContentId(
    _In_ ACXSTREAM      Stream,
    _In_ ULONG          DrmContentId,
    _In_ PACXDRMRIGHTS  DrmRights
);

PAGED_CODE_SEG
NTSTATUS
EvtStreamGetCurrentPacket(
    _In_ ACXSTREAM          Stream,
    _Out_ PULONG            CurrentPacket
);

PAGED_CODE_SEG
NTSTATUS
EvtStreamGetPresentationPosition(
    _In_ ACXSTREAM          Stream,
    _Out_ PULONGLONG        PositionInBlocks,
    _Out_ PULONGLONG        QPCPosition
);


class CStreamEngine
{
public:
    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS
    AllocateRtPackets(
        _In_    ULONG           PacketCount,
        _In_    ULONG           PacketSize,
        _Out_   PACX_RTPACKET * Packets
        );

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    VOID
    FreeRtPackets(
        _Frees_ptr_ PACX_RTPACKET   Packets,
        _In_        ULONG           PacketCount
        );

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS
    PrepareHardware();

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS
    ReleaseHardware();

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS
    Run();

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS
    Pause();

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS
    GetPresentationPosition(
        _Out_   PULONGLONG      PositionInBlocks,
        _Out_   PULONGLONG      QPCPosition
        );

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS
    GetLinearBufferPosition(
        _Out_   PULONGLONG  Position
        );

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS
    SetCurrentWritePosition(
        _In_    ULONG   Position
        );

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS
    SetLastBufferPosition(
        _In_    ULONG   Position
        );

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS
    GetCurrentPacket(
        _Out_ PULONG CurrentPacket
        );

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS
    GetHWLatency(
        _Out_   ULONG         * FifoSize, 
        _Out_   ULONG         * Delay
        );

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS
    AssignDrmContentId(
        _In_ ULONG          DrmContentId,
        _In_ PACXDRMRIGHTS  DrmRights
        );

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    CSimPeakMeter *
    GetPeakMeter();

    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    CStreamEngine(
        _In_    ACXSTREAM       Stream, 
        _In_    ACXDATAFORMAT   StreamFormat,
        _In_    BOOL            Offload,
        _In_opt_    CSimPeakMeter   *CircuitPeakmeter
        );

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    ~CStreamEngine();

    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    VOID
    SetFrequency(
        _In_    DWORD           ToneFrequency 
        )
    {
        PAGED_CODE();

        m_ToneFrequency = ToneFrequency;
    }

protected:
    PVOID               m_Packets[MAX_PACKET_COUNT];
    ULONG               m_PacketsCount;
    ULONG               m_PacketSize;
    ULONG               m_FirstPacketOffset;
    WDFTIMER            m_NotificationTimer;
    ACX_STREAM_STATE    m_CurrentState;
    ULONG               m_CurrentPacket;
    ULONGLONG           m_Position;
    ACXSTREAM           m_Stream;
    ACXDATAFORMAT       m_StreamFormat;
    ULONGLONG           m_StartTime;
    ULONGLONG           m_StartPosition;
    ULONGLONG           m_GlitchAdjust;
    LARGE_INTEGER       m_PerformanceCounterFrequency;
    LARGE_INTEGER       m_CurrentPacketStart;
    LARGE_INTEGER       m_LastPacketStart;
    DWORD               m_ToneFrequency;
    BOOL                m_Offload;
    CSimPeakMeter       m_PeakMeter;
    CSimPeakMeter*      m_pCircuitPeakmeter;

    static
    __drv_maxIRQL(DISPATCH_LEVEL)
    _Function_class_(EVT_WDF_TIMER)
    VOID s_EvtStreamPassCallback(
        _In_    WDFTIMER        Timer
        );

    // This is run every time the stream timer fires
    virtual
    __drv_maxIRQL(DISPATCH_LEVEL)
    VOID
    StreamPassCallback();

    virtual
    __drv_maxIRQL(DISPATCH_LEVEL)
    VOID
    ScheduleNextPass();

    virtual
    __drv_maxIRQL(DISPATCH_LEVEL)
    VOID
    UpdatePosition();

    virtual
    __drv_maxIRQL(DISPATCH_LEVEL)
    ULONG
    GetBytesPerSecond();

    virtual
    __drv_maxIRQL(DISPATCH_LEVEL)
    VOID
    ProcessPacket() = 0;
};

class CCaptureStreamEngine : public CStreamEngine
{
public:
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    CCaptureStreamEngine(
        _In_    ACXSTREAM       Stream,
        _In_    ACXDATAFORMAT   StreamFormat
        );

    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    ~CCaptureStreamEngine();

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS
    PrepareHardware();

    virtual
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS
    ReleaseHardware();

    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS
    GetCapturePacket(
        _Out_   ULONG         * LastCapturePacket,
        _Out_   ULONGLONG     * QPCPacketStart,
        _Out_   BOOLEAN       * MoreData
        );

protected:
    virtual
    __drv_maxIRQL(DISPATCH_LEVEL)
    VOID
    ProcessPacket();
};

// Define circuit/stream pin context.
//
typedef struct _STREAM_TIMER_CONTEXT {
    CStreamEngine * StreamEngine;
} STREAM_TIMER_CONTEXT, *PSTREAM_TIMER_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(STREAM_TIMER_CONTEXT, GetStreamTimerContext)
