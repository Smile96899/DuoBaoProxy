import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// Node list grouped by collapsible group headers.
Item {
    id: root
    property bool compact: false
    property string query: ""
    property var collapsed: ({})

    readonly property var grouped: {
        var map = {}, order = []
        var q = query.toLowerCase()
        for (var i = 0; i < store.nodes.length; i++) {
            var e = store.nodes[i], n = e.node
            if (q.length && (App.nodeName(n) + n.server + e.group).toLowerCase().indexOf(q) < 0) continue
            if (!map[e.group]) { map[e.group] = []; order.push(e.group) }
            map[e.group].push(e)
        }
        return order.map(g => ({name: g, items: map[g]}))
    }

    ImportDialog { id: importDialog }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.margins: compact ? 10 : 12
            Layout.leftMargin: 18; Layout.rightMargin: 18
            spacing: 10
            Label { text: "节点"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
            Label { text: store.nodes.length; color: Theme.fg2 }
            TextField {
                visible: !compact
                placeholderText: "搜索名称 / 地址"
                Layout.preferredWidth: 200
                onTextChanged: root.query = text
                color: Theme.fg
                background: Rectangle { radius: 6; color: Theme.panel2 }
            }
            Item { Layout.fillWidth: true }
            Button { text: compact ? "测速" : "全部测速"; flat: true; onClicked: App.testAll() }
            Button { text: "导入"; highlighted: true; onClicked: importDialog.openWith("") }
        }
        Rectangle { visible: !compact; Layout.fillWidth: true; height: 1; color: Theme.line }

        ListView {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            leftMargin: 10; rightMargin: 10; topMargin: 6; bottomMargin: 10
            model: root.grouped
            spacing: 2
            delegate: Column {
                width: list.width - 20
                spacing: 2
                Rectangle {
                    width: parent.width; height: 32; radius: Theme.radius
                    color: gh.hovered ? Theme.panel2 : "transparent"
                    HoverHandler { id: gh }
                    TapHandler { onTapped: { var c = Object.assign({}, root.collapsed); c[modelData.name] = !c[modelData.name]; root.collapsed = c } }
                    RowLayout {
                        anchors.fill: parent; anchors.leftMargin: 12; anchors.rightMargin: 12; spacing: 6
                        Label { text: "▸"; color: Theme.fg3; font.pixelSize: 11; rotation: root.collapsed[modelData.name] ? 0 : 90; Behavior on rotation { NumberAnimation { duration: 120 } } }
                        Label { text: modelData.name; color: Theme.fg2; font.pixelSize: 12; font.weight: Font.DemiBold }
                        Label { text: modelData.items.length; color: Theme.fg3; font.pixelSize: 12 }
                        Item { Layout.fillWidth: true }
                    }
                }
                Repeater {
                    model: root.collapsed[modelData.name] ? [] : modelData.items
                    NodeRow { width: parent.width; entry: modelData; compact: root.compact }
                }
            }
            Label {
                visible: store.nodes.length === 0
                anchors.centerIn: parent
                text: "还没有节点。点右上角「导入」粘贴链接或订阅。"
                color: Theme.fg3; font.pixelSize: 13
            }
        }
    }
}
