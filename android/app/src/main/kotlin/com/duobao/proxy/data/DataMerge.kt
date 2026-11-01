package com.duobao.proxy.data

data class MergeResult(val data: StoreData, val nodesAdded: Int, val groupsAdded: Int)

/**
 * Merges an imported data.json into the local one (critic A7, data-schema §5.4).
 *
 * - Imported nodes are appended in order with fresh ids; every other key of an entry is kept.
 *   Entries without a non-empty `node` object are skipped, as `addNode` would.
 * - Imported groups whose name is not present locally are appended whole.
 * - `settings` and every `ui` key stay local, except `ui.selected`: when the local selection is
 *   unset or dangling and the imported one names an imported node, it follows that node's new id.
 */
object DataMerge {
    fun merge(local: StoreData, imported: StoreData): MergeResult {
        var nextId = local.nextId
        val idMap = HashMap<Int, Int>()
        val added = imported.nodes.mapNotNull { entry ->
            val e = NodeEntry(entry)
            if (e.node.isEmpty()) return@mapNotNull null
            val id = nextId++
            idMap.putIfAbsent(e.id, id)
            entry + ("id" to id.toJson())
        }

        val names = local.groups.mapTo(HashSet()) { Group(it).name }
        val newGroups = imported.groups.filter { names.add(Group(it).name) }

        val nodes = local.nodes + added
        val selected = local.ui["selected"].asInt(-1)
        val selectionValid = local.nodes.any { NodeEntry(it).id == selected }
        val remapped = idMap[imported.ui["selected"].asInt(-1)]
        val ui = if (!selectionValid && remapped != null) local.ui + ("selected" to remapped.toJson()) else local.ui

        return MergeResult(
            data = local.copy(nodes = nodes, groups = local.groups + newGroups, ui = ui, nextId = nextId),
            nodesAdded = added.size,
            groupsAdded = newGroups.size,
        )
    }
}
