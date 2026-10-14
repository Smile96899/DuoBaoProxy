pragma Singleton
import QtQuick

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
    property int latency: 0
    property real upRate: 0
    property real downRate: 0
    property real upTotal: 0
    property real downTotal: 0
    property var logs: []
    property var statsTags: []
    property var pendingTests: ({})
    property int nextReq: 1

    readonly property var coreInfo: cores.find(c => c.id === coreId) || {id: coreId, kind: "proxy", protocols: [], version: ""}
    readonly property bool coreHasTun: coreId === "sing-box"
    readonly property bool coreIsVpn: coreInfo.kind === "vpn"
    readonly property var selected: store.node(selectedId)
    readonly property var selectedNode: selected.node || null

    function supports(n) { return n && coreInfo.protocols.indexOf(n.protocol) >= 0 }
    function nodeName(n) { return n ? (n.name && n.name.length ? n.name : n.server + ":" + n.port) : "未选择节点" }

    function log(line) {
        var l = logs.slice(); l.push(line); if (l.length > 500) l.shift(); logs = l
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
        var mode = store.ui.proxy_mode || "rule"
        if (mode === "global") {
            s.routing.proxy_domain = []; s.routing.direct_domain = []; s.routing.proxy_ip = []; s.routing.direct_ip = []
            s.routing.custom_rules = []; s.routing.final = "proxy"
        } else if (mode === "direct") {
            s.routing.final = "bypass"
        }
        s.tun.enabled = (store.ui.mode === "tun") && coreHasTun
        return s
    }

    // ---- connect ----------------------------------------------------------
    function connect() {
        if (connected || busy) return
        if (selectedId < 0) { statusText = "先选一个节点"; return }
        if (!supports(selectedNode)) { statusText = coreId + " 不支持 " + selectedNode.protocol; return }
        busy = true
        statusText = "正在连接…"
        var req = nextReq++
        pendingTests[req] = {kind: "start"}
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
    function testNode(id) {
        var n = store.node(id)
        if (!n.node || !supports(n.node)) return
        var req = nextReq++
        pendingTests[req] = {kind: "render-test", id: id}
        store.updateNode(id, {lat: 0})
        core.renderConfig(req, coreId, [store.nodeJson(id)], JSON.stringify(renderSettings()), 1)
    }
    function testAll() {
        for (var i = 0; i < store.nodes.length; i++) testNode(store.nodes[i].id)
    }

    // ---- import -----------------------------------------------------------
    property string importGroup: "默认"
    function importText(text, group) { importGroup = group || "默认"; core.importNodes(text) }

    // ---- wiring -----------------------------------------------------------
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
            var p = app.pendingTests[req]; delete app.pendingTests[req]
            if (!p) return
            if (error !== "") {
                if (p.kind === "start") { app.busy = false; app.statusText = error }
                else store.updateNode(p.id, {lat: -1})
                app.log("render: " + error)
                return
            }
            if (p.kind === "start") {
                app.statsTags = tags
                core.startCore(app.coreId, config, tags, sys.appDir())
            } else {
                var req2 = app.nextReq++
                app.pendingTests[req2] = {kind: "test", id: p.id}
                core.urlTest(req2, app.coreId, config, store.ui.test_url || "http://cp.cloudflare.com/", 8000)
            }
        }
        function onStarted(error) {
            app.busy = false
            if (error !== "") { app.statusText = error; return }
            app.statusText = app.coreIsVpn ? "系统 VPN 已接入" : "已连接"
            if (store.ui.mode === "system" && !app.coreIsVpn)
                sys.setSystemProxy(true, store.settings.inbound.address || "127.0.0.1", store.settings.inbound.port || 2080)
            if (!app.coreIsVpn) core.watchTraffic(app.statsTags)
            sys.setTrayState(true, sys.platformName + " · " + app.nodeName(app.selectedNode))
            app.testNode(app.selectedId)
        }
        function onStopped(error) {
            app.busy = false
            app.statusText = error !== "" ? error : "未连接"
            app.latency = 0; app.upRate = 0; app.downRate = 0
            if (sys.systemProxy) sys.setSystemProxy(false, "", 0)
            sys.setTrayState(false, sys.platformName)
            if (app.pendingTests["reconnect"]) { delete app.pendingTests["reconnect"]; app.connect() }
        }
        function onTested(req, ms, error) {
            var p = app.pendingTests[req]; delete app.pendingTests[req]
            if (!p) return
            var lat = error !== "" ? -1 : Math.max(1, ms)
            store.updateNode(p.id, {lat: lat})
            if (p.id === app.selectedId) app.latency = lat
        }
        function onTraffic(up, down, upT, downT) { app.upRate = up; app.downRate = down; app.upTotal = upT; app.downTotal = downT }
        function onImported(nodes, errors) {
            var n = store.addNodes(nodes, app.importGroup)
            app.log("导入 " + n + " 个节点" + (errors.length ? "，" + errors.length + " 行失败" : ""))
            if (app.selectedId < 0 && store.nodes.length > 0) { app.selectedId = store.nodes[0].id; store.setUi("selected", app.selectedId) }
            for (var i = 0; i < errors.length; i++) app.log("  " + errors[i])
        }
    }
    property Connections sysConn: Connections {
        target: sys
        function onTrayToggleRequested() { app.toggle() }
    }
}
