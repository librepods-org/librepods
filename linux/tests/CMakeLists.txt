cmake_minimum_required(VERSION 3.16)
project(librepods_dbus_regression LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
find_package(Qt6 REQUIRED COMPONENTS Core DBus)

add_executable(bluetoothmonitor_dbus_probe
  bluetoothmonitor_dbus_probe.cpp
  ../BluetoothMonitor.cpp
  ../BluetoothMonitor.h
)
target_include_directories(bluetoothmonitor_dbus_probe PRIVATE ..)
target_link_libraries(bluetoothmonitor_dbus_probe PRIVATE Qt6::Core Qt6::DBus)
