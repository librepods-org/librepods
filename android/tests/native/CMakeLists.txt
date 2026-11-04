cmake_minimum_required(VERSION 3.22.1)
project(librepods_native_regressions LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
enable_testing()
set(NATIVE_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")

# Exercise the real offset-driven production translation unit. Only unavailable
# Android logging/system-property APIs and the external hook/Bluetooth SDK are substituted.
add_library(native_module SHARED
  "${NATIVE_SOURCE_DIR}/l2c_fcr_hook.cpp"
  android_log.cpp
  android_properties.cpp
)
target_include_directories(native_module PRIVATE include "${NATIVE_SOURCE_DIR}")
target_link_libraries(native_module PRIVATE ${CMAKE_DL_LIBS})

add_library(bluetooth_fixture SHARED bluetooth_fixture.cpp)
set_target_properties(bluetooth_fixture PROPERTIES OUTPUT_NAME bluetooth_jni)
target_include_directories(bluetooth_fixture PRIVATE "${NATIVE_SOURCE_DIR}")

add_executable(sdp_registration_test sdp_registration_test.cpp)
target_include_directories(sdp_registration_test PRIVATE "${NATIVE_SOURCE_DIR}")
target_compile_options(sdp_registration_test PRIVATE -Wall -Wextra -Werror)
target_link_libraries(sdp_registration_test PRIVATE ${CMAKE_DL_LIBS})

foreach(target native_module bluetooth_fixture sdp_registration_test)
  target_compile_options(${target} PRIVATE
    -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer
  )
  target_link_options(${target} PRIVATE -fsanitize=address,undefined)
endforeach()

foreach(case single_registration disabled_vendor forwarding_contract null_record)
  add_test(NAME sdp_${case}
    COMMAND sdp_registration_test
      $<TARGET_FILE:native_module> $<TARGET_FILE:bluetooth_fixture> ${case}
  )
  set_tests_properties(sdp_${case} PROPERTIES TIMEOUT 10)
endforeach()
