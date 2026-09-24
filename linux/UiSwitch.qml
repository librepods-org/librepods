// UiSwitch.qml
// LibrePods HiiT: settings row with label, optional description and a shadcn/ui-style switch.
// React to `toggled` (user action only) instead of `checkedChanged`, so backend
// updates bound to `checked` are not sent back to the device.
import QtQuick
import QtQuick.Controls.Basic

Switch {
    id: root

    property string description: ""
    property string iconName: ""

    implicitWidth: 320
    padding: 0
    spacing: 12
    font.family: Theme.fontFamily
    font.pixelSize: Theme.fontSize
    opacity: enabled ? 1 : 0.5
    Accessible.description: description

    indicator: Rectangle {
        x: root.width - width
        y: (root.height - height) / 2
        implicitWidth: 36
        implicitHeight: 20
        radius: height / 2
        color: root.checked ? Theme.primary : Theme.input

        Rectangle {
            x: root.checked ? parent.width - width - 2 : 2
            y: 2
            width: 16
            height: 16
            radius: 8
            color: Theme.background

            Behavior on x {
                NumberAnimation { duration: Theme.animationDuration; easing.type: Easing.OutCubic }
            }
        }

        // Focus ring
        Rectangle {
            anchors.fill: parent
            anchors.margins: -3
            radius: height / 2
            color: "transparent"
            border.width: 2
            border.color: Theme.ring
            visible: root.visualFocus
        }

        Behavior on color {
            ColorAnimation { duration: Theme.animationDuration }
        }
    }

    contentItem: Row {
        rightPadding: root.indicator.width + root.spacing
        spacing: 10

        Icon {
            visible: root.iconName !== ""
            name: root.iconName
            color: Theme.foreground
            anchors.verticalCenter: parent.verticalCenter
        }

        Column {
            width: parent.width - parent.rightPadding - (root.iconName !== "" ? Theme.iconSize + parent.spacing : 0)
            anchors.verticalCenter: parent.verticalCenter
            spacing: 4

            Text {
                width: parent.width
                text: root.text
                font: root.font
                color: Theme.foreground
                wrapMode: Text.WordWrap
            }

            Text {
                width: parent.width
                visible: root.description !== ""
                text: root.description
                font: root.font
                color: Theme.mutedForeground
                wrapMode: Text.WordWrap
            }
        }
    }
}
