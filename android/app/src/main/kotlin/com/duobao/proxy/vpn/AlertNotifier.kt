package com.duobao.proxy.vpn

import com.duobao.proxy.core.tr
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.duobao.proxy.MainActivity
import com.duobao.proxy.R
import java.util.concurrent.atomic.AtomicInteger

/** Reminders and state changes (设置 › 通知): a default-importance channel next to the silent VPN one. */
class AlertNotifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val ids = AtomicInteger(100)

    init {
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, tr("提醒"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = tr("订阅到期、流量不足、连接断开等提醒")
                },
            )
        }
    }

    fun show(title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_duobao)
            .setContentTitle(tr(title))
            .setContentText(tr(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ids.incrementAndGet(), n) }
    }

    companion object {
        const val CHANNEL = "alerts"
    }
}
