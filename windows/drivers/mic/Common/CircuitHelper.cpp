/*++

Copyright (c) Microsoft Corporation.  All rights reserved.

    THIS CODE AND INFORMATION IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY
    KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND/OR FITNESS FOR A PARTICULAR
    PURPOSE.

Module Name:

    CircuitHelper.cpp

Abstract:

   This module contains helper functions for circuits.

Environment:

    Kernel mode

--*/

#include "private.h"
#include "public.h"
#include "CircuitHelper.h"

#ifndef __INTELLISENSE__
#include "CircuitHelper.tmh"
#endif


PAGED_CODE_SEG
NTSTATUS AllocateFormat(
    _In_ KSDATAFORMAT_WAVEFORMATEXTENSIBLE      WaveFormat,
    _In_ ACXCIRCUIT                             Circuit,
    _In_ WDFDEVICE                              Device,
    _Out_ ACXDATAFORMAT*                        Format
)
{
    PAGED_CODE();

    NTSTATUS status = STATUS_SUCCESS;

    WDF_OBJECT_ATTRIBUTES attributes;
    WDF_OBJECT_ATTRIBUTES_INIT(&attributes);

    ACX_DATAFORMAT_CONFIG formatCfg;
    ACX_DATAFORMAT_CONFIG_INIT_KS(&formatCfg, &WaveFormat);
    WDF_OBJECT_ATTRIBUTES_INIT_CONTEXT_TYPE(&attributes, FORMAT_CONTEXT);
    attributes.ParentObject = Circuit;

    //
    // Creates an ACXDATAFORMAT handle for the given wave format.
    //
    RETURN_NTSTATUS_IF_FAILED(AcxDataFormatCreate(Device, &attributes, &formatCfg, Format));

    ASSERT((*Format) != NULL);
    FORMAT_CONTEXT* formatCtx;
    formatCtx = GetFormatContext(*Format);
    ASSERT(formatCtx);
    UNREFERENCED_PARAMETER(formatCtx);

    return status;
}

