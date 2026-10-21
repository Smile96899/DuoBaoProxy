import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// One node in a list. entry = {id, group, lat, node}
Rectangle {
    id: root
    property var entry
    property bool compact: false
    property bool picked: false            // part of a multi-selection
    property bool selecting: false         // a multi-selection is in progress: show check dots
    property var columns: ["url"]          // result columns to show, in order
    signal clicked(int modifiers)
    signal menuRequested(real x, real y)
    signal editRequested()
    readonly property var n: entry.node
    readonly property bool active: entry.id === App.selectedId
    readonly property bool ok: App.supports(n)
    radius: Theme.radius
    color: active ? Theme.accentSoft : (hover.hovered ? Theme.panel2 : "transparent")
    opacity: ok ? 1 : .45
    border.width: picked ? 1.5 : 0
    border.color: Theme.fg2
    implicitHeight: compact ? 54 : 44

    HoverHandler { id: hover }
    MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.LeftButton | Qt.RightButton
        onClicked: mouse => mouse.button === Qt.RightButton ? root.menuRequested(mouse.x, mouse.y) : root.clicked(mouse.modifiers)
        onDoubleClicked: mouse => { if (mouse.button === Qt.LeftButton) root.editRequested() }
    }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 14; anchors.rightMargin: 14
        spacing: 12
        Rectangle { width: 8; height: 8; radius: 4; color: active ? Theme.lookColor(App.look) : Theme.fg3; visible: !compact && !selecting }
        CheckDot { visible: selecting; checked: picked }
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2
            Label { text: App.nodeName(n); color: Theme.fg; font.weight: Font.Medium; elide: Text.ElideRight; Layout.fillWidth: true }
            RowLayout {
                spacing: 8
                Rectangle { radius: 4; color: active ? Theme.panel : Theme.panel2; height: 16; width: pt.implicitWidth + 10
                    Label { id: pt; anchors.centerIn: parent; text: n.protocol; color: Theme.fg2; font.pixelSize: 10; font.family: Theme.mono } }
                Label { text: n.server + ":" + n.port; color: Theme.fg2; font.pixelSize: 11; font.family: Theme.mono; elide: Text.ElideMiddle; Layout.fillWidth: true }
                Label { visible: !ok; text: App.coreId + " 不支持"; color: Theme.bad; font.pixelSize: 11 }
            }
        }
        // One cell per result column the list shows (see Tests.kinds).
        Repeater {
            model: root.columns
            Item {
                readonly property var c: Tests.cell(root.entry, modelData)
                readonly property bool busy: Tests.isRunning(root.entry.id, modelData)
                Layout.preferredWidth: Tests.kind(modelData).w
                implicitHeight: 20
                Label {
                    anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                    width: Math.min(implicitWidth, parent.width); elide: Text.ElideRight
                    visible: parent.busy || parent.c !== null
                    text: parent.busy ? "测试中…" : (parent.c ? parent.c.text : "")
                    color: parent.busy ? Theme.fg3 : (parent.c ? parent.c.color : Theme.fg)
                    font.family: Theme.mono; font.pixelSize: 12
                }
            }
        }
        // Lines up with the × after the column titles.
        Item { visible: root.columns.length > 0; implicitWidth: 8 }
    }
}
