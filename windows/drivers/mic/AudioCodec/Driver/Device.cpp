/*++

Copyright (c) Microsoft Corporation.  All rights reserved.

Module Name:

    Device.cpp - Device handling events for example driver.

Abstract:

   This file contains the device entry points and callbacks.

Environment:

    Kernel-mode Driver Framework

--*/

#include <wdm.h>
#include <windef.h>
#include "public.h"
#include <devguid.h>
#include <ks.h>
#include <mmsystem.h>
#include <ksmedia.h>
#include "streamengine.h"
#include "MicPipe.h"
#include "DriverSettings.h"

#ifndef __INTELLISENSE__
#include "device.tmh"
#endif

UNICODE_STRING g_RegistryPath = { 0 };      // This is used to store the registry settings path for the driver

ULONG DeviceDriverTag = DRIVER_TAG;

ULONG IdleTimeoutMsec = IDLE_TIMEOUT_MSEC;

__drv_requiresIRQL(PASSIVE_LEVEL)
PAGED_CODE_SEG
NTSTATUS
CopyRegistrySettingsPath(
    _In_ PUNICODE_STRING RegistryPath
)
/*++

Routine Description:

Copies the following registry path to a global variable.

\REGISTRY\MACHINE\SYSTEM\ControlSetxxx\Services\<driver>\Parameters

Arguments:

RegistryPath - Registry path passed to DriverEntry

Returns:

NTSTATUS - SUCCESS if able to configure the framework

--*/

{
    PAGED_CODE();

    //
    // Initializing the unicode string, so that if it is not allocated it will not be deallocated too.
    //
    RtlInitUnicodeString(&g_RegistryPath, nullptr);

    g_RegistryPath.MaximumLength = RegistryPath->Length + sizeof(WCHAR);

    g_RegistryPath.Buffer = (PWCH)ExAllocatePool2(POOL_FLAG_PAGED, g_RegistryPath.MaximumLength, DRIVER_TAG);

    if (g_RegistryPath.Buffer == nullptr)
    {
        return STATUS_INSUFFICIENT_RESOURCES;
    }

    RtlAppendUnicodeToString(&g_RegistryPath, RegistryPath->Buffer);

    return STATUS_SUCCESS;
}

PAGED_CODE_SEG
NTSTATUS
Codec_EvtBusDeviceAdd(
    _In_    WDFDRIVER        Driver,
    _Inout_ PWDFDEVICE_INIT  DeviceInit
)
/*++

Routine Description:

    EvtDeviceAdd is called by the framework in response to AddDevice
    call from the PnP manager. We create and initialize a device object to
    represent a new instance of the device. All the software resources
    should be allocated in this callback.

Arguments:
    Driver - Handle to a framework driver object created in DriverEntry

    DeviceInit - Pointer to a framework-allocated WDFDEVICE_INIT structure.

Return Value:

    NTSTATUS

--*/
{
    NTSTATUS                            status = STATUS_SUCCESS;
    WDF_OBJECT_ATTRIBUTES               attributes;
    WDF_DEVICE_PNP_CAPABILITIES         pnpCaps;
    ACX_DEVICEINIT_CONFIG               devInitCfg;
    ACX_DEVICE_CONFIG                   devCfg;
    WDFDEVICE                           device = nullptr;
    PCODEC_DEVICE_CONTEXT               devCtx;
    WDF_PNPPOWER_EVENT_CALLBACKS        pnpPowerCallbacks;

    PAGED_CODE();

    UNREFERENCED_PARAMETER(Driver);

    //
    // The driver calls this DDI in its AddDevice callback before creating the PnP device.
    // ACX uses this call to add default/standard settings for the device to be created.
    //
    ACX_DEVICEINIT_CONFIG_INIT(&devInitCfg);
    RETURN_IF_FAILED(AcxDeviceInitInitialize(DeviceInit, &devInitCfg));

    //
    // Initialize the pnpPowerCallbacks structure.  Callback events for PNP
    // and Power are specified here.  If you don't supply any callbacks,
    // the Framework will take appropriate default actions based on whether
    // DeviceInit is initialized to be an FDO, a PDO or a filter device
    // object.
    //
    WDF_PNPPOWER_EVENT_CALLBACKS_INIT(&pnpPowerCallbacks);
    pnpPowerCallbacks.EvtDevicePrepareHardware = Codec_EvtDevicePrepareHardware;
    pnpPowerCallbacks.EvtDeviceReleaseHardware = Codec_EvtDeviceReleaseHardware;
    pnpPowerCallbacks.EvtDeviceD0Entry = Codec_EvtDeviceD0Entry;
    pnpPowerCallbacks.EvtDeviceD0Exit = Codec_EvtDeviceD0Exit;
    WdfDeviceInitSetPnpPowerEventCallbacks(DeviceInit, &pnpPowerCallbacks);

    WDF_OBJECT_ATTRIBUTES_INIT_CONTEXT_TYPE(&attributes, CODEC_DEVICE_CONTEXT);
    attributes.EvtCleanupCallback = Codec_EvtDeviceContextCleanup;

    RETURN_NTSTATUS_IF_FAILED(WdfDeviceCreate(&DeviceInit, &attributes, &device));

    //
    // Init Codec's device context.
    //
    devCtx = GetCodecDeviceContext(device);
    ASSERT(devCtx != nullptr);

    devCtx->Render = nullptr;
    devCtx->Capture = nullptr;
    devCtx->ExcludeD3Cold = WdfFalse;

    //
    // The driver calls this DDI in its AddDevice callback after creating the PnP
    // device. ACX uses this call to apply any post device settings.
    //
    ACX_DEVICE_CONFIG_INIT(&devCfg);
    RETURN_NTSTATUS_IF_FAILED(AcxDeviceInitialize(device, &devCfg));

    //
    // Tell the framework to set the SurpriseRemovalOK in the DeviceCaps so
    // that you don't get the popup in usermode (on Win2K) when you surprise
    // remove the device.
    //
    WDF_DEVICE_PNP_CAPABILITIES_INIT(&pnpCaps);
    pnpCaps.SurpriseRemovalOK = WdfTrue;
    WdfDeviceSetPnpCapabilities(device, &pnpCaps);

    //
    // Create a render circuit and capture circuit and add them to the current
    // device context. These circuits will be added to the device when the
    // prepare hardware callback is called. 
    //
    // LibrePodsMic is capture-only (a virtual microphone). No render/speaker
    // circuit — so Windows never exposes a phantom output device that could grab
    // the default output.
    RETURN_NTSTATUS_IF_FAILED(CodecC_AddStaticCapture(device, &CODEC_CAPTURE_COMPONENT_GUID, &MIC_CUSTOM_NAME, &captureCircuitName));

    //
    // LibrePods mic bridge (Phase 2): init the PCM ring and expose the control
    // device (\\.\LibrePodsMic) so user mode can push the decoded AirPods audio
    // into the capture stream. Best-effort — a failure here must not fail device
    // add (the mic still enumerates, just without a user-mode feed).
    //
    MicPipeInit();
    (VOID)MicPipeCreateControlDevice(device);

    return status;
}

PAGED_CODE_SEG
NTSTATUS
Codec_EvtDevicePrepareHardware(
    _In_ WDFDEVICE      Device,
    _In_ WDFCMRESLIST   ResourceList,
    _In_ WDFCMRESLIST   ResourceListTranslated
)
/*++

Routine Description:

    In this callback, the driver does whatever is necessary to make the
    hardware ready to use.

Arguments:

    Device - handle to a device

Return Value:

    NT status value

--*/
{
    NTSTATUS                status = STATUS_SUCCESS;
    PCODEC_DEVICE_CONTEXT   devCtx;

    UNREFERENCED_PARAMETER(ResourceList);
    UNREFERENCED_PARAMETER(ResourceListTranslated);

    PAGED_CODE();

    devCtx = GetCodecDeviceContext(Device);
    ASSERT(devCtx != nullptr);

    // NOTE: Download firmware here.

    // NOTE: Register streaming h/w resources here.

    //
    // Set power policy data.
    //
    RETURN_NTSTATUS_IF_FAILED(Codec_SetPowerPolicy(Device));

    //
    // The driver uses this DDI to associate a circuit to a device. After
    // this call the circuit is not visible until the device goes in D0.
    // For a real driver there should be a check here to make sure the
    // circuit has not been added already (there could be a situation where
    // prepareHardware is called multiple times and releaseHardware is only
    // called once). 
    //

    ASSERT(devCtx->Capture);
    RETURN_NTSTATUS_IF_FAILED(AcxDeviceAddCircuit(Device, devCtx->Capture));

    return status;
}

PAGED_CODE_SEG
NTSTATUS
Codec_EvtDeviceReleaseHardware(
    _In_ WDFDEVICE      Device,
    _In_ WDFCMRESLIST   ResourceListTranslated
)
/*++

Routine Description:

    In this callback, the driver releases the h/w resources allocated in the
    prepare h/w callback.

Arguments:

    Device - handle to a device

Return Value:

    NT status value

--*/
{
    NTSTATUS                status;
    PCODEC_DEVICE_CONTEXT   devCtx;

    UNREFERENCED_PARAMETER(Device);
    UNREFERENCED_PARAMETER(ResourceListTranslated);

    PAGED_CODE();

    devCtx = GetCodecDeviceContext(Device);
    ASSERT(devCtx != nullptr);

    //
    // The driver uses this DDI to delete a circuit from the current device. 
    //
    RETURN_NTSTATUS_IF_FAILED(AcxDeviceRemoveCircuit(Device, devCtx->Capture));

    // NOTE: Release streaming h/w resources here.

    status = STATUS_SUCCESS;

    return status;
}

PAGED_CODE_SEG
NTSTATUS
Codec_EvtDeviceD0Entry(
    _In_  WDFDEVICE              Device,
    _In_  WDF_POWER_DEVICE_STATE PreviousState
)
{
    UNREFERENCED_PARAMETER(Device);
    UNREFERENCED_PARAMETER(PreviousState);

    PAGED_CODE();

    return STATUS_SUCCESS;
}

NTSTATUS
Codec_EvtDeviceD0Exit(
    _In_  WDFDEVICE              Device,
    _In_  WDF_POWER_DEVICE_STATE TargetState
)
{
    NTSTATUS        status = STATUS_SUCCESS;
    POWER_ACTION    powerAction;

    PAGED_CODE();

    powerAction = WdfDeviceGetSystemPowerAction(Device);

    // 
    // Update the power policy D3-cold info for Connected Standby.
    //
    if (TargetState == WdfPowerDeviceD3 && powerAction == PowerActionNone)
    {
        PCODEC_DEVICE_CONTEXT   devCtx;
        WDF_TRI_STATE           excludeD3Cold = WdfTrue;
        ACX_DX_EXIT_LATENCY     latency;

        devCtx = GetCodecDeviceContext(Device);
        ASSERT(devCtx != nullptr);

        //
        // Get the current exit latency.
        //
        latency = AcxDeviceGetCurrentDxExitLatency(Device,
            WdfDeviceGetSystemPowerAction(Device),
            TargetState);

        //
        // If the current exit latency for the ACX device is responsive
        // (not instant or fast) then D3-cold does not need to be excluded.
        // Otherwise, D3-cold should be excluded because if the hardware
        // goes into this state it will take too long to go back into D0
        // and respond. 
        //
        if (latency == AcxDxExitLatencyResponsive)
        {
            excludeD3Cold = WdfFalse;
        }

        if (devCtx->ExcludeD3Cold != excludeD3Cold)
        {
            devCtx->ExcludeD3Cold = excludeD3Cold;

            RETURN_NTSTATUS_IF_FAILED(Codec_SetPowerPolicy(Device));
        }
    }

    return status;
}

PAGED_CODE_SEG
NTSTATUS
Codec_SetPowerPolicy(
    _In_ WDFDEVICE      Device
)
{
    NTSTATUS                status = STATUS_SUCCESS;
    PCODEC_DEVICE_CONTEXT   devCtx;

    PAGED_CODE();

    devCtx = GetCodecDeviceContext(Device);
    ASSERT(devCtx != nullptr);

    //
    // Init the idle policy structure.
    //
    WDF_DEVICE_POWER_POLICY_IDLE_SETTINGS idleSettings;
    WDF_DEVICE_POWER_POLICY_IDLE_SETTINGS_INIT(&idleSettings, IdleCannotWakeFromS0);
    idleSettings.IdleTimeout = IDLE_POWER_TIMEOUT;
    idleSettings.IdleTimeoutType = SystemManagedIdleTimeoutWithHint;
    idleSettings.ExcludeD3Cold = devCtx->ExcludeD3Cold;

    RETURN_NTSTATUS_IF_FAILED(WdfDeviceAssignS0IdleSettings(Device, &idleSettings));

    return status;
}

VOID
Codec_EvtDeviceContextCleanup(
    _In_ WDFOBJECT      WdfDevice
)
/*++

Routine Description:

    In this callback, it cleans up device context.

Arguments:

    WdfDevice - WDF device object

Return Value:

    nullptr

--*/
{
    WDFDEVICE               device;
    PCODEC_DEVICE_CONTEXT   devCtx;

    device = (WDFDEVICE)WdfDevice;
    devCtx = GetCodecDeviceContext(device);
    ASSERT(devCtx != nullptr);

    if (devCtx->Capture)
    {
        CodecC_CircuitCleanup(devCtx->Capture);
    }
}
