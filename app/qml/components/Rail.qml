import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Landscape left rail: mark, collapsed core picker, navigation.
Rectangle {
    id: root
    property string nav: "nodes"
    signal navigate(string nav)
    width: 64
    color: Theme.railPanel2

    ColumnLayout {
        anchors.fill: parent
        anchors.topMargin: 12; anchors.bottomMargin: 12
        spacing: 4

        Mark { Layout.alignment: Qt.AlignHCenter; size: 30; look: App.look }
        Item { height: 6 }

        // Core picker: the options float out beside the button.
        Rectangle {
            Layout.alignment: Qt.AlignHCenter
            width: 52; height: 32; radius: 16
            color: corePick.opened ? Theme.railPanel : (coreMa.containsMouse ? Theme.railBg : Theme.railPanel)
            border.width: corePick.opened ? 1 : 0; border.color: Theme.railLine
            Label {
                anchors.centerIn: parent
                text: App.coreId === "sing-box" ? "sing\nbox" : App.coreId
                horizontalAlignment: Text.AlignHCenter
                font.family: Theme.mono; font.pixelSize: 10; lineHeight: .9
                color: Theme.railFg
            }
            MouseArea { id: coreMa; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: corePick.open() }
            PickPopup {
                id: corePick
                x: parent.width + 10; y: 0
                itemWidth: 170
                model: App.cores.map(c => ({v: c.id, l: c.id}))
                current: App.coreId
                onPicked: v => App.setCore(v)
            }
        }
        Rectangle { Layout.alignment: Qt.AlignHCenter; width: 36; height: 1; color: Theme.railLine; Layout.topMargin: 4; Layout.bottomMargin: 4 }

        Item { Layout.fillHeight: true }
    }

    // Navigation: one vertical segmented control, settings included,
    // centred on the rail's full height.
    Rectangle {
        anchors.centerIn: parent
        width: 52; height: navCol.implicitHeight + 4; radius: 26
        color: Theme.railBg
        Column {
            id: navCol
            anchors.centerIn: parent
            Repeater {
                model: {
                    var items = [{v: "nodes", l: "节点"}, {v: "groups", l: "分组"}]
                    items.push({v: "routes", l: "路由"}, {v: "logs", l: "日志"}, {v: "settings", l: "设置"})
                    return items
                }
                Rectangle {
                    width: 48; height: 42; radius: 21
                    color: root.nav === modelData.v ? Theme.railPanel : "transparent"
                    Label { anchors.centerIn: parent; text: modelData.l; font.pixelSize: 12; color: root.nav === modelData.v ? Theme.railFg : Theme.railFg2 }
                    MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: root.navigate(modelData.v) }
                }
            }
        }
    }
    Label {
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom; anchors.bottomMargin: 14
        text: "开发者"; font.pixelSize: 12
        color: devMa.containsMouse ? Theme.railFg : Theme.railFg3
        MouseArea { id: devMa; anchors.fill: parent; anchors.margins: -6; hoverEnabled: true; cursorShape: Qt.PointingHandCursor
            onClicked: sys.openUrl("https://t.me/DuoBaoToolsBot") }
    }
    Rectangle { anchors.right: parent.right; width: 1; height: parent.height; color: Theme.railLine }
}
