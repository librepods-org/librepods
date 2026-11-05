// SPDX-License-Identifier: GPL-3.0-or-later
#include "bluetooth_fixture.h"

namespace {
uint32_t registeredRecords = 0;
uint32_t registrationAttempts = 0;
tBTA_STATUS resultStatus = BTA_SUCCESS;
uint32_t resultHandle = 0;
bool configuredResult = false;
bool writesHandle = true;
FixtureRegistrationRequest lastRequest{};
}

extern "C" uint8_t l2c_fcr_chk_chan_modes(void *) {
    return 0;
}

// This external SDK tracks attempts separately from successful non-primary DI
// registrations. A non-success reply never allocates a registry entry, even when
// its configured output writes a handle. OEM primary allocation is not modeled.
extern "C" tBTA_STATUS BTA_DmSetLocalDiRecord(tSDP_DI_RECORD *record, uint32_t *handle) {
    ++registrationAttempts;
    lastRequest = {};
    lastRequest.record = record;
    lastRequest.handle = handle;
    if (!record || !handle) {
        return BTA_FAILURE;
    }
    lastRequest.data = *record;
    lastRequest.inputHandle = *handle;
    if (resultStatus == BTA_SUCCESS && !record->primary_record) {
        ++registeredRecords;
    }
    if (writesHandle) {
        *handle = configuredResult ? resultHandle : 0xA100 + registeredRecords;
    }
    return resultStatus;
}

extern "C" void FixtureConfigureRegistrationResult(tBTA_STATUS status, uint32_t handle,
        bool writeHandle) {
    resultStatus = status;
    resultHandle = handle;
    writesHandle = writeHandle;
    configuredResult = true;
}

extern "C" uint32_t FixtureRegistrationAttemptCount() {
    return registrationAttempts;
}

extern "C" uint32_t FixtureRegisteredRecordCount() {
    return registeredRecords;
}

extern "C" FixtureRegistrationRequest FixtureLastRegistrationRequest() {
    return lastRequest;
}
