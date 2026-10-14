import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "components"
import "pages"

// Portrait: status card | page | bottom tabs.
Item {
    id: root
    property string tab: "nodes"

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
                case "settings": return settingsPage
                default: return nodesPage
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            height: 64
            color: Theme.panel
            Rectangle { width: parent.width; height: 1; color: Theme.line }
            RowLayout {
                anchors.fill: parent
                anchors.margins: 6
                spacing: 0
                Repeater {
                    model: App.coreIsVpn
                        ? [{v: "nodes", l: "节点"}, {v: "groups", l: "分组"}, {v: "settings", l: "设置"}]
                        : [{v: "nodes", l: "节点"}, {v: "groups", l: "分组"}, {v: "routes", l: "路由"}, {v: "settings", l: "设置"}]
                    Rectangle {
                        Layout.fillWidth: true; Layout.fillHeight: true
                        radius: 8; color: "transparent"
                        Label { anchors.centerIn: parent; text: modelData.l; color: root.tab === modelData.v ? Theme.fg : Theme.fg3; font.pixelSize: 12; font.weight: root.tab === modelData.v ? Font.DemiBold : Font.Normal }
                        MouseArea { anchors.fill: parent; onClicked: root.tab = modelData.v }
                    }
                }
            }
        }
    }

    Component { id: nodesPage; NodesPage { compact: true } }
    Component { id: groupsPage; GroupsPage { compact: true } }
    Component { id: routesPage; RoutesPage { compact: true } }
    Component { id: settingsPage; SettingsPage { compact: true } }
}
