package com.duobao.proxy.state

import com.duobao.proxy.data.asString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The 规则 page on top of `settings.routing.{proxy,direct,block}_{domain,ip}` (desktop RuleBox
 * syntax, spec/gui-groups-routes.md §2.2). A preset is "on" when all its entries are present;
 * every other entry is a custom rule. Pure.
 */
object Rules {
    const val PROXY = "proxy"
    const val DIRECT = "direct"
    const val BLOCK = "block"

    /** One 分流策略 switch: [entries] maps a routing list key (`direct_domain`) to its values. */
    data class Preset(val id: String, val title: String, val outbound: String, val detail: String, val icon: String, val entries: Map<String, List<String>>)

    val presets = listOf(
        Preset("ads", "广告拦截", BLOCK, "GeoSite 广告", "ban", mapOf("block_domain" to listOf("geosite:category-ads-all"))),
        Preset("cn", "国内网站直连", DIRECT, "GeoSite CN", "arrowRight", mapOf("direct_domain" to listOf("geosite:cn"), "direct_ip" to listOf("geoip:cn"))),
        Preset("lan", "局域网直连", DIRECT, "私有网段", "arrowRight", mapOf("direct_domain" to listOf("geosite:private"), "direct_ip" to listOf("geoip:private"))),
        Preset("media", "流媒体解锁", PROXY, "Netflix / YouTube / Disney+", "globe", mapOf("proxy_domain" to listOf("geosite:netflix", "geosite:youtube", "geosite:disney"))),
        Preset("apple", "苹果服务直连", DIRECT, "iCloud / App Store", "arrowRight", mapOf("direct_domain" to listOf("geosite:apple"))),
    )

    val listKeys = listOf("proxy_domain", "direct_domain", "block_domain", "proxy_ip", "direct_ip", "block_ip")

    fun outboundLabel(out: String) = when (out) {
        PROXY -> "代理"
        DIRECT -> "直连"
        else -> "拦截"
    }

    /** `settings.routing[key]` as strings. */
    fun list(settings: JsonObject, key: String): List<String> =
        ((settings["routing"] as? JsonObject)?.get(key) as? JsonArray)?.map { it.asString() } ?: emptyList()

    fun enabled(settings: JsonObject, p: Preset): Boolean =
        p.entries.all { (k, vs) -> list(settings, k).containsAll(vs) }

    /** The new lists after switching [p] on or off: `key → list` for every list it touches. */
    fun toggle(settings: JsonObject, p: Preset, on: Boolean): Map<String, List<String>> =
        p.entries.mapValues { (k, vs) ->
            val cur = list(settings, k)
            if (on) cur + vs.filter { it !in cur } else cur.filter { it !in vs }
        }

    /** A custom rule: stored [value] in list [key]. */
    data class Rule(val key: String, val value: String) {
        val outbound: String get() = key.substringBefore('_')
        val isIp: Boolean get() = key.endsWith("_ip")

        /** DOMAIN / DOMAIN-SUFFIX / DOMAIN-KEYWORD / DOMAIN-REGEX / GEOSITE / GEOIP / IP-CIDR. */
        val type: String get() = typeOf(value, isIp)

        /** What the row shows: `*.google.com` for suffixes, the bare value otherwise. */
        val display: String get() = when (type) {
            "DOMAIN-SUFFIX" -> "*." + body(value)
            else -> body(value)
        }
    }

    /** The rule types offered by 添加规则, in menu order. */
    val types = listOf("DOMAIN-SUFFIX", "DOMAIN", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "GEOSITE", "IP-CIDR", "GEOIP")

    fun typeOf(value: String, isIp: Boolean): String {
        if (isIp) return if (value.startsWith("geoip:")) "GEOIP" else "IP-CIDR"
        return when {
            value.startsWith("full:") -> "DOMAIN"
            value.startsWith("keyword:") -> "DOMAIN-KEYWORD"
            value.startsWith("regexp:") -> "DOMAIN-REGEX"
            value.startsWith("geosite:") -> "GEOSITE"
            else -> "DOMAIN-SUFFIX"
        }
    }

    private val prefixes = listOf("domain:", "full:", "keyword:", "regexp:", "geosite:", "geoip:")

    /** The value without its type prefix (IPv6 CIDRs contain `:` but no prefix). */
    private fun body(v: String) = prefixes.firstOrNull { v.startsWith(it) }?.let { v.removePrefix(it) } ?: v

    /** Stored form of a rule the user typed: `(list key, value)`; `*.` and `。` are normalised. */
    fun encode(type: String, input: String, outbound: String): Pair<String, String> {
        val t = input.trim().replace('。', '.')
        val isIp = type == "IP-CIDR" || type == "GEOIP"
        val key = outbound + if (isIp) "_ip" else "_domain"
        val v = when (type) {
            "DOMAIN" -> "full:$t"
            "DOMAIN-KEYWORD" -> "keyword:$t"
            "DOMAIN-REGEX" -> "regexp:$t"
            "GEOSITE" -> "geosite:" + t.removePrefix("geosite:").lowercase()
            "GEOIP" -> "geoip:" + t.removePrefix("geoip:").lowercase()
            "IP-CIDR" -> t
            else -> "domain:" + t.removePrefix("*.").removePrefix(".")
        }
        return key to v
    }

    /**
     * Entries of the six lists that no switched-on preset owns (comments and blanks skipped); an
     * entry of a partly present preset stays visible here.
     */
    fun custom(settings: JsonObject): List<Rule> {
        val owned = presets.filter { enabled(settings, it) }.flatMap { p -> p.entries.flatMap { (k, vs) -> vs.map { k to it } } }.toSet()
        return listKeys.flatMap { k -> list(settings, k).filter { it.isNotBlank() && !it.startsWith("#") && (k to it) !in owned }.map { Rule(k, it) } }
    }

    /** 规则集: distinct `geosite:` / `geoip:` entries in use. */
    fun ruleSetCount(settings: JsonObject): Int =
        listKeys.flatMap { list(settings, it) }.filter { it.startsWith("geosite:") || it.startsWith("geoip:") }.toSet().size

    /** `settings.routing.final`: proxy (default) or bypass. */
    fun final(settings: JsonObject): String = (settings["routing"] as? JsonObject)?.get("final").asString().ifEmpty { PROXY }
}
