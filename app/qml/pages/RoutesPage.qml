import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// Routing rules as plain text boxes, one rule per line, plus DNS.
Item {
    id: root
    property bool compact: false
    readonly property var routing: store.settings.routing || ({})

    Flickable {
        anchors.fill: parent
        contentHeight: col.implicitHeight + 36
        clip: true
        ColumnLayout {
            id: col
            width: parent.width - 36
            x: 18; y: 12
            spacing: 16

            RowLayout {
                Layout.fillWidth: true
                Label { text: "路由规则"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
                Label { visible: !compact; text: "一行一条，支持 geosite: geoip: full: domain: keyword: regexp: 和 CIDR"; color: Theme.fg2; font.pixelSize: 12 }
                Item { Layout.fillWidth: true }
                Button { text: "导出 JSON"; flat: true
                    onClicked: { var req = App.nextReq++; App.pendingTests[req] = {kind: "export"}; core.renderConfig(req, App.coreId, [store.nodeJson(App.selectedId)], JSON.stringify(App.renderSettings()), 2) } }
                Button { text: "应用"; highlighted: true; onClicked: App.reconnect() }
            }

            Seg {
                visible: compact
                model: [{v: "rule", l: "规则"}, {v: "global", l: "全局"}, {v: "direct", l: "直连"}]
                current: store.ui.proxy_mode || "rule"
                onPicked: v => { store.setUi("proxy_mode", v); App.reconnect() }
            }

            GridLayout {
                Layout.fillWidth: true
                columns: compact ? 1 : 3
                columnSpacing: 16; rowSpacing: 16
                RuleBox { Layout.fillWidth: true; title: "走代理 · 域名"; key: "routing.proxy_domain"; lines: routing.proxy_domain; minHeight: compact ? 90 : 140 }
                RuleBox { Layout.fillWidth: true; title: "直连 · 域名"; key: "routing.direct_domain"; lines: routing.direct_domain; minHeight: compact ? 90 : 140 }
                RuleBox { Layout.fillWidth: true; title: "拦截 · 域名"; key: "routing.block_domain"; lines: routing.block_domain; minHeight: compact ? 70 : 140 }
                RuleBox { Layout.fillWidth: true; title: "走代理 · IP"; key: "routing.proxy_ip"; lines: routing.proxy_ip; minHeight: 80 }
                RuleBox { Layout.fillWidth: true; title: "直连 · IP"; key: "routing.direct_ip"; lines: routing.direct_ip; minHeight: 80 }
                RuleBox { Layout.fillWidth: true; title: "拦截 · IP"; key: "routing.block_ip"; lines: routing.block_ip; minHeight: 80 }
            }

            Label { text: "兜底与 DNS"; color: Theme.fg3; font.pixelSize: 11; font.capitalization: Font.AllUppercase; font.letterSpacing: 1 }
            Field { Layout.fillWidth: true; title: "未匹配流量"; detail: "规则模式下没有命中任何规则时的去向"
                Seg { model: [{v: "proxy", l: "代理"}, {v: "bypass", l: "直连"}]; current: routing.final || "proxy"; onPicked: v => store.setSetting("routing.final", v) } }
            Field { Layout.fillWidth: true; title: "远程 DNS"; detail: "走代理解析，DoH / DoT / UDP 均可"
                TextField { width: 240; text: routing.remote_dns || ""; color: Theme.fg; background: Rectangle { radius: 6; color: Theme.panel2 } onEditingFinished: store.setSetting("routing.remote_dns", text) } }
            Field { Layout.fillWidth: true; title: "直连 DNS"; detail: "国内域名和代理服务器地址用它解析"
                TextField { width: 240; text: routing.direct_dns || ""; color: Theme.fg; background: Rectangle { radius: 6; color: Theme.panel2 } onEditingFinished: store.setSetting("routing.direct_dns", text) } }
            Field { Layout.fillWidth: true; title: "DNS 分流"; detail: "按上面的域名规则选择 DNS，减少泄漏"
                Toggle { on: routing.dns_routing === true; onClicked: store.setSetting("routing.dns_routing", !on) } }

            Loader { visible: compact && App.coreHasTun; Layout.fillWidth: true; sourceComponent: compact && App.coreHasTun ? appsInline : null }
            Component { id: appsInline; AppsPage { compact: true; implicitHeight: 360 } }
        }
    }

    Connections {
        target: core
        function onRendered(req, config, tags, error) {
            var p = App.pendingTests[req]
            if (!p || p.kind !== "export") return
            delete App.pendingTests[req]
            if (error !== "") { App.log("导出失败：" + error); return }
            sys.copyText(config)
            App.log("配置已复制到剪贴板（" + App.coreId + "）")
        }
    }
}
