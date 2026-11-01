package com.duobao.proxy.core

import com.duobao.core.mobile.Mobile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** A rendered engine config (`RenderResult`). [statsTags] has the exit tag (`proxy`) first. */
data class Rendered(val config: String, val statsTags: List<String>, val frontConfig: String)

/** A probe outcome (`TestResult`): [ms] for latency-like probes, [report] the probe's JSON. */
data class ProbeResult(val ms: Int, val report: String)

/**
 * The Go calls the state and test layers need, as suspend functions on [GoCore]'s dispatchers.
 * Every failure is the gomobile `Exception` with the Go error text as message.
 * [TestEngine][com.duobao.proxy.tests.TestEngine] takes this interface so it can be tested on the JVM.
 */
interface CoreApi {
    /** `Mobile.prepareSettings`: App.renderSettings() + platform injection. */
    suspend fun prepareSettings(settingsJson: String, proxyMode: String, routeParts: String, tun: Boolean): String

    /** `Mobile.renderConfig`; [mode] 0 Full, 1 Test, 2 Export. */
    suspend fun renderConfig(coreId: String, chainJson: String, settingsJson: String, mode: Int): Rendered

    /** `Mobile.urlTest`; [config] "" uses the running engine. */
    suspend fun urlTest(coreId: String, config: String, url: String, timeoutMs: Int): Int

    /** `Mobile.tcpPing` (direct). */
    suspend fun tcpPing(address: String, timeoutMs: Int): Int

    /** `Mobile.probe` with the desktop argument conventions (see `go/mobile/test.go`). */
    suspend fun probe(name: String, coreId: String, config: String, address: String, url: String, count: Int, timeoutMs: Int): ProbeResult

    companion object {
        /** Render modes of `RenderConfig`. */
        const val MODE_FULL = 0
        const val MODE_TEST = 1
        const val MODE_EXPORT = 2
    }
}

/** [CoreApi] on the real Go library. */
object GoCoreApi : CoreApi {
    override suspend fun prepareSettings(settingsJson: String, proxyMode: String, routeParts: String, tun: Boolean) =
        GoCore.call { Mobile.prepareSettings(settingsJson, proxyMode, routeParts, tun) }

    override suspend fun renderConfig(coreId: String, chainJson: String, settingsJson: String, mode: Int) = GoCore.call {
        val r = Mobile.renderConfig(coreId, chainJson, settingsJson, mode)
        Rendered(r.config, parseTags(r.statsTagsJSON), r.frontConfig)
    }

    override suspend fun urlTest(coreId: String, config: String, url: String, timeoutMs: Int) =
        GoCore.call { Mobile.urlTest(coreId, config, url, timeoutMs) }

    override suspend fun tcpPing(address: String, timeoutMs: Int) = GoCore.call { Mobile.tcpPing(address, timeoutMs) }

    override suspend fun probe(name: String, coreId: String, config: String, address: String, url: String, count: Int, timeoutMs: Int) =
        GoCore.call {
            val r = Mobile.probe(name, coreId, config, address, url, count, timeoutMs)
            ProbeResult(r.ms, r.report)
        }

    /** A `StatsTagsJSON` array as a list. */
    fun parseTags(json: String): List<String> = runCatching {
        (Json.parseToJsonElement(json) as JsonArray).mapNotNull { (it as? JsonPrimitive)?.content }
    }.getOrDefault(emptyList())
}
