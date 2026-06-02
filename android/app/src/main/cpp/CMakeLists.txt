cmake_minimum_required(VERSION 3.22.1)

project("l2c_fcr_hook")
set(CMAKE_CXX_STANDARD 23)

add_library(bluetooth_socket SHARED
        bluetooth_socket.cpp
)

target_compile_options(bluetooth_socket PRIVATE
        -O2
        -fvisibility=hidden
)

target_link_options(bluetooth_socket PRIVATE
        -Wl,--strip-all
        -Wl,--gc-sections
)

target_link_libraries(bluetooth_socket
        android
        log
)


set(XPOSED_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../xposed/cpp)

add_library(l2c_fcr_hook SHARED
        l2c_fcr_hook.cpp

        xz/xz_crc32.c
        xz/xz_crc64.c
        xz/xz_sha256.c
        xz/xz_dec_stream.c
        xz/xz_dec_lzma2.c
        xz/xz_dec_bcj.c
)

target_include_directories(l2c_fcr_hook PRIVATE
        xz
)

target_compile_definitions(l2c_fcr_hook PRIVATE
        XZ_DEC_X86
        XZ_DEC_ARM
        XZ_DEC_ARMTHUMB
        XZ_DEC_ARM64
        XZ_DEC_ANY_CHECK
        XZ_USE_CRC64
        XZ_USE_SHA256
        XZ_DEC_CONCATENATED
)

target_link_libraries(l2c_fcr_hook
        android
        log
)
