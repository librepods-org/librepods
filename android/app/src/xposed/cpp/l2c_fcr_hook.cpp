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

#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <elf.h>

#include "l2c_fcr_hook.h"

extern "C" {
    #include "xz.h"
}

#define LOG_TAG "LibrePods"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static HookFunType hook_func = nullptr;

static uint8_t (*original_l2c_fcr_chk_chan_modes)(void*) = nullptr;
static tBTA_STATUS (*original_BTA_DmSetLocalDiRecord)(
        tSDP_DI_RECORD*, uint32_t*) = nullptr;

uint8_t fake_l2c_fcr_chk_chan_modes(void* p_ccb) {
    LOGI("l2c_fcr_chk_chan_modes hooked");
    uint8_t orig = 0;
    if (original_l2c_fcr_chk_chan_modes)
        orig = original_l2c_fcr_chk_chan_modes(p_ccb);

    LOGI("Original returned %d, forcing 1", orig);
    return 1;
}

tBTA_STATUS fake_BTA_DmSetLocalDiRecord(
        tSDP_DI_RECORD* p_device_info,
        uint32_t* p_handle) {

    LOGI("BTA_DmSetLocalDiRecord hooked");

    if (p_device_info) {
        p_device_info->vendor = 0x004C;
        p_device_info->vendor_id_source = 0x0001;
    }

    if (original_BTA_DmSetLocalDiRecord)
        return original_BTA_DmSetLocalDiRecord(p_device_info, p_handle);

    return BTA_FAILURE;
}

static bool decompressXZ(
        const uint8_t* input,
        size_t input_size,
        std::vector<uint8_t>& output) {

    xz_crc32_init();
#ifdef XZ_USE_CRC64
    xz_crc64_init();
#endif

    struct xz_dec* dec = xz_dec_init(XZ_DYNALLOC, 64U << 20);
    if (!dec) return false;

    struct xz_buf buf{};
    buf.in = input;
    buf.in_pos = 0;
    buf.in_size = input_size;

    output.resize(input_size * 8);

    buf.out = output.data();
    buf.out_pos = 0;
    buf.out_size = output.size();

    while (true) {
        enum xz_ret ret = xz_dec_run(dec, &buf);

        if (ret == XZ_STREAM_END)
            break;

        if (ret != XZ_OK) {
            xz_dec_end(dec);
            return false;
        }

        if (buf.out_pos == buf.out_size) {
            size_t old = output.size();
            output.resize(old * 2);
            buf.out = output.data();
            buf.out_size = output.size();
        }
    }

    output.resize(buf.out_pos);
    xz_dec_end(dec);
    return true;
}

static bool getLibraryPath(const char* name, std::string& out) {
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) return false;

    char line[1024];

    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, name)) {
            char* path = strchr(line, '/');
            if (path) {
                out = path;
                out.erase(out.find('\n'));
                fclose(fp);
                return true;
            }
        }
    }

    fclose(fp);
    return false;
}

static uintptr_t getModuleBase(const char* name) {
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) return 0;

    char line[1024];
    uintptr_t base = 0;

    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, name)) {
            base = strtoull(line, nullptr, 16);
            break;
        }
    }

    fclose(fp);
    return base;
}

static uint64_t findSymbolOffset(
        const std::vector<uint8_t>& elf,
        const char* symbol_substring) {

    auto* eh = reinterpret_cast<const Elf64_Ehdr*>(elf.data());
    auto* shdr = reinterpret_cast<const Elf64_Shdr*>(
            elf.data() + eh->e_shoff);

    const char* shstr =
            reinterpret_cast<const char*>(
                    elf.data() + shdr[eh->e_shstrndx].sh_offset);

    const Elf64_Shdr* symtab = nullptr;
    const Elf64_Shdr* strtab = nullptr;

    for (int i = 0; i < eh->e_shnum; ++i) {
        const char* secname = shstr + shdr[i].sh_name;
        if (!strcmp(secname, ".symtab"))
            symtab = &shdr[i];
        if (!strcmp(secname, ".strtab"))
            strtab = &shdr[i];
    }

    if (!symtab || !strtab)
        return 0;

    auto* symbols = reinterpret_cast<const Elf64_Sym*>(
            elf.data() + symtab->sh_offset);

    const char* strings =
            reinterpret_cast<const char*>(
                    elf.data() + strtab->sh_offset);

    size_t count = symtab->sh_size / sizeof(Elf64_Sym);

    for (size_t i = 0; i < count; ++i) {
        const char* name = strings + symbols[i].st_name;

        if (strstr(name, symbol_substring) &&
            ELF64_ST_TYPE(symbols[i].st_info) == STT_FUNC) {

            LOGI("Resolved %s at 0x%lx",
                 name,
                 (unsigned long)symbols[i].st_value);

            return symbols[i].st_value;
        }
    }

    return 0;
}

static bool hookLibrary(const char* libname) {

    if (!hook_func) {
        LOGE("hook_func not initialized");
        return false;
    }

    std::string path;
    if (!getLibraryPath(libname, path)) {
        LOGE("Failed to locate %s", libname);
        return false;
    }

    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) return false;

    struct stat st{};
    if (fstat(fd, &st) != 0) {
        close(fd);
        return false;
    }

    std::vector<uint8_t> file(st.st_size);
    read(fd, file.data(), st.st_size);
    close(fd);

    auto* eh = reinterpret_cast<Elf64_Ehdr*>(file.data());
    auto* shdr = reinterpret_cast<Elf64_Shdr*>(
            file.data() + eh->e_shoff);

    const char* shstr =
            reinterpret_cast<const char*>(
                    file.data() + shdr[eh->e_shstrndx].sh_offset);

    for (int i = 0; i < eh->e_shnum; ++i) {

        if (!strcmp(shstr + shdr[i].sh_name, ".gnu_debugdata")) {

            std::vector<uint8_t> compressed(
                    file.begin() + shdr[i].sh_offset,
                    file.begin() + shdr[i].sh_offset + shdr[i].sh_size);

            std::vector<uint8_t> decompressed;

            if (!decompressXZ(
                    compressed.data(),
                    compressed.size(),
                    decompressed))
                return false;

            uintptr_t base = getModuleBase(libname);
            if (!base) return false;

            uint64_t chk_offset =
                    findSymbolOffset(decompressed,
                                     "l2c_fcr_chk_chan_modes");

//            uint64_t sdp_offset =
//                    findSymbolOffset(decompressed,
//                                     "BTA_DmSetLocalDiRecord");

            if (chk_offset) {
                void* target =
                        reinterpret_cast<void*>(base + chk_offset);

                hook_func(target,
                          (void*)fake_l2c_fcr_chk_chan_modes,
                          (void**)&original_l2c_fcr_chk_chan_modes);

                LOGI("Hooked l2c_fcr_chk_chan_modes");
            }

//            if (sdp_offset) {
//                void* target =
//                        reinterpret_cast<void*>(base + sdp_offset);
//
//                hook_func(target,
//                          (void*)fake_BTA_DmSetLocalDiRecord,
//                          (void**)&original_BTA_DmSetLocalDiRecord);
//
//                LOGI("Hooked BTA_DmSetLocalDiRecord");
//            }

            return true;
        }
    }

    return false;
}

static void on_library_loaded(const char* name, void*) {

    if (strstr(name, "libbluetooth_jni.so")) {
        LOGI("Bluetooth JNI loaded");
        hookLibrary("libbluetooth_jni.so");
    }

    if (strstr(name, "libbluetooth_qti.so")) {
        LOGI("Bluetooth QTI loaded");
        hookLibrary("libbluetooth_qti.so");
    }
}

extern "C"
[[gnu::visibility("default")]]
[[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries* entries) {

    LOGI("LibrePods initialized");

    hook_func = (HookFunType)entries->hook_func;

    return on_library_loaded;
}
