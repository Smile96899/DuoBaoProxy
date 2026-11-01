package com.duobao.proxy.ui.screens.settings

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duobao.proxy.data.asString
import com.duobao.proxy.state.AppController
import com.duobao.proxy.state.Prefs
import com.duobao.proxy.state.Rules
import kotlinx.serialization.json.JsonObject
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.duobao.proxy.ui.InfoRoute
import com.duobao.proxy.ui.components.Card
import com.duobao.proxy.ui.components.CardRow
import com.duobao.proxy.ui.components.DbpToggle
import com.duobao.proxy.ui.components.Footnote
import com.duobao.proxy.ui.components.ValueChevron

/** The app's nav controller, so any settings row can open its 说明 page. */
val LocalNav = compositionLocalOf<NavHostController?> { null }

/** One-line 开启 / 关闭 note for every switch in 设置, keyed by the row's title. */
val SETTING_INFO: Map<String, String> = mapOf(
    "开机自启" to "开启后手机开机自动连接，关闭则需手动连接",
    "启动后自动连接" to "开启后打开应用即连接，关闭则手动连接",
    "连接前测速选最快节点" to "开启后连接前自动选最快节点，关闭则用所选节点",
    "允许局域网连接" to "开启后同网设备可共用代理，关闭则仅本机可用",
    "TUN 模式" to "开启后全部应用走代理，关闭则需手动设代理",
    "IPv6" to "开启后 IPv6 走代理，关闭则拦截 IPv6 防泄露",
    "Kill Switch" to "开启后 VPN 断开即断网，关闭则断开后直连",
    "绕过中国大陆" to "开启后国内网站直连，关闭则按其他规则处理",
    "自动切换最快节点" to "开启后节点变慢自动切换，关闭则固定所选节点",
    "DNS 分流" to "开启后国内外分别解析，关闭则统一解析",
    "DoH 优先" to "开启后优先用加密 DNS，关闭则按列表顺序",
    "Fake-IP" to "开启后连接更快但少数应用不兼容，关闭则兼容最好",
    "DNS 劫持" to "开启后应用 DNS 交给内核，关闭则可能泄露",
    "多路复用" to "开启后减少握手更快，关闭则兼容所有节点",
    "TCP Fast Open" to "开启后首包更快，关闭则兼容性最好",
    "自动更新" to "开启后按间隔自动更新，关闭则只手动更新",
    "仅 Wi-Fi 下更新" to "开启后流量下不自动更新，关闭则随时更新",
    "通过代理更新" to "开启后经代理下载订阅，关闭则直接下载",
    "自动检测剪贴板" to "开启后发现链接提示导入，关闭则不读剪贴板",
    "允许 URL Scheme 导入" to "开启后点击节点链接可导入，关闭则不响应",
    "常驻通知显示速率" to "开启后通知栏显示网速，关闭则只显示状态",
    "连接状态变化" to "开启后断线或重连时提醒，关闭则不提醒",
    "订阅到期提醒" to "开启后到期前提醒续费，关闭则不提醒",
    "流量不足提醒" to "开启后流量快用完时提醒，关闭则不提醒",
    "节点全部超时" to "开启后节点全部超时时提醒，关闭则不提醒",
    "订阅与节点" to "开启后备份包含订阅和节点，关闭则不包含",
    "规则" to "开启后备份包含分流规则，关闭则不包含",
    "设置" to "开启后备份包含各项设置，关闭则不包含",
)

/** The click for a settings row: opens its 说明 page. */
@Composable
fun infoClick(title: String): (() -> Unit)? {
    val nav = LocalNav.current ?: return null
    if (title !in SETTING_INFO) return null
    return { nav.navigate(InfoRoute(title)) }
}

/** Reads and writes the switch behind each 说明 page (same keys as [SETTING_INFO]). */
private class Switch(val get: (Prefs, JsonObject) -> Boolean, val set: (AppController, JsonObject, Boolean) -> Unit)

private fun ui(k: String, read: (Prefs) -> Boolean) = Switch({ p, _ -> read(p) }, { c, _, v -> c.setUi(k, v) })
private fun st(path: String, default: Boolean = false, invert: Boolean = false) = Switch(
    { _, s -> val (a, b) = path.split('.').let { it[0] to it.getOrNull(1) }; (if (b == null) s.flag(a) else s.flag(a, b, default)) != invert },
    { c, _, v -> c.setSetting(path, v != invert) },
)

private val SWITCHES: Map<String, Switch> = mapOf(
    "开机自启" to ui(Prefs.BOOT_CONNECT) { it.bootConnect },
    "启动后自动连接" to ui(Prefs.LAUNCH_CONNECT) { it.launchConnect },
    "连接前测速选最快节点" to ui(Prefs.PICK_FASTEST) { it.pickFastest },
    "允许局域网连接" to Switch({ _, s -> s.sub("inbound")?.get("address").asString() == "0.0.0.0" }, { c, _, v -> c.setSetting("inbound.address", if (v) "0.0.0.0" else "127.0.0.1") }),
    "TUN 模式" to ui(Prefs.TUN) { it.tun },
    "IPv6" to st("tun.ipv6"),
    "绕过中国大陆" to Switch(
        { _, s -> Rules.enabled(s, Rules.presets.first { it.id == "cn" }) },
        { c, s, v -> Rules.toggle(s, Rules.presets.first { it.id == "cn" }, v).forEach { (k, x) -> c.setSetting("routing.$k", x) } },
    ),
    "自动切换最快节点" to ui(Prefs.AUTO_SWITCH) { it.autoSwitch },
    "DNS 分流" to st("routing.dns_routing", default = true),
    "DoH 优先" to st("routing.doh_first"),
    "Fake-IP" to st("tun.fake_dns"),
    "DNS 劫持" to st("tun.loose_dns_hijack", invert = true),
    "多路复用" to st("mux.default_on"),
    "TCP Fast Open" to st("tcp_fast_open"),
    "自动更新" to ui(Prefs.SUB_AUTO) { it.subAuto },
    "仅 Wi-Fi 下更新" to ui(Prefs.SUB_WIFI_ONLY) { it.subWifiOnly },
    "通过代理更新" to ui(Prefs.SUB_VIA_PROXY) { it.subViaProxy },
    "自动检测剪贴板" to ui(Prefs.CLIP_DETECT) { it.clipDetect },
    "允许 URL Scheme 导入" to ui(Prefs.URL_SCHEME) { it.urlScheme },
    "常驻通知显示速率" to ui(Prefs.NOTIFY_SPEED) { it.notifySpeed },
    "连接状态变化" to ui(Prefs.NOTIFY_STATE) { it.notifyState },
    "订阅到期提醒" to ui(Prefs.NOTIFY_EXPIRE) { it.notifyExpire },
    "流量不足提醒" to ui(Prefs.NOTIFY_TRAFFIC) { it.notifyTraffic },
    "节点全部超时" to ui(Prefs.NOTIFY_ALL_TIMEOUT) { it.notifyAllTimeout },
    "订阅与节点" to ui(Prefs.BACKUP_NODES) { it.backupNodes },
    "规则" to ui(Prefs.BACKUP_RULES) { it.backupRules },
    "设置" to ui(Prefs.BACKUP_SETTINGS) { it.backupSettings },
)

/** A switch's 说明 page: the switch on top, then what 开启 and 关闭 do in small grey text. */
@Composable
fun InfoScreen(title: String, onBack: () -> Unit) {
    val note = SETTING_INFO[title].orEmpty()
    val c = AppController.instance
    val ui by c.store.ui.collectAsStateWithLifecycle()
    val settings by c.store.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sw = SWITCHES[title]
    SubPage(title, onBack) {
        Card {
            if (sw != null) {
                CardRow(title) { DbpToggle(sw.get(Prefs(ui), settings), { sw.set(c, settings, it) }) }
            } else {
                CardRow("打开系统 VPN 设置", onClick = {
                    runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { ValueChevron() }
            }
        }
        Footnote(note)
    }
}
