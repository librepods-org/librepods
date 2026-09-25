/*++

Copyright (c) Microsoft Corporation.  All rights reserved.

    THIS CODE AND INFORMATION IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY
    KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND/OR FITNESS FOR A PARTICULAR
    PURPOSE.

Module Name:

    CaptureCircuit.cpp

Abstract:

    Capture Circuit. This file contains routines to create and handle
    capture circuit.

Environment:

    Kernel mode

--*/

#include "private.h"
#include "public.h"
#include <ks.h>
#include <mmsystem.h>
#include <ksmedia.h>
#include "AudioFormats.h"
#include "streamengine.h"
#include "cpp_utils.h"
#include "circuithelper.h"

#ifndef __INTELLISENSE__
#include "captureCircuit.tmh"
#endif

//
// Controls how the custom name of the bridge pin is read.
//
BOOL g_UseCustomInfName = TRUE;

PAGED_CODE_SEG
NTSTATUS
CodecC_EvtAcxPinSetDataFormat(
    _In_    ACXPIN          Pin,
    _In_    ACXDATAFORMAT   DataFormat
)
/*++

Routine Description:

    This ACX pin callback sets the device/mixed format.

Return Value:

    NTSTATUS

--*/
{
    UNREFERENCED_PARAMETER(Pin);
    UNREFERENCED_PARAMETER(DataFormat);

    PAGED_CODE();

    // NOTE: update device/mixed format here.

    return STATUS_NOT_SUPPORTED;
}

///////////////////////////////////////////////////////////
//
// For more information on volume element see: https://docs.microsoft.com/en-us/windows-hardware/drivers/audio/ksnodetype-volume
//
_Use_decl_annotations_
NTSTATUS
CodecC_EvtVolumeAssignLevelCallback(
    _In_    ACXVOLUME   Volume,
    _In_    ULONG       Channel,
    _In_    LONG        VolumeLevel
)
{
    PAGED_CODE();

    ASSERT(Volume);
    PVOLUME_ELEMENT_CONTEXT volumeCtx = GetVolumeElementContext(Volume);
    ASSERT(volumeCtx);

    if (Channel != ALL_CHANNELS_ID)
    {
        volumeCtx->VolumeLevel[Channel] = VolumeLevel;
    }
    else
    {
        for (ULONG i = 0; i < MAX_CHANNELS; ++i)
        {
            volumeCtx->VolumeLevel[i] = VolumeLevel;
        }
    }

    return STATUS_SUCCESS;
}

_Use_decl_annotations_
NTSTATUS
CodecC_EvtVolumeRetrieveLevelCallback(
    _In_    ACXVOLUME   Volume,
    _In_    ULONG       Channel,
    _Out_   LONG *      VolumeLevel
)
{
    PAGED_CODE();

    ASSERT(Volume);
    PVOLUME_ELEMENT_CONTEXT volumeCtx = GetVolumeElementContext(Volume);
    ASSERT(volumeCtx);

    if (Channel == ALL_CHANNELS_ID)
    {
        Channel = 0;
    }

    *VolumeLevel = volumeCtx->VolumeLevel[Channel];

    return STATUS_SUCCESS;
}

PAGED_CODE_SEG
NTSTATUS
CodecC_CreateVolumeElement(
    _In_    ACXCIRCUIT  Circuit,
    _Out_   ACXVOLUME* Element
)
/*++

Routine Description:

    This routine creates a volume element.

Return Value:

    NT status value
--*/
{
    NTSTATUS                        status = STATUS_SUCCESS;
    WDF_OBJECT_ATTRIBUTES           attributes;
    ACX_VOLUME_CALLBACKS            volumeCallbacks;
    ACX_VOLUME_CONFIG               volumeCfg;
    VOLUME_ELEMENT_CONTEXT *        volumeCtx;

    PAGED_CODE();

    //
    // The driver uses this DDI to assign its volume element callbacks.
    //
    ACX_VOLUME_CALLBACKS_INIT(&volumeCallbacks);
    volumeCallbacks.EvtAcxVolumeAssignLevel = CodecC_EvtVolumeAssignLevelCallback;
    volumeCallbacks.EvtAcxVolumeRetrieveLevel = CodecC_EvtVolumeRetrieveLevelCallback;

    //
    // Create Volume element
    //
    ACX_VOLUME_CONFIG_INIT(&volumeCfg);
    volumeCfg.ChannelsCount = MAX_CHANNELS;
    volumeCfg.Minimum = VOLUME_LEVEL_MINIMUM;
    volumeCfg.Maximum = VOLUME_LEVEL_MAXIMUM;
    volumeCfg.SteppingDelta = VOLUME_STEPPING;
    volumeCfg.Callbacks = &volumeCallbacks;

    WDF_OBJECT_ATTRIBUTES_INIT_CONTEXT_TYPE(&attributes, VOLUME_ELEMENT_CONTEXT);
    attributes.ParentObject = Circuit;

    RETURN_NTSTATUS_IF_FAILED(AcxVolumeCreate(Circuit, &attributes, &volumeCfg, Element));

    ASSERT(*Element != nullptr);
    volumeCtx = GetVolumeElementContext(*Element);
    ASSERT(volumeCtx);

    //
    // (max + min)/2 puts it in the middle of the valid range, divide that by the stepping to get the nearest
    // valid step, multiply that by stepping to put it back at a level value.
    //
    volumeCtx->VolumeLevel[0] = (VOLUME_LEVEL_MAXIMUM + VOLUME_LEVEL_MINIMUM) / 2 / VOLUME_STEPPING * VOLUME_STEPPING;
    volumeCtx->VolumeLevel[1] = (VOLUME_LEVEL_MAXIMUM + VOLUME_LEVEL_MINIMUM) / 2 / VOLUME_STEPPING * VOLUME_STEPPING;

    return status;
}

PAGED_CODE_SEG
NTSTATUS
CodecC_EvtAcxPinRetrieveName(
    _In_    ACXPIN              Pin,
    _Out_   PUNICODE_STRING     Name
)
/*++

Routine Description:

    If g_UseCustomInfName is false then the ACX
    pin callback EvtAcxPinRetrieveName calls this
    function in order to retrieve the pin name. 

Return Value:

    NTSTATUS

--*/
{
    UNREFERENCED_PARAMETER(Pin);

    PAGED_CODE();

    return RtlUnicodeStringPrintf(Name, L"LibrePods");
}

VOID
CodecC_EvtPinContextCleanup(
    _In_ WDFOBJECT      WdfPin
)
/*++

Routine Description:

    In this callback, it cleans up pin context.

Arguments:

    WdfDevice - WDF device object

Return Value:

    nullptr

--*/
{
    UNREFERENCED_PARAMETER(WdfPin);
}

PAGED_CODE_SEG
NTSTATUS
CodecC_CircuitCleanup(
    _In_ ACXCIRCUIT Circuit
)
{
    PCODEC_CAPTURE_CIRCUIT_CONTEXT  circuitCtx;

    PAGED_CODE();

    //
    // Remove the static capture circuit.
    //
    circuitCtx = GetCaptureCircuitContext(Circuit);
    ASSERT(circuitCtx != nullptr);

    return STATUS_SUCCESS;
}

PAGED_CODE_SEG
NTSTATUS
CodecC_AddStaticCapture(
    _In_ WDFDEVICE              Device,
    _In_ const GUID *           ComponentGuid,
    _In_ const GUID *           MicCustomName,
    _In_ const UNICODE_STRING * CircuitName
)
/*++

Routine Description:

    Creates the static capture circuit (pictured below)
    and adds it to the device context. This is called
    when a new device is detected and the AddDevice
    call is made by the pnp manager.

    ******************************************************
    * Capture Circuit                                    *
    *                                                    *
    *              +-----------------------+             *
    *              |                       |             *
    *              |    +-------------+    |             *
    * Host  ------>|    | Volume Node |    |---> Bridge  *
    * Pin          |    +-------------+    |      Pin    *
    *              |                       |             *
    *              +-----------------------+             *
    *                                                    *
    ******************************************************

Return Value:

    NTSTATUS

--*/
{
    NTSTATUS                        status = STATUS_SUCCESS;
    PCODEC_DEVICE_CONTEXT           devCtx;
    PCAPTURE_DEVICE_CONTEXT         captureDevCtx;
    ACXCIRCUIT                      captureCircuit = nullptr;
    WDF_OBJECT_ATTRIBUTES           attributes;

    PAGED_CODE();

    devCtx = GetCodecDeviceContext(Device);
    ASSERT(devCtx != nullptr);

    //
    // Alloc audio context to current device.
    //
    WDF_OBJECT_ATTRIBUTES_INIT_CONTEXT_TYPE(&attributes, CAPTURE_DEVICE_CONTEXT);
    RETURN_NTSTATUS_IF_FAILED(WdfObjectAllocateContext(Device, &attributes, (PVOID*)&captureDevCtx));
    ASSERT(captureDevCtx);

    //
    // Create a capture circuit associated with this child device.
    //
    RETURN_NTSTATUS_IF_FAILED(CodecC_CreateCaptureCircuit(Device, ComponentGuid, MicCustomName, CircuitName, &captureCircuit));

    devCtx->Capture = captureCircuit;

    return status;
}

PAGED_CODE_SEG
NTSTATUS
Capture_AllocateSupportedFormats(
    _In_                                   WDFDEVICE        Device,
    _In_reads_bytes_(CodecCapturePinCount) ACXPIN           Pin[],
    _In_                                   ACXCIRCUIT       Circuit,
    _In_                                   size_t           CodecCapturePinCount
)
{
    UNREFERENCED_PARAMETER(CodecCapturePinCount);

    NTSTATUS status = STATUS_SUCCESS;
    ACXDATAFORMAT formatPcm48000c1;
    ACXDATAFORMATLIST formatList;

    WDF_OBJECT_ATTRIBUTES attributes;
    WDF_OBJECT_ATTRIBUTES_INIT(&attributes);

    ///////////////////////////////////////////////////////////
    //
    // Allocate the formats this circuit supports.
    //

    // Offer 48000 Hz only: LibrePods always feeds decoded audio resampled to
    // 48 kHz, so exposing 44100 too made apps (e.g. Voice Recorder) open at
    // 44100 and play our 48 kHz samples ~8% slow (deep/robotic).
    RETURN_NTSTATUS_IF_FAILED(AllocateFormat(Pcm48000c1, Circuit, Device, &formatPcm48000c1));

    ///////////////////////////////////////////////////////////
    //
    // Define supported formats for the host pin.
    //

    //
    // The raw processing mode list is associated with each single circuit
    // by ACX. A driver uses this DDI to retrieve the built-in raw
    // data-format list.
    //
    RETURN_NTSTATUS_IF_TRUE(CodecCaptureHostPin >= CodecCapturePinCount, STATUS_INVALID_PARAMETER);
    formatList = AcxPinGetRawDataFormatList(Pin[CodecCaptureHostPin]);
    RETURN_NTSTATUS_IF_TRUE(formatList == nullptr, STATUS_INSUFFICIENT_RESOURCES);

    //
    // The driver uses this DDI to add data formats to the raw
    // processing mode list associated with the current circuit.
    //
    RETURN_NTSTATUS_IF_FAILED(AcxDataFormatListAddDataFormat(formatList, formatPcm48000c1));

    return status;
}

PAGED_CODE_SEG
NTSTATUS
CodecC_CreateCaptureCircuit(
    _In_     WDFDEVICE              Device,
    _In_     const GUID *           ComponentGuid,
    _In_     const GUID *           MicCustomName,
    _In_     const UNICODE_STRING * CircuitName,
    _Out_    ACXCIRCUIT*            Circuit
)
/*++

Routine Description:

    This routine builds the CODEC capture circuit.

Return Value:

    NT status value

--*/
{
    NTSTATUS                        status;
    WDF_OBJECT_ATTRIBUTES           attributes;
    ACXCIRCUIT                      circuit;
    CODEC_CAPTURE_CIRCUIT_CONTEXT*  circuitCtx;
    ACXPIN                          pin[CodecCapturePinCount];

    PAGED_CODE();

    //
    // Init output value.
    //
    *Circuit = nullptr;

    ///////////////////////////////////////////////////////////
    //
    // Create a circuit.
    //
    {
        PACXCIRCUIT_INIT                circuitInit = nullptr;
        ACX_CIRCUIT_PNPPOWER_CALLBACKS  powerCallbacks;

        //
        // The driver uses this DDI to allocate an ACXCIRCUIT_INIT
        // structure. This opaque structure is used when creating
        // a standalone audio circuit representing an audio device. 
        //
        circuitInit = AcxCircuitInitAllocate(Device);

        //
        // A driver uses this DDI to free the allocated
        // ACXCIRCUIT_INIT structure when an error is detected.
        // Normally the structures is deleted/cleared by ACX when
        // an ACX circuit is created successfully.
        //
        auto circuitInitScope = scope_exit([&circuitInit]() {
            if (circuitInit) {
                AcxCircuitInitFree(circuitInit);
            }
            });

        //
        // The driver uses this DDI to specify the Component ID
        // of the ACX circuit. This ID is a guid that uniquely
        // identifies the circuit instance (vendor specific).
        //
        AcxCircuitInitSetComponentId(circuitInit, ComponentGuid);

        //
        // The driver uses this DDI to specify the circuit name.
        // For standalone circuits, this is the audio device name
        // which is used by clients to open handles to the audio devices.
        //
        (VOID)AcxCircuitInitAssignName(circuitInit, CircuitName);

        //
        // The driver uses this DDI to specify the circuit type. The
        // circuit type can be AcxCircuitTypeRender, AcxCircuitTypeCapture,
        // AcxCircuitTypeOther, or AcxCircuitTypeMaximum (for validation). 
        //
        AcxCircuitInitSetCircuitType(circuitInit, AcxCircuitTypeCapture);

        //
        // The driver uses this DDI to assign its (if any) power callbacks.
        //
        ACX_CIRCUIT_PNPPOWER_CALLBACKS_INIT(&powerCallbacks);
        powerCallbacks.EvtAcxCircuitPowerUp = CodecC_EvtCircuitPowerUp;
        powerCallbacks.EvtAcxCircuitPowerDown = CodecC_EvtCircuitPowerDown;
        AcxCircuitInitSetAcxCircuitPnpPowerCallbacks(circuitInit, &powerCallbacks);

        //
        // The driver uses this DDI to register for a stream-create callback.
        //
        RETURN_NTSTATUS_IF_FAILED(AcxCircuitInitAssignAcxCreateStreamCallback(circuitInit, CodecC_EvtCircuitCreateStream));

        //
        // The driver uses this DDI to create a new ACX circuit.
        //
        WDF_OBJECT_ATTRIBUTES_INIT_CONTEXT_TYPE(&attributes, CODEC_CAPTURE_CIRCUIT_CONTEXT);
        RETURN_NTSTATUS_IF_FAILED(AcxCircuitCreate(Device, &attributes, &circuitInit, &circuit));

        circuitInitScope.release();

        circuitCtx = GetCaptureCircuitContext(circuit);
        ASSERT(circuitCtx);
    }

    //
    // Post circuit creation initialization.
    //

    ///////////////////////////////////////////////////////////
    //
    // Create volume element.
    //
    {
        ACXELEMENT elements[CaptureElementCount] = { 0 };

        RETURN_NTSTATUS_IF_FAILED(CodecC_CreateVolumeElement(circuit, (ACXVOLUME*)&elements[CaptureVolumeIndex]));

        //
        // Saving the volume element in the circuit context.
        //
        circuitCtx->VolumeElement = (ACXVOLUME)elements[CaptureVolumeIndex];

        //
        // The driver uses this DDI post circuit creation to add ACXELEMENTs.
        //
        RETURN_NTSTATUS_IF_FAILED(AcxCircuitAddElements(circuit, elements, SIZEOF_ARRAY(elements)));
    }

    ///////////////////////////////////////////////////////////
    //
    // Create the pins for the circuit. 
    //
    {
        ACX_PIN_CALLBACKS               pinCallbacks;
        ACX_PIN_CONFIG                  pinCfg;
        CODEC_PIN_CONTEXT*              pinCtx;

        ///////////////////////////////////////////////////////////
        //
        // Create capture streaming pin.
        //
        ACX_PIN_CALLBACKS_INIT(&pinCallbacks);
        pinCallbacks.EvtAcxPinSetDataFormat = CodecC_EvtAcxPinSetDataFormat;

        ACX_PIN_CONFIG_INIT(&pinCfg);
        pinCfg.Type = AcxPinTypeSource;
        pinCfg.Communication = AcxPinCommunicationSink;
        pinCfg.Category = &KSCATEGORY_AUDIO;
        pinCfg.PinCallbacks = &pinCallbacks;

        WDF_OBJECT_ATTRIBUTES_INIT_CONTEXT_TYPE(&attributes, CODEC_PIN_CONTEXT);
        attributes.EvtCleanupCallback = CodecC_EvtPinContextCleanup;
        attributes.ParentObject = circuit;

        //
        // The driver uses this DDI to create one or more pins on the circuits.
        //
        RETURN_NTSTATUS_IF_FAILED(AcxPinCreate(circuit, &attributes, &pinCfg, &(pin[CodecCaptureHostPin])));

        ASSERT(pin[CodecCaptureHostPin] != nullptr);
        pinCtx = GetCodecPinContext(pin[CodecCaptureHostPin]);
        ASSERT(pinCtx);
        pinCtx->CodecPinType = CodecPinTypeHost;

        ///////////////////////////////////////////////////////////
        //
        // Create capture endpoint pin. 
        //
        ACX_PIN_CALLBACKS_INIT(&pinCallbacks);
        ACX_PIN_CONFIG_INIT(&pinCfg);

        pinCfg.Type = AcxPinTypeSink;
        pinCfg.Communication = AcxPinCommunicationNone;
        pinCfg.Category = &KSNODETYPE_MICROPHONE;
        pinCfg.PinCallbacks = &pinCallbacks;

        // Specify how to read the custom name.
        if (g_UseCustomInfName)
        {
            pinCfg.Name = MicCustomName;
        }
        else
        {
            pinCallbacks.EvtAcxPinRetrieveName = CodecC_EvtAcxPinRetrieveName;
        }
        g_UseCustomInfName = !g_UseCustomInfName;

        WDF_OBJECT_ATTRIBUTES_INIT(&attributes);
        attributes.ParentObject = circuit;

        //
        // The driver uses this DDI to create one or more pins on the circuits.
        //
        RETURN_NTSTATUS_IF_FAILED(AcxPinCreate(circuit, &attributes, &pinCfg, &(pin[CodecCaptureBridgePin])));

        ASSERT(pin[CodecCaptureBridgePin] != nullptr);
    }

    ///////////////////////////////////////////////////////////
    //
    // Add audio jack to bridge pin. 
    // For more information on audio jack see: https://docs.microsoft.com/en-us/windows/win32/api/devicetopology/ns-devicetopology-ksjack_description
    //
    {
        ACX_JACK_CONFIG                 jackCfg;
        ACXJACK                         jack;
        PJACK_CONTEXT                   jackCtx;

        ACX_JACK_CONFIG_INIT(&jackCfg);
        jackCfg.Description.ChannelMapping = SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT;
        jackCfg.Description.Color = RGB(0, 0, 0);
        jackCfg.Description.ConnectionType = AcxConnTypeAtapiInternal;
        jackCfg.Description.GeoLocation = AcxGeoLocFront;
        jackCfg.Description.GenLocation = AcxGenLocPrimaryBox;
        jackCfg.Description.PortConnection = AcxPortConnIntegratedDevice;

        WDF_OBJECT_ATTRIBUTES_INIT_CONTEXT_TYPE(&attributes, JACK_CONTEXT);
        attributes.ParentObject = pin[CodecCaptureBridgePin];

        RETURN_NTSTATUS_IF_FAILED(AcxJackCreate(pin[CodecCaptureBridgePin], &attributes, &jackCfg, &jack));

        ASSERT(jack != nullptr);

        jackCtx = GetJackContext(jack);
        ASSERT(jackCtx);
        jackCtx->Dummy = 0;

        RETURN_NTSTATUS_IF_FAILED(AcxPinAddJacks(pin[CodecCaptureBridgePin], &jack, 1));
    }

    RETURN_NTSTATUS_IF_FAILED(Capture_AllocateSupportedFormats(Device, pin, circuit, CodecCapturePinCount));

    ///////////////////////////////////////////////////////////
    //
    // The driver uses this DDI post circuit creation to add ACXPINs.
    //
    RETURN_NTSTATUS_IF_FAILED(AcxCircuitAddPins(circuit, pin, CodecCapturePinCount));

    //
    // Set output value.
    //
    *Circuit = circuit;

    //
    // Done. 
    //
    status = STATUS_SUCCESS;

    return status;
}

_Use_decl_annotations_
NTSTATUS
CodecC_EvtCircuitPowerUp(
    _In_ WDFDEVICE  Device,
    _In_ ACXCIRCUIT Circuit,
    _In_ WDF_POWER_DEVICE_STATE PreviousState
)
{
    UNREFERENCED_PARAMETER(Device);
    UNREFERENCED_PARAMETER(Circuit);
    UNREFERENCED_PARAMETER(PreviousState);

    PAGED_CODE();

    return STATUS_SUCCESS;
}

_Use_decl_annotations_
NTSTATUS
CodecC_EvtCircuitPowerDown(
    _In_ WDFDEVICE  Device,
    _In_ ACXCIRCUIT Circuit,
    _In_ WDF_POWER_DEVICE_STATE TargetState
)
{
    UNREFERENCED_PARAMETER(Device);
    UNREFERENCED_PARAMETER(Circuit);
    UNREFERENCED_PARAMETER(TargetState);

    PAGED_CODE();

    return STATUS_SUCCESS;
}

PAGED_CODE_SEG
NTSTATUS
CodecC_EvtCircuitCreateStream(
    _In_    WDFDEVICE       Device,
    _In_    ACXCIRCUIT      Circuit,
    _In_    ACXPIN          Pin,
    _In_    PACXSTREAM_INIT StreamInit,
    _In_    ACXDATAFORMAT   StreamFormat,
    _In_    const GUID    * SignalProcessingMode,
    _In_    ACXOBJECTBAG    VarArguments
)
/*++

Routine Description:

    This routine creates a stream for the specified circuit.

Return Value:

    NT status value

--*/
{
    NTSTATUS                        status;
    PCAPTURE_DEVICE_CONTEXT         devCtx;
    WDF_OBJECT_ATTRIBUTES           attributes;
    ACXSTREAM                       stream;
    STREAMENGINE_CONTEXT *          streamCtx;
    ACX_STREAM_CALLBACKS            streamCallbacks;
    ACX_RT_STREAM_CALLBACKS         rtCallbacks;
    CCaptureStreamEngine *          streamEngine = nullptr;
    CODEC_CAPTURE_CIRCUIT_CONTEXT * circuitCtx;
    CODEC_PIN_CONTEXT *             pinCtx;

    auto streamEngineScope = scope_exit([&streamEngine]() {

        if (streamEngine)
        {
            delete streamEngine;
        }

        });

    PAGED_CODE();
    UNREFERENCED_PARAMETER(SignalProcessingMode);
    UNREFERENCED_PARAMETER(VarArguments);

    ASSERT(IsEqualGUID(*SignalProcessingMode, AUDIO_SIGNALPROCESSINGMODE_RAW));

    devCtx = GetCaptureDeviceContext(Device);
    ASSERT(devCtx != nullptr);

    circuitCtx = GetCaptureCircuitContext(Circuit);
    ASSERT(circuitCtx != nullptr);

    pinCtx = GetCodecPinContext(Pin);
    ASSERT(pinCtx != nullptr);

    //
    // Init streaming callbacks.
    //
    ACX_STREAM_CALLBACKS_INIT(&streamCallbacks);
    streamCallbacks.EvtAcxStreamPrepareHardware = EvtStreamPrepareHardware;
    streamCallbacks.EvtAcxStreamReleaseHardware = EvtStreamReleaseHardware;
    streamCallbacks.EvtAcxStreamRun = EvtStreamRun;
    streamCallbacks.EvtAcxStreamPause = EvtStreamPause;

    RETURN_NTSTATUS_IF_FAILED(AcxStreamInitAssignAcxStreamCallbacks(StreamInit, &streamCallbacks));

    //
    // Init RT streaming callbacks.
    //
    ACX_RT_STREAM_CALLBACKS_INIT(&rtCallbacks);
    rtCallbacks.EvtAcxStreamGetHwLatency = EvtStreamGetHwLatency;
    rtCallbacks.EvtAcxStreamAllocateRtPackets = EvtStreamAllocateRtPackets;
    rtCallbacks.EvtAcxStreamFreeRtPackets = EvtStreamFreeRtPackets;
    rtCallbacks.EvtAcxStreamGetCapturePacket = CodecC_EvtStreamGetCapturePacket;
    rtCallbacks.EvtAcxStreamGetCurrentPacket = EvtStreamGetCurrentPacket;
    rtCallbacks.EvtAcxStreamGetPresentationPosition = EvtStreamGetPresentationPosition;

    RETURN_NTSTATUS_IF_FAILED(AcxStreamInitAssignAcxRtStreamCallbacks(StreamInit, &rtCallbacks));

    //
    // Buffer notifications are supported.
    //
    AcxStreamInitSetAcxRtStreamSupportsNotifications(StreamInit);

    //
    // Create the stream.
    //
    WDF_OBJECT_ATTRIBUTES_INIT_CONTEXT_TYPE(&attributes, STREAMENGINE_CONTEXT);
    attributes.EvtDestroyCallback = EvtStreamDestroy;
    RETURN_NTSTATUS_IF_FAILED(AcxRtStreamCreate(Device, Circuit, &attributes, &StreamInit, &stream));

    streamCtx = GetStreamEngineContext(stream);
    ASSERT(streamCtx);

    //
    // Create the virtual streaming engine which will control
    // streaming logic for the capture circuit. 
    //
    streamEngine = new (POOL_FLAG_NON_PAGED, DeviceDriverTag) CCaptureStreamEngine(stream, StreamFormat);
    RETURN_NTSTATUS_IF_TRUE(streamEngine == nullptr, STATUS_INSUFFICIENT_RESOURCES);

    streamCtx->StreamEngine = (PVOID)streamEngine;
 
    streamEngine = nullptr;

    //
    // Done. 
    //
    status = STATUS_SUCCESS;

    return status;
}

PAGED_CODE_SEG
NTSTATUS
CodecC_EvtStreamGetCapturePacket(
    _In_ ACXSTREAM          Stream,
    _Out_ ULONG           * LastCapturePacket,
    _Out_ ULONGLONG       * QPCPacketStart,
    _Out_ BOOLEAN         * MoreData
)
{
    PSTREAMENGINE_CONTEXT ctx;
    CCaptureStreamEngine* streamEngine = nullptr;

    PAGED_CODE();

    ctx = GetStreamEngineContext(Stream);

    streamEngine = static_cast<CCaptureStreamEngine*>(ctx->StreamEngine);

    return streamEngine->GetCapturePacket(LastCapturePacket, QPCPacketStart, MoreData);
}


