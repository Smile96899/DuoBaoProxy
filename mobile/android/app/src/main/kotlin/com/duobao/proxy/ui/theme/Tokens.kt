package com.duobao.proxy.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Colour tokens of `app/qml/Theme.qml`. Grey-only UI; colour is for connection state, latency and errors. */
@Immutable
data class DbpColors(
    val dark: Boolean,
    val railDark: Boolean,
    val bg: Color,
    val panel: Color,
    val panel2: Color,
    val line: Color,
    val fg: Color,
    val fg2: Color,
    val fg3: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentFg: Color,
    val warn: Color,
    val bad: Color,
    val railBg: Color,
    val railPanel: Color,
    val railPanel2: Color,
    val railLine: Color,
    val railFg: Color,
    val railFg2: Color,
    val railFg3: Color,
) {
    val good: Color get() = accent
}

private class Content(
    val bg: Color, val panel: Color, val panel2: Color, val line: Color,
    val fg: Color, val fg2: Color, val fg3: Color,
    val accent: Color, val accentSoft: Color, val accentFg: Color, val warn: Color, val bad: Color,
)

private val Light = Content(
    bg = Color(0xFFF3F4F6), panel = Color(0xFFFFFFFF), panel2 = Color(0xFFE9EBEF), line = Color(0xFFDCDFE5),
    fg = Color(0xFF15181D), fg2 = Color(0xFF5B6270), fg3 = Color(0xFF8A909C),
    accent = Color(0xFF1F8F5F), accentSoft = Color(0xFFDCF2E7), accentFg = Color(0xFFFFFFFF),
    warn = Color(0xFFC48A08), bad = Color(0xFFC9433D),
)

private val Dark = Content(
    bg = Color(0xFF111317), panel = Color(0xFF1A1D23), panel2 = Color(0xFF22262E), line = Color(0xFF2C313B),
    fg = Color(0xFFE8EAEE), fg2 = Color(0xFFA0A7B4), fg3 = Color(0xFF6C7482),
    accent = Color(0xFF34B27A), accentSoft = Color(0xFF17352A), accentFg = Color(0xFF0B1A13),
    warn = Color(0xFFD9A420), bad = Color(0xFFE0605A),
)

/** `dark` picks the content column; `railDark` (dark or mixed) gives the rail / tab bar the fixed dark values. */
fun dbpColors(dark: Boolean, railDark: Boolean = dark): DbpColors {
    val c = if (dark) Dark else Light
    val r = if (railDark) Dark else c
    return DbpColors(
        dark = dark, railDark = railDark,
        bg = c.bg, panel = c.panel, panel2 = c.panel2, line = c.line,
        fg = c.fg, fg2 = c.fg2, fg3 = c.fg3,
        accent = c.accent, accentSoft = c.accentSoft, accentFg = c.accentFg, warn = c.warn, bad = c.bad,
        railBg = r.bg, railPanel = r.panel, railPanel2 = r.panel2, railLine = r.line,
        railFg = r.fg, railFg2 = r.fg2, railFg3 = r.fg3,
    )
}
