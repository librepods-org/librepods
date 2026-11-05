// SPDX-License-Identifier: GPL-3.0-or-later
#include <cstdarg>
#include <cstdio>

extern "C" int __android_log_print(int, const char *tag, const char *format, ...) {
    std::fprintf(stderr, "%s: ", tag);
    va_list args;
    va_start(args, format);
    const int result = std::vfprintf(stderr, format, args);
    va_end(args);
    std::fputc('\n', stderr);
    return result;
}
