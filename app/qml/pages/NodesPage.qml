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
    // Multi-selection (Ctrl+click, 全选) and the nodes the open menu acts on.
    property var picked: ({})
    readonly property var pickedIds: Object.keys(picked).filter(k => picked[k]).map(Number)
    property var menuIds: []
    property string menuGroup: ""       // group of the node that was right-clicked
    readonly property var menuNode: menuIds.length === 1 ? store.node(menuIds[0]) : ({})

    function shownIds() {
        var ids = []
        grouped.forEach(g => g.items.forEach(e => ids.push(e.id)))
        return ids
    }
    function selectAll() { var p = {}; shownIds().forEach(id => p[id] = true); picked = p }
    // Adds every shown node of one group to the selection.
    function selectGroupNodes(group) {
        clearPickedGroups()
        var p = Object.assign({}, picked)
        ;(itemsByGroup[group] || []).forEach(e => p[e.id] = true)
        picked = p
    }
    function clearPicked() { picked = {} }
    property int anchorId: -1           // where a Shift+click range starts
    function rowClicked(id, modifiers) {
        clearPickedGroups()
        if (modifiers & Qt.ShiftModifier && anchorId >= 0) {
            var ids = shownIds(), a = ids.indexOf(anchorId), b = ids.indexOf(id)
            if (a >= 0 && b >= 0) {
                var p = {}
                ids.slice(Math.min(a, b), Math.max(a, b) + 1).forEach(i => p[i] = true)
                picked = p
                return
            }
        }
        anchorId = id
        // Ctrl+click, or any click while picking, adds / removes this node.
        if (modifiers & Qt.ControlModifier || pickedIds.length > 0) { togglePicked(id); return }
        App.selectNode(id)
    }
    function togglePicked(id) { clearPickedGroups(); var q = Object.assign({}, picked); q[id] = !q[id]; picked = q }
    function openMenu(id, row, x, y) {
        menuGroup = store.node(id).group || ""
        clearPickedGroups()
        // Right-click inside a multi-selection acts on all of it, otherwise on this node.
        if (picked[id] && pickedIds.length > 1) menuIds = pickedIds
        else menuIds = [id]
        var p = row.mapToItem(root, x, y)
        nodeMenu.popup(root, p.x, p.y)
    }
    function removeMenuNodes() {
        if (menuIds.length > 1) { confirmDelete.open(); return }
        App.removeNodes(menuIds)
    }

    // Group multi-selection (Ctrl/Shift+click on headers) and the groups the group menu acts on.
    property var pickedGroups: ({})
    readonly property var pickedGroupNames: grouped.map(g => g.name).filter(n => pickedGroups[n])
    property var menuGroups: []
    property string groupAnchor: ""
    function clearPickedGroups() { pickedGroups = {} }
    function selectAllGroups() { clearPicked(); var p = {}; grouped.forEach(g => p[g.name] = true); pickedGroups = p }
    function toggleCollapsed(name) { var c = Object.assign({}, collapsed); c[name] = !c[name]; collapsed = c }
    function headerClicked(name, modifiers) {
        var names = grouped.map(g => g.name)
        if (modifiers & Qt.ShiftModifier && groupAnchor !== "" && names.indexOf(groupAnchor) >= 0) {
            clearPicked()
            var a = names.indexOf(groupAnchor), b = names.indexOf(name), p = {}
            names.slice(Math.min(a, b), Math.max(a, b) + 1).forEach(n => p[n] = true)
            pickedGroups = p
            return
        }
        groupAnchor = name
        // Ctrl+click, or any click while picking groups, adds / removes this group.
        if (modifiers & Qt.ControlModifier || pickedGroupNames.length > 0) { togglePickedGroup(name); return }
        toggleCollapsed(name)
    }
    function togglePickedGroup(name) { clearPicked(); var q = Object.assign({}, pickedGroups); q[name] = !q[name]; pickedGroups = q }
    function openGroupMenu(name, header, x, y) {
        clearPicked()
        if (pickedGroups[name] && pickedGroupNames.length > 1) menuGroups = pickedGroupNames
        else menuGroups = [name]
        var p = header.mapToItem(root, x, y)
        groupMenu.popup(root, p.x, p.y)
    }

    Shortcut { sequence: StandardKey.SelectAll; onActivated: root.selectAll() }
    Shortcut { sequence: "Esc"; enabled: root.pickedIds.length > 0 || root.pickedGroupNames.length > 0; onActivated: { root.clearPicked(); root.clearPickedGroups() } }

    PillMenu {
        id: groupMenu
        readonly property bool many: root.menuGroups.length > 1
        // Groups that can actually be removed ("默认" stays).
        readonly property var removable: root.menuGroups.filter(n => n !== App.defaultGroup)
        function counted(t) { return many ? t + "（" + root.menuGroups.length + " 个）" : t }
        Action { text: "从剪贴板导入节点"; enabled: !groupMenu.many; onTriggered: App.importClipboard(root.menuGroups[0]) }
        Action { text: "新建分组"; onTriggered: newGroup.openFresh() }
        MenuSeparator { contentItem: Rectangle { implicitHeight: 1; color: Theme.line } }
        PillMenu {
            id: mergeMenu
            title: groupMenu.counted("移到其他分组并删除")
            Instantiator {
                model: store.groups
                delegate: Action {
                    text: modelData.name
                    enabled: root.menuGroups.indexOf(modelData.name) < 0
                    onTriggered: { App.mergeGroups(root.menuGroups, modelData.name); root.clearPickedGroups() }
                }
                onObjectAdded: (index, object) => mergeMenu.insertAction(index, object)
                onObjectRemoved: (index, object) => mergeMenu.removeAction(object)
            }
        }
        Action {
            text: root.pickedGroups[root.menuGroups[0]] ? "取消选择此分组" : "选择此分组"
            enabled: !groupMenu.many
            onTriggered: root.togglePickedGroup(root.menuGroups[0])
        }
        Action { text: "全选分组"; onTriggered: root.selectAllGroups() }
        MenuSeparator { contentItem: Rectangle { implicitHeight: 1; color: Theme.line } }
        Action {
            property bool danger: true
            text: groupMenu.counted("删除分组")
            enabled: groupMenu.removable.length > 0
            onTriggered: confirmGroupDelete.open()
        }
    }

    Popup {
        id: newGroup
        function openFresh() { nameField.text = ""; open(); nameField.forceActiveFocus() }
        function create() { if (App.createGroup(nameField.text)) close() }
        readonly property bool taken: store.groups.some(g => g.name === nameField.text.trim())
        modal: true; anchors.centerIn: Overlay.overlay; width: Math.min(360, Overlay.overlay.width - 32); padding: 24
        background: Rectangle { color: Theme.panel; radius: 28; border.width: 1; border.color: Theme.line }
        ColumnLayout {
            width: parent.width; spacing: 14
            Label { text: "新建分组"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
            TextField { id: nameField; placeholderText: "分组名称"; Layout.fillWidth: true; color: Theme.fg; leftPadding: 14; rightPadding: 14
                background: Rectangle { radius: height / 2; color: Theme.panel2 }
                onAccepted: newGroup.create() }
            Label { visible: newGroup.taken; text: "已有同名分组"; color: Theme.bad; font.pixelSize: 12 }
            RowLayout {
                Item { Layout.fillWidth: true }
                PillButton { text: "取消"; flat: true; onClicked: newGroup.close() }
                PillButton { text: "创建"; highlighted: true; enabled: nameField.text.trim().length > 0 && !newGroup.taken; onClicked: newGroup.create() }
            }
        }
    }

    Popup {
        id: confirmGroupDelete
        readonly property var names: groupMenu.removable
        modal: true; anchors.centerIn: Overlay.overlay; width: Math.min(380, Overlay.overlay.width - 32); padding: 24
        background: Rectangle { color: Theme.panel; radius: 28; border.width: 1; border.color: Theme.line }
        ColumnLayout {
            width: parent.width; spacing: 16
            Label { text: confirmGroupDelete.names.length > 1 ? "删除 " + confirmGroupDelete.names.length + " 个分组？" : "删除分组「" + (confirmGroupDelete.names[0] || "") + "」？"
                color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold; wrapMode: Text.Wrap; Layout.fillWidth: true }
            Label {
                text: "其中的 " + App.groupNodeCount(confirmGroupDelete.names) + " 个节点会一起删除，无法恢复。"
                    + (groupMenu.removable.length < root.menuGroups.length ? "「默认」分组不会删除。" : "")
                color: Theme.fg2; font.pixelSize: 12; wrapMode: Text.Wrap; Layout.fillWidth: true
            }
            RowLayout {
                Item { Layout.fillWidth: true }
                PillButton { text: "取消"; flat: true; onClicked: confirmGroupDelete.close() }
                PillButton { text: "删除"; highlighted: true; onClicked: { App.removeGroups(confirmGroupDelete.names); root.clearPickedGroups(); confirmGroupDelete.close() } }
            }
        }
    }

    PillMenu {
        id: nodeMenu
        readonly property bool many: root.menuIds.length > 1
        function counted(t) { return many ? t + "（" + root.menuIds.length + " 个）" : t }
        Action { text: "编辑"; enabled: !nodeMenu.many; onTriggered: nodeEditor.openFor(root.menuIds[0]) }
        Action {
            text: "选择并启动"
            enabled: !nodeMenu.many && App.supports(root.menuNode.node) && !(App.connected && App.selectedId === root.menuIds[0])
            onTriggered: App.connectNode(root.menuIds[0])
        }
        Action { text: "停止"; enabled: App.connected && !App.busy; onTriggered: App.disconnect() }
        MenuSeparator { contentItem: Rectangle { implicitHeight: 1; color: Theme.line } }
        PillMenu {
            title: nodeMenu.counted("测试")
            Action { text: "真连接延迟"; onTriggered: Tests.run(root.menuIds, ["url"]) }
            Action { text: "TCP 延迟"; onTriggered: Tests.run(root.menuIds, ["tcp"]) }
            Action { text: "ICMP Ping"; onTriggered: Tests.run(root.menuIds, ["icmp"]) }
            Action { text: "UDP 延迟"; onTriggered: Tests.run(root.menuIds, ["udp"]) }
            Action { text: "丢包 / 抖动"; onTriggered: Tests.run(root.menuIds, ["loss"]) }
            MenuSeparator { contentItem: Rectangle { implicitHeight: 1; color: Theme.line } }
            Action { text: "下载速度"; onTriggered: Tests.run(root.menuIds, ["down"]) }
            Action { text: "上传速度"; onTriggered: Tests.run(root.menuIds, ["up"]) }
            Action { text: "出口 IP 与地区"; onTriggered: Tests.run(root.menuIds, ["geo"]) }
            MenuSeparator { contentItem: Rectangle { implicitHeight: 1; color: Theme.line } }
            Action { text: "流媒体解锁"; onTriggered: Tests.run(root.menuIds, ["media"]) }
            Action { text: "AI 服务可用性"; onTriggered: Tests.run(root.menuIds, ["ai"]) }
            Action { text: "DNS 泄漏检测"; onTriggered: Tests.run(root.menuIds, ["dns"]) }
            MenuSeparator { contentItem: Rectangle { implicitHeight: 1; color: Theme.line } }
            Action { text: "全部测试"; onTriggered: Tests.run(root.menuIds, Tests.allKinds) }
        }
        PillMenu {
            title: nodeMenu.counted("分享")
            Action { text: "复制链接"; onTriggered: App.shareNodes(root.menuIds, "link") }
            Action { text: "显示二维码"; onTriggered: App.shareNodes(root.menuIds, "qr") }
        }
        PillMenu {
            id: moveMenu
            title: nodeMenu.counted("移动到分组")
            Instantiator {
                model: store.groups
                delegate: Action {
                    text: modelData.name
                    // Nothing to do when every target is already in this group.
                    enabled: root.menuIds.some(id => store.node(id).group !== modelData.name)
                    onTriggered: { App.moveNodes(root.menuIds, modelData.name); root.clearPicked() }
                }
                onObjectAdded: (index, object) => moveMenu.insertAction(index, object)
                onObjectRemoved: (index, object) => moveMenu.removeAction(object)
            }
        }
        Action {
            text: root.picked[root.menuIds[0]] ? "取消选择此节点" : "选择此节点"
            enabled: !nodeMenu.many
            onTriggered: root.togglePicked(root.menuIds[0])
        }
        Action { text: "全选本组"; onTriggered: root.selectGroupNodes(root.menuGroup) }
        MenuSeparator { contentItem: Rectangle { implicitHeight: 1; color: Theme.line } }
        Action {
            property bool danger: true
            text: nodeMenu.counted("删除")
            onTriggered: root.removeMenuNodes()
        }
    }

    Popup {
        id: confirmDelete
        modal: true; anchors.centerIn: Overlay.overlay; width: Math.min(360, Overlay.overlay.width - 32); padding: 24
        background: Rectangle { color: Theme.panel; radius: 28; border.width: 1; border.color: Theme.line }
        ColumnLayout {
            width: parent.width; spacing: 16
            Label { text: "删除 " + root.menuIds.length + " 个节点？"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
            Label { text: "删除后无法恢复。"; color: Theme.fg2; font.pixelSize: 12 }
            RowLayout {
                Item { Layout.fillWidth: true }
                PillButton { text: "取消"; flat: true; onClicked: confirmDelete.close() }
                PillButton { text: "删除"; highlighted: true; onClicked: { App.removeNodes(root.menuIds); root.clearPicked(); confirmDelete.close() } }
            }
        }
    }

    // Result columns: whatever kind was tested last (see Tests.shownKinds).
    readonly property var chosenColumns: Tests.shownKinds
    // The ones that fit beside a readable name; later columns drop off first.
    readonly property var visibleColumns: {
        var room = list.width - 20 - 28 - 20 - 170
        var out = []
        for (var i = 0; i < chosenColumns.length; i++) {
            var w = Tests.kind(chosenColumns[i]).w + 12
            if (room < w) break
            room -= w
            out.push(chosenColumns[i])
        }
        return out
    }
    readonly property string sortMode: store.ui.sort || "default"
    // Sorting by latency uses the first latency column shown, else 真连接.
    readonly property string sortLatencyKind: chosenColumns.find(k => Tests.latencyKinds.indexOf(k) >= 0) || "url"
    function sortItems(items) {
        if (sortMode === "default") return items
        var key
        switch (sortMode) {
        case "latency": key = e => Tests.sortValue(e, sortLatencyKind); break
        case "speed":   key = e => Tests.sortValue(e, "down"); break
        case "geo":     key = e => { var r = Tests.result(e, "geo"); return r && r.cc ? r.cc : "~" }; break
        default:        key = e => App.nodeName(e.node).toLowerCase()
        }
        return items.slice().sort((a, b) => { var x = key(a), y = key(b); return x < y ? -1 : (x > y ? 1 : 0) })
    }

    readonly property var grouped: {
        var map = {}, order = []
        var q = query.toLowerCase()
        // Every group shows, empty ones too, in the groups page's order; a search hides empty ones.
        if (!q.length) for (var j = 0; j < store.groups.length; j++) {
            var gn = store.groups[j].name
            if (!map[gn]) { map[gn] = []; order.push(gn) }
        }
        for (var i = 0; i < store.nodes.length; i++) {
            var e = store.nodes[i], n = e.node
            if (q.length && (App.nodeName(n) + n.server + e.group).toLowerCase().indexOf(q) < 0) continue
            if (!map[e.group]) { map[e.group] = []; order.push(e.group) }
            map[e.group].push(e)
        }
        return order.map(g => ({name: g, items: sortItems(map[g])}))
    }

    readonly property var itemsByGroup: { var m = {}; grouped.forEach(g => m[g.name] = g.items); return m }
    property var groupNames: []
    function syncGroupNames() {
        var n = grouped.map(g => g.name)
        if (JSON.stringify(n) !== JSON.stringify(groupNames)) groupNames = n
    }
    onGroupedChanged: syncGroupNames()
    Component.onCompleted: syncGroupNames()

    ImportDialog { id: importDialog }
    NodeEditor { id: nodeEditor }

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
                Layout.fillWidth: true; Layout.maximumWidth: 200; Layout.minimumWidth: 90
                onTextChanged: root.query = text
                color: Theme.fg
                leftPadding: 14; rightPadding: 14; background: Rectangle { radius: height / 2; color: Theme.panel2 }
            }
            Dropdown {
                model: [{v: "default", l: "默认排序"}, {v: "latency", l: "按延迟"}, {v: "speed", l: "按速度"}, {v: "geo", l: "按地区"}, {v: "name", l: "按名称"}]
                current: root.sortMode
                onPicked: v => store.setUi("sort", v)
            }
            Item { Layout.fillWidth: true }
            PillButton { text: "导入"; highlighted: true; onClicked: importDialog.openWith("") }
        }
        Rectangle { visible: !compact; Layout.fillWidth: true; height: 1; color: Theme.line }

        // Shown while several nodes are picked.
        RowLayout {
            visible: root.pickedIds.length > 0 || root.pickedGroupNames.length > 0
            Layout.fillWidth: true; Layout.leftMargin: 18; Layout.rightMargin: 18; Layout.topMargin: 8
            spacing: 10
            Label { text: (root.pickedGroupNames.length ? "已选 " + root.pickedGroupNames.length + " 个分组" : "已选 " + root.pickedIds.length + " 个节点") + "，单击继续选择，右键批量操作"; color: Theme.fg2; font.pixelSize: 12; elide: Text.ElideRight; Layout.fillWidth: true }
            PillButton { text: "取消选择"; flat: true; onClicked: { root.clearPicked(); root.clearPickedGroups() } }
        }

        // Column titles, lined up with the cells in NodeRow (list margin 10 + row padding 14);
        // × hides the results again.
        Item {
            visible: root.visibleColumns.length > 0
            Layout.fillWidth: true; implicitHeight: 26
            Label {
                anchors.left: parent.left; anchors.leftMargin: 24; anchors.bottom: parent.bottom
                text: "测试结果"; color: Theme.fg3; font.pixelSize: 11
            }
            Row {
                anchors.right: parent.right; anchors.rightMargin: 24; anchors.bottom: parent.bottom
                spacing: 12
                Repeater {
                    model: root.visibleColumns
                    Label { width: Tests.kind(modelData).w; horizontalAlignment: Text.AlignRight; text: Tests.kind(modelData).s; color: Theme.fg3; font.pixelSize: 11 }
                }
                Label {
                    text: "×"; color: hideMa.containsMouse ? Theme.fg : Theme.fg3; font.pixelSize: 14
                    MouseArea { id: hideMa; anchors.fill: parent; anchors.margins: -6; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: Tests.shownKinds = [] }
                }
            }
        }

        ListView {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            leftMargin: 10; rightMargin: 10; topMargin: 6; bottomMargin: 10
            model: root.groupNames
            spacing: 2
            delegate: Column {
                id: groupBox
                readonly property string groupName: modelData
                readonly property var groupItems: root.itemsByGroup[groupName] || []
                width: list.width - 20
                spacing: 2
                Rectangle {
                    id: header
                    width: parent.width; height: 32; radius: Theme.radius
                    color: gh.hovered || root.pickedGroups[groupBox.groupName] ? Theme.panel2 : "transparent"
                    border.width: root.pickedGroups[groupBox.groupName] ? 1.5 : 0
                    border.color: Theme.fg2
                    HoverHandler { id: gh }
                    MouseArea {
                        anchors.fill: parent
                        acceptedButtons: Qt.LeftButton | Qt.RightButton
                        onClicked: mouse => mouse.button === Qt.RightButton
                            ? root.openGroupMenu(groupBox.groupName, header, mouse.x, mouse.y)
                            : root.headerClicked(groupBox.groupName, mouse.modifiers)
                    }
                    RowLayout {
                        anchors.fill: parent; anchors.leftMargin: 12; anchors.rightMargin: 12; spacing: 6
                        CheckDot { visible: root.pickedGroupNames.length > 0; checked: !!root.pickedGroups[groupBox.groupName] }
                        Label { text: "▸"; color: Theme.fg3; font.pixelSize: 11; rotation: root.collapsed[groupBox.groupName] ? 0 : 90; Behavior on rotation { NumberAnimation { duration: 120 } } }
                        Label { text: groupBox.groupName; color: Theme.fg2; font.pixelSize: 12; font.weight: Font.DemiBold }
                        Label { text: groupBox.groupItems.length; color: Theme.fg3; font.pixelSize: 12 }
                        Item { Layout.fillWidth: true }
                    }
                }
                Repeater {
                    model: root.collapsed[groupBox.groupName] ? [] : groupBox.groupItems
                    NodeRow {
                        id: row
                        width: parent.width; entry: modelData; compact: root.compact
                        columns: root.visibleColumns
                        picked: !!root.picked[modelData.id]
                        selecting: root.pickedIds.length > 0
                        onClicked: modifiers => root.rowClicked(modelData.id, modifiers)
                        onMenuRequested: (x, y) => root.openMenu(modelData.id, row, x, y)
                        onEditRequested: { root.clearPicked(); nodeEditor.openFor(modelData.id) }
                    }
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
