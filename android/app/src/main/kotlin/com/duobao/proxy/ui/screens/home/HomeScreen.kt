package com.duobao.proxy.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import com.duobao.proxy.core.tr
import com.duobao.proxy.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.duobao.proxy.R
import com.duobao.proxy.data.NodeEntry
import com.duobao.proxy.data.asString
import com.duobao.proxy.state.AppController
import com.duobao.proxy.state.NodeList
import com.duobao.proxy.state.NodeSpecs
import com.duobao.proxy.state.Prefs
import com.duobao.proxy.tests.TestFormat
import com.duobao.proxy.ui.AppViewModel
import com.duobao.proxy.ui.GroupsRoute
import com.duobao.proxy.ui.NodeEditRoute
import com.duobao.proxy.ui.SubEditRoute
import com.duobao.proxy.ui.components.ActionSheet
import com.duobao.proxy.ui.components.CardShape
import com.duobao.proxy.ui.components.ConfirmDialog
import com.duobao.proxy.ui.components.DbpIcon
import com.duobao.proxy.ui.components.DbpToggle
import com.duobao.proxy.ui.components.IconTile
import com.duobao.proxy.ui.components.OutlinePill
import com.duobao.proxy.ui.components.PillDropdown
import com.duobao.proxy.ui.components.RoundIconButton
import com.duobao.proxy.ui.components.SheetItem
import com.duobao.proxy.ui.components.UiMessages
import com.duobao.proxy.ui.screens.clipboardText
import com.duobao.proxy.ui.screens.rememberQrScanner
import com.duobao.proxy.ui.theme.DbpTheme
import com.duobao.proxy.ui.theme.formatBytes
import com.duobao.proxy.ui.theme.toneColor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** `ui.proxy_mode` values in the header pill. */
val PROXY_MODES = listOf("rule" to "规则", "global" to "全局", "direct" to "直连")

/** Display name of a protocol id (VLESS, Shadowsocks…). */
fun protocolLabel(p: String): String = NodeSpecs.protocols.firstOrNull { it.first == p }?.second ?: p.replaceFirstChar { it.uppercase() }

/** The country of a node: its geo test result, else a guess from its name. */
fun nodeCountry(e: NodeEntry): String? {
    val geo = TestFormat.result(e, "geo")
    val cc = (geo?.get("cc") as? JsonPrimitive)?.content
    if (!cc.isNullOrEmpty() && geo["err"] == null) return cc.uppercase()
    return Countries.guess(e.title)
}

@Composable
fun HomeScreen(nav: NavHostController, vm: AppViewModel) {
    val c = AppController.instance
    val colors = DbpTheme.colors
    val context = LocalContext.current
    val nodes by c.store.nodes.collectAsStateWithLifecycle()
    val groups by c.store.groups.collectAsStateWithLifecycle()
    val ui by c.store.ui.collectAsStateWithLifecycle()
    val selected by c.selectedId.collectAsStateWithLifecycle()
    val connected by c.connected.collectAsStateWithLifecycle()
    val busy by c.busy.collectAsStateWithLifecycle()
    val status by c.statusText.collectAsStateWithLifecycle()
    val latency by c.latency.collectAsStateWithLifecycle()
    val online by c.online.collectAsStateWithLifecycle()
    val local by c.localInfo.collectAsStateWithLifecycle()
    val session by c.session.collectAsStateWithLifecycle()
    val traffic by c.traffic.collectAsStateWithLifecycle()
    val running by c.tests.running.collectAsStateWithLifecycle()
    val core by c.coreId.collectAsStateWithLifecycle()
    val prefs = Prefs(ui)

    var addOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<NodeEntry?>(null) }
    var deleteFor by remember { mutableStateOf<NodeEntry?>(null) }
    var groupMenu by remember { mutableStateOf<String?>(null) }
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    val scanner = rememberQrScanner { vm.offerImport(it, "二维码") }

    val sections = remember(nodes, groups, ui) {
        val all = NodeList.sections(nodes, groups, "", prefs.sort, emptyList())
        val home = prefs.homeGroup
        all.sortedBy { if (it.group == home) 0 else 1 }
    }
    val sessionNode = session?.let { s -> nodes.firstOrNull { it.id == s.nodeId } }
    val exit = sessionNode?.let { TestFormat.result(it, "geo") }
    val alarm = connected && !online

    LazyColumn(Modifier.fillMaxSize().background(colors.bg)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(10.dp))
                Text("DuoBao Proxy", color = colors.fg, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                PillDropdown(PROXY_MODES, prefs.proxyMode, { c.setUi("proxy_mode", it) }, dot = colors.accent)
                Spacer(Modifier.width(10.dp))
                RoundIconButton("plus") { addOpen = true }
            }
        }
        item {
            val (text, tone) = when {
                !online -> "无网络" to colors.bad
                connected && exit != null && exit["err"] == null ->
                    exit["ip"].asString() to colors.fg
                connected -> "正在获取出口 IP…" to colors.fg2
                local != null -> local!!.ip to colors.fg
                else -> "正在获取 IP…" to colors.fg2
            }
            val exitCc = if (connected) exit?.get("cc").asString().uppercase().ifEmpty { sessionNode?.let(::nodeCountry) } else null
            Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(200.dp).clip(RoundedCornerShape(24.dp)).background(colors.mapBg)) {
                WorldMap(
                    from = local?.cc ?: "CN",
                    to = exitCc,
                    fromName = tr(countryName(local?.cc ?: "CN")),
                    toName = exitCc?.let { tr(countryName(it)) },
                    land = colors.mapDot,
                    gap = colors.mapBg,
                    highlight = if (!online) colors.alarm else colors.accent,
                    labelBg = colors.panel,
                    labelFg = colors.fg,
                )
                Row(
                    Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(50)).background(colors.panel).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (!online) colors.alarm else colors.accent))
                    Text(text, color = tone, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(CardShape).background(colors.panel)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconTile("power", colors.accentSoft, colors.accent, size = 48.dp, round = true)
                    Column(Modifier.weight(1f)) {
                        Text("代理服务", color = colors.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        val sub = when {
                            alarm -> "无网络，等待网络恢复"
                            busy || (status != AppController.STATUS_IDLE && status != AppController.STATUS_CONNECTED) -> status
                            connected -> sessionNode?.title.orEmpty()
                            else -> ""
                        }
                        if (sub.isNotEmpty()) Text(sub, color = if (alarm) colors.bad else colors.fg2, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    DbpToggle(connected || busy, { c.toggle() }, enabled = !busy, onColor = if (alarm) colors.alarm else null)
                }
                HorizontalDivider(thickness = 1.dp, color = colors.line)
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconTile("wifi", colors.accentSoft, colors.accent, size = 48.dp, round = true)
                    Column(Modifier.weight(1f)) {
                        Text("延迟测试", color = colors.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        val sub = when {
                            running.any { it.endsWith(":url") } -> "正在测试 ${running.count { it.endsWith(":url") }} 个节点…"
                            connected && latency > 0 -> "当前节点 $latency ms"
                            connected && latency < 0 -> "当前节点超时"
                            else -> ""
                        }
                        if (sub.isNotEmpty()) Text(sub, color = colors.fg2, fontSize = 13.sp)
                    }
                    OutlinePill("测试", {
                        val ids = nodes.filter { c.supports(it.node) }.map { it.id }
                        if (ids.isEmpty()) UiMessages.show("没有可测试的节点") else c.tests.run(ids, listOf("url"))
                    }, icon = "zap", accent = true)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("分组", color = colors.fg, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Row(Modifier.clip(RoundedCornerShape(50)).clickable { nav.navigate(GroupsRoute) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("管理分组", color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    DbpIcon("chevronRight", colors.accent, size = 18.dp)
                }
            }
        }
        for (s in sections) {
            val open = s.group !in collapsed
            item(key = "g:" + s.group) {
                GroupHeader(s.group, s.nodes.size, open, last = !open || s.nodes.isEmpty(),
                    onToggle = { collapsed = if (open) collapsed + s.group else collapsed - s.group },
                    onLong = { groupMenu = s.group })
            }
            if (open) {
                items(s.nodes, key = { "n:" + it.id }) { e ->
                    val supported = c.supports(e.node)
                    val cell = TestFormat.cell(e, "url")
                    NodeRow(
                        e = e,
                        selected = e.id == selected,
                        alarm = alarm,
                        supported = supported,
                        core = core,
                        latencyText = if ("${e.id}:url" in running) "测试中…" else cell?.text.orEmpty(),
                        latencyColor = if ("${e.id}:url" in running) colors.fg3 else cell?.tone?.let { colors.toneColor(it) } ?: colors.fg3,
                        speed = if (connected && session?.nodeId == e.id) "↑ ${formatBytes(traffic.upRate)}/s  ↓ ${formatBytes(traffic.downRate)}/s" else null,
                        last = e.id == s.nodes.last().id,
                        onClick = { if (supported) c.selectNode(e.id) else UiMessages.show("$core 不支持 ${e.protocol}") },
                        onLong = { menuFor = e },
                    )
                }
            }
            item(key = "s:" + s.group) { Spacer(Modifier.height(12.dp)) }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (addOpen) {
        ActionSheet("添加", listOf(
            SheetItem("扫一扫", icon = "scan") { scanner.camera() },
            SheetItem("从相册识别二维码", icon = "image") { scanner.gallery() },
            SheetItem("从剪贴板导入", icon = "clipboard") {
                val t = clipboardText(context)
                if (t.isBlank()) UiMessages.show("剪贴板里没有可导入的内容") else vm.importNow(t)
            },
            SheetItem("添加订阅", icon = "cloud") { nav.navigate(SubEditRoute()) },
            SheetItem("手动添加", icon = "pencil", children = NodeSpecs.protocols.map { (p, label) ->
                SheetItem(label) { nav.navigate(NodeEditRoute(protocol = p)) }
            }),
        )) { addOpen = false }
    }
    menuFor?.let { e -> NodeMenu(e, nav, vm, onDelete = { deleteFor = e }) { menuFor = null } }
    deleteFor?.let { e ->
        ConfirmDialog("删除节点「${e.title}」？", "删除后无法恢复。", "删除", { c.removeNodes(listOf(e.id)) }, { deleteFor = null })
    }
    groupMenu?.let { g ->
        val ids = nodes.filter { it.group == g && c.supports(it.node) }.map { it.id }
        ActionSheet("分组「$g」", listOf(
            SheetItem("测试本组延迟", icon = "zap", enabled = ids.isNotEmpty()) { c.tests.run(ids, listOf("url")) },
            SheetItem("从剪贴板导入到本组", icon = "clipboard") {
                val t = clipboardText(context)
                if (t.isBlank()) UiMessages.show("剪贴板里没有可导入的内容") else vm.importNow(t, g)
            },
            SheetItem(if (prefs.homeGroup == g) "取消首页置顶" else "在首页置顶", icon = "layers") { c.setUi(Prefs.HOME_GROUP, if (prefs.homeGroup == g) "" else g) },
            SheetItem("管理分组", icon = "settings") { nav.navigate(GroupsRoute) },
        )) { groupMenu = null }
    }
}

/** Names beyond the desktop table (`TestFormat.countryNames`, kept identical to Tests.qml). */
private val extraCountryNames = mapOf(
    "KH" to "柬埔寨", "LA" to "老挝", "MM" to "缅甸", "BD" to "孟加拉", "NP" to "尼泊尔", "LK" to "斯里兰卡", "MN" to "蒙古", "QA" to "卡塔尔",
    "BG" to "保加利亚", "PE" to "秘鲁", "PK" to "巴基斯坦", "IR" to "伊朗", "NG" to "尼日利亚",
)

fun countryName(cc: String): String = if (cc.isEmpty()) "未知地区" else extraCountryNames[cc] ?: TestFormat.country(cc)

private val TopShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private val BottomShape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupHeader(name: String, count: Int, open: Boolean, last: Boolean, onToggle: () -> Unit, onLong: () -> Unit) {
    val colors = DbpTheme.colors
    val shape: Shape = if (last) CardShape else TopShape
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panel)
            .combinedClickable(onClick = onToggle, onLongClick = onLong)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = colors.fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Text("$count", color = colors.fg3, fontSize = 13.sp)
        }
        DbpIcon(if (open) "chevronUp" else "chevronDown", colors.fg2, size = 20.dp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeRow(
    e: NodeEntry,
    selected: Boolean,
    alarm: Boolean,
    supported: Boolean,
    core: String,
    latencyText: String,
    latencyColor: androidx.compose.ui.graphics.Color,
    speed: String?,
    last: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit,
) {
    val colors = DbpTheme.colors
    val shape: Shape = if (last) BottomShape else RectangleShape
    val bg = when {
        selected && alarm -> colors.badSoft
        selected -> colors.selected
        else -> colors.panel
    }
    Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(shape).background(bg)) {
        HorizontalDivider(thickness = 1.dp, color = colors.line)
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLong)
                .heightIn(min = 60.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .alpha(if (supported) 1f else 0.45f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Flag(nodeCountry(e))
            Column(Modifier.weight(1f)) {
                Text(e.title, color = colors.fg, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (!supported) "$core 不支持" else if (speed != null) protocolLabel(e.protocol) + "  " + speed else protocolLabel(e.protocol),
                    color = if (supported) colors.fg2 else colors.bad,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
            if (latencyText.isNotEmpty()) Text(latencyText, color = latencyColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Box(Modifier.width(10.dp), contentAlignment = Alignment.Center) {
                if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(if (alarm) colors.alarm else colors.accent))
            }
        }
    }
}

/** A flag tile: the emoji flag of [cc], or a neutral globe. */
@Composable
fun Flag(cc: String?) {
    val colors = DbpTheme.colors
    val emoji = Countries.flag(cc)
    Box(Modifier.size(36.dp, 26.dp).clip(RoundedCornerShape(5.dp)).background(colors.panel2), contentAlignment = Alignment.Center) {
        if (emoji.isNotEmpty()) Text(emoji, fontSize = 20.sp) else DbpIcon("globe", colors.fg3, size = 16.dp)
    }
}

/** Long-press menu of a node: the desktop node context menu as a sheet. */
@Composable
private fun NodeMenu(e: NodeEntry, nav: NavHostController, vm: AppViewModel, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val c = AppController.instance
    val context = LocalContext.current
    val groups by c.store.groups.collectAsStateWithLifecycle()
    val connected by c.connected.collectAsStateWithLifecycle()
    val session by c.session.collectAsStateWithLifecycle()
    val supported = c.supports(e.node)
    val testItems = com.duobao.proxy.tests.TestKinds.kinds.flatMapIndexed { i, k ->
        val item = SheetItem(k.label, detail = k.description) { c.tests.run(listOf(e.id), listOf(k.k)) }
        if (i > 0 && com.duobao.proxy.tests.TestKinds.kinds[i - 1].section != k.section) listOf(SheetItem.Divider, item) else listOf(item)
    } + listOf(SheetItem.Divider, SheetItem("全部测试") { c.tests.runAll(listOf(e.id)) })
    ActionSheet(e.title, listOf(
        SheetItem(
            if (connected && session?.nodeId == e.id) "已连接" else "选择并连接",
            icon = "power",
            enabled = supported && !(connected && session?.nodeId == e.id),
        ) { c.connectNode(e.id) },
        SheetItem("编辑", icon = "pencil") { nav.navigate(NodeEditRoute(id = e.id)) },
        SheetItem("测试", icon = "zap", enabled = supported, children = testItems),
        SheetItem("分享", icon = "share", children = listOf(
            SheetItem("复制链接") { shareAction(c, e.id) { links -> com.duobao.proxy.ui.screens.copyText(context, links.joinToString("\n") { it.second }); UiMessages.show("已复制分享链接") } },
            SheetItem("显示二维码") { shareAction(c, e.id) { vm.showQr(it) } },
            SheetItem("分享到其他应用") { shareAction(c, e.id) { links -> com.duobao.proxy.ui.screens.shareText(context, links.joinToString("\n") { it.second }) } },
        )),
        SheetItem("移动到分组", icon = "layers", children = groups.map { g ->
            SheetItem(g.name, enabled = g.name != e.group, checked = g.name == e.group) { c.moveNodes(listOf(e.id), g.name) }
        }),
        SheetItem("删除", icon = "trash", danger = true) { onDelete() },
    ), onDismiss = onDismiss)
}

/** Share links are built in Go; the sheet is gone by then, so this runs on a process-wide scope. */
private fun shareAction(c: AppController, id: Int, then: (List<Pair<String, String>>) -> Unit) {
    kotlinx.coroutines.MainScope().launch {
        val links = c.shareLinks(listOf(id))
        if (links.isEmpty()) UiMessages.show("这个协议没有通用的分享链接") else then(links)
    }
}
