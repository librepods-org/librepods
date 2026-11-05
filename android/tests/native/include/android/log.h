// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6

extern "C" int __android_log_print(int priority, const char *tag, const char *format, ...);
