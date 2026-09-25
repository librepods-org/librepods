/*++

Copyright (c) Microsoft Corporation.  All rights reserved.

    THIS CODE AND INFORMATION IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY
    KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND/OR FITNESS FOR A PARTICULAR
    PURPOSE.

Module Name:

    AudioFormats.h

Abstract:

    Contains Audio formats supported for the ACX Sample Drivers

Environment:

    Kernel mode

--*/

#pragma once

#define NOBITMAP
#include <mmreg.h>

//
// Basic-testing formats.
//
static
KSDATAFORMAT_WAVEFORMATEXTENSIBLE Pcm48000c1 =
{
    {
        sizeof(KSDATAFORMAT_WAVEFORMATEXTENSIBLE),
        0,
        0,
        0,
        STATICGUIDOF(KSDATAFORMAT_TYPE_AUDIO),
        STATICGUIDOF(KSDATAFORMAT_SUBTYPE_PCM),
        STATICGUIDOF(KSDATAFORMAT_SPECIFIER_WAVEFORMATEX)
    },
    {
        {
            WAVE_FORMAT_EXTENSIBLE,
            1,
            48000,
            96000,
            2,
            16,
            sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX)
        },
    16,
    KSAUDIO_SPEAKER_MONO,
    STATICGUIDOF(KSDATAFORMAT_SUBTYPE_PCM)
    }
};
