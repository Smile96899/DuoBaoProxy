package com.duobao.proxy.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

/** Country helpers for the map, the flags and the exit-IP chip. Pure apart from [WorldMap]. */
object Countries {
    /** Approximate centre (lon, lat) of the countries nodes usually sit in. */
    val centre: Map<String, Pair<Float, Float>> = mapOf(
        "CN" to (116f to 35f), "HK" to (114f to 22f), "MO" to (113.5f to 22f), "TW" to (121f to 24f), "JP" to (139f to 36f),
        "KR" to (127f to 37f), "SG" to (104f to 1.3f), "MY" to (102f to 4f), "TH" to (101f to 15f), "VN" to (106f to 16f),
        "PH" to (122f to 12f), "ID" to (113f to -2f), "IN" to (78f to 22f), "AE" to (55f to 24f), "TR" to (35f to 39f),
        "IL" to (35f to 31f), "RU" to (38f to 56f), "UA" to (31f to 49f), "DE" to (10f to 51f), "FR" to (2f to 47f),
        "GB" to (-1f to 53f), "NL" to (5f to 52f), "BE" to (4f to 50.5f), "CH" to (8f to 47f), "IT" to (12f to 43f),
        "ES" to (-4f to 40f), "PT" to (-8f to 39.5f), "SE" to (16f to 61f), "NO" to (9f to 61f), "FI" to (26f to 63f),
        "DK" to (10f to 56f), "PL" to (19f to 52f), "AT" to (14f to 47.5f), "IE" to (-8f to 53f), "US" to (-97f to 39f),
        "CA" to (-100f to 56f), "MX" to (-102f to 23f), "BR" to (-50f to -12f), "AR" to (-64f to -35f), "CL" to (-71f to -33f),
        "AU" to (134f to -25f), "NZ" to (172f to -41f), "ZA" to (24f to -29f), "EG" to (30f to 27f), "NG" to (8f to 9f),
        "KH" to (105f to 12.5f), "LA" to (103f to 18f), "MM" to (96f to 21f), "BD" to (90f to 24f), "NP" to (84f to 28f),
        "LK" to (81f to 7.5f), "MN" to (104f to 47f), "QA" to (51f to 25.3f), "GR" to (22f to 39f), "CZ" to (15f to 49.8f),
        "RO" to (25f to 46f), "HU" to (19f to 47f), "BG" to (25f to 42.7f), "CO" to (-74f to 4f), "PE" to (-76f to -10f),
        "KZ" to (67f to 48f), "PK" to (70f to 30f), "SA" to (45f to 24f), "IR" to (53f to 32f), "LU" to (6f to 49.7f),
    )

    /** Name keywords → country code, checked in order (more specific first). */
    private val keywords: List<Pair<List<String>, String>> = listOf(
        listOf("香港", "hong kong", "hongkong", "🇭🇰") to "HK",
        listOf("澳门", "macau", "macao") to "MO",
        listOf("台湾", "臺灣", "taiwan", "台北", "🇹🇼") to "TW",
        listOf("新加坡", "singapore", "狮城", "🇸🇬") to "SG",
        listOf("日本", "japan", "东京", "東京", "tokyo", "大阪", "osaka", "🇯🇵") to "JP",
        listOf("韩国", "韓國", "korea", "首尔", "seoul", "🇰🇷") to "KR",
        listOf("美国", "美國", "united states", "usa", "洛杉矶", "纽约", "硅谷", "西雅图", "芝加哥", "达拉斯", "圣何塞", "los angeles", "new york", "🇺🇸") to "US",
        listOf("加拿大", "canada", "🇨🇦") to "CA",
        listOf("英国", "英國", "united kingdom", "london", "伦敦", "🇬🇧") to "GB",
        listOf("德国", "德國", "germany", "法兰克福", "frankfurt", "🇩🇪") to "DE",
        listOf("法国", "法國", "france", "巴黎", "paris", "🇫🇷") to "FR",
        listOf("荷兰", "netherlands", "阿姆斯特丹", "amsterdam", "🇳🇱") to "NL",
        listOf("俄罗斯", "俄羅斯", "russia", "莫斯科", "🇷🇺") to "RU",
        listOf("印度尼西亚", "印尼", "indonesia", "🇮🇩") to "ID",
        listOf("印度", "india", "孟买", "🇮🇳") to "IN",
        listOf("土耳其", "turkey", "türkiye", "🇹🇷") to "TR",
        listOf("澳大利亚", "澳洲", "australia", "悉尼", "sydney", "🇦🇺") to "AU",
        listOf("新西兰", "new zealand", "🇳🇿") to "NZ",
        listOf("马来西亚", "malaysia", "吉隆坡", "🇲🇾") to "MY",
        listOf("泰国", "thailand", "曼谷", "🇹🇭") to "TH",
        listOf("越南", "vietnam", "🇻🇳") to "VN",
        listOf("菲律宾", "philippines", "🇵🇭") to "PH",
        listOf("阿联酋", "迪拜", "dubai", "uae", "🇦🇪") to "AE",
        listOf("以色列", "israel", "🇮🇱") to "IL",
        listOf("乌克兰", "ukraine", "🇺🇦") to "UA",
        listOf("瑞士", "switzerland", "🇨🇭") to "CH",
        listOf("瑞典", "sweden", "🇸🇪") to "SE",
        listOf("挪威", "norway", "🇳🇴") to "NO",
        listOf("芬兰", "finland", "🇫🇮") to "FI",
        listOf("丹麦", "denmark", "🇩🇰") to "DK",
        listOf("波兰", "poland", "🇵🇱") to "PL",
        listOf("意大利", "italy", "米兰", "🇮🇹") to "IT",
        listOf("西班牙", "spain", "🇪🇸") to "ES",
        listOf("葡萄牙", "portugal", "🇵🇹") to "PT",
        listOf("爱尔兰", "ireland", "🇮🇪") to "IE",
        listOf("奥地利", "austria", "🇦🇹") to "AT",
        listOf("比利时", "belgium", "🇧🇪") to "BE",
        listOf("巴西", "brazil", "🇧🇷") to "BR",
        listOf("阿根廷", "argentina", "🇦🇷") to "AR",
        listOf("智利", "chile", "🇨🇱") to "CL",
        listOf("墨西哥", "mexico", "🇲🇽") to "MX",
        listOf("南非", "south africa", "🇿🇦") to "ZA",
        listOf("埃及", "egypt", "🇪🇬") to "EG",
        listOf("哈萨克", "kazakhstan", "🇰🇿") to "KZ",
        listOf("中国", "china", "上海", "北京", "深圳", "广州", "🇨🇳") to "CN",
    )

    /** The country a node name points at (`🇺🇸 美国 01`, `HK-03`, `Tokyo`), or null. */
    fun guess(name: String): String? {
        val n = name.lowercase()
        keywords.firstOrNull { (ks, _) -> ks.any { n.contains(it) } }?.let { return it.second }
        Regex("""(?:^|[^a-z])(hk|tw|mo|sg|jp|kr|us|uk|gb|de|fr|nl|ru|au|nz|ca|th|vn|tr|ro|pl|it|es|se|ch|cz|fi|dk|ie|pt|gr|hu|bg|ua|il|ae|sa|za|br|ar|mx|cl|co|pe|ph|kh|mm|bd|np|lk|pk|kz|mn|lu|ee|lv|lt|rs|hr|si|sk|md|ge|az|qa|kw|bh|om|eg|ng|ke)(?:[^a-z]|$)""").find(n)?.let {
            val cc = it.groupValues[1].uppercase()
            return if (cc == "UK") "GB" else cc
        }
        return null
    }

    /** The regional-indicator flag emoji of [cc]; empty for anything but two letters. */
    fun flag(cc: String?): String {
        if (cc == null || cc.length != 2 || !cc.all { it in 'A'..'Z' }) return ""
        return String(Character.toChars(0x1F1E6 + (cc[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (cc[1] - 'A')))
    }

    // ---- country shapes (assets/countries.txt, Natural Earth 1:110m, public domain) ----

    /** One country: its label point and outer rings, (lon, lat) pairs in degrees. */
    class Shape(val label: Pair<Float, Float>, val rings: List<FloatArray>)

    /**
     * Parses `CC|labelLon,labelLat|lon,lat lon,lat …;ring2…` lines (tenths of a degree), as
     * written from the Natural Earth GeoJSON. Antarctica is left out; tiny countries (HK, SG, MO)
     * are absent at this scale and only get a pin.
     */
    fun parseShapes(text: String): Map<String, Shape> {
        // Hand-rolled: split()/toFloat() per point took ~1 s on a debug build.
        val out = LinkedHashMap<String, Shape>()
        var i = 0
        val n = text.length
        fun num(): Float {
            var neg = false
            if (i < n && text[i] == '-') { neg = true; i++ }
            var v = 0
            while (i < n && text[i] in '0'..'9') { v = v * 10 + (text[i] - '0'); i++ }
            return (if (neg) -v else v) / 10f
        }
        while (i < n) {
            val bar = text.indexOf('|', i)
            if (bar < 0) break
            val cc = text.substring(i, bar).trim()
            i = bar + 1
            val lx = num(); i++ // ','
            val ly = num(); i++ // '|'
            val rings = ArrayList<FloatArray>()
            var pts = FloatArrayBuilder()
            while (i < n && text[i] != '\n' && text[i] != '\r') {
                val x = num(); i++ // ','
                val y = num()
                pts.add(x, y)
                if (i < n && text[i] == ';') { rings.add(pts.build()); pts = FloatArrayBuilder() }
                if (i < n && (text[i] == ' ' || text[i] == ';')) i++
            }
            rings.add(pts.build())
            while (i < n && (text[i] == '\n' || text[i] == '\r')) i++
            val prev = out[cc]
            out[cc] = if (prev == null) Shape(lx to ly, rings) else Shape(prev.label, prev.rings + rings)
        }
        return out
    }

    private class FloatArrayBuilder {
        private var a = FloatArray(64)
        private var size = 0
        fun add(x: Float, y: Float) {
            if (size + 2 > a.size) a = a.copyOf(a.size * 2)
            a[size++] = x; a[size++] = y
        }
        fun build(): FloatArray = a.copyOf(size)
    }

    /** Already parsed shapes, or empty. */
    fun cached(): Map<String, Shape> = loaded ?: emptyMap()

    @Volatile private var loaded: Map<String, Shape>? = null

    /** The shapes from assets, parsed once per process (call off the main thread). */
    fun shapes(context: android.content.Context): Map<String, Shape> = loaded ?: synchronized(this) {
        loaded ?: runCatching { parseShapes(context.assets.open("countries.txt").bufferedReader().use { it.readText() }) }
            .getOrDefault(emptyMap()).also { if (it.isNotEmpty()) loaded = it }
    }

    /** Where a pin for [cc] goes: the shape's label point, else the [centre] table. */
    fun point(cc: String?, shapes: Map<String, Shape>): Pair<Float, Float>? =
        cc?.let { centre[it] ?: shapes[it]?.label }

    const val LAT_TOP = 84f
    const val LAT_BOTTOM = -57f
}

/**
 * The 首页 map: every country a [land] tile, the device's country
 * ([from]) tinted and the exit country ([to], when connected) filled with [highlight], joined by a
 * flowing dashed arc. A beam falls onto the pinned country; both ends carry their names. The map
 * fills the card's height, centres on the countries shown, and can be dragged, springing back on
 * release.
 */
@Composable
fun WorldMap(
    from: String?,
    to: String?,
    fromName: String?,
    toName: String?,
    land: Color,
    gap: Color,
    highlight: Color,
    labelBg: Color,
    labelFg: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shapes by produceState(Countries.cached()) {
        if (value.isEmpty()) value = withContext(Dispatchers.Default) { Countries.shapes(context) }
    }
    // The beam falls again whenever the pinned country changes, then the pin keeps pulsing and
    // the arc's dashes keep flowing.
    val target = to ?: from
    val drop = remember { Animatable(0f) }
    LaunchedEffect(target, shapes.isNotEmpty()) {
        drop.snapTo(0f)
        drop.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val loop = rememberInfiniteTransition(label = "map")
    val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "pulse")
    val flow by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "flow")
    // The user's drag and pinch on top of the automatic view; released, both spring back.
    val pan = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val zoom = remember { Animatable(1f) }
    val back = spring<Float>(dampingRatio = 0.65f, stiffness = 200f)
    // The automatic view eases between countries instead of jumping.
    val view = remember { Animatable(Offset(15f, 20f), Offset.VectorConverter) }
    // The lon / lat extent to fit in the card, animated like the centre.
    val span = remember { Animatable(Offset(60f, 40f), Offset.VectorConverter) }
    LaunchedEffect(from, to, shapes.isNotEmpty()) {
        if (shapes.isEmpty()) return@LaunchedEffect
        val a = Countries.point(from, shapes)
        val b = Countries.point(to, shapes)
        val focus = when {
            a != null && b != null -> Offset((a.first + b.first) / 2, (a.second + b.second) / 2)
            else -> (b ?: a)?.let { Offset(it.first, it.second) } ?: Offset(15f, 20f)
        }
        // Connected: just the two points; otherwise a region around the one shown.
        val extent = if (a != null && b != null) Offset(abs(a.first - b.first), abs(a.second - b.second)) else Offset(40f, 24f)
        launch { view.animateTo(focus, tween(700, easing = FastOutSlowInEasing)) }
        span.animateTo(extent, tween(700, easing = FastOutSlowInEasing))
    }
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = labelFg)

    Canvas(
        modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                scope.launch { pan.stop() }
                scope.launch { zoom.stop() }
                do {
                    val ev = awaitPointerEvent()
                    val z = ev.calculateZoom()
                    val d = ev.calculatePan()
                    if (z != 1f || d != Offset.Zero) {
                        scope.launch { zoom.snapTo((zoom.value * z).coerceIn(0.4f, 6f)) }
                        scope.launch { pan.snapTo(pan.value + d) }
                        ev.changes.forEach { it.consume() }
                    }
                } while (ev.changes.any { it.pressed })
                scope.launch { pan.animateTo(Offset.Zero, spring(dampingRatio = 0.65f, stiffness = 200f)) }
                scope.launch { zoom.animateTo(1f, back) }
            }
        },
    ) {
        if (shapes.isEmpty()) return@Canvas
        val a = Countries.point(from, shapes)
        val b = Countries.point(to, shapes)
        val base = size.height / (Countries.LAT_TOP - Countries.LAT_BOTTOM)
        // Fit the span with room for the name tags above and below and the pins at the sides.
        val fit = minOf(
            (size.width - 120.dp.toPx()) / (span.value.x.coerceAtLeast(1f) * base),
            (size.height - 90.dp.toPx()) / (span.value.y.coerceAtLeast(1f) * base),
        ).coerceIn(1f, 12f)
        val scale = base * fit * zoom.value
        // The automatic centre, kept so the map always covers the card (before the user's pan).
        val halfW = size.width / 2 / scale
        val halfH = size.height / 2 / scale
        fun within(v: Float, lo: Float, hi: Float) = if (lo > hi) (lo + hi) / 2 else v.coerceIn(lo, hi)
        val cx = within(view.value.x, -180f + halfW, 180f - halfW)
        val cy = within(view.value.y, Countries.LAT_BOTTOM + halfH, Countries.LAT_TOP - halfH)
        fun x(lon: Float) = size.width / 2 + (lon - cx) * scale + pan.value.x
        fun y(lat: Float) = size.height / 2 - (lat - cy) * scale + pan.value.y
        val border = Stroke(width = 0.8.dp.toPx())
        for ((cc, shape) in shapes) {
            val fill = when (cc) {
                to -> highlight
                from -> highlight.copy(alpha = 0.38f)
                else -> land
            }
            for (ring in shape.rings) {
                val path = Path()
                for (i in 0 until ring.size / 2) {
                    val px = x(ring[2 * i]); val py = y(ring[2 * i + 1])
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                drawPath(path, fill)
                drawPath(path, gap, style = border)
            }
        }
        val pin = 2.6.dp.toPx()
        // A beam from the top edge down to a point, fading in from above.
        fun beam(p: Offset, d: Float, alpha: Float) {
            val headY = p.y * d
            drawLine(
                Brush.verticalGradient(listOf(highlight.copy(alpha = 0f), highlight.copy(alpha = alpha)), startY = 0f, endY = headY.coerceAtLeast(1f)),
                Offset(p.x, 0f), Offset(p.x, headY), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round,
            )
        }
        val p0 = a?.let { Offset(x(it.first), y(it.second)) }
        val p1 = b?.let { Offset(x(it.first), y(it.second)) }
        if (p0 != null && p1 != null && (abs(p0.x - p1.x) > 2f || abs(p0.y - p1.y) > 2f)) {
            val lift = (hypot(p1.x - p0.x, p1.y - p0.y) * 0.3f).coerceAtLeast(16.dp.toPx())
            val ctrl = Offset((p0.x + p1.x) / 2, minOf(p0.y, p1.y) - lift)
            val path = Path().apply { moveTo(p0.x, p0.y); quadraticTo(ctrl.x, ctrl.y, p1.x, p1.y) }
            val dash = 9.dp.toPx()
            drawPath(path, highlight, style = Stroke(width = 1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash * 0.55f, dash * 0.45f), -flow * dash)))
            beam(p0, drop.value, 0.55f)
            drawCircle(Color.White, pin * 1.4f, p0)
            drawCircle(highlight, pin, p0)
        }
        val main = p1 ?: p0
        main?.let { p ->
            val d = drop.value
            // The beam: from the top edge down to the country's centre, fading in from above.
            val headY = p.y * d
            beam(p, d, 0.9f)
            if (d < 1f) {
                drawCircle(highlight, pin * 0.8f, Offset(p.x, headY))
            } else {
                // Landed: a ripple spreading from the pin.
                drawCircle(highlight.copy(alpha = 0.35f * (1f - pulse)), pin * (1.4f + 3.6f * pulse), p)
                drawCircle(highlight.copy(alpha = 0.18f), pin * 2.2f, p)
                drawCircle(Color.White, pin * 1.4f, p)
                drawCircle(highlight, pin, p)
            }
        }
        // Name tags sit under their pins so the beams from above stay clear.
        fun tag(name: String?, at: Offset?, above: Boolean) {
            if (name.isNullOrEmpty() || at == null) return
            val t = measurer.measure(name, labelStyle)
            val padH = 7.dp.toPx(); val padV = 3.dp.toPx()
            val w = t.size.width + padH * 2; val h = t.size.height + padV * 2
            val gapY = 10.dp.toPx()
            val left = (at.x - w / 2).coerceIn(4.dp.toPx(), size.width - w - 4.dp.toPx())
            val top = if (above) at.y - gapY - h else at.y + gapY
            drawRoundRect(labelBg, Offset(left, top), Size(w, h), CornerRadius(h / 2))
            drawText(t, topLeft = Offset(left + padH, top + padV))
        }
        if (p1 != null && p0 != null && (abs(p0.x - p1.x) > 2f || abs(p0.y - p1.y) > 2f)) {
            tag(fromName, p0, above = false)
            if (drop.value >= 1f) tag(toName, p1, above = false)
        } else if (drop.value >= 1f) {
            tag(toName ?: fromName, main, above = false)
        }
    }
}
