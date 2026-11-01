package com.duobao.proxy.state

import com.duobao.proxy.data.asInt
import com.duobao.proxy.data.asString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Typed view of the mobile-only `ui.*` keys the Android settings pages write. They live in `ui`
 * so desktop round-trips keep them untouched; missing keys read as the design's defaults.
 */
class Prefs(private val ui: JsonObject) {
    private fun bool(key: String, default: Boolean) = (ui[key] as? JsonPrimitive)?.booleanOrNull ?: default
    private fun int(key: String, default: Int) = if (ui[key] is JsonPrimitive) ui[key].asInt(default) else default
    private fun str(key: String, default: String) = ui[key].asString().ifEmpty { default }

    /** 开机自启: connect at boot (needs a granted VPN permission). */
    val bootConnect get() = bool(BOOT_CONNECT, false)

    /** 启动后自动连接: connect when the app is opened. */
    val launchConnect get() = bool(LAUNCH_CONNECT, false)

    /** 断线自动重连: seconds before reconnecting after an unexpected drop; 0 = off. */
    val reconnectSec get() = int(RECONNECT_SEC, 5)

    /** 连接前测速选最快节点 (TCP latency within the selected node's group). */
    val pickFastest get() = bool(PICK_FASTEST, false)

    /** 订阅更新 › 自动更新. */
    val subAuto get() = bool(SUB_AUTO, true)

    /** Default interval for groups without their own, in hours; 0 = 每次启动时. */
    val subIntervalH get() = int(SUB_INTERVAL_H, 24)

    val subWifiOnly get() = bool(SUB_WIFI_ONLY, false)

    /** Subscription User-Agent; empty = `DuoBaoProxy/<version>` (Go default). */
    val subUserAgent get() = ui[SUB_UA].asString()

    /** 自动检测剪贴板: offer to import share links found on the clipboard when the app opens. */
    val clipDetect get() = bool(CLIP_DETECT, true)

    /** 允许 URL Scheme 导入 (vmess:// etc. opened from other apps). */
    val urlScheme get() = bool(URL_SCHEME, true)

    /** The group shown first on 首页 (设为默认分组). */
    val homeGroup get() = ui[HOME_GROUP].asString()

    val notifySpeed get() = bool(NOTIFY_SPEED, true)
    val notifyState get() = bool(NOTIFY_STATE, true)
    val notifyExpire get() = bool(NOTIFY_EXPIRE, true)
    val notifyExpireDays get() = int(NOTIFY_EXPIRE_DAYS, 3)
    val notifyTraffic get() = bool(NOTIFY_TRAFFIC, true)
    val notifyTrafficPct get() = int(NOTIFY_TRAFFIC_PCT, 10)
    val notifyAllTimeout get() = bool(NOTIFY_ALL_TIMEOUT, false)

    val backupNodes get() = bool(BACKUP_NODES, true)
    val backupRules get() = bool(BACKUP_RULES, true)
    val backupSettings get() = bool(BACKUP_SETTINGS, true)

    /** ISO time of the last export. */
    val lastBackup get() = ui[LAST_BACKUP].asString()
    val lastBackupSize get() = int(LAST_BACKUP_SIZE, 0)

    /** TUN 模式: route the device through the VPN; off runs only the local proxy port. */
    val tun get() = bool(TUN, true)

    /** 自动切换最快节点 while connected (within the connected node's group). */
    val autoSwitch get() = bool(AUTO_SWITCH, false)

    /** 通过代理更新: subscription downloads go through the tunnel while connected. */
    val subViaProxy get() = bool(SUB_VIA_PROXY, false)

    /** 语言: `system`, `zh` or `en`. */
    val language get() = str(LANGUAGE, "system")

    val proxyMode get() = str("proxy_mode", "global")
    val sort get() = str("sort", "default")

    companion object {
        const val BOOT_CONNECT = "boot_connect"
        const val LAUNCH_CONNECT = "launch_connect"
        const val RECONNECT_SEC = "reconnect_sec"
        const val PICK_FASTEST = "pick_fastest"
        const val SUB_AUTO = "sub_auto"
        const val SUB_INTERVAL_H = "sub_interval_h"
        const val SUB_WIFI_ONLY = "sub_wifi_only"
        const val SUB_UA = "sub_ua"
        const val CLIP_DETECT = "clip_detect"
        const val URL_SCHEME = "url_scheme"
        const val HOME_GROUP = "home_group"
        const val NOTIFY_SPEED = "notify_speed"
        const val NOTIFY_STATE = "notify_state"
        const val NOTIFY_EXPIRE = "notify_expire"
        const val NOTIFY_EXPIRE_DAYS = "notify_expire_days"
        const val NOTIFY_TRAFFIC = "notify_traffic"
        const val NOTIFY_TRAFFIC_PCT = "notify_traffic_pct"
        const val NOTIFY_ALL_TIMEOUT = "notify_all_timeout"
        const val BACKUP_NODES = "backup_nodes"
        const val BACKUP_RULES = "backup_rules"
        const val BACKUP_SETTINGS = "backup_settings"
        const val LAST_BACKUP = "last_backup"
        const val LAST_BACKUP_SIZE = "last_backup_size"
        const val TUN = "tun"
        const val AUTO_SWITCH = "auto_switch"
        const val SUB_VIA_PROXY = "sub_via_proxy"
        const val LANGUAGE = "language"
    }
}
