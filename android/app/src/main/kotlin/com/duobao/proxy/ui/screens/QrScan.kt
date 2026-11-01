package com.duobao.proxy.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.duobao.core.mobile.Mobile
import com.duobao.proxy.core.GoCore
import com.duobao.proxy.ui.components.UiMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * QR import without a camera library: the system camera writes a photo into our cache (FileProvider)
 * or the photo picker returns an image; either is downscaled and decoded by gozxing in Go.
 */
class QrScanner(val camera: () -> Unit, val gallery: () -> Unit)

@Composable
fun rememberQrScanner(onText: (String) -> Unit): QrScanner {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photo = remember { File(File(context.cacheDir, "scan").apply { mkdirs() }, "scan.jpg") }
    val photoUri = remember { FileProvider.getUriForFile(context, context.packageName + ".files", photo) }

    fun decode(uri: Uri) = scope.launch {
        val text = runCatching {
            val bytes = withContext(Dispatchers.IO) { downscaled(context, uri) } ?: error("无法读取图片")
            GoCore.call { Mobile.decodeQR(bytes) }
        }
        text.onSuccess { if (it.isBlank()) UiMessages.show("图片中没有可识别的二维码") else onText(it) }
            .onFailure { UiMessages.show(it.message ?: "二维码识别失败") }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) decode(photoUri) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) decode(uri) }
    return remember {
        QrScanner(
            camera = { runCatching { takePicture.launch(photoUri) }.onFailure { UiMessages.show("没有可用的相机应用") } },
            gallery = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }
}

/** The image at [uri] scaled to at most ~1600 px on the long side, as JPEG bytes. */
private fun downscaled(context: Context, uri: Uri): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    var sample = 1
    while (longest / (sample * 2) >= 1600) sample *= 2
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    return ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bmp.recycle()
        out.toByteArray()
    }
}
