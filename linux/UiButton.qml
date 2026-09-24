// UiButton.qml
// LibrePods HiiT: button with shadcn/ui variants: "default", "secondary", "outline", "ghost", "destructive".
import QtQuick
import QtQuick.Controls.Basic

Button {
    id: root

    property string variant: "default"
    property string iconName: ""
    property string toolTipText: ""
    readonly property bool iconOnly: text === ""

    readonly property color fillColor: {
        switch (variant) {
        case "secondary": return Theme.secondary
        case "destructive": return Theme.destructive
        case "outline":
        case "ghost": return "transparent"
        default: return Theme.primary
        }
    }
    readonly property color contentColor: {
        switch (variant) {
        case "secondary": return Theme.secondaryForeground
        case "destructive": return Theme.destructiveForeground
        case "outline":
        case "ghost": return Theme.foreground
        default: return Theme.primaryForeground
        }
    }

    implicitHeight: Theme.controlHeight
    implicitWidth: iconOnly ? Theme.controlHeight : implicitContentWidth + leftPadding + rightPadding
    padding: 0
    leftPadding: iconOnly ? 0 : 12
    rightPadding: iconOnly ? 0 : 12
    font.family: Theme.fontFamily
    font.pixelSize: Theme.fontSize
    focusPolicy: Qt.StrongFocus
    opacity: enabled ? 1 : 0.5
    Accessible.name: iconOnly ? toolTipText : text

    contentItem: Row {
        spacing: 6

        Icon {
            anchors.verticalCenter: parent.verticalCenter
            visible: root.iconName !== ""
            name: root.iconName
            color: root.contentColor
        }

        Text {
            anchors.verticalCenter: parent.verticalCenter
            visible: !root.iconOnly
            text: root.text
            font: root.font
            color: root.contentColor
        }
    }

    background: Rectangle {
        radius: Theme.radius
        color: {
            if (!root.hovered && !root.down)
                return root.fillColor
            if (root.variant === "outline" || root.variant === "ghost")
                return Theme.accent
            return Qt.darker(root.fillColor, Theme.dark ? 0.85 : 1.15)
        }
        border.width: root.variant === "outline" ? 1 : 0
        border.color: Theme.input

        // Focus ring
        Rectangle {
            anchors.fill: parent
            anchors.margins: -3
            radius: Theme.radius + 3
            color: "transparent"
            border.width: 2
            border.color: Theme.ring
            visible: root.visualFocus
        }

        Behavior on color {
            ColorAnimation { duration: Theme.animationDuration }
        }
    }

    UiToolTip {
        visible: root.toolTipText !== "" && root.hovered
        text: root.toolTipText
    }
}
