package com.duobao.proxy.ui.screens.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import com.duobao.proxy.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.duobao.proxy.data.asString
import com.duobao.proxy.state.AppController
import com.duobao.proxy.state.Prefs
import com.duobao.proxy.state.Rules
import com.duobao.proxy.ui.components.AddRow
import com.duobao.proxy.ui.components.Badge
import com.duobao.proxy.ui.components.Card
import com.duobao.proxy.ui.components.CardDialog
import com.duobao.proxy.ui.components.CardRow
import com.duobao.proxy.ui.components.DbpIcon
import com.duobao.proxy.ui.components.DbpToggle
import com.duobao.proxy.ui.components.FilterChip
import com.duobao.proxy.ui.components.IconTile
import com.duobao.proxy.ui.components.OutlinePill
import com.duobao.proxy.ui.components.PageTitle
import com.duobao.proxy.ui.components.SectionLabel
import com.duobao.proxy.ui.components.TextBox
import com.duobao.proxy.ui.screens.subs.ago
import com.duobao.proxy.ui.theme.DbpTheme
import kotlinx.serialization.json.JsonObject

/** The 代理 / 直连 / 拦截 badge colours. */
@Composable
fun OutboundBadge(out: String) {
    val c = DbpTheme.colors
    when (out) {
        Rules.PROXY -> Badge("代理", c.accentSoft, c.accent)
        Rules.BLOCK -> Badge("拦截", c.badSoft, c.bad)
        else -> Badge("直连", c.panel2, c.fg2)
    }
}

/**
 * 规则: 分流策略 switches (presets over the routing lists), 自定义规则 (the remaining entries),
 * 更新规则集 and 未命中 → 代理 / 直连 (`routing.final`).
 */
@Composable
fun RulesScreen(@Suppress("UNUSED_PARAMETER") nav: NavHostController) {
    val c = AppController.instance
    val colors = DbpTheme.colors
    val settings by c.store.settings.collectAsStateWithLifecycle()
    val ui by c.store.ui.collectAsStateWithLifecycle()
    val prefs = Prefs(ui)
    var editing by remember { mutableStateOf<Rules.Rule?>(null) }
    var adding by remember { mutableStateOf(false) }
    val custom = Rules.custom(settings)
    val final = Rules.final(settings)

    fun setList(key: String, list: List<String>) = c.setSetting("routing.$key", list)

    Column(Modifier.fillMaxSize().background(colors.bg).verticalScroll(rememberScrollState())) {
        PageTitle(
            "规则",
            subtitle = when (prefs.proxyMode) {
                "rule" -> "在“规则”模式下生效"
                "direct" -> "直连模式：命中规则照常生效，其余直连"
                else -> "全局模式：只有拦截和分应用代理生效"
            },
        ) {
            OutlinePill("更新规则集", { c.updateRuleSets() }, icon = "refresh")
        }
        SectionLabel("分流策略")
        Card {
            Rules.presets.forEachIndexed { i, p ->
                val (bg, tint) = when (p.outbound) {
                    Rules.BLOCK -> colors.badSoft to colors.bad
                    Rules.PROXY -> colors.accentSoft to colors.accent
                    else -> colors.panel2 to colors.fg2
                }
                CardRow(p.title, subtitle = Rules.outboundLabel(p.outbound) + " · " + p.detail, divider = i > 0, leading = { IconTile(p.icon, bg, tint) }) {
                    DbpToggle(Rules.enabled(settings, p), { on -> Rules.toggle(settings, p, on).forEach { (k, v) -> setList(k, v) } })
                }
            }
        }
        SectionLabel("自定义规则")
        Card {
            custom.forEachIndexed { i, r ->
                CardRow(r.display, subtitle = r.type, divider = i > 0, mono = true, onClick = { editing = r }) {
                    OutboundBadge(r.outbound)
                    DbpIcon("chevronRight", colors.fg3, size = 18.dp)
                }
            }
            if (custom.isNotEmpty()) HorizontalDivider(thickness = 1.dp, color = colors.line)
            AddRow("添加规则") { adding = true }
        }
        Spacer(Modifier.height(12.dp))
        Card {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                DbpIcon("clock", colors.fg2, size = 18.dp)
                val t = ui["rules_updated"].asString().toLongOrNull()
                Text("  规则集上次更新：" + (t?.let { ago(it) } ?: "未更新"), color = colors.fg2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(
                    "未命中 → " + Rules.outboundLabel(if (final == Rules.PROXY) Rules.PROXY else Rules.DIRECT),
                    color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { c.setSetting("routing.final", if (final == Rules.PROXY) "bypass" else "proxy") },
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (adding || editing != null) {
        RuleDialog(editing, settings, onDismiss = { adding = false; editing = null }, onSave = { old, key, value ->
            if (old != null) setList(old.key, Rules.list(c.store.settings.value, old.key) - old.value)
            val cur = Rules.list(c.store.settings.value, key).let { if (old?.key == key) it - old.value else it }
            if (value !in cur) setList(key, cur + value)
            adding = false
            editing = null
        }, onDelete = { old ->
            setList(old.key, Rules.list(c.store.settings.value, old.key) - old.value)
            editing = null
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleDialog(
    old: Rules.Rule?,
    @Suppress("UNUSED_PARAMETER") settings: JsonObject,
    onDismiss: () -> Unit,
    onSave: (Rules.Rule?, String, String) -> Unit,
    onDelete: (Rules.Rule) -> Unit,
) {
    val colors = DbpTheme.colors
    var type by remember { mutableStateOf(old?.type ?: "DOMAIN-SUFFIX") }
    var value by remember { mutableStateOf(old?.display?.removePrefix("*.") ?: "") }
    var out by remember { mutableStateOf(old?.outbound ?: Rules.PROXY) }
    CardDialog(
        title = if (old == null) "添加规则" else "编辑规则",
        onDismiss = onDismiss,
        confirm = "保存",
        canConfirm = value.isNotBlank(),
        onConfirm = { val (k, v) = Rules.encode(type, value, out); onSave(old, k, v) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("类型", color = colors.fg2, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in Rules.types) FilterChip(t, t == type) { type = t }
            }
            TextBox(value, { value = it }, placeholder = when (type) {
                "IP-CIDR" -> "如 192.168.0.0/16"
                "GEOIP" -> "如 cn、private"
                "GEOSITE" -> "如 netflix、google"
                "DOMAIN-KEYWORD" -> "如 google"
                "DOMAIN-REGEX" -> "正则表达式"
                else -> "如 google.com"
            }, mono = true)
            Text("去向", color = colors.fg2, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (o in listOf(Rules.PROXY, Rules.DIRECT, Rules.BLOCK)) FilterChip(Rules.outboundLabel(o), o == out) { out = o }
            }
            if (old != null) {
                Text("删除这条规则", color = colors.bad, fontSize = 14.sp, modifier = Modifier.clickable { onDelete(old) }.padding(vertical = 6.dp))
            }
        }
    }
}
