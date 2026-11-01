package com.duobao.proxy.ui.screens.settings

import com.duobao.proxy.core.tr
import android.content.Context
import android.content.Intent
import com.duobao.proxy.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duobao.core.mobile.Mobile
import com.duobao.proxy.core.GeoData
import com.duobao.proxy.core.GoCore
import com.duobao.proxy.core.LogRepository
import com.duobao.proxy.core.NetworkTracker
import com.duobao.proxy.data.asInt
import com.duobao.proxy.state.AppController
import com.duobao.proxy.state.Rules
import com.duobao.proxy.tests.TestEngine
import com.duobao.proxy.ui.components.Card
import com.duobao.proxy.ui.components.CardRow
import com.duobao.proxy.ui.components.IconTile
import com.duobao.proxy.ui.components.MenuRow
import com.duobao.proxy.ui.components.PrimaryButton
import com.duobao.proxy.ui.components.SectionLabel
import com.duobao.proxy.ui.components.UiMessages
import com.duobao.proxy.ui.components.ValueChevron
import com.duobao.proxy.ui.theme.DbpTheme
import com.duobao.proxy.ui.theme.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** One diagnosis line. */
data class Check(val name: String, val level: Level, val value: String) {
    enum class Level { Ok, Warn, Bad, Info, Pending }
}

private suspend fun runChecks(): List<Check> = coroutineScope {
    val c = AppController.instance
    val settings = c.store.settings.value
    val connected = c.connected.value
    val net = NetworkTracker.current.value
    val network = if (net != null) Check("网络连接", Check.Level.Ok, if (net.metered) "移动网络" else "正常") else Check("网络连接", Check.Level.Bad, "无网络")
    val dns = async(Dispatchers.IO) {
        runCatching {
            val t0 = System.nanoTime()
            InetAddress.getAllByName("www.baidu.com")
            ((System.nanoTime() - t0) / 1_000_000).toInt()
        }.fold({ Check("DNS 解析", Check.Level.Ok, "$it ms") }, { Check("DNS 解析", Check.Level.Bad, "失败") })
    }
    val port = (settings["inbound"] as? kotlinx.serialization.json.JsonObject)?.get("port").asInt(0)
    val portCheck = async(Dispatchers.IO) {
        when {
            port <= 0 -> Check("代理端口", Check.Level.Info, "已关闭")
            !connected -> Check("代理端口", Check.Level.Info, "未连接")
            else -> runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1500) } }
                .fold({ Check("代理端口", Check.Level.Ok, "正常") }, { Check("代理端口", Check.Level.Bad, "无法连接") })
        }
    }
    val sets = Rules.ruleSetCount(settings)
    val ruleCheck = if (c.currentCore() == AppController.CORE_XRAY) {
        val missing = listOf(GeoData.GEOIP, GeoData.GEOSITE).filterNot(c.geo::has)
        if (missing.isEmpty()) Check("规则集加载", Check.Level.Ok, "$sets 个") else Check("规则集加载", Check.Level.Warn, "缺少规则数据")
    } else {
        Check("规则集加载", Check.Level.Ok, "$sets 个")
    }
    val sel = c.store.node(c.currentSelected())
    val group = sel?.let { s -> c.store.nodes.value.filter { it.group == s.group && c.supports(it.node) } }.orEmpty().take(30)
    val nodes = async {
        if (group.isEmpty()) return@async Check("节点连通", Check.Level.Info, "未选择节点")
        val res = group.map { e -> async(GoCore.pool) { runCatching { Mobile.tcpPing(TestEngine.hostPort(e), 3000) }.getOrDefault(-1) } }.awaitAll()
        val bad = res.count { it <= 0 }
        when {
            bad == 0 -> Check("节点连通", Check.Level.Ok, "全部正常")
            bad == res.size -> Check("节点连通", Check.Level.Bad, "全部超时")
            else -> Check("节点连通", Check.Level.Warn, "$bad 个超时")
        }
    }
    val running = runCatching { GoCore.call { Mobile.status() } }.getOrDefault("")
    val core = if (running.isNotEmpty()) Check("内核状态", Check.Level.Info, "运行中") else Check("内核状态", Check.Level.Info, "未运行")
    listOf(network, dns.await(), portCheck.await(), ruleCheck, nodes.await(), core)
}

private fun dirSize(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf(::dirSize) ?: 0L

private fun report(context: Context, checks: List<Check>): Intent {
    val text = "DuoBao Proxy 诊断\n" + checks.joinToString("\n") { "${it.name}：${it.value}" } + "\n\n" + LogRepository.exportText(context)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "DuoBao Proxy 诊断报告").putExtra(Intent.EXTRA_TEXT, text)
    return Intent.createChooser(send, tr("导出诊断报告"))
}

private val LOG_LEVELS = listOf("trace" to "跟踪", "debug" to "调试", "info" to "信息", "warn" to "警告", "error" to "错误")

@Composable
fun DiagnoseScreen(onBack: () -> Unit) {
    val c = AppController.instance
    val colors = DbpTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.store.settings.collectAsStateWithLifecycle()
    var checks by remember { mutableStateOf<List<Check>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var cache by remember { mutableStateOf(0L) }
    fun rerun() {
        running = true
        scope.launch {
            checks = runChecks()
            running = false
        }
    }
    LaunchedEffect(Unit) {
        cache = withContext(Dispatchers.IO) { dirSize(context.cacheDir) }
        rerun()
    }

    SubPage("诊断", onBack) {
        Card {
            val shown = checks.ifEmpty { listOf("网络连接", "DNS 解析", "代理端口", "规则集加载", "节点连通", "内核状态").map { Check(it, Check.Level.Pending, "检测中…") } }
            shown.forEachIndexed { i, ch ->
                val (bg, fg, icon) = when (ch.level) {
                    Check.Level.Ok -> Triple(colors.goodSoft, colors.good, "check")
                    Check.Level.Warn -> Triple(colors.warnSoft, colors.warn, "alert")
                    Check.Level.Bad -> Triple(colors.badSoft, colors.bad, "x")
                    Check.Level.Info -> Triple(colors.accentSoft, colors.accent, "clock")
                    Check.Level.Pending -> Triple(colors.panel2, colors.fg3, "clock")
                }
                CardRow(ch.name, divider = i > 0, leading = { IconTile(icon, bg, fg, size = 36.dp, round = true) }) {
                    Text(ch.value, color = if (ch.level == Check.Level.Pending) colors.fg3 else fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        PrimaryButton(if (running) "正在诊断…" else "重新诊断", ::rerun, Modifier.padding(vertical = 16.dp), enabled = !running)
        SectionLabel("日志")
        Card {
            MenuRow("日志级别", LOG_LEVELS, (settings["log_level"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "info", { c.setSetting("log_level", it) })
            CardRow("导出诊断报告", divider = true, onClick = { context.startActivity(report(context, checks)) }) { ValueChevron() }
            CardRow("清除缓存", divider = true, onClick = {
                scope.launch(Dispatchers.IO) {
                    context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    cache = dirSize(context.cacheDir)
                    UiMessages.show("已清除缓存")
                }
            }) { ValueChevron(formatBytes(cache)) }
            CardRow("重启核心", divider = true, onClick = { c.restartCore() }) { ValueChevron() }
        }
    }
}
