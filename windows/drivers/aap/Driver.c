/*++
    Driver.c - driver entry and device creation.
--*/

// INITGUID (via initguid.h) must precede the headers so DEFINE_GUID emits the
// GUID *data* here (exactly one TU). Other .c files get extern declarations.
#include <initguid.h>
#include "LibrePodsAAP.h"

NTSTATUS
DriverEntry(
    _In_ PDRIVER_OBJECT  DriverObject,
    _In_ PUNICODE_STRING RegistryPath
)
{
    WDF_DRIVER_CONFIG config;
    NTSTATUS          status;

    KdPrint(("LibrePodsAAP: DriverEntry\n"));

    WDF_DRIVER_CONFIG_INIT(&config, LpEvtDeviceAdd);

    status = WdfDriverCreate(
        DriverObject,
        RegistryPath,
        WDF_NO_OBJECT_ATTRIBUTES,
        &config,
        WDF_NO_HANDLE
    );

    if (!NT_SUCCESS(status)) {
        KdPrint(("LibrePodsAAP: WdfDriverCreate failed 0x%08X\n", status));
    }
    return status;
}

NTSTATUS
LpEvtDeviceAdd(
    _In_    WDFDRIVER       Driver,
    _Inout_ PWDFDEVICE_INIT DeviceInit
)
{
    NTSTATUS                    status;
    WDF_PNPPOWER_EVENT_CALLBACKS pnpPower;
    WDF_OBJECT_ATTRIBUTES       deviceAttrs;
    WDF_FILEOBJECT_CONFIG       fileConfig;
    WDFDEVICE                   device;
    PDEVICE_CONTEXT             ctx;
    WDF_IO_QUEUE_CONFIG         queueConfig;

    UNREFERENCED_PARAMETER(Driver);

    KdPrint(("LibrePodsAAP: EvtDeviceAdd\n"));

    // We are the function driver for the AAP service PDO exposed by BTHENUM.
    WDF_PNPPOWER_EVENT_CALLBACKS_INIT(&pnpPower);
    pnpPower.EvtDevicePrepareHardware = LpEvtDevicePrepareHardware;
    pnpPower.EvtDeviceReleaseHardware = LpEvtDeviceReleaseHardware;
    // The L2CAP/ATT teardown must run HERE, not in ReleaseHardware: WDF stops the
    // default I/O target before ReleaseHardware, so BRBs submitted there never
    // reach bthport. SelfManagedIoSuspend runs while the target is still started.
    pnpPower.EvtDeviceSelfManagedIoSuspend = LpEvtDeviceSelfManagedIoSuspend;
    WdfDeviceInitSetPnpPowerEventCallbacks(DeviceInit, &pnpPower);

    // Track the user-mode handle lifetime so we can release the L2CAP channel
    // when the app exits. Only EvtFileClose is needed (create/cleanup default).
    // AutoForwardCleanupClose = WdfFalse: these CREATE/CLOSE IRPs belong to our
    // user-mode device interface, not the Bluetooth stack below us, so WDF must
    // complete them here rather than forwarding them down to bthport.
    WDF_FILEOBJECT_CONFIG_INIT(
        &fileConfig, WDF_NO_EVENT_CALLBACK, LpEvtFileClose, WDF_NO_EVENT_CALLBACK);
    fileConfig.AutoForwardCleanupClose = WdfFalse;
    WdfDeviceInitSetFileObjectConfig(DeviceInit, &fileConfig, WDF_NO_OBJECT_ATTRIBUTES);

    WdfDeviceInitSetDeviceType(DeviceInit, FILE_DEVICE_BLUETOOTH);
    WdfDeviceInitSetExclusive(DeviceInit, TRUE);

    WDF_OBJECT_ATTRIBUTES_INIT_CONTEXT_TYPE(&deviceAttrs, DEVICE_CONTEXT);
    deviceAttrs.EvtCleanupCallback = LpEvtDeviceContextCleanup;

    status = WdfDeviceCreate(&DeviceInit, &deviceAttrs, &device);
    if (!NT_SUCCESS(status)) {
        KdPrint(("LibrePodsAAP: WdfDeviceCreate failed 0x%08X\n", status));
        return status;
    }

    ctx = DeviceGetContext(device);
    RtlZeroMemory(ctx, sizeof(*ctx));
    ctx->State           = LpDisconnected;
    ctx->AttAcceptStatus   = STATUS_PENDING; // 0x00000103 = accept not yet attempted
    ctx->AttRegisterStatus = STATUS_PENDING; // 0x00000103 = register not yet attempted
    ctx->WdmDeviceObject = WdfDeviceWdmGetDeviceObject(device);

    status = WdfSpinLockCreate(WDF_NO_OBJECT_ATTRIBUTES, &ctx->Lock);
    if (!NT_SUCCESS(status)) {
        KdPrint(("LibrePodsAAP: WdfSpinLockCreate failed 0x%08X\n", status));
        return status;
    }

    // Work item that accepts the AirPods' inbound ATT (PSM 0x001F) connection at
    // PASSIVE_LEVEL (the connect indication may run at DISPATCH_LEVEL).
    {
        WDF_WORKITEM_CONFIG   wiConfig;
        WDF_OBJECT_ATTRIBUTES wiAttrs;
        WDF_WORKITEM_CONFIG_INIT(&wiConfig, LpAttAcceptWorkItem);
        WDF_OBJECT_ATTRIBUTES_INIT(&wiAttrs);
        wiAttrs.ParentObject = device;
        status = WdfWorkItemCreate(&wiConfig, &wiAttrs, &ctx->AttAcceptWorkItem);
        if (!NT_SUCCESS(status)) {
            KdPrint(("LibrePodsAAP: WdfWorkItemCreate failed 0x%08X\n", status));
            return status;
        }
    }

    // Single sequential IOCTL queue (connect/send/receive are serialized).
    WDF_IO_QUEUE_CONFIG_INIT_DEFAULT_QUEUE(&queueConfig, WdfIoQueueDispatchSequential);
    queueConfig.EvtIoDeviceControl = LpEvtIoDeviceControl;

    status = WdfIoQueueCreate(device, &queueConfig, WDF_NO_OBJECT_ATTRIBUTES, WDF_NO_HANDLE);
    if (!NT_SUCCESS(status)) {
        KdPrint(("LibrePodsAAP: WdfIoQueueCreate failed 0x%08X\n", status));
        return status;
    }

    status = WdfDeviceCreateDeviceInterface(device, &GUID_DEVINTERFACE_LIBREPODSAAP, NULL);
    if (!NT_SUCCESS(status)) {
        KdPrint(("LibrePodsAAP: CreateDeviceInterface failed 0x%08X\n", status));
        return status;
    }

    KdPrint(("LibrePodsAAP: device created\n"));
    return STATUS_SUCCESS;
}
