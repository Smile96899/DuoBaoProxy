package com.duobao.proxy.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The line icons of the mobile design: 24-unit stroke paths (Lucide style, width 2, round caps),
 * tinted by `Icon`. Kept as path strings so no icon library is linked (size first).
 */
object DbpIcons {
    private val cache = HashMap<String, ImageVector>()

    val paths: Map<String, List<String>> = mapOf(
        "power" to listOf("M12 2v10", "M18.4 6.6a9 9 0 1 1-12.77.04"),
        "wifi" to listOf("M12 20h.01", "M2 8.82a15 15 0 0 1 20 0", "M5 12.86a10 10 0 0 1 14 0", "M8.5 16.43a5 5 0 0 1 7 0"),
        "zap" to listOf("M13 2 3 14h9l-1 8 10-12h-9l1-8z"),
        "plus" to listOf("M5 12h14", "M12 5v14"),
        "chevronDown" to listOf("M6 9l6 6 6-6"),
        "chevronUp" to listOf("M18 15l-6-6-6 6"),
        "chevronRight" to listOf("M9 18l6-6-6-6"),
        "back" to listOf("M15 18l-6-6 6-6"),
        "scan" to listOf("M3 7V5a2 2 0 0 1 2-2h2", "M17 3h2a2 2 0 0 1 2 2v2", "M21 17v2a2 2 0 0 1-2 2h-2", "M7 21H5a2 2 0 0 1-2-2v-2", "M7 12h10"),
        "clipboard" to listOf(
            "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z",
            "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2",
            "M9 14l2 2 4-4",
        ),
        "pencil" to listOf("M17 3a2.85 2.85 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z"),
        "cloud" to listOf("M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9z"),
        "refresh" to listOf("M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8", "M21 3v5h-5"),
        "clock" to listOf("M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0", "M12 6v6l4 2"),
        "more" to listOf("M5 12h.01", "M12 12h.01", "M19 12h.01"),
        "ban" to listOf("M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0", "M4.9 4.9l14.2 14.2"),
        "arrowRight" to listOf("M5 12h14", "M12 5l7 7-7 7"),
        "globe" to listOf("M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0", "M12 2a14.5 14.5 0 0 0 0 20a14.5 14.5 0 0 0 0-20", "M2 12h20"),
        "search" to listOf("M3 11a8 8 0 1 0 16 0a8 8 0 1 0-16 0", "M21 21l-4.3-4.3"),
        "check" to listOf("M20 6 9 17l-5-5"),
        "x" to listOf("M18 6 6 18", "M6 6l12 12"),
        "cpu" to listOf("M5 5h14v14H5z", "M9 9h6v6H9z", "M9 2v3", "M15 2v3", "M9 19v3", "M15 19v3", "M2 9h3", "M2 15h3", "M19 9h3", "M19 15h3"),
        "upload" to listOf("M12 3v12", "M17 8l-5-5-5 5", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"),
        "download" to listOf("M12 15V3", "M7 10l5 5 5-5", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"),
        "share" to listOf("M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "M16 6l-4-4-4 4", "M12 2v13"),
        "image" to listOf("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M7 9a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M21 15l-3-3a2 2 0 0 0-2.8 0L6 21"),
        "link" to listOf("M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"),
        "alert" to listOf("M12 7v6", "M12 17h.01"),
        "trash" to listOf("M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"),
        "home" to listOf(
            "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
            "M3 10a2 2 0 0 1 .71-1.53l7-6a2 2 0 0 1 2.58 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
        ),
        "rules" to listOf("M3 6h14", "M3 12h10", "M3 18h8", "M14 17l2 2 4-4"),
        "settings" to listOf(
            "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
        ),
        "layers" to listOf("M12 2 2 7l10 5 10-5-10-5z", "M2 17l10 5 10-5", "M2 12l10 5 10-5"),
    )

    /** The icon [name] (one of [paths]); unknown names give an empty icon. */
    fun get(name: String): ImageVector = cache.getOrPut(name) {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths[name].orEmpty()) {
            b.addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        b.build()
    }
}
