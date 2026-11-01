package com.duobao.proxy.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.duobao.proxy.BuildConfig
import com.duobao.proxy.R
import com.duobao.proxy.data.asInt
import com.duobao.proxy.data.asString
import com.duobao.proxy.state.AppController
import com.duobao.proxy.state.Prefs
import com.duobao.proxy.state.Rules
import com.duobao.proxy.tests.TestKinds
import com.duobao.proxy.ui.AboutRoute
import com.duobao.proxy.ui.AppsRoute
import com.duobao.proxy.ui.BackupRoute
import com.duobao.proxy.ui.CoreRoute
import com.duobao.proxy.ui.DiagnoseRoute
import com.duobao.proxy.ui.DnsRoute
import com.duobao.proxy.ui.LatencyRoute
import com.duobao.proxy.ui.LogsRoute
import com.duobao.proxy.ui.NotifyRoute
import com.duobao.proxy.ui.RulesRoute
import com.duobao.proxy.ui.SubUpdateRoute
import com.duobao.proxy.ui.components.Card
import com.duobao.proxy.ui.components.CardRow
import com.duobao.proxy.ui.components.DbpToggle
import com.duobao.proxy.ui.components.MenuRow
import com.duobao.proxy.ui.components.PageTitle
import com.duobao.proxy.ui.components.PromptDialog
import com.duobao.proxy.ui.components.SectionLabel
import com.duobao.proxy.ui.components.ValueChevron
import com.duobao.proxy.ui.screens.home.PROXY_MODES
import com.duobao.proxy.ui.screens.subs.ago
import com.duobao.proxy.ui.theme.DbpTheme
import com.duobao.proxy.ui.theme.ThemeMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

const val RELEASES_URL = "https://github.com/Smile96899/DuoBaoProxy/releases"
const val DEVELOPER_URL = "https://t.me/hyk857"

fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** `settings[a][b]` helpers for the pages. */
fun JsonObject.sub(a: String): JsonObject? = this[a] as? JsonObject
fun JsonObject.flag(a: String, b: String, default: Boolean = false) = (sub(a)?.get(b) as? JsonPrimitive)?.booleanOrNull ?: default
fun JsonObject.flag(a: String) = (this[a] as? JsonPrimitive)?.booleanOrNull ?: false

/** 语言 choices; the names stay in their own language. */
val LANGUAGES = listOf("system" to "跟随系统", "zh" to "简体中文", "en" to "English")

val RECONNECT_CHOICES = listOf(0 to "关闭", 3 to "3 秒", 5 to "5 秒", 10 to "10 秒", 30 to "30 秒")

fun subUpdateLabel(p: Prefs) = when {
    !p.subAuto -> "已关闭"
    p.subIntervalH == 0 -> "每次启动时"
    else -> "每 ${p.subIntervalH} 小时"
}

@Composable
fun SettingsScreen(nav: NavHostController) {
    val c = AppController.instance
    val colors = DbpTheme.colors
    val context = LocalContext.current
    val ui by c.store.ui.collectAsStateWithLifecycle()
    val settings by c.store.settings.collectAsStateWithLifecycle()
    val cores by c.cores.collectAsStateWithLifecycle()
    val core by c.coreId.collectAsStateWithLifecycle()
    val prefs = Prefs(ui)
    var portEdit by remember { mutableStateOf<String?>(null) }
    val inbound = settings.sub("inbound")
    val port = inbound?.get("port").asInt(0)
    val cnPreset = Rules.presets.first { it.id == "cn" }
    val apps = (settings.sub("tun")?.get("rule_process") as? JsonArray)?.size ?: 0
    val coreVersion = cores.firstOrNull { it.id == core }?.version.orEmpty()

    Column(Modifier.fillMaxSize().background(colors.bg).verticalScroll(rememberScrollState())) {
        PageTitle("设置") { Image(painterResource(R.drawable.logo), null, Modifier.size(40.dp)) }

        SectionLabel("连接")
        Card {
            CardRow("开机自启", onClick = infoClick("开机自启")) { DbpToggle(prefs.bootConnect, { c.setUi(Prefs.BOOT_CONNECT, it) }) }
            CardRow("启动后自动连接", divider = true, onClick = infoClick("启动后自动连接")) { DbpToggle(prefs.launchConnect, { c.setUi(Prefs.LAUNCH_CONNECT, it) }) }
            MenuRow("断线自动重连", RECONNECT_CHOICES, prefs.reconnectSec, { c.setUi(Prefs.RECONNECT_SEC, it) }, divider = true)
            CardRow("连接前测速选最快节点", divider = true, onClick = infoClick("连接前测速选最快节点")) { DbpToggle(prefs.pickFastest, { c.setUi(Prefs.PICK_FASTEST, it) }) }
        }

        SectionLabel("网络")
        Card {
            CardRow("DNS", onClick = { nav.navigate(DnsRoute) }) { ValueChevron(dnsModeLabel(settings)) }
            CardRow("内核", divider = true, onClick = { nav.navigate(CoreRoute) }) { ValueChevron(listOf(core, coreVersion).filter { it.isNotEmpty() }.joinToString(" ")) }
            CardRow("代理端口", divider = true, onClick = { portEdit = if (port > 0) port.toString() else "" }) {
                ValueChevron(if (port > 0) port.toString() else "关闭")
            }
            CardRow("允许局域网连接", divider = true, enabled = port > 0, onClick = infoClick("允许局域网连接")) {
                DbpToggle(inbound?.get("address").asString() == "0.0.0.0", { c.setSetting("inbound.address", if (it) "0.0.0.0" else "127.0.0.1") }, enabled = port > 0)
            }
            CardRow("TUN 模式", divider = true, onClick = infoClick("TUN 模式")) { DbpToggle(prefs.tun, { c.setUi(Prefs.TUN, it) }) }
            CardRow("IPv6", divider = true, onClick = infoClick("IPv6")) { DbpToggle(settings.flag("tun", "ipv6"), { c.setSetting("tun.ipv6", it) }) }
            CardRow("Kill Switch", divider = true, onClick = infoClick("Kill Switch")) { ValueChevron() }
        }

        SectionLabel("分流")
        Card {
            MenuRow("代理模式", PROXY_MODES, prefs.proxyMode, { c.setUi("proxy_mode", it) })
            CardRow("规则集管理", divider = true, onClick = { nav.navigate(RulesRoute) }) { ValueChevron("${Rules.ruleSetCount(settings)} 个") }
            CardRow("分应用代理", divider = true, onClick = { nav.navigate(AppsRoute) }) { ValueChevron(if (apps > 0) "已选 $apps 个" else "未启用") }
            CardRow("绕过中国大陆", divider = true, onClick = infoClick("绕过中国大陆")) {
                DbpToggle(Rules.enabled(settings, cnPreset), { on -> Rules.toggle(settings, cnPreset, on).forEach { (k, v) -> c.setSetting("routing.$k", v) } })
            }
        }

        SectionLabel("订阅与节点")
        Card {
            CardRow("订阅更新", onClick = { nav.navigate(SubUpdateRoute) }) { ValueChevron(subUpdateLabel(prefs)) }
            CardRow("延迟测试", divider = true, onClick = { nav.navigate(LatencyRoute) }) {
                ValueChevron(latencyTargetName(TestKinds.optString(ui, "url", "url")) + " · ${TestKinds.ms(ui, "url") / 1000} 秒")
            }
            CardRow("自动切换最快节点", divider = true, onClick = infoClick("自动切换最快节点")) { DbpToggle(prefs.autoSwitch, { c.setUi(Prefs.AUTO_SWITCH, it) }) }
        }

        SectionLabel("备份与恢复")
        Card {
            CardRow("备份与恢复", onClick = { nav.navigate(BackupRoute) }) {
                ValueChevron(prefs.lastBackup.toLongOrNull()?.let { ago(it) } ?: "")
            }
        }

        SectionLabel("外观与关于")
        Card {
            MenuRow("语言", LANGUAGES, prefs.language, { c.setUi(Prefs.LANGUAGE, it) })
            MenuRow("深色模式", ThemeMode.entries.map { it.key to it.label }, ThemeMode.from(ui["theme"].asString()).key, { c.setUi("theme", it) }, divider = true)
            CardRow("通知", divider = true, onClick = { nav.navigate(NotifyRoute) }) { ValueChevron() }
            CardRow("连接日志", divider = true, onClick = { nav.navigate(LogsRoute) }) { ValueChevron() }
            CardRow("诊断", divider = true, onClick = { nav.navigate(DiagnoseRoute) }) { ValueChevron() }
            CardRow("操作说明与关于", divider = true, onClick = { nav.navigate(AboutRoute) }) { ValueChevron() }
            CardRow("检查更新", divider = true, onClick = { openUrl(context, RELEASES_URL) }) { ValueChevron("v" + BuildConfig.VERSION_NAME) }
        }
        Spacer(Modifier.height(24.dp))
    }

    portEdit?.let { text ->
        val n = text.toIntOrNull()
        val ok = text.isEmpty() || (n != null && n in 1..65535)
        PromptDialog(
            "代理端口", text, { portEdit = it.filter(Char::isDigit).take(5) }, "留空则关闭", "保存", ok,
            if (ok) null else "端口要在 1–65535 之间",
            onConfirm = { c.setSetting("inbound.port", n ?: 0); portEdit = null },
            onDismiss = { portEdit = null },
            numeric = true,
        )
    }
}
