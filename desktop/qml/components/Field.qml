import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Settings row: title + description on the left, a control on the right.
// When the row is too narrow for both, the control drops below the text.
Item {
    id: root
    property string title
    property string detail
    default property alias control: slot.data
    readonly property bool stacked: width > 0 && width < slot.implicitWidth + 160
    implicitHeight: Math.max(grid.implicitHeight, 44) + 12
    GridLayout {
        id: grid
        anchors.fill: parent
        anchors.topMargin: 6; anchors.bottomMargin: 6
        columns: root.stacked ? 1 : 2
        columnSpacing: 12; rowSpacing: 8
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2
            Label { text: root.title; color: Theme.fg; font.weight: Font.Medium; wrapMode: Text.Wrap; Layout.fillWidth: true }
            Label { visible: root.detail !== ""; text: root.detail; color: Theme.fg2; font.pixelSize: 12; wrapMode: Text.WrapAtWordBoundaryOrAnywhere; Layout.fillWidth: true }
        }
        Item { id: slot; implicitWidth: childrenRect.width; implicitHeight: childrenRect.height }
    }
    Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
}
