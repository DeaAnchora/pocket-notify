package com.pocketnotify.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etPrimaryUrl: EditText
    private lateinit var etBackupUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etInterval: EditText
    private lateinit var llHistory: LinearLayout

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toast("请授予通知权限，否则无法弹出提醒")
        }
    }

    private val uiRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etPrimaryUrl = findViewById(R.id.etPrimaryUrl)
        etBackupUrl = findViewById(R.id.etBackupUrl)
        etToken = findViewById(R.id.etToken)
        etInterval = findViewById(R.id.etInterval)
        llHistory = findViewById(R.id.llHistory)

        val cfg = Storage.loadConfig(this)
        etPrimaryUrl.setText(cfg.primaryUrl)
        etBackupUrl.setText(cfg.backupUrl)
        etToken.setText(cfg.token)
        etInterval.setText(cfg.pollIntervalMinutes.toString())

        requestNotificationPermissionIfNeeded()

        findViewById<Button>(R.id.btnSaveStart).setOnClickListener {
            val updated = readFormConfig(serviceEnabled = true)
            if (updated.candidateBaseUrls().isEmpty() || updated.token.isBlank()) {
                toast("请至少填写一个 http(s):// 服务地址和 Token")
                return@setOnClickListener
            }
            Storage.saveConfig(this, updated)
            RelayService.start(this)
            toast("已保存并启动后台常驻服务")
            refreshUi()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            RelayService.stop(this)
            toast("后台服务已停止")
            refreshUi()
        }

        findViewById<Button>(R.id.btnTestConn).setOnClickListener {
            val current = readFormConfig(serviceEnabled = Storage.loadConfig(this).serviceEnabled)
            Storage.saveConfig(this, current)
            Thread {
                val res = RelayClient.checkHealth(current)
                runOnUiThread {
                    toast(res.detail)
                    if (res.ok) {
                        Storage.setStatusText(this, res.detail)
                        refreshUi()
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.btnSelfTest).setOnClickListener {
            val current = readFormConfig(serviceEnabled = Storage.loadConfig(this).serviceEnabled)
            Storage.saveConfig(this, current)
            Thread {
                val (ok, msg) = RelayClient.sendSelfTest(current)
                runOnUiThread {
                    toast(msg)
                    if (ok && current.serviceEnabled) {
                        RelayService.syncNow(this)
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            val current = readFormConfig(serviceEnabled = Storage.loadConfig(this).serviceEnabled)
            Storage.saveConfig(this, current)
            RelayService.syncNow(this)
            toast("已触发同步")
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        refreshUi()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            uiRefreshReceiver,
            IntentFilter(RelayService.ACTION_UI_REFRESH),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshUi()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(uiRefreshReceiver)
        } catch (_: Exception) {
        }
    }

    private fun readFormConfig(serviceEnabled: Boolean): RelayConfig {
        val mins = etInterval.text.toString().trim().toIntOrNull() ?: 5
        return RelayConfig(
            primaryUrl = etPrimaryUrl.text.toString().trim(),
            backupUrl = etBackupUrl.text.toString().trim(),
            token = etToken.text.toString().trim(),
            pollIntervalMinutes = mins.coerceIn(1, 60),
            serviceEnabled = serviceEnabled
        )
    }

    private fun refreshUi() {
        val status = Storage.getStatusText(this)
        val cursor = Storage.getLastSeenId(this)
        tvStatus.text = "状态：$status\n已同步游标：#$cursor"

        llHistory.removeAllViews()
        val history = Storage.loadHistory(this)
        if (history.isEmpty()) {
            val empty = TextView(this).apply {
                text = "暂无历史通知"
                setPadding(8, 12, 8, 12)
            }
            llHistory.addView(empty)
            return
        }

        for (ev in history) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 18, 24, 18)
                setBackgroundColor(Color.parseColor("#12000000"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 14 }
                setOnClickListener {
                    if (ev.clickUrl.startsWith("http://") || ev.clickUrl.startsWith("https://")) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ev.clickUrl)))
                    }
                }
            }
            val header = TextView(this).apply {
                text = "[${ev.channel}] ${ev.title}  (#${ev.id})"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val body = TextView(this).apply {
                text = ev.body
                textSize = 13f
                setPadding(0, 6, 0, 4)
            }
            val meta = TextView(this).apply {
                text = if (ev.clickUrl.isNotBlank()) "${ev.createdAt} · 点按打开链接" else ev.createdAt
                textSize = 11f
                setTextColor(Color.GRAY)
            }
            card.addView(header)
            card.addView(body)
            card.addView(meta)
            llHistory.addView(card)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("已处于忽略电池优化状态")
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
