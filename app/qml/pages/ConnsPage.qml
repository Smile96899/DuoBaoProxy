import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Connection list. The core does not expose connections yet; this page
// shows what is known and says so plainly for cores that never will.
Item {
    id: root
    ColumnLayout {
        anchors.fill: parent
        spacing: 0
        RowLayout {
            Layout.fillWidth: true; Layout.margins: 12; Layout.leftMargin: 18; Layout.rightMargin: 18
            Label { text: "连接"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
            Item { Layout.fillWidth: true }
        }
        Rectangle { Layout.fillWidth: true; height: 1; color: Theme.line }
        Item {
            Layout.fillWidth: true; Layout.fillHeight: true
            Label {
                anchors.centerIn: parent
                width: parent.width - 80
                horizontalAlignment: Text.AlignHCenter; wrapMode: Text.Wrap
                color: Theme.fg3; font.pixelSize: 13
                text: App.coreId === "xray" ? "xray 内核不提供连接列表。切换到 sing-box 查看。"
                    : App.coreIsVpn ? "系统 VPN 由操作系统管理，没有连接列表。"
                    : !App.connected ? "未连接。"
                    : "连接列表接口尚未实现。"
            }
        }
    }
}
