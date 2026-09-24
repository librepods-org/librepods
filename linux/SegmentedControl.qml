// SegmentedControl.qml
// Modified by LibrePods HiiT: shadcn/ui "Tabs" look with an icon per option. `currentIndex`
// now only follows its binding; user choices are reported through `activated`, so
// changes coming from the device or the tray keep showing up after the first click.
pragma ComponentBehavior: Bound

import QtQuick
import QtQuick.Controls.Basic

Control {
    id: root

    // Properties
    property var model: ["Option 1", "Option 2"] // Default model
    property var icons: [] // Optional icon name per option
    property int currentIndex: 0

    // Option picked by the user and not yet confirmed through `currentIndex`
    property int pendingIndex: -1

    signal activated(int index)

    function activate(index) {
        if (index < 0 || index >= model.length || index === currentIndex)
            return;
        pendingIndex = index;
        pendingTimer.restart();
        activated(index);
    }

    onCurrentIndexChanged: pendingIndex = -1

    Timer {
        id: pendingTimer
        interval: 3000
        onTriggered: root.pendingIndex = -1
    }

    // Internal properties
    padding: 4
    implicitWidth: 360
    implicitHeight: 72
    font.family: Theme.fontFamily
    font.pixelSize: Theme.fontSize

    // Set focus policy to enable keyboard navigation
    focusPolicy: Qt.StrongFocus
    activeFocusOnTab: true

    // Styling
    background: Rectangle {
        radius: Theme.radiusLarge
        color: Theme.muted

        // Focus ring
        Rectangle {
            anchors.fill: parent
            anchors.margins: -3
            radius: Theme.radiusLarge + 3
            color: "transparent"
            border.width: 2
            border.color: Theme.ring
            visible: root.visualFocus
        }
    }

    contentItem: Row {
        spacing: root.padding

        Repeater {
            model: root.model

            delegate: AbstractButton {
                id: segmentButton
                required property int index
                required property string modelData
                readonly property bool selected: root.currentIndex === index
                readonly property bool pending: root.pendingIndex === index

                width: (root.availableWidth - (root.model.length - 1) * root.padding) / root.model.length
                height: root.availableHeight
                text: modelData
                focusPolicy: Qt.NoFocus // Let the root control handle focus
                Accessible.role: Accessible.RadioButton
                Accessible.checked: selected

                contentItem: Column {
                    spacing: 6
                    topPadding: 8

                    Icon {
                        anchors.horizontalCenter: parent.horizontalCenter
                        visible: name !== ""
                        name: root.icons[segmentButton.index] ?? ""
                        color: segmentButton.selected ? Theme.foreground : Theme.mutedForeground
                    }

                    Text {
                        width: parent.width
                        text: segmentButton.text
                        font: root.font
                        color: segmentButton.selected ? Theme.foreground : Theme.mutedForeground
                        horizontalAlignment: Text.AlignHCenter
                        wrapMode: Text.WordWrap
                        maximumLineCount: 2
                        elide: Text.ElideRight
                    }
                }

                background: Rectangle {
                    radius: Theme.radius
                    color: segmentButton.selected ? Theme.background
                         : segmentButton.pending || segmentButton.hovered ? Theme.accent
                         : "transparent"
                    border.width: segmentButton.selected || segmentButton.pending ? 1 : 0
                    border.color: segmentButton.pending ? Theme.ring : Theme.border

                    Behavior on color {
                        ColorAnimation { duration: Theme.animationDuration }
                    }
                }

                onClicked: root.activate(index)
            }
        }
    }

    // Handle key events for navigation
    Keys.onPressed: event => {
        if (event.key === Qt.Key_Left) {
            root.activate(root.currentIndex - 1);
            event.accepted = true;
        } else if (event.key === Qt.Key_Right) {
            root.activate(root.currentIndex + 1);
            event.accepted = true;
        } else if (event.key === Qt.Key_Home) {
            root.activate(0);
            event.accepted = true;
        } else if (event.key === Qt.Key_End) {
            root.activate(root.model.length - 1);
            event.accepted = true;
        } else if (event.key >= Qt.Key_1 && event.key <= Qt.Key_9) {
            root.activate(event.key - Qt.Key_1);
            event.accepted = true;
        }
    }
}
