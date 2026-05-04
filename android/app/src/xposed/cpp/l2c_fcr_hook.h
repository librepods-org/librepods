/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

#pragma once
#include <cstdint>

typedef int (*HookFunType)(void *func, void *replace, void **backup);

typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);

typedef struct {
    uint32_t version;
    void* hook_func;
    void* unhook_func;
} NativeAPIEntries;

typedef NativeOnModuleLoaded (*NativeInit)(const NativeAPIEntries *entries);

typedef enum : uint8_t {
    BTA_SUCCESS = 0, /* Successful operation. */
    BTA_FAILURE = 1, /* Generic failure. */
    BTA_PENDING = 2, /* API cannot be completed right now */
    BTA_BUSY = 3,
    BTA_NO_RESOURCES = 4,
    BTA_WRONG_MODE = 5,
} tBTA_STATUS;

typedef struct t_sdp_di_record {
    uint16_t vendor;
    uint16_t vendor_id_source;
    uint16_t product;
    uint16_t version;
    bool primary_record;
    char client_executable_url[400];
    char service_description[400];
    char documentation_url[400];
} tSDP_DI_RECORD;
