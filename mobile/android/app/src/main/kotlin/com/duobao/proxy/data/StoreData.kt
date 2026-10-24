package com.duobao.proxy.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** The five top-level keys of data.json. Unknown top-level keys are dropped, as on desktop. */
data class StoreData(
    val nodes: List<JsonObject>,
    val groups: List<JsonObject>,
    val settings: JsonObject,
    val ui: JsonObject,
    val nextId: Int,
) {
    fun toJson(): JsonObject = jsonObjectOf(
        "nodes" to JsonArray(nodes),
        "groups" to JsonArray(groups),
        "settings" to settings,
        "ui" to ui,
        "next_id" to nextId,
    )

    fun encode(): String = QtJsonWriter.indented(toJson())

    companion object {
        /** `Store::load` on an already parsed root, including the first-run and per-key defaults. */
        fun load(root: JsonObject): StoreData {
            val groups = root.arrayOfObjects("groups").ifEmpty { listOf(Defaults.group) }
            val ui = LinkedHashMap(root["ui"] as? JsonObject ?: EMPTY)
            for ((k, v) in Defaults.ui) if (k !in ui) ui[k] = v
            val settings = (root["settings"] as? JsonObject).takeUnless { it.isNullOrEmpty() } ?: Defaults.settings
            return StoreData(
                nodes = root.arrayOfObjects("nodes"),
                groups = groups,
                settings = settings,
                ui = JsonObject(ui),
                nextId = root["next_id"].qtToInt(1),
            )
        }

        fun parse(text: String): StoreData = load(parseObject(text))

        private fun JsonObject.arrayOfObjects(key: String): List<JsonObject> =
            (this[key] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

        /** `QJsonValue::toInt(default)`: only integral numbers in int range count. */
        private fun JsonElement?.qtToInt(default: Int): Int {
            val p = this as? JsonPrimitive ?: return default
            if (p.isString) return default
            val l = p.longOrNull ?: p.doubleOrNull?.takeIf { it % 1.0 == 0.0 }?.toLong() ?: return default
            return if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else default
        }
    }
}
