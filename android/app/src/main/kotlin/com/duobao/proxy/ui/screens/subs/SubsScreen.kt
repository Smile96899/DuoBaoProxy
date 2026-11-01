package com.duobao.proxy.ui.screens.subs

import com.duobao.proxy.core.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.duobao.proxy.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.duobao.proxy.data.Group
import com.duobao.proxy.state.AppController
import com.duobao.proxy.state.Prefs
import com.duobao.proxy.ui.SubEditRoute
import com.duobao.proxy.ui.components.ActionSheet
import com.duobao.proxy.ui.components.Badge
import com.duobao.proxy.ui.components.CardShape
import com.duobao.proxy.ui.components.ConfirmDialog
import com.duobao.proxy.ui.components.DbpIcon
import com.duobao.proxy.ui.components.IconTile
import com.duobao.proxy.ui.components.OutlinePill
import com.duobao.proxy.ui.components.PageTitle
import com.duobao.proxy.ui.components.PrimaryButton
import com.duobao.proxy.ui.components.SheetItem
import com.duobao.proxy.ui.components.UiMessages
import com.duobao.proxy.ui.screens.copyText
import com.duobao.proxy.ui.theme.DbpTheme
import com.duobao.proxy.ui.theme.formatBytes
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "刚刚 / 10 分钟前 / 2 小时前 / 3 天前" for an epoch-ms time. */
fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - ms) / 60_000
    return when {
        m < 1 -> "刚刚"
        m < 60 -> "$m 分钟前"
        m < 60 * 24 -> "${m / 60} 小时前"
        else -> "${m / (60 * 24)} 天前"
    }
}

fun updatedMs(g: Group): Long? = g.updated?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** `M月d日` of unix seconds. */
fun monthDay(sec: Long): String = DateTimeFormatter.ofPattern(tr("M月d日"), java.util.Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(sec))

fun isExpired(g: Group) = (g.expire ?: 0) > 0 && g.expire!! * 1000 < System.currentTimeMillis()

@Composable
fun SubsScreen(nav: NavHostController) {
    val c = AppController.instance
    val colors = DbpTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups by c.store.groups.collectAsStateWithLifecycle()
    val nodes by c.store.nodes.collectAsStateWithLifecycle()
    val ui by c.store.ui.collectAsStateWithLifecycle()
    val prefs = Prefs(ui)
    val subs = groups.filter { it.subUrl.isNotEmpty() }
    var updating by remember { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf<Group?>(null) }
    var delete by remember { mutableStateOf<Group?>(null) }

    fun update(names: List<String>) {
        updating = updating + names
        scope.launch {
            for (n in names) {
                val r = c.updateSubscription(n)
                updating = updating - n
                UiMessages.show(r.message)
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().background(colors.bg)) {
        item {
            PageTitle("订阅") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinePill("添加", { nav.navigate(SubEditRoute()) }, icon = "plus")
                    OutlinePill("更新全部", { update(subs.map { it.name }) }, icon = "refresh", enabled = subs.isNotEmpty() && updating.isEmpty())
                }
            }
        }
        if (subs.isNotEmpty()) {
            item {
                val last = subs.mapNotNull(::updatedMs).maxOrNull()
                Row(
                    Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.accentSoft).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DbpIcon("clock", colors.accent, size = 18.dp)
                    Text(
                        "共 ${subs.size} 个订阅 · " + if (last != null) "上次更新 ${ago(last)}" else "尚未更新",
                        color = colors.accent, fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        } else {
            item {
                Column(
                    Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(CardShape).background(colors.panel).padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    IconTile("cloud", colors.accentSoft, colors.accent, size = 56.dp)
                    Text("还没有订阅", color = colors.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("添加机场提供的订阅链接，节点会自动导入并定期更新。", color = colors.fg2, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp))
                    PrimaryButton("添加订阅", { nav.navigate(SubEditRoute()) })
                }
            }
        }
        items(subs, key = { it.name }) { g ->
            SubCard(
                g = g,
                nodeCount = nodes.count { it.group == g.name },
                isDefault = prefs.homeGroup == g.name,
                auto = prefs.subAuto,
                updating = g.name in updating,
                onClick = { nav.navigate(SubEditRoute(name = g.name)) },
                onMore = { menu = g },
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    menu?.let { g ->
        ActionSheet(g.name, listOf(
            SheetItem("立即更新", icon = "refresh", enabled = g.name !in updating) { update(listOf(g.name)) },
            SheetItem("编辑", icon = "pencil") { nav.navigate(SubEditRoute(name = g.name)) },
            SheetItem(if (prefs.homeGroup == g.name) "取消默认分组" else "设为默认分组", icon = "layers") {
                c.setUi(Prefs.HOME_GROUP, if (prefs.homeGroup == g.name) "" else g.name)
            },
            SheetItem("复制订阅链接", icon = "link") { copyText(context, g.subUrl); UiMessages.show("已复制订阅链接") },
            SheetItem("删除订阅", icon = "trash", danger = true) { delete = g },
        )) { menu = null }
    }
    delete?.let { g ->
        val n = nodes.count { it.group == g.name }
        ConfirmDialog("删除订阅「${g.name}」？", "其中的 $n 个节点会一起删除，无法恢复。", "删除", { c.removeGroups(listOf(g.name)) }, { delete = null })
    }
}

@Composable
private fun SubCard(g: Group, nodeCount: Int, isDefault: Boolean, auto: Boolean, updating: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
    val colors = DbpTheme.colors
    val expired = isExpired(g)
    val total = g.total ?: 0
    val used = (g.upload ?: 0) + (g.download ?: 0)
    val left = (total - used).coerceAtLeast(0)
    val info = when {
        expired -> "已于 ${monthDay(g.expire!!)} 过期"
        else -> listOfNotNull(
            if (total > 0) "剩余 ${formatBytes(left)} / ${formatBytes(total)}" else null,
            g.expire?.takeIf { it > 0 }?.let { monthDay(it) + "到期" },
        ).joinToString(" · ").ifEmpty { "无流量信息" }
    }
    val border = if (isDefault && !expired) Modifier.border(2.dp, colors.accent, CardShape) else Modifier.border(1.dp, colors.line, CardShape)
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.panel)
            .then(border)
            .clickable(onClick = onClick)
            .padding(16.dp)
            .alpha(if (expired) 0.75f else 1f),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile("cloud", if (isDefault && !expired) colors.accentSoft else colors.panel2, if (isDefault && !expired) colors.accent else colors.fg2, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(g.name, color = colors.fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(info, color = if (expired) colors.bad else colors.fg2, fontSize = 13.sp, maxLines = 2)
            }
            when {
                expired -> Badge("已过期", colors.badSoft, colors.bad)
                isDefault -> Badge("默认", colors.accentSoft, colors.accent)
                auto && (g.subIntervalMin > 0 || g.json["sub_interval_min"] == null) -> Badge("自动更新", colors.panel2, colors.fg2)
            }
            Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onMore), contentAlignment = Alignment.Center) {
                DbpIcon("more", colors.fg2, size = 22.dp)
            }
        }
        val frac = if (total > 0 && !expired) left.toFloat() / total else 0f
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.panel2)) {
            if (frac > 0f) Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (isDefault) colors.accent else colors.accent.copy(alpha = 0.55f)))
        }
        Row {
            Text("$nodeCount 个节点", color = colors.fg2, fontSize = 13.sp, modifier = Modifier.weight(1f))
            val right = when {
                updating -> "正在更新…"
                expired -> "已停止更新"
                else -> updatedMs(g)?.let { ago(it) + "更新" } ?: "未更新"
            }
            Text(right, color = colors.fg2, fontSize = 13.sp)
        }
    }
}
