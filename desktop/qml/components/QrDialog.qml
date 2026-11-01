import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Share as QR: one code at a time, previous / next when several nodes are shared.
// items: [{name, link, image}]
Popup {
    id: root
    property var items: []
    property int index: 0
    readonly property var cur: items[index] || ({})
    readonly property bool many: items.length > 1
    modal: true
    anchors.centerIn: Overlay.overlay
    width: Math.min(340, Overlay.overlay.width - 32)
    padding: 24
    background: Rectangle { color: Theme.panel; radius: 28; border.width: 1; border.color: Theme.line }
    onItemsChanged: index = 0

    ColumnLayout {
        width: parent.width
        spacing: 12
        Label { text: "分享二维码"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
        Label { text: root.cur.name || ""; color: Theme.fg2; font.pixelSize: 13; elide: Text.ElideRight; Layout.fillWidth: true }
        // White card behind the code so it scans in the dark theme too.
        Rectangle {
            Layout.alignment: Qt.AlignHCenter
            Layout.preferredWidth: Math.min(240, root.availableWidth); Layout.preferredHeight: Layout.preferredWidth
            radius: 16; color: "white"
            Image {
                anchors.fill: parent; anchors.margins: 8
                source: root.cur.image || ""
                fillMode: Image.PreserveAspectFit
                smooth: false; cache: false
            }
        }
        Label { text: root.cur.link || ""; color: Theme.fg3; font.family: Theme.mono; font.pixelSize: 11; elide: Text.ElideMiddle; Layout.fillWidth: true }
        RowLayout {
            visible: root.many
            Layout.fillWidth: true
            PillButton { text: "上一个"; flat: true; enabled: root.index > 0; onClicked: root.index-- }
            Label { text: (root.index + 1) + " / " + root.items.length; color: Theme.fg2; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; Layout.fillWidth: true }
            PillButton { text: "下一个"; flat: true; enabled: root.index < root.items.length - 1; onClicked: root.index++ }
        }
        RowLayout {
            Layout.fillWidth: true
            Item { Layout.fillWidth: true }
            PillButton { text: "复制链接"; flat: true; onClicked: { sys.copyText(root.cur.link); App.log("已复制分享链接") } }
            PillButton { text: "关闭"; highlighted: true; onClicked: root.close() }
        }
    }
}
