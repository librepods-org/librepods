/*++

Copyright (c) Microsoft Corporation.  All rights reserved.

    THIS CODE AND INFORMATION IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY
    KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND/OR FITNESS FOR A PARTICULAR
    PURPOSE.

Module Name:

    Public.h

Abstract:

    Contains structure definitions and function prototypes for the driver
    that are public. 

Environment:

    Kernel mode

--*/

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

// Number of msec for idle timeout.
#define IDLE_POWER_TIMEOUT 5000

extern RECORDER_LOG g_AudioDspLog;
extern RECORDER_LOG g_AudioDspMcLog;

//
// Define CODEC device context.
//
typedef struct _CODEC_DEVICE_CONTEXT {
    ACXCIRCUIT      Render;
    ACXCIRCUIT      Capture;
    WDF_TRI_STATE   ExcludeD3Cold;
} CODEC_DEVICE_CONTEXT, * PCODEC_DEVICE_CONTEXT;

//
// Codec driver prototypes.
//
EVT_WDF_DRIVER_DEVICE_ADD           Codec_EvtBusDeviceAdd;
DRIVER_INITIALIZE                   DriverEntry;
EVT_WDF_DRIVER_UNLOAD               AudioCodecDriverUnload;

//
// Codec device callbacks.
//
EVT_WDF_DEVICE_PREPARE_HARDWARE     Codec_EvtDevicePrepareHardware;
EVT_WDF_DEVICE_RELEASE_HARDWARE     Codec_EvtDeviceReleaseHardware;
EVT_WDF_DEVICE_D0_ENTRY             Codec_EvtDeviceD0Entry;
EVT_WDF_DEVICE_D0_EXIT              Codec_EvtDeviceD0Exit;
EVT_WDF_DEVICE_CONTEXT_CLEANUP      Codec_EvtDeviceContextCleanup;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(CODEC_DEVICE_CONTEXT, GetCodecDeviceContext)

//
// Define DSP device context.
//
typedef struct _DSP_DEVICE_CONTEXT {
    ACXCIRCUIT      Speaker;
    ACXCIRCUIT      MicArray;
    ACXCIRCUIT      SpeakerHp;
    ACXCIRCUIT      MicrophoneHp;
    ACXCIRCUIT      HDMI;
    WDF_TRI_STATE   ExcludeD3Cold;
} DSP_DEVICE_CONTEXT, * PDSP_DEVICE_CONTEXT;

//
// Dsp driver prototypes.
//
EVT_WDF_DRIVER_DEVICE_ADD           Dsp_EvtBusDeviceAdd;
EVT_WDF_DRIVER_UNLOAD               AudioDspDriverUnload;

//
// Dsp device callbacks.
//
EVT_WDF_DEVICE_PREPARE_HARDWARE     Dsp_EvtDevicePrepareHardware;
EVT_WDF_DEVICE_RELEASE_HARDWARE     Dsp_EvtDeviceReleaseHardware;
EVT_WDF_DEVICE_D0_ENTRY             Dsp_EvtDeviceD0Entry;
EVT_WDF_DEVICE_D0_EXIT              Dsp_EvtDeviceD0Exit;
EVT_WDF_DEVICE_CONTEXT_CLEANUP      Dsp_EvtDeviceContextCleanup;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(DSP_DEVICE_CONTEXT, GetDspDeviceContext)

//
// Define CODECMC (multicircuit codec) device context.
//
typedef struct _CODECMC_DEVICE_CONTEXT
{
    ACXCIRCUIT Render;
    ACXCIRCUIT Capture;
    ACXCOMPOSITETEMPLATE Composite[2];
    ULONG refComposite[2];
    WDF_TRI_STATE ExcludeD3Cold;
} CODECMC_DEVICE_CONTEXT, *PCODECMC_DEVICE_CONTEXT;

//
// Multicircuit codec driver prototypes.
//
EVT_WDF_DRIVER_DEVICE_ADD CodecMc_EvtBusDeviceAdd;
DRIVER_INITIALIZE DriverEntry;
EVT_WDF_DRIVER_UNLOAD AudioCodecMcDriverUnload;

//
// Multicircuit codec device callbacks.
//
EVT_WDF_DEVICE_PREPARE_HARDWARE CodecMc_EvtDevicePrepareHardware;
EVT_WDF_DEVICE_RELEASE_HARDWARE CodecMc_EvtDeviceReleaseHardware;
EVT_WDF_DEVICE_D0_ENTRY CodecMc_EvtDeviceD0Entry;
EVT_WDF_DEVICE_D0_EXIT CodecMc_EvtDeviceD0Exit;
EVT_WDF_DEVICE_CONTEXT_CLEANUP CodecMc_EvtDeviceContextCleanup;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(CODECMC_DEVICE_CONTEXT, GetCodecMcDeviceContext)

//
// Composite type definition used in multicircuit codec.
//
typedef enum
{
    CompositeType_RENDER,
    CompositeType_CAPTURE
} CompositeType;

//
// Define DSPMC (multicircuit dsp) device context.
//
typedef struct _DSPMC_DEVICE_CONTEXT
{
    ACXCIRCUIT Render;
    ACXCIRCUIT Capture;
    WDF_TRI_STATE ExcludeD3Cold;
} DSPMC_DEVICE_CONTEXT, *PDSPMC_DEVICE_CONTEXT;

//
// Multicircuit dsp driver prototypes.
//
EVT_WDF_DRIVER_DEVICE_ADD DspMc_EvtBusDeviceAdd;
EVT_WDF_DRIVER_UNLOAD AudioDspMcDriverUnload;

//
// Multicircuit dsp device callbacks.
//
EVT_WDF_DEVICE_PREPARE_HARDWARE DspMc_EvtDevicePrepareHardware;
EVT_WDF_DEVICE_RELEASE_HARDWARE DspMc_EvtDeviceReleaseHardware;
EVT_WDF_DEVICE_D0_ENTRY DspMc_EvtDeviceD0Entry;
EVT_WDF_DEVICE_D0_EXIT DspMc_EvtDeviceD0Exit;
EVT_WDF_DEVICE_CONTEXT_CLEANUP DspMc_EvtDeviceContextCleanup;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(DSPMC_DEVICE_CONTEXT, GetDspMcDeviceContext)

// Factory circuit callbacks for multicircuit dsp. 
EVT_ACX_FACTORY_CIRCUIT_CREATE_CIRCUITDEVICE RenderMCDsp_EvtAcxFactoryCircuitCreateCircuitDevice;
EVT_ACX_FACTORY_CIRCUIT_CREATE_CIRCUIT RenderMCDsp_EvtAcxFactoryCircuitCreateCircuit;

EVT_ACX_FACTORY_CIRCUIT_CREATE_CIRCUITDEVICE CaptureMCDsp_EvtAcxFactoryCircuitCreateCircuitDevice;
EVT_ACX_FACTORY_CIRCUIT_CREATE_CIRCUIT CaptureMCDsp_EvtAcxFactoryCircuitCreateCircuit;


/* make internal prototypes usable from C++ */
#ifdef __cplusplus
}
#endif

// Used to store the registry settings path for the driver:
extern UNICODE_STRING g_RegistryPath;

// Driver tag name:
extern ULONG DeviceDriverTag;

// The idle timeout in msec for power policy structure:
extern ULONG IdleTimeoutMsec;

#define DECLARE_CONST_ACXOBJECTBAG_MULTICIRCUIT_SAMPLE_PROPERTY_NAME(name) \
    DECLARE_CONST_UNICODE_STRING(name, L"mc_" #name)

__drv_requiresIRQL(PASSIVE_LEVEL)
PAGED_CODE_SEG
NTSTATUS
CopyRegistrySettingsPath(
    _In_ PUNICODE_STRING RegistryPath
);

PAGED_CODE_SEG
NTSTATUS
Codec_SetPowerPolicy(
    _In_ WDFDEVICE      Device
);

PAGED_CODE_SEG
NTSTATUS
Dsp_SetPowerPolicy(
    _In_ WDFDEVICE      Device
);

PAGED_CODE_SEG
NTSTATUS
CodecMc_SetPowerPolicy(
    _In_ WDFDEVICE      Device
);

PAGED_CODE_SEG
NTSTATUS
DspMc_SetPowerPolicy(
    _In_ WDFDEVICE      Device
);

PAGED_CODE_SEG
NTSTATUS
CodecR_AddStaticRender(
    _In_ WDFDEVICE              Device,
    _In_ const GUID *           ComponentGuid,
    _In_ const UNICODE_STRING * CircuitName
);

PAGED_CODE_SEG
NTSTATUS
CodecC_AddStaticCapture(
    _In_ WDFDEVICE              Device,
    _In_ const GUID *           ComponentGuid,
    _In_ const GUID  *          MicCustomName,
    _In_ const UNICODE_STRING * CircuitName
);

PAGED_CODE_SEG
NTSTATUS
Speaker_AddStaticRender(
    _In_ WDFDEVICE        Device,
    _In_ GUID             ComponentGuid,
    _In_ UNICODE_STRING   CircuitName,
    _In_ BOOLEAN          IsHeadphones
);

PAGED_CODE_SEG
NTSTATUS
MicArray_AddStaticCapture(
    _In_ WDFDEVICE        Device,
    _In_ GUID             ComponentGuid,
    _In_ GUID             MicCustomName,
    _In_ UNICODE_STRING   CircuitName
);

PAGED_CODE_SEG
NTSTATUS
MicrophoneHp_AddStaticCapture(
    _In_ WDFDEVICE        Device,
    _In_ GUID             ComponentGuid,
    _In_ GUID             MicCustomName,
    _In_ UNICODE_STRING   CircuitName
);

PAGED_CODE_SEG
NTSTATUS
SpeakerHp_AddStaticRender(
    _In_ WDFDEVICE        Device,
    _In_ GUID             ComponentGuid,
    _In_ UNICODE_STRING   CircuitName
);

PAGED_CODE_SEG
NTSTATUS
HDMI_AddStaticRender(
    _In_ WDFDEVICE        Device,
    _In_ GUID             ComponentGuid,
    _In_ UNICODE_STRING   CircuitName
);

PAGED_CODE_SEG
NTSTATUS
RenderMC_AddStaticRender(
    _In_ WDFDEVICE              Device,
    _In_ const GUID *           ComponentGuid,
    _In_ const UNICODE_STRING * CircuitName,
    _In_ const UNICODE_STRING * Uri
);

PAGED_CODE_SEG
NTSTATUS
CaptureMC_AddStaticCapture(
    _In_ WDFDEVICE              Device,
    _In_ const GUID *           ComponentGuid,
    _In_ const GUID  *          MicCustomName,
    _In_ const UNICODE_STRING * CircuitName,
    _In_ const UNICODE_STRING * Uri
);

PAGED_CODE_SEG
NTSTATUS
CodecC_CircuitCleanup(
    _In_ ACXCIRCUIT Device
);

PAGED_CODE_SEG
NTSTATUS
MicArray_CircuitCleanup(
    _In_ ACXCIRCUIT Device
);

PAGED_CODE_SEG
NTSTATUS
CaptureMCDsp_CircuitCleanup(
    _In_ ACXCIRCUIT Device
);

PAGED_CODE_SEG
NTSTATUS
DspMc_AddFactoryCircuit(
    _In_ WDFDRIVER      Driver,
    _In_ WDFDEVICE      Device
);

NTSTATUS 
CodecMc_AddRenderComposites(_In_ WDFDEVICE Device);

PAGED_CODE_SEG
NTSTATUS 
CodecMc_AddCaptureComposites(_In_ WDFDEVICE Device);

PAGED_CODE_SEG
NTSTATUS 
CodecMc_AddComposites(_In_ WDFDEVICE Device, _In_ CompositeType compositeType);

NTSTATUS
CodecMc_RemoveComposites(_In_ WDFDEVICE Device);
