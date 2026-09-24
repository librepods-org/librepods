// Theme.qml
// LibrePods HiiT: design tokens for the Linux UI (shadcn/ui-inspired, zinc palette).
// Follows the system light/dark preference through SystemPalette.
pragma Singleton

import QtQuick

QtObject {
    id: theme

    readonly property SystemPalette systemPalette: SystemPalette {}
    readonly property bool dark: systemPalette.window.hslLightness < systemPalette.windowText.hslLightness

    // Colors
    readonly property color background: dark ? "#09090b" : "#ffffff"
    readonly property color foreground: dark ? "#fafafa" : "#09090b"
    readonly property color card: dark ? "#0f0f11" : "#ffffff"
    readonly property color muted: dark ? "#27272a" : "#f4f4f5"
    readonly property color mutedForeground: dark ? "#a1a1aa" : "#71717a"
    readonly property color accent: dark ? "#27272a" : "#f4f4f5"
    readonly property color border: dark ? "#27272a" : "#e4e4e7"
    readonly property color input: dark ? "#3f3f46" : "#d4d4d8"
    readonly property color ring: dark ? "#d4d4d8" : "#18181b"
    readonly property color primary: dark ? "#fafafa" : "#18181b"
    readonly property color primaryForeground: dark ? "#18181b" : "#fafafa"
    readonly property color secondary: dark ? "#27272a" : "#f4f4f5"
    readonly property color secondaryForeground: dark ? "#fafafa" : "#18181b"
    readonly property color destructive: dark ? "#dc2626" : "#ef4444"
    readonly property color destructiveForeground: "#fafafa"

    // Status colors, always paired with an icon or text
    readonly property color success: dark ? "#4ade80" : "#16a34a"
    readonly property color warning: dark ? "#fbbf24" : "#d97706"
    readonly property color danger: dark ? "#f87171" : "#dc2626"

    // Typography: Departure Mono is a pixel font, crisp at multiples of 11px
    readonly property string fontFamily: "Departure Mono"
    readonly property int fontSize: 11
    readonly property int fontSizeLarge: 22

    // Shape and spacing
    readonly property int radius: 6
    readonly property int radiusLarge: 10
    readonly property int spacing: 8
    readonly property int controlHeight: 32
    readonly property int iconSize: 16
    readonly property int animationDuration: 150
}
