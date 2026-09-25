/*++

Copyright (c) Microsoft Corporation.  All rights reserved.

    THIS CODE AND INFORMATION IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY
    KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND/OR FITNESS FOR A PARTICULAR
    PURPOSE.

Module Name:

    CircuitHelper.h

Abstract:

   This module contains helper functions for endpoints.

Environment:

    Kernel mode

--*/

PAGED_CODE_SEG
NTSTATUS AllocateFormat(
    _In_ KSDATAFORMAT_WAVEFORMATEXTENSIBLE      WaveFormat,
    _In_ ACXCIRCUIT                             Circuit,
    _In_ WDFDEVICE                              Device,
    _Out_ ACXDATAFORMAT*                        Format
);
