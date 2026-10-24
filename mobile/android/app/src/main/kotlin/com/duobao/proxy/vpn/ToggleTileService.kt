package com.duobao.proxy.vpn

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.duobao.proxy.MainActivity
import com.duobao.proxy.state.AppController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: toggles the VPN. Without the VPN permission it opens the app instead (the
 * permission dialog needs an Activity).
 */
class ToggleTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val c = AppController.instance
        job?.cancel()
        job = scope.launch {
            combine(c.connected, c.busy, c.session) { on, busy, s -> Triple(on, busy, s) }.collect { (on, busy, s) ->
                val tile = qsTile ?: return@collect
                tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.label = "多宝代理"
                tile.subtitle = when {
                    busy -> "正在处理…"
                    on -> s?.nodeName ?: "已连接"
                    else -> "未连接"
                }
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val c = AppController.instance
        if (!c.connected.value && !DuoBaoVpnService.permissionGranted(this)) {
            openApp()
            return
        }
        c.toggle()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Asks the system to re-bind the tile so it shows the new state. */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, ToggleTileService::class.java)) }
        }
    }
}
