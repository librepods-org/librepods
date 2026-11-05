// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "l2c_fcr_hook.h"

// Public observations at the external Bluetooth SDK boundary, not hook internals.
struct FixtureRegistrationRequest {
    tSDP_DI_RECORD *record;
    uint32_t *handle;
    tSDP_DI_RECORD data;
    uint32_t inputHandle;
};

extern "C" void FixtureConfigureRegistrationResult(tBTA_STATUS status, uint32_t handle,
        bool writesHandle);
extern "C" uint32_t FixtureRegistrationAttemptCount();
extern "C" uint32_t FixtureRegisteredRecordCount();
extern "C" FixtureRegistrationRequest FixtureLastRegistrationRequest();
