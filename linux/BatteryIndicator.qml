// BatteryIndicator.qml
// Modified by LibrePods HiiT: Lucide battery icons instead of the hand-drawn battery;
// the level is shown by icon shape and text, not by color alone.
import QtQuick

Row {
    id: root

    // Public properties
    property int batteryLevel: 50 // 0-100
    property bool isCharging: false
    property string indicator: "" // "L" or "R"

    readonly property bool low: !isCharging && batteryLevel <= 20
    readonly property string iconName: {
        if (isCharging) return "battery-charging";
        if (batteryLevel <= 10) return "battery-warning";
        if (batteryLevel <= 20) return "battery-low";
        if (batteryLevel <= 60) return "battery-medium";
        return "battery-full";
    }
    readonly property color levelColor: isCharging ? Theme.success : low ? Theme.danger : Theme.foreground

    spacing: 6

    // Left/Right indicator
    Rectangle {
        visible: root.indicator !== ""
        anchors.verticalCenter: parent.verticalCenter
        width: 18
        height: 18
        radius: 4
        color: Theme.muted

        Text {
            anchors.centerIn: parent
            text: root.indicator
            font.family: Theme.fontFamily
            font.pixelSize: Theme.fontSize
            color: Theme.foreground
        }
    }

    Icon {
        anchors.verticalCenter: parent.verticalCenter
        name: root.iconName
        color: root.levelColor
        size: 20
    }

    // Battery percentage
    Text {
        anchors.verticalCenter: parent.verticalCenter
        text: root.batteryLevel + "%"
        font.family: Theme.fontFamily
        font.pixelSize: Theme.fontSize
        color: root.low ? Theme.danger : Theme.foreground
    }
}
