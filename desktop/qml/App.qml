pragma Singleton
import QtQuick
import "LogZh.js" as LogZh

// Application state and the connect / test / import flows. Pages only
// bind to these properties and call these functions.
QtObject {
    id: app

    property var cores: []                       // from core.listCores()
    property string coreId: store.ui.core || "sing-box"
    property int selectedId: store.ui.selected !== undefined ? store.ui.selected : -1
    readonly property bool connected: core.runningCore !== ""
    property bool busy: false
    property string statusText: "未连接"
    property string page: "nodes"                // current page, shared by both layouts
    property int latency: 0
    property real upRate: 0
    property real downRate: 0
    property real upTotal: 0
    property real downTotal: 0
    property var logs: []
    property var statsTags: []
    property var pendingTests: ({})
    property int nextReq: 1
    property string appliedSettings: ""          // settings JSON the running core was started with

    readonly property var coreInfo: cores.find(c => c.id === coreId) || {id: coreId, protocols: [], version: ""}
    readonly property bool coreHasTun: coreId === "sing-box"
    readonly property var selected: { store.nodes; return store.node(selectedId) }   // re-read when any node changes
    readonly property var selectedNode: selected.node || null
    // Rabbit colour everywhere: off grey, running green, running in TUN red.
    readonly property bool tunActive: connected && coreHasTun && store.ui.mode === "tun"
    readonly property string look: !connected ? "off" : (tunActive ? "tun" : "on")
    onLookChanged: sys.setTrayLook(look)

    // What the tray's right-click menu lists.
    readonly property var trayModel: ({
        nodes: store.nodes.map(e => ({id: e.id, name: nodeName(e.node), group: e.group, lat: e.lat || 0, ok: supports(e.node)})),
        selected: selectedId,
        mode: store.ui.mode || "system",
        hasTun: coreHasTun
    })
    onTrayModelChanged: sys.setTrayModel(trayModel.nodes, trayModel.selected, trayModel.mode, trayModel.hasTun)
    Component.onCompleted: sys.setTrayModel(trayModel.nodes, trayModel.selected, trayModel.mode, trayModel.hasTun)

    function supports(n) { return n && coreInfo.protocols.indexOf(n.protocol) >= 0 }
    function nodeName(n) { return n ? (n.name && n.name.length ? n.name : n.server + ":" + n.port) : "未选择节点" }

    function log(line) {
        var l = logs.slice(); l.push(LogZh.translate(line.replace(/\x1b\[[0-9;]*m/g, ""))); if (l.length > 500) l.shift(); logs = l
    }

    function setCore(id) {
        if (id === coreId) return
        if (connected) disconnect()
        coreId = id
        store.setUi("core", id)
        if (!supports(selectedNode)) { selectedId = -1; store.setUi("selected", -1) }
    }
    function selectNode(id) {
        var n = store.node(id)
        if (!supports(n.node)) return
        var wasConnected = connected
        selectedId = id
        store.setUi("selected", id)
        if (wasConnected) reconnect()
    }
    function setLayout(l) { store.setUi("layout", l) }

    // ---- settings as the renderer wants them ------------------------------
    function renderSettings() {
        var s = JSON.parse(store.settingsJson())
        s.routing = s.routing || {}
        s.tun = s.tun || {}
        var mode = store.ui.proxy_mode || "global"
        if (mode === "global") {
            s.routing.proxy_domain = []; s.routing.direct_domain = []; s.routing.proxy_ip = []; s.routing.direct_ip = []
            s.routing.custom_rules = []; s.routing.final = "proxy"
        } else if (mode === "direct") {
            s.routing.final = "bypass"
        }
        // 分流方式: rule lists only, apps only, or both (default).
        var parts = routeParts
        if (parts === "rules") s.apps = []
        if (parts === "apps") {
            s.routing.proxy_domain = []; s.routing.direct_domain = []; s.routing.block_domain = []
            s.routing.proxy_ip = []; s.routing.direct_ip = []; s.routing.block_ip = []
            s.routing.custom_rules = []
        }
        s.tun.enabled = (store.ui.mode === "tun") && coreHasTun
        return s
    }
    readonly property string routeParts: store.ui.route_parts || "both"     // rules | apps | both
    function setRouteParts(p) { if (p === routeParts) return; store.setUi("route_parts", p); reconnect() }

    // ---- connect ----------------------------------------------------------
    function connect() {
        if (connected || busy) return
        if (selectedId < 0) { statusText = "先选一个节点"; return }
        if (!supports(selectedNode)) { statusText = coreId + " 不支持 " + selectedNode.protocol; return }
        busy = true
        statusText = "正在连接…"
        var req = nextReq++
        pendingTests[req] = {kind: "start"}
        appliedSettings = store.settingsJson()
        core.renderConfig(req, coreId, [store.nodeJson(selectedId)], JSON.stringify(renderSettings()), 0)
    }
    function disconnect() {
        if (!connected) return
        busy = true
        core.stopCore()
    }
    function reconnect() { if (connected) { pendingTests["reconnect"] = true; disconnect() } }
    function toggle() { connected ? disconnect() : connect() }

    // ---- tests ------------------------------------------------------------
    function testNode(id) { Tests.run([id], ["url"]) }
    // ---- node actions (right-click menu, detail column) ---------------------
    function connectNode(id) {
        selectNode(id)              // reconnects when already running
        if (selectedId === id && !connected) connect()
    }
    function testNodes(ids) { Tests.run(ids, ["url"]) }
    function moveNodes(ids, group) { ids.forEach(id => store.updateNode(id, {group: group})); log("已移动 " + ids.length + " 个节点到「" + group + "」") }
    function removeNodes(ids) {
        if (ids.indexOf(selectedId) >= 0) { selectedId = -1; store.setUi("selected", -1) }
        ids.forEach(id => store.removeNode(id))
        log("已删除 " + ids.length + " 个节点")
    }
    // ---- group actions (right-click on a group header) ----------------------
    // "默认" always stays: tray and clipboard imports land there.
    readonly property string defaultGroup: "默认"
    function createGroup(name) {
        name = name.trim()
        if (!name.length || store.groups.some(g => g.name === name)) return false
        store.addGroup({name: name})
        return true
    }
    function groupNodeCount(names) { return store.nodes.filter(e => names.indexOf(e.group) >= 0).length }
    function dropMissingSelection() { if (selectedId >= 0 && !store.node(selectedId).node) { selectedId = -1; store.setUi("selected", -1) } }
    function removeGroups(names) {
        names = names.filter(n => n !== defaultGroup)
        names.forEach(n => store.removeGroup(n))
        dropMissingSelection()
        log("已删除 " + names.length + " 个分组")
    }
    // Move every node of the given groups into target, then drop those groups.
    function mergeGroups(names, target) {
        var moved = 0
        names.filter(n => n !== target).forEach(n => {
            store.nodes.filter(e => e.group === n).forEach(e => { store.updateNode(e.id, {group: target}); moved++ })
            if (n !== defaultGroup) store.removeGroup(n)
        })
        log("已移动 " + moved + " 个节点到「" + target + "」")
    }

    // Share: links are fetched one at a time so they keep the list order, then
    // copied together ("link") or shown as QR codes ("qr") by the QR dialog.
    property var shareQueue: []
    property var shareItems: []         // [{name, link}]
    property string shareMode: "link"
    property var qrItems: []            // [{name, link, image}]
    function shareNodes(ids, mode) {
        shareMode = mode || "link"
        shareItems = []
        shareQueue = ids.slice()
        shareNext()
    }
    function shareNext() {
        if (shareQueue.length) { core.shareLink(store.nodeJson(shareQueue[0])); return }
        if (!shareItems.length) return
        if (shareMode === "qr") { sys.makeQrImages(shareItems.map(i => i.link)); return }
        sys.copyText(shareItems.map(i => i.link).join("\n"))
        log(shareItems.length > 1 ? "已复制 " + shareItems.length + " 条分享链接" : "已复制分享链接")
    }

    function testAll() { Tests.run(store.nodes.map(e => e.id), ["url"]) }

    // ---- import -----------------------------------------------------------
    property string importGroup: "默认"
    property bool importFromTray: false    // report the result as a tray message
    function importText(text, group) { importGroup = group || "默认"; core.importNodes(text) }
    // Everything on the clipboard (one link per line, or a subscription / Clash blob) into one group.
    function importClipboard(group) {
        var text = sys.clipboardText()
        if (!text || !text.trim().length) { log("剪贴板里没有可导入的内容"); return }
        importText(text, group)
    }
    function importFromTrayText(text) {
        if (!text || !text.trim().length) { sys.notify("导入", "没有可导入的内容"); return }
        importFromTray = true
        importText(text, "默认")
    }

    // ---- wiring -----------------------------------------------------------
    // Settings edits (app rules, rule lists, DNS, TUN…) restart the core so
    // they take effect; debounced so a burst of edits reconnects once.
    property Connections storeConn: Connections {
        target: store
        function onSettingsChanged() { if (app.connected) app.applyTimer.restart() }
    }
    property Timer applyTimer: Timer {
        interval: 800
        onTriggered: if (app.connected && store.settingsJson() !== app.appliedSettings) app.reconnect()
    }

    // Re-test the connected node so the detail column shows a live latency.
    property Timer latencyTimer: Timer {
        interval: 5000; repeat: true
        running: app.connected
        onTriggered: Tests.run([app.selectedId], ["url"], true)
    }

    property Connections coreConn: Connections {
        target: core
        function onReadyChanged() {
            if (!core.ready) return
            core.listCores()
            // A previous instance that was killed may have left the OS proxy on.
            var inb = store.settings.inbound || {}
            if (!app.connected && sys.systemProxyPointsTo(inb.address || "127.0.0.1", inb.port || 2080)) {
                sys.setSystemProxy(false, "", 0)
                app.log("已清除上次遗留的系统代理设置")
            }
        }
        function onCoresListed(list) { app.cores = list }
        function onLogLine(line) { app.log(line) }
        function onRendered(req, config, tags, error) {
            var p = app.pendingTests[req]
            if (!p || p.kind !== "start") return      // exports are handled by the routes page
            delete app.pendingTests[req]
            if (error !== "") { app.busy = false; app.statusText = error; app.log("生成配置失败：" + error); return }
            app.statsTags = tags
            core.startCore(app.coreId, config, tags, sys.appDir())
        }
        function onStarted(error) {
            app.busy = false
            if (error !== "") { app.statusText = error; return }
            app.statusText = "已连接"
            if (store.ui.mode === "system")
                sys.setSystemProxy(true, store.settings.inbound.address || "127.0.0.1", store.settings.inbound.port || 2080)
            core.watchTraffic(app.statsTags)
            sys.setTrayState(true, sys.platformName + " · " + app.nodeName(app.selectedNode))
            Tests.run([app.selectedId], ["url"], true)
        }
        function onStopped(error) {
            app.busy = false
            app.statusText = error !== "" ? error : "未连接"
            app.latency = 0; app.upRate = 0; app.downRate = 0
            if (sys.systemProxy) sys.setSystemProxy(false, "", 0)
            sys.setTrayState(false, sys.platformName)
            if (app.pendingTests["reconnect"]) { delete app.pendingTests["reconnect"]; app.connect() }
        }
        function onLinkReady(link, error) {
            if (!app.shareQueue.length) return
            var id = app.shareQueue.shift()
            if (error === "") app.shareItems.push({name: app.nodeName(store.node(id).node), link: link})
            else app.log("分享失败：" + error)
            app.shareNext()
        }
        function onTraffic(up, down, upT, downT) { app.upRate = up; app.downRate = down; app.upTotal = upT; app.downTotal = downT }
        function onImported(nodes, errors) {
            var n = store.addNodes(nodes, app.importGroup)
            var msg = "导入 " + n + " 个节点到「" + app.importGroup + "」" + (errors.length ? "，" + errors.length + " 行失败" : "")
            app.log(msg)
            if (app.importFromTray) { app.importFromTray = false; sys.notify("导入", msg) }
            if (app.selectedId < 0 && store.nodes.length > 0) { app.selectedId = store.nodes[0].id; store.setUi("selected", app.selectedId) }
            for (var i = 0; i < errors.length; i++) app.log("  " + errors[i])
        }
    }
    property Connections sysConn: Connections {
        target: sys
        function onTrayToggleRequested() { app.toggle() }
        function onTrayNodePicked(id) { app.selectNode(id) }
        function onTrayModePicked(mode) { if (mode === (store.ui.mode || "system")) return; store.setUi("mode", mode); app.reconnect() }
        function onTrayImportClipboardRequested() { app.importFromTrayText(sys.clipboardText()) }
        function onTrayScanRequested() { sys.scanScreenQr() }
        function onQrImagesReady(urls, error) {
            if (error !== "") { app.log(error); return }
            app.qrItems = app.shareItems.map((it, i) => ({name: it.name, link: it.link, image: urls[i] || ""}))
        }
        function onQrScanned(text, error) {
            if (error !== "") { app.log(error); sys.notify("扫描二维码", error); return }
            app.importFromTrayText(text)
        }
    }
}
