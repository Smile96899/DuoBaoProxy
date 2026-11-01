package com.duobao.proxy.state

import com.duobao.proxy.data.Group
import com.duobao.proxy.data.NodeEntry
import com.duobao.proxy.data.jsonObjectOf
import com.duobao.proxy.data.parseObject
import com.duobao.proxy.ui.components.DbpIcons
import com.duobao.proxy.ui.looksImportable
import com.duobao.proxy.ui.screens.home.Countries
import com.duobao.proxy.ui.screens.settings.dnsKind
import com.duobao.proxy.ui.screens.subs.ago
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileLogicTest {
    private fun entry(id: Int, name: String, group: String, res: Map<String, Any?>? = null) = NodeEntry(
        jsonObjectOf(
            "id" to id, "group" to group, "lat" to 0,
            "node" to mapOf("protocol" to "vless", "name" to name, "server" to "h$id", "port" to 443),
            "res" to (res ?: emptyMap<String, Any?>()),
        ),
    )

    // ---- NodeList ----

    @Test
    fun sectionsKeepEmptyGroupsAndAddOrphans() {
        val groups = listOf(Group(jsonObjectOf("name" to "默认")), Group(jsonObjectOf("name" to "空")))
        val nodes = listOf(entry(1, "a", "默认"), entry(2, "b", "孤儿"))
        val s = NodeList.sections(nodes, groups, "", "default", emptyList())
        assertEquals(listOf("默认", "空", "孤儿"), s.map { it.group })
        assertEquals(listOf(listOf(1), emptyList(), listOf(2)), s.map { sec -> sec.nodes.map { it.id } })
    }

    @Test
    fun queryHidesEmptyGroupsAndMatchesServerAndGroup() {
        val groups = listOf(Group(jsonObjectOf("name" to "默认")), Group(jsonObjectOf("name" to "JP")))
        val nodes = listOf(entry(1, "Tokyo", "JP"), entry(2, "x", "默认"))
        assertEquals(listOf("JP"), NodeList.sections(nodes, groups, "tok", "default", emptyList()).map { it.group })
        assertEquals(listOf("默认"), NodeList.sections(nodes, groups, "H2", "default", emptyList()).map { it.group })
        assertEquals(listOf("JP"), NodeList.sections(nodes, groups, "jp", "default", emptyList()).map { it.group })
    }

    @Test
    fun latencySortPutsFailuresLast() {
        val nodes = listOf(
            entry(1, "a", "g", mapOf("url" to mapOf("err" to "超时"))),
            entry(2, "b", "g", mapOf("url" to mapOf("ms" to 90))),
            entry(3, "c", "g"),
            entry(4, "d", "g", mapOf("url" to mapOf("ms" to 20))),
        )
        assertEquals(listOf(4, 2), NodeList.sorted(nodes, "latency", emptyList()).map { it.id }.take(2))
        assertEquals(listOf(1, 2, 3, 4), NodeList.sorted(nodes, "default", emptyList()).map { it.id })
        assertEquals(listOf(1, 2, 3, 4), NodeList.sorted(nodes, "name", emptyList()).map { it.id })
    }

    // ---- NodeSpecs ----

    @Test
    fun newDraftPresets() {
        assertEquals(1080, (NodeSpecs.newDraft("socks")["port"] as JsonPrimitive).content.toInt())
        assertEquals("tls", NodeSpecs.text(NodeSpecs.newDraft("trojan"), "stream.sec"))
        assertEquals("3", NodeSpecs.text(NodeSpecs.newDraft("shadowtls"), "shadowtls.version"))
        assertEquals("10.0.0.2/32", NodeSpecs.text(NodeSpecs.newDraft("wireguard"), "wireguard.local_address"))
    }

    @Test
    fun setAtCreatesParentsAndDeletesEmptyValues() {
        var d = NodeSpecs.newDraft("vless")
        d = NodeSpecs.setAt(d, "stream.sni", JsonPrimitive("a.com"))
        assertEquals("a.com", NodeSpecs.text(d, "stream.sni"))
        d = NodeSpecs.setAt(d, "stream.sni", JsonPrimitive(""))
        assertFalse(d.containsKey("stream"))
        d = NodeSpecs.setAt(d, "stream.insecure", JsonPrimitive(true))
        assertTrue(NodeSpecs.bool(d, "stream.insecure"))
        d = NodeSpecs.setAt(d, "stream.insecure", JsonPrimitive(false))
        assertFalse(d.containsKey("stream"))
        assertNull(NodeSpecs.intValue(""))
    }

    @Test
    fun fieldsFollowTheProtocol() {
        val labels = { p: String -> NodeSpecs.fields(NodeSpecs.newDraft(p)).map { it.path } }
        assertTrue("stream.net" in labels("vless"))
        assertTrue("quic_tls.sni" in labels("hysteria2"))
        assertFalse("stream.net" in labels("wireguard"))
        assertFalse("stream.net" in labels("socks"))
        assertTrue("stream.net" in NodeSpecs.fields(NodeSpecs.setAt(NodeSpecs.newDraft("socks"), "stream.sec", JsonPrimitive("tls"))).map { it.path })
    }

    @Test
    fun validateChecksAddressAndPort() {
        val d = NodeSpecs.newDraft("vless")
        assertEquals("地址不能为空", NodeSpecs.validate(d).exceptionOrNull()?.message)
        val ok = NodeSpecs.setAt(NodeSpecs.setAt(d, "server", JsonPrimitive("h")), "port", JsonPrimitive("8443"))
        assertEquals(JsonPrimitive(8443), NodeSpecs.validate(ok).getOrThrow()["port"])
        val bad = NodeSpecs.setAt(ok, "port", JsonPrimitive(70000))
        assertEquals("端口要在 1–65535 之间", NodeSpecs.validate(bad).exceptionOrNull()?.message)
    }

    // ---- Rules ----

    private fun settings(routing: String) = parseObject("""{"routing":$routing}""")

    @Test
    fun presetsAreOnWhenAllEntriesArePresent() {
        val s = settings("""{"direct_domain":["geosite:cn","geosite:private"],"direct_ip":["geoip:cn"],"block_domain":["geosite:category-ads-all"]}""")
        val byId = Rules.presets.associateBy { it.id }
        assertTrue(Rules.enabled(s, byId.getValue("ads")))
        assertTrue(Rules.enabled(s, byId.getValue("cn")))
        assertFalse(Rules.enabled(s, byId.getValue("lan")))
        assertEquals(mapOf("direct_domain" to listOf("geosite:private"), "direct_ip" to emptyList<String>()), Rules.toggle(s, byId.getValue("cn"), false))
        assertEquals(listOf("geosite:cn", "geosite:private"), Rules.toggle(s, byId.getValue("lan"), true)["direct_domain"])
        assertEquals(listOf("geoip:cn", "geoip:private"), Rules.toggle(s, byId.getValue("lan"), true)["direct_ip"])
    }

    @Test
    fun customRulesAreTheUnownedEntries() {
        val s = settings("""{"proxy_domain":["domain:google.com","geosite:netflix","# note"],"direct_ip":["geoip:cn","10.0.0.0/8"],"block_domain":["full:ads.example.com"]}""")
        val custom = Rules.custom(s)
        assertEquals(
            listOf("proxy_domain" to "domain:google.com", "proxy_domain" to "geosite:netflix", "block_domain" to "full:ads.example.com", "direct_ip" to "geoip:cn", "direct_ip" to "10.0.0.0/8"),
            custom.map { it.key to it.value },
        )
        assertEquals(listOf("*.google.com", "netflix", "ads.example.com", "cn", "10.0.0.0/8"), custom.map { it.display })
        assertEquals(listOf("DOMAIN-SUFFIX", "GEOSITE", "DOMAIN", "GEOIP", "IP-CIDR"), custom.map { it.type })
        assertEquals(listOf("proxy", "proxy", "block", "direct", "direct"), custom.map { it.outbound })
        assertEquals(2, Rules.ruleSetCount(s))
        // Once the whole preset is present its entries leave the custom list.
        val cnOn = settings("""{"direct_domain":["geosite:cn"],"direct_ip":["geoip:cn"]}""")
        assertTrue(Rules.custom(cnOn).isEmpty())
    }

    @Test
    fun encodeNormalisesInput() {
        assertEquals("proxy_domain" to "domain:google.com", Rules.encode("DOMAIN-SUFFIX", " *.google。com ", "proxy"))
        assertEquals("block_domain" to "full:a.com", Rules.encode("DOMAIN", "a.com", "block"))
        assertEquals("direct_ip" to "geoip:cn", Rules.encode("GEOIP", "CN", "direct"))
        assertEquals("direct_ip" to "fd00::/8", Rules.encode("IP-CIDR", "fd00::/8", "direct"))
        assertEquals("fd00::/8", Rules.Rule("direct_ip", "fd00::/8").display)
        assertEquals("proxy", Rules.final(parseObject("{}")))
    }

    // ---- misc ----

    @Test
    fun ipTraceParses() {
        assertEquals(IpInfo("1.2.3.4", "JP"), IpInfo.parse("fl=1\nip=1.2.3.4\nloc=jp\ncolo=NRT\n"))
        assertNull(IpInfo.parse("<html>"))
    }

    @Test
    fun countriesFromNames() {
        assertEquals("US", Countries.guess("美国 · 纽约"))
        assertEquals("HK", Countries.guess("🇭🇰 香港 03"))
        assertEquals("JP", Countries.guess("JP-Tokyo-01"))
        assertEquals("GB", Countries.guess("uk 01"))
        assertEquals("SG", Countries.guess("Singapore"))
        assertNull(Countries.guess("my node"))
        assertEquals("🇯🇵", Countries.flag("JP"))
        assertEquals("", Countries.flag("j"))
        val one = Countries.parseShapes("RO|250,460|200,440 300,440 300,480 200,480\nRO|0,0|10,10 20,10 20,20")
        assertEquals(25f to 46f, one.getValue("RO").label)
        assertEquals(2, one.getValue("RO").rings.size)
        assertEquals(30f, one.getValue("RO").rings[0][2])
        val all = Countries.parseShapes(java.io.File("src/main/assets/countries.txt").readText())
        assertTrue(all.size > 150)
        for (cc in listOf("CN", "US", "RO", "FR", "NO", "JP", "KH")) assertTrue(cc, cc in all)
        assertEquals(25f to 46f, Countries.point("RO", all))
    }

    @Test
    fun everyIconPathParses() {
        for (name in DbpIcons.paths.keys) assertTrue(name, DbpIcons.get(name).root.size > 0)
    }

    @Test
    fun smallFormatters() {
        assertEquals("刚刚", ago(1_000, now = 30_000))
        assertEquals("10 分钟前", ago(0, now = 600_000))
        assertEquals("2 小时前", ago(0, now = 7_200_000))
        assertEquals("DoH", dnsKind("https://dns.google/dns-query"))
        assertEquals("UDP", dnsKind("223.5.5.5"))
        assertEquals("系统", dnsKind("local"))
        assertTrue(looksImportable("vmess://abc"))
        assertTrue(looksImportable("https://sub.example.com/x"))
        assertFalse(looksImportable("hello"))
    }
}
