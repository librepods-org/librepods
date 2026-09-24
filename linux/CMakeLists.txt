cmake_minimum_required(VERSION 3.16)

project(linux VERSION 0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 REQUIRED COMPONENTS Quick Widgets Svg Bluetooth DBus LinguistTools)
find_package(OpenSSL REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(PULSEAUDIO REQUIRED libpulse)

qt_standard_project_setup()

# Translation files
set(TS_FILES
    translations/librepods_tr.ts
)

qt_add_executable(librepods
    main.cpp
    logger.h
    media/mediacontroller.cpp
    media/mediacontroller.h
    media/pulseaudiocontroller.cpp
    media/pulseaudiocontroller.h
    airpods_packets.h
    trayiconmanager.cpp
    trayiconmanager.h
    enums.h
    battery.hpp
    BluetoothMonitor.cpp
    BluetoothMonitor.h
    autostartmanager.hpp
    BasicControlCommand.hpp
    deviceinfo.hpp
    ble/bleutils.cpp
    ble/bleutils.h
    ble/blemanager.cpp
    ble/blemanager.h
    thirdparty/QR-Code-generator/qrcodegen.cpp
    thirdparty/QR-Code-generator/qrcodegen.hpp
    QRCodeImageProvider.hpp
    IconImageProvider.hpp
    eardetection.hpp
    media/playerstatuswatcher.cpp
    media/playerstatuswatcher.h
    systemsleepmonitor.hpp
)

set_source_files_properties(Theme.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)

qt_add_qml_module(librepods
    URI linux
    VERSION 1.0
    QML_FILES
        Main.qml
        BatteryIndicator.qml
        SegmentedControl.qml
        PodColumn.qml
        Icon.qml
        KeysQRDialog.qml
        Theme.qml
        UiBadge.qml
        UiButton.qml
        UiCard.qml
        UiSelect.qml
        UiSlider.qml
        UiSpinBox.qml
        UiSwitch.qml
        UiTextField.qml
        UiToolTip.qml
)

# Add the resource file
qt_add_resources(librepods "resources"
    PREFIX "/icons"
    FILES
        assets/fonts/DepartureMono-Regular.otf
        assets/icons/arrow-left.svg
        assets/icons/audio-lines.svg
        assets/icons/battery-charging.svg
        assets/icons/battery-full.svg
        assets/icons/battery-low.svg
        assets/icons/battery-medium.svg
        assets/icons/battery-warning.svg
        assets/icons/bell.svg
        assets/icons/bluetooth-connected.svg
        assets/icons/bluetooth-off.svg
        assets/icons/bluetooth-searching.svg
        assets/icons/check.svg
        assets/icons/chevron-down.svg
        assets/icons/circle-alert.svg
        assets/icons/circle-check.svg
        assets/icons/circle-off.svg
        assets/icons/ear-off.svg
        assets/icons/ear.svg
        assets/icons/headphone-off.svg
        assets/icons/info.svg
        assets/icons/key-round.svg
        assets/icons/loader-circle.svg
        assets/icons/pencil.svg
        assets/icons/power.svg
        assets/icons/qr-code.svg
        assets/icons/refresh-cw.svg
        assets/icons/settings.svg
        assets/icons/sliders-horizontal.svg
        assets/icons/smartphone.svg
        assets/icons/speech.svg
        assets/icons/triangle-alert.svg
        assets/icons/x.svg
        assets/illustrations/bud.svg
        assets/illustrations/case.svg
        assets/illustrations/headphones.svg
)

target_link_libraries(librepods
    PRIVATE Qt6::Quick Qt6::Widgets Qt6::Svg Qt6::Bluetooth Qt6::DBus OpenSSL::SSL OpenSSL::Crypto ${PULSEAUDIO_LIBRARIES}
)

qt_add_executable(librepods-ctl
    librepods-ctl.cpp
)
target_link_libraries(librepods-ctl
    PRIVATE Qt6::Core Qt6::Network
)
install(TARGETS librepods-ctl
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)

target_include_directories(librepods PRIVATE ${PULSEAUDIO_INCLUDE_DIRS})

include(GNUInstallDirs)
install(TARGETS librepods
    BUNDLE DESTINATION .
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)
install(FILES assets/me.kavishdevar.librepods.desktop
    DESTINATION "${CMAKE_INSTALL_DATAROOTDIR}/applications")
install(FILES assets/librepods.svg
    DESTINATION "${CMAKE_INSTALL_DATAROOTDIR}/icons/hicolor/scalable/apps")

# Translation support
qt_add_translations(librepods
    TS_FILES ${TS_FILES}
    QM_FILES_OUTPUT_VARIABLE QM_FILES
)

# Install translation files
install(FILES ${QM_FILES}
    DESTINATION "${CMAKE_INSTALL_DATAROOTDIR}/librepods/translations")
