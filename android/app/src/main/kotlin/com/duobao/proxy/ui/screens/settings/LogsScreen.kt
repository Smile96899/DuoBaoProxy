package com.duobao.proxy.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import com.duobao.proxy.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duobao.proxy.core.LogRepository
import com.duobao.proxy.state.AppController
import com.duobao.proxy.ui.components.CardShape
import com.duobao.proxy.ui.components.FilterChip
import com.duobao.proxy.ui.components.OutlinePill
import com.duobao.proxy.ui.components.SubPageHeader
import com.duobao.proxy.ui.theme.DbpTheme
import com.duobao.proxy.ui.theme.formatBytes

/** The 连接日志 filters: label → a phrase LogZh writes for that route (`→ 代理`, `拦截`…). */
private val FILTERS = listOf("全部" to null, "代理" to "代理", "直连" to "直连", "拦截" to "拦截", "错误" to "")

/**
 * 连接日志: the translated core log (`LogsPage.qml`), filterable by route, with this session's
 * totals. Lines are selectable; the list follows new output while scrolled to the end.
 */
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val c = AppController.instance
    val colors = DbpTheme.colors
    val context = LocalContext.current
    val lines by LogRepository.lines.collectAsStateWithLifecycle()
    val traffic by c.traffic.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<String?>(null) }
    var errorsOnly by remember { mutableStateOf(false) }
    val shown = remember(lines, filter, errorsOnly) {
        when {
            errorsOnly -> lines.filter { LogRepository.tone(it) != LogRepository.LineTone.Normal }
            filter != null -> lines.filter { it.contains(filter!!) }
            else -> lines
        }
    }
    val list = rememberLazyListState()
    val atEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 2 } ?: true } }
    LaunchedEffect(shown.size) { if (atEnd && shown.isNotEmpty()) list.scrollToItem(shown.lastIndex) }

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        SubPageHeader("连接日志", onBack) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinePill("分享", { context.startActivity(LogRepository.shareIntent(context)) }, icon = "share")
                OutlinePill("清空", { LogRepository.clear() })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, f) in FILTERS) {
                val on = if (f == "") errorsOnly else !errorsOnly && filter == f
                FilterChip(label, on) {
                    errorsOnly = f == ""
                    filter = if (f == "") null else f
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("日志条数", shown.size.toString(), Modifier.weight(1f))
            Stat("本次上传", formatBytes(traffic.upTotal), Modifier.weight(1f))
            Stat("本次下载", formatBytes(traffic.downTotal), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        SelectionContainer(Modifier.weight(1f).padding(horizontal = 20.dp).padding(bottom = 16.dp).fillMaxWidth().clip(CardShape).background(colors.panel).border(1.dp, colors.line, CardShape)) {
            if (shown.isEmpty()) {
                Text("暂无日志", color = colors.fg3, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
            } else {
                LazyColumn(state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown) { line ->
                        Text(
                            line,
                            color = when (LogRepository.tone(line)) {
                                LogRepository.LineTone.Bad -> colors.bad
                                LogRepository.LineTone.Warn -> colors.warn
                                else -> colors.fg
                            },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val colors = DbpTheme.colors
    Column(modifier.clip(CardShape).background(colors.panel).border(1.dp, colors.line, CardShape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = colors.fg2, fontSize = 13.sp)
        Text(value, color = colors.fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
