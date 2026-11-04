// SPDX-License-Identifier: GPL-3.0-or-later
#include "bluetooth_fixture.h"
#include "l2c_fcr_hook.h"
#include <cstddef>
#include <cstring>
#include <dlfcn.h>
#include <iostream>
#include <stdexcept>
#include <string>
#include <sstream>

namespace {
class Library {
public:
    explicit Library(const char *path) : handle(dlopen(path, RTLD_NOW | RTLD_LOCAL)) {
        if (!handle) {
            throw std::runtime_error(std::string("dlopen failed: ") + dlerror());
        }
    }
    ~Library() { dlclose(handle); }
    Library(const Library &) = delete;
    Library &operator=(const Library &) = delete;

    template<typename Function>
    Function symbol(const char *name) const {
        dlerror();
        void *address = dlsym(handle, name);
        const char *error = dlerror();
        if (error || !address) {
            throw std::runtime_error(std::string("Missing required symbol ") + name);
        }
        return reinterpret_cast<Function>(address);
    }
    void *get() const { return handle; }

private:
    void *handle;
};

void *sdpTarget = nullptr;
void *checkTarget = nullptr;
void *sdpReplacement = nullptr;
bool unexpectedRegistration = false;

// The native-hook SDK is an external system boundary. Capture the installed
// replacement and supply the real fixture function as its original trampoline.
int registerHook(void *target, void *replacement, void **backup) {
    if (!replacement || !backup || replacement == target ||
        (target != sdpTarget && target != checkTarget)) {
        unexpectedRegistration = true;
        return -1;
    }
    if (target == sdpTarget) {
        if (sdpReplacement) {
            unexpectedRegistration = true;
            return -1;
        }
        sdpReplacement = replacement;
    }
    *backup = target;
    return 0;
}

using RegisterRecord = tBTA_STATUS (*)(tSDP_DI_RECORD *, uint32_t *);
using SetSystemProperty = void (*)(const char *, const char *);
using RecordCount = uint32_t (*)();

std::string fixtureOffset(void *target) {
    Dl_info info{};
    if (!dladdr(target, &info) || !info.dli_fbase) {
        throw std::runtime_error("Cannot resolve the real mapped fixture base");
    }
    const auto address = reinterpret_cast<uintptr_t>(target);
    const auto base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    if (address <= base) {
        throw std::runtime_error("Expected a positive exported SDK symbol offset");
    }
    std::ostringstream text;
    text << std::hex << (address - base);
    return text.str();
}

template<std::size_t BufferSize, std::size_t TextSize>
void fillCallerText(char (&buffer)[BufferSize], char fill, const char (&text)[TextSize]) {
    static_assert(TextSize <= BufferSize, "Caller text must fit its buffer");
    std::memset(buffer, fill, BufferSize);
    std::memcpy(buffer, text, TextSize);
    buffer[BufferSize - 1] = '\0';
}

void expectCallerData(const tSDP_DI_RECORD &actual, const tSDP_DI_RECORD &caller,
        const std::string &context) {
    if (actual.product != caller.product || actual.version != caller.version ||
        actual.primary_record != caller.primary_record ||
        std::memcmp(actual.client_executable_url, caller.client_executable_url,
                sizeof(caller.client_executable_url)) != 0 ||
        std::memcmp(actual.service_description, caller.service_description,
                sizeof(caller.service_description)) != 0 ||
        std::memcmp(actual.documentation_url, caller.documentation_url,
                sizeof(caller.documentation_url)) != 0) {
        throw std::runtime_error(context + ": product/version/primary/text buffers changed");
    }
}

// Characterize the existing public forwarding contract; this is not a new RED.
void characterizeForwarding(const Library &fixture, RegisterRecord installed,
        RecordCount recordCount) {
    const auto configure = fixture.symbol<decltype(&FixtureConfigureRegistrationResult)>(
            "FixtureConfigureRegistrationResult");
    const auto attemptCount = fixture.symbol<decltype(&FixtureRegistrationAttemptCount)>(
            "FixtureRegistrationAttemptCount");
    const auto lastRequest = fixture.symbol<decltype(&FixtureLastRegistrationRequest)>(
            "FixtureLastRegistrationRequest");
    struct ForwardingCase {
        const char *name;
        tBTA_STATUS status;
        uint32_t sdkHandle;
        bool writesHandle;
        uint32_t expectedHandle;
        bool primaryRecord;
    };
    // Literal external SDK outcomes, independent of the production wrapper.
    // A fixed uint8_t enum can also carry an unnamed byte without normalization.
    const ForwardingCase cases[] = {
        {"BTA_SUCCESS", BTA_SUCCESS, 0xA1B2C3D4, true, 0xA1B2C3D4, false},
        {"BTA_FAILURE", BTA_FAILURE, 0xF1E2D3C4, true, 0xF1E2D3C4, true},
        {"BTA_PENDING", BTA_PENDING, 0x01020304, true, 0x01020304, false},
        {"BTA_BUSY", BTA_BUSY, 0x00000000, true, 0x00000000, true},
        {"BTA_NO_RESOURCES", BTA_NO_RESOURCES, 0x89ABCDEF, true, 0x89ABCDEF, false},
        {"BTA_WRONG_MODE", BTA_WRONG_MODE, 0x76543210, true, 0x76543210, true},
        {"unknown_status_0xA5", static_cast<tBTA_STATUS>(0xA5),
                0x13579BDF, true, 0x13579BDF, false},
        {"BTA_FAILURE_untouched_handle", BTA_FAILURE,
                0x2468ACE0, false, 0xDEADBEEF, true},
    };
    if (attemptCount() != 0 || recordCount() != 0) {
        throw std::runtime_error("Expected an empty SDK fixture before forwarding requests");
    }
    uint32_t expectedAttempts = 0;
    for (const auto &test : cases) {
        configure(test.status, test.sdkHandle, test.writesHandle);
        tSDP_DI_RECORD record{};
        record.vendor = 0x1234;
        record.vendor_id_source = 0x0002;
        record.product = 0x6B7C;
        record.version = 0x9D2E;
        record.primary_record = test.primaryRecord;
        fillCallerText(record.client_executable_url, 'C', "https://caller.invalid/client");
        fillCallerText(record.service_description, 'S', "Caller-owned SDP record");
        fillCallerText(record.documentation_url, 'D', "https://caller.invalid/manual");
        const tSDP_DI_RECORD caller = record;
        uint32_t handle = 0xDEADBEEF;

        const tBTA_STATUS status = installed(&record, &handle);
        const FixtureRegistrationRequest request = lastRequest();
        const std::string context = test.name;
        ++expectedAttempts;
        if (attemptCount() != expectedAttempts || recordCount() != 1) {
            throw std::runtime_error(context +
                    ": expected one SDK attempt per request and only one successful non-primary record");
        }
        if (status != test.status || handle != test.expectedHandle) {
            throw std::runtime_error(context + ": SDK status/handle output was not preserved");
        }
        if (request.record != &record || request.handle != &handle ||
            request.inputHandle != 0xDEADBEEF) {
            throw std::runtime_error(context + ": original caller pointers/input handle were not forwarded");
        }
        if (request.data.vendor != 0x004C || request.data.vendor_id_source != 0x0001 ||
            record.vendor != 0x004C || record.vendor_id_source != 0x0001) {
            throw std::runtime_error(context + ": enabled SDP policy must change only the Apple vendor identity");
        }
        expectCallerData(request.data, caller, context + " at SDK entry");
        expectCallerData(record, caller, context + " after SDK return");
    }
    std::cout << "PASS: installed SDP callback preserves SDK status/handle and caller data with enabled vendor policy\n";
}
}

int main(int argc, char **argv) {
    try {
        if (argc != 3 && argc != 4) {
            throw std::runtime_error("Usage: sdp_registration_test MODULE FIXTURE [CASE]");
        }
        const std::string scenario = argc == 4 ? argv[3] : "single_registration";
        if (scenario != "single_registration" && scenario != "disabled_vendor" &&
            scenario != "forwarding_contract" && scenario != "null_record") {
            throw std::runtime_error("Unknown regression case: " + scenario);
        }
        Library fixture(argv[2]);
        const auto original = fixture.symbol<RegisterRecord>("BTA_DmSetLocalDiRecord");
        sdpTarget = reinterpret_cast<void *>(original);
        checkTarget = reinterpret_cast<void *>(fixture.symbol<uint8_t (*)(void *)>(
                "l2c_fcr_chk_chan_modes"));
        const auto recordCount = fixture.symbol<RecordCount>("FixtureRegisteredRecordCount");

        Library module(argv[1]);
        const auto setProperty = module.symbol<SetSystemProperty>("AndroidTestSetSystemProperty");
        const auto checkOffset = fixtureOffset(checkTarget);
        const auto sdpOffset = fixtureOffset(sdpTarget);
        setProperty("persist.librepods.hook_offset", checkOffset.c_str());
        // The integration implementation disables spoofing by omitting the SDP
        // offset. Preserve this public policy instead of importing a new JNI loader.
        setProperty("persist.librepods.sdp_offset",
                scenario == "disabled_vendor" ? "" : sdpOffset.c_str());
        NativeAPIEntries entries{1, &registerHook, nullptr};
        const auto moduleLoaded = module.symbol<NativeInit>("native_init")(&entries);
        if (!moduleLoaded) {
            throw std::runtime_error("native_init returned no module-loaded callback");
        }
        moduleLoaded("libbluetooth_jni.so", fixture.get());
        if (unexpectedRegistration ||
                (scenario == "disabled_vendor" ? sdpReplacement != nullptr : sdpReplacement == nullptr)) {
            throw std::runtime_error("Unexpected native_init SDK installation for the selected offset policy");
        }
        const auto installed = scenario == "disabled_vendor" ? original :
                reinterpret_cast<RegisterRecord>(sdpReplacement);
        if (scenario == "null_record") {
            uint32_t handle = 0xDEADBEEF;
            const auto status = installed(nullptr, &handle);
            const auto attempts = fixture.symbol<decltype(&FixtureRegistrationAttemptCount)>(
                    "FixtureRegistrationAttemptCount")();
            const auto request = fixture.symbol<decltype(&FixtureLastRegistrationRequest)>(
                    "FixtureLastRegistrationRequest")();
            if (status != BTA_FAILURE || handle != 0xDEADBEEF || attempts != 1 ||
                    recordCount() != 0 || request.record != nullptr || request.handle != &handle) {
                throw std::runtime_error("Null record must forward once and preserve the actual SDK failure/handle");
            }
            std::cout << "PASS: null record forwards once and preserves SDK failure\n";
            return 0;
        }

        if (scenario == "forwarding_contract") {
            characterizeForwarding(fixture, installed, recordCount);
            return 0;
        }

        tSDP_DI_RECORD record{};
        record.vendor = 0x1234;
        record.vendor_id_source = 0x0002;
        record.primary_record = false;
        const tSDP_DI_RECORD caller = record;
        fixture.symbol<decltype(&FixtureConfigureRegistrationResult)>(
                "FixtureConfigureRegistrationResult")(BTA_SUCCESS, 0xA1B2C3D4, true);
        uint32_t handle = 0;
        const auto status = installed(&record, &handle);
        const auto request = fixture.symbol<decltype(&FixtureLastRegistrationRequest)>(
                "FixtureLastRegistrationRequest")();
        if (status != BTA_SUCCESS || handle != 0xA1B2C3D4 ||
                request.record != &record || request.handle != &handle || request.inputHandle != 0) {
            throw std::runtime_error("SDK status, handle and original pointers must be preserved");
        }
        expectCallerData(record, caller, "after registration");
        expectCallerData(request.data, caller, "at SDK entry");

        const uint32_t actual = recordCount();
        if (actual != 1) {
            throw std::runtime_error("Expected one native SDP registration, got " +
                                     std::to_string(actual));
        }
        if (scenario == "disabled_vendor") {
            if (record.vendor != 0x1234 || record.vendor_id_source != 0x0002 ||
                    request.data.vendor != 0x1234 || request.data.vendor_id_source != 0x0002) {
                throw std::runtime_error("Disabled SDP spoof must preserve the original vendor identity");
            }
            std::cout << "PASS: disabled spoof preserves vendor identity and normal SDP registration\n";
        } else {
            std::cout << "PASS: one hook invocation creates exactly one native SDP record\n";
        }
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
