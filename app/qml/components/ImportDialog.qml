import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Paste anything: links, a subscription blob, Clash YAML. The core detects
// the format; the user only picks the target group.
Popup {
    id: root
    modal: true
    anchors.centerIn: Overlay.overlay
    width: Math.min(560, Overlay.overlay.width - 32)
    padding: 24
    background: Rectangle { color: Theme.panel; radius: 28; border.width: 1; border.color: Theme.line }

    // group: preselect the target group (right-click "导入节点" on a group).
    function openWith(text, group) {
        ta.text = text || ""
        if (group) groupBox.current = group
        else if (!store.groups.some(g => g.name === groupBox.current)) groupBox.current = store.groups.length ? store.groups[0].name : "默认"
        open()
    }

    ColumnLayout {
        width: parent.width
        spacing: 12
        Label { text: "导入节点"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
        Label { text: "粘贴分享链接、订阅内容或 Clash 配置，一行一个。格式自动识别。"; color: Theme.fg2; font.pixelSize: 12; wrapMode: Text.Wrap; Layout.fillWidth: true }
        Rectangle {
            Layout.fillWidth: true; implicitHeight: 180
            radius: 16; color: Theme.bg; border.width: 1; border.color: Theme.line
            ScrollView { anchors.fill: parent; anchors.margins: 8
                TextArea { id: ta; color: Theme.fg; font.family: Theme.mono; font.pixelSize: 12; background: null; wrapMode: TextEdit.WrapAnywhere; placeholderText: "vmess:// ss:// vless:// trojan:// hy2:// tuic:// hproxy:// …" }
            }
        }
        RowLayout {
            spacing: 10
            Label { text: "加入分组"; color: Theme.fg2; font.pixelSize: 12 }
            Dropdown { id: groupBox; model: store.groups.map(g => ({v: g.name, l: g.name})); current: "默认"; onPicked: v => current = v; Layout.fillWidth: true }
            PillButton { text: "从剪贴板"; flat: true; onClicked: ta.text = sys.clipboardText() }
        }
        RowLayout {
            Item { Layout.fillWidth: true }
            PillButton { text: "取消"; flat: true; onClicked: root.close() }
            PillButton { text: "导入"; highlighted: true; enabled: ta.text.trim().length > 0
                onClicked: { App.importText(ta.text, groupBox.current); root.close() } }
        }
    }
}
