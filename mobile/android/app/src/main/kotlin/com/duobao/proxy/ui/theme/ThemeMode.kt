package com.duobao.proxy.ui.theme

/** `ui.theme` values and their Chinese labels (`SettingsPage.qml:52`). */
enum class ThemeMode(val key: String, val label: String) {
    System("system", "跟随系统"),
    Light("light", "浅色"),
    Dark("dark", "深色"),
    Mixed("mixed", "深浅组合");

    /** `Theme.qml`: `dark` follows the OS only in system mode. */
    fun isDark(systemDark: Boolean): Boolean = this == Dark || (this == System && systemDark)

    /** Mixed keeps light content with a dark rail / tab bar. */
    fun isRailDark(systemDark: Boolean): Boolean = isDark(systemDark) || this == Mixed

    companion object {
        fun from(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: System
    }
}
