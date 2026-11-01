import QtQuick
import QtQuick.Controls

// Segmented control. model: [{v, l}], current: v
Rectangle {
    id: root
    property var model: []
    property var current
    signal picked(var v)
    radius: 999
    color: Theme.panel2
    implicitWidth: row.implicitWidth + 4
    implicitHeight: 28
    Row {
        id: row
        anchors.centerIn: parent
        spacing: 0
        Repeater {
            model: root.model
            Rectangle {
                radius: 999
                height: 24
                width: lbl.implicitWidth + 24
                color: root.current === modelData.v ? Theme.panel : "transparent"
                Label { id: lbl; anchors.centerIn: parent; text: modelData.l; font.pixelSize: 12; color: root.current === modelData.v ? Theme.fg : Theme.fg2 }
                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: root.picked(modelData.v) }
            }
        }
    }
}
