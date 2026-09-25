/*++
    Device.c - PnP: obtain the Bluetooth profile driver interface + I/O target
    from our BTHENUM parent, and tear down the connection on removal.
--*/

#include "LibrePodsAAP.h"

NTSTATUS
LpEvtDevicePrepareHardware(
    _In_ WDFDEVICE    Device,
    _In_ WDFCMRESLIST ResourcesRaw,
    _In_ WDFCMRESLIST ResourcesTranslated
)
{
    NTSTATUS        status;
    PDEVICE_CONTEXT ctx;

    UNREFERENCED_PARAMETER(ResourcesRaw);
    UNREFERENCED_PARAMETER(ResourcesTranslated);

    ctx = DeviceGetContext(Device);

    // Default I/O target = our parent = the Bluetooth stack. BRBs submitted here
    // reach bthport (this is exactly what the Root\ install of other drivers
    // gets wrong).
    ctx->IoTarget = WdfDeviceGetIoTarget(Device);

    // Ask the BTHENUM parent for BthAllocateBrb/BthFreeBrb/... Because we are a
    // real profile driver bound to the AAP service PDO, this succeeds.
    ctx->BthInterface.Interface.Size    = sizeof(BTH_PROFILE_DRIVER_INTERFACE);
    ctx->BthInterface.Interface.Version = BTHDDI_PROFILE_DRIVER_INTERFACE_VERSION_FOR_QI;

    status = WdfFdoQueryForInterface(
        Device,
        &GUID_BTHDDI_PROFILE_DRIVER_INTERFACE,
        (PINTERFACE)&ctx->BthInterface.Interface,
        sizeof(BTH_PROFILE_DRIVER_INTERFACE),
        BTHDDI_PROFILE_DRIVER_INTERFACE_VERSION_FOR_QI,
        NULL);

    if (!NT_SUCCESS(status)) {
        KdPrint(("LibrePodsAAP: QueryInterface(PROFILE_DRIVER_INTERFACE) failed 0x%08X\n", status));
        ctx->HasBthInterface = FALSE;
        return status; // fatal: without it we cannot allocate/submit BRBs
    }

    ctx->HasBthInterface = TRUE;
    KdPrint(("LibrePodsAAP: acquired BTH profile interface\n"));

    // NB: the ATT (PSM 0x001F) server is registered later, from LpConnect, once we
    // know the AirPods' address (registering with BtAddress=0 here returned
    // STATUS_INVALID_PARAMETER 0xC000000D).

    return STATUS_SUCCESS;
}

//
// Remove path, step 1 of 2. Runs while the default I/O target is still STARTED.
//
// This is where the L2CAP/ATT teardown has to happen. It used to live in
// LpEvtDeviceReleaseHardware, but WDF stops the default I/O target BEFORE calling
// ReleaseHardware, so every BRB submitted from there fails at WdfRequestSend and
// never reaches bthport - silently, because all three call sites discarded the
// result with (VOID). bthport then kept our PSM 0x001F server registration and
// the open channels, referencing a driver that was going away, and that stale
// reference is what pins the whole Bluetooth branch: measured 2026-08-31,
// `pnputil /restart-device` answered "System reboot is needed to complete
// configuration operations!" for our devnode AND for the Intel radio above it,
// with the LibrePodsAAP service already STOPPED. Nothing short of a reboot
// cleared it.
//
// Statuses are logged rather than discarded, so the debug build shows whether
// each BRB actually landed.
//
NTSTATUS
LpEvtDeviceSelfManagedIoSuspend(
    _In_ WDFDEVICE Device
)
{
    PDEVICE_CONTEXT ctx = DeviceGetContext(Device);
    NTSTATUS        status;

    if (ctx->State != LpDisconnected) {
        status = LpDisconnect(ctx);
        KdPrint(("LibrePodsAAP: suspend: LpDisconnect = 0x%08X\n", status));
    }

    // Close the accepted ATT channel, then unregister the server (both use the
    // interface, so both must precede the dereference in ReleaseHardware).
    LpCloseAttChannel(ctx);
    LpUnregisterAttServer(ctx);

    return STATUS_SUCCESS;
}

//
// Remove path, step 2 of 2. The I/O target is stopped by now, so BRBs submitted
// here go nowhere - this is only about dropping the interface reference.
//
NTSTATUS
LpEvtDeviceReleaseHardware(
    _In_ WDFDEVICE    Device,
    _In_ WDFCMRESLIST ResourcesTranslated
)
{
    PDEVICE_CONTEXT ctx = DeviceGetContext(Device);
    UNREFERENCED_PARAMETER(ResourcesTranslated);

    // Belt and braces: SelfManagedIoSuspend normally did this already, but it is
    // not reached on every failure path (e.g. PrepareHardware failed), and a
    // channel left open here would leak. Both calls are idempotent and cheap.
    if (ctx->State != LpDisconnected) {
        LpDisconnect(ctx);
    }
    LpCloseAttChannel(ctx);
    LpUnregisterAttServer(ctx);

    // Release the Bluetooth profile driver interface we took in PrepareHardware.
    // WdfFdoQueryForInterface increments the interface's reference count; not
    // dereferencing it leaks a reference to our BTHENUM parent, so the device
    // never tears down cleanly - the old driver instance stays resident and the
    // next load fails with Code 38 ("a previous instance is still in memory"),
    // which the AirPods reconnect can't recover without a reboot. Dereferencing
    // here lets it unload and rebind on every reconnect.
    if (ctx->HasBthInterface) {
        ctx->BthInterface.Interface.InterfaceDereference(
            ctx->BthInterface.Interface.Context);
        ctx->HasBthInterface = FALSE;
        KdPrint(("LibrePodsAAP: BTH interface dereferenced\n"));
    }
    return STATUS_SUCCESS;
}

//
// Last line of defence. WDF calls this when the device object itself is being
// destroyed, on EVERY path - including ones where ReleaseHardware was skipped or
// bailed out early. An interface reference still held at this point would pin the
// BTHENUM parent for good (and with it the radio), so drop it here too.
//
VOID
LpEvtDeviceContextCleanup(
    _In_ WDFOBJECT Object
)
{
    PDEVICE_CONTEXT ctx = DeviceGetContext((WDFDEVICE)Object);

    if (ctx->State != LpDisconnected) {
        LpDisconnect(ctx);
    }

    if (ctx->HasBthInterface) {
        KdPrint(("LibrePodsAAP: cleanup: interface still held - dereferencing\n"));
        ctx->BthInterface.Interface.InterfaceDereference(
            ctx->BthInterface.Interface.Context);
        ctx->HasBthInterface = FALSE;
    }
}
