pragma ComponentBehavior: Bound

import QtQuick 2.15
import QtQuick.Controls 2.15

Control {
    id: root

    signal activated(int index)

    // Properties
    property var model: ["Option 1", "Option 2"] // Default model
    property int currentIndex: 0
    property color accentColor: palette.highlight
    property color disabledBackgroundColor: "#eef1f5"
    property color disabledSelectedColor: "#b9c4d2"
    property color disabledTextColor: "#8b96a3"

    // Colors using system palette
    readonly property color backgroundColor: root.enabled ? palette.light : root.disabledBackgroundColor
    readonly property color selectedColor: root.enabled ? root.accentColor : root.disabledSelectedColor
    readonly property color textColor: root.enabled ? palette.buttonText : root.disabledTextColor
    readonly property color selectedTextColor: palette.highlightedText

    // System palette
    SystemPalette {
        id: palette
    }

    // Internal properties
    padding: 6
    implicitHeight: 32
    // Removed: implicitWidth: Math.max(200, model.length * 100)

    // Set focus policy to enable keyboard navigation
    focusPolicy: Qt.StrongFocus
    activeFocusOnTab: true

    // Styling
    background: Rectangle {
        radius: height / 2
        color: root.backgroundColor
        border.width: root.activeFocus ? 1 : 0
        border.color: root.selectedColor
    }

    contentItem: Row {
        spacing: root.padding

        Repeater {
            model: root.model

            delegate: Button {
                id: segmentButton
                required property int index
                required property string modelData
                text: modelData
                // Removed: width: (root.availableWidth - (root.model.length - 1) * root.padding) / root.model.length
                height: root.availableHeight
                focusPolicy: Qt.NoFocus // Let the root control handle focus
                enabled: root.enabled

                // Add explicit text color
                contentItem: Text {
                    text: segmentButton.text
                    font: segmentButton.font
                    color: root.currentIndex === segmentButton.index ? root.selectedTextColor : root.textColor
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                    leftPadding: 2
                    rightPadding: 2
                    elide: Text.ElideRight
                }

                background: Rectangle {
                    radius: height / 2
                    color: root.currentIndex === segmentButton.index ? root.selectedColor : "transparent"
                    border.width: 0

                    Behavior on color {
                        ColorAnimation {
                            duration: 600
                            easing.type: Easing.OutQuad
                        }
                    }
                }

                onClicked: {
                    if (root.currentIndex !== index) {
                        root.currentIndex = index;
                        root.activated(index);
                    }
                }
            }
        }
    }

    // Handle key events for navigation
    Keys.onPressed: event => {
        if (event.key === Qt.Key_Left) {
            if (root.currentIndex > 0) {
                root.currentIndex--;
                root.activated(root.currentIndex);
                event.accepted = true;
            }
        } else if (event.key === Qt.Key_Right) {
            if (root.currentIndex < root.model.length - 1) {
                root.currentIndex++;
                root.activated(root.currentIndex);
                event.accepted = true;
            }
        } else if (event.key === Qt.Key_Home) {
            if (root.currentIndex !== 0) {
                root.currentIndex = 0;
                root.activated(root.currentIndex);
                event.accepted = true;
            }
        } else if (event.key === Qt.Key_End) {
            const lastIndex = root.model.length - 1;
            if (root.currentIndex !== lastIndex) {
                root.currentIndex = lastIndex;
                root.activated(root.currentIndex);
                event.accepted = true;
            }
        } else if (event.key >= Qt.Key_1 && event.key <= Qt.Key_9) {
            const index = event.key - Qt.Key_1;
            if (index < root.model.length && root.currentIndex !== index) {
                root.currentIndex = index;
                root.activated(root.currentIndex);
                event.accepted = true;
            }
        }
    }
}
