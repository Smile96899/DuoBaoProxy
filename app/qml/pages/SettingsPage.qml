import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

Item {
    id: root
    property bool compact: false
    readonly property var inbound: store.settings.inbound || ({})
    readonly property var tun: store.settings.tun || ({})

    component Section: Label { color: Theme.fg3; font.pixelSize: 11; font.capitalization: Font.AllUppercase; font.letterSpacing: 1; Layout.topMargin: 14 }
    component Input: TextField { color: Theme.fg; background: Rectangle { radius: 6; color: Theme.panel2 } }

    Flickable {
        anchors.fill: parent
        contentHeight: col.implicitHeight + 36
        clip: true
        ColumnLayout {
            id: col
            width: Math.min(parent.width - 36, 640)
            x: 18; y: 12
            spacing: 0

            Label { text: "设置"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold; Layout.bottomMargin: 4 }

            Section { text: "界面" }
            Field { Layout.fillWidth: true; title: "布局"; detail: "横屏适合桌面，竖屏适合贴边或迷你窗口。Ctrl+L 快速切换"
                Seg { model: [{v: "landscape", l: "横屏"}, {v: "portrait", l: "竖屏"}]; current: store.ui.layout || "landscape"; onPicked: v => App.setLayout(v) } }
            Field { Layout.fillWidth: true; title: "主题"
                Seg { model: [{v: "system", l: "跟随系统"}, {v: "light", l: "浅色"}, {v: "dark", l: "深色"}]; current: store.ui.theme || "system"; onPicked: v => { store.setUi("theme", v); Theme.mode = v } } }
            Field { Layout.fillWidth: true; title: "开机自启"; detail: "登录后最小化到托盘"
                Toggle { on: sys.autoStart; onClicked: sys.autoStart = !sys.autoStart } }

            Section { text: "连接" }
            Field { Layout.fillWidth: true; title: "模式"; detail: App.coreIsVpn ? "系统 VPN，由操作系统接管" : (App.coreHasTun ? "系统代理只影响读取代理设置的程序，TUN 接管全部流量" : "当前内核没有 TUN")
                Seg { visible: !App.coreIsVpn; model: App.coreHasTun ? [{v: "system", l: "系统代理"}, {v: "tun", l: "TUN"}] : [{v: "system", l: "系统代理"}]; current: store.ui.mode || "system"; onPicked: v => { store.setUi("mode", v); App.reconnect() } } }
            Field { Layout.fillWidth: true; title: "本地端口"; detail: "socks 与 http 共用"
                Input { width: 90; text: inbound.port || 2080; font.family: Theme.mono; validator: IntValidator { bottom: 1; top: 65535 } onEditingFinished: store.setSetting("inbound.port", Number(text)) } }
            Field { Layout.fillWidth: true; title: "允许局域网"; detail: "监听 0.0.0.0，建议同时设置用户名密码"
                Toggle { on: inbound.address === "0.0.0.0"; onClicked: store.setSetting("inbound.address", on ? "127.0.0.1" : "0.0.0.0") } }
            Field { Layout.fillWidth: true; title: "测速地址"
                Input { width: 240; text: store.ui.test_url || ""; onEditingFinished: store.setUi("test_url", text) } }

            Section { text: "TUN"; visible: App.coreHasTun }
            Field { visible: App.coreHasTun; Layout.fillWidth: true; title: "协议栈"
                Seg { model: [{v: "gvisor", l: "gvisor"}, {v: "system", l: "system"}, {v: "mixed", l: "mixed"}]; current: tun.stack || "gvisor"; onPicked: v => store.setSetting("tun.stack", v) } }
            Field { visible: App.coreHasTun; Layout.fillWidth: true; title: "严格路由 / Kill Switch"; detail: "代理断开时阻止流量直连，仅 TUN 模式有效"
                Toggle { on: tun.strict_route === true; onClicked: store.setSetting("tun.strict_route", !on) } }
            Field { visible: App.coreHasTun; Layout.fillWidth: true; title: "FakeIP"; detail: "TUN 下加速 DNS，部分应用不兼容"
                Toggle { on: tun.fake_dns === true; onClicked: store.setSetting("tun.fake_dns", !on) } }
            Field { visible: App.coreHasTun; Layout.fillWidth: true; title: "IPv6"
                Toggle { on: tun.ipv6 === true; onClicked: store.setSetting("tun.ipv6", !on) } }

            Section { text: "维护" }
            Field { Layout.fillWidth: true; title: "版本"; detail: sys.platformName + " " + Qt.application.version + " · " + App.cores.map(c => c.id + " " + c.version).join(" · ")
                Button { text: "检查更新"; flat: true; onClicked: sys.openUrl("https://github.com/Smile96899/h.proxy/releases") } }
            Field { Layout.fillWidth: true; title: "数据目录"; detail: store.dataDir
                Button { text: "打开"; flat: true; onClicked: sys.openUrl("file:///" + store.dataDir) } }
            Field { Layout.fillWidth: true; title: "核心日志"
                Button { text: "重启核心"; flat: true; onClicked: { core.shutdown(); core.launch() } } }
        }
    }
}
