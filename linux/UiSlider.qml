// UiSlider.qml
// LibrePods HiiT: slider in the shadcn/ui style.
import QtQuick
import QtQuick.Controls.Basic

Slider {
    id: root

    implicitWidth: 240
    implicitHeight: 20
    opacity: enabled ? 1 : 0.5

    background: Rectangle {
        x: root.leftPadding
        y: root.topPadding + (root.availableHeight - height) / 2
        width: root.availableWidth
        height: 6
        radius: 3
        color: Theme.muted

        Rectangle {
            width: root.visualPosition * parent.width
            height: parent.height
            radius: 3
            color: Theme.primary
        }
    }

    handle: Rectangle {
        x: root.leftPadding + root.visualPosition * (root.availableWidth - width)
        y: root.topPadding + (root.availableHeight - height) / 2
        width: 18
        height: 18
        radius: 9
        color: Theme.background
        border.width: root.visualFocus ? 3 : 2
        border.color: root.visualFocus ? Theme.ring : Theme.primary
    }
}
