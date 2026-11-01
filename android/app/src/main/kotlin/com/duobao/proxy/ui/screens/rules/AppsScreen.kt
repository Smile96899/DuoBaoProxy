package com.duobao.proxy.ui.screens.rules

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import com.duobao.proxy.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duobao.proxy.data.asString
import com.duobao.proxy.state.AppController
import com.duobao.proxy.ui.components.CardRow
import com.duobao.proxy.ui.components.CardShape
import com.duobao.proxy.ui.components.DbpToggle
import com.duobao.proxy.ui.components.LetterTile
import com.duobao.proxy.ui.components.PrimaryButton
import com.duobao.proxy.ui.components.SegTabs
import com.duobao.proxy.ui.components.SubPageHeader
import com.duobao.proxy.ui.components.TextBox
import com.duobao.proxy.ui.components.UiMessages
import com.duobao.proxy.ui.theme.DbpTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One installed app of the picker. */
class InstalledApp(val label: String, val pkg: String, val system: Boolean, val icon: ImageBitmap?)

/** Installed apps with a launcher entry (plus the selected ones), sorted by label; our own app left out. */
private fun loadApps(context: Context, keep: Set<String>): List<InstalledApp> {
    val pm = context.packageManager
    val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.toSet()
    return pm.getInstalledApplications(0)
        .filter { it.packageName != context.packageName && (it.packageName in launchable || it.packageName in keep) }
        .map { ai ->
            val icon = runCatching {
                val d = ai.loadIcon(pm)
                val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 96, 96)
                d.draw(Canvas(bmp))
                bmp.asImageBitmap()
            }.getOrNull()
            InstalledApp(ai.loadLabel(pm).toString(), ai.packageName, ai.flags and ApplicationInfo.FLAG_SYSTEM != 0, icon)
        }
        .sortedWith(compareBy({ it.pkg !in keep }, { it.label.lowercase() }))
}

private val avatarColors = listOf(0xFF4285F4, 0xFF2AABEE, 0xFFE53935, 0xFF1DB954, 0xFF07C160, 0xFFFF5000, 0xFF1677FF, 0xFF8E44AD).map { Color(it) }

/**
 * 分应用代理: 仅所选走代理 (`tun.rule_white` true, include_package) or 所选不走代理 (exclude_package),
 * over `tun.rule_process`. Edits stay local until 保存并应用 (a change while connected reconnects).
 */
@Composable
fun AppsScreen(onBack: () -> Unit) {
    val c = AppController.instance
    val colors = DbpTheme.colors
    val context = LocalContext.current
    val tun = remember { c.store.settings.value["tun"] as? JsonObject }
    var white by remember { mutableStateOf((tun?.get("rule_white") as? JsonPrimitive)?.booleanOrNull ?: true) }
    var picked by remember { mutableStateOf((tun?.get("rule_process") as? JsonArray)?.map { it.asString() }?.toSet() ?: emptySet()) }
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps(context, picked) } }
    val q = query.trim().lowercase()
    val shown = apps.orEmpty().filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        SubPageHeader("分应用代理", onBack)
        SegTabs(listOf(true to "仅所选走代理", false to "所选不走代理"), white, { white = it })
        Spacer(Modifier.height(12.dp))
        TextBox(query, { query = it }, Modifier.padding(horizontal = 20.dp), placeholder = "搜索应用", leadingIcon = "search")
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("已安装应用 · 已选 ${picked.size} 个", color = colors.fg2, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            val allOn = shown.isNotEmpty() && shown.all { it.pkg in picked }
            Text(
                if (allOn) "全不选" else "全选", color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { picked = if (allOn) picked - shown.map { it.pkg }.toSet() else picked + shown.map { it.pkg } },
            )
        }
        Box(Modifier.weight(1f).padding(horizontal = 20.dp).fillMaxWidth().clip(CardShape).background(colors.panel)) {
            if (apps == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = colors.accent)
            } else {
                LazyColumn {
                    itemsIndexed(shown, key = { _, a -> a.pkg }) { i, a ->
                        CardRow(a.label, subtitle = a.pkg, divider = i > 0, onClick = { picked = if (a.pkg in picked) picked - a.pkg else picked + a.pkg }, leading = {
                            if (a.icon != null) Image(a.icon, null, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
                            else LetterTile(a.label.take(1).uppercase(), avatarColors[(a.pkg.hashCode() and 0x7fffffff) % avatarColors.size])
                        }) {
                            DbpToggle(a.pkg in picked, { on -> picked = if (on) picked + a.pkg else picked - a.pkg })
                        }
                    }
                }
            }
        }
        PrimaryButton("保存并应用", {
            c.setSetting("tun.rule_white", white)
            c.setSetting("tun.rule_process", picked.sorted())
            UiMessages.show(if (picked.isEmpty()) "已保存：未选择应用，全部走代理" else "已保存 ${picked.size} 个应用")
            onBack()
        }, Modifier.padding(vertical = 12.dp))
    }
}
