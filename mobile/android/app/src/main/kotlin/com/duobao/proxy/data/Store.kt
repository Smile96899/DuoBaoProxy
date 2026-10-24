package com.duobao.proxy.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Port of `app/src/store.cpp`: everything the app persists, in one `data.json`.
 *
 * Every mutator saves immediately. Mutators do blocking file I/O, so call them off the main thread.
 * Each section is a [StateFlow]; unlike Qt signals a StateFlow skips values equal to the current one,
 * which is harmless because desktop consumers compare contents anyway (critic A9).
 */
class Store(val dataDir: File) {
    val file = File(dataDir, "data.json")

    private val lock = Any()
    private var nodeList = mutableListOf<JsonObject>()
    private var groupList = mutableListOf<JsonObject>()
    private var settingsObj = EMPTY
    private var uiObj = EMPTY
    private var nextId = 1

    private val _nodes = MutableStateFlow<List<NodeEntry>>(emptyList())
    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    private val _settings = MutableStateFlow(EMPTY)
    private val _ui = MutableStateFlow(EMPTY)

    val nodes: StateFlow<List<NodeEntry>> = _nodes.asStateFlow()
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()
    val settings: StateFlow<JsonObject> = _settings.asStateFlow()
    val ui: StateFlow<JsonObject> = _ui.asStateFlow()

    init {
        dataDir.mkdirs()
        load()
    }

    fun load() {
        val text = try {
            file.readText()
        } catch (_: IOException) {
            ""
        }
        synchronized(lock) { apply(StoreData.parse(text)) }
    }

    fun save(): Boolean = synchronized(lock) { writeAtomically(snapshot().encode()) }

    fun snapshot(): StoreData = synchronized(lock) {
        StoreData(nodeList.toList(), groupList.toList(), settingsObj, uiObj, nextId)
    }

    // ---- nodes ----

    /** Returns the new id, or -1 when [node] is empty. */
    fun addNode(node: JsonObject, group: String): Int = synchronized(lock) {
        if (node.isEmpty()) return -1
        val id = nextId++
        nodeList += newEntry(id, group, node)
        commitNodes()
        id
    }

    /** Skips empty objects; returns how many were added. */
    fun addNodes(nodes: List<JsonObject>, group: String): Int = synchronized(lock) {
        var n = 0
        for (node in nodes) {
            if (node.isEmpty()) continue
            nodeList += newEntry(nextId++, group, node)
            n++
        }
        if (n > 0) commitNodes()
        n
    }

    /** Shallow merge of [patch] into the entry (not into `node`). */
    fun updateNode(id: Int, patch: JsonObject): Unit = synchronized(lock) {
        val i = indexOfNode(id)
        if (i < 0) return
        nodeList[i] = nodeList[i].merged(patch)
        commitNodes()
    }

    fun removeNode(id: Int) = synchronized(lock) {
        indexOfNode(id).takeIf { it >= 0 }?.let { nodeList.removeAt(it) }
        commitNodes()
    }

    /** `App.removeNodes`: drops the selection first when it is among [ids]. */
    fun removeNodes(ids: Collection<Int>) = synchronized(lock) {
        if (uiObj["selected"].asInt(-1) in ids) setUi("selected", -1)
        for (id in ids) indexOfNode(id).takeIf { it >= 0 }?.let { nodeList.removeAt(it) }
        commitNodes()
    }

    fun removeGroupNodes(group: String) = synchronized(lock) {
        nodeList.removeAll { NodeEntry(it).group == group }
        commitNodes()
    }

    fun node(id: Int): NodeEntry? = synchronized(lock) { nodeList.getOrNull(indexOfNode(id))?.let(::NodeEntry) }

    /** The `node` object as compact JSON for the core, or "" when missing or empty. */
    fun nodeJson(id: Int): String {
        val n = node(id)?.node ?: return ""
        return if (n.isEmpty()) "" else QtJsonWriter.compact(n)
    }

    /** Places [id] next to [targetId] (after it when [after]) and adopts the target's group. */
    fun moveNode(id: Int, targetId: Int, after: Boolean): Unit = synchronized(lock) {
        if (id == targetId) return
        val from = indexOfNode(id)
        var to = indexOfNode(targetId)
        if (from < 0 || to < 0) return
        val dest = to + if (after) 1 else 0
        if (dest == from || dest == from + 1) return
        val e = nodeList.removeAt(from)
        if (from < to) to--
        if (after) to++
        val group = nodeList[to - if (after) 1 else 0]["group"] ?: JsonNull
        nodeList.add(to, e + ("group" to group))
        commitNodes()
    }

    /** Appends [id] to the end of the list in [group] (dropping on an empty group). */
    fun moveNodeToGroup(id: Int, group: String): Unit = synchronized(lock) {
        val i = indexOfNode(id)
        if (i < 0) return
        val e = nodeList.removeAt(i)
        nodeList += e + ("group" to group.toJson())
        commitNodes()
    }

    // ---- groups ----

    /** Ignored when a group with the same name exists. */
    fun addGroup(group: JsonObject): Unit = synchronized(lock) {
        if (groupList.any { it["name"] == group["name"] }) return
        groupList += group
        commitGroups()
    }

    /** Shallow merge. Renaming does not rewrite `nodes[].group`, exactly like desktop. */
    fun updateGroup(name: String, patch: JsonObject): Unit = synchronized(lock) {
        val i = groupList.indexOfFirst { Group(it).name == name }
        if (i < 0) return
        groupList[i] = groupList[i].merged(patch)
        commitGroups()
    }

    fun removeGroup(name: String) = synchronized(lock) {
        removeGroupNodes(name)
        groupList.indexOfFirst { Group(it).name == name }.takeIf { it >= 0 }?.let { groupList.removeAt(it) }
        commitGroups()
    }

    fun moveGroup(from: Int, to: Int): Unit = synchronized(lock) {
        if (from == to || from !in groupList.indices || to !in groupList.indices) return
        groupList.add(to, groupList.removeAt(from))
        commitGroups()
    }

    // ---- settings / ui ----

    /**
     * [key] is `"a"` or `"a.b"` (`"routing.final"`, `"tun.enabled"`); like desktop only the first two
     * parts are used, and a non-object parent is replaced by an object. Always emits and saves.
     */
    fun setSetting(key: String, value: JsonElement): Unit = synchronized(lock) {
        val parts = key.split('.')
        settingsObj = if (parts.size == 1) {
            settingsObj + (key to value)
        } else {
            val sub = settingsObj[parts[0]] as? JsonObject ?: EMPTY
            settingsObj + (parts[0] to sub + (parts[1] to value))
        }
        _settings.value = settingsObj
        save()
    }

    fun setSetting(key: String, value: Any?) = setSetting(key, value.toJson())

    fun settingsJson(): String = QtJsonWriter.compact(settings.value)

    /** No-op when the value is unchanged. */
    fun setUi(key: String, value: JsonElement): Unit = synchronized(lock) {
        if (uiObj[key] == value) return
        uiObj = uiObj + (key to value)
        _ui.value = uiObj
        save()
    }

    fun setUi(key: String, value: Any?) = setUi(key, value.toJson())

    // ---- data exchange (critic A7) ----

    fun exportJson(): String = snapshot().encode()

    /** Replaces everything with [text], keeping its ids and `next_id`. */
    fun importReplace(text: String): Unit = synchronized(lock) {
        apply(StoreData.load(requireDataFile(text)))
        save()
    }

    /** Appends the nodes and missing groups of [text] with fresh ids; settings and ui stay local. */
    fun importMerge(text: String): MergeResult = synchronized(lock) {
        val result = DataMerge.merge(snapshot(), StoreData.load(requireDataFile(text)))
        apply(result.data)
        save()
        result
    }

    // ----

    private fun requireDataFile(text: String): JsonObject {
        val root = parseObject(text)
        require(DATA_KEYS.any { it in root }) { "不是 data.json" }
        return root
    }

    private fun apply(d: StoreData) {
        nodeList = d.nodes.toMutableList()
        groupList = d.groups.toMutableList()
        settingsObj = d.settings
        uiObj = d.ui
        nextId = d.nextId
        _nodes.value = nodeList.map(::NodeEntry)
        _groups.value = groupList.map(::Group)
        _settings.value = settingsObj
        _ui.value = uiObj
    }

    private fun commitNodes() {
        _nodes.value = nodeList.map(::NodeEntry)
        save()
    }

    private fun commitGroups() {
        _groups.value = groupList.map(::Group)
        save()
    }

    private fun indexOfNode(id: Int) = nodeList.indexOfFirst { NodeEntry(it).id == id }

    private fun newEntry(id: Int, group: String, node: JsonObject) =
        jsonObjectOf("id" to id, "group" to group, "lat" to 0, "node" to node)

    /** Write a sibling temp file, fsync, rename over the target (what `QSaveFile` does). */
    private fun writeAtomically(text: String): Boolean {
        val tmp = File(dataDir, "${file.name}.tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: IOException) {
            tmp.delete()
            false
        }
    }

    private companion object {
        val DATA_KEYS = listOf("nodes", "groups", "settings", "ui", "next_id")
    }
}
