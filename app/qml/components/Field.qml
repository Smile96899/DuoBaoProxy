import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Settings row: title + description on the left, a control on the right.
Item {
    id: root
    property string title
    property string detail
    default property alias control: slot.data
    implicitHeight: Math.max(row.implicitHeight, 44) + 12
    RowLayout {
        id: row
        anchors.fill: parent
        anchors.topMargin: 6; anchors.bottomMargin: 6
        spacing: 12
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2
            Label { text: root.title; color: Theme.fg; font.weight: Font.Medium }
            Label { visible: root.detail !== ""; text: root.detail; color: Theme.fg2; font.pixelSize: 12; wrapMode: Text.Wrap; Layout.fillWidth: true }
        }
        Item { id: slot; implicitWidth: childrenRect.width; implicitHeight: childrenRect.height }
    }
    Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
}
