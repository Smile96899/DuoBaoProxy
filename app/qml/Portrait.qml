import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "components"
import "pages"

// Portrait: status card | page | bottom tabs.
Item {
    id: root
    readonly property string tab: App.page

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        StatusCard {
            Layout.fillWidth: true
            Layout.margins: 14
        }

        Loader {
            Layout.fillWidth: true
            Layout.fillHeight: true
            sourceComponent: {
                switch (root.tab) {
                case "groups": return groupsPage
                case "routes": return routesPage
                case "logs": return logsPage
                case "settings": return settingsPage
                default: return nodesPage
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            height: 64
            color: Theme.railPanel2
            Rectangle { width: parent.width; height: 1; color: Theme.railLine }
            RowLayout {
                anchors.fill: parent
                anchors.margins: 6
                spacing: 0
                Repeater {
                    model: [{v: "nodes", l: "节点"}, {v: "groups", l: "分组"}, {v: "routes", l: "路由"}, {v: "logs", l: "日志"}, {v: "settings", l: "设置"}]
                    Item {
                        Layout.fillWidth: true; Layout.fillHeight: true
                        Rectangle { anchors.centerIn: parent; width: 64; height: 32; radius: 16; color: root.tab === modelData.v ? Theme.railPanel : "transparent" }
                        Label { anchors.centerIn: parent; text: modelData.l; color: root.tab === modelData.v ? Theme.railFg : Theme.railFg3; font.pixelSize: 12; font.weight: root.tab === modelData.v ? Font.DemiBold : Font.Normal }
                        MouseArea { anchors.fill: parent; onClicked: App.page = modelData.v }
                    }
                }
            }
        }
    }

    Component { id: nodesPage; NodesPage { compact: true } }
    Component { id: groupsPage; GroupsPage { compact: true } }
    Component { id: routesPage; RoutesPage { compact: true } }
    Component { id: logsPage; LogsPage {} }
    Component { id: settingsPage; SettingsPage { compact: true } }
}
