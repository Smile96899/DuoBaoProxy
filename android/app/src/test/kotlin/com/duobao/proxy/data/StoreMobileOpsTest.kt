package com.duobao.proxy.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Store operations added for the mobile screens. */
class StoreMobileOpsTest : StoreTestBase() {
    private fun node(name: String) = obj("protocol" to "socks", "name" to name, "server" to "s", "port" to 1080)

    private fun twoGroups(): Store = Store(dir).apply {
        addGroup(obj("name" to "A"))
        addNode(node("a1"), "A")
        addNode(node("d1"), Defaults.GROUP)
        addNode(node("a2"), "A")
    }

    @Test
    fun renameGroupMovesItsNodes() {
        val s = twoGroups()
        assertTrue(s.renameGroup("A", "B"))
        assertEquals(listOf(Defaults.GROUP, "B"), s.groups.value.map { it.name })
        assertEquals(listOf("B", Defaults.GROUP, "B"), s.nodes.value.map { it.group })
        assertEquals(listOf("B", Defaults.GROUP, "B"), Store(dir).nodes.value.map { it.group })
    }

    @Test
    fun renameGroupRejectsTakenOrEmptyNames() {
        val s = twoGroups()
        assertFalse(s.renameGroup("A", Defaults.GROUP))
        assertFalse(s.renameGroup("A", ""))
        assertFalse(s.renameGroup("missing", "X"))
        assertTrue(s.renameGroup("A", "A"))
        assertEquals(listOf("A", Defaults.GROUP, "A"), s.nodes.value.map { it.group })
    }

    @Test
    fun setNodesGroupKeepsOrderAndCountsMoves() {
        val s = twoGroups()
        val ids = s.ids()
        assertEquals(1, s.setNodesGroup(listOf(ids[0], ids[1]), Defaults.GROUP))
        assertEquals(ids, s.ids())
        assertEquals(listOf(Defaults.GROUP, Defaults.GROUP, "A"), s.nodes.value.map { it.group })
        assertEquals(0, s.setNodesGroup(listOf(ids[0]), Defaults.GROUP))
    }

    @Test
    fun filteredExportDropsTheUncheckedParts() {
        val s = twoGroups()
        s.setUi("theme", "dark")
        val noNodes = parseObject(s.exportJson(nodes = false, rules = true, settings = true))
        assertEquals(0, (noNodes["nodes"] as JsonArray).size)
        assertEquals(1, (noNodes["groups"] as JsonArray).size)
        assertTrue((noNodes["settings"] as JsonObject).containsKey("routing"))

        val noRules = parseObject(s.exportJson(nodes = true, rules = false, settings = true))
        assertFalse((noRules["settings"] as JsonObject).containsKey("routing"))
        assertTrue((noRules["settings"] as JsonObject).containsKey("inbound"))
        assertEquals("dark", (noRules["ui"] as JsonObject)["theme"].asString())

        val rulesOnly = parseObject(s.exportJson(nodes = false, rules = true, settings = false))
        assertEquals(setOf("routing", "tun"), (rulesOnly["settings"] as JsonObject).keys)
        assertTrue((rulesOnly["ui"] as JsonObject).isEmpty())
    }

    @Test
    fun resetSettingsKeepsNodesAndUi() {
        val s = twoGroups()
        s.setSetting("routing.final", "bypass")
        s.setUi("theme", "dark")
        s.resetSettings()
        assertEquals(Defaults.settings, s.settings.value)
        assertEquals(3, s.nodes.value.size)
        assertEquals("dark", Store(dir).ui.value["theme"].asString())
    }
}
