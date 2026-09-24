// Icon.qml
// LibrePods HiiT: Lucide icon or device illustration, tinted through IconImageProvider.
import QtQuick

Image {
    id: root

    property string name: ""
    property color color: Theme.foreground
    property int size: Theme.iconSize

    width: size
    height: size
    sourceSize: Qt.size(width, height)
    fillMode: Image.PreserveAspectFit
    source: name !== "" ? "image://icon/" + name + "/" + color.toString().substring(1) : ""
}
