package com.duobao.proxy.tests

import com.duobao.proxy.data.NodeEntry
import com.duobao.proxy.data.jsonObjectOf
import com.duobao.proxy.tests.TestKinds.jsNumber
import com.duobao.proxy.tests.TestKinds.jsString
import com.duobao.proxy.tests.TestKinds.jsTruthy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.math.floor

/** Colour role of a result cell; the UI maps it to `DbpColors` (good = accent). */
enum class Tone { Good, Warn, Bad, Fg, Fg3 }

/** One list cell: short text and its colour role. */
data class Cell(val text: String, val tone: Tone)

/** Reading results: the `result` / `cell` / `detail` / `sortValue` half of `Tests.qml`. Pure. */
object TestFormat {
    /** `Theme.latColor`: 0/unknown fg3, negative bad, < 80 good, < 150 warn, else bad. */
    fun latTone(ms: Double?): Tone = when {
        ms == null || ms == 0.0 || ms.isNaN() -> Tone.Fg3
        ms < 0 -> Tone.Bad
        ms < 80 -> Tone.Good
        ms < 150 -> Tone.Warn
        else -> Tone.Bad
    }

    /**
     * The stored result of [k] on [entry]. Older nodes only have `lat`: `lat > 0` reads as `{ms}`
     * and `lat < 0` as `{err: "超时"}` for the url kind.
     */
    fun result(entry: NodeEntry?, k: String): JsonObject? {
        val r = entry?.res?.get(k) as? JsonObject
        if (r == null && k == "url" && entry != null && entry.lat != 0) {
            return if (entry.lat > 0) jsonObjectOf("ms" to entry.lat) else jsonObjectOf("err" to "超时")
        }
        return r
    }

    /** `X.X MB/s` from 1 MiB/s, `N KB/s` below, `—` for 0. */
    fun speed(bps: Double): String = when {
        bps == 0.0 || bps.isNaN() -> "—"
        bps >= 1048576 -> String.format(Locale.ROOT, "%.1f MB/s", bps / 1048576)
        else -> "${jsRound(bps / 1024)} KB/s"
    }

    /** ISO code → Chinese name (51 codes); unknown codes stay as they are. */
    val countryNames: Map<String, String> = mapOf(
        "CN" to "中国", "HK" to "香港", "TW" to "台湾", "MO" to "澳门", "JP" to "日本", "KR" to "韩国", "SG" to "新加坡", "MY" to "马来西亚", "TH" to "泰国",
        "VN" to "越南", "PH" to "菲律宾", "ID" to "印尼", "IN" to "印度", "AU" to "澳大利亚", "NZ" to "新西兰", "US" to "美国", "CA" to "加拿大", "MX" to "墨西哥",
        "BR" to "巴西", "AR" to "阿根廷", "GB" to "英国", "DE" to "德国", "FR" to "法国", "NL" to "荷兰", "IT" to "意大利", "ES" to "西班牙", "CH" to "瑞士",
        "SE" to "瑞典", "NO" to "挪威", "FI" to "芬兰", "DK" to "丹麦", "PL" to "波兰", "RU" to "俄罗斯", "UA" to "乌克兰", "TR" to "土耳其", "AE" to "阿联酋",
        "IL" to "以色列", "SA" to "沙特", "ZA" to "南非", "EG" to "埃及", "IE" to "爱尔兰", "AT" to "奥地利", "BE" to "比利时", "PT" to "葡萄牙", "CZ" to "捷克",
        "RO" to "罗马尼亚", "HU" to "匈牙利", "GR" to "希腊", "KZ" to "哈萨克斯坦", "LU" to "卢森堡", "IS" to "冰岛", "CL" to "智利", "CO" to "哥伦比亚",
    )

    fun country(cc: String?): String = if (cc.isNullOrEmpty()) "" else countryNames[cc] ?: cc

    private fun mark(s: JsonElement?): String = if (!jsTruthy(s)) "?" else if (jsTruthy((s as? JsonObject)?.get("ok"))) "✓" else "✗"

    /** Short text for a list column, or null when [k] has no result. */
    fun cell(entry: NodeEntry?, k: String): Cell? {
        val r = result(entry, k) ?: return null
        if (jsTruthy(r["err"])) return Cell(str(r["err"]), Tone.Bad)
        return when (k) {
            "url", "tcp", "icmp", "udp" -> Cell(num(r["ms"]) + " ms", latTone(jsNumber(r["ms"])))
            "loss" -> {
                val loss = jsNumber(r["loss"])
                val tone = if (loss > 0.2) Tone.Bad else if (loss > 0) Tone.Warn else latTone(jsNumber(r["avg"]))
                Cell(num(r["avg"]) + "ms · " + jsRound(loss * 100) + "%", tone)
            }
            "down", "up" -> Cell(speed(jsNumber(r["bps"])), Tone.Fg)
            "geo" -> Cell(if (jsTruthy(r["cc"])) str(r["cc"]) + " " + country(str(r["cc"])) else "—", Tone.Fg)
            "media", "ai" -> {
                val names = (if (k == "media") TestKinds.mediaNames else TestKinds.aiNames).filter { jsTruthy(r[it.key]) }
                val ok = names.count { jsTruthy((r[it.key] as? JsonObject)?.get("ok")) }
                val tone = if (ok == names.size) Tone.Good else if (ok > 0) Tone.Warn else Tone.Bad
                Cell(names.joinToString(" ") { it.code + mark(r[it.key]) }, tone)
            }
            "dns" -> if (jsTruthy(r["leak"])) Cell("泄漏", Tone.Bad) else Cell("无泄漏", Tone.Good)
            else -> null
        }
    }

    /** Full text for the detail column; "" when [k] has no result. */
    fun detail(entry: NodeEntry?, k: String): String {
        val r = result(entry, k) ?: return ""
        if (jsTruthy(r["err"])) {
            val d = r["detail"]
            return str(r["err"]) + if (jsTruthy(d)) "（" + str(d).split("\n")[0].take(80) + "）" else ""
        }
        return when (k) {
            "url", "tcp", "icmp", "udp" -> num(r["ms"]) + " ms"
            "loss" -> "平均 " + num(r["avg"]) + " ms · 最小 " + num(r["min"]) + " · 最大 " + num(r["max"]) + " · 抖动 " + num(r["jitter"]) +
                " ms · 丢包 " + jsRound(jsNumber(r["loss"]) * 100) + "%"
            "down", "up" -> speed(jsNumber(r["bps"]))
            "geo" -> (if (jsTruthy(r["cc"])) str(r["cc"]) + " " + country(str(r["cc"])) + " · " else "") + (if (jsTruthy(r["ip"])) str(r["ip"]) else "")
            "media", "ai" -> {
                val names = (if (k == "media") TestKinds.mediaNames else TestKinds.aiNames).filter { jsTruthy(r[it.key]) }
                names.joinToString("\n") { n ->
                    val s = r[n.key] as? JsonObject
                    if (s == null) {
                        n.name + " ?"
                    } else {
                        val where = if (jsTruthy(s["region"])) " " + country(str(s["region"])) else ""
                        n.name + " " + mark(s) + where + if (jsTruthy(s["note"])) "（" + str(s["note"]) + "）" else ""
                    }
                }
            }
            "dns" -> {
                // Group servers by country and operator so 20 Google addresses read as one line.
                val seen = LinkedHashMap<String, Int>()
                for (s in (r["servers"] as? JsonArray).orEmpty()) {
                    val o = s as? JsonObject ?: continue
                    val asn = if (jsTruthy(o["asn"])) str(o["asn"]) else ""
                    val key = country(if (jsTruthy(o["cc"])) str(o["cc"]) else "") + " · " + asn.replaceFirst(Regex("""^AS\d+\s*"""), "")
                    seen[key] = (seen[key] ?: 0) + 1
                }
                val lines = seen.map { (k2, n) -> "$k2（$n 个）" }
                val local = if (jsTruthy(r["local"])) str(r["local"]) else ""
                (if (jsTruthy(r["leak"])) "可能泄漏：本地网络所在地（" + country(local) + "）的 DNS 收到了查询"
                else "无泄漏" + if (local.isNotEmpty()) "（本地网络在" + country(local) + "）" else "") +
                    if (lines.isNotEmpty()) "\nDNS 服务器：\n" + lines.joinToString("\n") else ""
            }
            else -> ""
        }
    }

    /** Number used to sort by [k]; failures and untested sort last ([Double.POSITIVE_INFINITY]). */
    fun sortValue(entry: NodeEntry?, k: String): Double {
        val r = result(entry, k)
        if (r == null || jsTruthy(r["err"])) return Double.POSITIVE_INFINITY
        if (k == "down" || k == "up") return -(jsNumber(r["bps"]).takeUnless { it.isNaN() } ?: 0.0)
        if (k == "loss") return jsNumber(r["loss"]) * 100000 + jsNumber(r["avg"])
        val ms = jsNumber(r["ms"])
        return if (ms.isNaN() || ms == 0.0) Double.POSITIVE_INFINITY else ms
    }

    /** JS `Math.round`: half rounds up. */
    fun jsRound(x: Double): Long = floor(x + 0.5).toLong()

    private fun str(v: JsonElement?) = jsString(v)

    /** JS number → string: integral values without a fraction. */
    private fun num(v: JsonElement?): String {
        val d = jsNumber(v)
        return when {
            d.isNaN() -> if (v == null) "undefined" else "NaN"
            d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15 -> d.toLong().toString()
            else -> d.toString()
        }
    }
}
