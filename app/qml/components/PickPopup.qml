import QtQuick
import QtQuick.Controls

// Options listed top to bottom in a rounded card that floats over the page
// where it is opened; it never pushes the layout. model: [{v, l}], current: v
Popup {
    id: root
    property var model: []
    property var current
    property real itemWidth: 140
    signal picked(var v)
    padding: 6
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    background: Rectangle { color: Theme.panel; radius: 22; border.width: 1; border.color: Theme.line }

    contentItem: Column {
        spacing: 2
        Repeater {
            model: root.model
            Rectangle {
                width: root.itemWidth; height: 32; radius: 16
                color: modelData.v === root.current ? Theme.panel2 : (ma.containsMouse ? Theme.bg : "transparent")
                Text {
                    anchors.fill: parent; anchors.leftMargin: 14; anchors.rightMargin: 14
                    verticalAlignment: Text.AlignVCenter
                    text: modelData.l; elide: Text.ElideRight
                    font.pixelSize: 13; font.weight: modelData.v === root.current ? Font.DemiBold : Font.Normal
                    color: modelData.v === root.current ? Theme.fg : Theme.fg2
                }
                MouseArea { id: ma; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor
                    onClicked: { root.close(); root.picked(modelData.v) } }
            }
        }
    }
}
