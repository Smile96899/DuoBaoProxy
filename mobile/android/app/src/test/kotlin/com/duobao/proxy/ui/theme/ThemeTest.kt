package com.duobao.proxy.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    private val light = dbpColors(dark = false)
    private val dark = dbpColors(dark = true)

    @Test
    fun latencyColourThresholds() {
        assertEquals(light.fg3, light.latColor(null))
        assertEquals(light.fg3, light.latColor(0))
        assertEquals(light.bad, light.latColor(-1))
        assertEquals(light.good, light.latColor(1))
        assertEquals(light.good, light.latColor(79))
        assertEquals(light.warn, light.latColor(80))
        assertEquals(light.warn, light.latColor(149))
        assertEquals(light.bad, light.latColor(150))
        assertEquals(Color(0xFF34B27A), dark.latColor(42))
    }

    @Test
    fun latencyText() {
        assertEquals("未测", latText(0))
        assertEquals("未测", latText(null))
        assertEquals("超时", latText(-1))
        assertEquals("42 ms", latText(42))
    }

    @Test
    fun lookColours() {
        assertEquals(dark.bad, dark.lookColor("tun"))
        assertEquals(dark.accent, dark.lookColor("on"))
        assertEquals(dark.fg3, dark.lookColor("off"))
    }

    @Test
    fun bytes() {
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 MB", formatBytes(1_572_864))
        assertEquals("2.00 GB", formatBytes(2L * 1_073_741_824))
    }

    @Test
    fun modes() {
        assertEquals(ThemeMode.System, ThemeMode.from(null))
        assertEquals(ThemeMode.System, ThemeMode.from("bogus"))
        assertEquals(ThemeMode.Mixed, ThemeMode.from("mixed"))
        assertTrue(ThemeMode.System.isDark(systemDark = true))
        assertFalse(ThemeMode.Light.isDark(systemDark = true))
        assertFalse(ThemeMode.Mixed.isDark(systemDark = true))
        assertTrue(ThemeMode.Mixed.isRailDark(systemDark = false))
        assertFalse(ThemeMode.System.isRailDark(systemDark = false))
    }

    @Test
    fun tokensMatchThemeQml() {
        assertEquals(Color(0xFFF3F4F6), light.bg)
        assertEquals(Color(0xFF1F8F5F), light.accent)
        assertEquals(Color(0xFFC48A08), light.warn)
        assertEquals(Color(0xFFC9433D), light.bad)
        assertEquals(Color(0xFF111317), dark.bg)
        assertEquals(Color(0xFFD9A420), dark.warn)
        assertEquals(Color(0xFFE0605A), dark.bad)
        assertEquals(light.panel2, light.railPanel2)
    }

    @Test
    fun mixedIsLightContentWithDarkRail() {
        val mixed = dbpColors(dark = false, railDark = true)
        assertEquals(light.bg, mixed.bg)
        assertEquals(light.fg, mixed.fg)
        assertEquals(Color(0xFF22262E), mixed.railPanel2)
        assertEquals(Color(0xFF1A1D23), mixed.railPanel)
        assertEquals(Color(0xFFE8EAEE), mixed.railFg)
        assertEquals(Color(0xFF6C7482), mixed.railFg3)
    }
}
