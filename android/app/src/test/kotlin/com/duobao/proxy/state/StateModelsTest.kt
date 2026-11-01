package com.duobao.proxy.state

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StateModelsTest {
    @Test
    fun userInfoIsParsedLikeGroupsPage() {
        val p = Subscriptions.parseUserInfo("upload=455727941; download=6174315083; total=1073741824000; expire=1798736000")
        assertEquals(JsonPrimitive(455727941L), p["upload"])
        assertEquals(JsonPrimitive(6174315083L), p["download"])
        assertEquals(JsonPrimitive(1073741824000L), p["total"])
        assertEquals(JsonPrimitive(1798736000L), p["expire"])
    }

    @Test
    fun userInfoEdgeCases() {
        // Keys verbatim (not lowercased), parts with != 2 pieces skipped, NaN → null, "" → 0.
        val p = Subscriptions.parseUserInfo("Upload=1;bad;a=b=c; x=abc ;y=;z= 2.5 ")
        assertEquals(JsonPrimitive(1), p["Upload"])
        assertFalse("bad" in p)
        assertFalse("a" in p)
        assertEquals(JsonNull, p["x"])
        assertEquals(JsonPrimitive(0), p["y"])
        assertEquals("2.5", (p["z"] as JsonPrimitive).content)
        assertTrue(Subscriptions.parseUserInfo("").isEmpty())
    }

    @Test
    fun updatePatchHasIsoUpdatedFirst() {
        val patch = Subscriptions.updatePatch("total=10", Instant.parse("2026-10-25T01:02:03.004Z"))
        assertEquals(listOf("updated", "total"), patch.keys.toList())
        assertEquals("2026-10-25T01:02:03.004Z", (patch["updated"] as JsonPrimitive).content)
        assertEquals("2026-10-25T01:02:03.000Z", Subscriptions.isoNow(Instant.parse("2026-10-25T01:02:03Z")))
    }

    @Test
    fun subscriptionUrlDetection() {
        assertTrue(Subscriptions.isSubscriptionUrl(" http://43.160.249.60:30099/sub.txt\n"))
        assertTrue(Subscriptions.isSubscriptionUrl("HTTPS://example.com/s?token=1"))
        assertFalse(Subscriptions.isSubscriptionUrl("ss://abc@1.2.3.4:1#x"))
        assertFalse(Subscriptions.isSubscriptionUrl("http://a.com/1\nhttp://b.com/2"))
        assertFalse(Subscriptions.isSubscriptionUrl("naive+https://u:p@a.com:443"))
    }

    @Test
    fun groupNames() {
        assertEquals("a.com", Subscriptions.groupNameFor("https://a.com/x", setOf("默认")))
        assertEquals("a.com (3)", Subscriptions.groupNameFor("https://a.com/x", setOf("a.com", "a.com (2)")))
        assertEquals("订阅", Subscriptions.groupNameFor("https:///x", emptySet()))
    }

    @Test
    fun trafficMeterRates() {
        val m = TrafficMeter()
        assertEquals(TrafficState(0, 0, 100, 200), m.update(100, 200))
        assertEquals(TrafficState(50, 1000, 150, 1200), m.update(150, 1200, 1000))
        // A 2 s gap halves the per-second rate.
        assertEquals(TrafficState(25, 0, 200, 1200), m.update(200, 1200, 2000))
        // Counters that go backwards (engine restart) never give negative rates.
        assertEquals(TrafficState(0, 0, 10, 10), m.update(10, 10))
        m.reset()
        assertEquals(TrafficState(0, 0, 5, 5), m.update(5, 5))
    }
}
