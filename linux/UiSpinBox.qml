// UiSpinBox.qml
// LibrePods HiiT: number stepper with outline "-" and "+" buttons.
import QtQuick
import QtQuick.Controls.Basic

SpinBox {
    id: root

    implicitHeight: Theme.controlHeight
    implicitWidth: 112
    leftPadding: Theme.controlHeight
    rightPadding: Theme.controlHeight
    font.family: Theme.fontFamily
    font.pixelSize: Theme.fontSize
    editable: false

    contentItem: Text {
        text: root.displayText
        font: root.font
        color: Theme.foreground
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
    }

    down.indicator: Rectangle {
        x: 0
        width: Theme.controlHeight
        height: root.height
        radius: Theme.radius
        color: root.down.hovered ? Theme.accent : "transparent"
        opacity: root.value > root.from ? 1 : 0.4

        Text {
            anchors.centerIn: parent
            text: "-"
            font: root.font
            color: Theme.foreground
        }
    }

    up.indicator: Rectangle {
        x: root.width - width
        width: Theme.controlHeight
        height: root.height
        radius: Theme.radius
        color: root.up.hovered ? Theme.accent : "transparent"
        opacity: root.value < root.to ? 1 : 0.4

        Text {
            anchors.centerIn: parent
            text: "+"
            font: root.font
            color: Theme.foreground
        }
    }

    background: Rectangle {
        radius: Theme.radius
        color: "transparent"
        border.width: root.visualFocus ? 2 : 1
        border.color: root.visualFocus ? Theme.ring : Theme.input
    }
}
