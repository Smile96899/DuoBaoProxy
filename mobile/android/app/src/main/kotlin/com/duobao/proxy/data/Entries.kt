package com.duobao.proxy.data

import kotlinx.serialization.json.JsonObject

/** One `nodes[]` entry. The backing object is kept whole so unknown keys survive a round trip. */
@JvmInline
value class NodeEntry(val json: JsonObject) {
    val id: Int get() = json["id"].asInt()
    val group: String get() = json["group"].asString()

    /** Legacy latency: 0 untested, -1 failed. */
    val lat: Int get() = json["lat"].asInt()

    /** Test results by kind (`url`, `tcp`, `geo`, …); empty until the first test. */
    val res: JsonObject get() = json["res"] as? JsonObject ?: EMPTY

    /** The `node.Node` object, passed verbatim to the core. */
    val node: JsonObject get() = json["node"] as? JsonObject ?: EMPTY

    val name: String get() = node["name"].asString()
    val protocol: String get() = node["protocol"].asString()
    val server: String get() = node["server"].asString()
    val port: Int get() = node["port"].asInt()

    /** The display name: `name`, falling back to `server:port`. */
    val title: String get() = name.ifEmpty { "$server:$port" }
}

/** One `groups[]` entry; unknown keys (extra subscription-userinfo fields) are kept. */
@JvmInline
value class Group(val json: JsonObject) {
    val name: String get() = json["name"].asString()
    val subUrl: String get() = json["sub_url"].asString()
    val subIntervalMin: Int get() = json["sub_interval_min"].asInt()

    /** ISO-8601 UTC of the last subscription refresh. */
    val updated: String? get() = json["updated"]?.asString()
    val upload: Long? get() = json["upload"].asLongOrNull()
    val download: Long? get() = json["download"].asLongOrNull()
    val total: Long? get() = json["total"].asLongOrNull()

    /** Unix seconds. */
    val expire: Long? get() = json["expire"].asLongOrNull()
}

internal val EMPTY = JsonObject(emptyMap())
