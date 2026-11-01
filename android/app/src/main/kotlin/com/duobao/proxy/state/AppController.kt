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
import com.duobao.proxy.core.NetworkTracker
import com.duobao.proxy.data.Defaults
import com.duobao.proxy.data.Group
import com.duobao.proxy.data.Store
import com.duobao.proxy.data.asInt
import com.duobao.proxy.data.asString
import com.duobao.proxy.data.jsonObjectOf
import com.duobao.proxy.data.merged
import com.duobao.proxy.tests.TestEngine
import com.duobao.proxy.tests.TestFormat
import com.duobao.proxy.tests.TestKinds
import com.duobao.proxy.vpn.DuoBaoVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * Application state and the connect / test / import flows: the Kotlin port of `desktop/qml/App.qml`
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

    /** `App.renderSettings()` for the mobile renderer; TUN follows 设置 › TUN 模式. */
    suspend fun renderSettings(): String {
        val ui = store.ui.value
        return core.prepareSettings(store.settingsJson(), ui["proxy_mode"].asString(), ui["route_parts"].asString(), Prefs(ui).tun)
    }

    /** The local mixed port (`settings.inbound.port`), 0 when off. */
    private fun proxyPort(): Int = (store.settings.value["inbound"] as? JsonObject)?.get("port").asInt(0)

    // ---- selection / engine ----------------------------------------------------------------

    /**
     * `App.setCore`, as the mobile design words it (切换内核后会自动重新连接): stop, switch, drop an
     * unsupported selection, and connect again when the tunnel was up and the node still works.
     */
    fun setCore(id: String) = launch {
        if (id == currentCore()) return@launch
        val was = _connected.value
        if (was) disconnectNow()
        store.setUi("core", id)
        val sel = store.node(currentSelected())
        if (!supports(sel?.node)) store.setUi("selected", -1)
        else if (was) connectNow(fromSystem = false)
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
        reconnectJob?.cancel()
        if (prefs.pickFastest) pickFastest()
        checkNode()?.let { _statusText.value = it; return }
        // Without TUN only the local port carries traffic, so it must exist; no VPN permission
        // is needed then (the service runs as a plain foreground service).
        if (!prefs.tun) {
            if (proxyPort() <= 0) {
                _statusText.value = "关闭 TUN 模式时需要先设置代理端口"
                return
            }
            _busy.value = true
            _statusText.value = STATUS_CONNECTING
            startService()
            return
        }
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
        startAutoSwitch(s)
        // An immediate latency, like desktop's quiet test after start.
        scope.launch { liveLatencyOnce(s) }
        // The exit IP and country for the 首页 map.
        tests.run(listOf(s.nodeId), listOf("geo"), quiet = true)
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
        if (wasConnected && reason != null && !reconnectPending) {
            if (prefs.notifyState) alerts.show("连接已断开", reason)
            // 断线自动重连: only for drops the user did not cause (not a revoke by another VPN).
            val sec = prefs.reconnectSec
            if (sec > 0 && reason in AUTO_RECONNECT_REASONS) {
                reconnectJob = scope.launch {
                    log("${sec} 秒后自动重连")
                    delay(sec * 1000L)
                    if (!_connected.value && !_busy.value) connectNow(fromSystem = true)
                }
            }
        }
        refreshLocalInfo()
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
        autoSwitchJob?.cancel()
        autoSwitchJob = null
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
        if (_session.value?.nodeId == s.nodeId) {
            tests.save(s.nodeId, "url", result)
            if (result.containsKey("err")) liveFailures++ else liveFailures = 0
        }
    }

    // ---- 自动切换最快节点 -----------------------------------------------------------------------

    private var autoSwitchJob: Job? = null
    private var liveFailures = 0

    /**
     * 自动切换最快节点: while connected, every [AUTO_SWITCH_INTERVAL_MS] (and soon after two failed
     * live tests in a row) TCP-pings the connected node's group and moves to the fastest node when
     * the current one is unreachable or clearly slower. Switching reconnects.
     */
    private fun startAutoSwitch(s: Session) {
        autoSwitchJob?.cancel()
        liveFailures = 0
        autoSwitchJob = scope.launch {
            var waited = 0L
            while (isActive) {
                delay(AUTO_SWITCH_TICK_MS)
                waited += AUTO_SWITCH_TICK_MS
                if (!prefs.autoSwitch || !_connected.value || _busy.value) continue
                if (waited < AUTO_SWITCH_INTERVAL_MS && liveFailures < 2) continue
                waited = 0
                liveFailures = 0
                val best = fastestInGroup(s.nodeId, current = true) ?: continue
                if (best != s.nodeId) {
                    log("自动切换到更快的节点：" + nodeName(store.node(best)?.node))
                    selectNow(best)
                    break // the reconnect starts a new watcher
                }
            }
        }
    }

    /**
     * The fastest node by TCP latency in [id]'s group, or null when nothing answers. With [current]
     * the node [id] keeps its place unless it fails or another is faster by 30% and 50 ms.
     */
    private suspend fun fastestInGroup(id: Int, current: Boolean): Int? {
        val sel = store.node(id) ?: return null
        val candidates = store.nodes.value.filter { it.group == sel.group && supports(it.node) }
        if (candidates.size < 2) return null
        val results = coroutineScope {
            candidates.map { e ->
                async(GoCore.pool) { e.id to runCatching { core.tcpPing(TestEngine.hostPort(e), 3000) }.getOrDefault(-1) }
            }.awaitAll()
        }.filter { it.second > 0 }
        val best = results.minByOrNull { it.second } ?: return null
        if (!current) return best.first
        val mine = results.firstOrNull { it.first == id }?.second ?: return best.first
        return if (best.second * 10 < mine * 7 && mine - best.second >= 50) best.first else id
    }

    /**
     * 节点全部超时: when a visible url test batch ends and every node it covered failed, post a
     * reminder (opt-in).
     */
    private fun watchTestBatches() {
        scope.launch {
            var batch = emptySet<Int>()
            tests.running.collect { r ->
                if (r.isNotEmpty()) {
                    batch = batch + r.filter { it.endsWith(":url") }.map { it.substringBefore(':').toInt() }
                    return@collect
                }
                val ids = batch
                batch = emptySet()
                if (ids.size < 2 || !prefs.notifyAllTimeout || "url" !in tests.shownKinds.value) return@collect
                val allFailed = ids.mapNotNull(store::node).all { TestFormat.result(it, "url")?.containsKey("err") == true }
                if (allFailed) alerts.show("节点全部超时", "刚测试的 ${ids.size} 个节点都无法连接")
            }
        }
    }

    /** 启动后自动连接: called once when the main screen first opens. */
    fun onAppLaunched() = launch {
        if (prefs.launchConnect && !_connected.value && !_busy.value && currentSelected() >= 0) connectNow(fromSystem = false)
    }

    /** One live latency test now (the UI's refresh); no-op when disconnected. */
    fun testLatencyNow() = launch { _session.value?.let { liveLatencyOnce(it) } }

    // ---- settings → reconnect (critic A9) ------------------------------------------------------

    private fun watchSettings() {
        scope.launch {
            combine(store.settings, store.ui.map { listOf(it["proxy_mode"], it["route_parts"], it[Prefs.TUN]) }.distinctUntilChanged()) { _, _ -> Unit }
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
            store.addGroup(jsonObjectOf("name" to it, "sub_url" to url, "sub_interval_min" to 1440))
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
            // 通过代理更新: through the tunnel when connected, falling back to a direct fetch.
            val proxied = if (prefs.subViaProxy && _connected.value) {
                runCatching { GoCore.call { Mobile.fetchSubscriptionProxied(g.subUrl, prefs.subUserAgent, SUB_TIMEOUT_MS) } }
                    .onFailure { if (it is CancellationException) throw it; log("订阅 $name 经代理更新失败，改为直连：" + it.message) }
                    .getOrNull()
            } else {
                null
            }
            proxied ?: GoCore.call { Mobile.fetchSubscription(g.subUrl, prefs.subUserAgent, SUB_TIMEOUT_MS) }
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
        store.groups.value.firstOrNull { it.name == name }?.let(::remind)
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

    /**
     * Auto-update of subscription groups (`GroupsPage.qml:146-153`), but process-wide instead of only
     * while the page is open. A group updates itself when `sub_interval_min > 0` (0 = off, as on
     * desktop) and it has not expired. 订阅更新 › 自动更新 off stops everything; 每次启动时
     * (`sub_interval_h` 0) updates those groups once per process instead of on a timer;
     * 仅 Wi-Fi 下更新 skips metered networks.
     */
    private fun scheduleSubscriptions() {
        scope.launch {
            GoCore.awaitReady()
            var first = true
            while (isActive) {
                val p = prefs
                val metered = NetworkTracker.current.value?.metered ?: false
                if (p.subAuto && !(p.subWifiOnly && metered)) {
                    val now = System.currentTimeMillis()
                    for (g in store.groups.value) {
                        if (g.subUrl.isEmpty() || g.subIntervalMin <= 0) continue
                        if ((g.expire ?: 0) in 1 until now / 1000) continue
                        val due = if (p.subIntervalH == 0) first else now - lastUpdated(g) > g.subIntervalMin * 60_000L
                        if (due) updateSubscription(g.name)
                    }
                    first = false
                }
                delay(SUB_TICK_MS)
            }
        }
    }

    /** One field of group [name] (e.g. `notify_expire`). */
    suspend fun setGroupField(name: String, key: String, value: Any?) = withContext(main) {
        store.updateGroup(name, jsonObjectOf(key to value))
    }

    /** 订阅更新 › 更新间隔: [hours] for every auto-updating subscription (0 = 每次启动时 keeps theirs). */
    fun setSubInterval(hours: Int) = launch {
        store.setUi(Prefs.SUB_INTERVAL_H, hours)
        if (hours <= 0) return@launch
        for (g in store.groups.value) if (g.subUrl.isNotEmpty() && g.subIntervalMin > 0) store.updateGroup(g.name, jsonObjectOf("sub_interval_min" to hours * 60))
    }

    private fun lastUpdated(g: Group): Long =
        g.updated?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    /** Expiry and low-traffic reminders for subscription [g] (通知 › 提醒), at most once a day each. */
    private fun remind(g: Group) {
        val p = prefs
        val today = java.time.LocalDate.now().toString()
        val expire = g.expire
        val groupOn = (g.json["notify_expire"] as? JsonPrimitive)?.booleanOrNull ?: true
        if (p.notifyExpire && groupOn && expire != null && expire > 0) {
            val days = (expire * 1000 - System.currentTimeMillis()) / 86_400_000.0
            if (days <= p.notifyExpireDays && reminded.add("expire:${g.name}:$today")) {
                alerts.show("订阅即将到期", if (days < 0) "「${g.name}」已过期" else "「${g.name}」将在 ${kotlin.math.ceil(days).toInt()} 天内到期")
            }
        }
        val total = g.total
        if (p.notifyTraffic && total != null && total > 0) {
            val left = total - (g.upload ?: 0) - (g.download ?: 0)
            if (left * 100 < total * p.notifyTrafficPct && reminded.add("traffic:${g.name}:$today")) {
                alerts.show("订阅流量不足", "「${g.name}」剩余 ${com.duobao.proxy.ui.theme.formatBytes(maxOf(0, left))}")
            }
        }
    }

    private val reminded = HashSet<String>()

    /**
     * The device's own public IP and country (首页 map chip while disconnected). The app package is
     * outside the VPN, so this Cloudflare trace always goes direct.
     */
    fun refreshLocalInfo() = scope.launch(Dispatchers.IO) {
        _localInfo.value = runCatching { IpInfo.fetchDirect() }.getOrNull()
    }

    private val _localInfo = MutableStateFlow<IpInfo?>(null)

    /** Null until fetched, or when offline. */
    val localInfo: StateFlow<IpInfo?> = _localInfo.asStateFlow()

    /** Whether the device has a usable non-VPN network (首页 无网络 state). */
    val online: StateFlow<Boolean> = NetworkTracker.current.map { it != null }.stateIn(scope, SharingStarted.Eagerly, NetworkTracker.current.value != null)

    /** 连接前测速选最快节点: TCP latency over the selected node's group, the lowest wins. */
    private suspend fun pickFastest() {
        val sel = currentSelected()
        if (store.node(sel) == null) return
        _statusText.value = "正在测速选择节点…"
        val best = fastestInGroup(sel, current = false) ?: return
        if (best != sel) {
            store.setUi("selected", best)
            log("已选择最快节点：" + nodeName(store.node(best)?.node))
        }
    }

    /** The notification-channel helper for reminders and state changes. */
    private val alerts = com.duobao.proxy.vpn.AlertNotifier(app)

    /** Live typed view of the mobile settings. */
    val prefs: Prefs get() = Prefs(store.ui.value)

    private var reconnectJob: Job? = null

    // ---- nodes and groups ------------------------------------------------------------------

    /** `App.removeNodes`; unlike desktop, deleting the connected node disconnects. */
    fun removeNodes(ids: Collection<Int>) = launch {
        if (ids.isEmpty()) return@launch
        store.removeNodes(ids)
        log("已删除 ${ids.size} 个节点")
        if (_session.value?.nodeId in ids) disconnectNow()
    }

    /** `App.moveNodes`: only `group` changes, array order stays. */
    fun moveNodes(ids: Collection<Int>, group: String) = launch {
        val n = store.setNodesGroup(ids, group)
        log("已移动 $n 个节点到「$group」")
    }

    /** `App.createGroup`: trims; false when empty or taken. */
    suspend fun createGroup(name: String): Boolean = withContext(main) {
        val n = name.trim()
        if (n.isEmpty() || store.groups.value.any { it.name == n }) return@withContext false
        store.addGroup(jsonObjectOf("name" to n))
        true
    }

    /**
     * The group editor's 保存: adds a group or renames / edits [oldName] (nodes follow a rename).
     * Returns an error text, or null.
     */
    suspend fun saveGroup(oldName: String?, name: String, subUrl: String, intervalMin: Int): String? = withContext(main) {
        val n = name.trim()
        if (n.isEmpty()) return@withContext "名称不能为空"
        val taken = store.groups.value.any { it.name == n }
        val patch = jsonObjectOf("sub_url" to subUrl.trim(), "sub_interval_min" to intervalMin.coerceIn(0, 1440))
        if (oldName == null) {
            if (taken) return@withContext "已有同名分组"
            store.addGroup(jsonObjectOf("name" to n).merged(patch))
        } else {
            if (n != oldName && !store.renameGroup(oldName, n)) return@withContext "已有同名分组"
            store.updateGroup(n, patch)
        }
        null
    }

    /** `App.removeGroups`: 默认 is never deleted; nodes go with their group; the selection is fixed. */
    fun removeGroups(names: Collection<String>) = launch {
        val targets = names.filter { it != Defaults.GROUP }
        if (targets.isEmpty()) return@launch
        for (g in targets) store.removeGroup(g)
        dropMissingSelection()
        log("已删除 ${targets.size} 个分组")
        val s = _session.value
        if (s != null && store.node(s.nodeId) == null) disconnectNow()
    }

    /** `App.mergeGroups`: every node of [names] into [target], then the sources (except 默认) go. */
    fun mergeGroups(names: Collection<String>, target: String) = launch {
        val ids = store.nodes.value.filter { it.group in names && it.group != target }.map { it.id }
        val n = store.setNodesGroup(ids, target)
        for (g in names) if (g != target && g != Defaults.GROUP) store.removeGroup(g)
        log("已移动 $n 个节点到「$target」")
    }

    /**
     * The node editor's 保存 (`NodeEditor.qml:119-145`): a new node goes to [group]; an edited one
     * gets its test results reset and, when it is the connected node, a reconnect.
     */
    suspend fun saveNode(id: Int?, node: JsonObject, group: String): String? = withContext(main) {
        val name = nodeName(node)
        if (id == null) {
            val newId = store.addNode(node, group)
            if (newId < 0) return@withContext "节点数据无效"
            log("已添加节点 $name")
            autoSelect()
        } else {
            store.updateNode(id, jsonObjectOf("node" to node, "group" to group, "lat" to 0, "res" to emptyMap<String, Any>()))
            log("已保存节点 $name")
            if (_connected.value && _session.value?.nodeId == id) reconnectNow()
        }
        null
    }

    /**
     * 更新规则集: xray reads geoip.dat / geosite.dat from disk, so they are downloaded again;
     * sing-box fetches its `.srs` rule sets when it starts, so a running tunnel is restarted.
     */
    fun updateRuleSets() = launch {
        if (currentCore() == CORE_XRAY) {
            log("正在更新规则数据…")
            runCatching { geo.update() }
                .onSuccess { log("规则数据已更新") }
                .onFailure { log("规则数据更新失败：" + it.message); return@launch }
            if (_connected.value) reconnectNow()
        } else if (_connected.value) {
            log("正在重新连接以刷新规则集")
            reconnectNow()
        } else {
            log("sing-box 会在连接时下载并缓存规则集")
        }
        store.setUi("rules_updated", System.currentTimeMillis().toString())
    }

    /** `store.moveGroup` off the main thread. */
    fun moveGroup(from: Int, to: Int) = launch { store.moveGroup(from, to) }

    /** `store.setUi` off the main thread. */
    fun setUi(key: String, value: Any?) = launch { store.setUi(key, value) }

    /** `store.setSetting` off the main thread (a change while connected reconnects, debounced). */
    fun setSetting(key: String, value: Any?) = launch { store.setSetting(key, value) }

    /** 重启核心 (critic A6): stop the tunnel and re-run Setup. */
    fun restartCore() = launch {
        if (_connected.value) disconnectNow()
        runCatching { GoCore.restart() }.onFailure { log("重启核心失败：" + it.message) }.onSuccess { log("核心已重启") }
    }

    // ---- share / export ---------------------------------------------------------------------

    /**
     * `App.shareNodes`: the links of [ids] in order, as `(name, link)`; nodes without a link format
     * are logged as `分享失败：…` and skipped.
     */
    suspend fun shareLinks(ids: List<Int>): List<Pair<String, String>> = buildList {
        for (id in ids) {
            val e = store.node(id) ?: continue
            try {
                add(nodeName(e.node) to shareLink(id))
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                log("分享失败：" + (ex.message ?: ex.toString()))
            }
        }
    }

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
    val bootConnect: Boolean get() = prefs.bootConnect

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

    init {
        trackVisibility()
        watchSettings()
        scheduleSubscriptions()
        watchTestBatches()
        refreshLocalInfo()
        NetworkTracker.current.map { it?.network }.distinctUntilChanged().drop(1).let { f ->
            scope.launch { f.collect { if (!_connected.value) refreshLocalInfo() } }
        }
    }

    companion object {
        /** Drops 断线自动重连 retries; a revoke by the user or another VPN is not one of them. */
        val AUTO_RECONNECT_REASONS = setOf("核心已退出", "VPN 服务已停止")
        const val CORE_SINGBOX = "sing-box"
        const val CORE_XRAY = "xray"
        const val STATUS_IDLE = "未连接"
        const val STATUS_CONNECTING = "正在连接…"
        const val STATUS_CONNECTED = "已连接"
        const val APPLY_DEBOUNCE_MS = 800L
        const val LATENCY_INTERVAL_MS = 5000L
        const val SUB_TIMEOUT_MS = 30_000
        const val SUB_TICK_MS = 60_000L
        const val AUTO_SWITCH_TICK_MS = 15_000L
        const val AUTO_SWITCH_INTERVAL_MS = 300_000L

        @Volatile private var _instance: AppController? = null

        /** The process singleton; [init] must have run (Application.onCreate). */
        val instance: AppController get() = _instance ?: error("AppController.init was not called")

        /** Creates the singleton. Called once from `Application.onCreate`. */
        @Synchronized
        fun init(app: Application, store: Store): AppController =
            _instance ?: AppController(app, store, GoCoreApi).also { _instance = it }
    }
}
