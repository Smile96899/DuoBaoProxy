import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "components"
import "pages"

// Landscape: rail | main (topbar + page) | detail column.
Item {
    id: root
    property string nav: "nodes"

    RowLayout {
        anchors.fill: parent
        spacing: 0

        Rail {
            Layout.fillHeight: true
            nav: root.nav
            onNavigate: n => root.nav = n
        }

        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 0

            // Top bar: switch, state, core, latency, proxy mode, system/tun.
            Rectangle {
                Layout.fillWidth: true
                height: 56
                color: Theme.panel
                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 18; anchors.rightMargin: 18
                    spacing: 14
                    Toggle { on: App.connected; enabled: !App.busy; onClicked: App.toggle() }
                    Rectangle { width: 8; height: 8; radius: 4; color: App.connected ? Theme.accent : Theme.fg3 }
                    Label { text: App.statusText; color: Theme.fg; font.weight: Font.Medium }
                    Rectangle {
                        radius: 999; color: Theme.panel2; height: 22; Layout.preferredWidth: pill.implicitWidth + 16
                        Label { id: pill; anchors.centerIn: parent; text: App.coreId + " " + App.coreInfo.version; color: Theme.fg2; font.pixelSize: 11; font.family: Theme.mono }
                    }
                    Label { text: App.connected ? Theme.latText(App.latency) : "— ms"; color: App.connected ? Theme.latColor(App.latency) : Theme.fg3; font.family: Theme.mono; font.pixelSize: 12 }
                    Item { Layout.fillWidth: true }
                    Seg {
                        visible: !App.coreIsVpn
                        model: [{v: "rule", l: "规则"}, {v: "global", l: "全局"}, {v: "direct", l: "直连"}]
                        current: store.ui.proxy_mode || "rule"
                        onPicked: v => { store.setUi("proxy_mode", v); App.reconnect() }
                    }
                    Seg {
                        visible: !App.coreIsVpn
                        model: App.coreHasTun ? [{v: "system", l: "系统代理"}, {v: "tun", l: "TUN"}] : [{v: "system", l: "系统代理"}]
                        current: store.ui.mode || "system"
                        onPicked: v => { store.setUi("mode", v); App.reconnect() }
                    }
                }
                Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
            }

            RowLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                spacing: 0

                Loader {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    sourceComponent: {
                        switch (root.nav) {
                        case "groups": return groupsPage
                        case "routes": return routesPage
                        case "apps": return appsPage
                        case "conns": return connsPage
                        case "logs": return logsPage
                        case "settings": return settingsPage
                        default: return nodesPage
                        }
                    }
                }
                Rectangle { width: 1; Layout.fillHeight: true; color: Theme.line }

                // Detail column
                Rectangle {
                    width: 300
                    Layout.fillHeight: true
                    color: Theme.panel
                    Flickable {
                        anchors.fill: parent
                        anchors.margins: 16
                        contentHeight: detail.implicitHeight
                        clip: true
                        ColumnLayout {
                            id: detail
                            width: parent.width
                            spacing: 18
                            Label { text: "当前节点"; color: Theme.fg3; font.pixelSize: 11; font.capitalization: Font.AllUppercase; font.letterSpacing: 1 }
                            Label { text: App.nodeName(App.selectedNode); color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold; elide: Text.ElideRight; Layout.fillWidth: true }
                            RowLayout {
                                visible: App.selectedNode !== null
                                spacing: 8
                                Rectangle { radius: 4; color: Theme.panel2; height: 18; Layout.preferredWidth: pt.implicitWidth + 12
                                    Label { id: pt; anchors.centerIn: parent; text: App.selectedNode ? App.selectedNode.protocol : ""; color: Theme.fg2; font.pixelSize: 11; font.family: Theme.mono } }
                                Label { text: Theme.latText(App.selected.lat); color: Theme.latColor(App.selected.lat); font.family: Theme.mono; font.pixelSize: 12 }
                                Label { visible: !App.supports(App.selectedNode); text: App.coreId + " 不支持"; color: Theme.bad; font.pixelSize: 11 }
                            }
                            GridLayout {
                                columns: 2; columnSpacing: 10; rowSpacing: 10
                                Layout.fillWidth: true
                                Repeater {
                                    model: [
                                        {l: "下行", v: Theme.bytes(App.downRate) + "/s"},
                                        {l: "上行", v: Theme.bytes(App.upRate) + "/s"},
                                        {l: "本次下载", v: Theme.bytes(App.downTotal)},
                                        {l: "本次上传", v: Theme.bytes(App.upTotal)},
                                    ]
                                    Rectangle {
                                        Layout.fillWidth: true; height: 58; radius: Theme.radius; color: Theme.panel2
                                        Column { anchors.fill: parent; anchors.margins: 10; spacing: 2
                                            Label { text: modelData.l; color: Theme.fg3; font.pixelSize: 11; font.capitalization: Font.AllUppercase; font.letterSpacing: 1 }
                                            Label { text: modelData.v; color: Theme.fg; font.pixelSize: 18; font.family: Theme.mono; font.weight: Font.Medium }
                                        }
                                    }
                                }
                            }
                            GridLayout {
                                visible: App.selectedNode !== null
                                columns: 2; columnSpacing: 14; rowSpacing: 6
                                Label { text: "地址"; color: Theme.fg3; font.pixelSize: 12 }
                                Label { text: App.selectedNode ? App.selectedNode.server + ":" + App.selectedNode.port : ""; color: Theme.fg; font.family: Theme.mono; font.pixelSize: 12; elide: Text.ElideMiddle; Layout.fillWidth: true }
                                Label { text: "分组"; color: Theme.fg3; font.pixelSize: 12 }
                                Label { text: App.selected.group || ""; color: Theme.fg; font.pixelSize: 12 }
                                Label { text: "内核"; color: Theme.fg3; font.pixelSize: 12 }
                                Label { text: App.coreId; color: Theme.fg; font.family: Theme.mono; font.pixelSize: 12 }
                            }
                            Flow {
                                visible: App.selectedNode !== null
                                spacing: 8; Layout.fillWidth: true
                                Button { text: "测速"; onClicked: App.testNode(App.selectedId) }
                                Button { text: "复制链接"; onClicked: core.shareLink(store.nodeJson(App.selectedId), false) }
                                Button { text: "删除"; flat: true; onClicked: { var id = App.selectedId; App.selectedId = -1; store.setUi("selected", -1); store.removeNode(id) } }
                            }
                            Label { text: "最近日志"; color: Theme.fg3; font.pixelSize: 11; font.capitalization: Font.AllUppercase; font.letterSpacing: 1 }
                            Rectangle {
                                Layout.fillWidth: true; radius: Theme.radius; color: Theme.bg
                                implicitHeight: logText.implicitHeight + 20
                                Label { id: logText; anchors.fill: parent; anchors.margins: 10; wrapMode: Text.Wrap
                                    text: App.logs.slice(-4).join("\n"); color: Theme.fg2; font.family: Theme.mono; font.pixelSize: 11 }
                            }
                        }
                    }
                }
            }
        }
    }

    Component { id: nodesPage; NodesPage {} }
    Component { id: groupsPage; GroupsPage {} }
    Component { id: routesPage; RoutesPage {} }
    Component { id: appsPage; AppsPage {} }
    Component { id: connsPage; ConnsPage {} }
    Component { id: logsPage; LogsPage {} }
    Component { id: settingsPage; SettingsPage {} }

    Connections {
        target: core
        function onLinkReady(link, error) { if (error === "") sys.copyText(link); App.log(error === "" ? "已复制链接" : error) }
    }
}
