import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Landscape left rail: mark, collapsed core picker, navigation.
Rectangle {
    id: root
    property string nav: "nodes"
    signal navigate(string nav)
    width: 64
    color: Theme.panel2

    ColumnLayout {
        anchors.fill: parent
        anchors.topMargin: 12; anchors.bottomMargin: 12
        spacing: 4

        Mark { Layout.alignment: Qt.AlignHCenter; size: 30; look: App.look }
        Item { height: 6 }

        // Core picker: the options float out beside the button.
        Rectangle {
            Layout.alignment: Qt.AlignHCenter
            width: 52; height: 32; radius: 16
            color: corePick.opened ? Theme.panel : (coreMa.containsMouse ? Theme.bg : Theme.panel)
            border.width: corePick.opened ? 1 : 0; border.color: Theme.line
            Label {
                anchors.centerIn: parent
                text: App.coreId === "sing-box" ? "sing\nbox" : App.coreId
                horizontalAlignment: Text.AlignHCenter
                font.family: Theme.mono; font.pixelSize: 10; lineHeight: .9
                color: Theme.fg
            }
            MouseArea { id: coreMa; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: corePick.open() }
            PickPopup {
                id: corePick
                x: parent.width + 10; y: 0
                itemWidth: 170
                model: App.cores.map(c => ({v: c.id, l: c.id + (c.version ? "  " + c.version : "")}))
                current: App.coreId
                onPicked: v => App.setCore(v)
            }
        }
        Rectangle { Layout.alignment: Qt.AlignHCenter; width: 36; height: 1; color: Theme.line; Layout.topMargin: 4; Layout.bottomMargin: 4 }

        Repeater {
            model: {
                var items = [{v: "nodes", l: "节点"}, {v: "groups", l: "分组"}]
                if (!App.coreIsVpn) items.push({v: "routes", l: "路由"})
                if (!App.coreIsVpn) items.push({v: "conns", l: "连接"})
                items.push({v: "logs", l: "日志"})
                return items
            }
            Rectangle {
                Layout.alignment: Qt.AlignHCenter
                width: 52; height: 32; radius: 16
                color: root.nav === modelData.v ? Theme.panel : "transparent"
                Label { anchors.centerIn: parent; text: modelData.l; font.pixelSize: 12; color: root.nav === modelData.v ? Theme.fg : Theme.fg2 }
                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: root.navigate(modelData.v) }
            }
        }
        Item { Layout.fillHeight: true }
        Rectangle {
            Layout.alignment: Qt.AlignHCenter
            width: 52; height: 32; radius: 16
            color: root.nav === "settings" ? Theme.panel : "transparent"
            Label { anchors.centerIn: parent; text: "设置"; font.pixelSize: 12; color: root.nav === "settings" ? Theme.fg : Theme.fg2 }
            MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: root.navigate("settings") }
        }
    }
    Rectangle { anchors.right: parent.right; width: 1; height: parent.height; color: Theme.line }
}
