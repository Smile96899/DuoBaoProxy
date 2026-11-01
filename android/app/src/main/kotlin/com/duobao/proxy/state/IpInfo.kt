package com.duobao.proxy.state

import java.net.HttpURLConnection
import java.net.URL

/** A public IP and its country, from a Cloudflare trace (`ip=` / `loc=` lines). */
data class IpInfo(val ip: String, val cc: String) {
    companion object {
        private const val TRACE = "https://www.cloudflare.com/cdn-cgi/trace"

        /** Parses a trace body; null when it has no `ip=`. */
        fun parse(body: String): IpInfo? {
            val kv = body.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            val ip = kv["ip"]?.takeIf { it.isNotEmpty() } ?: return null
            return IpInfo(ip, kv["loc"].orEmpty().uppercase())
        }

        /** Blocking direct fetch (the app process is outside the VPN). */
        fun fetchDirect(timeoutMs: Int = 6000): IpInfo? {
            val c = URL(TRACE).openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            return try {
                if (c.responseCode != 200) null else parse(c.inputStream.bufferedReader().use { it.readText() })
            } finally {
                c.disconnect()
            }
        }
    }
}
