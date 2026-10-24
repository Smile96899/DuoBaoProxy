package com.duobao.proxy.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duobao.proxy.state.AppController
import com.duobao.proxy.tests.TestFormat
import com.duobao.proxy.ui.theme.DbpTheme
import com.duobao.proxy.ui.theme.toneColor
import com.duobao.proxy.vpn.VpnNotification
import kotlinx.coroutines.launch

/**
 * TEMPORARY 节点 screen for driving the core by hand: status, connect button, import from the
 * clipboard, test all, and the node list (tap = select, long text = latest url result). The real
 * NodesPage replaces it.
 */
@Composable
fun NodesDebugScreen() {
    val c = AppController.instance
    val colors = DbpTheme.colors
    val nodes by c.store.nodes.collectAsStateWithLifecycle()
    val selected by c.selectedId.collectAsStateWithLifecycle()
    val connected by c.connected.collectAsStateWithLifecycle()
    val busy by c.busy.collectAsStateWithLifecycle()
    val status by c.statusText.collectAsStateWithLifecycle()
    val traffic by c.traffic.collectAsStateWithLifecycle()
    val latency by c.latency.collectAsStateWithLifecycle()
    val core by c.coreId.collectAsStateWithLifecycle()
    val running by c.tests.running.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(colors.bg).padding(12.dp)) {
        Text("$status · $core", color = colors.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (connected) {
            Text(
                "↑ ${VpnNotification.rate(traffic.upRate)}  ↓ ${VpnNotification.rate(traffic.downRate)}  延迟 ${if (latency > 0) "$latency ms" else if (latency < 0) "超时" else "—"}",
                color = colors.fg2, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { c.toggle() }, enabled = !busy) { Text(if (connected) "断开" else "连接") }
            OutlinedButton(onClick = {
                val text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                scope.launch { c.importText(text) }
            }) { Text("从剪贴板导入") }
            OutlinedButton(onClick = { c.tests.run(nodes.map { it.id }, listOf("url")) }) { Text("测试") }
        }
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(nodes, key = { it.id }) { e ->
                val ok = c.supports(e.node)
                val cell = TestFormat.cell(e, "url")
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (e.id == selected) colors.panel2 else colors.bg, RoundedCornerShape(8.dp))
                        .clickable(enabled = ok) { c.selectNode(e.id) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title, color = if (ok) colors.fg else colors.fg3, fontSize = 13.sp)
                        Text("${e.protocol} · ${e.group}", color = colors.fg3, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if ("${e.id}:url" in running) "测试中…" else cell?.text ?: "",
                        color = cell?.tone?.let { colors.toneColor(it) } ?: colors.fg3,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
