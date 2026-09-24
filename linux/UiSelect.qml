// UiSelect.qml
// LibrePods HiiT: dropdown in the shadcn/ui "Select" style.
pragma ComponentBehavior: Bound

import QtQuick
import QtQuick.Controls.Basic

ComboBox {
    id: root

    implicitHeight: Theme.controlHeight
    implicitWidth: 240
    leftPadding: 12
    rightPadding: 36
    font.family: Theme.fontFamily
    font.pixelSize: Theme.fontSize
    opacity: enabled ? 1 : 0.5

    contentItem: Text {
        text: root.displayText
        font: root.font
        color: Theme.foreground
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }

    indicator: Icon {
        x: root.width - width - 12
        y: (root.height - height) / 2
        name: "chevron-down"
        color: Theme.mutedForeground
    }

    background: Rectangle {
        radius: Theme.radius
        color: root.hovered ? Theme.accent : "transparent"
        border.width: 1
        border.color: root.visualFocus ? Theme.ring : Theme.input
    }

    delegate: ItemDelegate {
        id: option
        required property int index
        required property var modelData

        width: ListView.view.width
        height: Theme.controlHeight
        leftPadding: 8
        highlighted: root.highlightedIndex === index

        contentItem: Row {
            spacing: 8

            Icon {
                anchors.verticalCenter: parent.verticalCenter
                name: "check"
                opacity: root.currentIndex === option.index ? 1 : 0
            }

            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: option.modelData
                font: root.font
                color: Theme.foreground
            }
        }

        background: Rectangle {
            radius: Theme.radius - 2
            color: option.highlighted ? Theme.accent : "transparent"
        }
    }

    popup: Popup {
        y: root.height + 4
        width: root.width
        padding: 4
        implicitHeight: contentItem.implicitHeight + topPadding + bottomPadding

        contentItem: ListView {
            clip: true
            implicitHeight: contentHeight
            model: root.popup.visible ? root.delegateModel : null
            currentIndex: root.highlightedIndex
        }

        background: Rectangle {
            radius: Theme.radius
            color: Theme.background
            border.width: 1
            border.color: Theme.border
        }
    }
}
