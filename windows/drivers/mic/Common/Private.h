/*++

Copyright (c) Microsoft Corporation.  All rights reserved.

    THIS CODE AND INFORMATION IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY
    KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND/OR FITNESS FOR A PARTICULAR
    PURPOSE.

Module Name:

    Private.h

Abstract:

    Contains structure definitions and function prototypes private to
    the Common library.

Environment:

    Kernel mode

--*/

#ifndef _PRIVATE_H_
#define _PRIVATE_H_


#include <wdm.h>
#include <windef.h>
#include "cpp_utils.h"
#include <mmsystem.h>
#include <ks.h>
#include <ksmedia.h>
#include "NewDelete.h"

/* make prototypes usable from C++ */
#ifdef __cplusplus
extern "C" {
#endif

#include <initguid.h>
#include <ntddk.h>
#include <ntstrsafe.h>
#include <ntintsafe.h>
#include "Trace.h"

#include <wdf.h>
#include <acx.h>

#define PAGED_CODE_SEG __declspec(code_seg("PAGE"))
#define INIT_CODE_SEG __declspec(code_seg("INIT"))

extern const GUID DSP_CIRCUIT_SPEAKER_GUID;
extern const GUID DSP_CIRCUIT_MICROPHONE_GUID;
extern const GUID DSP_CIRCUIT_UNIVERSALJACK_RENDER_GUID;
extern const GUID DSP_CIRCUIT_UNIVERSALJACK_CAPTURE_GUID;

/////////////////////////////////////////////////////////
//
// Driver wide definitions
//

// Copied from cfgmgr32.h
#if !defined(MAX_DEVICE_ID_LEN)
#define MAX_DEVICE_ID_LEN 200
#endif

// Number of millisecs per sec. 
#define MS_PER_SEC 1000

// Number of hundred nanosecs per sec. 
#define HNS_PER_SEC 10000000

#define REQUEST_TIMEOUT_SECONDS 5

#undef MIN
#undef MAX
#define MIN(a,b) ((a) > (b) ? (b) : (a))
#define MAX(a,b) ((a) > (b) ? (a) : (b))

#ifndef BOOL
typedef int BOOL;
#endif

#ifndef SIZEOF_ARRAY
#define SIZEOF_ARRAY(ar)        (sizeof(ar)/sizeof((ar)[0]))
#endif // !defined(SIZEOF_ARRAY)

#ifndef RGB
#define RGB(r, g, b) (DWORD)(r << 16 | g << 8 | b)
#endif

#define ALL_CHANNELS_ID             UINT32_MAX
#define MAX_CHANNELS                2
    
//
// Ks support.
//
#define KSPROPERTY_TYPE_ALL         KSPROPERTY_TYPE_BASICSUPPORT | \
                                        KSPROPERTY_TYPE_GET | \
                                        KSPROPERTY_TYPE_SET

//
// Define struct to hold signal processing mode and corresponding
// list of supported formats. 
//
typedef struct
{
    GUID SignalProcessingMode;
    KSDATAFORMAT_WAVEFORMATEXTENSIBLE* FormatList;
    ULONG FormatListCount;
} SUPPORTED_FORMATS_LIST;

//
// Define CAPTURE device context.
//
typedef struct _CAPTURE_DEVICE_CONTEXT {
    ACXCIRCUIT      Circuit;
    BOOLEAN         FirstTimePrepareHardware;
} CAPTURE_DEVICE_CONTEXT, * PCAPTURE_DEVICE_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(CAPTURE_DEVICE_CONTEXT, GetCaptureDeviceContext)

//
// Define RENDER device context.
//
typedef struct _RENDER_DEVICE_CONTEXT {
    ACXCIRCUIT      Circuit;
    BOOLEAN         FirstTimePrepareHardware;
} RENDER_DEVICE_CONTEXT, * PRENDER_DEVICE_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(RENDER_DEVICE_CONTEXT, GetRenderDeviceContext)

//
// Define circuit/stream element context.
//
typedef struct _ELEMENT_CONTEXT {
    BOOLEAN         Dummy;
} ELEMENT_CONTEXT, *PELEMENT_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(ELEMENT_CONTEXT, GetElementContext)
    
//
// Define circuit/stream element context.
//
typedef struct _MUTE_ELEMENT_CONTEXT {
    BOOL            MuteState[MAX_CHANNELS]; 
} MUTE_ELEMENT_CONTEXT, *PMUTE_ELEMENT_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(MUTE_ELEMENT_CONTEXT, GetMuteElementContext)

//
// Define circuit/stream element context.
//
typedef struct _VOLUME_ELEMENT_CONTEXT {
    LONG            VolumeLevel[MAX_CHANNELS];
} VOLUME_ELEMENT_CONTEXT, *PVOLUME_ELEMENT_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(VOLUME_ELEMENT_CONTEXT, GetVolumeElementContext)

#define VOLUME_STEPPING         0x8000
#define VOLUME_LEVEL_MAXIMUM    0x00000000
#define VOLUME_LEVEL_MINIMUM    (-96 * 0x10000)
    
//
// Define mute timer context.
//
typedef struct _MUTE_TIMER_CONTEXT {
    ACXELEMENT      MuteElement;
    ACXEVENT        Event;
} MUTE_TIMER_CONTEXT, *PMUTE_TIMER_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(MUTE_TIMER_CONTEXT, GetMuteTimerContext)

//
// Define format context.
//
typedef struct _FORMAT_CONTEXT {
    BOOLEAN         Dummy;
} FORMAT_CONTEXT, *PFORMAT_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(FORMAT_CONTEXT, GetFormatContext)

//
// Define jack context.
//
typedef struct _JACK_CONTEXT {
    ULONG Dummy;
} JACK_CONTEXT, * PJACK_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(JACK_CONTEXT, GetJackContext)

//
// Define audio engine context.
//
typedef struct _ENGINE_CONTEXT {
    ACXDATAFORMAT   MixFormat;
} ENGINE_CONTEXT, * PENGINE_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(ENGINE_CONTEXT, GetEngineContext)

//
// Define stream audio engine context.
//
typedef struct _STREAMAUDIOENGINE_CONTEXT {
    BOOLEAN         Dummy;
} STREAMAUDIOENGINE_CONTEXT, * PSTREAMAUDIOENGINE_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(STREAMAUDIOENGINE_CONTEXT, GetStreamAudioEngineContext)

//
// Define keyword spotter context
//
typedef struct _KEYWORDSPOTTER_CONTEXT {
    ACXPNPEVENT Event;
    PVOID       KeywordDetector;
} KEYWORDSPOTTER_CONTEXT, * PKEYWORDSPOTTER_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(KEYWORDSPOTTER_CONTEXT, GetKeywordSpotterContext)

//
// Define pnp event context.
//
typedef struct _PNPEVENT_CONTEXT {
    BOOLEAN         Dummy;
} PNPEVENT_CONTEXT, * PPNPEVENT_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(PNPEVENT_CONTEXT, GetPnpEventContext)

//
// Define peakmeter element context.
//
typedef struct _PEAKMETER_ELEMENT_CONTEXT {
    LONG            PeakMeterLevel[MAX_CHANNELS];
} PEAKMETER_ELEMENT_CONTEXT, * PPEAKMETER_ELEMENT_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(PEAKMETER_ELEMENT_CONTEXT, GetPeakMeterElementContext)

//
// Define DSP circuit's peakmeter element context.
//
typedef struct _DSP_PEAKMETER_ELEMENT_CONTEXT
{
    PVOID peakMeter;
} DSP_PEAKMETER_ELEMENT_CONTEXT, *PDSP_PEAKMETER_ELEMENT_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(DSP_PEAKMETER_ELEMENT_CONTEXT, GetDspPeakMeterElementContext)

//
// Define stream engine context.
//
typedef struct _STREAMENGINE_CONTEXT {
    PVOID                   StreamEngine;
} STREAMENGINE_CONTEXT, * PSTREAMENGINE_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(STREAMENGINE_CONTEXT, GetStreamEngineContext)

#define PEAKMETER_STEPPING_DELTA    0x1000
#define PEAKMETER_MAXIMUM           LONG_MAX
#define PEAKMETER_MINIMUM           LONG_MIN

/////////////////////////////////////////////////////////////
// Codec driver defintions
//

typedef enum _CODEC_PIN_TYPE {
    CodecPinTypeHost,
    CodecPinTypeOffload,
    CodecPinTypeLoopback,
    CodecPinTypeKeyword,
    CodecPinTypeDevice
} CODEC_PIN_TYPE, * PCODEC_PIN_TYPE;

typedef struct _CODEC_PIN_CONTEXT {
    CODEC_PIN_TYPE  CodecPinType;
} CODEC_PIN_CONTEXT, * PCODEC_PIN_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(CODEC_PIN_CONTEXT, GetCodecPinContext)


/////////////////////////////////////////////////////////
//
// Codec Capture (microphone) definitions
//

//
// Define capture circuit context.
//
typedef struct _CODEC_CAPTURE_CIRCUIT_CONTEXT {
    ACXVOLUME           BoostElement;
    ACXVOLUME           VolumeElement;
    ACXKEYWORDSPOTTER   KeywordSpotter;
} CODEC_CAPTURE_CIRCUIT_CONTEXT, * PCODEC_CAPTURE_CIRCUIT_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(CODEC_CAPTURE_CIRCUIT_CONTEXT, GetCaptureCircuitContext)

typedef enum {
    CodecCaptureHostPin = 0,
    CodecCaptureBridgePin = 1,
    CodecCapturePinCount = 2
} CODEC_CAPTURE_PINS;

typedef enum {
    CaptureVolumeIndex = 0,
    CaptureElementCount = 1
} CAPTURE_ELEMENTS;

// Capture callbacks.

EVT_ACX_CIRCUIT_CREATE_STREAM       CodecC_EvtCircuitCreateStream;
EVT_ACX_CIRCUIT_POWER_UP            CodecC_EvtCircuitPowerUp;
EVT_ACX_CIRCUIT_POWER_DOWN          CodecC_EvtCircuitPowerDown;
EVT_ACX_VOLUME_ASSIGN_LEVEL         CodecC_EvtVolumeAssignLevelCallback;
EVT_ACX_VOLUME_RETRIEVE_LEVEL       CodecC_EvtVolumeRetrieveLevelCallback;
EVT_ACX_VOLUME_ASSIGN_LEVEL         CodecC_EvtBoostAssignLevelCallback;
EVT_ACX_VOLUME_RETRIEVE_LEVEL       CodecC_EvtBoostRetrieveLevelCallback;
EVT_ACX_STREAM_GET_CAPTURE_PACKET   CodecC_EvtStreamGetCapturePacket;
EVT_ACX_PIN_SET_DATAFORMAT          CodecC_EvtAcxPinSetDataFormat;
EVT_ACX_PIN_RETRIEVE_NAME           CodecC_EvtAcxPinRetrieveName;
EVT_WDF_DEVICE_CONTEXT_CLEANUP      CodecC_EvtPinContextCleanup;
EVT_ACX_KEYWORDSPOTTER_RETRIEVE_ARM     CodecC_EvtAcxKeywordSpotterRetrieveArm;
EVT_ACX_KEYWORDSPOTTER_ASSIGN_ARM       CodecC_EvtAcxKeywordSpotterAssignArm;
EVT_ACX_KEYWORDSPOTTER_ASSIGN_PATTERNS  CodecC_EvtAcxKeywordSpotterAssignPatterns;
EVT_ACX_KEYWORDSPOTTER_ASSIGN_RESET     CodecC_EvtAcxKeywordSpotterAssignReset;

PAGED_CODE_SEG
NTSTATUS
CodecC_CreateCaptureCircuit(
    _In_     WDFDEVICE              Device,
    _In_     const GUID *           ComponentGuid,
    _In_     const GUID *           MicCustomName,
    _In_     const UNICODE_STRING * CircuitName,
    _Out_    ACXCIRCUIT *           Circuit
);


/* make internal prototypes usable from C++ */
#ifdef __cplusplus
}
#endif



#endif // _PRIVATE_H_
