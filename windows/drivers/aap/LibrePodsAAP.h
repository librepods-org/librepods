/*++
    LibrePodsAAP - open-source KMDF Bluetooth L2CAP profile driver for the
    Apple Accessory Protocol (AAP) on Windows.

    Binds to the AAP SDP service the AirPods advertise
    (BTHENUM\{74ec2172-0bad-4d01-8f77-997b2be0722a}), opens an L2CAP channel to
    PSM 0x1001 in kernel mode (which user-mode Winsock cannot), and bridges it
    to user space via DeviceIoControl. The LibrePods app talks to this driver
    through the IOCTL contract below.

    Architecture reference: Microsoft bthecho sample + nefarius/BthPS3.
    License: same as LibrePods.
--*/

#ifndef _LIBREPODSAAP_H_
#define _LIBREPODSAAP_H_

#include <ntddk.h>
#include <wdf.h>
// Order matters: bthdef/bthguid define the types bthddi.h consumes.
#include <bthdef.h>
#include <bthguid.h>
#include <bthddi.h>
#include <bthioctl.h> // IOCTL_INTERNAL_BTH_SUBMIT_BRB

#define LP_POOL_TAG          'PbiL' // "LibP"
#define LP_RECV_TIMEOUT_MS   5000
// PSM_ATT (0x001F) — the AirPods open THIS to us (inbound) for hearing-aid config.
// Already defined by the WDK's bthdef.h, so we just use that.

//
// User-mode device interface: the LibrePods app enumerates this GUID to find
// the driver, then opens it and issues the IOCTLs below.
// {C0FFEE00-1337-4A5B-9E6F-A1B2C3D4E5F6}
//
DEFINE_GUID(GUID_DEVINTERFACE_LIBREPODSAAP,
    0xc0ffee00, 0x1337, 0x4a5b, 0x9e, 0x6f, 0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf6);

#define FILE_DEVICE_LIBREPODS   0x8000
#define IOCTL_LP_CONNECT     CTL_CODE(FILE_DEVICE_LIBREPODS, 0x800, METHOD_BUFFERED, FILE_ANY_ACCESS)
#define IOCTL_LP_DISCONNECT  CTL_CODE(FILE_DEVICE_LIBREPODS, 0x801, METHOD_BUFFERED, FILE_ANY_ACCESS)
#define IOCTL_LP_SEND        CTL_CODE(FILE_DEVICE_LIBREPODS, 0x802, METHOD_BUFFERED, FILE_ANY_ACCESS)
#define IOCTL_LP_RECEIVE     CTL_CODE(FILE_DEVICE_LIBREPODS, 0x803, METHOD_BUFFERED, FILE_ANY_ACCESS)
#define IOCTL_LP_GET_STATUS  CTL_CODE(FILE_DEVICE_LIBREPODS, 0x804, METHOD_BUFFERED, FILE_ANY_ACCESS)
// ATT (PSM 0x001F) channel I/O — raw ATT PDUs to/from the AirPods' hearing-aid GATT.
#define IOCTL_LP_ATT_SEND    CTL_CODE(FILE_DEVICE_LIBREPODS, 0x805, METHOD_BUFFERED, FILE_ANY_ACCESS)
#define IOCTL_LP_ATT_RECEIVE CTL_CODE(FILE_DEVICE_LIBREPODS, 0x806, METHOD_BUFFERED, FILE_ANY_ACCESS)

typedef enum _LP_STATE {
    LpDisconnected = 0,
    LpConnecting   = 1,
    LpConnected    = 2
} LP_STATE;

//
// IOCTL payload layout (packed so it matches the Rust transport byte-for-byte).
//
#include <pshpack1.h>

typedef struct _LP_CONNECT_INPUT {
    ULONGLONG BluetoothAddress; // 48-bit BTH_ADDR of the AirPods
    USHORT    Psm;              // 0x1001 for AAP
} LP_CONNECT_INPUT, *PLP_CONNECT_INPUT;

typedef struct _LP_CONNECT_OUTPUT {
    ULONG    Success;  // non-zero on success
    LONG     Status;   // NTSTATUS from the open-channel BRB
} LP_CONNECT_OUTPUT, *PLP_CONNECT_OUTPUT;

typedef struct _LP_RECEIVE_INPUT {
    ULONG TimeoutMs;   // 0 => LP_RECV_TIMEOUT_MS
} LP_RECEIVE_INPUT, *PLP_RECEIVE_INPUT;

typedef struct _LP_STATUS_OUTPUT {
    ULONG     State;            // LP_STATE
    ULONGLONG ConnectedAddress;
    // ATT (PSM 0x001F) server diagnostics — so user mode can see the hearing-aid
    // channel progress without a kernel debugger (DebugView is unreliable here).
    ULONG     AttServerRegistered; // 0/1
    ULONG     AttIndicationCount;  // # of inbound ATT connect indications seen
    LONG      AttAcceptStatus;     // NTSTATUS of the last accept (0x00000103 = not tried)
    ULONG     AttChannelOpen;      // 0/1 (the accepted ATT channel is up)
    LONG      AttRegisterStatus;   // NTSTATUS of the server register (0x00000103 = not tried)
} LP_STATUS_OUTPUT, *PLP_STATUS_OUTPUT;

#include <poppack.h>

// IOCTL_LP_SEND:    input buffer  = raw AAP bytes to write to the L2CAP channel.
// IOCTL_LP_RECEIVE: output buffer = raw AAP bytes read; Information = byte count.

//
// Per-device state. One device = one AAP service PDO = one AirPods.
//
typedef struct _DEVICE_CONTEXT {
    // Bluetooth profile driver interface (BthAllocateBrb/BthFreeBrb/...),
    // obtained from the BTHENUM parent via QueryInterface.
    BTH_PROFILE_DRIVER_INTERFACE BthInterface;
    BOOLEAN                      HasBthInterface;

    // I/O target used to submit BRBs to the Bluetooth stack (our parent).
    WDFIOTARGET IoTarget;

    // WDM device object, passed as BRB ReferenceObject when a callback is set.
    PDEVICE_OBJECT WdmDeviceObject;

    // L2CAP connection state.
    WDFSPINLOCK          Lock;
    LP_STATE             State;
    BTH_ADDR             RemoteAddress;
    USHORT               Psm;
    L2CAP_CHANNEL_HANDLE ChannelHandle;

    // ATT (PSM 0x001F) server. The AirPods don't wait for us to connect — right
    // after a hearing-aid enable they open an L2CAP channel INBOUND to this PSM,
    // which bthport refuses ("PSM not supported") unless we register a server.
    L2CAP_SERVER_HANDLE  AttServerHandle;
    BOOLEAN              AttServerRegistered;

    // The connect indication can fire at DISPATCH_LEVEL, where we cannot make the
    // blocking BRB submit that accepts the channel. So we stash the connect params
    // and defer the accept (BRB_L2CA_OPEN_CHANNEL_RESPONSE) to this PASSIVE-level
    // work item.
    WDFWORKITEM          AttAcceptWorkItem;
    L2CAP_CHANNEL_HANDLE PendingAttConn;   // connection handle from the indication
    BTH_ADDR             PendingAttAddr;
    L2CAP_CHANNEL_HANDLE AttChannelHandle; // the accepted ATT channel
    BOOLEAN              AttConnected;
    ULONG                AttIndicationCount; // # inbound ATT connect indications
    LONG                 AttAcceptStatus;    // NTSTATUS of the last accept attempt
    LONG                 AttRegisterStatus;  // NTSTATUS of the server register
} DEVICE_CONTEXT, *PDEVICE_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(DEVICE_CONTEXT, DeviceGetContext)

//
// Callbacks / helpers, split across Driver.c / Device.c / L2cap.c / Ioctl.c.
//
DRIVER_INITIALIZE                 DriverEntry;
EVT_WDF_DRIVER_DEVICE_ADD         LpEvtDeviceAdd;

EVT_WDF_DEVICE_PREPARE_HARDWARE   LpEvtDevicePrepareHardware;
EVT_WDF_DEVICE_RELEASE_HARDWARE   LpEvtDeviceReleaseHardware;
// Runs on the remove path while the default I/O target is still STARTED, which
// is the only window where our teardown BRBs can actually reach bthport. See
// the comment on the implementation in Device.c.
EVT_WDF_DEVICE_SELF_MANAGED_IO_SUSPEND LpEvtDeviceSelfManagedIoSuspend;
EVT_WDF_OBJECT_CONTEXT_CLEANUP    LpEvtDeviceContextCleanup;

EVT_WDF_IO_QUEUE_IO_DEVICE_CONTROL LpEvtIoDeviceControl;

// Fires when the LibrePods app closes its handle to our device interface -> we
// tear down the L2CAP channel so it doesn't leak (which left Windows unable to
// disconnect the AirPods until a manual disconnect/reboot).
EVT_WDF_FILE_CLOSE                 LpEvtFileClose;

// L2cap.c
NTSTATUS LpSubmitBrbSync(_In_ PDEVICE_CONTEXT Ctx, _Inout_ PBRB Brb);
NTSTATUS LpConnect(_In_ PDEVICE_CONTEXT Ctx, _In_ BTH_ADDR Address, _In_ USHORT Psm);
NTSTATUS LpDisconnect(_In_ PDEVICE_CONTEXT Ctx);
NTSTATUS LpSend(_In_ PDEVICE_CONTEXT Ctx, _In_reads_bytes_(Length) PVOID Buffer, _In_ ULONG Length);
NTSTATUS LpReceive(_In_ PDEVICE_CONTEXT Ctx, _Out_writes_bytes_(BufferLen) PVOID Buffer,
                   _In_ ULONG BufferLen, _Out_ PULONG BytesRead, _In_ ULONG TimeoutMs);

_Function_class_(PFNBTHPORT_INDICATION_CALLBACK)
VOID LpIndicationCallback(_In_opt_ PVOID Context, _In_ INDICATION_CODE Indication,
                          _In_ PINDICATION_PARAMETERS Parameters);

// ATT (PSM 0x001F) — we OPEN it as a CLIENT to the AirPods (like Android's
// ATTManager), which sidesteps the bthport rule that forbids registering a SERVER
// on the reserved ATT PSM. LpConnectAtt opens it; LpCloseAttChannel tears it down.
// (The server-register path below is kept but unused — it returned 0xC000000D.)
NTSTATUS LpConnectAtt(_In_ PDEVICE_CONTEXT Ctx);
NTSTATUS LpAttSend(_In_ PDEVICE_CONTEXT Ctx, _In_reads_bytes_(Length) PVOID Buffer, _In_ ULONG Length);
NTSTATUS LpAttReceive(_In_ PDEVICE_CONTEXT Ctx, _Out_writes_bytes_(BufferLen) PVOID Buffer,
                      _In_ ULONG BufferLen, _Out_ PULONG BytesRead, _In_ ULONG TimeoutMs);
NTSTATUS LpRegisterAttServer(_In_ PDEVICE_CONTEXT Ctx);
VOID     LpUnregisterAttServer(_In_ PDEVICE_CONTEXT Ctx);
VOID     LpCloseAttChannel(_In_ PDEVICE_CONTEXT Ctx);

_Function_class_(PFNBTHPORT_INDICATION_CALLBACK)
VOID LpAttServerIndication(_In_opt_ PVOID Context, _In_ INDICATION_CODE Indication,
                           _In_ PINDICATION_PARAMETERS Parameters);

EVT_WDF_WORKITEM LpAttAcceptWorkItem;

#endif // _LIBREPODSAAP_H_
