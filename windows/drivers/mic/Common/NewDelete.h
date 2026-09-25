/*++
Copyright (c) Microsoft Corporation.  All rights reserved.
    THIS CODE AND INFORMATION IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY
    KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND/OR FITNESS FOR A PARTICULAR
    PURPOSE.
Module Name:
    NewDelete.h
Abstract:
    Contains overloaded placement new and delete operators
Environment:
    Kernel mode
--*/

/*****************************************************************************
 * ::new(POOL_FLAGS)
 *****************************************************************************
 * New function for creating objects with a specified pool flags.
 */
PVOID operator new(
    _In_ size_t size,
    _In_ POOL_FLAGS poolFlags
);

/*****************************************************************************
 * ::new(POOL_FLAGS, TAG)
 *****************************************************************************
 * New function for creating objects with specified pool flags and allocation tag.
 */
PVOID operator new(
    _In_ size_t size,
    _In_ POOL_FLAGS poolFlags,
    _In_ ULONG tag
);

/*****************************************************************************
 * ::delete()
 *****************************************************************************
 * Delete function.
 */
void __cdecl operator delete(PVOID buffer);

/*****************************************************************************
 * ::delete()
 *****************************************************************************
 * Delete function.
 */
void __cdecl operator delete(PVOID buffer, ULONG tag);

void __cdecl operator delete[](PVOID pVoid, _In_ size_t cbSize);

void __cdecl operator delete(_Pre_maybenull_ __drv_freesMem(Mem) PVOID buffer, _In_ size_t cbSize);

void __cdecl operator delete[](_Pre_maybenull_ __drv_freesMem(Mem) PVOID buffer);

void __cdecl operator delete[](_Pre_maybenull_ __drv_freesMem(Mem) PVOID buffer, _In_ size_t cbSize);


