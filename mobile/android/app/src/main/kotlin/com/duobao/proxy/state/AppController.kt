package com.duobao.proxy.state

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import com.duobao.core.mobile.Mobile
import com.duobao.core.mobile.StartOptions
import com.duobao.proxy.core.CoreApi
import com.duobao.proxy.core.GeoData
import com.duobao.proxy.core.GoCore
import com.duobao.proxy.core.GoCoreApi
import com.duobao.proxy.core.LogRepository
import com.duobao.proxy.data.Defaults
import com.duobao.proxy.data.Group
import com.duobao.proxy.data.Store
import com.duobao.proxy.data.asInt
import com.duobao.proxy.data.asString
import com.duobao.proxy.data.jsonObjectOf
import com.duobao.proxy.tests.TestEngine
import com.duobao.proxy.tests.TestKinds
import com.duobao.proxy.vpn.DuoBaoVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Application state and the connect / test / import flows: the Kotlin port of `app/qml/App.qml`
 * (spec/gui-nodes.md §4). One per process ([instance]); screens observe the StateFlows and call
 * the functions, the VPN service, tile, boot receiver and notification drive it too.
 *
 * Threading: every state transition runs on one serial dispatcher (like the single QML thread);
 * Go calls go through [GoCore]. All `fun`s without `suspend` return immediately.
 *
 * Connect: [connect] checks the node, asks for VPN permission when needed ([vpnPermissionRequest]),
 * then starts [DuoBaoVpnService], which calls back [startEngine]: `PrepareSettings(settings,
 * ui.proxy_mode, ui.route_parts, tun = true)` → `RenderConfig(core, [node], settings, Full)` →
 * (xray: geo data) → `Start`. Reconnect is stop-then-start inside the running service.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppController private constructor(private val app: Application, val store: Store, private val core: CoreApi) {
    private val main = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + main)

    // ---- observable state -------------------------------------------------------------------

    private val _connected = MutableStateFlow(false)
    private val _busy = MutableStateFlow(false)
    private val _statusText = MutableStateFlow(STATUS_IDLE)
    private val _latency = MutableStateFlow(0)
    private val _traffic = MutableStateFlow(TrafficState())
    private val _session = MutableStateFlow<Session?>(null)
    private val _uiVisible = MutableStateFlow(false)
    private val _vpnPermission = MutableStateFlow<Intent?>(null)

    /** A tunnel is up (`core.runningCore !== ""`). */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** A start or stop is in progress. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Chinese status line: 未连接 / 正在连接… / 已连接 / 先选一个节点 / an error. */
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    /** Live latency of the connected node in ms (0 untested, -1 failed). */
    val latency: StateFlow<Int> = _latency.asStateFlow()

    /** Rates and totals of the exit outbound; rates drop to 0 on disconnect, totals stay. */
    val traffic: StateFlow<TrafficState> = _traffic.asStateFlow()

    /** What the running tunnel was started with; null when disconnected. */
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** Whether any activity of the app is started (live latency only runs then). */
    val uiVisible: StateFlow<Boolean> = _uiVisible.asStateFlow()

    /**
     * Non-null while the VPN permission dialog must be shown: the Activity launches this intent
     * (from `VpnService.prepare`) and reports back with [onVpnPermissionResult].
     */
    val vpnPermissionRequest: StateFlow<Intent?> = _vpnPermission.asStateFlow()

    /** `ui.core || "sing-box"`. */
    val coreId: StateFlow<String> = store.ui.map(::coreOf).stateIn(scope, SharingStarted.Eagerly, coreOf(store.ui.value))

    /** `ui.selected` (-1 none). */
    val selectedId: StateFlow<Int> = store.ui.map(::selectedOf).stateIn(scope, SharingStarted.Eagerly, selectedOf(store.ui.value))

    private fun coreOf(ui: JsonObject) = ui["core"].asString().ifEmpty { CORE_SINGBOX }
    private fun selectedOf(ui: JsonObject) = ui["selected"].asInt(-1)

    /** The selected engine, read from the Store (never lags behind a write). */
    fun currentCore(): String = coreOf(store.ui.value)

    /** The selected node id, read from the Store. */
    fun currentSelected(): Int = selectedOf(store.ui.value)

    /** Engines and their protocols. */
    val cores: StateFlow<List<GoCore.CoreInfo>> = GoCore.cores

    /** geoip.dat / geosite.dat for xray. */
    val geo = GeoData(app)

    /** The node test queue (results are written to the Store). */
    val tests = TestEngine(
        store = store,
        core = core,
        scope = CoroutineScope(SupervisorJob() + GoCore.pool),
        coreId = { currentCore() },
        supports = ::supports,
        renderSettings = { renderSettings() },
        onUrlResult = { id, lat -> if (id == currentSelected()) _latency.value = lat },
    )

    private var reconnectPending = false
    private var permissionPendingConnect = false
    private var trafficJob: Job? = null
    private var latencyJob: Job? = null
    private var applyJob: Job? = null
    private val meter = TrafficMeter()

    init {
        trackVisibility()
        watchSettings()
    }

    // ---- helpers --------------------------------------------------------------------------

    /** `App.supports(n)`: the selected engine carries the node's protocol. */
    fun supports(node: JsonObject?): Boolean {
        val p = node?.get("protocol").asString()
        return node != null && p.isNotEmpty() && GoCore.supports(currentCore(), p)
    }

    /** `App.nodeName(n)`: name, else `server:port`, else 未选择节点. */
    fun nodeName(node: JsonObject?): String {
        if (node == null || node.isEmpty()) return "未选择节点"
        return node["name"].asString().ifEmpty { node["server"].asString() + ":" + node["port"].asString() }
    }

    /** `App.log(line)`. */
    fun log(line: String) = LogRepository.log(line)

    /** `App.renderSettings()` for the mobile renderer (TUN always on). */
    suspend fun renderSettings(): String {
        val ui = store.ui.value
        return core.prepareSettings(store.settingsJson(), ui["proxy_mode"].asString(), ui["route_parts"].asString(), true)
    }

    // ---- selection / engine ----------------------------------------------------------------

    /** `App.setCore`: a change disconnects (never reconnects) and drops an unsupported selection. */
    fun setCore(id: String) = launch {
        if (id == currentCore()) return@launch
        if (_connected.value) disconnectNow()
        store.setUi("core", id)
        val sel = store.node(currentSelected())
        if (!supports(sel?.node)) store.setUi("selected", -1)
    }

    /** `App.selectNode`: ignored for unsupported nodes; reconnects when connected. */
    fun selectNode(id: Int) = launch { selectNow(id) }

    private suspend fun selectNow(id: Int) {
        val n = store.node(id) ?: return
        if (!supports(n.node)) return
        val wasConnected = _connected.value
        store.setUi("selected", id)
        _latency.value = n.lat
        if (wasConnected && _session.value?.nodeId != id) reconnectNow()
    }

    /** `App.connectNode`: select, and connect when not connected. */
    fun connectNode(id: Int) = launch {
        selectNow(id)
        if (currentSelected() == id && !_connected.value) connectNow(fromSystem = false)
    }

    // ---- connect / disconnect ----------------------------------------------------------------

    /** `App.connect()`. */
    fun connect() = launch { connectNow(fromSystem = false) }

    /**
     * Connect without UI (开机自动连接): never shows the permission dialog; without a granted VPN
     * permission it only sets the status.
     */
    fun connectFromSystem() = launch { connectNow(fromSystem = true) }

    /** `App.disconnect()`. */
    fun disconnect() = launch { disconnectNow() }

    /** `App.reconnect()`: only when connected; stop then start. */
    fun reconnect() = launch { reconnectNow() }

    /** `App.toggle()`. */
    fun toggle() = launch { if (_connected.value) disconnectNow() else connectNow(fromSystem = false) }

    /** The Activity's answer to [vpnPermissionRequest]. */
    fun onVpnPermissionResult(granted: Boolean) = launch {
        _vpnPermission.value = null
        if (!permissionPendingConnect) return@launch
        permissionPendingConnect = false
        if (granted && VpnService.prepare(app) == null) {
            startService()
        } else {
            _busy.value = false
            _statusText.value = "未获得 VPN 权限"
        }
    }

    private fun checkNode(): String? {
        val sel = currentSelected()
        if (sel < 0) return "先选一个节点"
        val n = store.node(sel)?.node
        if (n == null || n.isEmpty()) return "先选一个节点"
        if (!supports(n)) return currentCore() + " 不支持 " + n["protocol"].asString()
        return null
    }

    private suspend fun connectNow(fromSystem: Boolean) {
        if (_connected.value || _busy.value) return
        GoCore.awaitReady()
        checkNode()?.let { _statusText.value = it; return }
        _busy.value = true
        _statusText.value = STATUS_CONNECTING
        val prepare = VpnService.prepare(app)
        if (prepare != null) {
            if (fromSystem) {
                _busy.value = false
                _statusText.value = "需要先在应用内授予 VPN 权限"
                return
            }
            permissionPendingConnect = true
            _vpnPermission.value = prepare
            return
        }
        startService()
    }

    private fun startService() {
        try {
            app.startForegroundService(DuoBaoVpnService.connectIntent(app))
        } catch (e: Exception) {
            _busy.value = false
            _statusText.value = "无法启动 VPN 服务：" + (e.message ?: e.toString())
            log(_statusText.value)
        }
    }

    /**
     * Called by [DuoBaoVpnService] once it is in the foreground (after [connect], Always-on, or the
     * tile): renders and starts the engine with the service as `Host`. On failure the service is
     * told to stop. [system] marks starts the user did not initiate here (Always-on, boot).
     */
    fun startEngine(service: DuoBaoVpnService, system: Boolean) = launch {
        if (_connected.value) {
            if (_session.value != null) service.showConnected(_session.value!!.nodeName)
            return@launch
        }
        val err = checkNode()
        if (err != null) {
            _statusText.value = err
            _busy.value = false
            service.shutdown()
            return@launch
        }
        _busy.value = true
        _statusText.value = STATUS_CONNECTING
        val result = runCatching { startEngineNow(service) }
        result.exceptionOrNull()?.let { e ->
            if (e is CancellationException) throw e
            val msg = e.message ?: e.toString()
            _statusText.value = msg
            log("错误：$msg")
            withContext(GoCore.lifecycle) { runCatching { Mobile.stop() } }
            service.shutdown()
            _busy.value = false
            if (system) log("自动连接失败")
        }
    }

    private suspend fun startEngineNow(service: DuoBaoVpnService) {
        val id = currentSelected()
        val entry = store.node(id) ?: error("先选一个节点")
        val engine = currentCore()
        val settings = renderSettings()
        val rendered = try {
            core.renderConfig(engine, "[" + store.nodeJson(id) + "]", settings, CoreApi.MODE_FULL)
        } catch (e: Exception) {
            log("生成配置失败：" + e.message)
            throw e
        }
        if (engine == CORE_XRAY) {
            val need = geo.needed(rendered.config).filterNot(geo::has)
            if (need.isNotEmpty()) {
                _statusText.value = "正在下载规则数据…"
                geo.ensure(need)
                _statusText.value = STATUS_CONNECTING
            }
        }
        val name = nodeName(entry.node)
        service.prepareSession(name)
        GoCore.serial {
            Mobile.start(StartOptions().apply {
                coreID = engine
                config = rendered.config
                statsTagsJSON = Json.encodeToString(JsonArray.serializer(), JsonArray(rendered.statsTags.map(::JsonPrimitive)))
                frontConfig = rendered.frontConfig
            }, service)
        }
        onStarted(Session(id, name, engine, rendered.statsTags, settings, System.currentTimeMillis()), service)
    }

    private fun onStarted(s: Session, service: DuoBaoVpnService) {
        _session.value = s
        _connected.value = true
        _busy.value = false
        _statusText.value = STATUS_CONNECTED
        service.showConnected(s.nodeName)
        log("已连接：" + s.nodeName + "（" + s.coreId + "）")
        startPolling(s)
        // An immediate latency, like desktop's quiet test after start.
        scope.launch { liveLatencyOnce(s) }
    }

    private suspend fun disconnectNow(reason: String? = null) {
        if (!_connected.value && DuoBaoVpnService.instance == null) return
        _busy.value = true
        stopEngine(reason, keepService = false)
    }

    private suspend fun reconnectNow() {
        if (!_connected.value) return
        reconnectPending = true
        _busy.value = true
        stopEngine(null, keepService = true)
    }

    /** Stops the engine; [keepService] keeps the foreground service for an immediate restart. */
    private suspend fun stopEngine(reason: String?, keepService: Boolean) {
        stopPolling()
        val err = withContext(GoCore.lifecycle) { runCatching { Mobile.stop() }.exceptionOrNull() }
        err?.let { log("停止核心出错：" + it.message) }
        val service = DuoBaoVpnService.instance
        if (keepService && reconnectPending && service != null) {
            service.closeTun()
        } else {
            service?.shutdown()
        }
        onStopped(reason)
    }

    private suspend fun onStopped(reason: String?) {
        val wasConnected = _connected.value
        _connected.value = false
        _session.value = null
        _busy.value = false
        _statusText.value = reason ?: STATUS_IDLE
        _latency.value = 0
        _traffic.value = _traffic.value.copy(upRate = 0, downRate = 0)
        if (wasConnected) log(if (reason != null) "已断开：$reason" else "已断开")
        if (reconnectPending) {
            reconnectPending = false
            val service = DuoBaoVpnService.instance
            if (service != null) {
                _busy.value = true
                _statusText.value = STATUS_CONNECTING
                startEngine(service, system = false).join()
            } else {
                connectNow(fromSystem = false)
            }
        }
    }

    /** `VpnService.onRevoke`: another VPN took over or the user revoked it (critic A5). */
    fun onRevoked() = launch {
        reconnectPending = false
        disconnectNow("VPN 已被系统或其他 VPN 应用断开")
    }

    /** The service died while the controller still thought it was connected. */
    fun onServiceDestroyed() = launch {
        if (!_connected.value && !_busy.value) return@launch
        if (reconnectPending) return@launch
        stopPolling()
        withContext(GoCore.lifecycle) { runCatching { Mobile.stop() } }
        onStopped("VPN 服务已停止")
    }

    /**
     * Re-syncs with a tunnel that outlived the UI (critic A5): called when the app starts. In one
     * process the session lives here, so this only repairs a dead engine (core exited).
     */
    fun reattach() = launch {
        GoCore.awaitReady()
        val running = GoCore.call { Mobile.status() }
        val service = DuoBaoVpnService.instance
        val s = _session.value
        when {
            _connected.value && running.isEmpty() -> {
                service?.shutdown()
                onStopped("核心已退出")
            }
            !_connected.value && running.isNotEmpty() && service != null && s != null -> {
                _connected.value = true
                _statusText.value = STATUS_CONNECTED
                startPolling(s)
            }
        }
    }

    // ---- traffic and live latency ------------------------------------------------------------

    private fun startPolling(s: Session) {
        stopPolling()
        meter.reset()
        val tag = s.statsTags.firstOrNull()
        trafficJob = scope.launch {
            var lastAt = System.currentTimeMillis()
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                val (t, running) = GoCore.call {
                    (if (tag != null) Mobile.queryStats(tag) else null) to Mobile.status()
                }
                if (running.isEmpty() && _connected.value && !_busy.value) {
                    // The engine died under us (desktop: 核心已退出).
                    DuoBaoVpnService.instance?.shutdown()
                    onStopped("核心已退出")
                    break
                }
                if (t != null) _traffic.value = meter.update(t.uplink, t.downlink, now - lastAt)
                lastAt = now
            }
        }
        latencyJob = scope.launch {
            combine(_connected, _uiVisible) { c, v -> c && v }.distinctUntilChanged().collect { on ->
                if (!on) return@collect
                while (isActive && _connected.value && _uiVisible.value) {
                    delay(LATENCY_INTERVAL_MS)
                    if (_connected.value && _uiVisible.value && !_busy.value) liveLatencyOnce(s)
                }
            }
        }
    }

    private fun stopPolling() {
        trafficJob?.cancel()
        latencyJob?.cancel()
        trafficJob = null
        latencyJob = null
    }

    /** One `URLTest(core, "", url)` on the running engine (critic A3), saved like a quiet url test. */
    private suspend fun liveLatencyOnce(s: Session) {
        val ui = store.ui.value
        val result = try {
            TestEngine.msResult(core.urlTest(s.coreId, "", TestKinds.optString(ui, "url", "url"), TestKinds.ms(ui, "url")))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TestEngine.failure(e.message ?: e.toString())
        }
        if (_session.value?.nodeId == s.nodeId) tests.save(s.nodeId, "url", result)
    }

    /** One live latency test now (the UI's refresh); no-op when disconnected. */
    fun testLatencyNow() = launch { _session.value?.let { liveLatencyOnce(it) } }

    // ---- settings → reconnect (critic A9) ------------------------------------------------------

    private fun watchSettings() {
        scope.launch {
            combine(store.settings, store.ui.map { it["proxy_mode"] to it["route_parts"] }.distinctUntilChanged()) { _, _ -> Unit }
                .drop(1)
                .collect {
                    if (!_connected.value) return@collect
                    applyJob?.cancel()
                    applyJob = scope.launch {
                        delay(APPLY_DEBOUNCE_MS)
                        val s = _session.value ?: return@launch
                        if (!_connected.value || _busy.value) return@launch
                        val now = runCatching { renderSettings() }.getOrNull() ?: return@launch
                        if (now != s.appliedSettings) {
                            log("设置已更改，正在重新连接")
                            reconnectNow()
                        }
                    }
                }
        }
    }

    // ---- import / subscriptions -----------------------------------------------------------------

    /**
     * `App.importText`: share links, base64 or Clash text into [group]; a single `http(s)://` URL
     * becomes a subscription group instead ([addSubscription]).
     */
    suspend fun importText(text: String, group: String = Defaults.GROUP): ImportOutcome = withContext(main) {
        val t = text.trim()
        if (t.isEmpty()) return@withContext ImportOutcome(group, 0, emptyList(), "没有可导入的内容", ok = false).also { log(it.message) }
        if (Subscriptions.isSubscriptionUrl(t)) return@withContext addSubscription(t)
        val (nodes, errors) = parseImport(t)
        val n = store.addNodes(nodes, group)
        val msg = "导入 $n 个节点到「$group」" + if (errors.isNotEmpty()) "，${errors.size} 行失败" else ""
        log(msg)
        for (e in errors) log("  $e")
        autoSelect()
        ImportOutcome(group, n, errors, msg)
    }

    private suspend fun parseImport(text: String): Pair<List<JsonObject>, List<String>> {
        val r = GoCore.call { Mobile.importNodes(text) }
        val nodes = runCatching { (Json.parseToJsonElement(r.nodesJSON) as JsonArray).filterIsInstance<JsonObject>() }.getOrDefault(emptyList())
        val errors = runCatching {
            (Json.parseToJsonElement(r.errorsJSON) as JsonArray).map { (it as? JsonPrimitive)?.content ?: it.toString() }
        }.getOrDefault(emptyList())
        return nodes to errors
    }

    /** Adds (or refreshes, when the URL is already a group's) a subscription group and updates it. */
    suspend fun addSubscription(url: String): ImportOutcome = withContext(main) {
        val existing = store.groups.value.firstOrNull { it.subUrl == url }
        val name = existing?.name ?: Subscriptions.groupNameFor(url, store.groups.value.map(Group::name).toSet()).also {
            store.addGroup(jsonObjectOf("name" to it, "sub_url" to url, "sub_interval_min" to 0))
            log("已添加订阅分组「$it」")
        }
        updateSubscription(name)
    }

    /**
     * Updates subscription group [name] like `GroupsPage.updateGroup`: GET `sub_url` (in Go, direct,
     * UA `DuoBaoProxy/<version>`), non-200 logs `订阅 X 更新失败：HTTP n`; the group gets `updated` and
     * the `subscription-userinfo` fields; its nodes are replaced by the parsed ones. Unlike desktop,
     * a body that yields no node keeps the old nodes (spec/gui-groups-routes.md §1.4).
     */
    suspend fun updateSubscription(name: String): ImportOutcome = withContext(main) {
        val g = store.groups.value.firstOrNull { it.name == name }
        if (g == null || g.subUrl.isEmpty()) return@withContext ImportOutcome(name, 0, emptyList(), "「$name」不是订阅分组", ok = false)
        val r = try {
            GoCore.call { Mobile.fetchSubscription(g.subUrl, "", SUB_TIMEOUT_MS) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "订阅 $name 更新失败：" + (e.message ?: e.toString())
            log(msg)
            return@withContext ImportOutcome(name, 0, emptyList(), msg, ok = false)
        }
        if (r.status != 200) {
            val msg = "订阅 $name 更新失败：HTTP ${r.status}"
            log(msg)
            return@withContext ImportOutcome(name, 0, emptyList(), msg, ok = false)
        }
        store.updateGroup(name, Subscriptions.updatePatch(r.userInfo))
        val (nodes, errors) = parseImport(r.body)
        if (nodes.isEmpty()) {
            val msg = "订阅 $name 没有解析出节点，保留原有节点" + if (errors.isNotEmpty()) "（${errors.size} 行失败）" else ""
            log(msg)
            for (e in errors) log("  $e")
            return@withContext ImportOutcome(name, 0, errors, msg, ok = false)
        }
        store.removeGroupNodes(name)
        val n = store.addNodes(nodes, name)
        val msg = "导入 $n 个节点到「$name」" + if (errors.isNotEmpty()) "，${errors.size} 行失败" else ""
        log(msg)
        for (e in errors) log("  $e")
        dropMissingSelection()
        autoSelect()
        ImportOutcome(name, n, errors, msg)
    }

    /** 全部更新: every subscription group, one after another. */
    fun updateAllSubscriptions() = launch {
        for (g in store.groups.value) if (g.subUrl.isNotEmpty()) updateSubscription(g.name)
    }

    private suspend fun autoSelect() {
        if (currentSelected() < 0) {
            val first = store.nodes.value.firstOrNull { supports(it.node) } ?: store.nodes.value.firstOrNull() ?: return
            store.setUi("selected", first.id)
        }
    }

    /** `App.dropMissingSelection`. */
    private suspend fun dropMissingSelection() {
        val sel = currentSelected()
        if (sel >= 0 && store.node(sel) == null) store.setUi("selected", -1)
    }

    // ---- share / export ---------------------------------------------------------------------

    /** The share link of node [id] (`ShareLink`); [v2rayN] picks the v2rayN vmess flavour. */
    suspend fun shareLink(id: Int, v2rayN: Boolean = false): String {
        val json = store.nodeJson(id)
        require(json.isNotEmpty()) { "节点不存在" }
        return GoCore.call { Mobile.shareLink(json, v2rayN) }
    }

    /** 导出配置: the selected node rendered in Export mode (2) for the selected engine. */
    suspend fun exportConfig(): String {
        val id = currentSelected()
        require(id >= 0 && store.nodeJson(id).isNotEmpty()) { "先选一个节点" }
        return core.renderConfig(currentCore(), "[" + store.nodeJson(id) + "]", renderSettings(), CoreApi.MODE_EXPORT).config
    }

    /** 开机自动连接 (`ui.boot_connect`, mobile-only, default off). */
    val bootConnect: Boolean get() = (store.ui.value["boot_connect"] as? JsonPrimitive)?.booleanOrNull == true

    // ---- internals ------------------------------------------------------------------------

    private fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    private fun trackVisibility() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                started++
                _uiVisible.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                _uiVisible.value = started > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        const val CORE_SINGBOX = "sing-box"
        const val CORE_XRAY = "xray"
        const val STATUS_IDLE = "未连接"
        const val STATUS_CONNECTING = "正在连接…"
        const val STATUS_CONNECTED = "已连接"
        const val APPLY_DEBOUNCE_MS = 800L
        const val LATENCY_INTERVAL_MS = 5000L
        const val SUB_TIMEOUT_MS = 30_000

        @Volatile private var _instance: AppController? = null

        /** The process singleton; [init] must have run (Application.onCreate). */
        val instance: AppController get() = _instance ?: error("AppController.init was not called")

        /** Creates the singleton. Called once from `Application.onCreate`. */
        @Synchronized
        fun init(app: Application, store: Store): AppController =
            _instance ?: AppController(app, store, GoCoreApi).also { _instance = it }
    }
}
