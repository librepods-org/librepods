/*++
    L2cap.c - the L2CAP channel operations, implemented with Bluetooth Request
    Blocks (BRBs) submitted to the stack via IOCTL_INTERNAL_BTH_SUBMIT_BRB.
--*/

#include "LibrePodsAAP.h"

//
// Submit a BRB synchronously to the Bluetooth stack (our parent I/O target).
//
NTSTATUS
LpSubmitBrbSync(
    _In_    PDEVICE_CONTEXT Ctx,
    _Inout_ PBRB            Brb
)
{
    NTSTATUS                 status;
    WDFREQUEST               request;
    PIRP                     irp;
    PIO_STACK_LOCATION       stack;
    WDF_REQUEST_SEND_OPTIONS options;

    if (Ctx->IoTarget == NULL) {
        return STATUS_DEVICE_NOT_READY;
    }

    status = WdfRequestCreate(WDF_NO_OBJECT_ATTRIBUTES, Ctx->IoTarget, &request);
    if (!NT_SUCCESS(status)) {
        return status;
    }

    // bthport reads the BRB pointer from Parameters.Others.Argument1 of the
    // IOCTL_INTERNAL_BTH_SUBMIT_BRB IRP. Set it explicitly on the next stack
    // location (the WDF memory-descriptor path left Argument1 NULL, so bthport
    // dereferenced a NULL BRB -> bugcheck 0x3B in BTHport!IOTracing::TraceStart).
    irp = WdfRequestWdmGetIrp(request);
    stack = IoGetNextIrpStackLocation(irp);
    stack->MajorFunction = IRP_MJ_INTERNAL_DEVICE_CONTROL;
    stack->MinorFunction = 0;
    stack->Parameters.DeviceIoControl.IoControlCode = IOCTL_INTERNAL_BTH_SUBMIT_BRB;
    stack->Parameters.Others.Argument1 = Brb;

    WDF_REQUEST_SEND_OPTIONS_INIT(
        &options, WDF_REQUEST_SEND_OPTION_SYNCHRONOUS | WDF_REQUEST_SEND_OPTION_TIMEOUT);
    WDF_REQUEST_SEND_OPTIONS_SET_TIMEOUT(&options, WDF_REL_TIMEOUT_IN_SEC(10));

    if (WdfRequestSend(request, Ctx->IoTarget, &options)) {
        status = WdfRequestGetStatus(request);
    } else {
        status = WdfRequestGetStatus(request);
    }

    WdfObjectDelete(request);
    return status;
}

//
// Open an outbound L2CAP channel to the remote device on the given PSM.
//
NTSTATUS
LpConnect(
    _In_ PDEVICE_CONTEXT Ctx,
    _In_ BTH_ADDR        Address,
    _In_ USHORT          Psm
)
{
    NTSTATUS                       status;
    struct _BRB_L2CA_OPEN_CHANNEL* brb;

    if (!Ctx->HasBthInterface) {
        return STATUS_DEVICE_NOT_READY;
    }

    if (Ctx->State == LpConnected) {
        if (Ctx->RemoteAddress == Address && Ctx->Psm == Psm) {
            return STATUS_SUCCESS;
        }
        LpDisconnect(Ctx);
    }

    // Basic-mode L2CAP open. ERTM was investigated exhaustively and ruled out: the
    // Windows bthport stack does NOT serialize a Retransmission-and-Flow option to
    // the wire when a profile driver requests ERTM via BRB_L2CA_OPEN_ENHANCED_CHANNEL
    // (CM_RETRANSMISSION_AND_FLOW). Two builds — timeouts 0, then 2000/12000 with
    // RtlZeroMemory + MPS>0 — both produced Configure Requests carrying only MTU +
    // FlushTO (btvs-confirmed); the ERTM option never left the host. The AirPods run
    // AAP over Basic regardless (they counter-propose Basic), as does Android (whose
    // ERTM request is likewise rejected and falls back). So ERTM is not the heart-
    // rate differentiator, and requesting it just breaks the connect on Windows.
    brb = (struct _BRB_L2CA_OPEN_CHANNEL*)
        Ctx->BthInterface.BthAllocateBrb(BRB_L2CA_OPEN_CHANNEL, LP_POOL_TAG);
    if (brb == NULL) {
        return STATUS_INSUFFICIENT_RESOURCES;
    }

    brb->BtAddress    = Address;
    brb->Psm          = Psm;
    // CF_ROLE_EITHER only. Tried adding CF_LINK_ENCRYPTED (the AACP socket is opened
    // auth/encrypt on Android) — it triggers a re-authentication at open time that
    // the controller rejects (HCI status 0x27), failing the connect, exactly the
    // race a LibrePods dev described. The paired link is already encrypted de-facto
    // (battery/ANC work), so requiring it explicitly only breaks the open; it is not
    // the heart-rate blocker.
    brb->ChannelFlags = CF_ROLE_EITHER;

    // Flags == 0 => let the stack negotiate default MTU/flush/QoS.
    brb->ConfigOut.Flags    = 0;
    brb->ConfigIn.Flags     = 0;
    brb->IncomingQueueDepth = 10; // MS-recommended default

    // Be notified when the remote tears the channel down.
    brb->CallbackFlags   = CALLBACK_DISCONNECT;
    brb->Callback        = LpIndicationCallback;
    brb->CallbackContext = Ctx;
    brb->ReferenceObject = Ctx->WdmDeviceObject;

    WdfSpinLockAcquire(Ctx->Lock);
    Ctx->State         = LpConnecting;
    Ctx->RemoteAddress = Address;
    Ctx->Psm           = Psm;
    WdfSpinLockRelease(Ctx->Lock);

    status = LpSubmitBrbSync(Ctx, (PBRB)brb);

    if (NT_SUCCESS(status)) {
        WdfSpinLockAcquire(Ctx->Lock);
        Ctx->ChannelHandle = brb->ChannelHandle;
        Ctx->State         = LpConnected;
        WdfSpinLockRelease(Ctx->Lock);
        KdPrint(("LibrePodsAAP: L2CAP connected (handle=%p)\n", brb->ChannelHandle));
        // NB: the ATT (PSM 0x001F) channel is opened LAZILY — on the first hearing-aid
        // ATT write (see LpAttSend), NOT here. The buds' ATT server is dormant until
        // hearing-assist is enabled, so opening a second L2CAP channel to it on every
        // connect only churns the shared ACL and destabilises the AAP channel / A2DP
        // (it opened, idled, and dropped ~30 s later on every session).
        //
        // NB: we deliberately do NOT register an ATT *server* here to accept the
        // buds' inbound PSM-0x001F connection. bthport rejects registering a server
        // on the reserved ATT PSM with STATUS_INVALID_PARAMETER (0xC000000D) — a
        // profile driver may be an ATT client but not a server (tested 2026-08-12).
        // So the AirPods' inbound GATT connection is unavoidably refused on Windows.
    } else {
        WdfSpinLockAcquire(Ctx->Lock);
        Ctx->State         = LpDisconnected;
        Ctx->ChannelHandle = NULL;
        WdfSpinLockRelease(Ctx->Lock);
        KdPrint(("LibrePodsAAP: L2CAP connect failed 0x%08X\n", status));
    }

    Ctx->BthInterface.BthFreeBrb((PBRB)brb);
    return status;
}

//
// Open the ATT (PSM 0x001F) channel to the AirPods as a CLIENT — the same
// outbound BRB_L2CA_OPEN_CHANNEL we use for the AAP channel (0x1001), just a second
// channel on the reserved ATT PSM. This is exactly what Android's ATTManager does
// (createL2capChannel(0x1F)); connecting as a client to a reserved PSM is allowed,
// unlike registering a SERVER on it. The buds accept it (their end is the ATT
// server) and we then read/write the hearing-aid audiogram over handle 0x2A.
//
NTSTATUS
LpConnectAtt(
    _In_ PDEVICE_CONTEXT Ctx
)
{
    NTSTATUS                       status;
    struct _BRB_L2CA_OPEN_CHANNEL* brb;

    if (!Ctx->HasBthInterface) {
        return STATUS_DEVICE_NOT_READY;
    }
    if (Ctx->AttConnected) {
        return STATUS_SUCCESS;
    }

    brb = (struct _BRB_L2CA_OPEN_CHANNEL*)
        Ctx->BthInterface.BthAllocateBrb(BRB_L2CA_OPEN_CHANNEL, LP_POOL_TAG);
    if (brb == NULL) {
        return STATUS_INSUFFICIENT_RESOURCES;
    }

    brb->BtAddress          = Ctx->RemoteAddress;
    brb->Psm                = PSM_ATT; // 0x001F, connect to the buds' ATT server
    brb->ChannelFlags       = CF_ROLE_EITHER;
    brb->ConfigOut.Flags    = 0;
    brb->ConfigIn.Flags     = 0;
    brb->IncomingQueueDepth = 10;
    brb->CallbackFlags      = CALLBACK_DISCONNECT;
    brb->Callback           = LpAttServerIndication; // reused for the disconnect event
    brb->CallbackContext    = Ctx;
    brb->ReferenceObject    = Ctx->WdmDeviceObject;

    status = LpSubmitBrbSync(Ctx, (PBRB)brb);
    WdfSpinLockAcquire(Ctx->Lock);
    Ctx->AttAcceptStatus = status; // reuse the accept-status field for the open result
    if (NT_SUCCESS(status)) {
        Ctx->AttChannelHandle = brb->ChannelHandle;
        Ctx->AttConnected     = TRUE;
    }
    WdfSpinLockRelease(Ctx->Lock);
    if (NT_SUCCESS(status)) {
        KdPrint(("LibrePodsAAP: *** ATT client channel OPEN (handle=%p) ***\n", brb->ChannelHandle));
    } else {
        KdPrint(("LibrePodsAAP: ATT client open FAILED 0x%08X\n", status));
    }

    Ctx->BthInterface.BthFreeBrb((PBRB)brb);
    return status;
}

//
// Write raw ATT PDU bytes to the ATT (PSM 0x001F) channel.
//
NTSTATUS
LpAttSend(
    _In_ PDEVICE_CONTEXT Ctx,
    _In_ PVOID           Buffer,
    _In_ ULONG           Length
)
{
    NTSTATUS                       status;
    struct _BRB_L2CA_ACL_TRANSFER* brb;

    // Lazily open the ATT (PSM 0x001F) client channel on first use, instead of on
    // every AAP connect: the buds' ATT server is dormant until hearing-assist is
    // enabled (the AAP 0x2C/0x33 enable wakes it just before the first write), and
    // opening it eagerly churns the shared ACL and destabilises the AAP channel /
    // A2DP. Reconnects transparently after an idle close — the remote-disconnect
    // indication clears AttConnected.
    if (!Ctx->AttConnected) {
        (VOID)LpConnectAtt(Ctx);
    }
    if (!Ctx->AttConnected || Ctx->AttChannelHandle == NULL) {
        return STATUS_DEVICE_NOT_CONNECTED;
    }
    if (Buffer == NULL || Length == 0) {
        return STATUS_INVALID_PARAMETER;
    }

    brb = (struct _BRB_L2CA_ACL_TRANSFER*)
        Ctx->BthInterface.BthAllocateBrb(BRB_L2CA_ACL_TRANSFER, LP_POOL_TAG);
    if (brb == NULL) {
        return STATUS_INSUFFICIENT_RESOURCES;
    }

    brb->BtAddress     = Ctx->RemoteAddress;
    brb->ChannelHandle = Ctx->AttChannelHandle;
    brb->TransferFlags = ACL_TRANSFER_DIRECTION_OUT;
    brb->Buffer        = Buffer;
    brb->BufferMDL     = NULL;
    brb->BufferSize    = Length;
    brb->Timeout       = 0;

    status = LpSubmitBrbSync(Ctx, (PBRB)brb);
    Ctx->BthInterface.BthFreeBrb((PBRB)brb);
    return status;
}

//
// Read raw ATT PDU bytes from the ATT channel (blocking up to TimeoutMs).
//
NTSTATUS
LpAttReceive(
    _In_  PDEVICE_CONTEXT Ctx,
    _Out_ PVOID           Buffer,
    _In_  ULONG           BufferLen,
    _Out_ PULONG          BytesRead,
    _In_  ULONG           TimeoutMs
)
{
    NTSTATUS                       status;
    struct _BRB_L2CA_ACL_TRANSFER* brb;

    *BytesRead = 0;

    if (!Ctx->AttConnected || Ctx->AttChannelHandle == NULL) {
        return STATUS_DEVICE_NOT_CONNECTED;
    }
    if (Buffer == NULL || BufferLen == 0) {
        return STATUS_INVALID_PARAMETER;
    }

    brb = (struct _BRB_L2CA_ACL_TRANSFER*)
        Ctx->BthInterface.BthAllocateBrb(BRB_L2CA_ACL_TRANSFER, LP_POOL_TAG);
    if (brb == NULL) {
        return STATUS_INSUFFICIENT_RESOURCES;
    }

    brb->BtAddress     = Ctx->RemoteAddress;
    brb->ChannelHandle = Ctx->AttChannelHandle;
    brb->TransferFlags = ACL_TRANSFER_DIRECTION_IN | ACL_SHORT_TRANSFER_OK | ACL_TRANSFER_TIMEOUT;
    brb->Buffer        = Buffer;
    brb->BufferMDL     = NULL;
    brb->BufferSize    = BufferLen;
    brb->Timeout       = (LONGLONG)(TimeoutMs ? TimeoutMs : LP_RECV_TIMEOUT_MS);

    status = LpSubmitBrbSync(Ctx, (PBRB)brb);
    if (NT_SUCCESS(status)) {
        *BytesRead = brb->BufferSize;
    }

    Ctx->BthInterface.BthFreeBrb((PBRB)brb);
    return status;
}

//
// Close the channel (best-effort).
//
NTSTATUS
LpDisconnect(
    _In_ PDEVICE_CONTEXT Ctx
)
{
    struct _BRB_L2CA_CLOSE_CHANNEL* brb;
    L2CAP_CHANNEL_HANDLE            handle;
    BTH_ADDR                        addr;

    WdfSpinLockAcquire(Ctx->Lock);
    handle             = Ctx->ChannelHandle;
    addr               = Ctx->RemoteAddress;
    Ctx->State         = LpDisconnected;
    Ctx->ChannelHandle = NULL;
    WdfSpinLockRelease(Ctx->Lock);

    if (handle == NULL || !Ctx->HasBthInterface) {
        return STATUS_SUCCESS;
    }

    brb = (struct _BRB_L2CA_CLOSE_CHANNEL*)
        Ctx->BthInterface.BthAllocateBrb(BRB_L2CA_CLOSE_CHANNEL, LP_POOL_TAG);
    if (brb == NULL) {
        return STATUS_INSUFFICIENT_RESOURCES;
    }

    brb->BtAddress     = addr;
    brb->ChannelHandle = handle;
    (VOID)LpSubmitBrbSync(Ctx, (PBRB)brb);
    Ctx->BthInterface.BthFreeBrb((PBRB)brb);

    KdPrint(("LibrePodsAAP: disconnected\n"));
    return STATUS_SUCCESS;
}

//
// Write bytes to the channel.
//
NTSTATUS
LpSend(
    _In_ PDEVICE_CONTEXT Ctx,
    _In_ PVOID           Buffer,
    _In_ ULONG           Length
)
{
    NTSTATUS                       status;
    struct _BRB_L2CA_ACL_TRANSFER* brb;

    if (Ctx->State != LpConnected || Ctx->ChannelHandle == NULL) {
        return STATUS_DEVICE_NOT_CONNECTED;
    }
    if (Buffer == NULL || Length == 0) {
        return STATUS_INVALID_PARAMETER;
    }

    brb = (struct _BRB_L2CA_ACL_TRANSFER*)
        Ctx->BthInterface.BthAllocateBrb(BRB_L2CA_ACL_TRANSFER, LP_POOL_TAG);
    if (brb == NULL) {
        return STATUS_INSUFFICIENT_RESOURCES;
    }

    brb->BtAddress     = Ctx->RemoteAddress;
    brb->ChannelHandle = Ctx->ChannelHandle;
    brb->TransferFlags = ACL_TRANSFER_DIRECTION_OUT;
    brb->Buffer        = Buffer;
    brb->BufferMDL     = NULL;
    brb->BufferSize    = Length;
    brb->Timeout       = 0;

    status = LpSubmitBrbSync(Ctx, (PBRB)brb);
    Ctx->BthInterface.BthFreeBrb((PBRB)brb);
    return status;
}

//
// Read bytes from the channel (blocking up to TimeoutMs).
//
NTSTATUS
LpReceive(
    _In_  PDEVICE_CONTEXT Ctx,
    _Out_ PVOID           Buffer,
    _In_  ULONG           BufferLen,
    _Out_ PULONG          BytesRead,
    _In_  ULONG           TimeoutMs
)
{
    NTSTATUS                       status;
    struct _BRB_L2CA_ACL_TRANSFER* brb;

    *BytesRead = 0;

    if (Ctx->State != LpConnected || Ctx->ChannelHandle == NULL) {
        return STATUS_DEVICE_NOT_CONNECTED;
    }
    if (Buffer == NULL || BufferLen == 0) {
        return STATUS_INVALID_PARAMETER;
    }

    brb = (struct _BRB_L2CA_ACL_TRANSFER*)
        Ctx->BthInterface.BthAllocateBrb(BRB_L2CA_ACL_TRANSFER, LP_POOL_TAG);
    if (brb == NULL) {
        return STATUS_INSUFFICIENT_RESOURCES;
    }

    brb->BtAddress     = Ctx->RemoteAddress;
    brb->ChannelHandle = Ctx->ChannelHandle;
    brb->TransferFlags = ACL_TRANSFER_DIRECTION_IN | ACL_SHORT_TRANSFER_OK | ACL_TRANSFER_TIMEOUT;
    brb->Buffer        = Buffer;
    brb->BufferMDL     = NULL;
    brb->BufferSize    = BufferLen;
    brb->Timeout       = (LONGLONG)(TimeoutMs ? TimeoutMs : LP_RECV_TIMEOUT_MS);

    status = LpSubmitBrbSync(Ctx, (PBRB)brb);
    if (NT_SUCCESS(status)) {
        *BytesRead = brb->BufferSize; // updated with bytes actually read
    }

    Ctx->BthInterface.BthFreeBrb((PBRB)brb);
    return status;
}

//
// Notifications from the stack (we only care about remote disconnect).
//
VOID
LpIndicationCallback(
    _In_opt_ PVOID                 Context,
    _In_     INDICATION_CODE       Indication,
    _In_     PINDICATION_PARAMETERS Parameters
)
{
    PDEVICE_CONTEXT ctx = (PDEVICE_CONTEXT)Context;

    UNREFERENCED_PARAMETER(Parameters);

    if (ctx == NULL) {
        return;
    }

    switch (Indication) {
    case IndicationRemoteDisconnect:
        WdfSpinLockAcquire(ctx->Lock);
        ctx->State         = LpDisconnected;
        ctx->ChannelHandle = NULL;
        WdfSpinLockRelease(ctx->Lock);
        KdPrint(("LibrePodsAAP: remote disconnected the channel\n"));
        break;
    default:
        break;
    }
}

//
// Register an L2CAP server on PSM 0x001F (ATT). The AirPods connect INBOUND here
// for hearing-aid configuration; without a registered server bthport answers their
// Connection Request with "PSM not supported" and the config channel never opens.
// STEP 1: register + log the connect indication (proves the path). Accepting the
// channel (BRB_L2CA_OPEN_CHANNEL_RESPONSE) is Step 2. Non-fatal to the AAP channel.
//
NTSTATUS
LpRegisterAttServer(
    _In_ PDEVICE_CONTEXT Ctx
)
{
    NTSTATUS                          status;
    struct _BRB_L2CA_REGISTER_SERVER* brb;

    if (!Ctx->HasBthInterface) {
        return STATUS_DEVICE_NOT_READY;
    }
    if (Ctx->AttServerRegistered) {
        return STATUS_SUCCESS;
    }

    brb = (struct _BRB_L2CA_REGISTER_SERVER*)
        Ctx->BthInterface.BthAllocateBrb(BRB_L2CA_REGISTER_SERVER, LP_POOL_TAG);
    if (brb == NULL) {
        return STATUS_INSUFFICIENT_RESOURCES;
    }

    brb->BtAddress                 = Ctx->RemoteAddress; // the connected AirPods
    brb->PSM                       = PSM_ATT;
    brb->IndicationFlags           = 0;
    brb->IndicationCallback        = LpAttServerIndication;
    brb->IndicationCallbackContext = Ctx;
    brb->ReferenceObject           = Ctx->WdmDeviceObject;

    status = LpSubmitBrbSync(Ctx, (PBRB)brb);
    Ctx->AttRegisterStatus = status; // surfaced via IOCTL_LP_GET_STATUS
    if (NT_SUCCESS(status)) {
        Ctx->AttServerHandle     = brb->ServerHandle;
        Ctx->AttServerRegistered = TRUE;
        KdPrint(("LibrePodsAAP: ATT server registered on PSM 0x%04X\n", PSM_ATT));
    } else {
        KdPrint(("LibrePodsAAP: ATT server register FAILED 0x%08X\n", status));
    }

    Ctx->BthInterface.BthFreeBrb((PBRB)brb);
    return status;
}

//
// Unregister the ATT server (on device removal). Best-effort.
//
VOID
LpUnregisterAttServer(
    _In_ PDEVICE_CONTEXT Ctx
)
{
    struct _BRB_L2CA_UNREGISTER_SERVER* brb;

    if (!Ctx->AttServerRegistered || !Ctx->HasBthInterface) {
        return;
    }

    brb = (struct _BRB_L2CA_UNREGISTER_SERVER*)
        Ctx->BthInterface.BthAllocateBrb(BRB_L2CA_UNREGISTER_SERVER, LP_POOL_TAG);
    if (brb != NULL) {
        brb->BtAddress    = 0;
        brb->ServerHandle = Ctx->AttServerHandle;
        brb->Psm          = PSM_ATT;
        (VOID)LpSubmitBrbSync(Ctx, (PBRB)brb);
        Ctx->BthInterface.BthFreeBrb((PBRB)brb);
    }
    Ctx->AttServerRegistered = FALSE;
    KdPrint(("LibrePodsAAP: ATT server unregistered\n"));
}

//
// Server indication: bthport calls this when the AirPods connect to our PSM 0x001F
// server (and, once accepted, on the channel's disconnect). The connect can arrive
// at DISPATCH_LEVEL, so we stash the params and defer the accept to a work item.
//
VOID
LpAttServerIndication(
    _In_opt_ PVOID                 Context,
    _In_     INDICATION_CODE       Indication,
    _In_     PINDICATION_PARAMETERS Parameters
)
{
    PDEVICE_CONTEXT ctx = (PDEVICE_CONTEXT)Context;

    if (ctx == NULL) {
        return;
    }

    switch (Indication) {
    case IndicationRemoteConnect:
        KdPrint(("LibrePodsAAP: *** ATT connect indication from 0x%012I64X on PSM 0x001F "
                 "-- accepting ***\n", Parameters->BtAddress));
        WdfSpinLockAcquire(ctx->Lock);
        ctx->PendingAttConn = Parameters->ConnectionHandle;
        ctx->PendingAttAddr = Parameters->BtAddress;
        ctx->AttIndicationCount++;
        WdfSpinLockRelease(ctx->Lock);
        WdfWorkItemEnqueue(ctx->AttAcceptWorkItem);
        break;
    case IndicationRemoteDisconnect:
        WdfSpinLockAcquire(ctx->Lock);
        ctx->AttConnected     = FALSE;
        ctx->AttChannelHandle = NULL;
        WdfSpinLockRelease(ctx->Lock);
        KdPrint(("LibrePodsAAP: ATT channel disconnected by remote\n"));
        break;
    default:
        break;
    }
}

//
// Deferred accept (PASSIVE_LEVEL): respond SUCCESS to the AirPods' inbound ATT
// connect, opening the channel we bridge the hearing-aid config over.
//
VOID
LpAttAcceptWorkItem(
    _In_ WDFWORKITEM WorkItem
)
{
    PDEVICE_CONTEXT                ctx;
    NTSTATUS                       status;
    struct _BRB_L2CA_OPEN_CHANNEL* brb;
    L2CAP_CHANNEL_HANDLE           conn;
    BTH_ADDR                       addr;

    ctx = DeviceGetContext((WDFDEVICE)WdfWorkItemGetParentObject(WorkItem));

    WdfSpinLockAcquire(ctx->Lock);
    conn = ctx->PendingAttConn;
    addr = ctx->PendingAttAddr;
    WdfSpinLockRelease(ctx->Lock);

    if (!ctx->HasBthInterface || conn == NULL) {
        return;
    }

    brb = (struct _BRB_L2CA_OPEN_CHANNEL*)
        ctx->BthInterface.BthAllocateBrb(BRB_L2CA_OPEN_CHANNEL_RESPONSE, LP_POOL_TAG);
    if (brb == NULL) {
        return;
    }

    brb->ChannelHandle      = conn;                     // from the connect indication
    brb->Response           = CONNECT_RSP_RESULT_SUCCESS; // accept
    brb->ChannelFlags       = CF_ROLE_EITHER;
    brb->BtAddress          = addr;
    brb->ConfigOut.Flags    = 0;
    brb->ConfigIn.Flags     = 0;
    brb->IncomingQueueDepth = 10;
    brb->CallbackFlags      = CALLBACK_DISCONNECT;
    brb->Callback           = LpAttServerIndication; // reused for the channel's disconnect
    brb->CallbackContext    = ctx;
    brb->ReferenceObject    = ctx->WdmDeviceObject;

    status = LpSubmitBrbSync(ctx, (PBRB)brb);
    WdfSpinLockAcquire(ctx->Lock);
    ctx->AttAcceptStatus = status; // surfaced via IOCTL_LP_GET_STATUS
    if (NT_SUCCESS(status)) {
        ctx->AttChannelHandle = brb->ChannelHandle;
        ctx->AttConnected     = TRUE;
    }
    WdfSpinLockRelease(ctx->Lock);
    if (NT_SUCCESS(status)) {
        KdPrint(("LibrePodsAAP: *** ATT channel ACCEPTED (handle=%p) ***\n", brb->ChannelHandle));
    } else {
        KdPrint(("LibrePodsAAP: ATT accept FAILED 0x%08X\n", status));
    }

    ctx->BthInterface.BthFreeBrb((PBRB)brb);
}

//
// Close the accepted ATT channel (best-effort, on device removal).
//
VOID
LpCloseAttChannel(
    _In_ PDEVICE_CONTEXT Ctx
)
{
    struct _BRB_L2CA_CLOSE_CHANNEL* brb;
    L2CAP_CHANNEL_HANDLE            handle;
    BTH_ADDR                        addr;

    WdfSpinLockAcquire(Ctx->Lock);
    handle                = Ctx->AttChannelHandle;
    addr                  = Ctx->PendingAttAddr;
    Ctx->AttConnected     = FALSE;
    Ctx->AttChannelHandle = NULL;
    WdfSpinLockRelease(Ctx->Lock);

    if (handle == NULL || !Ctx->HasBthInterface) {
        return;
    }

    brb = (struct _BRB_L2CA_CLOSE_CHANNEL*)
        Ctx->BthInterface.BthAllocateBrb(BRB_L2CA_CLOSE_CHANNEL, LP_POOL_TAG);
    if (brb != NULL) {
        brb->BtAddress     = addr;
        brb->ChannelHandle = handle;
        (VOID)LpSubmitBrbSync(Ctx, (PBRB)brb);
        Ctx->BthInterface.BthFreeBrb((PBRB)brb);
    }
    KdPrint(("LibrePodsAAP: ATT channel closed\n"));
}
