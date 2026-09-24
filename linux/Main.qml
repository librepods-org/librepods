// Main.qml
// Modified by LibrePods HiiT: controls rebuilt on the Ui* component kit, Departure Mono
// font, Lucide icons and a connection badge that does not rely on color alone.
pragma ComponentBehavior: Bound

import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

ApplicationWindow {
    id: mainWindow
    visible: !airPodsTrayApp.hideOnStart
    width: 440
    height: 480
    minimumWidth: 400
    minimumHeight: 380
    title: "LibrePods"
    objectName: "mainWindowObject"
    color: Theme.background
    font.family: Theme.fontFamily
    font.pixelSize: Theme.fontSize

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

    Component {
        id: mainPage
        ScrollView {
            id: mainScroll
            contentWidth: availableWidth

            ColumnLayout {
                x: 20
                y: 20
                width: mainScroll.availableWidth - 40
                spacing: 20

                // Header: device name, connection status and settings
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 12

                    Text {
                        Layout.fillWidth: true
                        text: airPodsTrayApp.airpodsConnected && airPodsTrayApp.deviceInfo.deviceName !== ""
                              ? airPodsTrayApp.deviceInfo.deviceName : "LibrePods"
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.fontSizeLarge
                        color: Theme.foreground
                        elide: Text.ElideRight
                    }

                    UiButton {
                        variant: "ghost"
                        iconName: "settings"
                        toolTipText: qsTr("Settings")
                        onClicked: stackView.push(settingsPage)
                    }
                }

                UiBadge {
                    text: airPodsTrayApp.airpodsConnected ? qsTr("Connected") : qsTr("Disconnected")
                    iconName: airPodsTrayApp.airpodsConnected ? "bluetooth-connected" : "bluetooth-off"
                    tone: airPodsTrayApp.airpodsConnected ? Theme.success : Theme.danger
                }

                // Battery Indicator Row
                Row {
                    Layout.alignment: Qt.AlignHCenter
                    spacing: 24

                    PodColumn {
                        visible: airPodsTrayApp.deviceInfo.battery.leftPodAvailable
                        inEar: airPodsTrayApp.deviceInfo.leftPodInEar
                        illustration: airPodsTrayApp.deviceInfo.podIcon
                        batteryLevel: airPodsTrayApp.deviceInfo.battery.leftPodLevel
                        isCharging: airPodsTrayApp.deviceInfo.battery.leftPodCharging
                        indicator: "L"
                    }

                    PodColumn {
                        visible: airPodsTrayApp.deviceInfo.battery.rightPodAvailable
                        inEar: airPodsTrayApp.deviceInfo.rightPodInEar
                        illustration: airPodsTrayApp.deviceInfo.podIcon
                        batteryLevel: airPodsTrayApp.deviceInfo.battery.rightPodLevel
                        isCharging: airPodsTrayApp.deviceInfo.battery.rightPodCharging
                        indicator: "R"
                    }

                    PodColumn {
                        visible: airPodsTrayApp.deviceInfo.battery.caseAvailable
                        inEar: true
                        illustration: airPodsTrayApp.deviceInfo.caseIcon
                        batteryLevel: airPodsTrayApp.deviceInfo.battery.caseLevel
                        isCharging: airPodsTrayApp.deviceInfo.battery.caseCharging
                    }

                    PodColumn {
                        visible: airPodsTrayApp.deviceInfo.battery.headsetAvailable
                        inEar: true
                        illustration: airPodsTrayApp.deviceInfo.podIcon
                        batteryLevel: airPodsTrayApp.deviceInfo.battery.headsetLevel
                        isCharging: airPodsTrayApp.deviceInfo.battery.headsetCharging
                    }
                }

                SegmentedControl {
                    Layout.fillWidth: true
                    model: [qsTr("Off"), qsTr("Noise Cancellation"), qsTr("Transparency"), qsTr("Adaptive")]
                    icons: ["circle-off", "headphone-off", "ear", "audio-lines"]
                    currentIndex: airPodsTrayApp.deviceInfo.noiseControlMode
                    onActivated: (index) => airPodsTrayApp.setNoiseControlModeInt(index)
                    visible: airPodsTrayApp.airpodsConnected
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    visible: airPodsTrayApp.deviceInfo.adaptiveModeActive
                    spacing: 8

                    Text {
                        text: qsTr("Adaptive Noise Level: ") + adaptiveSlider.value
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.fontSize
                        color: Theme.foreground
                    }

                    UiSlider {
                        id: adaptiveSlider
                        Layout.fillWidth: true
                        from: 0
                        to: 100
                        stepSize: 1
                        value: airPodsTrayApp.deviceInfo.adaptiveNoiseLevel

                        Timer {
                            id: debounceTimer
                            interval: 500
                            onTriggered: if (!adaptiveSlider.pressed) airPodsTrayApp.setAdaptiveNoiseLevel(adaptiveSlider.value)
                        }

                        onPressedChanged: if (!pressed) airPodsTrayApp.setAdaptiveNoiseLevel(value)
                        onValueChanged: if (pressed) debounceTimer.restart()
                    }
                }

                UiSwitch {
                    Layout.fillWidth: true
                    visible: airPodsTrayApp.airpodsConnected
                    iconName: "speech"
                    text: qsTr("Conversational Awareness")
                    checked: airPodsTrayApp.deviceInfo.conversationalAwareness
                    onToggled: airPodsTrayApp.setConversationalAwareness(checked)
                }

                UiSwitch {
                    Layout.fillWidth: true
                    visible: airPodsTrayApp.airpodsConnected
                    iconName: "ear"
                    text: qsTr("Hearing Aid")
                    checked: airPodsTrayApp.deviceInfo.hearingAidEnabled
                    onToggled: airPodsTrayApp.setHearingAidEnabled(checked)
                }

                Item {
                    implicitHeight: 20
                }
            }
        }
    }

    Component {
        id: settingsPage
        Page {
            id: settingsPageItem
            title: qsTr("Settings")
            background: Rectangle { color: Theme.background }

            Shortcut {
                sequences: [StandardKey.Back, "Esc"]
                onActivated: stackView.pop()
            }

            header: Rectangle {
                implicitHeight: 56
                color: Theme.background

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 12
                    anchors.rightMargin: 20
                    spacing: 8

                    UiButton {
                        variant: "ghost"
                        iconName: "arrow-left"
                        toolTipText: qsTr("Back")
                        onClicked: stackView.pop()
                    }

                    Text {
                        Layout.fillWidth: true
                        text: settingsPageItem.title
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.fontSizeLarge
                        color: Theme.foreground
                    }
                }

                Rectangle {
                    anchors.bottom: parent.bottom
                    width: parent.width
                    height: 1
                    color: Theme.border
                }
            }

            ScrollView {
                id: settingsScroll
                anchors.fill: parent
                contentWidth: availableWidth

                ColumnLayout {
                    width: settingsScroll.availableWidth
                    spacing: 20

                    Item { implicitHeight: 4 }

                    ColumnLayout {
                        Layout.fillWidth: true
                        Layout.leftMargin: 20
                        Layout.rightMargin: 20
                        spacing: 8 // Small gap between label and ComboBox

                        Text {
                            text: qsTr("Pause Behavior When Removing AirPods:")
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.fontSize
                            color: Theme.foreground
                        }

                        UiSelect {
                            Layout.fillWidth: true
                            model: [qsTr("One Removed"), qsTr("Both Removed"), qsTr("Never")]
                            currentIndex: airPodsTrayApp.earDetectionBehavior
                            onActivated: airPodsTrayApp.earDetectionBehavior = currentIndex
                        }
                    }

                    UiSwitch {
                        Layout.fillWidth: true
                        Layout.leftMargin: 20
                        Layout.rightMargin: 20
                        iconName: "smartphone"
                        text: qsTr("Cross-Device Connectivity with Android")
                        checked: airPodsTrayApp.crossDeviceEnabled
                        onToggled: airPodsTrayApp.setCrossDeviceEnabled(checked)
                    }

                    UiSwitch {
                        Layout.fillWidth: true
                        Layout.leftMargin: 20
                        Layout.rightMargin: 20
                        iconName: "power"
                        text: qsTr("Auto-Start on Login")
                        checked: airPodsTrayApp.autoStartManager.autoStartEnabled
                        onToggled: airPodsTrayApp.autoStartManager.autoStartEnabled = checked
                    }

                    UiSwitch {
                        Layout.fillWidth: true
                        Layout.leftMargin: 20
                        Layout.rightMargin: 20
                        iconName: "bell"
                        text: qsTr("Enable System Notifications")
                        checked: airPodsTrayApp.notificationsEnabled
                        onToggled: airPodsTrayApp.notificationsEnabled = checked
                    }

                    UiSwitch {
                        Layout.fillWidth: true
                        Layout.leftMargin: 20
                        Layout.rightMargin: 20
                        visible: airPodsTrayApp.airpodsConnected
                        iconName: "headphone-off"
                        text: qsTr("One Bud ANC Mode")
                        description: qsTr("Enable ANC when using one AirPod\n(More noise reduction, but uses more battery)")
                        checked: airPodsTrayApp.deviceInfo.oneBudANCMode
                        onToggled: airPodsTrayApp.deviceInfo.oneBudANCMode = checked
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        Layout.leftMargin: 20
                        Layout.rightMargin: 20
                        spacing: 12

                        Text {
                            Layout.fillWidth: true
                            text: qsTr("Bluetooth Retry Attempts:")
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.fontSize
                            color: Theme.foreground
                            wrapMode: Text.WordWrap
                        }

                        UiSpinBox {
                            from: 1
                            to: 10
                            value: airPodsTrayApp.retryAttempts
                            onValueModified: airPodsTrayApp.retryAttempts = value
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        Layout.leftMargin: 20
                        Layout.rightMargin: 20
                        spacing: 8
                        visible: airPodsTrayApp.airpodsConnected

                        UiTextField {
                            id: newNameField
                            Layout.fillWidth: true
                            placeholderText: airPodsTrayApp.deviceInfo.deviceName
                            maximumLength: 32
                        }

                        UiButton {
                            variant: "outline"
                            iconName: "pencil"
                            text: qsTr("Rename")
                            onClicked: airPodsTrayApp.renameAirPods(newNameField.text)
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        Layout.leftMargin: 20
                        Layout.rightMargin: 20
                        spacing: 8
                        visible: airPodsTrayApp.airpodsConnected

                        UiTextField {
                            id: newPhoneMacField
                            Layout.fillWidth: true
                            placeholderText: (PHONE_MAC_ADDRESS !== "" ? PHONE_MAC_ADDRESS : "00:00:00:00:00:00")
                            maximumLength: 32
                        }

                        UiButton {
                            variant: "outline"
                            iconName: "smartphone"
                            text: qsTr("Change Phone MAC")
                            onClicked: airPodsTrayApp.setPhoneMac(newPhoneMacField.text)
                        }
                    }

                    UiButton {
                        Layout.leftMargin: 20
                        variant: "outline"
                        iconName: "qr-code"
                        text: qsTr("Show Magic Cloud Keys QR")
                        onClicked: keysQrDialog.show()
                    }

                    KeysQRDialog {
                        id: keysQrDialog
                        encKey: airPodsTrayApp.deviceInfo.magicAccEncKey
                        irk: airPodsTrayApp.deviceInfo.magicAccIRK
                    }

                    Item { implicitHeight: 4 }
                }
            }
        }
    }
}
