package com.duobao.proxy.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens of the mobile design (`Desktop/vpn/安卓`): light uses a blue accent, dark a green
 * one. Grey UI; colour is for connection state, latency and errors.
 */
@Immutable
data class DbpColors(
    val dark: Boolean,
    /** Page background. */
    val bg: Color,
    /** Cards. */
    val panel: Color,
    /** Neutral fills: segmented track, neutral icon tiles, chips. */
    val panel2: Color,
    /** Card borders and row dividers. */
    val line: Color,
    val fg: Color,
    val fg2: Color,
    val fg3: Color,
    val accent: Color,
    /** Accent icon tiles, info banners, the 默认 badge. */
    val accentSoft: Color,
    val accentFg: Color,
    /** Background of the selected node row. */
    val selected: Color,
    /** Latency below 80 ms. */
    val good: Color,
    val goodSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val bad: Color,
    val badSoft: Color,
    /** The toggle when the tunnel is up but there is no network. */
    val alarm: Color,
    val mapBg: Color,
    val mapDot: Color,
    val navBg: Color,
)

val LightColors = DbpColors(
    dark = false,
    bg = Color(0xFFF3F6F4), panel = Color(0xFFFFFFFF), panel2 = Color(0xFFE9EDEB), line = Color(0xFFE1E8E4),
    fg = Color(0xFF15201D), fg2 = Color(0xFF6B7A75), fg3 = Color(0xFF9AA6A1),
    accent = Color(0xFF1A78F5), accentSoft = Color(0xFFE7F0FE), accentFg = Color(0xFFFFFFFF), selected = Color(0xFFF1F6FE),
    good = Color(0xFF1C9A6A), goodSoft = Color(0xFFDFF3EA),
    warn = Color(0xFFC98A2B), warnSoft = Color(0xFFFBF0DE),
    bad = Color(0xFFA8322A), badSoft = Color(0xFFFBE4E1), alarm = Color(0xFFE04A3C),
    mapBg = Color(0xFFEEF2EF), mapDot = Color(0xFFD3DCD7), navBg = Color(0xFFFFFFFF),
)

val DarkColors = DbpColors(
    dark = true,
    bg = Color(0xFF0A0D0C), panel = Color(0xFF141A17), panel2 = Color(0xFF1E2622), line = Color(0xFF243029),
    fg = Color(0xFFE9F3EE), fg2 = Color(0xFF8CA096), fg3 = Color(0xFF5E6F67),
    accent = Color(0xFF2CE88E), accentSoft = Color(0xFF0F2A1D), accentFg = Color(0xFF06140D), selected = Color(0xFF102119),
    good = Color(0xFF2CE88E), goodSoft = Color(0xFF0F2A1D),
    warn = Color(0xFFF2B64B), warnSoft = Color(0xFF2E2512),
    bad = Color(0xFFFF7A6E), badSoft = Color(0xFF3A1A17), alarm = Color(0xFFE5534B),
    mapBg = Color(0xFF0F1512), mapDot = Color(0xFF26332D), navBg = Color(0xFF141A17),
)

fun dbpColors(dark: Boolean): DbpColors = if (dark) DarkColors else LightColors
