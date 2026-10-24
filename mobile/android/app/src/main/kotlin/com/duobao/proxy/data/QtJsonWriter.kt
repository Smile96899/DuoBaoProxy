package com.duobao.proxy.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Writes JSON the way `QJsonDocument::toJson` does (qjsonwriter.cpp): keys sorted, four-space
 * indentation, empty containers as `[\n<indent>]`, a trailing newline after the root, UTF-8 left
 * unescaped. Number literals are written verbatim, so values read from desktop files keep their
 * exact spelling (kotlinx would re-print `12345678.5` as `1.23456785E7`).
 */
object QtJsonWriter {
    fun indented(root: JsonElement): String = StringBuilder().apply { value(root, 0, compact = false); append('\n') }.toString()

    fun compact(root: JsonElement): String = StringBuilder().apply { value(root, 0, compact = true) }.toString()

    private fun StringBuilder.value(e: JsonElement, indent: Int, compact: Boolean) {
        when (e) {
            is JsonObject -> container('{', '}', e.entries.sortedBy { it.key }, indent, compact) { (k, v) ->
                string(k)
                append(if (compact) ":" else ": ")
                value(v, indent + 1, compact)
            }
            is JsonArray -> container('[', ']', e, indent, compact) { value(it, indent + 1, compact) }
            JsonNull -> append("null")
            is JsonPrimitive -> if (e.isString) string(e.content) else append(e.content)
        }
    }

    private inline fun <T> StringBuilder.container(
        open: Char, close: Char, items: Collection<T>, indent: Int, compact: Boolean, item: StringBuilder.(T) -> Unit,
    ) {
        append(open)
        if (!compact) append('\n')
        val pad = if (compact) "" else " ".repeat(4 * (indent + 1))
        items.forEachIndexed { i, it ->
            append(pad)
            item(it)
            if (i < items.size - 1) append(',')
            if (!compact) append('\n')
        }
        if (!compact) append(" ".repeat(4 * indent))
        append(close)
    }

    private fun StringBuilder.string(s: String) {
        append('"')
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u00").append(HEX[ch.code shr 4]).append(HEX[ch.code and 0xF]) else append(ch)
            }
        }
        append('"')
    }

    private const val HEX = "0123456789abcdef"
}
