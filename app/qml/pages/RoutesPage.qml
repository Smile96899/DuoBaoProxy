import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// Routing in two parts that apply together: rule lists (domain / IP) and
// per-app choices. The core matches block rules, then apps, then the
// proxy / direct lists, then the fallback.
Item {
    id: root
    property bool compact: false
    property string part: "rules"       // rules | apps
    readonly property var routing: store.settings.routing || ({})
    readonly property var apps: store.settings.apps || []
    readonly property string proxyMode: store.ui.proxy_mode || "rule"

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true; Layout.margins: 12; Layout.leftMargin: 18; Layout.rightMargin: 18
            spacing: 10
            Label { text: "路由"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
            Seg {
                visible: !root.compact
                model: [{v: "rules", l: "规则分流" + (App.routeParts === "apps" ? "（未启用）" : "")}, {v: "apps", l: "应用分流 " + root.apps.length + (App.routeParts === "rules" ? "（未启用）" : "")}]
                current: root.part
                onPicked: v => root.part = v
            }
            Item { Layout.fillWidth: true }
            PillButton { text: "导出 JSON"; flat: true; visible: !root.compact
                onClicked: { var req = App.nextReq++; App.pendingTests[req] = {kind: "export"}; core.renderConfig(req, App.coreId, [store.nodeJson(App.selectedId)], JSON.stringify(App.renderSettings()), 2) } }
            PillButton { text: "应用"; highlighted: true; onClicked: App.reconnect() }
        }
        Seg {
            visible: root.compact
            Layout.leftMargin: 18; Layout.bottomMargin: 8
            model: [{v: "rules", l: "规则分流" + (App.routeParts === "apps" ? "（未启用）" : "")}, {v: "apps", l: "应用分流 " + root.apps.length + (App.routeParts === "rules" ? "（未启用）" : "")}]
            current: root.part
            onPicked: v => root.part = v
        }

        // How the two parts combine, and what the current mode changes.
        Label {
            Layout.fillWidth: true; Layout.leftMargin: 18; Layout.rightMargin: 18; Layout.bottomMargin: 10
            wrapMode: Text.Wrap; color: Theme.fg2; font.pixelSize: 12
            text: (App.routeParts === "rules" ? "当前只启用规则分流，应用分流未启用（可在 设置 › 分流方式 中更改）。"
                   : App.routeParts === "apps" ? "当前只启用应用分流，规则分流未启用（可在 设置 › 分流方式 中更改）。没有应用规则的流量按“未匹配流量”处理。"
                   : "两部分同时生效，按顺序匹配：拦截规则 → 应用分流 → 走代理 / 直连规则 → 未匹配流量。")
                + (root.proxyMode === "global" ? "当前是全局模式：走代理 / 直连名单不生效。"
                   : root.proxyMode === "direct" ? "当前是直连模式：未匹配的流量直连。" : "")
        }
        Rectangle { Layout.fillWidth: true; height: 1; color: Theme.line }

        StackLayout {
            Layout.fillWidth: true; Layout.fillHeight: true
            currentIndex: root.part === "rules" ? 0 : 1

            // ---- part 1: rule lists ----
            Flickable {
                contentHeight: col.implicitHeight + 36
                clip: true
                ColumnLayout {
                    id: col
                    width: parent.width - 36
                    x: 18; y: 12
                    spacing: 16

                    Label { visible: !root.compact; Layout.fillWidth: true; text: "一行一条，支持 geosite: geoip: full: domain: keyword: regexp: 和 CIDR"; color: Theme.fg2; font.pixelSize: 12; wrapMode: Text.Wrap }
                    Seg {
                        visible: root.compact
                        model: [{v: "rule", l: "规则"}, {v: "global", l: "全局"}, {v: "direct", l: "直连"}]
                        current: root.proxyMode
                        onPicked: v => { store.setUi("proxy_mode", v); App.reconnect() }
                    }

                    GridLayout {
                        Layout.fillWidth: true
                        columns: root.compact || col.width < 440 ? 1 : (col.width < 700 ? 2 : 3)
                        columnSpacing: 16; rowSpacing: 16
                        RuleBox { Layout.fillWidth: true; title: "走代理 · 域名"; key: "routing.proxy_domain"; lines: root.routing.proxy_domain; boxHeight: root.compact ? 110 : 140 }
                        RuleBox { Layout.fillWidth: true; title: "直连 · 域名"; key: "routing.direct_domain"; lines: root.routing.direct_domain; boxHeight: root.compact ? 110 : 140 }
                        RuleBox { Layout.fillWidth: true; title: "拦截 · 域名"; key: "routing.block_domain"; lines: root.routing.block_domain; boxHeight: root.compact ? 110 : 140 }
                        RuleBox { Layout.fillWidth: true; title: "走代理 · IP"; key: "routing.proxy_ip"; lines: root.routing.proxy_ip; boxHeight: root.compact ? 110 : 140 }
                        RuleBox { Layout.fillWidth: true; title: "直连 · IP"; key: "routing.direct_ip"; lines: root.routing.direct_ip; boxHeight: root.compact ? 110 : 140 }
                        RuleBox { Layout.fillWidth: true; title: "拦截 · IP"; key: "routing.block_ip"; lines: root.routing.block_ip; boxHeight: root.compact ? 110 : 140 }
                    }

                    Label { text: "兜底与 DNS"; color: Theme.fg3; font.pixelSize: 11; font.capitalization: Font.AllUppercase; font.letterSpacing: 1 }
                    Field { Layout.fillWidth: true; title: "未匹配流量"; detail: "规则模式下没有命中任何规则时的去向"
                        Seg { model: [{v: "proxy", l: "代理"}, {v: "bypass", l: "直连"}]; current: root.routing.final || "proxy"; onPicked: v => store.setSetting("routing.final", v) } }
                    Field { Layout.fillWidth: true; title: "远程 DNS"; detail: "走代理解析，DoH / DoT / UDP 均可"
                        TextField { width: 240; text: root.routing.remote_dns || ""; color: Theme.fg; leftPadding: 14; rightPadding: 14; background: Rectangle { radius: height / 2; color: Theme.panel2 } onEditingFinished: store.setSetting("routing.remote_dns", text) } }
                    Field { Layout.fillWidth: true; title: "直连 DNS"; detail: "国内域名和代理服务器地址用它解析"
                        TextField { width: 240; text: root.routing.direct_dns || ""; color: Theme.fg; leftPadding: 14; rightPadding: 14; background: Rectangle { radius: height / 2; color: Theme.panel2 } onEditingFinished: store.setSetting("routing.direct_dns", text) } }
                    Field { Layout.fillWidth: true; title: "DNS 分流"; detail: "按上面的域名规则选择 DNS，减少泄漏"
                        Toggle { on: root.routing.dns_routing === true; onClicked: store.setSetting("routing.dns_routing", !on) } }
                }
            }

            // ---- part 2: per-app choices ----
            AppsPage { embedded: true; compact: root.compact }
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
