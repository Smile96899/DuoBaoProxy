package com.duobao.proxy.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreMutationTest : StoreTestBase() {

    private fun node(name: String) = obj("protocol" to "socks", "name" to name, "server" to "s", "port" to 1080)

    /** Nodes a, b in g1 and c, d in g2 with ids 1..4. */
    private fun fourNodes(): Store = Store(dir).apply {
        addNodes(listOf(node("a"), node("b")), "g1")
        addNodes(listOf(node("c"), node("d")), "g2")
    }

    private fun Store.names() = nodes.value.map { it.name }
    private fun Store.groupsOf() = nodes.value.map { it.group }

    @Test
    fun addNodeAllocatesIdsAndPersists() {
        val s = Store(dir)
        assertEquals(1, s.addNode(node("a"), "默认"))
        assertEquals(2, s.addNode(node("b"), "x"))
        assertEquals(-1, s.addNode(JsonObject(emptyMap()), "x"))
        assertEquals(3, s.snapshot().nextId)
        assertEquals(obj("id" to 1, "group" to "默认", "lat" to 0, "node" to node("a")), s.nodes.value[0].json)

        val reloaded = Store(dir)
        assertEquals(listOf(1, 2), reloaded.ids())
        assertEquals(3, reloaded.snapshot().nextId)
    }

    @Test
    fun addNodesSkipsEmptyAndSavesOnlyWhenSomethingWasAdded() {
        val s = Store(dir)
        assertEquals(0, s.addNodes(listOf(JsonObject(emptyMap())), "g"))
        assertFalse(dataFile.exists())
        assertEquals(2, s.addNodes(listOf(node("a"), JsonObject(emptyMap()), node("b")), "g"))
        assertEquals(listOf(1, 2), s.ids())
        assertTrue(dataFile.exists())
    }

    @Test
    fun updateNodeMergesShallowly() {
        val s = fourNodes()
        s.updateNode(2, obj("lat" to 55, "res" to mapOf("url" to mapOf("ms" to 55))))
        val e = s.node(2)!!
        assertEquals(55, e.lat)
        assertEquals(json("""{"url":{"ms":55}}"""), e.res)
        assertEquals("b", e.name)
        s.updateNode(99, obj("lat" to 1))
        assertEquals(listOf(1, 2, 3, 4), s.ids())
    }

    @Test
    fun removeNodeAndRemoveNodes() {
        val s = fourNodes()
        s.setUi("selected", 3)
        s.removeNode(1)
        assertEquals(listOf(2, 3, 4), s.ids())
        s.removeNodes(listOf(3, 4))
        assertEquals(listOf(2), s.ids())
        assertEquals(JsonPrimitive(-1), s.ui.value["selected"])
        assertEquals(listOf(2), Store(dir).ids())
    }

    @Test
    fun removeNodesKeepsUnrelatedSelection() {
        val s = fourNodes()
        s.setUi("selected", 2)
        s.removeNodes(listOf(3))
        assertEquals(JsonPrimitive(2), s.ui.value["selected"])
    }

    @Test
    fun removeNodeAlwaysSaves() {
        val s = fourNodes()
        dataFile.delete()
        s.removeNode(99)
        assertTrue(dataFile.exists())
    }

    @Test
    fun moveNodeAfterTargetAdoptsItsGroup() {
        val s = fourNodes()
        s.moveNode(1, 3, after = true)
        assertEquals(listOf("b", "c", "a", "d"), s.names())
        assertEquals(listOf("g1", "g2", "g2", "g2"), s.groupsOf())
    }

    @Test
    fun moveNodeBeforeTarget() {
        val s = fourNodes()
        s.moveNode(4, 1, after = false)
        assertEquals(listOf("d", "a", "b", "c"), s.names())
        assertEquals("g1", s.node(4)!!.group)
    }

    @Test
    fun moveNodeForwardBeforeTarget() {
        val s = fourNodes()
        s.moveNode(1, 4, after = false)
        assertEquals(listOf("b", "c", "a", "d"), s.names())
        assertEquals("g2", s.node(1)!!.group)
    }

    @Test
    fun moveNodeToEnd() {
        val s = fourNodes()
        s.moveNode(1, 4, after = true)
        assertEquals(listOf("b", "c", "d", "a"), s.names())
        assertEquals("g2", s.node(1)!!.group)
    }

    @Test
    fun moveNodeIntoItsOwnSlotIsANoOp() {
        val s = fourNodes()
        dataFile.delete()
        s.moveNode(1, 2, after = false)
        s.moveNode(2, 1, after = true)
        s.moveNode(2, 2, after = true)
        s.moveNode(2, 99, after = true)
        assertEquals(listOf("a", "b", "c", "d"), s.names())
        assertFalse(dataFile.exists())
    }

    @Test
    fun moveNodeToGroupAppends() {
        val s = fourNodes()
        s.moveNodeToGroup(1, "empty")
        assertEquals(listOf("b", "c", "d", "a"), s.names())
        assertEquals("empty", s.node(1)!!.group)
    }

    @Test
    fun groupsAddUpdateRemoveMove() {
        val s = fourNodes()
        s.addGroup(obj("name" to "g1"))
        s.addGroup(obj("name" to "g1"))
        s.addGroup(obj("name" to "g2", "sub_url" to "https://x"))
        assertEquals(listOf("默认", "g1", "g2"), s.groups.value.map { it.name })

        s.updateGroup("g2", obj("updated" to "2026-01-01T00:00:00.000Z", "total" to 10_000_000_000L))
        assertEquals(10_000_000_000L, s.groups.value[2].total)
        assertEquals("https://x", s.groups.value[2].subUrl)

        s.updateGroup("g1", obj("name" to "renamed"))
        assertEquals("rename does not touch nodes", listOf("g1", "g1", "g2", "g2"), s.groupsOf())

        s.moveGroup(2, 0)
        assertEquals(listOf("g2", "默认", "renamed"), s.groups.value.map { it.name })
        s.moveGroup(0, 3)
        assertEquals(listOf("g2", "默认", "renamed"), s.groups.value.map { it.name })

        s.removeGroup("g2")
        assertEquals(listOf("默认", "renamed"), s.groups.value.map { it.name })
        assertEquals(listOf("a", "b"), s.names())
    }

    @Test
    fun setSettingByPath() {
        val s = Store(dir)
        s.setSetting("routing.final", "bypass")
        s.setSetting("inbound.port", 2081.0)
        s.setSetting("log_level", "debug")
        s.setSetting("routing.proxy_ip", listOf("1.1.1.1/32"))
        s.setSetting("a.b.c", true)
        s.setSetting("log_level.x", 1)

        val st = s.settings.value
        assertEquals(JsonPrimitive("bypass"), (st["routing"] as JsonObject)["final"])
        assertEquals(JsonPrimitive("https://doh.pub/dns-query"), (st["routing"] as JsonObject)["direct_dns"])
        assertEquals(json("""["1.1.1.1/32"]"""), (st["routing"] as JsonObject)["proxy_ip"])
        assertEquals(json("""{"b":true}"""), st["a"])
        assertEquals("non-object parent is replaced", json("""{"x":1}"""), st["log_level"])
        assertTrue(dataFile.readText().contains("\"port\": 2081,"))
        assertEquals(s.settings.value, Store(dir).settings.value)
    }

    @Test
    fun setSettingAlwaysSaves() {
        val s = Store(dir)
        s.setSetting("log_level", "info")
        dataFile.delete()
        s.setSetting("log_level", "info")
        assertTrue(dataFile.exists())
    }

    @Test
    fun setUiShortCircuitsOnEqualValue() {
        val s = Store(dir)
        s.setUi("theme", "system")
        assertFalse(dataFile.exists())
        s.setUi("selected", -1.0)
        assertFalse(dataFile.exists())
        s.setUi("theme", "dark")
        assertTrue(dataFile.exists())
        assertEquals(JsonPrimitive("dark"), Store(dir).ui.value["theme"])
    }

    @Test
    fun jsonForTheCoreIsCompactAndSorted() {
        val s = Store(dir)
        val id = s.addNode(obj("server" to "s", "protocol" to "socks", "port" to 1), "g")
        assertEquals("""{"port":1,"protocol":"socks","server":"s"}""", s.nodeJson(id))
        assertEquals("", s.nodeJson(99))
        assertTrue(s.settingsJson().startsWith("""{"inbound":{"address":"127.0.0.1","port":2080,"sniff":1},"log_level":"info","""))
    }

    @Test
    fun flowsReflectMutations() {
        val s = Store(dir)
        s.addNode(node("a"), "g")
        assertEquals(1, s.nodes.value.size)
        s.addGroup(obj("name" to "g"))
        assertEquals(2, s.groups.value.size)
        s.setSetting("log_level", "warn")
        assertEquals(JsonPrimitive("warn"), s.settings.value["log_level"])
        s.setUi("core", "xray")
        assertEquals(JsonPrimitive("xray"), s.ui.value["core"])
        assertNull(s.node(99))
    }

    @Test
    fun saveLeavesNoTempFile() {
        val s = fourNodes()
        assertTrue(s.save())
        assertEquals(listOf("data.json"), dir.list()!!.toList())
    }
}
