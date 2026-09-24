// UiTextField.qml
// LibrePods HiiT: text input in the shadcn/ui "Input" style, with room for an error message.
import QtQuick
import QtQuick.Controls.Basic

TextField {
    id: root

    property bool invalid: false

    implicitHeight: Theme.controlHeight
    implicitWidth: 240
    leftPadding: 12
    rightPadding: 12
    font.family: Theme.fontFamily
    font.pixelSize: Theme.fontSize
    color: Theme.foreground
    placeholderTextColor: Theme.mutedForeground
    selectionColor: Theme.primary
    selectedTextColor: Theme.primaryForeground
    verticalAlignment: TextInput.AlignVCenter
    opacity: enabled ? 1 : 0.5

    background: Rectangle {
        radius: Theme.radius
        color: "transparent"
        border.width: root.activeFocus || root.invalid ? 2 : 1
        border.color: root.invalid ? Theme.danger : root.activeFocus ? Theme.ring : Theme.input
    }
}
