package com.duobao.proxy.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import com.duobao.proxy.ui.components.AddRow
import com.duobao.proxy.ui.components.DbpIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.duobao.proxy.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duobao.proxy.BuildConfig
import com.duobao.proxy.data.asString
import com.duobao.proxy.state.AppController
import com.duobao.proxy.state.Prefs
import com.duobao.proxy.tests.TestKinds
import com.duobao.proxy.ui.components.Card
import com.duobao.proxy.ui.components.CardRow
import com.duobao.proxy.ui.components.CheckRow
import com.duobao.proxy.ui.components.DbpToggle
import com.duobao.proxy.ui.components.Footnote
import com.duobao.proxy.ui.components.IconTile
import com.duobao.proxy.ui.components.MenuRow
import com.duobao.proxy.ui.components.OutlinePill
import com.duobao.proxy.ui.components.PromptDialog
import com.duobao.proxy.ui.components.SectionLabel
import com.duobao.proxy.ui.components.SegTabs
import com.duobao.proxy.ui.components.SubPageHeader
import com.duobao.proxy.ui.components.ValueChevron
import com.duobao.proxy.ui.theme.DbpTheme
import kotlinx.serialization.json.JsonObject

/** A scrollable sub-page body with its header. */
@Composable
fun SubPage(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(DbpTheme.colors.bg)) {
        SubPageHeader(title, onBack, action)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- DNS ----------------------------------------------------------------------------------------

const val DNS_REMOTE_DEFAULT = "https://dns.google/dns-query"
const val DNS_DIRECT_DEFAULT = "https://doh.pub/dns-query"

private fun routing(settings: JsonObject) = settings.sub("routing")
private fun remoteDns(settings: JsonObject) = routing(settings)?.get("remote_dns").asString().ifEmpty { DNS_REMOTE_DEFAULT }
private fun directDns(settings: JsonObject) = routing(settings)?.get("direct_dns").asString().ifEmpty { DNS_DIRECT_DEFAULT }

/** 自动 (the defaults), 系统 (domestic through the system resolver), or 自定义. */
fun dnsMode(settings: JsonObject): String = when {
    remoteDns(settings) == DNS_REMOTE_DEFAULT && directDns(settings) == DNS_DIRECT_DEFAULT -> "auto"
    remoteDns(settings) == DNS_REMOTE_DEFAULT && directDns(settings) == "local" -> "system"
    else -> "custom"
}

fun dnsModeLabel(settings: JsonObject) = when (dnsMode(settings)) {
    "auto" -> "分流 DNS"
    "system" -> "系统 DNS"
    else -> "自定义"
}

/** The transport label of a DNS address (sing-box syntax, spec/gui-groups-routes.md §2.4). */
fun dnsKind(addr: String): String {
    val a = addr.lowercase()
    return when {
        a == "local" || a == "localhost" || a.isEmpty() -> "系统"
        a.startsWith("https://") || a.startsWith("http://") -> "DoH"
        a.startsWith("h3://") -> "DoH3"
        a.startsWith("tls://") -> "DoT"
        a.startsWith("quic://") -> "DoQ"
        a.startsWith("tcp://") -> "TCP"
        a.startsWith("dhcp://") -> "DHCP"
        else -> "UDP"
    }
}

/** The servers of a DNS setting (newlines or commas, as `render.DNSList` reads them). */
fun dnsServers(value: String): List<String> = value.split('\n', ',').map(String::trim).filter(String::isNotEmpty).distinct()

@Composable
fun DnsScreen(onBack: () -> Unit) {
    val c = AppController.instance
    val colors = DbpTheme.colors
    val settings by c.store.settings.collectAsStateWithLifecycle()
    var custom by remember { mutableStateOf(dnsMode(settings) == "custom") }
    val mode = if (custom) "custom" else dnsMode(settings)
    var adding by remember { mutableStateOf<Pair<String, String>?>(null) }

    SubPage("DNS 设置", onBack) {
        SegTabs(listOf("auto" to "自动", "system" to "系统", "custom" to "自定义"), mode, { m ->
            custom = m == "custom"
            when (m) {
                "auto" -> { c.setSetting("routing.remote_dns", DNS_REMOTE_DEFAULT); c.setSetting("routing.direct_dns", DNS_DIRECT_DEFAULT) }
                "system" -> { c.setSetting("routing.remote_dns", DNS_REMOTE_DEFAULT); c.setSetting("routing.direct_dns", "local") }
            }
        })
        for ((key, title, value) in listOf(Triple("direct_dns", "国内 DNS", directDns(settings)), Triple("remote_dns", "国外 DNS", remoteDns(settings)))) {
            val list = dnsServers(value)
            SectionLabel(title)
            Card {
                list.forEachIndexed { i, addr ->
                    CardRow(if (addr == "local") "系统 DNS" else addr, mono = addr != "local", divider = i > 0) {
                        Text(dnsKind(addr), color = colors.fg2, fontSize = 14.sp)
                        // The last server cannot go: an empty list would fall back to the default.
                        if (custom && list.size > 1) {
                            Box(Modifier.size(32.dp).clip(CircleShape).clickable { c.setSetting("routing.$key", (list - addr).joinToString("\n")) }, contentAlignment = Alignment.Center) {
                                DbpIcon("x", colors.fg3, size = 18.dp)
                            }
                        }
                    }
                }
                if (custom) {
                    HorizontalDivider(thickness = 1.dp, color = colors.line)
                    AddRow("添加服务器") { adding = key to "" }
                }
            }
        }
        SectionLabel("高级")
        Card {
            CardRow("DNS 分流", onClick = infoClick("DNS 分流")) { DbpToggle(settings.flag("routing", "dns_routing", true), { c.setSetting("routing.dns_routing", it) }) }
            CardRow("DoH 优先", divider = true, onClick = infoClick("DoH 优先")) { DbpToggle(settings.flag("routing", "doh_first"), { c.setSetting("routing.doh_first", it) }) }
            CardRow("Fake-IP", divider = true, onClick = infoClick("Fake-IP")) { DbpToggle(settings.flag("tun", "fake_dns"), { c.setSetting("tun.fake_dns", it) }) }
            CardRow("DNS 劫持", divider = true, onClick = infoClick("DNS 劫持")) {
                DbpToggle(!settings.flag("tun", "loose_dns_hijack"), { c.setSetting("tun.loose_dns_hijack", !it) })
            }
        }
        Footnote("国内域名用国内 DNS 解析，其余走国外 DNS，避免 DNS 污染和泄露。支持 223.5.5.5、tls://、https://、quic:// 等写法，填 local 使用系统 DNS。Xray 按列表顺序依次尝试；sing-box 使用每组的第一个。")
    }

    adding?.let { (key, text) ->
        PromptDialog(
            if (key == "direct_dns") "添加国内 DNS" else "添加国外 DNS", text, { adding = key to it.trim() }, "如 223.5.5.5 或 https://…", "添加", text.isNotEmpty(), null,
            onConfirm = {
                val list = dnsServers((settings.sub("routing")?.get(key)).asString().ifEmpty { if (key == "direct_dns") DNS_DIRECT_DEFAULT else DNS_REMOTE_DEFAULT })
                c.setSetting("routing.$key", (list + text).distinct().joinToString("\n"))
                adding = null
            },
            onDismiss = { adding = null }, mono = true,
        )
    }
}

// ---- 内核 ---------------------------------------------------------------------------------------

private val LOG_LEVELS = listOf("trace" to "跟踪", "debug" to "调试", "info" to "信息", "warn" to "警告", "error" to "错误")

@Composable
fun CoreScreen(onBack: () -> Unit) {
    val c = AppController.instance
    val colors = DbpTheme.colors
    val context = LocalContext.current
    val cores by c.cores.collectAsStateWithLifecycle()
    val core by c.coreId.collectAsStateWithLifecycle()
    val connected by c.connected.collectAsStateWithLifecycle()
    val settings by c.store.settings.collectAsStateWithLifecycle()
    val info = cores.firstOrNull { it.id == core }

    SubPage("内核", onBack) {
        Card {
            CardRow(
                listOf(core, info?.version.orEmpty()).filter { it.isNotEmpty() }.joinToString(" "),
                subtitle = (if (connected) "运行中" else "未运行") + " · 支持 ${info?.protocols?.size ?: 0} 种协议",
                leading = { IconTile("cpu", colors.accent, colors.accentFg, size = 48.dp) },
            )
        }
        SectionLabel("切换内核")
        Card {
            val ids = cores.map { it.id }.ifEmpty { listOf(AppController.CORE_SINGBOX, AppController.CORE_XRAY) }
            ids.forEachIndexed { i, id ->
                CheckRow(if (id == AppController.CORE_XRAY) "Xray" else id, id == core, divider = i > 0) { c.setCore(id) }
            }
        }
        SectionLabel("选项")
        Card {
            CardRow("多路复用", onClick = infoClick("多路复用")) { DbpToggle(settings.flag("mux", "default_on"), { c.setSetting("mux.default_on", it) }) }
            CardRow("TCP Fast Open", divider = true, onClick = infoClick("TCP Fast Open")) { DbpToggle(settings.flag("tcp_fast_open"), { c.setSetting("tcp_fast_open", it) }) }
            MenuRow("日志级别", LOG_LEVELS, settings["log_level"].asString().ifEmpty { "info" }, { c.setSetting("log_level", it) }, divider = true)
        }
        Footnote("切换内核后会自动重新连接，节点配置无需修改。当前内核不支持的协议会在首页变灰。")
    }
}

// ---- 订阅更新 -----------------------------------------------------------------------------------

@Composable
fun SubUpdateScreen(onBack: () -> Unit) {
    val c = AppController.instance
    val ui by c.store.ui.collectAsStateWithLifecycle()
    val p = Prefs(ui)
    var ua by remember { mutableStateOf<String?>(null) }
    SubPage("订阅更新", onBack) {
        Card { CardRow("自动更新", onClick = infoClick("自动更新")) { DbpToggle(p.subAuto, { c.setUi(Prefs.SUB_AUTO, it) }) } }
        if (p.subAuto) {
            SectionLabel("更新间隔")
            Card {
                listOf(6 to "每 6 小时", 12 to "每 12 小时", 24 to "每 24 小时", 0 to "每次启动时").forEachIndexed { i, (h, label) ->
                    CheckRow(label, p.subIntervalH == h, divider = i > 0) { c.setSubInterval(h) }
                }
            }
        }
        SectionLabel("更新方式")
        Card {
            CardRow("仅 Wi-Fi 下更新", onClick = infoClick("仅 Wi-Fi 下更新")) { DbpToggle(p.subWifiOnly, { c.setUi(Prefs.SUB_WIFI_ONLY, it) }) }
            CardRow("通过代理更新", divider = true, onClick = infoClick("通过代理更新")) { DbpToggle(p.subViaProxy, { c.setUi(Prefs.SUB_VIA_PROXY, it) }) }
            CardRow("User-Agent", divider = true, onClick = { ua = p.subUserAgent }) { ValueChevron(p.subUserAgent.ifEmpty { "DuoBaoProxy/" + BuildConfig.VERSION_NAME }) }
        }
        SectionLabel("导入")
        Card {
            CardRow("自动检测剪贴板", onClick = infoClick("自动检测剪贴板")) { DbpToggle(p.clipDetect, { c.setUi(Prefs.CLIP_DETECT, it) }) }
            CardRow("允许 URL Scheme 导入", divider = true, onClick = infoClick("允许 URL Scheme 导入")) { DbpToggle(p.urlScheme, { c.setUi(Prefs.URL_SCHEME, it) }) }
        }
        Footnote(if (p.subViaProxy) "已连接时订阅经代理下载；未连接时直接从本机网络拉取。" else "订阅直接从本机网络拉取，不经过代理。")
    }
    ua?.let { text ->
        PromptDialog("User-Agent", text, { ua = it }, "留空使用默认", "保存", true, null,
            onConfirm = { c.setUi(Prefs.SUB_UA, text.trim()); ua = null }, onDismiss = { ua = null })
    }
}

// ---- 延迟测试 -----------------------------------------------------------------------------------

private val LATENCY_TARGETS = listOf(
    "http://www.gstatic.com/generate_204" to "Google",
    "http://cp.cloudflare.com/" to "Cloudflare",
    "http://captive.apple.com/hotspot-detect.html" to "Apple",
)

fun latencyTargetName(url: String) = LATENCY_TARGETS.firstOrNull { it.first == url }?.second ?: "自定义"

@Composable
fun LatencyScreen(onBack: () -> Unit) {
    val c = AppController.instance
    val ui by c.store.ui.collectAsStateWithLifecycle()
    val url = TestKinds.optString(ui, "url", "url")
    val timeout = TestKinds.ms(ui, "url") / 1000
    var custom by remember { mutableStateOf<String?>(null) }
    fun set(f: String, v: Any) = c.setUi("tests", TestKinds.withOpt(ui, "url", f, v))
    SubPage("延迟测试", onBack) {
        SectionLabel("测试地址")
        Card {
            LATENCY_TARGETS.forEachIndexed { i, (u, name) -> CheckRow(name, url == u, divider = i > 0, subtitle = u) { set("url", u) } }
            val isCustom = LATENCY_TARGETS.none { it.first == url }
            CheckRow("自定义", isCustom, divider = true, subtitle = if (isCustom) url else "返回 2xx / 3xx 即视为成功") { custom = if (isCustom) url else "https://" }
        }
        SectionLabel("超时")
        Card {
            listOf(3, 5, 8, 10).forEachIndexed { i, s -> CheckRow("$s 秒", timeout == s, divider = i > 0) { set("timeout", s) } }
        }
        Footnote("首页「测试」和连接后的实时延迟都使用这里的设置。")
    }
    custom?.let { text ->
        val ok = Regex("""^https?://\S+$""").matches(text.trim())
        PromptDialog("自定义测试地址", text, { custom = it }, "http(s)://…", "保存", ok, null,
            onConfirm = { set("url", text.trim()); custom = null }, onDismiss = { custom = null }, mono = true)
    }
}

// ---- 通知 ---------------------------------------------------------------------------------------

@Composable
fun NotifyScreen(onBack: () -> Unit) {
    val c = AppController.instance
    val ui by c.store.ui.collectAsStateWithLifecycle()
    val p = Prefs(ui)
    SubPage("通知", onBack) {
        Card {
            CardRow("常驻通知显示速率", onClick = infoClick("常驻通知显示速率")) { DbpToggle(p.notifySpeed, { c.setUi(Prefs.NOTIFY_SPEED, it) }) }
            CardRow("连接状态变化", divider = true, onClick = infoClick("连接状态变化")) { DbpToggle(p.notifyState, { c.setUi(Prefs.NOTIFY_STATE, it) }) }
        }
        SectionLabel("提醒")
        Card {
            CardRow("订阅到期提醒", onClick = infoClick("订阅到期提醒")) { DbpToggle(p.notifyExpire, { c.setUi(Prefs.NOTIFY_EXPIRE, it) }) }
            if (p.notifyExpire) MenuRow("提前提醒", listOf(1 to "1 天", 3 to "3 天", 7 to "7 天"), p.notifyExpireDays, { c.setUi(Prefs.NOTIFY_EXPIRE_DAYS, it) }, divider = true)
            CardRow("流量不足提醒", divider = true, onClick = infoClick("流量不足提醒")) { DbpToggle(p.notifyTraffic, { c.setUi(Prefs.NOTIFY_TRAFFIC, it) }) }
            if (p.notifyTraffic) MenuRow("剩余低于", listOf(5 to "5%", 10 to "10%", 20 to "20%"), p.notifyTrafficPct, { c.setUi(Prefs.NOTIFY_TRAFFIC_PCT, it) }, divider = true)
            CardRow("节点全部超时", divider = true, onClick = infoClick("节点全部超时")) { DbpToggle(p.notifyAllTimeout, { c.setUi(Prefs.NOTIFY_ALL_TIMEOUT, it) }) }
        }
        Footnote("到期和流量提醒在订阅更新后检查，每个订阅每天最多提醒一次。")
    }
}

// ---- 操作说明与关于 ------------------------------------------------------------------------------

private val HELP = listOf(
    "首页" to listOf(
        "代理服务" to "打开：按当前节点建立 VPN，接管手机的全部流量。关闭：停止代理。",
        "规则 / 全局 / 直连" to "规则：按「规则」页的分流策略和自定义规则决定去向，没命中的走「未命中」设置。全局：除拦截和分应用代理外，所有流量都走代理。直连：规则照常生效，没命中的直连。",
        "节点" to "点一下选中并切换节点；长按可编辑、测试、分享、移动或删除。灰色节点是当前内核不支持的协议。",
        "延迟测试" to "通过节点访问测试地址，计算往返时间。地址和超时在「设置 › 延迟测试」修改。",
    ),
    "订阅与规则" to listOf(
        "订阅" to "添加机场的订阅链接，节点会导入到同名分组并按间隔自动更新；更新失败或没有解析出节点时保留原有节点。",
        "分流策略" to "常用的直连、代理和拦截名单，打开即生效。",
        "自定义规则" to "按域名、关键词、正则、GeoSite、IP 段或 GeoIP 决定代理、直连或拦截。",
        "分应用代理" to "仅所选走代理：只有选中的应用经过 VPN。所选不走代理：选中的应用直接联网。",
    ),
    "设置" to listOf(
        "Kill Switch" to "Android 的真正断网保护在系统设置里：网络 › VPN › 多宝代理 › 始终开启的 VPN，并打开「屏蔽未使用 VPN 的连接」。",
        "IPv6" to "关闭时 IPv6 流量会被拦截，避免绕过 VPN。网络支持 IPv6 时可以打开。",
        "代理端口" to "本机 socks / http 混合端口，供不支持 VPN 的应用手动设置代理；允许局域网后可给同一 Wi-Fi 下的设备使用。",
    ),
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val colors = DbpTheme.colors
    val context = LocalContext.current
    SubPage("操作说明与关于", onBack) {
        for ((section, entries) in HELP) {
            SectionLabel(section)
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((name, body) in entries) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(name, color = colors.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(body, color = colors.fg2, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }
                }
            }
        }
        SectionLabel("关于")
        Card {
            CardRow("多宝代理 DuoBao Proxy", subtitle = "版本 " + BuildConfig.VERSION_NAME)
            CardRow("关于开发者", divider = true, onClick = { openUrl(context, DEVELOPER_URL) }) { ValueChevron("Telegram") }
            CardRow("发布页", divider = true, onClick = { openUrl(context, RELEASES_URL) }) { ValueChevron("GitHub") }
        }
    }
}
