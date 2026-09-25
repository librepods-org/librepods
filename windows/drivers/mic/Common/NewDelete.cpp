/*++
    THIS CODE AND INFORMATION IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY
    KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND/OR FITNESS FOR A PARTICULAR
    PURPOSE.
Module Name:
    newdelete.cpp
Abstract:
    Contains overloaded placement new and delete operators
Environment:
    Kernel mode
--*/

#include "private.h"
#include "NewDelete.h"

/*****************************************************************************
 * ::new(POOL_FLAGS)
 *****************************************************************************
 * New function for creating objects with a specified pool flags.
 */
PVOID operator new(
    _In_ size_t size,
    _In_ POOL_FLAGS poolFlags
)
{
    PVOID result = ExAllocatePool2(poolFlags, size, 'wNwS');

    return result;
}

/*****************************************************************************
 * ::new(POOL_FLAGS, TAG)
 *****************************************************************************
 * New function for creating objects with specified pool flags and allocation tag.
 */
PVOID operator new(
    _In_ size_t size,
    _In_ POOL_FLAGS poolFlags,
    _In_ ULONG tag
)
{
    PVOID result = ExAllocatePool2(poolFlags, size, tag);

    return result;
}

void __cdecl operator delete(PVOID buffer)
{
    if (buffer)
    {
        ExFreePool(buffer);
    }
}

void __cdecl operator delete(PVOID buffer, ULONG tag)
{
    if (buffer)
    {
        ExFreePoolWithTag(buffer, tag);
    }
}

void __cdecl operator delete(_Pre_maybenull_ __drv_freesMem(Mem) PVOID buffer, _In_ size_t cbSize)
{
    UNREFERENCED_PARAMETER(cbSize);

    if (buffer)
    {
        ExFreePool(buffer);
    }
}

void __cdecl operator delete[](_Pre_maybenull_ __drv_freesMem(Mem) PVOID buffer)
{
    if (buffer)
    {
        ExFreePool(buffer);
    }
}

void __cdecl operator delete[](_Pre_maybenull_ __drv_freesMem(Mem) PVOID buffer, _In_ size_t cbSize)
{
    UNREFERENCED_PARAMETER(cbSize);

    if (buffer)
    {
        ExFreePool(buffer);
    }
}

