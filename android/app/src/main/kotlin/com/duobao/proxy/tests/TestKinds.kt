package com.duobao.proxy.tests

import com.duobao.proxy.data.EMPTY
import com.duobao.proxy.data.toJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * One test kind of `Tests.qml`: [k] id, [label] menu label, [short] column title, [width] column
 * width (dp ≈ desktop px), [section] menu section, [description] what it does.
 */
data class TestKind(val k: String, val label: String, val short: String, val width: Int, val section: Int, val description: String)

/** One field the 测试设置 page edits: [type] is `text`, `int` or `services` (then [choices]). */
data class TestField(val f: String, val label: String, val type: String, val hint: String = "", val choices: List<ServiceName> = emptyList())

/** A media / AI service: report key, display name, short code for the list cell. */
data class ServiceName(val key: String, val name: String, val code: String)

/** The static tables of `Tests.qml` and the `ui.tests` option lookup. */
object TestKinds {
    val kinds: List<TestKind> = listOf(
        TestKind("url", "真连接延迟", "真连接", 64, 0, "通过节点访问一个网址，计算往返时间"),
        TestKind("tcp", "TCP 延迟", "TCP", 60, 0, "直接连接节点服务器的端口，不经过代理"),
        TestKind("icmp", "ICMP Ping", "ICMP", 60, 0, "直接 ping 节点服务器；很多服务器禁 ping"),
        TestKind("udp", "UDP 延迟", "UDP", 60, 0, "通过节点发一次 DNS 查询，也能看出是否支持 UDP"),
        TestKind("loss", "丢包 / 抖动", "丢包", 92, 0, "连续多次 TCP 连接，统计平均、抖动和丢包"),
        TestKind("down", "下载速度", "下载", 76, 1, "通过节点下载测速文件"),
        TestKind("up", "上传速度", "上传", 76, 1, "通过节点上传测速数据"),
        TestKind("geo", "出口 IP 与地区", "地区", 76, 1, "通过节点查询对方看到的 IP 和所在地区"),
        TestKind("media", "流媒体解锁", "流媒体", 96, 2, "Netflix、Disney+、YouTube Premium 能否使用"),
        TestKind("ai", "AI 服务可用性", "AI", 96, 2, "ChatGPT、Claude、Gemini 能否访问"),
        TestKind("dns", "DNS 泄漏检测", "DNS", 60, 2, "检查 DNS 查询有没有绕过代理（使用 bash.ws）"),
    )

    val mediaNames = listOf(ServiceName("netflix", "Netflix", "N"), ServiceName("disney", "Disney+", "D"), ServiceName("youtube", "YouTube Premium", "Y"))
    val aiNames = listOf(ServiceName("chatgpt", "ChatGPT", "GPT"), ServiceName("claude", "Claude", "Cl"), ServiceName("gemini", "Gemini", "Ge"))

    /** Per-kind defaults (`Tests.defaults`). */
    val defaults: Map<String, JsonObject> = mapOf(
        "url" to obj("url" to "http://cp.cloudflare.com/", "timeout" to 8),
        "tcp" to obj("timeout" to 5),
        "icmp" to obj("timeout" to 3),
        "udp" to obj("server" to "8.8.8.8:53", "domain" to "www.google.com", "timeout" to 5),
        "loss" to obj("count" to 10, "timeout" to 2),
        "down" to obj("url" to "https://speed.cloudflare.com/__down?bytes=25000000", "timeout" to 15),
        "up" to obj("url" to "https://speed.cloudflare.com/__up", "size" to 10, "timeout" to 15),
        "geo" to obj("url" to "https://www.cloudflare.com/cdn-cgi/trace", "timeout" to 8),
        "media" to obj("services" to listOf("netflix", "disney", "youtube"), "timeout" to 15),
        "ai" to obj("services" to listOf("chatgpt", "claude", "gemini"), "timeout" to 15),
        "dns" to obj("timeout" to 25),
    )

    /** What the settings page edits for each kind (`Tests.fields`). */
    val fields: Map<String, List<TestField>> = mapOf(
        "url" to listOf(TestField("url", "测试网址", "text", "返回 2xx / 3xx 即视为成功"), TestField("timeout", "超时（秒）", "int")),
        "tcp" to listOf(TestField("timeout", "超时（秒）", "int")),
        "icmp" to listOf(TestField("timeout", "超时（秒）", "int")),
        "udp" to listOf(TestField("server", "DNS 服务器", "text", "IP:端口"), TestField("domain", "查询域名", "text"), TestField("timeout", "超时（秒）", "int")),
        "loss" to listOf(TestField("count", "次数", "int"), TestField("timeout", "每次超时（秒）", "int")),
        "down" to listOf(TestField("url", "下载网址", "text", "文件越大测得越准"), TestField("timeout", "最长时间（秒）", "int")),
        "up" to listOf(TestField("url", "上传网址", "text", "需接受 POST 上传"), TestField("size", "上传大小（MB）", "int"), TestField("timeout", "最长时间（秒）", "int")),
        "geo" to listOf(TestField("url", "查询网址", "text", "需返回 Cloudflare trace 格式（ip= / loc=）"), TestField("timeout", "超时（秒）", "int")),
        "media" to listOf(TestField("services", "检测项目", "services", choices = mediaNames), TestField("timeout", "超时（秒）", "int")),
        "ai" to listOf(TestField("services", "检测项目", "services", choices = aiNames), TestField("timeout", "超时（秒）", "int")),
        "dns" to listOf(TestField("timeout", "超时（秒）", "int")),
    )

    val allKinds: List<String> = kinds.map { it.k }
    val latencyKinds = listOf("url", "tcp", "icmp", "udp", "loss")

    fun kind(k: String): TestKind = kinds.firstOrNull { it.k == k } ?: TestKind(k, k, k, 64, 0, "")

    fun isSpeed(k: String) = k == "down" || k == "up"

    /**
     * `Tests.opt(k, f)` over a `ui` object: the user value unless it is missing, `""` or an empty
     * array; then the legacy `ui.test_url` for the url test; then the default.
     */
    fun opt(ui: JsonObject, k: String, f: String): JsonElement {
        val v = ((ui["tests"] as? JsonObject)?.get(k) as? JsonObject)?.get(f)
        if (v != null && !(v is JsonPrimitive && v.isString && v.content.isEmpty()) && !(v is JsonArray && v.isEmpty())) return v
        if (k == "url" && f == "url") {
            val legacy = ui["test_url"]
            if (legacy != null && jsTruthy(legacy)) return legacy
        }
        return defaults[k]?.get(f) ?: JsonNull
    }

    fun optString(ui: JsonObject, k: String, f: String): String = jsString(opt(ui, k, f))

    /** `Number(opt(k, f)) || fallback`. */
    fun optNumber(ui: JsonObject, k: String, f: String, fallback: Int): Int {
        val n = jsNumber(opt(ui, k, f))
        return if (n.isNaN() || n == 0.0) fallback else n.toInt()
    }

    fun optList(ui: JsonObject, k: String, f: String): List<String> = when (val v = opt(ui, k, f)) {
        is JsonArray -> v.map { jsString(it) }
        else -> listOf(jsString(v))
    }

    /** `Tests.ms(k)`: `max(1, Number(timeout) || default) * 1000`. */
    fun ms(ui: JsonObject, k: String): Int {
        val def = jsNumber(defaults[k]?.get("timeout") ?: JsonNull).takeUnless { it.isNaN() } ?: 5.0
        val n = jsNumber(opt(ui, k, "timeout")).let { if (it.isNaN() || it == 0.0) def else it }
        return (maxOf(1.0, n) * 1000).toInt()
    }

    /** `Tests.setOpt`: the new `ui.tests` object with `tests[k][f] = v`. */
    fun withOpt(ui: JsonObject, k: String, f: String, v: Any?): JsonObject {
        val all = LinkedHashMap(ui["tests"] as? JsonObject ?: EMPTY)
        val o = LinkedHashMap(all[k] as? JsonObject ?: EMPTY)
        o[f] = v.toJson()
        all[k] = JsonObject(o)
        return JsonObject(all)
    }

    /** `Tests.resetOpts`: the new `ui.tests` object without [k] (the caller also clears `test_url` for url). */
    fun withoutOpts(ui: JsonObject, k: String): JsonObject =
        JsonObject(LinkedHashMap(ui["tests"] as? JsonObject ?: EMPTY).apply { remove(k) })

    // ---- JS value semantics used by the QML ----

    fun jsTruthy(v: JsonElement?): Boolean = when (v) {
        null, JsonNull -> false
        is JsonPrimitive -> when {
            v.isString -> v.content.isNotEmpty()
            v.booleanOrNull != null -> v.booleanOrNull!!
            else -> v.doubleOrNull.let { it != null && it != 0.0 && !it.isNaN() }
        }
        else -> true
    }

    /** JS `Number(v)`. */
    fun jsNumber(v: JsonElement?): Double = when (v) {
        null -> Double.NaN
        JsonNull -> 0.0
        is JsonPrimitive -> when {
            v.isString -> v.content.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() ?: Double.NaN }
            v.booleanOrNull != null -> if (v.booleanOrNull!!) 1.0 else 0.0
            else -> v.doubleOrNull ?: Double.NaN
        }
        is JsonArray -> if (v.isEmpty()) 0.0 else if (v.size == 1) jsNumber(v[0]) else Double.NaN
        else -> Double.NaN
    }

    /** JS `String(v)` for scalars. */
    fun jsString(v: JsonElement?): String = when (v) {
        null -> "undefined"
        JsonNull -> "null"
        is JsonPrimitive -> v.content
        is JsonArray -> v.joinToString(",") { jsString(it) }
        else -> "[object Object]"
    }

    private fun obj(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (k, v) -> k to v.toJson() })
}
