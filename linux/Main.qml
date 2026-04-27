pragma ComponentBehavior: Bound

import QtQuick 2.15
import QtQuick.Controls 2.15

ApplicationWindow {
    id: mainWindow
    visible: !airPodsTrayApp.hideOnStart
    width: 420
    height: 420
    minimumWidth: 320
    minimumHeight: 360
    color: "#f4f5f7"
    title: "LibrePods"
    objectName: "mainWindowObject"
    readonly property color cardBackgroundColor: "#fbfbfc"
    readonly property color cardBorderColor: "#d9dde3"
    readonly property color primaryTextColor: "#1f2933"
    readonly property color secondaryTextColor: "#4f5965"
    readonly property color statusRowBackgroundColor: "#eef1f5"
    readonly property color controlAccentColor: "#2563eb"
    readonly property color controlInactiveColor: "#d6dce4"
    readonly property color controlBorderMutedColor: "#c5ced8"
    readonly property color controlDisabledFillColor: "#e9edf2"
    readonly property color controlDisabledAccentColor: "#b9c4d2"
    readonly property color controlDisabledTextColor: "#8b96a3"

    Component.onCompleted: {
        if (!airPodsTrayApp.hideOnStart) {
            visible = true
            show()
            raise()
            requestActivate()
        }
    }

    onClosing: mainWindow.visible = false

    function reopen(pageToLoad) {
        if (pageToLoad == "settings")
        {
            if (stackView.depth == 1)
            {
                stackView.push(settingsPage)
            }
        }
        else
        {
            if (stackView.depth > 1)
            {
                stackView.pop()
            }
        }

        if (!mainWindow.visible) {
            mainWindow.visible = true
        }
        raise()
        requestActivate()
    }

    // Mouse area for handling back/forward navigation
    MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.BackButton | Qt.ForwardButton
        onClicked: (mouse) => {
            if (mouse.button === Qt.BackButton && stackView.depth > 1) {
                stackView.pop()
            } else if (mouse.button === Qt.ForwardButton) {
                console.log("Forward button pressed")
            }
        }
    }

    StackView {
        id: stackView
        anchors.fill: parent
        initialItem: mainPage
    }

    FontLoader {
        id: iconFont
        source: "qrc:/icons/assets/fonts/SF-Symbols-6.ttf"
    }

    Component {
        id: mainPage
        Item {
            id: mainPageRoot
            readonly property int pagePadding: width < 360 ? 12 : 20

            ScrollView {
                id: mainScrollView
                anchors.fill: parent
                contentWidth: availableWidth
                clip: true

                Item {
                    id: mainScrollContent
                    width: Math.max(0, mainScrollView.availableWidth)
                    implicitHeight: mainContent.implicitHeight + (mainPageRoot.pagePadding * 2)
                
                    Column {
                        id: mainContent
                        x: mainPageRoot.pagePadding
                        y: mainPageRoot.pagePadding
                        width: Math.max(0, parent.width - (mainPageRoot.pagePadding * 2))
                        spacing: 18

                    Frame {
                        width: parent.width
                        padding: 18

                        background: Rectangle {
                            radius: 14
                            color: mainWindow.cardBackgroundColor
                            border.color: mainWindow.cardBorderColor
                        }

                        Column {
                            width: parent.width
                            spacing: 10

                            Label {
                                width: parent.width
                                font.pixelSize: 24
                                font.bold: true
                                wrapMode: Text.WordWrap
                                text: airPodsTrayApp.deviceInfo.deviceName !== ""
                                      ? airPodsTrayApp.deviceInfo.deviceName
                                      : qsTr("LibrePods")
                            }

                            Rectangle {
                                width: statusLabel.implicitWidth + 20
                                height: 28
                                radius: 14
                                color: airPodsTrayApp.airpodsConnected ? "#30D158" : "#FF453A"
                                opacity: 0.9

                                Label {
                                    id: statusLabel
                                    anchors.centerIn: parent
                                    text: airPodsTrayApp.airpodsConnected ? qsTr("Connected") : qsTr("Disconnected")
                                    color: "white"
                                    font.pixelSize: 12
                                    font.weight: Font.Medium
                                }
                            }

                            Label {
                                visible: airPodsTrayApp.airpodsConnected
                                width: parent.width
                                wrapMode: Text.WordWrap
                                color: mainWindow.secondaryTextColor
                                text: airPodsTrayApp.airpodsCommandReady
                                      ? qsTr("Noise control ready. Current mode: ") + airPodsTrayApp.deviceInfo.noiseControlLabel
                                      : qsTr("Audio is still connected. Waiting for the AirPods control channel before sending commands.")
                            }

                            Label {
                                visible: !airPodsTrayApp.airpodsConnected
                                width: parent.width
                                wrapMode: Text.WordWrap
                                color: mainWindow.secondaryTextColor
                                text: qsTr("Reconnect your AirPods to see live battery, ear detection, and quick controls here.")
                            }
                        }
                    }

                    Frame {
                        visible: airPodsTrayApp.airpodsConnected
                        width: parent.width
                        padding: 18

                        background: Rectangle {
                            radius: 14
                            color: mainWindow.cardBackgroundColor
                            border.color: mainWindow.cardBorderColor
                        }

                        Column {
                            width: parent.width
                            spacing: 12

                            Label {
                                text: qsTr("Battery")
                                font.bold: true
                            }

                            Flow {
                                id: batteryFlow
                                width: parent.width
                                spacing: 8

                                PodColumn {
                                    visible: airPodsTrayApp.deviceInfo.battery.leftPodAvailable
                                    inEar: airPodsTrayApp.deviceInfo.leftPodInEar
                                    iconSource: "qrc:/icons/assets/" + airPodsTrayApp.deviceInfo.podIcon
                                    batteryLevel: airPodsTrayApp.deviceInfo.battery.leftPodLevel
                                    isCharging: airPodsTrayApp.deviceInfo.battery.leftPodCharging
                                    indicator: "L"
                                }

                                PodColumn {
                                    visible: airPodsTrayApp.deviceInfo.battery.rightPodAvailable
                                    inEar: airPodsTrayApp.deviceInfo.rightPodInEar
                                    iconSource: "qrc:/icons/assets/" + airPodsTrayApp.deviceInfo.podIcon
                                    batteryLevel: airPodsTrayApp.deviceInfo.battery.rightPodLevel
                                    isCharging: airPodsTrayApp.deviceInfo.battery.rightPodCharging
                                    indicator: "R"
                                }

                                PodColumn {
                                    visible: airPodsTrayApp.deviceInfo.battery.caseAvailable
                                    inEar: true
                                    iconSource: "qrc:/icons/assets/" + airPodsTrayApp.deviceInfo.caseIcon
                                    batteryLevel: airPodsTrayApp.deviceInfo.battery.caseLevel
                                    isCharging: airPodsTrayApp.deviceInfo.battery.caseCharging
                                }

                                PodColumn {
                                    visible: airPodsTrayApp.deviceInfo.battery.headsetAvailable
                                    inEar: true
                                    iconSource: "qrc:/icons/assets/" + airPodsTrayApp.deviceInfo.podIcon
                                    batteryLevel: airPodsTrayApp.deviceInfo.battery.headsetLevel
                                    isCharging: airPodsTrayApp.deviceInfo.battery.headsetCharging
                                }
                            }

                            Label {
                                width: parent.width
                                wrapMode: Text.WordWrap
                                color: mainWindow.secondaryTextColor
                                text: airPodsTrayApp.deviceInfo.batteryStatus
                            }
                        }
                    }

                    Frame {
                        visible: airPodsTrayApp.airpodsConnected
                        width: parent.width
                        padding: 18

                        background: Rectangle {
                            radius: 14
                            color: mainWindow.cardBackgroundColor
                            border.color: mainWindow.cardBorderColor
                        }

                        Column {
                            id: quickControlsColumn
                            width: parent.width
                            spacing: 14
                            readonly property bool compactMode: width < 360
                            readonly property bool controlsReady: airPodsTrayApp.airpodsCommandReady

                            function syncQuickControlsFromDeviceInfo() {
                                if (noiseControlSegment.currentIndex !== airPodsTrayApp.deviceInfo.noiseControlMode)
                                    noiseControlSegment.currentIndex = airPodsTrayApp.deviceInfo.noiseControlMode
                                if (noiseControlCombo.currentIndex !== airPodsTrayApp.deviceInfo.noiseControlMode)
                                    noiseControlCombo.currentIndex = airPodsTrayApp.deviceInfo.noiseControlMode
                                if (!adaptiveNoiseSlider.pressed && adaptiveNoiseSlider.value !== airPodsTrayApp.deviceInfo.adaptiveNoiseLevel)
                                    adaptiveNoiseSlider.value = airPodsTrayApp.deviceInfo.adaptiveNoiseLevel
                                if (conversationalAwarenessSwitch.checked !== airPodsTrayApp.deviceInfo.conversationalAwareness)
                                    conversationalAwarenessSwitch.checked = airPodsTrayApp.deviceInfo.conversationalAwareness
                                if (hearingAidSwitch.checked !== airPodsTrayApp.deviceInfo.hearingAidEnabled)
                                    hearingAidSwitch.checked = airPodsTrayApp.deviceInfo.hearingAidEnabled
                                if (oneBudAncSwitch.checked !== airPodsTrayApp.deviceInfo.oneBudANCMode)
                                    oneBudAncSwitch.checked = airPodsTrayApp.deviceInfo.oneBudANCMode
                                if (allowOffSwitch.checked !== airPodsTrayApp.deviceInfo.allowOffOption)
                                    allowOffSwitch.checked = airPodsTrayApp.deviceInfo.allowOffOption
                                if (adaptiveVolumeSwitch.checked !== airPodsTrayApp.deviceInfo.adaptiveVolumeEnabled)
                                    adaptiveVolumeSwitch.checked = airPodsTrayApp.deviceInfo.adaptiveVolumeEnabled
                                if (volSwipeSwitch.checked !== airPodsTrayApp.deviceInfo.volumeSwipeEnabled)
                                    volSwipeSwitch.checked = airPodsTrayApp.deviceInfo.volumeSwipeEnabled
                                if (!volSwipeIntervalSlider.pressed && volSwipeIntervalSlider.value !== airPodsTrayApp.deviceInfo.volumeSwipeInterval)
                                    volSwipeIntervalSlider.value = airPodsTrayApp.deviceInfo.volumeSwipeInterval
                                if (caseChargingSwitch.checked !== airPodsTrayApp.deviceInfo.caseChargingSoundsEnabled)
                                    caseChargingSwitch.checked = airPodsTrayApp.deviceInfo.caseChargingSoundsEnabled
                                stemLongPressSection.stemModes = airPodsTrayApp.deviceInfo.stemLongPressModes
                            }

                            Component.onCompleted: syncQuickControlsFromDeviceInfo()

                            Connections {
                                target: airPodsTrayApp.deviceInfo

                                function onNoiseControlModeChangedInt(mode) {
                                    if (noiseControlSegment.currentIndex !== mode)
                                        noiseControlSegment.currentIndex = mode
                                    if (noiseControlCombo.currentIndex !== mode)
                                        noiseControlCombo.currentIndex = mode
                                }

                                function onAdaptiveNoiseLevelChanged(level) {
                                    if (!adaptiveNoiseSlider.pressed && adaptiveNoiseSlider.value !== level)
                                        adaptiveNoiseSlider.value = level
                                }

                                function onConversationalAwarenessChanged(enabled) {
                                    if (conversationalAwarenessSwitch.checked !== enabled)
                                        conversationalAwarenessSwitch.checked = enabled
                                }

                                function onHearingAidEnabledChanged(enabled) {
                                    if (hearingAidSwitch.checked !== enabled)
                                        hearingAidSwitch.checked = enabled
                                }

                                function onOneBudANCModeChanged(enabled) {
                                    if (oneBudAncSwitch.checked !== enabled)
                                        oneBudAncSwitch.checked = enabled
                                }

                                function onAllowOffOptionChanged(enabled) {
                                    if (allowOffSwitch.checked !== enabled)
                                        allowOffSwitch.checked = enabled
                                }

                                function onAdaptiveVolumeEnabledChanged(enabled) {
                                    if (adaptiveVolumeSwitch.checked !== enabled)
                                        adaptiveVolumeSwitch.checked = enabled
                                }

                                function onVolumeSwipeEnabledChanged(enabled) {
                                    if (volSwipeSwitch.checked !== enabled)
                                        volSwipeSwitch.checked = enabled
                                }

                                function onVolumeSwipeIntervalChanged(interval) {
                                    if (!volSwipeIntervalSlider.pressed && volSwipeIntervalSlider.value !== interval)
                                        volSwipeIntervalSlider.value = interval
                                }

                                function onCaseChargingSoundsEnabledChanged(enabled) {
                                    if (caseChargingSwitch.checked !== enabled)
                                        caseChargingSwitch.checked = enabled
                                }
                            }

                            Label {
                                text: qsTr("Quick Controls")
                                font.bold: true
                            }

                            Label {
                                width: parent.width
                                wrapMode: Text.WordWrap
                                color: mainWindow.secondaryTextColor
                                text: airPodsTrayApp.airpodsCommandReady
                                      ? qsTr("Core controls are ready. Changes apply immediately to the connected AirPods.")
                                      : qsTr("Controls stay disabled until the AirPods command channel finishes initializing.")
                            }

                            SegmentedControl {
                                id: noiseControlSegment
                                visible: !quickControlsColumn.compactMode
                                width: parent.width
                                model: [qsTr("Off"), qsTr("Noise Cancellation"), qsTr("Transparency"), qsTr("Adaptive")]
                                currentIndex: airPodsTrayApp.deviceInfo.noiseControlMode
                                enabled: quickControlsColumn.controlsReady
                                accentColor: mainWindow.controlAccentColor
                                disabledBackgroundColor: mainWindow.controlDisabledFillColor
                                disabledSelectedColor: mainWindow.controlDisabledAccentColor
                                disabledTextColor: mainWindow.controlDisabledTextColor
                                onActivated: index => airPodsTrayApp.setNoiseControlModeInt(index)
                            }

                            ComboBox {
                                id: noiseControlCombo
                                visible: quickControlsColumn.compactMode
                                width: parent.width
                                model: [qsTr("Off"), qsTr("Noise Cancellation"), qsTr("Transparency"), qsTr("Adaptive")]
                                currentIndex: airPodsTrayApp.deviceInfo.noiseControlMode
                                enabled: quickControlsColumn.controlsReady
                                opacity: enabled ? 1 : 0.7
                                onActivated: airPodsTrayApp.setNoiseControlModeInt(currentIndex)
                            }

                            Column {
                                visible: airPodsTrayApp.deviceInfo.adaptiveModeActive
                                width: parent.width
                                spacing: 6

                                Label {
                                    width: parent.width
                                    wrapMode: Text.WordWrap
                                    text: qsTr("Adaptive Noise Level: ") + adaptiveNoiseSlider.value
                                    color: quickControlsColumn.controlsReady ? mainWindow.secondaryTextColor : mainWindow.controlDisabledTextColor
                                }

                                Slider {
                                    id: adaptiveNoiseSlider
                                    width: parent.width
                                    enabled: quickControlsColumn.controlsReady
                                    opacity: enabled ? 1 : 0.65
                                    from: 0
                                    to: 100
                                    stepSize: 1
                                    value: airPodsTrayApp.deviceInfo.adaptiveNoiseLevel

                                    Timer {
                                        id: debounceTimer
                                        interval: 500
                                        onTriggered: if (!parent.pressed) airPodsTrayApp.setAdaptiveNoiseLevel(parent.value)
                                    }

                                    onPressedChanged: if (!pressed) airPodsTrayApp.setAdaptiveNoiseLevel(value)
                                    onValueChanged: if (pressed) debounceTimer.restart()
                                }
                            }

                            Item {
                                width: parent.width
                                implicitHeight: Math.max(conversationalAwarenessLabel.implicitHeight, conversationalAwarenessSwitch.implicitHeight)

                                Label {
                                    id: conversationalAwarenessLabel
                                    width: Math.max(0, parent.width - conversationalAwarenessSwitch.implicitWidth - 12)
                                    anchors.left: parent.left
                                    anchors.verticalCenter: parent.verticalCenter
                                    wrapMode: Text.WordWrap
                                    text: qsTr("Conversational Awareness")
                                    color: conversationalAwarenessSwitch.enabled ? mainWindow.primaryTextColor : mainWindow.controlDisabledTextColor
                                }

                                Switch {
                                    id: conversationalAwarenessSwitch
                                    anchors.right: parent.right
                                    anchors.verticalCenter: parent.verticalCenter
                                    checked: airPodsTrayApp.deviceInfo.conversationalAwareness
                                    enabled: quickControlsColumn.controlsReady
                                    leftPadding: 0
                                    rightPadding: 0
                                    implicitWidth: 46
                                    implicitHeight: 28
                                    contentItem: Item {}

                                    indicator: Rectangle {
                                        implicitWidth: 46
                                        implicitHeight: 28
                                        radius: height / 2
                                        color: conversationalAwarenessSwitch.enabled
                                               ? (conversationalAwarenessSwitch.checked ? mainWindow.controlAccentColor : mainWindow.controlInactiveColor)
                                               : (conversationalAwarenessSwitch.checked ? mainWindow.controlDisabledAccentColor : mainWindow.controlDisabledFillColor)
                                        border.color: conversationalAwarenessSwitch.enabled
                                                      ? (conversationalAwarenessSwitch.checked ? mainWindow.controlAccentColor : mainWindow.controlBorderMutedColor)
                                                      : mainWindow.controlBorderMutedColor

                                        Rectangle {
                                            width: 22
                                            height: 22
                                            radius: 11
                                            x: conversationalAwarenessSwitch.checked ? parent.width - width - 3 : 3
                                            y: 3
                                            color: "#ffffff"
                                            border.color: "#d4dbe3"

                                            Behavior on x {
                                                NumberAnimation {
                                                    duration: 120
                                                    easing.type: Easing.OutCubic
                                                }
                                            }
                                        }
                                    }

                                    onClicked: airPodsTrayApp.setConversationalAwareness(checked)
                                }
                            }

                            Item {
                                width: parent.width
                                implicitHeight: Math.max(hearingAidLabel.implicitHeight, hearingAidSwitch.implicitHeight)

                                Label {
                                    id: hearingAidLabel
                                    width: Math.max(0, parent.width - hearingAidSwitch.implicitWidth - 12)
                                    anchors.left: parent.left
                                    anchors.verticalCenter: parent.verticalCenter
                                    wrapMode: Text.WordWrap
                                    text: qsTr("Hearing Aid")
                                    color: hearingAidSwitch.enabled ? mainWindow.primaryTextColor : mainWindow.controlDisabledTextColor
                                }

                                Switch {
                                    id: hearingAidSwitch
                                    anchors.right: parent.right
                                    anchors.verticalCenter: parent.verticalCenter
                                    checked: airPodsTrayApp.deviceInfo.hearingAidEnabled
                                    enabled: quickControlsColumn.controlsReady
                                    leftPadding: 0
                                    rightPadding: 0
                                    implicitWidth: 46
                                    implicitHeight: 28
                                    contentItem: Item {}

                                    indicator: Rectangle {
                                        implicitWidth: 46
                                        implicitHeight: 28
                                        radius: height / 2
                                        color: hearingAidSwitch.enabled
                                               ? (hearingAidSwitch.checked ? mainWindow.controlAccentColor : mainWindow.controlInactiveColor)
                                               : (hearingAidSwitch.checked ? mainWindow.controlDisabledAccentColor : mainWindow.controlDisabledFillColor)
                                        border.color: hearingAidSwitch.enabled
                                                      ? (hearingAidSwitch.checked ? mainWindow.controlAccentColor : mainWindow.controlBorderMutedColor)
                                                      : mainWindow.controlBorderMutedColor

                                        Rectangle {
                                            width: 22
                                            height: 22
                                            radius: 11
                                            x: hearingAidSwitch.checked ? parent.width - width - 3 : 3
                                            y: 3
                                            color: "#ffffff"
                                            border.color: "#d4dbe3"

                                            Behavior on x {
                                                NumberAnimation {
                                                    duration: 120
                                                    easing.type: Easing.OutCubic
                                                }
                                            }
                                        }
                                    }

                                    onClicked: airPodsTrayApp.setHearingAidEnabled(checked)
                                }
                            }

                            Item {
                                width: parent.width
                                implicitHeight: Math.max(oneBudAncLabel.implicitHeight, oneBudAncSwitch.implicitHeight)

                                Label {
                                    id: oneBudAncLabel
                                    width: Math.max(0, parent.width - oneBudAncSwitch.implicitWidth - 12)
                                    anchors.left: parent.left
                                    anchors.verticalCenter: parent.verticalCenter
                                    wrapMode: Text.WordWrap
                                    text: qsTr("One Bud ANC Mode")
                                    color: oneBudAncSwitch.enabled ? mainWindow.primaryTextColor : mainWindow.controlDisabledTextColor
                                }

                                Switch {
                                    id: oneBudAncSwitch
                                    anchors.right: parent.right
                                    anchors.verticalCenter: parent.verticalCenter
                                    checked: airPodsTrayApp.deviceInfo.oneBudANCMode
                                    enabled: quickControlsColumn.controlsReady
                                    leftPadding: 0
                                    rightPadding: 0
                                    implicitWidth: 46
                                    implicitHeight: 28
                                    contentItem: Item {}

                                    indicator: Rectangle {
                                        implicitWidth: 46
                                        implicitHeight: 28
                                        radius: height / 2
                                        color: oneBudAncSwitch.enabled
                                               ? (oneBudAncSwitch.checked ? mainWindow.controlAccentColor : mainWindow.controlInactiveColor)
                                               : (oneBudAncSwitch.checked ? mainWindow.controlDisabledAccentColor : mainWindow.controlDisabledFillColor)
                                        border.color: oneBudAncSwitch.enabled
                                                      ? (oneBudAncSwitch.checked ? mainWindow.controlAccentColor : mainWindow.controlBorderMutedColor)
                                                      : mainWindow.controlBorderMutedColor

                                        Rectangle {
                                            width: 22
                                            height: 22
                                            radius: 11
                                            x: oneBudAncSwitch.checked ? parent.width - width - 3 : 3
                                            y: 3
                                            color: "#ffffff"
                                            border.color: "#d4dbe3"

                                            Behavior on x {
                                                NumberAnimation {
                                                    duration: 120
                                                    easing.type: Easing.OutCubic
                                                }
                                            }
                                        }
                                    }

                                    onClicked: airPodsTrayApp.setOneBudANCMode(checked)

                                    ToolTip {
                                        visible: parent.hovered
                                        text: qsTr("Enable ANC when using one AirPod\n(More noise reduction, but uses more battery)")
                                        delay: 500
                                    }
                                }
                            }

                            Item {
                                width: parent.width
                                implicitHeight: Math.max(allowOffLabel.implicitHeight, allowOffSwitch.implicitHeight)

                                Label {
                                    id: allowOffLabel
                                    width: Math.max(0, parent.width - allowOffSwitch.implicitWidth - 12)
                                    anchors.left: parent.left
                                    anchors.verticalCenter: parent.verticalCenter
                                    wrapMode: Text.WordWrap
                                    text: qsTr("Allow Off in Noise Control Cycle")
                                    color: allowOffSwitch.enabled ? mainWindow.primaryTextColor : mainWindow.controlDisabledTextColor
                                }

                                Switch {
                                    id: allowOffSwitch
                                    anchors.right: parent.right
                                    anchors.verticalCenter: parent.verticalCenter
                                    checked: airPodsTrayApp.deviceInfo.allowOffOption
                                    enabled: quickControlsColumn.controlsReady
                                    leftPadding: 0; rightPadding: 0
                                    implicitWidth: 46; implicitHeight: 28
                                    contentItem: Item {}
                                    indicator: Rectangle {
                                        implicitWidth: 46; implicitHeight: 28; radius: height / 2
                                        color: allowOffSwitch.enabled ? (allowOffSwitch.checked ? mainWindow.controlAccentColor : mainWindow.controlInactiveColor) : (allowOffSwitch.checked ? mainWindow.controlDisabledAccentColor : mainWindow.controlDisabledFillColor)
                                        border.color: allowOffSwitch.enabled ? (allowOffSwitch.checked ? mainWindow.controlAccentColor : mainWindow.controlBorderMutedColor) : mainWindow.controlBorderMutedColor
                                        Rectangle {
                                            width: 22; height: 22; radius: 11; y: 3; color: "#ffffff"; border.color: "#d4dbe3"
                                            x: allowOffSwitch.checked ? parent.width - width - 3 : 3
                                            Behavior on x { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
                                        }
                                    }
                                    onClicked: airPodsTrayApp.setAllowOffOption(checked)
                                }
                            }

                            Item {
                                width: parent.width
                                implicitHeight: Math.max(adaptiveVolumeLabel.implicitHeight, adaptiveVolumeSwitch.implicitHeight)

                                Label {
                                    id: adaptiveVolumeLabel
                                    width: Math.max(0, parent.width - adaptiveVolumeSwitch.implicitWidth - 12)
                                    anchors.left: parent.left
                                    anchors.verticalCenter: parent.verticalCenter
                                    wrapMode: Text.WordWrap
                                    text: qsTr("Adaptive Volume")
                                    color: adaptiveVolumeSwitch.enabled ? mainWindow.primaryTextColor : mainWindow.controlDisabledTextColor
                                }

                                Switch {
                                    id: adaptiveVolumeSwitch
                                    anchors.right: parent.right
                                    anchors.verticalCenter: parent.verticalCenter
                                    checked: airPodsTrayApp.deviceInfo.adaptiveVolumeEnabled
                                    enabled: quickControlsColumn.controlsReady
                                    leftPadding: 0; rightPadding: 0
                                    implicitWidth: 46; implicitHeight: 28
                                    contentItem: Item {}
                                    indicator: Rectangle {
                                        implicitWidth: 46; implicitHeight: 28; radius: height / 2
                                        color: adaptiveVolumeSwitch.enabled ? (adaptiveVolumeSwitch.checked ? mainWindow.controlAccentColor : mainWindow.controlInactiveColor) : (adaptiveVolumeSwitch.checked ? mainWindow.controlDisabledAccentColor : mainWindow.controlDisabledFillColor)
                                        border.color: adaptiveVolumeSwitch.enabled ? (adaptiveVolumeSwitch.checked ? mainWindow.controlAccentColor : mainWindow.controlBorderMutedColor) : mainWindow.controlBorderMutedColor
                                        Rectangle {
                                            width: 22; height: 22; radius: 11; y: 3; color: "#ffffff"; border.color: "#d4dbe3"
                                            x: adaptiveVolumeSwitch.checked ? parent.width - width - 3 : 3
                                            Behavior on x { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
                                        }
                                    }
                                    onClicked: airPodsTrayApp.setAdaptiveVolumeEnabled(checked)
                                }
                            }

                            Column {
                                width: parent.width
                                spacing: 6

                                Item {
                                    width: parent.width
                                    implicitHeight: Math.max(volSwipeLabel.implicitHeight, volSwipeSwitch.implicitHeight)

                                    Label {
                                        id: volSwipeLabel
                                        width: Math.max(0, parent.width - volSwipeSwitch.implicitWidth - 12)
                                        anchors.left: parent.left
                                        anchors.verticalCenter: parent.verticalCenter
                                        wrapMode: Text.WordWrap
                                        text: qsTr("Volume Swipe")
                                        color: volSwipeSwitch.enabled ? mainWindow.primaryTextColor : mainWindow.controlDisabledTextColor
                                    }

                                    Switch {
                                        id: volSwipeSwitch
                                        anchors.right: parent.right
                                        anchors.verticalCenter: parent.verticalCenter
                                        checked: airPodsTrayApp.deviceInfo.volumeSwipeEnabled
                                        enabled: quickControlsColumn.controlsReady
                                        leftPadding: 0; rightPadding: 0
                                        implicitWidth: 46; implicitHeight: 28
                                        contentItem: Item {}
                                        indicator: Rectangle {
                                            implicitWidth: 46; implicitHeight: 28; radius: height / 2
                                            color: volSwipeSwitch.enabled ? (volSwipeSwitch.checked ? mainWindow.controlAccentColor : mainWindow.controlInactiveColor) : (volSwipeSwitch.checked ? mainWindow.controlDisabledAccentColor : mainWindow.controlDisabledFillColor)
                                            border.color: volSwipeSwitch.enabled ? (volSwipeSwitch.checked ? mainWindow.controlAccentColor : mainWindow.controlBorderMutedColor) : mainWindow.controlBorderMutedColor
                                            Rectangle {
                                                width: 22; height: 22; radius: 11; y: 3; color: "#ffffff"; border.color: "#d4dbe3"
                                                x: volSwipeSwitch.checked ? parent.width - width - 3 : 3
                                                Behavior on x { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
                                            }
                                        }
                                        onClicked: airPodsTrayApp.setVolumeSwipeEnabled(checked)
                                    }
                                }

                                Column {
                                    visible: volSwipeSwitch.checked
                                    width: parent.width
                                    spacing: 4

                                    Label {
                                        text: qsTr("Swipe Sensitivity: ") + volSwipeIntervalSlider.value
                                        color: quickControlsColumn.controlsReady ? mainWindow.secondaryTextColor : mainWindow.controlDisabledTextColor
                                    }

                                    Slider {
                                        id: volSwipeIntervalSlider
                                        width: parent.width
                                        enabled: quickControlsColumn.controlsReady
                                        opacity: enabled ? 1 : 0.65
                                        from: 0; to: 100; stepSize: 1
                                        value: airPodsTrayApp.deviceInfo.volumeSwipeInterval

                                        Timer {
                                            id: volSwipeDebounce
                                            interval: 500
                                            onTriggered: if (!parent.pressed) airPodsTrayApp.setVolumeSwipeInterval(parent.value)
                                        }

                                        onPressedChanged: if (!pressed) airPodsTrayApp.setVolumeSwipeInterval(value)
                                        onValueChanged: if (pressed) volSwipeDebounce.restart()
                                    }
                                }
                            }

                            Item {
                                width: parent.width
                                implicitHeight: Math.max(caseChargingLabel.implicitHeight, caseChargingSwitch.implicitHeight)

                                Label {
                                    id: caseChargingLabel
                                    width: Math.max(0, parent.width - caseChargingSwitch.implicitWidth - 12)
                                    anchors.left: parent.left
                                    anchors.verticalCenter: parent.verticalCenter
                                    wrapMode: Text.WordWrap
                                    text: qsTr("Case Charging Sounds")
                                    color: caseChargingSwitch.enabled ? mainWindow.primaryTextColor : mainWindow.controlDisabledTextColor
                                }

                                Switch {
                                    id: caseChargingSwitch
                                    anchors.right: parent.right
                                    anchors.verticalCenter: parent.verticalCenter
                                    checked: airPodsTrayApp.deviceInfo.caseChargingSoundsEnabled
                                    enabled: quickControlsColumn.controlsReady
                                    leftPadding: 0; rightPadding: 0
                                    implicitWidth: 46; implicitHeight: 28
                                    contentItem: Item {}
                                    indicator: Rectangle {
                                        implicitWidth: 46; implicitHeight: 28; radius: height / 2
                                        color: caseChargingSwitch.enabled ? (caseChargingSwitch.checked ? mainWindow.controlAccentColor : mainWindow.controlInactiveColor) : (caseChargingSwitch.checked ? mainWindow.controlDisabledAccentColor : mainWindow.controlDisabledFillColor)
                                        border.color: caseChargingSwitch.enabled ? (caseChargingSwitch.checked ? mainWindow.controlAccentColor : mainWindow.controlBorderMutedColor) : mainWindow.controlBorderMutedColor
                                        Rectangle {
                                            width: 22; height: 22; radius: 11; y: 3; color: "#ffffff"; border.color: "#d4dbe3"
                                            x: caseChargingSwitch.checked ? parent.width - width - 3 : 3
                                            Behavior on x { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
                                        }
                                    }
                                    onClicked: airPodsTrayApp.setCaseChargingSoundsEnabled(checked)
                                }
                            }

                            Column {
                                id: stemLongPressSection
                                width: parent.width
                                spacing: 4
                                property int stemModes: airPodsTrayApp.deviceInfo.stemLongPressModes

                                Connections {
                                    target: airPodsTrayApp.deviceInfo
                                    function onStemLongPressModesChanged(modes) {
                                        stemLongPressSection.stemModes = modes
                                    }
                                }

                                function toggleBit(bit, on) {
                                    var m = on ? (stemModes | bit) : (stemModes & ~bit)
                                    var count = 0; var tmp = m
                                    while (tmp) { count += (tmp & 1); tmp = tmp >>> 1 }
                                    if (count >= 2) airPodsTrayApp.setStemLongPressModes(m)
                                }

                                Label {
                                    width: parent.width
                                    wrapMode: Text.WordWrap
                                    text: qsTr("Press-and-Hold Stem Cycles Through:")
                                    color: quickControlsColumn.controlsReady ? mainWindow.primaryTextColor : mainWindow.controlDisabledTextColor
                                }

                                Flow {
                                    width: parent.width
                                    spacing: 4
                                    opacity: quickControlsColumn.controlsReady ? 1.0 : 0.65

                                    CheckBox {
                                        text: qsTr("Off")
                                        checked: stemLongPressSection.stemModes & 0x01
                                        enabled: quickControlsColumn.controlsReady
                                        onClicked: stemLongPressSection.toggleBit(0x01, checked)
                                    }
                                    CheckBox {
                                        text: qsTr("Noise Cancellation")
                                        checked: stemLongPressSection.stemModes & 0x02
                                        enabled: quickControlsColumn.controlsReady
                                        onClicked: stemLongPressSection.toggleBit(0x02, checked)
                                    }
                                    CheckBox {
                                        text: qsTr("Transparency")
                                        checked: stemLongPressSection.stemModes & 0x04
                                        enabled: quickControlsColumn.controlsReady
                                        onClicked: stemLongPressSection.toggleBit(0x04, checked)
                                    }
                                    CheckBox {
                                        text: qsTr("Adaptive")
                                        checked: stemLongPressSection.stemModes & 0x08
                                        enabled: quickControlsColumn.controlsReady
                                        onClicked: stemLongPressSection.toggleBit(0x08, checked)
                                    }
                                }

                                Label {
                                    color: mainWindow.secondaryTextColor
                                    font.pixelSize: 11
                                    text: qsTr("Select at least 2 modes")
                                }
                            }

                            Label {
                                visible: quickControlsColumn.compactMode
                                width: parent.width
                                wrapMode: Text.WordWrap
                                color: mainWindow.secondaryTextColor
                                text: qsTr("Tip: if the segmented noise control feels cramped on a narrow window, LibrePods switches to a dropdown automatically.")
                            }
                        }
                    }

                    Frame {
                        visible: airPodsTrayApp.airpodsConnected
                        width: parent.width
                        padding: 18

                        background: Rectangle {
                            radius: 14
                            color: mainWindow.cardBackgroundColor
                            border.color: mainWindow.cardBorderColor
                        }

                        Column {
                            width: parent.width
                            spacing: 12

                            Label {
                                text: qsTr("Live Status")
                                font.bold: true
                            }

                            Label {
                                width: parent.width
                                wrapMode: Text.WordWrap
                                color: mainWindow.secondaryTextColor
                                text: qsTr("Current device signals and setup state update here while the AirPods are connected.")
                            }

                            Rectangle {
                                id: earDetectionStatusCard
                                width: parent.width
                                height: earDetectionStatusContent.implicitHeight + 24
                                radius: 10
                                color: mainWindow.statusRowBackgroundColor

                                Column {
                                    id: earDetectionStatusContent
                                    anchors.fill: parent
                                    anchors.margins: 12
                                    spacing: 4

                                    Label {
                                        text: qsTr("Ear Detection")
                                        font.bold: true
                                    }

                                    Label {
                                        width: parent.width
                                        wrapMode: Text.WordWrap
                                        color: mainWindow.secondaryTextColor
                                        text: airPodsTrayApp.deviceInfo.earStatusSummary
                                    }
                                }
                            }

                            Rectangle {
                                id: hearingAidStatusCard
                                width: parent.width
                                height: hearingAidStatusContent.implicitHeight + 24
                                radius: 10
                                color: mainWindow.statusRowBackgroundColor

                                Column {
                                    id: hearingAidStatusContent
                                    anchors.fill: parent
                                    anchors.margins: 12
                                    spacing: 4

                                    Label {
                                        text: qsTr("Advanced Hearing Aid Setup")
                                        font.bold: true
                                    }

                                    Label {
                                        width: parent.width
                                        wrapMode: Text.WordWrap
                                        color: mainWindow.secondaryTextColor
                                        text: airPodsTrayApp.hearingAidSetupStatus
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }

            RoundButton {
                anchors.top: parent.top
                anchors.right: parent.right
                anchors.margins: 10
                font.family: iconFont.name
                font.pixelSize: 18
                text: "\uf958"
                onClicked: stackView.push(settingsPage)
            }
        }
    }

        Component {
            id: settingsPage
            Page {
                id: settingsPageItem
                title: qsTr("Settings")

                ScrollView {
                    id: settingsScrollView
                    anchors.fill: parent
                    contentWidth: availableWidth
                    clip: true

                    Item {
                        width: Math.max(0, settingsScrollView.availableWidth)
                        implicitHeight: settingsContent.implicitHeight + 40

                        Column {
                            id: settingsContent
                            x: 20
                            y: 20
                            width: Math.max(0, parent.width - 40)
                            spacing: 20

                    Label {
                        text: qsTr("Settings")
                        font.pixelSize: 24
                        // center the label
                        anchors.horizontalCenter: parent.horizontalCenter
                    }

                    Column {
                        spacing: 5 // Small gap between label and ComboBox

                        Label {
                            text: qsTr("Pause Behavior When Removing AirPods:")
                        }

                        ComboBox {
                            width: parent.width // Ensures full width
                            model: [qsTr("One Removed"), qsTr("Both Removed"), qsTr("Never")]
                            currentIndex: airPodsTrayApp.earDetectionBehavior
                            onActivated: airPodsTrayApp.earDetectionBehavior = currentIndex
                        }

                        Label {
                            width: parent.width
                            wrapMode: Text.WordWrap
                            color: "#666666"
                            text: qsTr("Current ear detection: ") + airPodsTrayApp.deviceInfo.earStatusSummary
                        }
                    }

                    Frame {
                        width: settingsContent.width

                        Column {
                            width: settingsContent.width
                            spacing: 8

                            Label {
                                width: settingsContent.width
                                wrapMode: Text.WordWrap
                                font.bold: true
                                text: qsTr("Cross-Device / Handoff")
                            }

                            Label {
                                width: settingsContent.width
                                wrapMode: Text.WordWrap
                                text: qsTr("Linux can relay packets to the Android app over Bluetooth, request handoff when local playback starts, and export Magic Cloud Keys as a QR for another device.")
                            }

                            Switch {
                                text: qsTr("Cross-Device Connectivity with Android")
                                checked: airPodsTrayApp.crossDeviceEnabled
                                onClicked: airPodsTrayApp.setCrossDeviceEnabled(checked)
                            }

                            Row {
                                width: settingsContent.width
                                spacing: 10

                                TextField {
                                    id: newPhoneMacField
                                    width: Math.max(0, settingsContent.width - 220)
                                    placeholderText: (PHONE_MAC_ADDRESS !== "" ? PHONE_MAC_ADDRESS : "00:00:00:00:00:00")
                                    maximumLength: 32
                                }

                                Button {
                                    text: qsTr("Save Phone MAC")
                                    onClicked: airPodsTrayApp.setPhoneMac(newPhoneMacField.text)
                                }

                                Button {
                                    text: qsTr("Clear")
                                    onClicked: {
                                        newPhoneMacField.text = ""
                                        airPodsTrayApp.setPhoneMac("")
                                    }
                                }
                            }

                            Label {
                                width: settingsContent.width
                                wrapMode: Text.WordWrap
                                color: "#666666"
                                text: airPodsTrayApp.phoneMacStatus
                            }

                            Label {
                                width: settingsContent.width
                                wrapMode: Text.WordWrap
                                color: "#666666"
                                text: airPodsTrayApp.phoneConnected
                                      ? qsTr("Phone relay status: connected")
                                      : qsTr("Phone relay status: disconnected")
                            }

                            Row {
                                spacing: 10

                                Button {
                                    text: qsTr("Reconnect Phone Relay")
                                    enabled: airPodsTrayApp.crossDeviceEnabled
                                    onClicked: airPodsTrayApp.reconnectPhoneRelay()
                                }

                                Button {
                                    text: qsTr("Fetch Magic Cloud Keys")
                                    enabled: airPodsTrayApp.airpodsCommandReady
                                    onClicked: airPodsTrayApp.initiateMagicPairing()
                                }
                            }

                            Label {
                                width: settingsContent.width
                                wrapMode: Text.WordWrap
                                color: "#666666"
                                text: airPodsTrayApp.deviceInfo.hasMagicCloudKeys
                                      ? qsTr("Magic Cloud Keys: ready to export as QR")
                                      : qsTr("Magic Cloud Keys: not fetched yet. Connect AirPods and use 'Fetch Magic Cloud Keys'.")
                            }

                            Button {
                                text: qsTr("Show Magic Cloud Keys QR")
                                enabled: airPodsTrayApp.deviceInfo.hasMagicCloudKeys
                                onClicked: keysQrDialog.show()
                            }
                        }
                    }

                    Switch {
                        text: qsTr("Auto-Start on Login")
                        checked: airPodsTrayApp.autoStartManager.autoStartEnabled
                        onClicked: airPodsTrayApp.autoStartManager.autoStartEnabled = checked
                    }

                    Switch {
                        text: qsTr("Enable System Notifications")
                        checked: airPodsTrayApp.notificationsEnabled
                        onClicked: airPodsTrayApp.notificationsEnabled = checked
                    }

                    Row {
                        spacing: 5
                        Label {
                            text: qsTr("Bluetooth Retry Attempts:")
                            anchors.verticalCenter: parent.verticalCenter
                        }
                        SpinBox {
                            from: 1
                            to: 10
                            value: airPodsTrayApp.retryAttempts
                            onValueChanged: airPodsTrayApp.retryAttempts = value
                        }
                    }

                    Row {
                        spacing: 10
                        visible: airPodsTrayApp.airpodsConnected

                        TextField {
                            id: newNameField
                            placeholderText: airPodsTrayApp.deviceInfo.deviceName
                            maximumLength: 32
                        }

                        Button {
                            text: qsTr("Rename")
                            enabled: airPodsTrayApp.airpodsCommandReady && newNameField.text.length > 0
                            onClicked: airPodsTrayApp.renameAirPods(newNameField.text)
                        }
                    }

                    Frame {
                        width: settingsContent.width
                        visible: airPodsTrayApp.airpodsConnected

                        Column {
                            width: settingsContent.width
                            spacing: 8

                            Label {
                                width: settingsContent.width
                                wrapMode: Text.WordWrap
                                font.bold: true
                                text: qsTr("Customize Transparency Mode")
                            }

                            Label {
                                width: settingsContent.width
                                wrapMode: Text.WordWrap
                                color: "#666666"
                                text: qsTr("Adjust how transparency mode sounds. Press Apply to send settings to the AirPods.")
                            }

                            Switch {
                                id: customTranspEnabledSwitch
                                text: qsTr("Enabled")
                                checked: airPodsTrayApp.deviceInfo.customizeTransparencyEnabled
                            }

                            Label { font.bold: true; text: qsTr("Left Bud") }

                            Item {
                                width: parent.width; height: 28
                                Label { id: lAmpLbl; anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Amplification:"); width: 110 }
                                Label { id: lAmpVal; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; text: (leftAmpSlider.value / 50.0).toFixed(2); width: 36; horizontalAlignment: Text.AlignRight }
                                Slider { id: leftAmpSlider; from: 0; to: 100; stepSize: 1; value: 50; anchors.left: lAmpLbl.right; anchors.right: lAmpVal.left; anchors.verticalCenter: parent.verticalCenter; anchors.leftMargin: 8; anchors.rightMargin: 4 }
                            }

                            Item {
                                width: parent.width; height: 28
                                Label { id: lToneLbl; anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Tone:"); width: 110 }
                                Label { id: lToneVal; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; text: (leftToneSlider.value / 50.0).toFixed(2); width: 36; horizontalAlignment: Text.AlignRight }
                                Slider { id: leftToneSlider; from: 0; to: 100; stepSize: 1; value: 50; anchors.left: lToneLbl.right; anchors.right: lToneVal.left; anchors.verticalCenter: parent.verticalCenter; anchors.leftMargin: 8; anchors.rightMargin: 4 }
                            }

                            Item {
                                width: parent.width; height: 28
                                Label { anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Conversation Boost:"); width: 110 }
                                CheckBox { id: leftConvBoost; anchors.left: parent.left; anchors.leftMargin: 118; anchors.verticalCenter: parent.verticalCenter }
                            }

                            Item {
                                width: parent.width; height: 28
                                Label { id: lAnrLbl; anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Ambient Noise:"); width: 110 }
                                Label { id: lAnrVal; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; text: (leftAmbientSlider.value / 100.0).toFixed(2); width: 36; horizontalAlignment: Text.AlignRight }
                                Slider { id: leftAmbientSlider; from: 0; to: 100; stepSize: 1; value: 0; anchors.left: lAnrLbl.right; anchors.right: lAnrVal.left; anchors.verticalCenter: parent.verticalCenter; anchors.leftMargin: 8; anchors.rightMargin: 4 }
                            }

                            Label { font.bold: true; text: qsTr("Right Bud") }

                            Item {
                                width: parent.width; height: 28
                                Label { id: rAmpLbl; anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Amplification:"); width: 110 }
                                Label { id: rAmpVal; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; text: (rightAmpSlider.value / 50.0).toFixed(2); width: 36; horizontalAlignment: Text.AlignRight }
                                Slider { id: rightAmpSlider; from: 0; to: 100; stepSize: 1; value: 50; anchors.left: rAmpLbl.right; anchors.right: rAmpVal.left; anchors.verticalCenter: parent.verticalCenter; anchors.leftMargin: 8; anchors.rightMargin: 4 }
                            }

                            Item {
                                width: parent.width; height: 28
                                Label { id: rToneLbl; anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Tone:"); width: 110 }
                                Label { id: rToneVal; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; text: (rightToneSlider.value / 50.0).toFixed(2); width: 36; horizontalAlignment: Text.AlignRight }
                                Slider { id: rightToneSlider; from: 0; to: 100; stepSize: 1; value: 50; anchors.left: rToneLbl.right; anchors.right: rToneVal.left; anchors.verticalCenter: parent.verticalCenter; anchors.leftMargin: 8; anchors.rightMargin: 4 }
                            }

                            Item {
                                width: parent.width; height: 28
                                Label { anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Conversation Boost:"); width: 110 }
                                CheckBox { id: rightConvBoost; anchors.left: parent.left; anchors.leftMargin: 118; anchors.verticalCenter: parent.verticalCenter }
                            }

                            Item {
                                width: parent.width; height: 28
                                Label { id: rAnrLbl; anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Ambient Noise:"); width: 110 }
                                Label { id: rAnrVal; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; text: (rightAmbientSlider.value / 100.0).toFixed(2); width: 36; horizontalAlignment: Text.AlignRight }
                                Slider { id: rightAmbientSlider; from: 0; to: 100; stepSize: 1; value: 0; anchors.left: rAnrLbl.right; anchors.right: rAnrVal.left; anchors.verticalCenter: parent.verticalCenter; anchors.leftMargin: 8; anchors.rightMargin: 4 }
                            }

                            Button {
                                text: qsTr("Apply Transparency Settings")
                                enabled: airPodsTrayApp.airpodsCommandReady
                                onClicked: airPodsTrayApp.applyCustomizeTransparency(
                                    customTranspEnabledSwitch.checked,
                                    [], leftAmpSlider.value / 50.0, leftToneSlider.value / 50.0, leftConvBoost.checked, leftAmbientSlider.value / 100.0,
                                    [], rightAmpSlider.value / 50.0, rightToneSlider.value / 50.0, rightConvBoost.checked, rightAmbientSlider.value / 100.0
                                )
                            }
                        }
                    }

                    Frame {
                        width: settingsContent.width
                        visible: airPodsTrayApp.airpodsConnected

                        Column {
                            width: settingsContent.width
                            spacing: 8

                            Label {
                                width: settingsContent.width
                                wrapMode: Text.WordWrap
                                font.bold: true
                                text: qsTr("Headphone Accommodation")
                            }

                            Label {
                                width: settingsContent.width
                                wrapMode: Text.WordWrap
                                color: "#666666"
                                text: qsTr("Amplifies soft sounds and adjusts frequencies to suit your hearing. Press Apply to send.")
                            }

                            Switch {
                                id: hpAccomPhoneSwitch
                                text: qsTr("Enable for Phone Calls")
                                checked: airPodsTrayApp.deviceInfo.headphoneAccomPhoneEnabled
                            }

                            Switch {
                                id: hpAccomMediaSwitch
                                text: qsTr("Enable for Media")
                                checked: airPodsTrayApp.deviceInfo.headphoneAccomMediaEnabled
                            }

                            Label {
                                font.bold: true
                                text: qsTr("8-Band EQ")
                            }

                            Item { width: parent.width; height: 28
                                Label { id: eq0Lbl; anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Band 1:"); width: 60 }
                                Label { id: eq0Val; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; text: hpEq0.value; width: 28; horizontalAlignment: Text.AlignRight }
                                Slider { id: hpEq0; from: 0; to: 100; stepSize: 1; value: 0; anchors.left: eq0Lbl.right; anchors.right: eq0Val.left; anchors.verticalCenter: parent.verticalCenter; anchors.leftMargin: 8; anchors.rightMargin: 4 }
                            }
                            Item { width: parent.width; height: 28
                                Label { id: eq1Lbl; anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Band 2:"); width: 60 }
                                Label { id: eq1Val; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; text: hpEq1.value; width: 28; horizontalAlignment: Text.AlignRight }
                                Slider { id: hpEq1; from: 0; to: 100; stepSize: 1; value: 0; anchors.left: eq1Lbl.right; anchors.right: eq1Val.left; anchors.verticalCenter: parent.verticalCenter; anchors.leftMargin: 8; anchors.rightMargin: 4 }
                            }
                            Item { width: parent.width; height: 28
                                Label { id: eq2Lbl; anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Band 3:"); width: 60 }
                                Label { id: eq2Val; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; text: hpEq2.value; width: 28; horizontalAlignment: Text.AlignRight }
                                Slider { id: hpEq2; from: 0; to: 100; stepSize: 1; value: 0; anchors.left: eq2Lbl.right; anchors.right: eq2Val.left; anchors.verticalCenter: parent.verticalCenter; anchors.leftMargin: 8; anchors.rightMargin: 4 }
                            }
                            Item { width: parent.width; height: 28
                                Label { id: eq3Lbl; anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Band 4:"); width: 60 }
                                Label { id: eq3Val; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; text: hpEq3.value; width: 28; horizontalAlignment: Text.AlignRight }
                                Slider { id: hpEq3; from: 0; to: 100; stepSize: 1; value: 0; anchors.left: eq3Lbl.right; anchors.right: eq3Val.left; anchors.verticalCenter: parent.verticalCenter; anchors.leftMargin: 8; anchors.rightMargin: 4 }
                            }
                            Item { width: parent.width; height: 28
                                Label { id: eq4Lbl; anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Band 5:"); width: 60 }
                                Label { id: eq4Val; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; text: hpEq4.value; width: 28; horizontalAlignment: Text.AlignRight }
                                Slider { id: hpEq4; from: 0; to: 100; stepSize: 1; value: 0; anchors.left: eq4Lbl.right; anchors.right: eq4Val.left; anchors.verticalCenter: parent.verticalCenter; anchors.leftMargin: 8; anchors.rightMargin: 4 }
                            }
                            Item { width: parent.width; height: 28
                                Label { id: eq5Lbl; anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Band 6:"); width: 60 }
                                Label { id: eq5Val; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; text: hpEq5.value; width: 28; horizontalAlignment: Text.AlignRight }
                                Slider { id: hpEq5; from: 0; to: 100; stepSize: 1; value: 0; anchors.left: eq5Lbl.right; anchors.right: eq5Val.left; anchors.verticalCenter: parent.verticalCenter; anchors.leftMargin: 8; anchors.rightMargin: 4 }
                            }
                            Item { width: parent.width; height: 28
                                Label { id: eq6Lbl; anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Band 7:"); width: 60 }
                                Label { id: eq6Val; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; text: hpEq6.value; width: 28; horizontalAlignment: Text.AlignRight }
                                Slider { id: hpEq6; from: 0; to: 100; stepSize: 1; value: 0; anchors.left: eq6Lbl.right; anchors.right: eq6Val.left; anchors.verticalCenter: parent.verticalCenter; anchors.leftMargin: 8; anchors.rightMargin: 4 }
                            }
                            Item { width: parent.width; height: 28
                                Label { id: eq7Lbl; anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Band 8:"); width: 60 }
                                Label { id: eq7Val; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; text: hpEq7.value; width: 28; horizontalAlignment: Text.AlignRight }
                                Slider { id: hpEq7; from: 0; to: 100; stepSize: 1; value: 0; anchors.left: eq7Lbl.right; anchors.right: eq7Val.left; anchors.verticalCenter: parent.verticalCenter; anchors.leftMargin: 8; anchors.rightMargin: 4 }
                            }

                            Button {
                                text: qsTr("Apply Headphone Accommodation")
                                enabled: airPodsTrayApp.airpodsCommandReady
                                onClicked: airPodsTrayApp.applyHeadphoneAccommodation(
                                    hpAccomPhoneSwitch.checked, hpAccomMediaSwitch.checked,
                                    [hpEq0.value, hpEq1.value, hpEq2.value, hpEq3.value,
                                     hpEq4.value, hpEq5.value, hpEq6.value, hpEq7.value]
                                )
                            }
                        }
                    }

                    Frame {
                        width: settingsContent.width

                        Column {
                            width: settingsContent.width
                            spacing: 8

                            Label {
                                width: settingsContent.width
                                wrapMode: Text.WordWrap
                                font.bold: true
                                text: qsTr("Head Tracking / Gestures")
                            }

                            Label {
                                width: settingsContent.width
                                wrapMode: Text.WordWrap
                                text: qsTr("Linux now exposes the existing Python gesture detector so you can test nod and shake detection for the connected AirPods without touching Android.")
                            }

                            Label {
                                width: settingsContent.width
                                wrapMode: Text.WordWrap
                                text: qsTr("Current device: ") + (airPodsTrayApp.deviceInfo.bluetoothAddress !== "" ? airPodsTrayApp.deviceInfo.bluetoothAddress : qsTr("No Bluetooth address detected yet"))
                            }

                            Button {
                                text: qsTr("Open Head Gesture Detector")
                                enabled: airPodsTrayApp.deviceInfo.bluetoothAddress !== ""
                                onClicked: airPodsTrayApp.openHeadTrackingGestures()
                            }

                            Label {
                                width: settingsContent.width
                                wrapMode: Text.WordWrap
                                color: "#666666"
                                text: airPodsTrayApp.headTrackingStatus
                            }

                            Label {
                                width: settingsContent.width
                                wrapMode: Text.WordWrap
                                color: "#666666"
                                text: qsTr("Requirements: Python 3, a terminal emulator, and the Python bluetooth dependency used by the existing `head-tracking` scripts. This phase only launches the current detector; it does not add inline visualization, alternate packets, or multi-device handoff.")
                            }
                        }
                    }
                        KeysQRDialog {
                            id: keysQrDialog
                            encKey: airPodsTrayApp.deviceInfo.magicAccEncKey
                            irk: airPodsTrayApp.deviceInfo.magicAccIRK
                        }
                    }
                }
            }

            // Floating back button
            RoundButton {
                anchors.top: parent.top
                anchors.left: parent.left
                anchors.margins: 10
                font.family: iconFont.name
                font.pixelSize: 18
                text: "\uecb1" // U+ECB1
                onClicked: stackView.pop()
            }
        }
    }
}
