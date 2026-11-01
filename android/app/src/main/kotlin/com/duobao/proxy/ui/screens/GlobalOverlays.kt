package com.duobao.proxy.ui.screens

import com.duobao.proxy.core.tr
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.duobao.proxy.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.duobao.core.mobile.Mobile
import com.duobao.proxy.core.GoCore
import com.duobao.proxy.state.AppController
import com.duobao.proxy.ui.AppViewModel
import com.duobao.proxy.ui.components.CardDialog
import com.duobao.proxy.ui.components.OutlinePill
import com.duobao.proxy.ui.components.UiMessages
import com.duobao.proxy.ui.theme.DbpTheme

/** Dialogs any screen can raise: the import confirmation and the share QR codes. */
@Composable
fun GlobalOverlays(vm: AppViewModel, nav: NavHostController) {
    val pending by vm.pendingImport.collectAsStateWithLifecycle()
    val qr by vm.qr.collectAsStateWithLifecycle()
    val c = DbpTheme.colors
    pending?.let { p ->
        val lines = p.text.lines().filter { it.isNotBlank() }
        CardDialog(
            title = "导入节点",
            onDismiss = vm::dismissImport,
            confirm = "导入",
            onConfirm = { vm.importNow(p.text) },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("来自${p.source}：${lines.size} 行内容", color = c.fg2, fontSize = 13.sp)
                Text(
                    lines.take(3).joinToString("\n") { it.take(60) } + if (lines.size > 3) "\n…" else "",
                    color = c.fg,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.panel2).padding(10.dp),
                )
                Text("单个 http(s) 链接会作为订阅添加。", color = c.fg3, fontSize = 12.sp)
            }
        }
    }
    if (qr.isNotEmpty()) QrDialog(qr, vm::closeQr)
}

/** 分享二维码: node name, the code on a white card, the link, 上一个 / 下一个, 复制链接 and 分享. */
@Composable
private fun QrDialog(items: List<Pair<String, String>>, onClose: () -> Unit) {
    val c = DbpTheme.colors
    val context = LocalContext.current
    var index by remember(items) { mutableIntStateOf(0) }
    val (name, link) = items[index.coerceIn(items.indices)]
    var image by remember(link) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(link) {
        image = runCatching {
            val png = GoCore.call { Mobile.encodeQR(link, 600) }
            BitmapFactory.decodeByteArray(png, 0, png.size).asImageBitmap()
        }.getOrNull()
    }
    CardDialog(title = "分享二维码", onDismiss = onClose) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Text(name, color = c.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(240.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
                image?.let { Image(it, contentDescription = "二维码", modifier = Modifier.size(220.dp)) }
            }
            Text(link, color = c.fg2, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (items.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinePill("上一个", { index-- }, enabled = index > 0)
                    Text("${index + 1} / ${items.size}", color = c.fg2, fontSize = 13.sp)
                    OutlinePill("下一个", { index++ }, enabled = index < items.size - 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinePill("复制链接", {
                    copyText(context, link)
                    AppController.instance.log("已复制分享链接")
                    UiMessages.show("已复制分享链接")
                }, icon = "link", accent = true)
                OutlinePill("分享", { shareText(context, link) }, icon = "share")
            }
        }
    }
}

fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DuoBao Proxy", text))
}

fun clipboardText(context: Context): String =
    context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, tr("分享")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
