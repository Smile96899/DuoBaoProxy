import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Landscape left rail: mark, collapsed core picker, navigation.
Rectangle {
    id: root
    property string nav: "nodes"
    property bool coresOpen: false
    signal navigate(string nav)
    width: 64
    color: Theme.panel2

    ColumnLayout {
        anchors.fill: parent
        anchors.topMargin: 14; anchors.bottomMargin: 14
        spacing: 6

        Mark { Layout.alignment: Qt.AlignHCenter; size: 30; on: App.connected; error: App.selectedNode !== null && !App.supports(App.selectedNode) }
        Item { height: 6 }

        // Core picker: only the active core until clicked.
        Repeater {
            model: root.coresOpen ? App.cores : [App.coreInfo]
            Rectangle {
                Layout.alignment: Qt.AlignHCenter
                width: 48; height: 40; radius: 10
                color: modelData.id === App.coreId ? Theme.panel : "transparent"
                border.width: root.coresOpen && modelData.id === App.coreId ? 2 : 0
                border.color: Theme.accent
                Label {
                    anchors.centerIn: parent
                    text: modelData.id === "sing-box" ? "sing\nbox" : modelData.id
                    horizontalAlignment: Text.AlignHCenter
                    font.family: Theme.mono; font.pixelSize: 10; lineHeight: .9
                    color: modelData.id === App.coreId ? Theme.fg : Theme.fg2
                }
                Label { visible: !root.coresOpen; anchors.bottom: parent.bottom; anchors.bottomMargin: 1; anchors.horizontalCenter: parent.horizontalCenter; text: "▾"; font.pixelSize: 8; color: Theme.fg3 }
                MouseArea {
                    anchors.fill: parent; cursorShape: Qt.PointingHandCursor
                    onClicked: { if (root.coresOpen) { App.setCore(modelData.id); root.coresOpen = false } else root.coresOpen = true }
                }
            }
        }
        Rectangle { Layout.alignment: Qt.AlignHCenter; width: 36; height: 1; color: Theme.line; Layout.topMargin: 4; Layout.bottomMargin: 4 }

        Repeater {
            model: {
                var items = [{v: "nodes", l: "节点"}, {v: "groups", l: "分组"}]
                if (!App.coreIsVpn) items.push({v: "routes", l: "路由"})
                if (App.coreHasTun) items.push({v: "apps", l: "应用"})
                if (!App.coreIsVpn) items.push({v: "conns", l: "连接"})
                items.push({v: "logs", l: "日志"})
                return items
            }
            Rectangle {
                Layout.alignment: Qt.AlignHCenter
                width: 44; height: 44; radius: 10
                color: root.nav === modelData.v ? Theme.panel : "transparent"
                Label { anchors.centerIn: parent; text: modelData.l; font.pixelSize: 12; color: root.nav === modelData.v ? Theme.fg : Theme.fg2 }
                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: root.navigate(modelData.v) }
            }
        }
        Item { Layout.fillHeight: true }
        Rectangle {
            Layout.alignment: Qt.AlignHCenter
            width: 44; height: 44; radius: 10
            color: root.nav === "settings" ? Theme.panel : "transparent"
            Label { anchors.centerIn: parent; text: "设置"; font.pixelSize: 12; color: root.nav === "settings" ? Theme.fg : Theme.fg2 }
            MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: root.navigate("settings") }
        }
    }
    Rectangle { anchors.right: parent.right; width: 1; height: parent.height; color: Theme.line }
}
