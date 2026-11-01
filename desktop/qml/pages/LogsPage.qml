import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// Log view: selectable text, right-click for 复制 / 全选 / 清空.
// Lines are appended one by one so a selection survives new output.
Item {
    id: root

    function esc(s) {
        return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
                .replace(/\n/g, "<br>").replace(/ {2,}/g, m => "&nbsp;".repeat(m.length))
    }
    function lineColor(l) {
        return /(^|\s)(错误|致命|崩溃)[ ：]|失败|超时/.test(l) ? Theme.bad : (/(^|\s)警告 /.test(l) ? Theme.warn : Theme.fg2)
    }
    function lineHtml(l) { return '<span style="color:' + lineColor(l) + '">' + esc(l) + "</span>" }

    property int shown: 0
    function sync() {
        var logs = App.logs
        if (logs.length < shown) { area.clear(); shown = 0 }        // 清空, or the 500-line window slid
        var follow = area.follow
        for (; shown < logs.length; shown++) area.append(lineHtml(logs[shown]))
        if (follow && area.selectedText === "") Qt.callLater(area.toEnd)
    }
    Component.onCompleted: sync()
    Connections { target: App; function onLogsChanged() { root.sync() } }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0
        RowLayout {
            Layout.fillWidth: true; Layout.margins: 12; Layout.leftMargin: 18; Layout.rightMargin: 18; spacing: 10
            Label { text: "日志"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
            Item { Layout.fillWidth: true }
            PillButton { text: "清空"; flat: true; onClicked: App.logs = [] }
        }
        Rectangle { Layout.fillWidth: true; height: 1; color: Theme.line }
        Rectangle {
            Layout.fillWidth: true; Layout.fillHeight: true; Layout.margins: 12
            radius: Theme.radius; color: Theme.bg
            ScrollView {
                id: scroll
                anchors.fill: parent; anchors.margins: 10
                clip: true
                TextArea {
                    id: area
                    readOnly: true
                    selectByMouse: true
                    textFormat: TextEdit.RichText
                    wrapMode: TextEdit.WrapAnywhere
                    font.family: Theme.mono; font.pixelSize: 11
                    color: Theme.fg2
                    selectionColor: Theme.accent; selectedTextColor: Theme.accentFg
                    background: null; padding: 0
                    // Follow new output while the view is at the bottom; stop once the user scrolls up.
                    property bool follow: true
                    function toEnd() { scroll.ScrollBar.vertical.position = Math.max(0, 1 - scroll.ScrollBar.vertical.size) }
                    Connections {
                        target: scroll.ScrollBar.vertical
                        function onPositionChanged() { area.follow = scroll.ScrollBar.vertical.position + scroll.ScrollBar.vertical.size >= 0.98 }
                    }
                    function copySelection() { sys.copyText(area.selectedText.split(String.fromCharCode(0x2029)).join("\n")) }   // plain text, no colour markup
                    Keys.onPressed: event => { if (event.matches(StandardKey.Copy)) { copySelection(); event.accepted = true } }
                    MouseArea {
                        anchors.fill: parent
                        acceptedButtons: Qt.RightButton
                        onClicked: mouse => { menu.x = mouse.x; menu.y = mouse.y; menu.open() }
                    }
                    Menu {
                        id: menu
                        background: Rectangle { implicitWidth: 140; color: Theme.panel; radius: 12; border.width: 1; border.color: Theme.line }
                        delegate: MenuItem {
                            id: mi
                            implicitHeight: 32
                            contentItem: Label { text: mi.text; color: mi.enabled ? Theme.fg : Theme.fg3; font.pixelSize: 13; leftPadding: 8; verticalAlignment: Text.AlignVCenter }
                            background: Rectangle { color: mi.highlighted ? Theme.panel2 : "transparent"; radius: 8 }
                        }
                        Action { text: "复制"; enabled: area.selectedText !== ""; onTriggered: area.copySelection() }
                        Action { text: "全选"; onTriggered: area.selectAll() }
                        MenuSeparator { contentItem: Rectangle { implicitHeight: 1; color: Theme.line } }
                        Action { text: "清空"; onTriggered: App.logs = [] }
                    }
                }
            }
            // Right-click on the empty part of the box opens the same menu.
            MouseArea { anchors.fill: parent; z: -1; acceptedButtons: Qt.RightButton; onClicked: mouse => { menu.x = mouse.x - 10; menu.y = mouse.y - 10; menu.open() } }
        }
    }
}
