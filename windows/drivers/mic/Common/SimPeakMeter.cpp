/*++

    THIS CODE AND INFORMATION IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY
    KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND/OR FITNESS FOR A PARTICULAR
    PURPOSE.

Module Name:

    SimPeakMeter.cpp

Abstract:

    Virtual Peakmeter - aggregates all streams

Environment:

    Kernel mode

--*/

#include "private.h"
#include "SimPeakMeter.h"

#ifndef __INTELLISENSE__
#include "SimPeakMeter.tmh"
#endif

_Use_decl_annotations_
PAGED_CODE_SEG
CSimPeakMeter::CSimPeakMeter()
{
    PAGED_CODE();
    m_NumStreams = 0;
    m_PeakMeterIndex = 0;
}

_Use_decl_annotations_
PAGED_CODE_SEG
CSimPeakMeter::~CSimPeakMeter()
{
    PAGED_CODE();
}

_Use_decl_annotations_
PAGED_CODE_SEG
LONG CSimPeakMeter::GetValue(ULONG Channel)
{
    PAGED_CODE();

    // Ignore channel
    UNREFERENCED_PARAMETER(Channel);

#define PEAKMETER_VALUE_FULL        (PEAKMETER_MAXIMUM / PEAKMETER_STEPPING_DELTA * PEAKMETER_STEPPING_DELTA)
#define PEAKMETER_VALUE_HALF        (PEAKMETER_MAXIMUM / 2 / PEAKMETER_STEPPING_DELTA * PEAKMETER_STEPPING_DELTA)
#define PEAKMETER_VALUE_QUARTER     (PEAKMETER_MAXIMUM / 4 / PEAKMETER_STEPPING_DELTA * PEAKMETER_STEPPING_DELTA)
#define PEAKMETER_VALUE_ONE_EIGTH   (PEAKMETER_MAXIMUM / 8 / PEAKMETER_STEPPING_DELTA * PEAKMETER_STEPPING_DELTA)

    LONG PeakMeterValues[] = {
    PEAKMETER_VALUE_ONE_EIGTH,
    PEAKMETER_VALUE_QUARTER,
    PEAKMETER_VALUE_HALF,
    PEAKMETER_VALUE_FULL,
    PEAKMETER_VALUE_HALF,
    PEAKMETER_VALUE_QUARTER
    };

    if (m_NumStreams)
    {
        LONG pmi = InterlockedIncrement(&m_PeakMeterIndex);
        if (pmi == ARRAYSIZE(PeakMeterValues))
        {
            pmi = 0;
            InterlockedExchange(&m_PeakMeterIndex, 0);
        }

        return PeakMeterValues[pmi];
    }

    //
    // No active streams. Peak meter = 0
    //
    return 0;
}

_Use_decl_annotations_
PAGED_CODE_SEG
NTSTATUS CSimPeakMeter::StartStream()
{
    PAGED_CODE();
    InterlockedIncrement(&m_NumStreams);

    return STATUS_SUCCESS;
}

_Use_decl_annotations_
PAGED_CODE_SEG
NTSTATUS CSimPeakMeter::StopStream()
{
    PAGED_CODE();

    ASSERT(m_NumStreams);
    InterlockedDecrement(&m_NumStreams);

    return STATUS_SUCCESS;
}
