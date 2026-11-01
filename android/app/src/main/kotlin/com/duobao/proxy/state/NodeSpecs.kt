package com.duobao.proxy.state

import com.duobao.proxy.data.EMPTY
import com.duobao.proxy.data.asString
import com.duobao.proxy.data.jsonObjectOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One editor row: [label] → JSON [path] (`a` or `a.b`) of [type] `text` / `int` / `bool` / `choice`. */
data class NodeField(
    val label: String,
    val path: String,
    val type: String = "text",
    val hint: String = "",
    val choices: List<Pair<String, String>> = emptyList(),
    val default: String = "",
)

/** The tables and field-writing rules of `NodeEditor.qml` (spec/gui-nodes.md §2). Pure. */
object NodeSpecs {
    /** 手动添加 entries: protocol id → label (naive is editable but not offered). */
    val protocols = listOf(
        "socks" to "SOCKS5",
        "http" to "HTTP",
        "shadowsocks" to "Shadowsocks",
        "vmess" to "VMess",
        "vless" to "VLESS",
        "trojan" to "Trojan",
        "hysteria2" to "Hysteria2",
        "tuic" to "TUIC",
        "ssh" to "SSH",
        "anytls" to "AnyTLS",
        "wireguard" to "WireGuard",
        "hysteria" to "Hysteria",
        "shadowtls" to "ShadowTLS",
    )

    fun defaultPort(protocol: String): Int = when (protocol) {
        "socks" -> 1080
        "http" -> 8080
        "shadowsocks" -> 8388
        "wireguard" -> 51820
        "ssh" -> 22
        else -> 443
    }

    /** `openNew(protocol)`: the draft with the protocol presets. */
    fun newDraft(protocol: String): JsonObject {
        var d = jsonObjectOf("protocol" to protocol, "server" to "", "port" to defaultPort(protocol))
        when (protocol) {
            "trojan", "anytls" -> d = setAt(d, "stream.sec", JsonPrimitive("tls"))
            "shadowsocks" -> d = setAt(d, "method", JsonPrimitive("aes-256-gcm"))
            "shadowtls" -> {
                d = setAt(d, "method", JsonPrimitive("aes-256-gcm"))
                d = setAt(d, "shadowtls.version", JsonPrimitive(3))
            }
            "wireguard" -> d = setAt(d, "wireguard.local_address", JsonPrimitive("10.0.0.2/32"))
        }
        return d
    }

    private val common = listOf(NodeField("名称", "name", hint = "留空则显示地址"), NodeField("地址", "server"), NodeField("端口", "port", "int"))

    private val quic = listOf(NodeField("SNI", "quic_tls.sni"), NodeField("ALPN", "quic_tls.alpn"), NodeField("跳过证书验证", "quic_tls.insecure", "bool"))

    private val stream = listOf(
        NodeField("传输方式", "stream.net", "choice", choices = listOf("tcp", "ws", "grpc", "http", "httpupgrade", "xhttp").map { it to it }, default = "tcp"),
        NodeField("TLS", "stream.sec", "choice", choices = listOf("" to "关闭", "tls" to "开启"), default = ""),
        NodeField("SNI", "stream.sni"),
        NodeField("Host", "stream.host"),
        NodeField("路径", "stream.path"),
        NodeField("ALPN", "stream.alpn", hint = "逗号分隔，如 h2,http/1.1"),
        NodeField("指纹", "stream.utls", hint = "chrome、firefox、safari…"),
        NodeField("Reality 公钥", "stream.pbk"),
        NodeField("Reality ShortID", "stream.sid"),
        NodeField("跳过证书验证", "stream.insecure", "bool"),
    )

    private fun own(protocol: String): List<NodeField> = when (protocol) {
        "socks", "http", "ssh" -> listOf(NodeField("用户名", "username"), NodeField("密码", "password"))
        "shadowsocks" -> listOf(
            NodeField("加密方式", "method", hint = "如 aes-256-gcm、2022-blake3-aes-128-gcm"),
            NodeField("密码", "password"),
            NodeField("插件", "plugin"),
        )
        "vmess" -> listOf(NodeField("UUID", "uuid"), NodeField("AlterID", "alter_id", "int"), NodeField("加密", "vmess_security", hint = "auto"))
        "vless" -> listOf(NodeField("UUID", "uuid"), NodeField("Flow", "flow", hint = "xtls-rprx-vision"))
        "trojan", "anytls" -> listOf(NodeField("密码", "password"))
        "hysteria2" -> listOf(
            NodeField("密码", "password"),
            NodeField("混淆密码", "hysteria2.obfs_password"),
            NodeField("上行 Mbps", "hysteria2.up_mbps", "int"),
            NodeField("下行 Mbps", "hysteria2.down_mbps", "int"),
            NodeField("端口跳跃", "hysteria2.hop_ports", hint = "如 20000-30000"),
        )
        "tuic" -> listOf(NodeField("UUID", "uuid"), NodeField("密码", "password"), NodeField("拥塞控制", "tuic.congestion_control", hint = "bbr、cubic"))
        "naive" -> listOf(NodeField("用户名", "username"), NodeField("密码", "password"), NodeField("SNI", "naive.sni"))
        "wireguard" -> listOf(
            NodeField("本机私钥", "wireguard.private_key"),
            NodeField("对端公钥", "wireguard.peer_public_key"),
            NodeField("预共享密钥", "wireguard.pre_shared_key"),
            NodeField("本机地址", "wireguard.local_address", hint = "10.0.0.2/32,fd00::2/128"),
            NodeField("Reserved", "wireguard.reserved", hint = "WARP 用，如 1,2,3"),
            NodeField("MTU", "wireguard.mtu", "int"),
        )
        "hysteria" -> listOf(
            NodeField("认证字符串", "hysteria.auth"),
            NodeField("上行 Mbps", "hysteria.up_mbps", "int"),
            NodeField("下行 Mbps", "hysteria.down_mbps", "int"),
            NodeField("混淆密码", "hysteria.obfs"),
        )
        "shadowtls" -> listOf(
            NodeField("ShadowTLS 密码", "password"),
            NodeField("版本", "shadowtls.version", "int", hint = "3"),
            NodeField("握手 SNI", "stream.sni", hint = "如 www.microsoft.com"),
            NodeField("指纹", "stream.utls", hint = "chrome"),
            NodeField("SS 加密方式", "method"),
            NodeField("SS 密码", "shadowtls.ss_password"),
        )
        else -> emptyList()
    }

    /** The rows for [draft]: common, protocol, then QUIC TLS or stream/TLS (NodeEditor.qml:85-115). */
    fun fields(draft: JsonObject): List<NodeField> {
        val p = draft["protocol"].asString()
        val extra = when (p) {
            "hysteria2", "tuic", "hysteria" -> quic
            "shadowtls", "wireguard", "ssh" -> emptyList()
            "vmess", "vless", "trojan", "anytls" -> stream
            else -> if (draft["stream"] is JsonObject) stream else emptyList()
        }
        return common + own(p) + extra
    }

    fun getAt(o: JsonObject, path: String): JsonElement? {
        val parts = path.split('.')
        var cur: JsonElement? = o
        for (p in parts) cur = (cur as? JsonObject)?.get(p) ?: return null
        return cur
    }

    /** The text an input shows for [path]. */
    fun text(o: JsonObject, path: String): String = when (val v = getAt(o, path)) {
        null, JsonNull -> ""
        is JsonPrimitive -> v.content
        else -> v.toString()
    }

    fun bool(o: JsonObject, path: String): Boolean = (getAt(o, path) as? JsonPrimitive)?.booleanOrNull == true

    /**
     * `setAt`: creates missing parents; `""`, `false` and null delete the key (and an emptied
     * parent object goes too, so a cleared stream does not leave `"stream":{}` behind).
     */
    fun setAt(o: JsonObject, path: String, value: JsonElement?): JsonObject {
        val parts = path.split('.')
        return setIn(o, parts, value)
    }

    private fun setIn(o: JsonObject, parts: List<String>, value: JsonElement?): JsonObject {
        val m = LinkedHashMap(o)
        val key = parts.first()
        if (parts.size == 1) {
            if (isEmptyValue(value)) m.remove(key) else m[key] = value!!
        } else {
            val child = setIn(o[key] as? JsonObject ?: EMPTY, parts.drop(1), value)
            if (child.isEmpty()) m.remove(key) else m[key] = child
        }
        return JsonObject(m)
    }

    private fun isEmptyValue(v: JsonElement?): Boolean = when (v) {
        null, JsonNull -> true
        is JsonPrimitive -> (v.isString && v.content.isEmpty()) || (!v.isString && v.booleanOrNull == false)
        else -> false
    }

    /** An int field's text → value: empty deletes, otherwise the number (0–65535 is enforced by the input). */
    fun intValue(text: String): JsonElement? = text.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()?.let(::JsonPrimitive)

    /**
     * Save validation (`NodeEditor.qml:130-137`): non-empty address, port 1–65535 (coerced to a
     * number). Returns the cleaned draft or an error text.
     */
    fun validate(draft: JsonObject): Result<JsonObject> {
        if (draft["server"].asString().trim().isEmpty()) return Result.failure(IllegalArgumentException("地址不能为空"))
        val port = draft["port"].asString().trim().toDoubleOrNull()
        if (port == null || port < 1 || port > 65535 || port % 1.0 != 0.0) return Result.failure(IllegalArgumentException("端口要在 1–65535 之间"))
        return Result.success(setAt(draft, "port", JsonPrimitive(port.toInt())))
    }
}
