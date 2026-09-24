// UiCard.qml
// LibrePods HiiT: bordered container with an optional title, in the shadcn/ui "Card" style.
import QtQuick

Rectangle {
    id: root

    property string title: ""
    default property alias content: body.data

    implicitWidth: 360
    implicitHeight: column.implicitHeight + 2 * column.anchors.margins
    radius: Theme.radiusLarge
    color: Theme.card
    border.width: 1
    border.color: Theme.border

    Column {
        id: column
        anchors.fill: parent
        anchors.margins: 16
        spacing: 12

        Text {
            visible: root.title !== ""
            text: root.title
            font.family: Theme.fontFamily
            font.pixelSize: Theme.fontSize
            font.capitalization: Font.AllUppercase
            color: Theme.mutedForeground
        }

        Column {
            id: body
            width: parent.width
            spacing: 16
        }
    }
}
