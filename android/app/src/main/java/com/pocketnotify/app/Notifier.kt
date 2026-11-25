package com.pocketnotify.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

object Notifier {
    const val STATUS_CHANNEL_ID = "pocket_notify_status"
    const val STATUS_NOTIFICATION_ID = 1001

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureStatusChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                STATUS_CHANNEL_ID,
                "后台保活状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示 Pocket Notify 后台长连接状态（静音）"
                setShowBadge(false)
            }
            manager(context).createNotificationChannel(ch)
        }
    }

    fun buildStatusNotification(context: Context, statusText: String): Notification {
        ensureStatusChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Pocket Notify 运行中")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun updateStatusNotification(context: Context, statusText: String) {
        manager(context).notify(STATUS_NOTIFICATION_ID, buildStatusNotification(context, statusText))
    }

    private fun ensureEventChannel(context: Context, channelKey: String): String {
        val safe = channelKey.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(32).ifBlank { "default" }
        val channelId = "pocket_notify_ch_$safe"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager(context).getNotificationChannel(channelId)
            if (existing == null) {
                val ch = NotificationChannel(
                    channelId,
                    "频道：$channelKey",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "来自 $channelKey 的即时通知"
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager(context).createNotificationChannel(ch)
            }
        }
        return channelId
    }

    fun showEvent(context: Context, event: NotifyEvent) {
        val channelId = ensureEventChannel(context, event.channel)
        val clickIntent = if (event.clickUrl.startsWith("http://") || event.clickUrl.startsWith("https://")) {
            Intent(Intent.ACTION_VIEW, Uri.parse(event.clickUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        val notifId = if (event.id > 0) (2000 + (event.id % 100000).toInt()) else (System.currentTimeMillis() % 100000).toInt() + 2000
        val pi = PendingIntent.getActivity(
            context,
            notifId,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = event.title.ifBlank { "[${event.channel}] 新通知" }
        val body = event.body.ifBlank { "点击查看详情" }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(event.channel)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        manager(context).notify(notifId, builder.build())
    }
}
