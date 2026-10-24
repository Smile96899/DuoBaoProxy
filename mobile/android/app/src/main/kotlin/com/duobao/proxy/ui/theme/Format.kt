package com.duobao.proxy.ui.theme

import androidx.compose.ui.graphics.Color
import java.util.Locale

/** `Theme.latColor`: 0/unknown → fg3, negative → bad, < 80 good, < 150 warn, else bad. */
fun DbpColors.latColor(ms: Int?): Color = when {
    ms == null || ms == 0 -> fg3
    ms < 0 -> bad
    ms < 80 -> good
    ms < 150 -> warn
    else -> bad
}

fun latText(ms: Int?): String = when {
    ms == null || ms == 0 -> "未测"
    ms < 0 -> "超时"
    else -> "$ms ms"
}

/** Logo / status-dot colour for `off`, `on` and `tun`. */
fun DbpColors.lookColor(look: String): Color = when (look) {
    "tun" -> bad
    "on" -> accent
    else -> fg3
}

fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1_048_576 -> String.format(Locale.ROOT, "%.1f KB", n / 1024.0)
    n < 1_073_741_824 -> String.format(Locale.ROOT, "%.1f MB", n / 1_048_576.0)
    else -> String.format(Locale.ROOT, "%.2f GB", n / 1_073_741_824.0)
}
