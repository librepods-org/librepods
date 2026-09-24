// KeysQRDialog.qml
// Modified by LibrePods HiiT: Theme colors and Departure Mono font.
import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts
import QtQuick.Window

Window {
    id: root
    title: "Magic Cloud Keys QR Code"
    flags: Qt.Dialog
    modality: Qt.WindowModal

    color: Theme.background

    width: Math.min(Screen.width * 0.8, 300)
    height: Math.min(Screen.height * 0.7, 350)

    property string irk: ""
    property string encKey: ""

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 20
        spacing: 20

        // QR Code Container
        Rectangle {
            id: qrContainer
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.minimumHeight: width
            radius: Theme.radiusLarge
            color: "white" // QR codes need a light quiet zone to scan in dark mode too
            border.color: Theme.border

            Image {
                id: qrCodeImage
                anchors.centerIn: parent
                width: Math.min(parent.width * 0.9, parent.height * 0.9)
                height: width
                fillMode: Image.PreserveAspectFit
                source: "image://qrcode/" + root.encKey + ";" + root.irk

                BusyIndicator {
                    anchors.centerIn: parent
                    running: qrCodeImage.status === Image.Loading
                }

                Label {
                    anchors.centerIn: parent
                    visible: qrCodeImage.status === Image.Error
                    text: "Failed to generate QR code"
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.fontSize
                    color: "#09090b"
                }
            }
        }

        // Instruction text
        Label {
            Layout.fillWidth: true
            text: "Scan this QR code to transfer\nthe Magic Cloud Keys to another device"
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.WordWrap
            color: Theme.foreground
            font.family: Theme.fontFamily
            font.pixelSize: Theme.fontSize
        }
    }
}