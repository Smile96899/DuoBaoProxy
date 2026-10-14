import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// One node in a list. entry = {id, group, lat, node}
Rectangle {
    id: root
    property var entry
    property bool compact: false
    readonly property var n: entry.node
    readonly property bool active: entry.id === App.selectedId
    readonly property bool ok: App.supports(n)
    radius: Theme.radius
    color: active ? Theme.accentSoft : (hover.hovered ? Theme.panel2 : "transparent")
    opacity: ok ? 1 : .45
    implicitHeight: compact ? 54 : 44

    HoverHandler { id: hover }
    TapHandler { onTapped: App.selectNode(entry.id); onDoubleTapped: App.testNode(entry.id) }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 14; anchors.rightMargin: 14
        spacing: 12
        Rectangle { width: 8; height: 8; radius: 4; color: active && App.connected ? Theme.accent : Theme.fg3; visible: !compact }
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2
            Label { text: App.nodeName(n); color: Theme.fg; font.weight: Font.Medium; elide: Text.ElideRight; Layout.fillWidth: true }
            RowLayout {
                spacing: 8
                Rectangle { radius: 4; color: active ? Theme.panel : Theme.panel2; height: 16; width: pt.implicitWidth + 10
                    Label { id: pt; anchors.centerIn: parent; text: n.protocol; color: Theme.fg2; font.pixelSize: 10; font.family: Theme.mono } }
                Label { text: n.server + ":" + n.port; color: Theme.fg2; font.pixelSize: 11; font.family: Theme.mono; elide: Text.ElideMiddle; Layout.fillWidth: true }
                Label { visible: !compact; text: entry.group; color: Theme.fg3; font.pixelSize: 11 }
                Label { visible: !ok; text: App.coreId + " 不支持"; color: Theme.bad; font.pixelSize: 11 }
            }
        }
        Label { text: Theme.latText(entry.lat); color: Theme.latColor(entry.lat); font.family: Theme.mono; font.pixelSize: 12 }
    }
}
