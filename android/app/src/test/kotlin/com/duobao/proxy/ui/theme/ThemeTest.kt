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
        assertEquals(Color(0xFF2CE88E), dark.latColor(42))
    }

    @Test
    fun latencyText() {
        assertEquals("未测", latText(0))
        assertEquals("未测", latText(null))
        assertEquals("超时", latText(-1))
        assertEquals("42 ms", latText(42))
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
        assertEquals(ThemeMode.System, ThemeMode.from("mixed"))
        assertTrue(ThemeMode.System.isDark(systemDark = true))
        assertFalse(ThemeMode.System.isDark(systemDark = false))
        assertFalse(ThemeMode.Light.isDark(systemDark = true))
        assertTrue(ThemeMode.Dark.isDark(systemDark = false))
    }

    @Test
    fun tokensMatchDesign() {
        assertEquals(Color(0xFFF3F6F4), light.bg)
        assertEquals(Color(0xFF1A78F5), light.accent)
        assertEquals(Color(0xFF0A0D0C), dark.bg)
        assertEquals(Color(0xFF2CE88E), dark.accent)
    }
}
