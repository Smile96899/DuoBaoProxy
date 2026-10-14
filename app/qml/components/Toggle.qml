import QtQuick

// Pill switch. Colour only when on.
Rectangle {
    id: root
    property bool on: false
    signal clicked()
    width: 46; height: 26; radius: 13
    color: on ? Theme.accent : Theme.panel2
    opacity: enabled ? 1 : .5
    Behavior on color { ColorAnimation { duration: 150 } }
    Rectangle {
        width: 20; height: 20; radius: 10
        y: 3; x: root.on ? 23 : 3
        color: Theme.panel
        Behavior on x { NumberAnimation { duration: 150 } }
    }
    MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: root.clicked() }
}
