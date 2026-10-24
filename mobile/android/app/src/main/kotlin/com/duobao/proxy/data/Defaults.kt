package com.duobao.proxy.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Seeds from `app/src/store.cpp` `Store::load`. */
object Defaults {
    const val GROUP = "默认"

    /** Filled per key on every load when the key is absent (critic A8). */
    val ui: List<Pair<String, JsonElement>> = listOf(
        "layout" to "landscape",
        "theme" to "system",
        "core" to "sing-box",
        "selected" to -1,
        "proxy_mode" to "global",
        "mode" to "system",
        "test_url" to "http://cp.cloudflare.com/",
    ).map { (k, v) -> k to v.toJson() }

    /** Used only when the whole `settings` object is empty (critic A8). */
    val settings: JsonObject = jsonObjectOf(
        "log_level" to "info",
        "inbound" to mapOf("address" to "127.0.0.1", "port" to 2080, "sniff" to 1),
        "routing" to mapOf(
            "direct_domain" to listOf("geosite:cn", "geosite:private"),
            "direct_ip" to listOf("geoip:cn", "geoip:private"),
            "block_domain" to listOf("geosite:category-ads-all"),
            "final" to "proxy",
            "dns_routing" to true,
            "remote_dns" to "https://dns.google/dns-query",
            "direct_dns" to "https://doh.pub/dns-query",
        ),
        "tun" to mapOf("enabled" to false, "stack" to "gvisor", "mtu" to 9000),
        "mux" to mapOf("protocol" to "h2mux", "concurrency" to 8, "default_on" to false),
    )

    val group: JsonObject = jsonObjectOf("name" to GROUP)
}
