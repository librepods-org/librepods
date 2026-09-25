/*++
    Ioctl.c - user-mode bridge. Translates DeviceIoControl calls from the
    LibrePods app into L2CAP operations.
--*/

#include "LibrePodsAAP.h"

//
// The app closed its handle (clean exit OR crash -> the OS closes it for us).
// Release the L2CAP channel so it doesn't leak. LpDisconnect is idempotent and
// runs at PASSIVE_LEVEL, which is where EvtFileClose is called.
//
VOID
LpEvtFileClose(
    _In_ WDFFILEOBJECT FileObject
)
{
    PDEVICE_CONTEXT ctx = DeviceGetContext(WdfFileObjectGetDevice(FileObject));

    if (ctx->State != LpDisconnected) {
        KdPrint(("LibrePodsAAP: app handle closed -> releasing L2CAP channel\n"));
        LpDisconnect(ctx);
    }
}

VOID
LpEvtIoDeviceControl(
    _In_ WDFQUEUE   Queue,
    _In_ WDFREQUEST Request,
    _In_ size_t     OutputBufferLength,
    _In_ size_t     InputBufferLength,
    _In_ ULONG      IoControlCode
)
{
    NTSTATUS        status      = STATUS_INVALID_DEVICE_REQUEST;
    ULONG_PTR       information = 0;
    WDFDEVICE       device      = WdfIoQueueGetDevice(Queue);
    PDEVICE_CONTEXT ctx         = DeviceGetContext(device);
    PVOID           inBuf, outBuf;
    size_t          sz;

    switch (IoControlCode) {

    case IOCTL_LP_CONNECT: {
        PLP_CONNECT_INPUT  in;
        PLP_CONNECT_OUTPUT out;

        if (InputBufferLength < sizeof(LP_CONNECT_INPUT) ||
            OutputBufferLength < sizeof(LP_CONNECT_OUTPUT)) {
            status = STATUS_BUFFER_TOO_SMALL;
            break;
        }
        status = WdfRequestRetrieveInputBuffer(Request, sizeof(LP_CONNECT_INPUT), &inBuf, &sz);
        if (!NT_SUCCESS(status)) break;
        status = WdfRequestRetrieveOutputBuffer(Request, sizeof(LP_CONNECT_OUTPUT), &outBuf, &sz);
        if (!NT_SUCCESS(status)) break;

        in  = (PLP_CONNECT_INPUT)inBuf;
        out = (PLP_CONNECT_OUTPUT)outBuf;

        status       = LpConnect(ctx, in->BluetoothAddress, in->Psm);
        out->Status  = (LONG)status;
        out->Success = NT_SUCCESS(status) ? 1u : 0u;
        information  = sizeof(LP_CONNECT_OUTPUT);
        status       = STATUS_SUCCESS; // the connect result is inside the struct
        break;
    }

    case IOCTL_LP_DISCONNECT:
        status = LpDisconnect(ctx);
        break;

    case IOCTL_LP_SEND: {
        if (InputBufferLength == 0) {
            status = STATUS_INVALID_PARAMETER;
            break;
        }
        status = WdfRequestRetrieveInputBuffer(Request, 1, &inBuf, &sz);
        if (!NT_SUCCESS(status)) break;
        status = LpSend(ctx, inBuf, (ULONG)sz);
        break;
    }

    case IOCTL_LP_RECEIVE: {
        ULONG timeoutMs = 0;
        ULONG bytesRead = 0;

        if (InputBufferLength >= sizeof(LP_RECEIVE_INPUT)) {
            status = WdfRequestRetrieveInputBuffer(Request, sizeof(LP_RECEIVE_INPUT), &inBuf, &sz);
            if (NT_SUCCESS(status)) {
                timeoutMs = ((PLP_RECEIVE_INPUT)inBuf)->TimeoutMs;
            }
        }
        if (OutputBufferLength == 0) {
            status = STATUS_BUFFER_TOO_SMALL;
            break;
        }
        status = WdfRequestRetrieveOutputBuffer(Request, 1, &outBuf, &sz);
        if (!NT_SUCCESS(status)) break;

        status = LpReceive(ctx, outBuf, (ULONG)sz, &bytesRead, timeoutMs);
        if (NT_SUCCESS(status)) {
            information = bytesRead;
        }
        break;
    }

    case IOCTL_LP_GET_STATUS: {
        PLP_STATUS_OUTPUT out;
        if (OutputBufferLength < sizeof(LP_STATUS_OUTPUT)) {
            status = STATUS_BUFFER_TOO_SMALL;
            break;
        }
        status = WdfRequestRetrieveOutputBuffer(Request, sizeof(LP_STATUS_OUTPUT), &outBuf, &sz);
        if (!NT_SUCCESS(status)) break;

        out                      = (PLP_STATUS_OUTPUT)outBuf;
        out->State               = (ULONG)ctx->State;
        out->ConnectedAddress    = ctx->RemoteAddress;
        out->AttServerRegistered = ctx->AttServerRegistered ? 1u : 0u;
        out->AttIndicationCount  = ctx->AttIndicationCount;
        out->AttAcceptStatus     = ctx->AttAcceptStatus;
        out->AttChannelOpen      = ctx->AttConnected ? 1u : 0u;
        out->AttRegisterStatus   = ctx->AttRegisterStatus;
        information              = sizeof(LP_STATUS_OUTPUT);
        break;
    }

    case IOCTL_LP_ATT_SEND: {
        if (InputBufferLength == 0) {
            status = STATUS_INVALID_PARAMETER;
            break;
        }
        status = WdfRequestRetrieveInputBuffer(Request, 1, &inBuf, &sz);
        if (!NT_SUCCESS(status)) break;
        status = LpAttSend(ctx, inBuf, (ULONG)sz);
        break;
    }

    case IOCTL_LP_ATT_RECEIVE: {
        ULONG timeoutMs = 0;
        ULONG bytesRead = 0;

        if (InputBufferLength >= sizeof(LP_RECEIVE_INPUT)) {
            status = WdfRequestRetrieveInputBuffer(Request, sizeof(LP_RECEIVE_INPUT), &inBuf, &sz);
            if (NT_SUCCESS(status)) {
                timeoutMs = ((PLP_RECEIVE_INPUT)inBuf)->TimeoutMs;
            }
        }
        if (OutputBufferLength == 0) {
            status = STATUS_BUFFER_TOO_SMALL;
            break;
        }
        status = WdfRequestRetrieveOutputBuffer(Request, 1, &outBuf, &sz);
        if (!NT_SUCCESS(status)) break;

        status = LpAttReceive(ctx, outBuf, (ULONG)sz, &bytesRead, timeoutMs);
        if (NT_SUCCESS(status)) {
            information = bytesRead;
        }
        break;
    }

    default:
        break;
    }

    WdfRequestCompleteWithInformation(Request, status, information);
}
