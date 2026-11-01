package com.duobao.proxy.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreLoadTest : StoreTestBase() {

    @Test
    fun firstRunSeedsDefaultsWithoutWriting() {
        val s = Store(dir)
        assertEquals(listOf("默认"), s.groups.value.map { it.name })
        assertEquals(Defaults.settings, s.settings.value)
        assertEquals(JsonObject(Defaults.ui.toMap()), s.ui.value)
        assertEquals(1, s.snapshot().nextId)
        assertTrue(s.nodes.value.isEmpty())
        assertFalse("load must not save", dataFile.exists())
    }

    @Test
    fun uiDefaultsArePerKeyAndKeepPresentValues() {
        val s = storeWith("""{"ui":{"theme":"dark","test_url":"","extra":1}}""")
        val ui = s.ui.value
        assertEquals(JsonPrimitive("dark"), ui["theme"])
        assertEquals("reset test_url is not re-seeded (A8)", JsonPrimitive(""), ui["test_url"])
        assertEquals(JsonPrimitive("landscape"), ui["layout"])
        assertEquals(JsonPrimitive(-1), ui["selected"])
        assertEquals(JsonPrimitive(1), ui["extra"])
        assertEquals(Defaults.ui.size + 1, ui.size)
    }

    @Test
    fun nullUiValueCountsAsPresent() {
        val s = storeWith("""{"ui":{"core":null}}""")
        assertEquals(json("null"), s.ui.value["core"])
    }

    @Test
    fun partialSettingsAreNotSeeded() {
        val s = storeWith("""{"settings":{"log_level":"debug"}}""")
        assertEquals(obj("log_level" to "debug"), s.settings.value)
    }

    @Test
    fun emptyOrInvalidSettingsAreSeeded() {
        assertEquals(Defaults.settings, storeWith("""{"settings":{}}""").settings.value)
        assertEquals(Defaults.settings, storeWith("""{"settings":[1]}""").settings.value)
    }

    @Test
    fun groupsSeededOnlyWhenEmpty() {
        assertEquals(listOf("默认"), storeWith("""{"groups":[]}""").groups.value.map { it.name })
        assertEquals(listOf("x"), storeWith("""{"groups":[{"name":"x"}]}""").groups.value.map { it.name })
    }

    @Test
    fun brokenFileLoadsAsEmpty() {
        for (text in listOf("", "not json", "[1,2]", "{\"nodes\":")) {
            val s = storeWith(text)
            assertEquals(Defaults.settings, s.settings.value)
            assertEquals(1, s.snapshot().nextId)
        }
    }

    @Test
    fun nextIdFollowsQJsonValueToInt() {
        assertEquals(7, storeWith("""{"next_id":7}""").snapshot().nextId)
        assertEquals(7, storeWith("""{"next_id":7.0}""").snapshot().nextId)
        assertEquals(1, storeWith("""{"next_id":7.5}""").snapshot().nextId)
        assertEquals(1, storeWith("""{"next_id":"7"}""").snapshot().nextId)
        assertEquals(1, storeWith("""{"next_id":1e12}""").snapshot().nextId)
    }

    @Test
    fun unknownTopLevelKeysAreDroppedOnSave() {
        val s = storeWith("""{"future":1,"nodes":[]}""")
        s.save()
        assertNull(savedRoot()["future"])
        assertEquals(setOf("nodes", "groups", "settings", "ui", "next_id"), savedRoot().keys)
    }

    @Test
    fun desktopFixtureRoundTripsByteForByte() {
        val text = fixture()
        val s = storeWith(text)
        assertTrue(s.save())
        val saved = dataFile.readText()
        assertEquals(json(text), json(saved))
        assertEquals(text, saved)
        assertFalse(java.io.File(dir, "data.json.tmp").exists())
    }

    @Test
    fun desktopFixtureTypedView() {
        val s = storeWith(fixture())
        assertEquals(listOf(3, 4, 5, 6, 7, 8), s.ids())
        assertEquals(9, s.snapshot().nextId)

        val n3 = s.node(3)!!
        assertEquals("默认", n3.group)
        assertEquals(42, n3.lat)
        assertEquals("东京 VLESS Reality", n3.title)
        assertEquals("vless", n3.protocol)
        assertEquals(11, n3.res.size)
        assertEquals(json("""{"nested":[1,2,3]}"""), n3.node["x_future_field"])

        assertEquals("203.0.113.9:20000", s.node(5)!!.title)
        assertEquals(-1, s.node(4)!!.lat)
        assertEquals("已删除的分组", s.node(7)!!.group)

        val g = s.groups.value[1]
        assertEquals("机场A", g.name)
        assertEquals(360, g.subIntervalMin)
        assertEquals(9876543210L, g.download)
        assertEquals(214748364800L, g.total)
        assertEquals(1798732800L, g.expire)
        assertEquals("2026-10-20T08:15:30.123Z", g.updated)
        assertEquals(JsonPrimitive("kept"), g.json["x_unknown_header"])
        assertNull(s.groups.value[0].updated)

        assertEquals(JsonPrimitive("mixed"), s.ui.value["theme"])
        assertEquals(JsonPrimitive(""), s.ui.value["test_url"])
    }

    @Test
    fun mutatingFixturePreservesEverythingElse() {
        val s = storeWith(fixture())
        s.setUi("theme", "dark")
        val before = parseObject(fixture())
        val after = savedRoot()
        assertEquals(before["nodes"], after["nodes"])
        assertEquals(before["groups"], after["groups"])
        assertEquals(before["settings"], after["settings"])
        assertEquals(before["ui"] as JsonObject + ("theme" to JsonPrimitive("dark")), after["ui"])
    }
}
