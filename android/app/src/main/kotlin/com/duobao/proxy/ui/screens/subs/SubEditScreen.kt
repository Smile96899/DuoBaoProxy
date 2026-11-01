package com.duobao.proxy.ui.screens.subs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.duobao.proxy.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duobao.proxy.state.AppController
import com.duobao.proxy.state.Prefs
import com.duobao.proxy.state.Subscriptions
import com.duobao.proxy.ui.SubEditRoute
import com.duobao.proxy.ui.components.Card
import com.duobao.proxy.ui.components.CardRow
import com.duobao.proxy.ui.components.DbpIcon
import com.duobao.proxy.ui.components.DbpToggle
import com.duobao.proxy.ui.components.FieldLabel
import com.duobao.proxy.ui.components.Footnote
import com.duobao.proxy.ui.components.IconTile
import com.duobao.proxy.ui.components.MenuRow
import com.duobao.proxy.ui.components.OutlinePill
import com.duobao.proxy.ui.components.PrimaryButton
import com.duobao.proxy.ui.components.SegTabs
import com.duobao.proxy.ui.components.SubPageHeader
import com.duobao.proxy.ui.components.TextBox
import com.duobao.proxy.ui.components.UiMessages
import com.duobao.proxy.ui.screens.clipboardText
import com.duobao.proxy.ui.screens.rememberQrScanner
import com.duobao.proxy.ui.theme.DbpTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 更新间隔 choices for one subscription, in minutes (group `sub_interval_min`). */
val SUB_INTERVALS = listOf(360 to "6 小时", 720 to "12 小时", 1440 to "24 小时")

/**
 * 添加订阅 / 编辑订阅: a link (订阅链接), a scanned code (扫码) or pasted node links (手动配置, a
 * manual group). Options: 自动更新 + 更新间隔 (`sub_interval_min`, 0 = off), 设为默认分组
 * (`ui.home_group`), 到期提醒 (group `notify_expire`).
 */
@Composable
fun SubEditScreen(route: SubEditRoute, onBack: () -> Unit) {
    val c = AppController.instance
    val colors = DbpTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(route) { c.store.groups.value.firstOrNull { it.name == route.name && route.name.isNotEmpty() } }
    var tab by remember { mutableStateOf(if (existing != null && existing.subUrl.isEmpty()) 2 else 0) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.subUrl ?: route.url) }
    var links by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(existing?.let { it.subIntervalMin > 0 } ?: true) }
    var interval by remember { mutableStateOf(existing?.subIntervalMin?.takeIf { it > 0 } ?: 1440) }
    var isDefault by remember { mutableStateOf(existing != null && Prefs(c.store.ui.value).homeGroup == existing.name) }
    var remind by remember { mutableStateOf((existing?.json?.get("notify_expire") as? JsonPrimitive)?.booleanOrNull ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scanner = rememberQrScanner { text ->
        val t = text.trim()
        if (Subscriptions.isSubscriptionUrl(t)) {
            url = t
            tab = 0
        } else {
            links = t
            tab = 2
        }
    }

    fun save() {
        error = null
        val manual = tab == 2
        val u = url.trim()
        if (!manual && !Subscriptions.isSubscriptionUrl(u)) {
            error = "请填写 http:// 或 https:// 开头的订阅链接"
            return
        }
        if (manual && existing == null && links.isBlank()) {
            error = "请粘贴节点链接"
            return
        }
        val n = name.trim().ifEmpty {
            if (manual) "自建节点" else Subscriptions.groupNameFor(u, c.store.groups.value.map { it.name }.toSet())
        }
        saving = true
        scope.launch {
            val err = c.saveGroup(existing?.name, n, if (manual) "" else u, if (!manual && auto) interval else 0)
            if (err != null) {
                error = err
                saving = false
                return@launch
            }
            c.setGroupField(n, "notify_expire", remind)
            val home = Prefs(c.store.ui.value).homeGroup
            if (isDefault) c.setUi(Prefs.HOME_GROUP, n) else if (home == n || home == existing?.name) c.setUi(Prefs.HOME_GROUP, "")
            val r = when {
                manual && links.isNotBlank() -> c.importText(links, n)
                !manual && (existing == null || existing.subUrl != u) -> c.updateSubscription(n)
                else -> null
            }
            r?.let { UiMessages.show(it.message) } ?: UiMessages.show("已保存")
            onBack()
        }
    }

    Column(Modifier.fillMaxSize().background(colors.bg).imePadding()) {
        SubPageHeader(if (existing == null) "添加订阅" else "编辑订阅", onBack)
        SegTabs(listOf(0 to "订阅链接", 1 to "扫码", 2 to "手动配置"), tab, { tab = it })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FieldLabel(if (tab == 2) "分组名称" else "订阅名称")
            TextBox(name, { name = it }, Modifier.padding(horizontal = 20.dp), placeholder = if (tab == 2) "自建节点" else "留空则使用链接的域名")
            when (tab) {
                0 -> {
                    FieldLabel("订阅链接")
                    TextBox(url, { url = it.trim() }, Modifier.padding(horizontal = 20.dp), placeholder = "粘贴 https:// 开头的订阅地址", singleLine = false, minHeight = 110.dp, trailing = {
                        PasteChip { url = clipboardText(context).trim() }
                    })
                }
                1 -> {
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.panel).padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        IconTile("scan", colors.accentSoft, colors.accent, size = 64.dp, round = true)
                        Text("扫描订阅或节点二维码", color = colors.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinePill("打开相机", scanner.camera, icon = "scan", accent = true)
                            OutlinePill("从相册选择", scanner.gallery, icon = "image")
                        }
                        if (url.isNotEmpty()) Text(url, color = colors.fg2, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }
                else -> {
                    FieldLabel("节点链接")
                    TextBox(links, { links = it }, Modifier.padding(horizontal = 20.dp), placeholder = "vmess:// vless:// ss:// trojan:// hy2:// …\n一行一个，也支持 Base64 和 Clash 配置", singleLine = false, mono = true, minHeight = 160.dp, trailing = {
                        PasteChip { links = clipboardText(context) }
                    })
                }
            }
            Spacer(Modifier.height(16.dp))
            Card {
                if (tab != 2) {
                    CardRow("自动更新", subtitle = "按设定间隔自动拉取节点") { DbpToggle(auto, { auto = it }) }
                    if (auto) MenuRow("更新间隔", SUB_INTERVALS, interval, { interval = it }, divider = true)
                }
                CardRow("设为默认分组", subtitle = "首页优先显示此分组的节点", divider = tab != 2) { DbpToggle(isDefault, { isDefault = it }) }
                if (tab != 2) CardRow("到期提醒", divider = true) { DbpToggle(remind, { remind = it }) }
            }
            Footnote(
                if (tab == 2) "手动配置会建立一个普通分组，节点不会自动更新。"
                else "支持 Clash、V2Ray、Shadowsocks 等常见订阅格式，添加后会自动识别。",
            )
            error?.let { Text(it, color = colors.bad, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp)) }
        }
        PrimaryButton(
            when {
                saving -> "正在保存…"
                existing != null -> "保存"
                tab == 2 -> "添加节点"
                else -> "添加订阅"
            },
            ::save,
            Modifier.padding(vertical = 12.dp),
            enabled = !saving,
        )
    }
}

/** The small accent 粘贴 chip inside a text area. */
@Composable
fun PasteChip(onClick: () -> Unit) {
    val colors = DbpTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(colors.accentSoft).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DbpIcon("clipboard", colors.accent, size = 15.dp)
        Text("粘贴", color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
