import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import "../components"

// Per-application routing; rows map to Settings.apps. sing-box applies it in
// TUN mode (every program) and system-proxy mode (programs that use the proxy).
Item {
    id: root
    property bool compact: false
    property bool embedded: false        // inside the 路由 page, which has the title
    readonly property var apps: store.settings.apps || []
    readonly property bool tunOn: (store.ui.mode === "tun") && App.coreHasTun
    readonly property bool supported: App.coreId === "sing-box"

    function setApps(list) { store.setSetting("apps", list) }
    function setOut(i, out) { var l = apps.slice(); l[i] = Object.assign({}, l[i], {outbound: out}); setApps(l) }
    function remove(i) { var l = apps.slice(); l.splice(i, 1); setApps(l) }
    function add(path) {
        var file = path.replace(/^file:\/\/\//, "").replace(/\//g, "\\")
        // A shortcut names the app ("Google Chrome"); the rule uses its target exe.
        var name = file.split("\\").pop().replace(/\.(exe|lnk)$/i, "")
        var target = sys.resolveShortcut(file)
        if (target === "") { addError.open(); return }
        addApp(name, target)
    }
    function addApp(name, path) {
        var base = path.split("\\").pop()
        var l = apps.slice(); l.push({name: name, process: base, outbound: "proxy"}); setApps(l)
    }

    FileDialog { id: picker; title: "选择应用程序"; nameFilters: ["程序或快捷方式 (*.exe *.lnk)", "所有文件 (*)"]; onAccepted: root.add(selectedFile.toString()) }

    // Installed programs found through desktop and Start Menu shortcuts.
    Popup {
        id: appPicker
        property var all: []
        readonly property var added: root.apps.map(a => a.process.toLowerCase())
        readonly property var shown: {
            var q = search.text.trim().toLowerCase()
            return q === "" ? all : all.filter(a => a.name.toLowerCase().indexOf(q) >= 0 || a.path.toLowerCase().indexOf(q) >= 0)
        }
        onAboutToShow: { search.text = ""; all = sys.installedApps(); search.forceActiveFocus() }
        modal: true; anchors.centerIn: Overlay.overlay
        width: Math.min(460, Overlay.overlay.width - 32); height: Math.min(560, Overlay.overlay.height - 32); padding: 20
        background: Rectangle { color: Theme.panel; radius: 28; border.width: 1; border.color: Theme.line }
        ColumnLayout { anchors.fill: parent; spacing: 12
            Label { text: "选择应用"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
            TextField { id: search; placeholderText: "搜索"; Layout.fillWidth: true; color: Theme.fg; leftPadding: 14; rightPadding: 14; background: Rectangle { radius: height / 2; color: Theme.panel2 } }
            ListView {
                Layout.fillWidth: true; Layout.fillHeight: true; clip: true; spacing: 2
                model: appPicker.shown
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                delegate: Rectangle {
                    readonly property string exe: modelData.path.split("\\").pop()
                    readonly property bool isAdded: appPicker.added.indexOf(exe.toLowerCase()) >= 0
                    width: ListView.view.width; height: 48; radius: Theme.radius
                    color: pickMa.containsMouse && !isAdded ? Theme.panel2 : "transparent"
                    opacity: isAdded ? .5 : 1
                    RowLayout { anchors.fill: parent; anchors.leftMargin: 10; anchors.rightMargin: 10; spacing: 12
                        Rectangle { width: 28; height: 28; radius: 7; color: Theme.panel2
                            Label { anchors.centerIn: parent; text: modelData.name.charAt(0).toUpperCase(); color: Theme.fg2; font.weight: Font.DemiBold } }
                        ColumnLayout { Layout.fillWidth: true; spacing: 1
                            Label { text: modelData.name; color: Theme.fg; elide: Text.ElideRight; Layout.fillWidth: true }
                            Label { text: exe; color: Theme.fg3; font.pixelSize: 11; font.family: Theme.mono; elide: Text.ElideMiddle; Layout.fillWidth: true }
                        }
                        Label { visible: isAdded; text: "已添加"; color: Theme.fg3; font.pixelSize: 12 }
                    }
                    MouseArea { id: pickMa; anchors.fill: parent; hoverEnabled: true; enabled: !isAdded; cursorShape: Qt.PointingHandCursor
                        onClicked: { root.addApp(modelData.name, modelData.path); appPicker.close() } }
                }
                Label { visible: appPicker.shown.length === 0; anchors.centerIn: parent; text: "没有找到应用"; color: Theme.fg3; font.pixelSize: 13 }
            }
            RowLayout {
                PillButton { text: "浏览…"; flat: true; onClicked: { appPicker.close(); picker.open() } }
                Item { Layout.fillWidth: true }
                PillButton { text: "取消"; flat: true; onClicked: appPicker.close() }
            }
        }
    }

    Popup {
        id: addError
        modal: true; anchors.centerIn: Overlay.overlay; width: Math.min(360, Overlay.overlay.width - 32); padding: 24
        background: Rectangle { color: Theme.panel; radius: 28; border.width: 1; border.color: Theme.line }
        ColumnLayout { width: parent.width; spacing: 16
            Label { text: "这个快捷方式没有指向程序文件"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold; wrapMode: Text.Wrap; Layout.fillWidth: true }
            RowLayout { Item { Layout.fillWidth: true }
                PillButton { text: "好"; highlighted: true; onClicked: addError.close() } }
        }
    }

    Popup {
        id: manual
        modal: true; anchors.centerIn: Overlay.overlay; width: Math.min(400, Overlay.overlay.width - 32); padding: 24
        background: Rectangle { color: Theme.panel; radius: 28; border.width: 1; border.color: Theme.line }
        ColumnLayout { width: parent.width; spacing: 12
            Label { text: "按进程名添加"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
            TextField { id: procField; placeholderText: "chrome.exe 或完整路径"; Layout.fillWidth: true; color: Theme.fg; leftPadding: 14; rightPadding: 14; background: Rectangle { radius: height / 2; color: Theme.panel2 } }
            RowLayout { Item { Layout.fillWidth: true }
                PillButton { text: "取消"; flat: true; onClicked: manual.close() }
                PillButton { text: "添加"; highlighted: true; enabled: procField.text.trim().length > 0; onClicked: { root.add(procField.text.trim()); procField.text = ""; manual.close() } } }
        }
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0
        RowLayout {
            Layout.fillWidth: true; Layout.margins: 12; Layout.leftMargin: compact ? 0 : 18; Layout.rightMargin: compact ? 0 : 18; spacing: 10
            Label { visible: !root.embedded; text: "应用分流"; color: Theme.fg; font.pixelSize: compact ? 13 : 15; font.weight: Font.DemiBold }
            Label { text: root.embedded ? apps.length + " 个应用" : apps.length; color: Theme.fg2 }
            Item { Layout.fillWidth: true }
            PillButton { text: "按进程名"; flat: true; onClicked: manual.open() }
            PillButton { text: "选择程序"; highlighted: true; visible: Qt.platform.os === "windows"; onClicked: appPicker.open() }
        }
        Rectangle { visible: !compact && !root.embedded; Layout.fillWidth: true; height: 1; color: Theme.line }
        Label {
            visible: !root.supported
            Layout.fillWidth: true; Layout.margins: 12; Layout.topMargin: 0; Layout.leftMargin: compact ? 0 : 18; Layout.rightMargin: compact ? 0 : 18
            text: App.coreId + " 内核不支持应用分流，切换到 sing-box 后生效。"
            color: Theme.bad; font.pixelSize: 12; wrapMode: Text.Wrap
        }
        ListView {
            Layout.fillWidth: true; Layout.fillHeight: true; clip: true
            leftMargin: compact ? 0 : 10; rightMargin: compact ? 0 : 10
            model: apps
            spacing: 2
            opacity: root.supported ? 1 : .5
            delegate: Rectangle {
                width: ListView.view.width - (compact ? 0 : 20); height: 52; radius: Theme.radius
                color: h.hovered ? Theme.panel2 : "transparent"
                HoverHandler { id: h }
                RowLayout {
                    anchors.fill: parent; anchors.leftMargin: 14; anchors.rightMargin: 10; spacing: 12
                    Rectangle { width: 32; height: 32; radius: 8; color: Theme.panel2
                        Label { anchors.centerIn: parent; text: (modelData.name || modelData.process).charAt(0).toUpperCase(); color: Theme.fg2; font.weight: Font.DemiBold } }
                    ColumnLayout { Layout.fillWidth: true; spacing: 1
                        Label { text: modelData.name || modelData.process; color: Theme.fg; font.weight: Font.Medium; elide: Text.ElideRight; Layout.fillWidth: true }
                        Label { text: modelData.process; color: Theme.fg3; font.pixelSize: 11; font.family: Theme.mono; elide: Text.ElideMiddle; Layout.fillWidth: true }
                    }
                    Seg { model: [{v: "proxy", l: "代理"}, {v: "bypass", l: "直连"}, {v: "block", l: "拦截"}]; current: modelData.outbound; onPicked: v => root.setOut(index, v) }
                    PillButton { text: "×"; flat: true; Layout.preferredWidth: 32; onClicked: root.remove(index) }
                }
            }
            Label { visible: apps.length === 0; anchors.centerIn: parent; text: "还没有应用规则"; color: Theme.fg3; font.pixelSize: 13 }
        }
    }
}
