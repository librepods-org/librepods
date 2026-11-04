// SPDX-License-Identifier: AGPL-3.0-only
#pragma once
#define PROP_VALUE_MAX 92
#ifdef __cplusplus
extern "C" {
#endif
int __system_property_get(const char *name, char *value);
#ifdef __cplusplus
}
#endif
