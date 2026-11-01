package com.duobao.proxy.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duobao.proxy.state.AppController
import com.duobao.proxy.state.Prefs
import com.duobao.proxy.ui.components.Card
import com.duobao.proxy.ui.components.CardDialog
import com.duobao.proxy.ui.components.CardRow
import com.duobao.proxy.ui.components.CardShape
import com.duobao.proxy.ui.components.ConfirmDialog
import com.duobao.proxy.ui.components.DbpIcon
import com.duobao.proxy.ui.components.DbpToggle
import com.duobao.proxy.ui.components.Footnote
import com.duobao.proxy.ui.components.SectionLabel
import com.duobao.proxy.ui.components.UiMessages
import com.duobao.proxy.ui.screens.subs.ago
import com.duobao.proxy.ui.theme.DbpTheme
import com.duobao.proxy.ui.theme.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复: data.json export / import through the system file picker (critic A7), with the
 * 备份内容 switches, and 恢复默认设置.
 */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val c = AppController.instance
    val colors = DbpTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by c.store.ui.collectAsStateWithLifecycle()
    val p = Prefs(ui)
    var imported by remember { mutableStateOf<String?>(null) }
    var resetting by remember { mutableStateOf(false) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = c.store.exportJson(p.backupNodes, p.backupRules, p.backupSettings)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("无法写入文件")
                }
                c.setUi(Prefs.LAST_BACKUP, System.currentTimeMillis().toString())
                c.setUi(Prefs.LAST_BACKUP_SIZE, text.toByteArray().size)
            }.onSuccess { UiMessages.show("已导出配置") }.onFailure { UiMessages.show("导出失败：" + it.message) }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } } ?: error("无法读取文件")
            }.onSuccess { imported = it }.onFailure { UiMessages.show("读取失败：" + it.message) }
        }
    }

    SubPage("备份与恢复", onBack) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("上次备份", color = colors.fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    val last = p.lastBackup.toLongOrNull()
                    Text(
                        if (last == null) "从未备份" else ago(last) + " · " + formatBytes(p.lastBackupSize.toLong()),
                        color = colors.fg2, fontSize = 14.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton("导出配置", "upload", Modifier.weight(1f)) {
                        export.launch("duobao-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".json")
                    }
                    BigButton("导入配置", "download", Modifier.weight(1f)) { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                }
            }
        }
        SectionLabel("备份内容")
        Card {
            CardRow("订阅与节点", onClick = infoClick("订阅与节点")) { DbpToggle(p.backupNodes, { c.setUi(Prefs.BACKUP_NODES, it) }) }
            CardRow("规则", divider = true, onClick = infoClick("规则")) { DbpToggle(p.backupRules, { c.setUi(Prefs.BACKUP_RULES, it) }) }
            CardRow("设置", divider = true, onClick = infoClick("设置")) { DbpToggle(p.backupSettings, { c.setUi(Prefs.BACKUP_SETTINGS, it) }) }
        }
        Footnote("备份文件就是桌面版的 data.json，可以在电脑和手机之间互相导入。")
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(CardShape).background(colors.panel).border(1.dp, colors.line, CardShape)
                .clickable { resetting = true }.padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("恢复默认设置", color = colors.bad, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    imported?.let { text ->
        CardDialog("导入配置", { imported = null }, confirm = "合并", onConfirm = {
            imported = null
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { c.store.importMerge(text) } }
                    .onSuccess { UiMessages.show("已导入 ${it.nodesAdded} 个节点、${it.groupsAdded} 个分组") }
                    .onFailure { UiMessages.show("导入失败：" + it.message) }
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("合并：把文件里的节点和分组追加进来，本机的设置不变。", color = colors.fg2, fontSize = 14.sp)
                Text("覆盖：用文件替换本机的全部数据。", color = colors.fg2, fontSize = 14.sp)
                Text(
                    "覆盖导入", color = colors.bad, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        imported = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { c.store.importReplace(text) } }
                                .onSuccess { UiMessages.show("已覆盖导入") }
                                .onFailure { UiMessages.show("导入失败：" + it.message) }
                        }
                    }.padding(vertical = 4.dp),
                )
            }
        }
    }
    if (resetting) {
        ConfirmDialog("恢复默认设置？", "规则、DNS、端口等设置会恢复为初始值，节点和订阅保留。", "恢复", {
            scope.launch(Dispatchers.IO) { c.store.resetSettings() }
            UiMessages.show("已恢复默认设置")
        }, { resetting = false })
    }
}

@Composable
private fun BigButton(text: String, icon: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = DbpTheme.colors
    Row(
        modifier.height(52.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, colors.line, RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        DbpIcon(icon, colors.accent, size = 20.dp)
        Text("  $text", color = colors.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
