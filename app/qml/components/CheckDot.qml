import QtQuick

// Round check shown on rows and group headers while picking several.
Rectangle {
    property bool checked: false
    width: 16; height: 16; radius: 8
    color: checked ? Theme.fg : "transparent"
    border.width: checked ? 0 : 1.5
    border.color: Theme.fg3
    Text { anchors.centerIn: parent; visible: parent.checked; text: "✓"; color: Theme.panel; font.pixelSize: 11; font.bold: true }
}
