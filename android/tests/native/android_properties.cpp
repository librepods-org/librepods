// SPDX-License-Identifier: AGPL-3.0-only
#include <sys/system_properties.h>
#include <algorithm>
#include <cstring>
#include <map>
#include <mutex>
#include <string>

namespace {
std::map<std::string, std::string> properties;
std::mutex propertyMutex;
}

// Control only the external Android property boundary, never production hook state.
extern "C" void AndroidTestSetSystemProperty(const char *name, const char *value) {
    std::lock_guard<std::mutex> lock(propertyMutex);
    properties[name] = value;
}

extern "C" int __system_property_get(const char *name, char *value) {
    std::lock_guard<std::mutex> lock(propertyMutex);
    const auto entry = properties.find(name);
    if (entry == properties.end()) {
        value[0] = '\0';
        return 0;
    }
    const auto size = std::min(entry->second.size(), static_cast<std::size_t>(PROP_VALUE_MAX - 1));
    std::memcpy(value, entry->second.data(), size);
    value[size] = '\0';
    return static_cast<int>(size);
}
