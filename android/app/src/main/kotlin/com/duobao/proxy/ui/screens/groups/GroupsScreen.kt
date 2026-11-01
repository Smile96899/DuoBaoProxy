package com.duobao.proxy.ui.screens.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duobao.proxy.data.Defaults
import com.duobao.proxy.data.Group
import com.duobao.proxy.state.AppController
import com.duobao.proxy.ui.components.ActionSheet
import com.duobao.proxy.ui.components.Badge
import com.duobao.proxy.ui.components.Card
import com.duobao.proxy.ui.components.CardRow
import com.duobao.proxy.ui.components.ConfirmDialog
import com.duobao.proxy.ui.components.DbpIcon
import com.duobao.proxy.ui.components.Footnote
import com.duobao.proxy.ui.components.IconTile
import com.duobao.proxy.ui.components.OutlinePill
import com.duobao.proxy.ui.components.PromptDialog
import com.duobao.proxy.ui.components.SheetItem
import com.duobao.proxy.ui.components.SubPageHeader
import com.duobao.proxy.ui.components.UiMessages
import com.duobao.proxy.ui.screens.clipboardText
import com.duobao.proxy.ui.theme.DbpTheme
import kotlinx.coroutines.launch

/**
 * 管理分组: every group in store order (subscriptions included) with its node count; tap opens the
 * group menu (重命名, 导入, 上移 / 下移, 合并, 删除). 默认 is never deleted (`App.removeGroups`).
 */
@Composable
fun GroupsScreen(onBack: () -> Unit) {
    val c = AppController.instance
    val colors = DbpTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups by c.store.groups.collectAsStateWithLifecycle()
    val nodes by c.store.nodes.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<Group?>(null) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Group?>(null) }
    var text by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Group?>(null) }
    val names = groups.map { it.name }.toSet()

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        SubPageHeader("管理分组", onBack) {
            OutlinePill("新建", { text = ""; creating = true }, icon = "plus")
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Card {
                groups.forEachIndexed { i, g ->
                    val count = nodes.count { it.group == g.name }
                    CardRow(
                        g.name,
                        subtitle = "$count 个节点" + if (g.subUrl.isNotEmpty()) " · 订阅" else "",
                        divider = i > 0,
                        onClick = { menu = g },
                        leading = { IconTile(if (g.subUrl.isNotEmpty()) "cloud" else "layers", colors.panel2, colors.fg2, size = 40.dp) },
                    ) {
                        if (g.name == Defaults.GROUP) Badge("默认", colors.accentSoft, colors.accent)
                        DbpIcon("more", colors.fg2)
                    }
                }
            }
            Footnote("删除分组会同时删除其中的节点；「默认」分组不能删除。重命名时节点会跟着移动。")
        }
    }

    menu?.let { g ->
        val i = groups.indexOfFirst { it.name == g.name }
        ActionSheet(g.name, listOf(
            SheetItem("重命名", icon = "pencil") { text = g.name; renaming = g },
            SheetItem("从剪贴板导入节点", icon = "clipboard") {
                val t = clipboardText(context)
                if (t.isBlank()) UiMessages.show("剪贴板里没有可导入的内容")
                else scope.launch { UiMessages.show(c.importText(t, g.name).message) }
            },
            SheetItem("上移", icon = "chevronUp", enabled = i > 0) { c.moveGroup(i, i - 1) },
            SheetItem("下移", icon = "chevronDown", enabled = i in 0 until groups.lastIndex) { c.moveGroup(i, i + 1) },
            SheetItem("移到其他分组并删除", icon = "layers", children = groups.map { t ->
                SheetItem(t.name, enabled = t.name != g.name) { c.mergeGroups(listOf(g.name), t.name) }
            }),
            SheetItem("删除分组", icon = "trash", danger = true, enabled = g.name != Defaults.GROUP) { deleting = g },
        )) { menu = null }
    }
    if (creating) {
        val t = text.trim()
        PromptDialog("新建分组", text, { text = it }, "分组名称", "创建", t.isNotEmpty() && t !in names, if (t in names) "已有同名分组" else null,
            onConfirm = { scope.launch { c.createGroup(t) }; creating = false }, onDismiss = { creating = false })
    }
    renaming?.let { g ->
        val t = text.trim()
        val taken = t != g.name && t in names
        PromptDialog("重命名分组", text, { text = it }, "分组名称", "保存", t.isNotEmpty() && !taken, if (taken) "已有同名分组" else null,
            onConfirm = {
                scope.launch { c.saveGroup(g.name, t, g.subUrl, g.subIntervalMin)?.let(UiMessages::show) }
                renaming = null
            }, onDismiss = { renaming = null })
    }
    deleting?.let { g ->
        val n = nodes.count { it.group == g.name }
        ConfirmDialog("删除分组「${g.name}」？", "其中的 $n 个节点会一起删除，无法恢复。", "删除", { c.removeGroups(listOf(g.name)) }, { deleting = null })
    }
}
