package com.pocketnotify.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class RelayService : Service() {
    private val cancelled = AtomicBoolean(false)
    @Volatile
    private var workerThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notif = Notifier.buildStatusNotification(this, "正在建立实时连接...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                Notifier.STATUS_NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(Notifier.STATUS_NOTIFICATION_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cancelled.set(true)
                Storage.setServiceEnabled(this, false)
                PollAlarmReceiver.cancel(this)
                updateStatus("已停止")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SYNC_NOW -> {
                triggerOneShotSync()
            }
        }

        Storage.setServiceEnabled(this, true)
        PollAlarmReceiver.scheduleNext(this)
        ensureWorkerRunning()
        return START_STICKY
    }

    override fun onDestroy() {
        cancelled.set(true)
        workerThread?.interrupt()
        super.onDestroy()
    }

    private fun ensureWorkerRunning() {
        val current = workerThread
        if (current != null && current.isAlive) return
        cancelled.set(false)
        workerThread = Thread({ runLoop() }, "PocketNotifySSE").apply {
            isDaemon = true
            start()
        }
    }

    private fun runLoop() {
        var backoffMs = 3000L
        while (!cancelled.get()) {
            val cfg = Storage.loadConfig(this)
            if (cfg.candidateBaseUrls().isEmpty() || cfg.token.isBlank()) {
                updateStatus("等待配置服务地址和 Token")
                sleepQuietly(10000L)
                continue
            }
            val reason = RelayClient.streamOnce(
                config = cfg,
                sinceIdProvider = { Storage.getLastSeenId(this) },
                isCancelled = { cancelled.get() },
                onConnected = { endpoint ->
                    backoffMs = 3000L
                    updateStatus("实时已连接：$endpoint (${nowClock()})")
                },
                onEvent = { ev ->
                    handleIncomingEvent(ev)
                }
            )
            if (cancelled.get()) break
            updateStatus("重试中 ($reason) · ${nowClock()}")
            sleepQuietly(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30000L)
        }
    }

    private fun triggerOneShotSync() {
        Thread({
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketNotify:Sync")
            try {
                wl.acquire(15_000L)
                val cfg = Storage.loadConfig(this)
                val res = RelayClient.fetchEvents(cfg, Storage.getLastSeenId(this))
                if (res.ok) {
                    for (ev in res.events) {
                        handleIncomingEvent(ev)
                    }
                    updateStatus("同步完成：${res.endpoint} (${nowClock()}, 新增 ${res.events.size})")
                } else {
                    updateStatus("同步失败：${res.error} (${nowClock()})")
                }
            } finally {
                if (wl.isHeld) wl.release()
                if (Storage.loadConfig(this).serviceEnabled) {
                    PollAlarmReceiver.scheduleNext(this)
                }
            }
        }, "PocketNotifySync").start()
    }

    private fun handleIncomingEvent(ev: NotifyEvent) {
        val isNew = Storage.recordEvent(this, ev)
        if (isNew) {
            Notifier.showEvent(this, ev)
            sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(packageName))
        }
    }

    private fun updateStatus(text: String) {
        Storage.setStatusText(this, text)
        Notifier.updateStatusNotification(this, text)
        sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(packageName))
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            // Wake immediately
        }
    }

    private fun nowClock(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    companion object {
        const val ACTION_START = "com.pocketnotify.app.ACTION_START"
        const val ACTION_STOP = "com.pocketnotify.app.ACTION_STOP"
        const val ACTION_SYNC_NOW = "com.pocketnotify.app.ACTION_SYNC_NOW"
        const val ACTION_UI_REFRESH = "com.pocketnotify.app.ACTION_UI_REFRESH"

        fun start(context: Context) {
            val i = Intent(context, RelayService::class.java).apply { action = ACTION_START }
            ContextCompat.startForegroundService(context, i)
        }

        fun syncNow(context: Context) {
            val i = Intent(context, RelayService::class.java).apply { action = ACTION_SYNC_NOW }
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, RelayService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }
}
