package com.duobao.proxy.ui.theme

/** `ui.theme` values and their Chinese labels (设置 › 深色模式). Desktop's `mixed` reads as 跟随系统. */
enum class ThemeMode(val key: String, val label: String) {
    System("system", "跟随系统"),
    Light("light", "浅色"),
    Dark("dark", "深色");

    fun isDark(systemDark: Boolean): Boolean = this == Dark || (this == System && systemDark)

    companion object {
        fun from(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: System
    }
}
