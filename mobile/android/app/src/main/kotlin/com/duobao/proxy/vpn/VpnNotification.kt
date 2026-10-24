package com.duobao.proxy.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.duobao.proxy.MainActivity
import com.duobao.proxy.R
import com.duobao.proxy.state.TrafficState
import com.duobao.proxy.ui.theme.formatBytes

/**
 * The foreground-service notification (the desktop tray on mobile): node name, live ↑/↓ speed and a
 * 断开 action. Low importance, silent, ongoing.
 */
class VpnNotification(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "VPN 连接", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "连接状态与实时速度"
                    setShowBadge(false)
                },
            )
        }
    }

    private val openApp: PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val stop: PendingIntent = PendingIntent.getService(
        context, 1, DuoBaoVpnService.stopIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun base(): Notification.Builder = Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_duobao)
        .setContentIntent(openApp)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        .addAction(Notification.Action.Builder(null, "断开", stop).build())

    fun connecting(): Notification = base().setContentTitle("多宝代理").setContentText("正在连接…").build()

    fun connected(nodeName: String, t: TrafficState): Notification = base()
        .setContentTitle(nodeName)
        .setContentText("↑ ${rate(t.upRate)}   ↓ ${rate(t.downRate)}")
        .build()

    fun update(n: Notification) = nm.notify(ID, n)

    companion object {
        const val ID = 1
        const val CHANNEL = "vpn"

        fun rate(bps: Long) = formatBytes(bps) + "/s"
    }
}
