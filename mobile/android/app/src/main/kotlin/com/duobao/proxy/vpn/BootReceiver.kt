package com.duobao.proxy.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.duobao.proxy.core.LogRepository
import com.duobao.proxy.state.AppController

/**
 * 开机自动连接: on boot, connects the last node only when the opt-in `ui.boot_connect` is true and
 * the VPN permission is already granted (critic A4: desktop autostart never connects). The
 * Application has already loaded the Store and started Setup by the time this runs.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val c = AppController.instance
        if (!c.bootConnect) return
        if (!DuoBaoVpnService.permissionGranted(context)) {
            LogRepository.log("开机自动连接：尚未授予 VPN 权限，已跳过")
            return
        }
        LogRepository.log("开机自动连接")
        c.connectFromSystem()
    }
}
