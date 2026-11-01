package com.duobao.proxy.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal

internal val CompactJson = Json

/** Parses like `QJsonDocument::fromJson(...).object()`: anything that is not an object yields an empty one. */
fun parseObject(text: String): JsonObject = try {
    CompactJson.parseToJsonElement(text.removePrefix("﻿")) as? JsonObject ?: JsonObject(emptyMap())
} catch (_: IllegalArgumentException) {
    JsonObject(emptyMap())
}

/**
 * Converts plain Kotlin values the way QVariant → QJsonValue does. Integral doubles become integers
 * because Go unmarshals `port`, `mtu`, … into `int` and rejects `2080.0`.
 */
fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Int, is Long, is Short, is Byte -> JsonPrimitive(this as Number)
    is Double -> integral(this)
    is Float -> integral(toDouble())
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJson() })
    is Iterable<*> -> JsonArray(map { it.toJson() })
    is Array<*> -> JsonArray(map { it.toJson() })
    else -> throw IllegalArgumentException("unsupported JSON value: ${this::class}")
}

/** Plain decimal spelling (`0.25`, `12345678.5`), never Java's `1.23456785E7`. */
@OptIn(ExperimentalSerializationApi::class)
private fun integral(d: Double): JsonPrimitive = when {
    !d.isFinite() -> throw IllegalArgumentException("JSON has no $d")
    d % 1.0 == 0.0 && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE -> JsonPrimitive(d.toLong())
    else -> JsonUnquotedLiteral(BigDecimal(d.toString()).toPlainString())
}

fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to v.toJson() })

operator fun JsonObject.plus(entry: Pair<String, JsonElement>): JsonObject = JsonObject(LinkedHashMap(this).apply { put(entry.first, entry.second) })

fun JsonObject.merged(patch: JsonObject): JsonObject = JsonObject(LinkedHashMap(this).apply { putAll(patch) })

/** `QVariant::toInt()` for a JSON value: numbers (also integral doubles) and numeric strings, else [default]. */
fun JsonElement?.asInt(default: Int = 0): Int {
    val p = this as? JsonPrimitive ?: return default
    p.longOrNull?.let { return it.toInt() }
    return p.doubleOrNull?.toInt() ?: default
}

fun JsonElement?.asLongOrNull(): Long? {
    val p = this as? JsonPrimitive ?: return null
    return p.longOrNull ?: p.doubleOrNull?.toLong()
}

/** `QVariant::toString()`: strings verbatim, numbers and booleans as text, everything else empty. */
fun JsonElement?.asString(): String {
    val p = this as? JsonPrimitive ?: return ""
    return if (p is JsonNull) "" else p.content
}
