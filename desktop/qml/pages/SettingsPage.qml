import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

Item {
    id: root
    property bool compact: false
    readonly property var inbound: store.settings.inbound || ({})
    readonly property var tun: store.settings.tun || ({})
    property bool testsOpen: false       // showing 测试设置 sub-page
    property bool helpOpen: false        // showing 操作说明 sub-page


    Loader {
        anchors.fill: parent
        active: root.testsOpen
        sourceComponent: TestSettings { onBack: root.testsOpen = false }
    }
    Loader {
        anchors.fill: parent
        active: root.helpOpen
        sourceComponent: HelpPage { onBack: root.helpOpen = false }
    }

    Flickable {
        visible: !root.testsOpen && !root.helpOpen
        anchors.fill: parent
        contentHeight: col.implicitHeight + 36
        clip: true
        ColumnLayout {
            id: col
            width: Math.min(parent.width - 36, 640)
            x: 18; y: 12
            spacing: 0

            // Opens the 操作说明 sub-page.
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 56
                color: helpMa.containsMouse ? Theme.panel2 : "transparent"
                radius: Theme.radius
                Label { anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: "操作说明"; color: Theme.fg; font.weight: Font.Medium }
                Label { anchors.right: parent.right; anchors.rightMargin: 12; anchors.verticalCenter: parent.verticalCenter; text: "›"; color: Theme.fg3; font.pixelSize: 18 }
                Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
                MouseArea { id: helpMa; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: root.helpOpen = true }
            }

            Field { Layout.fillWidth: true; title: "布局"
                Dropdown { model: [{v: "landscape", l: "横屏"}, {v: "portrait", l: "竖屏"}]; current: store.ui.layout || "landscape"; onPicked: v => App.setLayout(v) } }
            Field { Layout.fillWidth: true; title: "主题"
                Dropdown { model: [{v: "system", l: "跟随系统"}, {v: "light", l: "浅色"}, {v: "dark", l: "深色"}, {v: "mixed", l: "深浅组合"}]; current: store.ui.theme || "system"; onPicked: v => { store.setUi("theme", v); Theme.mode = v } } }
            Field { Layout.fillWidth: true; title: "开机自启"
                Toggle { on: sys.autoStart; onClicked: sys.autoStart = !sys.autoStart } }
            Field { Layout.fillWidth: true; title: "模式"
                Dropdown { model: App.coreHasTun ? [{v: "system", l: "系统代理"}, {v: "tun", l: "TUN"}] : [{v: "system", l: "系统代理"}]; current: store.ui.mode || "system"; onPicked: v => { store.setUi("mode", v); App.reconnect() } } }
            Field { Layout.fillWidth: true; title: "分流方式"
                Dropdown { model: [{v: "rules", l: "规则分流"}, {v: "apps", l: "应用分流"}, {v: "both", l: "同时生效"}]; current: App.routeParts; onPicked: v => App.setRouteParts(v) } }
            Field { Layout.fillWidth: true; title: "本地端口"
                Input { width: 90; text: inbound.port || 2080; font.family: Theme.mono; validator: IntValidator { bottom: 1; top: 65535 } onEditingFinished: store.setSetting("inbound.port", Number(text)) } }
            Field { Layout.fillWidth: true; title: "允许局域网"
                Toggle { on: inbound.address === "0.0.0.0"; onClicked: store.setSetting("inbound.address", on ? "127.0.0.1" : "0.0.0.0") } }

            // A row that opens the 测试设置 sub-page.
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 56
                color: testsMa.containsMouse ? Theme.panel2 : "transparent"
                radius: Theme.radius
                Label { anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: "测试设置"; color: Theme.fg; font.weight: Font.Medium }
                Label { anchors.right: parent.right; anchors.rightMargin: 12; anchors.verticalCenter: parent.verticalCenter; text: "›"; color: Theme.fg3; font.pixelSize: 18 }
                Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
                MouseArea { id: testsMa; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: root.testsOpen = true }
            }

            Field { visible: App.coreHasTun; Layout.fillWidth: true; title: "协议栈"
                Dropdown { model: [{v: "gvisor", l: "gvisor"}, {v: "system", l: "system"}, {v: "mixed", l: "mixed"}]; current: tun.stack || "gvisor"; onPicked: v => store.setSetting("tun.stack", v) } }
            Field { visible: App.coreHasTun; Layout.fillWidth: true; title: "严格路由 / Kill Switch"
                Toggle { on: tun.strict_route === true; onClicked: store.setSetting("tun.strict_route", !on) } }
            Field { visible: App.coreHasTun; Layout.fillWidth: true; title: "FakeIP"
                Toggle { on: tun.fake_dns === true; onClicked: store.setSetting("tun.fake_dns", !on) } }
            Field { visible: App.coreHasTun; Layout.fillWidth: true; title: "IPv6"
                Toggle { on: tun.ipv6 === true; onClicked: store.setSetting("tun.ipv6", !on) } }

            Field { Layout.fillWidth: true; title: "版本"; detail: "粗制滥造的v" + Qt.application.version
                PillButton { text: "检查更新"; flat: true; onClicked: sys.openUrl("https://github.com/Smile96899/DuoBaoProxy/releases") } }
            Field { Layout.fillWidth: true; title: "数据目录"; detail: store.dataDir
                PillButton { text: "打开"; flat: true; onClicked: sys.openUrl("file:///" + store.dataDir) } }
            Field { Layout.fillWidth: true; title: "核心日志"
                PillButton { text: "重启核心"; flat: true; onClicked: { core.shutdown(); core.launch() } } }
        }
    }
}
