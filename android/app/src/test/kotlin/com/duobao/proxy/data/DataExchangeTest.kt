package com.duobao.proxy.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class DataExchangeTest : StoreTestBase() {

    private fun localStore(): Store = Store(dir).apply {
        addNodes(listOf(obj("protocol" to "socks", "server" to "l", "port" to 1)), "默认")
        addGroup(obj("name" to "机场A", "sub_url" to "local"))
        setSetting("log_level", "debug")
        setUi("theme", "dark")
    }

    @Test
    fun exportMatchesSavedFile() {
        val s = localStore()
        assertEquals(dataFile.readText(), s.exportJson())
    }

    @Test
    fun replaceKeepsIdsAndNextId() {
        val s = localStore()
        s.importReplace(fixture())
        assertEquals(listOf(3, 4, 5, 6, 7, 8), s.ids())
        assertEquals(9, s.snapshot().nextId)
        assertEquals(JsonPrimitive("mixed"), s.ui.value["theme"])
        assertEquals(fixture(), dataFile.readText())
    }

    @Test
    fun mergeReassignsIdsAndKeepsLocalSettings() {
        val s = localStore()
        val r = s.importMerge(fixture())
        assertEquals(6, r.nodesAdded)
        assertEquals("默认 and 机场A exist; 手动 is new", 1, r.groupsAdded)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), s.ids())
        assertEquals(8, s.snapshot().nextId)
        assertEquals(listOf("默认", "机场A", "手动"), s.groups.value.map { it.name })
        assertEquals("local group wins", "local", s.groups.value[1].subUrl)
        assertEquals(JsonPrimitive("debug"), s.settings.value["log_level"])
        assertEquals(JsonPrimitive("dark"), s.ui.value["theme"])

        val imported = parseObject(fixture())["nodes"].let { it as kotlinx.serialization.json.JsonArray }
        val first = s.node(2)!!
        assertEquals(imported[0] as kotlinx.serialization.json.JsonObject + ("id" to JsonPrimitive(2)), first.json)
    }

    @Test
    fun mergeRemapsSelectionWhenLocalHasNone() {
        val s = localStore()
        s.importMerge(fixture())
        assertEquals("imported selected=4 is the 2nd imported node → id 3", JsonPrimitive(3), s.ui.value["selected"])
    }

    @Test
    fun mergeKeepsValidLocalSelection() {
        val s = localStore()
        s.setUi("selected", 1)
        s.importMerge(fixture())
        assertEquals(JsonPrimitive(1), s.ui.value["selected"])
    }

    @Test
    fun mergeReplacesDanglingLocalSelection() {
        val s = localStore()
        s.setUi("selected", 42)
        s.importMerge(fixture())
        assertEquals(JsonPrimitive(3), s.ui.value["selected"])
    }

    @Test
    fun mergeSkipsEntriesWithoutNode() {
        val s = localStore()
        val r = s.importMerge("""{"nodes":[{"id":1,"group":"x"},{"id":2,"group":"x","node":{}},{"id":3,"group":"x","node":{"protocol":"http","server":"h","port":80}}]}""")
        assertEquals(1, r.nodesAdded)
        assertEquals(listOf(1, 2), s.ids())
    }

    @Test
    fun mergedDataSurvivesReload() {
        val s = localStore()
        s.importMerge(fixture())
        assertEquals(s.snapshot(), Store(dir).snapshot())
    }

    @Test
    fun rejectsNonDataFiles() {
        val s = localStore()
        val before = s.snapshot()
        for (bad in listOf("", "garbage", "[]", """{"foo":1}""")) {
            assertThrows(IllegalArgumentException::class.java) { s.importReplace(bad) }
            assertThrows(IllegalArgumentException::class.java) { s.importMerge(bad) }
        }
        assertEquals(before, s.snapshot())
    }

    @Test
    fun replaceFromAnotherStoresExport() {
        val other = Store(File(dir, "other")).apply { importReplace(fixture()) }
        val s = localStore()
        s.importReplace(other.exportJson())
        assertEquals(other.snapshot(), s.snapshot())
    }
}
