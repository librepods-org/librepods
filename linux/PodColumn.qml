// PodColumn.qml
// Modified by LibrePods HiiT: original line-art illustrations instead of product photos,
// and an "out of ear" label in addition to the dimmed opacity.
import QtQuick

Column {
    id: root
    property bool inEar: true
    property string illustration: "bud" // bud, case or headphones (assets/illustrations)
    property int batteryLevel: 0
    property bool isCharging: false
    property string indicator: ""
    property bool showsEarState: indicator !== ""
    property real targetOpacity: inEar ? 1 : 0.5

    Timer {
        id: opacityTimer
        interval: 50
        onTriggered: illustrationIcon.opacity = root.targetOpacity
    }

    onInEarChanged: {
        opacityTimer.restart()
    }

    spacing: 10

    Icon {
        id: illustrationIcon
        name: root.illustration
        color: Theme.foreground
        size: 72
        mirror: root.indicator === "R"
        anchors.horizontalCenter: parent.horizontalCenter

        Behavior on opacity {
            NumberAnimation { duration: Theme.animationDuration }
        }
    }

    BatteryIndicator {
        anchors.horizontalCenter: parent.horizontalCenter
        batteryLevel: root.batteryLevel
        isCharging: root.isCharging
        indicator: root.indicator
    }

    Text {
        anchors.horizontalCenter: parent.horizontalCenter
        visible: root.showsEarState
        text: root.inEar ? qsTr("In ear") : qsTr("Out of ear")
        font.family: Theme.fontFamily
        font.pixelSize: Theme.fontSize
        color: Theme.mutedForeground
    }
}
