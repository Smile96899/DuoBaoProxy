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
    padding: 20
    background: Rectangle { color: Theme.panel; radius: 12; border.width: 1; border.color: Theme.line }

    function openWith(text) { ta.text = text || ""; open() }

    ColumnLayout {
        width: parent.width
        spacing: 12
        Label { text: "导入节点"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
        Label { text: "粘贴分享链接、订阅内容或 Clash 配置，一行一个。格式自动识别。"; color: Theme.fg2; font.pixelSize: 12; wrapMode: Text.Wrap; Layout.fillWidth: true }
        Rectangle {
            Layout.fillWidth: true; implicitHeight: 180
            radius: Theme.radius; color: Theme.bg; border.width: 1; border.color: Theme.line
            ScrollView { anchors.fill: parent; anchors.margins: 8
                TextArea { id: ta; color: Theme.fg; font.family: Theme.mono; font.pixelSize: 12; background: null; wrapMode: TextEdit.WrapAnywhere; placeholderText: "vmess:// ss:// vless:// trojan:// hy2:// tuic:// hproxy:// …" }
            }
        }
        RowLayout {
            spacing: 10
            Label { text: "加入分组"; color: Theme.fg2; font.pixelSize: 12 }
            ComboBox { id: groupBox; model: store.groups.map(g => g.name); Layout.fillWidth: true }
            Button { text: "从剪贴板"; flat: true; onClicked: ta.text = sys.clipboardText() }
        }
        RowLayout {
            Item { Layout.fillWidth: true }
            Button { text: "取消"; flat: true; onClicked: root.close() }
            Button { text: "导入"; highlighted: true; enabled: ta.text.trim().length > 0
                onClicked: { App.importText(ta.text, groupBox.currentText); root.close() } }
        }
    }
}
