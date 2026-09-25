/*++

    THIS CODE AND INFORMATION IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY
    KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND/OR FITNESS FOR A PARTICULAR
    PURPOSE.

Module Name:

    SimPeakMeter.h

Abstract:

    Virtual Peakmeter - aggregates all streams

Environment:

    Kernel mode

--*/

#pragma once

class CSimPeakMeter
{
public:
    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    CSimPeakMeter();

    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    ~CSimPeakMeter();

    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    LONG GetValue(_In_ ULONG Channel);

    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS StartStream();

    __drv_maxIRQL(PASSIVE_LEVEL)
    PAGED_CODE_SEG
    NTSTATUS StopStream();

private:
    LONG m_NumStreams;
    LONG m_PeakMeterIndex;
};

