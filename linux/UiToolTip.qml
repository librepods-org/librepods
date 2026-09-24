// UiToolTip.qml
// LibrePods HiiT: tooltip in the shadcn/ui style (inverted colors).
import QtQuick
import QtQuick.Controls.Basic

ToolTip {
    id: root

    delay: 500
    padding: 6
    leftPadding: 10
    rightPadding: 10

    contentItem: Text {
        text: root.text
        font.family: Theme.fontFamily
        font.pixelSize: Theme.fontSize
        color: Theme.primaryForeground
        wrapMode: Text.WordWrap
    }

    background: Rectangle {
        radius: Theme.radius
        color: Theme.primary
    }
}
