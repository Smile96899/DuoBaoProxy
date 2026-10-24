package com.duobao.proxy.state

import com.duobao.proxy.data.toJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max

/** Live traffic of the exit outbound: rates in bytes/s, totals in bytes since the connect. */
data class TrafficState(val upRate: Long = 0, val downRate: Long = 0, val upTotal: Long = 0, val downTotal: Long = 0)

/**
 * Turns cumulative counters polled every second into rates, like `core_client.cpp` `pollTraffic`:
 * the rate is the delta since the previous poll (0 on the first poll), never negative.
 */
class TrafficMeter {
    private var last: Pair<Long, Long>? = null

    /** Feeds one poll of cumulative [up] / [down] bytes taken [intervalMs] after the previous one. */
    fun update(up: Long, down: Long, intervalMs: Long = 1000): TrafficState {
        val prev = last
        last = up to down
        if (prev == null) return TrafficState(0, 0, up, down)
        val scale = if (intervalMs > 0) 1000.0 / intervalMs else 1.0
        return TrafficState(
            upRate = (max(0L, up - prev.first) * scale).toLong(),
            downRate = (max(0L, down - prev.second) * scale).toLong(),
            upTotal = up,
            downTotal = down,
        )
    }

    fun reset() {
        last = null
    }
}

/** What the running tunnel was started with (critic A5: restored when the UI re-attaches). */
data class Session(
    val nodeId: Int,
    val nodeName: String,
    val coreId: String,
    val statsTags: List<String>,
    /** The PrepareSettings output the engine was started with; a change triggers a reconnect. */
    val appliedSettings: String,
    val startedAt: Long,
)

/** Outcome of an import or subscription update. */
data class ImportOutcome(val group: String, val added: Int, val errors: List<String>, val message: String, val ok: Boolean = true)

/** Subscription helpers (`GroupsPage.qml`). Pure. */
object Subscriptions {
    private val urlRe = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)

    /** A single `http(s)://` URL is a subscription, not a share link. */
    fun isSubscriptionUrl(text: String): Boolean = urlRe.matches(text.trim())

    /**
     * `subscription-userinfo` → group patch, exactly like `GroupsPage.qml:22-27`: split on `;`, trim
     * each part, split on `=`; a part with exactly two pieces sets `patch[key] = Number(value)`
     * (keys verbatim, NaN stored as null).
     */
    fun parseUserInfo(info: String): Map<String, JsonElement> {
        val patch = LinkedHashMap<String, JsonElement>()
        for (kv in info.split(";")) {
            val p = kv.trim().split("=")
            if (p.size == 2) patch[p[0]] = jsNumber(p[1])
        }
        return patch
    }

    /** JS `Number(string)` as a JSON value: "" → 0, non-numeric → null (NaN). */
    private fun jsNumber(s: String): JsonElement {
        val t = s.trim()
        if (t.isEmpty()) return 0.toJson()
        val d = when {
            t.startsWith("0x", ignoreCase = true) -> t.substring(2).toLongOrNull(16)?.toDouble()
            t == "Infinity" || t == "+Infinity" || t == "-Infinity" -> null
            else -> t.toDoubleOrNull()?.takeIf { it.isFinite() }
        } ?: return JsonNull
        return d.toJson()
    }

    /** `new Date().toISOString()`. */
    fun isoNow(now: Instant = Instant.now()): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(now)

    /** A free group name for a subscription [url]: its host, then `host (2)`, `host (3)` … */
    fun groupNameFor(url: String, taken: Set<String>): String {
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "订阅"
        if (host !in taken) return host
        var i = 2
        while ("$host ($i)" in taken) i++
        return "$host ($i)"
    }

    /** Group fields a subscription update writes (`updated` + userinfo). */
    fun updatePatch(userInfo: String, now: Instant = Instant.now()): JsonObject =
        JsonObject(LinkedHashMap<String, JsonElement>().apply {
            put("updated", isoNow(now).toJson())
            putAll(parseUserInfo(userInfo))
        })
}
