package com.duobao.proxy.tests

import com.duobao.proxy.core.CoreApi
import com.duobao.proxy.data.NodeEntry
import com.duobao.proxy.data.Store
import com.duobao.proxy.data.jsonObjectOf
import com.duobao.proxy.data.parseObject
import com.duobao.proxy.data.toJson
import com.duobao.proxy.tests.TestKinds.isSpeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.max

/**
 * Node tests: the queue half of `app/qml/Tests.qml`. A result lives on the node entry as
 * `res[kind]`: `{ms}` for latencies, `{bps}` for speeds, `{err, detail}` on failure, or the probe's
 * own report; a url result also writes the legacy `lat` (`ms` or -1).
 *
 * At most [MAX_ACTIVE] jobs run at once and speed tests (`down` / `up`) one at a time; jobs are
 * deduplicated on `"id:kind"` while queued or running. Through-node kinds first render a throw-away
 * Test-mode config ([CoreApi.MODE_TEST]) with [renderSettings].
 *
 * @param coreId the selected engine (`App.coreId`)
 * @param supports `App.supports`: whether the selected engine carries the node's protocol
 * @param renderSettings `App.renderSettings()` as JSON (the PrepareSettings output)
 * @param onUrlResult called after a url result was saved, with the node id and its new `lat`
 */
class TestEngine(
    private val store: Store,
    private val core: CoreApi,
    private val scope: CoroutineScope,
    private val coreId: () -> String,
    private val supports: (JsonObject) -> Boolean,
    private val renderSettings: suspend () -> String,
    private val onUrlResult: (id: Int, lat: Int) -> Unit = { _, _ -> },
) {
    /** A queued test. */
    data class Job(val id: Int, val k: String)

    private val lock = Any()
    private val saveLock = Any()
    private val queue = ArrayDeque<Job>()
    private var active = 0
    private var speedBusy = false
    private val _running = MutableStateFlow<Set<String>>(emptySet())
    private val _shownKinds = MutableStateFlow<List<String>>(emptyList())

    /** `"id:kind"` keys of the jobs queued or running (`Tests.running`). */
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    /**
     * Result columns the node list shows: the kinds of the last non-quiet run. Empty at launch, so
     * the list shows no results until something is tested.
     */
    val shownKinds: StateFlow<List<String>> = _shownKinds.asStateFlow()

    fun isRunning(id: Int, k: String) = "$id:$k" in _running.value

    /** The × in the result-column header. */
    fun clearShown() {
        _shownKinds.value = emptyList()
    }

    /**
     * Queues [kinds] for every node of [ids] the current engine supports. [quiet] runs in the
     * background without changing [shownKinds].
     */
    fun run(ids: List<Int>, kinds: List<String>, quiet: Boolean = false) {
        if (!quiet) _shownKinds.value = kinds.toList()
        synchronized(lock) {
            val r = _running.value.toMutableSet()
            for (id in ids) {
                val e = store.node(id) ?: continue
                if (e.node.isEmpty() || !supports(e.node)) continue
                for (k in kinds) {
                    val key = "$id:$k"
                    if (key in r) continue
                    r += key
                    queue.addLast(Job(id, k))
                }
            }
            _running.value = r
        }
        pump()
    }

    /** Runs every kind on [ids] (全部测试). */
    fun runAll(ids: List<Int>) = run(ids, TestKinds.allKinds)

    private fun pump() {
        val start = mutableListOf<Job>()
        synchronized(lock) {
            while (active < MAX_ACTIVE && queue.isNotEmpty()) {
                val i = queue.indexOfFirst { !isSpeed(it.k) || !speedBusy }
                if (i < 0) break
                val job = queue.removeAt(i)
                active++
                if (isSpeed(job.k)) speedBusy = true
                start += job
            }
        }
        for (job in start) scope.launch { finish(job, execute(job)) }
    }

    private fun finish(job: Job, result: JsonObject) {
        synchronized(lock) {
            active--
            if (isSpeed(job.k)) speedBusy = false
            _running.value = _running.value - "${job.id}:${job.k}"
        }
        save(job.id, job.k, result)
        pump()
    }

    /**
     * Stores [result] as `res[k]` (and `lat` for url), like `Tests.save`. Jobs finish on several
     * threads, so the read-modify-write of `res` is serialised here (QML had one thread).
     */
    fun save(id: Int, k: String, result: JsonObject) {
        var lat = 0
        synchronized(saveLock) {
            val e = store.node(id) ?: return
            if (e.node.isEmpty()) return
            val res = LinkedHashMap(e.res).apply { put(k, result) }
            val patch = LinkedHashMap<String, JsonElement>()
            patch["res"] = JsonObject(res)
            if (k == "url") {
                lat = (result["ms"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it != 0.0 }?.toInt() ?: -1
                patch["lat"] = lat.toJson()
            }
            store.updateNode(id, JsonObject(patch))
        }
        if (k == "url") onUrlResult(id, lat)
    }

    /** Runs one job and returns the result object; never throws. */
    private suspend fun execute(job: Job): JsonObject {
        val n = store.node(job.id)
        if (n == null || n.node.isEmpty()) return failure("节点不存在")
        val ui = store.ui.value
        val ms = TestKinds.ms(ui, job.k)
        try {
            when (job.k) {
                "tcp" -> return msResult(core.tcpPing(hostPort(n), ms))
                "icmp" -> return msResult(core.probe("icmp", "", "", n.server, "", 0, ms).ms)
                "loss" -> {
                    val r = core.probe("loss", "", "", hostPort(n), "", TestKinds.optNumber(ui, "loss", "count", 10), ms)
                    return reportResult(job.k, r.ms, r.report)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failure(e.message ?: e.toString())
        }
        // The rest go through the node: build a throw-away config first.
        val engine = coreId()
        val config = try {
            core.renderConfig(engine, "[" + store.nodeJson(job.id) + "]", renderSettings(), CoreApi.MODE_TEST).config
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return jsonObjectOf("err" to "失败", "detail" to (e.message ?: e.toString()))
        }
        return try {
            if (job.k == "url") {
                msResult(core.urlTest(engine, config, TestKinds.optString(ui, "url", "url"), ms))
            } else {
                val a = probeArgs(ui, job.k)
                val r = core.probe(PROBE_NAME.getValue(job.k), engine, config, a.address, a.url, a.count, ms)
                reportResult(job.k, r.ms, r.report)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e.message ?: e.toString())
        }
    }

    /** Targets travel in the probe's address / url / count fields. */
    internal data class ProbeArgs(val address: String, val url: String, val count: Int)

    internal fun probeArgs(ui: JsonObject, k: String): ProbeArgs = when (k) {
        "udp" -> ProbeArgs(TestKinds.optString(ui, "udp", "server"), TestKinds.optString(ui, "udp", "domain"), 0)
        "down" -> ProbeArgs("", TestKinds.optString(ui, "down", "url"), 0)
        "up" -> ProbeArgs("", TestKinds.optString(ui, "up", "url"), TestKinds.optNumber(ui, "up", "size", 10))
        "geo" -> ProbeArgs("", TestKinds.optString(ui, "geo", "url"), 0)
        "media", "ai" -> ProbeArgs("", TestKinds.optList(ui, k, "services").joinToString(","), 0)
        else -> ProbeArgs("", "", 0)
    }

    companion object {
        /** Concurrent jobs (`Tests.maxActive`). */
        const val MAX_ACTIVE = 6

        /** Kind → `Probe` name for the through-node probes. */
        val PROBE_NAME = mapOf("udp" to "udp", "down" to "download", "up" to "upload", "geo" to "geo", "media" to "media", "ai" to "ai", "dns" to "dnsleak")

        private val TIMEOUT_RE = Regex("""timeout|deadline|i/o timeout""", RegexOption.IGNORE_CASE)

        /** `[server]:port` for IPv6 literals, `server:port` otherwise. */
        fun hostPort(n: NodeEntry): String = (if (':' in n.server) "[${n.server}]" else n.server) + ":" + n.port

        /** `{err: 超时 | 失败, detail}` from an error message. */
        fun failure(error: String): JsonObject =
            jsonObjectOf("err" to if (TIMEOUT_RE.containsMatchIn(error)) "超时" else "失败", "detail" to error)

        fun msResult(ms: Int): JsonObject = jsonObjectOf("ms" to max(1, ms))

        /** `onProbed`: the report object; icmp / udp keep only `{ms}`; loss ≥ 1 adds `err: 超时`. */
        fun reportResult(k: String, ms: Int, report: String): JsonObject {
            if (k == "icmp" || k == "udp") return msResult(ms)
            val r = if (report.isEmpty()) JsonObject(emptyMap()) else parseObject(report)
            if (k == "loss") {
                val loss = (r["loss"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                if (loss >= 1) return JsonObject(LinkedHashMap(r).apply { put("err", "超时".toJson()) })
            }
            return r
        }
    }
}
