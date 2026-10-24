package com.duobao.proxy

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.duobao.proxy.state.AppController
import kotlinx.coroutines.launch
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
    private val controller get() = AppController.instance

    /** The VPN consent dialog of `VpnService.prepare`, requested by the controller. */
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        controller.onVpnPermissionResult(r.resultCode == RESULT_OK)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.vpnPermissionRequest.collect { intent ->
                    if (intent != null) runCatching { vpnConsent.launch(intent) }.onFailure { controller.onVpnPermissionResult(false) }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge()
        setContent {
            val mode by vm.themeMode.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            LaunchedEffect(mode, systemDark) { applySystemBars(mode, systemDark) }
            DbpTheme(mode) { AppShell() }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.reattach()
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
