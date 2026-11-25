package com.pocketnotify.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

class PollAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val cfg = Storage.loadConfig(context)
        if (!cfg.serviceEnabled) return

        // Schedule next Doze-piercing wakeup immediately.
        scheduleNext(context)

        val pending = goAsync()
        Thread({
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketNotify:AlarmSync")
            try {
                wl.acquire(15_000L)
                val res = RelayClient.fetchEvents(cfg, Storage.getLastSeenId(context))
                if (res.ok) {
                    for (ev in res.events) {
                        if (Storage.recordEvent(context, ev)) {
                            Notifier.showEvent(context, ev)
                        }
                    }
                    context.sendBroadcast(Intent(RelayService.ACTION_UI_REFRESH).setPackage(context.packageName))
                }
                // Keep foreground service alive if the OS previously evicted it.
                try {
                    RelayService.start(context)
                } catch (_: Exception) {
                    // Android 12+ may restrict FGS launch from background; Alarm sync still succeeded.
                }
            } finally {
                if (wl.isHeld) wl.release()
                pending.finish()
            }
        }, "PocketNotifyAlarm").start()
    }

    companion object {
        private const val REQUEST_CODE = 9001

        private fun alarmIntent(context: Context): PendingIntent {
            val intent = Intent(context, PollAlarmReceiver::class.java)
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun scheduleNext(context: Context) {
            val cfg = Storage.loadConfig(context)
            val delayMs = cfg.pollIntervalMinutes.coerceIn(1, 60) * 60_000L
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = alarmIntent(context)
            try {
                val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.canScheduleExactAlarms()
                } else {
                    true
                }
                if (canExact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(alarmIntent(context))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (Storage.loadConfig(context).serviceEnabled) {
                PollAlarmReceiver.scheduleNext(context)
                try {
                    RelayService.start(context)
                } catch (_: Exception) {
                    // Will recover via scheduled alarm.
                }
            }
        }
    }
}
