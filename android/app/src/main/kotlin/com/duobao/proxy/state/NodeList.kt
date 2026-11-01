package com.duobao.proxy.state

import com.duobao.proxy.data.Group
import com.duobao.proxy.data.NodeEntry
import com.duobao.proxy.tests.TestFormat
import com.duobao.proxy.tests.TestKinds

/** One group of the node list with the nodes it shows. */
data class NodeSection(val group: String, val nodes: List<NodeEntry>)

/** The node list of `NodesPage.qml:272-301`. Pure. */
object NodeList {
    /**
     * Groups in store order, then pseudo-groups for nodes whose group no longer exists. With a
     * [query], nodes match on `title + server + group` (case-insensitive) and empty groups are
     * hidden; without one every group shows, empty ones included. Each group is sorted by [sort].
     */
    fun sections(nodes: List<NodeEntry>, groups: List<Group>, query: String, sort: String, shownKinds: List<String>): List<NodeSection> {
        val q = query.trim().lowercase()
        val names = LinkedHashSet<String>()
        groups.mapTo(names) { it.name }
        nodes.mapTo(names) { it.group }
        val byGroup = nodes.groupBy { it.group }
        return names.mapNotNull { g ->
            var list = byGroup[g].orEmpty()
            if (q.isNotEmpty()) {
                list = list.filter { (it.title + it.server + it.group).lowercase().contains(q) }
                if (list.isEmpty()) return@mapNotNull null
            }
            NodeSection(g, sorted(list, sort, shownKinds))
        }
    }

    /** Stable ascending sort within one group (`Tests.sortValue`). */
    fun sorted(list: List<NodeEntry>, sort: String, shownKinds: List<String>): List<NodeEntry> = when (sort) {
        "default", "" -> list
        "latency" -> {
            val k = shownKinds.firstOrNull { it in TestKinds.latencyKinds } ?: "url"
            list.sortedBy { TestFormat.sortValue(it, k) }
        }
        "speed" -> list.sortedBy { TestFormat.sortValue(it, "down") }
        "geo" -> list.sortedBy { geoKey(it) }
        else -> list.sortedBy { it.title.lowercase() }
    }

    private fun geoKey(e: NodeEntry): String {
        val cc = (TestFormat.result(e, "geo")?.get("cc") as? kotlinx.serialization.json.JsonPrimitive)?.content
        return if (cc.isNullOrEmpty() || TestFormat.result(e, "geo")?.containsKey("err") == true) "~" else cc
    }
}
