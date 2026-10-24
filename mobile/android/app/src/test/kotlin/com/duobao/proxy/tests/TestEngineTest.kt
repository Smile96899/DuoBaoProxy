package com.duobao.proxy.tests

import com.duobao.proxy.core.CoreApi
import com.duobao.proxy.core.ProbeResult
import com.duobao.proxy.core.Rendered
import com.duobao.proxy.data.Store
import com.duobao.proxy.data.StoreTestBase
import com.duobao.proxy.data.jsonObjectOf
import com.duobao.proxy.data.asInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class TestEngineTest : StoreTestBase() {
    /** Records every call; [gate] (when set) holds calls until completed. */
    private class FakeCore : CoreApi {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var gate: CompletableDeferred<Unit>? = null
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val speedActive = AtomicInteger()
        val maxSpeedActive = AtomicInteger()
        var urlMs = 42
        var urlError: String? = null
        var renderError: String? = null
        var probeResult: (String) -> ProbeResult = { ProbeResult(7, "{}") }

        private suspend fun hold(speed: Boolean = false) {
            val n = active.incrementAndGet()
            maxActive.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            if (speed) maxSpeedActive.accumulateAndGet(speedActive.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
                gate?.await() ?: delay(5)
            } finally {
                if (speed) speedActive.decrementAndGet()
                active.decrementAndGet()
            }
        }

        override suspend fun prepareSettings(settingsJson: String, proxyMode: String, routeParts: String, tun: Boolean) = settingsJson

        override suspend fun renderConfig(coreId: String, chainJson: String, settingsJson: String, mode: Int): Rendered {
            calls += "render $coreId $mode $chainJson $settingsJson"
            renderError?.let { throw Exception(it) }
            return Rendered("CFG", listOf("proxy"), "")
        }

        override suspend fun urlTest(coreId: String, config: String, url: String, timeoutMs: Int): Int {
            calls += "url $coreId $config $url $timeoutMs"
            hold()
            urlError?.let { throw Exception(it) }
            return urlMs
        }

        override suspend fun tcpPing(address: String, timeoutMs: Int): Int {
            calls += "tcp $address $timeoutMs"
            hold()
            return 0
        }

        override suspend fun probe(name: String, coreId: String, config: String, address: String, url: String, count: Int, timeoutMs: Int): ProbeResult {
            calls += "probe $name|$coreId|$config|$address|$url|$count|$timeoutMs"
            hold(speed = name == "download" || name == "upload")
            return probeResult(name)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val core = FakeCore()
    private var unsupported = setOf<String>()
    private val urlResults = mutableListOf<Pair<Int, Int>>()

    @After
    fun tearDown() = scope.cancel()

    private fun engine(ui: String = """{"core":"sing-box","selected":1}""", nodes: String = NODES): TestEngine {
        val store = storeWith("""{"nodes":$nodes,"groups":[{"name":"默认"}],"settings":{"log_level":"info"},"ui":$ui,"next_id":20}""")
        storeRef = store
        return TestEngine(
            store = store,
            core = core,
            scope = scope,
            coreId = { "sing-box" },
            supports = { it["protocol"]?.let { p -> (p as JsonPrimitive).content !in unsupported } ?: false },
            renderSettings = { "SETTINGS" },
            onUrlResult = { id, lat -> synchronized(urlResults) { urlResults += id to lat } },
        )
    }

    private lateinit var storeRef: Store

    private fun TestEngine.awaitIdle() = runBlocking {
        withTimeout(10_000) { running.first { it.isEmpty() } }
        delay(20)
    }

    private fun res(id: Int, k: String): JsonObject? = storeRef.node(id)?.res?.get(k) as? JsonObject

    @Test
    fun tcpGoesDirectWithHostPortAndDefaultTimeout() {
        val t = engine()
        t.run(listOf(1, 2), listOf("tcp"))
        t.awaitIdle()
        assertTrue(core.calls.contains("tcp 1.2.3.4:443 5000"))
        assertTrue(core.calls.contains("tcp [2001:db8::1]:8443 5000"))
        // max(1, ms): a 0 ms connect is stored as 1.
        assertEquals("""{"ms":1}""", res(1, "tcp").toString())
        assertEquals(0, storeRef.node(1)!!.lat) // only url writes lat
        assertEquals(listOf("tcp"), t.shownKinds.value)
    }

    @Test
    fun urlRendersTestModeThenSavesLat() {
        val t = engine()
        t.run(listOf(1), listOf("url"), quiet = true)
        t.awaitIdle()
        assertEquals(
            // nodeJson is QJsonDocument::Compact: keys sorted
            """render sing-box 1 [{"name":"a","port":443,"protocol":"vless","server":"1.2.3.4"}] SETTINGS""",
            core.calls[0],
        )
        assertEquals("url sing-box CFG http://cp.cloudflare.com/ 8000", core.calls[1])
        assertEquals("""{"ms":42}""", res(1, "url").toString())
        assertEquals(42, storeRef.node(1)!!.lat)
        assertEquals(listOf(1 to 42), urlResults)
        assertEquals(emptyList<String>(), t.shownKinds.value) // quiet
    }

    @Test
    fun urlOptionsAndLegacyTestUrl() {
        var t = engine(ui = """{"test_url":"http://legacy/","tests":{"url":{"timeout":"3"}}}""")
        t.run(listOf(1), listOf("url"))
        t.awaitIdle()
        assertEquals("url sing-box CFG http://legacy/ 3000", core.calls[1])
        core.calls.clear()
        // resetOpts("url") leaves test_url "" which is not re-seeded: the default applies.
        t = engine(ui = """{"test_url":"","tests":{"url":{"url":"","timeout":0}}}""")
        t.run(listOf(1), listOf("url"))
        t.awaitIdle()
        assertEquals("url sing-box CFG http://cp.cloudflare.com/ 8000", core.calls[1])
    }

    @Test
    fun errorsMapToTimeoutOrFailure() {
        val t = engine()
        core.urlError = "Get \"http://cp.cloudflare.com/\": context deadline exceeded"
        t.run(listOf(1), listOf("url"))
        t.awaitIdle()
        assertEquals("超时", (res(1, "url")!!["err"] as JsonPrimitive).content)
        assertEquals(-1, storeRef.node(1)!!.lat)
        core.urlError = "EOF"
        t.run(listOf(1), listOf("url"))
        t.awaitIdle()
        assertEquals("""{"err":"失败","detail":"EOF"}""", res(1, "url").toString())
        core.renderError = "no such protocol"
        t.run(listOf(1), listOf("geo"))
        t.awaitIdle()
        assertEquals("""{"err":"失败","detail":"no such protocol"}""", res(1, "geo").toString())
    }

    @Test
    fun probesUseDesktopArguments() {
        val t = engine(ui = """{"tests":{"up":{"size":3},"media":{"services":["netflix"]}}}""")
        core.probeResult = { name ->
            when (name) {
                "loss" -> ProbeResult(40, """{"avg":40,"min":1,"max":2,"jitter":3,"loss":1}""")
                "udp" -> ProbeResult(0, """{"ignored":true}""")
                "geo" -> ProbeResult(0, """{"ip":"43.160.249.60","cc":"SG"}""")
                else -> ProbeResult(9, "")
            }
        }
        t.run(listOf(1), TestKinds.allKinds)
        t.awaitIdle()
        val probes = core.calls.filter { it.startsWith("probe ") }.toSet()
        assertEquals(
            setOf(
                "probe icmp|||1.2.3.4||0|3000",
                "probe loss|||1.2.3.4:443||10|2000",
                "probe udp|sing-box|CFG|8.8.8.8:53|www.google.com|0|5000",
                "probe download|sing-box|CFG||https://speed.cloudflare.com/__down?bytes=25000000|0|15000",
                "probe upload|sing-box|CFG||https://speed.cloudflare.com/__up|3|15000",
                "probe geo|sing-box|CFG||https://www.cloudflare.com/cdn-cgi/trace|0|8000",
                "probe media|sing-box|CFG||netflix|0|15000",
                "probe ai|sing-box|CFG||chatgpt,claude,gemini|0|15000",
                "probe dnsleak|sing-box|CFG|||0|25000",
            ),
            probes,
        )
        assertEquals("""{"ms":1}""", res(1, "udp").toString()) // report discarded, max(1, 0)
        assertEquals("""{"ms":9}""", res(1, "icmp").toString())
        assertEquals("超时", (res(1, "loss")!!["err"] as JsonPrimitive).content) // loss >= 1
        assertEquals("""{"ip":"43.160.249.60","cc":"SG"}""", res(1, "geo").toString())
        assertEquals(TestKinds.allKinds.toSet(), storeRef.node(1)!!.res.keys)
    }

    @Test
    fun concurrencyIsCappedAndSpeedTestsAreSerial() {
        val t = engine(nodes = manyNodes(12))
        core.gate = CompletableDeferred()
        t.run((1..12).toList(), listOf("url", "down"))
        runBlocking {
            withTimeout(5_000) { while (core.active.get() < TestEngine.MAX_ACTIVE) delay(5) }
            delay(100)
        }
        assertEquals(TestEngine.MAX_ACTIVE, core.active.get())
        assertEquals(24, t.running.value.size)
        core.gate!!.complete(Unit)
        t.awaitIdle()
        assertEquals(TestEngine.MAX_ACTIVE, core.maxActive.get())
        assertEquals(1, core.maxSpeedActive.get())
        assertEquals(24, core.calls.count { it.startsWith("url ") || it.startsWith("probe download") })
    }

    @Test
    fun runningJobsAreDeduplicatedAndUnsupportedSkipped() {
        val t = engine()
        core.gate = CompletableDeferred()
        t.run(listOf(1), listOf("url"))
        t.run(listOf(1), listOf("url"))
        unsupported = setOf("vmess")
        t.run(listOf(2, 99), listOf("tcp"))
        assertTrue(t.isRunning(1, "url"))
        assertFalse(t.isRunning(2, "tcp"))
        core.gate!!.complete(Unit)
        t.awaitIdle()
        assertEquals(1, core.calls.count { it.startsWith("url ") })
        assertEquals(0, core.calls.count { it.startsWith("tcp ") })
        assertEquals(42, storeRef.node(1)!!.json["lat"].asInt())
    }

    @Test
    fun saveKeepsOtherResults() {
        val t = engine()
        t.save(1, "tcp", jsonObjectOf("ms" to 5))
        t.save(1, "url", jsonObjectOf("err" to "失败"))
        assertEquals("""{"tcp":{"ms":5},"url":{"err":"失败"}}""", storeRef.node(1)!!.res.toString())
        assertEquals(-1, storeRef.node(1)!!.lat)
    }

    private fun manyNodes(n: Int) = (1..n).joinToString(",", "[", "]") {
        """{"id":$it,"group":"默认","lat":0,"node":{"name":"n$it","protocol":"vless","server":"10.0.0.$it","port":1}}"""
    }

    private companion object {
        const val NODES = """[
            {"id":1,"group":"默认","lat":0,"node":{"name":"a","protocol":"vless","server":"1.2.3.4","port":443}},
            {"id":2,"group":"默认","lat":0,"node":{"name":"b","protocol":"vmess","server":"2001:db8::1","port":8443}}
        ]"""
    }
}
