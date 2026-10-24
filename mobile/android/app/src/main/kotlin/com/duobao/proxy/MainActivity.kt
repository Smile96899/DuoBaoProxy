package com.duobao.proxy

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duobao.proxy.ui.AppShell
import com.duobao.proxy.ui.AppViewModel
import com.duobao.proxy.ui.theme.DbpTheme
import com.duobao.proxy.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mode by vm.themeMode.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            LaunchedEffect(mode, systemDark) { applySystemBars(mode, systemDark) }
            DbpTheme(mode) { AppShell() }
        }
    }

    private fun applySystemBars(mode: ThemeMode, systemDark: Boolean) {
        fun style(dark: Boolean) =
            if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(
            statusBarStyle = style(mode.isDark(systemDark)),
            navigationBarStyle = style(mode.isRailDark(systemDark)),
        )
    }
}
