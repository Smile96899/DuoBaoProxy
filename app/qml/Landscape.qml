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
                    Rectangle { width: 8; height: 8; radius: 4; color: Theme.lookColor(App.look) }
                    Label { id: statusLbl; text: App.statusText; color: Theme.fg; font.weight: Font.Medium; elide: Text.ElideRight; Layout.fillWidth: true; Layout.maximumWidth: Math.ceil(statusFull.advanceWidth) + 2; Layout.minimumWidth: 40
                        TextMetrics { id: statusFull; text: statusLbl.text; font: statusLbl.font } }
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
                        case "logs": return logsPage
                        case "settings": return settingsPage
                        default: return nodesPage
                        }
                    }
                }
                Rectangle { width: 1; Layout.fillHeight: true; color: Theme.line }

                // Detail column
                Rectangle {
                    Layout.preferredWidth: root.width >= 1100 ? 300 : 260
                    Layout.fillHeight: true
                    color: Theme.panel
                    // Compact facts on top; the log takes whatever height is left.
                    ColumnLayout {
                        id: detail
                        anchors.fill: parent
                        anchors.margins: 16
                        spacing: 10
                        Label { text: "当前节点"; color: Theme.fg3; font.pixelSize: 11; font.capitalization: Font.AllUppercase; font.letterSpacing: 1 }
                        Label { text: App.nodeName(App.selectedNode); color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold; elide: Text.ElideRight; Layout.fillWidth: true }
                        RowLayout {
                            visible: App.selectedNode !== null
                            spacing: 8
                            Rectangle { radius: 4; color: Theme.panel2; height: 18; Layout.preferredWidth: pt.implicitWidth + 12
                                Label { id: pt; anchors.centerIn: parent; text: App.selectedNode ? App.selectedNode.protocol : ""; color: Theme.fg2; font.pixelSize: 11; font.family: Theme.mono } }
                            Label { visible: App.connected; text: App.latency === 0 ? "— ms" : Theme.latText(App.latency); color: App.latency === 0 ? Theme.fg3 : Theme.latColor(App.latency); font.family: Theme.mono; font.pixelSize: 12 }
                            Label { visible: !App.supports(App.selectedNode); text: App.coreId + " 不支持"; color: Theme.bad; font.pixelSize: 11 }
                        }
                        GridLayout {
                            columns: 2; columnSpacing: 8; rowSpacing: 8
                            Layout.fillWidth: true
                            Repeater {
                                model: [
                                    {l: "下行", v: Theme.bytes(App.downRate) + "/s"},
                                    {l: "上行", v: Theme.bytes(App.upRate) + "/s"},
                                    {l: "本次下载", v: Theme.bytes(App.downTotal)},
                                    {l: "本次上传", v: Theme.bytes(App.upTotal)},
                                ]
                                Rectangle {
                                    Layout.fillWidth: true; Layout.preferredWidth: 1; implicitHeight: 40; radius: Theme.radius; color: Theme.panel2
                                    Column { anchors.fill: parent; anchors.leftMargin: 10; anchors.rightMargin: 8; anchors.topMargin: 5; spacing: 1
                                        Label { text: modelData.l; color: Theme.fg3; font.pixelSize: 10 }
                                        Label { text: modelData.v; width: parent.width; color: Theme.fg; font.pixelSize: 13; fontSizeMode: Text.HorizontalFit; minimumPixelSize: 10; font.family: Theme.mono; font.weight: Font.Medium }
                                    }
                                }
                            }
                        }
                        GridLayout {
                            visible: App.selectedNode !== null
                            columns: 2; columnSpacing: 14; rowSpacing: 4
                            Layout.fillWidth: true
                            Label { text: "地址"; color: Theme.fg3; font.pixelSize: 12 }
                            Label { text: App.selectedNode ? App.selectedNode.server + ":" + App.selectedNode.port : ""; color: Theme.fg; font.family: Theme.mono; font.pixelSize: 12; elide: Text.ElideMiddle; Layout.fillWidth: true }
                            Label { text: "分组"; color: Theme.fg3; font.pixelSize: 12 }
                            Label { text: App.selected.group || ""; color: Theme.fg; font.pixelSize: 12; elide: Text.ElideRight; Layout.fillWidth: true }
                            Label { text: "内核"; color: Theme.fg3; font.pixelSize: 12 }
                            Label { text: App.coreId; color: Theme.fg; font.family: Theme.mono; font.pixelSize: 12 }
                        }
                        Label { text: "最近日志"; color: Theme.fg3; font.pixelSize: 11; font.capitalization: Font.AllUppercase; font.letterSpacing: 1; Layout.topMargin: 4 }
                        Rectangle {
                            Layout.fillWidth: true; Layout.fillHeight: true; Layout.minimumHeight: 80
                            radius: Theme.radius; color: Theme.bg
                            ListView {
                                id: logList
                                anchors.fill: parent; anchors.margins: 10
                                clip: true
                                spacing: 4
                                model: App.logs.slice(-200)
                                onCountChanged: Qt.callLater(positionViewAtEnd)
                                Component.onCompleted: positionViewAtEnd()
                                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                                delegate: Label {
                                    width: logList.width
                                    text: modelData
                                    wrapMode: Text.WrapAtWordBoundaryOrAnywhere
                                    color: /error|fatal|failed|timeout/i.test(modelData) ? Theme.bad : (/warn/i.test(modelData) ? Theme.warn : Theme.fg2)
                                    font.family: Theme.mono; font.pixelSize: 11
                                }
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
    Component { id: logsPage; LogsPage {} }
    Component { id: settingsPage; SettingsPage {} }
}
