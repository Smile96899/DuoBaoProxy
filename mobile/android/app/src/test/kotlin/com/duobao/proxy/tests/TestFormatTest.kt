package com.duobao.proxy.tests

import com.duobao.proxy.data.NodeEntry
import com.duobao.proxy.data.parseObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TestFormatTest {
    private fun entry(json: String) = NodeEntry(parseObject(json))
    private fun res(k: String, r: String, lat: Int = 0) =
        entry("""{"id":1,"group":"默认","lat":$lat,"node":{"protocol":"vless","server":"a","port":1},"res":{"$k":$r}}""")

    @Test
    fun latencyThresholds() {
        assertEquals(Cell("79 ms", Tone.Good), TestFormat.cell(res("url", """{"ms":79}"""), "url"))
        assertEquals(Cell("80 ms", Tone.Warn), TestFormat.cell(res("tcp", """{"ms":80}"""), "tcp"))
        assertEquals(Cell("149 ms", Tone.Warn), TestFormat.cell(res("icmp", """{"ms":149}"""), "icmp"))
        assertEquals(Cell("150 ms", Tone.Bad), TestFormat.cell(res("udp", """{"ms":150}"""), "udp"))
        assertEquals(Cell("超时", Tone.Bad), TestFormat.cell(res("url", """{"err":"超时","detail":"x"}"""), "url"))
    }

    @Test
    fun legacyLatFallback() {
        val e = entry("""{"id":1,"group":"g","lat":120,"node":{}}""")
        assertEquals(Cell("120 ms", Tone.Warn), TestFormat.cell(e, "url"))
        assertEquals(Cell("超时", Tone.Bad), TestFormat.cell(entry("""{"id":1,"lat":-1,"node":{}}"""), "url"))
        assertNull(TestFormat.cell(entry("""{"id":1,"lat":0,"node":{}}"""), "url"))
        assertNull(TestFormat.cell(e, "tcp"))
    }

    @Test
    fun lossSpeedGeo() {
        assertEquals(Cell("40ms · 0%", Tone.Good), TestFormat.cell(res("loss", """{"avg":40,"min":30,"max":50,"jitter":5,"loss":0}"""), "loss"))
        assertEquals(Cell("40ms · 10%", Tone.Warn), TestFormat.cell(res("loss", """{"avg":40,"loss":0.1}"""), "loss"))
        assertEquals(Cell("40ms · 25%", Tone.Bad), TestFormat.cell(res("loss", """{"avg":40,"loss":0.25}"""), "loss"))
        assertEquals(
            "平均 40 ms · 最小 30 · 最大 50 · 抖动 5 ms · 丢包 0%",
            TestFormat.detail(res("loss", """{"avg":40,"min":30,"max":50,"jitter":5,"loss":0}"""), "loss"),
        )
        assertEquals(Cell("2.5 MB/s", Tone.Fg), TestFormat.cell(res("down", """{"bps":2621440}"""), "down"))
        assertEquals(Cell("513 KB/s", Tone.Fg), TestFormat.cell(res("up", """{"bps":524800}"""), "up"))
        assertEquals(Cell("—", Tone.Fg), TestFormat.cell(res("up", """{"bps":0}"""), "up"))
        assertEquals(Cell("SG 新加坡", Tone.Fg), TestFormat.cell(res("geo", """{"ip":"43.160.249.60","cc":"SG"}"""), "geo"))
        assertEquals("SG 新加坡 · 43.160.249.60", TestFormat.detail(res("geo", """{"ip":"43.160.249.60","cc":"SG"}"""), "geo"))
        assertEquals(Cell("XX XX", Tone.Fg), TestFormat.cell(res("geo", """{"cc":"XX"}"""), "geo"))
        assertEquals(Cell("—", Tone.Fg), TestFormat.cell(res("geo", """{"ip":"1.2.3.4"}"""), "geo"))
    }

    @Test
    fun mediaAiDns() {
        val m = res("media", """{"netflix":{"ok":true,"region":"SG"},"disney":{"ok":false,"note":"blocked"}}""")
        assertEquals(Cell("N✓ D✗", Tone.Warn), TestFormat.cell(m, "media"))
        assertEquals("Netflix ✓ 新加坡\nDisney+ ✗（blocked）", TestFormat.detail(m, "media"))
        assertEquals(Cell("GPT✓ Cl✓ Ge✓", Tone.Good), TestFormat.cell(res("ai", """{"chatgpt":{"ok":true},"claude":{"ok":true},"gemini":{"ok":true}}"""), "ai"))
        assertEquals(Cell("GPT✗", Tone.Bad), TestFormat.cell(res("ai", """{"chatgpt":{"ok":false}}"""), "ai"))
        val d = res(
            "dns",
            """{"leak":false,"local":"KH","servers":[{"cc":"US","asn":"AS15169 Google LLC"},{"cc":"US","asn":"AS15169 Google LLC"},{"cc":"SG","asn":"AS13335 Cloudflare"}]}""",
        )
        assertEquals(Cell("无泄漏", Tone.Good), TestFormat.cell(d, "dns"))
        assertEquals("无泄漏（本地网络在KH）\nDNS 服务器：\n美国 · Google LLC（2 个）\n新加坡 · Cloudflare（1 个）", TestFormat.detail(d, "dns"))
        assertEquals("可能泄漏：本地网络所在地（中国）的 DNS 收到了查询", TestFormat.detail(res("dns", """{"leak":true,"local":"CN"}"""), "dns"))
    }

    @Test
    fun errorDetailIsFirstLineCappedAt80() {
        val long = "x".repeat(100)
        assertEquals("失败（" + "x".repeat(80) + "）", TestFormat.detail(res("url", """{"err":"失败","detail":"$long\nsecond"}"""), "url"))
        assertEquals("超时", TestFormat.detail(res("url", """{"err":"超时"}"""), "url"))
    }

    @Test
    fun sortValues() {
        assertEquals(42.0, TestFormat.sortValue(res("url", """{"ms":42}"""), "url"), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, TestFormat.sortValue(res("url", """{"err":"失败"}"""), "url"), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, TestFormat.sortValue(res("url", """{"ms":1}"""), "tcp"), 0.0)
        assertEquals(-1000.0, TestFormat.sortValue(res("down", """{"bps":1000}"""), "down"), 0.0)
        assertEquals(10040.0, TestFormat.sortValue(res("loss", """{"avg":40,"loss":0.1}"""), "loss"), 1e-6)
    }
}
