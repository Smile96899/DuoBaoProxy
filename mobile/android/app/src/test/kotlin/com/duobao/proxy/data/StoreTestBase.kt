package com.duobao.proxy.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

abstract class StoreTestBase {
    @get:Rule
    val tmp = TemporaryFolder()

    val dir: File get() = tmp.root
    val dataFile: File get() = File(dir, "data.json")

    fun storeWith(json: String): Store {
        dataFile.writeText(json)
        return Store(dir)
    }

    fun fixture(): String = javaClass.getResource("/desktop-data.json")!!.readText()

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = jsonObjectOf(*pairs)

    fun json(text: String): JsonElement = CompactJson.parseToJsonElement(text)

    fun savedRoot(): JsonObject = parseObject(dataFile.readText())

    fun Store.ids(): List<Int> = nodes.value.map { it.id }
}
