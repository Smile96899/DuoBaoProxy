import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// Groups and subscriptions. Subscription fetching lives in the GUI for
// now: download, then hand the body to the core's importer.
Item {
    id: root
    property bool compact: false

    function countFor(name) { return store.nodes.filter(e => e.group === name).length }

    function updateGroup(g) {
        if (!g.sub_url) return
        var xhr = new XMLHttpRequest()
        xhr.open("GET", g.sub_url)
        xhr.setRequestHeader("User-Agent", "DuoBaoProxy/" + Qt.application.version)
        xhr.onreadystatechange = function() {
            if (xhr.readyState !== XMLHttpRequest.DONE) return
            if (xhr.status !== 200) { App.log("订阅 " + g.name + " 更新失败：HTTP " + xhr.status); return }
            var info = xhr.getResponseHeader("subscription-userinfo") || ""
            var patch = {updated: new Date().toISOString()}
            info.split(";").forEach(function(kv) {
                var p = kv.trim().split("=")
                if (p.length === 2) patch[p[0]] = Number(p[1])
            })
            store.updateGroup(g.name, patch)
            store.removeGroupNodes(g.name)
            App.importText(xhr.responseText, g.name)
        }
        xhr.send()
    }

    Popup {
        id: editor
        property var group: ({})
        modal: true; anchors.centerIn: Overlay.overlay; width: Math.min(420, Overlay.overlay.width - 32); padding: 24
        background: Rectangle { color: Theme.panel; radius: 28; border.width: 1; border.color: Theme.line }
        ColumnLayout {
            width: parent.width; spacing: 12
            Label { text: editor.group.name ? "编辑分组" : "添加分组"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
            Input { id: nameField; placeholderText: "名称"; text: editor.group.name || ""; Layout.fillWidth: true }
            Input { id: urlField; placeholderText: "订阅链接（留空则为手动分组）"; text: editor.group.sub_url || ""; Layout.fillWidth: true }
            RowLayout {
                Label { text: "自动更新间隔（分钟，0 关闭）"; color: Theme.fg2; font.pixelSize: 12; wrapMode: Text.Wrap; Layout.fillWidth: true }
                Input { id: intervalBox; Layout.preferredWidth: 90; text: editor.group.sub_interval_min || 0; validator: IntValidator { bottom: 0; top: 1440 } font.family: Theme.mono; horizontalAlignment: Text.AlignHCenter }
            }
            RowLayout {
                PillButton { visible: !!editor.group.name; text: "删除分组"; flat: true; onClicked: { store.removeGroup(editor.group.name); editor.close() } }
                Item { Layout.fillWidth: true }
                PillButton { text: "取消"; flat: true; onClicked: editor.close() }
                PillButton { text: "保存"; highlighted: true; enabled: nameField.text.trim().length > 0
                    onClicked: {
                        var patch = {name: nameField.text.trim(), sub_url: urlField.text.trim(), sub_interval_min: Number(intervalBox.text) || 0}
                        if (editor.group.name) store.updateGroup(editor.group.name, patch); else store.addGroup(patch)
                        editor.close()
                    } }
            }
        }
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0
        RowLayout {
            Layout.fillWidth: true; Layout.margins: 12; Layout.leftMargin: 18; Layout.rightMargin: 18; spacing: 10
            Label { text: "分组与订阅"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
            Item { Layout.fillWidth: true }
            PillButton { text: "全部更新"; flat: true; onClicked: store.groups.forEach(g => root.updateGroup(g)) }
            RoundAdd { onClicked: { editor.group = {}; editor.open() } }
        }
        Rectangle { visible: !compact; Layout.fillWidth: true; height: 1; color: Theme.line }
        ListView {
            id: list
            Layout.fillWidth: true; Layout.fillHeight: true; clip: true
            leftMargin: 10; rightMargin: 10; topMargin: 6
            model: store.groups
            spacing: 2
            // Rows are reordered by dragging the handle; the move is applied on release.
            delegate: Rectangle {
                id: row
                readonly property int idx: index
                property real startY: 0
                width: ListView.view.width - 20; height: Math.max(64, groupRow.implicitHeight + 16); radius: Theme.radius
                color: handle.drag.active ? Theme.panel : (h.hovered ? Theme.panel2 : "transparent")
                border.width: handle.drag.active ? 1 : 0; border.color: Theme.line
                z: handle.drag.active ? 2 : 0
                HoverHandler { id: h }
                RowLayout {
                    id: groupRow
                    anchors.fill: parent; anchors.leftMargin: 10; anchors.rightMargin: 14; spacing: 12
                    Label {
                        text: "\u2261"; color: handle.containsMouse || handle.drag.active ? Theme.fg : Theme.fg3; font.pixelSize: 18
                        Layout.preferredWidth: 20; horizontalAlignment: Text.AlignHCenter
                        MouseArea {
                            id: handle
                            anchors.fill: parent; anchors.margins: -8
                            hoverEnabled: true; cursorShape: Qt.SizeVerCursor
                            drag.target: row; drag.axis: Drag.YAxis
                            onPressed: row.startY = row.y
                            onReleased: {
                                // The dragged row sits under the cursor itself, so pick the
                                // other row whose centre is nearest; staying put keeps index.
                                var cy = row.y + row.height / 2
                                var to = index, best = Math.abs(row.startY + row.height / 2 - cy)
                                var kids = list.contentItem.children
                                for (var i = 0; i < kids.length; i++) {
                                    var k = kids[i]
                                    if (k === row || k.idx === undefined) continue
                                    var d = Math.abs(k.y + k.height / 2 - cy)
                                    if (d < best) { best = d; to = k.idx }
                                }
                                if (to !== index) store.moveGroup(index, to)
                                else row.y = row.startY
                            }
                        }
                    }
                    ColumnLayout {
                        Layout.fillWidth: true; spacing: 3
                        // Name on its own line so it is never squeezed by the buttons; details below.
                        Label { text: modelData.name; color: Theme.fg; font.weight: Font.Medium; elide: Text.ElideRight; Layout.fillWidth: true }
                        Label {
                            color: Theme.fg2; font.pixelSize: 12; wrapMode: Text.Wrap; maximumLineCount: 2; elide: Text.ElideRight; Layout.fillWidth: true
                            text: root.countFor(modelData.name) + " 个节点"
                                + (modelData.sub_url
                                   ? ((modelData.total ? " · 已用 " + Theme.bytes((modelData.upload || 0) + (modelData.download || 0)) + " / " + Theme.bytes(modelData.total) : "")
                                      + (modelData.expire ? " · " + new Date(modelData.expire * 1000).toLocaleDateString(Qt.locale(), "yyyy-MM-dd") + " 到期" : "")
                                      + (modelData.updated ? " · 更新于 " + new Date(modelData.updated).toLocaleString(Qt.locale(), "MM-dd HH:mm") : " · 未更新"))
                                   : "")
                        }
                        Rectangle {
                            visible: !!modelData.total; Layout.fillWidth: true; height: 4; radius: 2; color: Theme.panel2
                            Rectangle { height: parent.height; radius: 2; color: Theme.fg2
                                width: parent.width * Math.min(1, ((modelData.upload || 0) + (modelData.download || 0)) / (modelData.total || 1)) }
                        }
                    }
                    PillButton { text: "编辑"; flat: true; onClicked: { editor.group = modelData; editor.open() } }
                    PillButton { visible: !!modelData.sub_url; text: "更新"; onClicked: root.updateGroup(modelData) }
                }
            }
        }
    }

    // Auto update timer: once a minute, update groups whose interval elapsed.
    Timer {
        interval: 60000; running: true; repeat: true
        onTriggered: store.groups.forEach(function(g) {
            if (!g.sub_url || !g.sub_interval_min) return
            var last = g.updated ? new Date(g.updated).getTime() : 0
            if (Date.now() - last > g.sub_interval_min * 60000) root.updateGroup(g)
        })
    }
}
