// UiBadge.qml
// LibrePods HiiT: small status label with icon; the color never carries the meaning alone.
import QtQuick

Rectangle {
    id: root

    property string text: ""
    property string iconName: ""
    property color tone: Theme.foreground

    implicitWidth: row.implicitWidth + 16
    implicitHeight: 24
    radius: height / 2
    color: "transparent"
    border.width: 1
    border.color: Theme.border

    Row {
        id: row
        anchors.centerIn: parent
        spacing: 6

        Icon {
            anchors.verticalCenter: parent.verticalCenter
            visible: root.iconName !== ""
            name: root.iconName
            color: root.tone
            size: 14
        }

        Text {
            anchors.verticalCenter: parent.verticalCenter
            text: root.text
            font.family: Theme.fontFamily
            font.pixelSize: Theme.fontSize
            color: root.tone
        }
    }
}
