package com.pocketnotify.app

import android.Manifest
import android.app.ActivityManager
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
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvRootStatus: TextView
    private lateinit var etPrimaryUrl: EditText
    private lateinit var etBackupUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etInterval: EditText
    private lateinit var swHideRecents: SwitchMaterial
    private lateinit var swRootMode: SwitchMaterial
    private lateinit var llHistory: LinearLayout

    private var tokenVisible = false
    private var suppressRootSwitchCallback = false

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
        tvRootStatus = findViewById(R.id.tvRootStatus)
        etPrimaryUrl = findViewById(R.id.etPrimaryUrl)
        etBackupUrl = findViewById(R.id.etBackupUrl)
        etToken = findViewById(R.id.etToken)
        etInterval = findViewById(R.id.etInterval)
        swHideRecents = findViewById(R.id.swHideRecents)
        swRootMode = findViewById(R.id.swRootMode)
        llHistory = findViewById(R.id.llHistory)

        val cfg = Storage.loadConfig(this)
        etPrimaryUrl.setText(cfg.primaryUrl)
        etBackupUrl.setText(cfg.backupUrl)
        etToken.setText(cfg.token)
        etInterval.setText(cfg.pollIntervalMinutes.toString())
        swHideRecents.isChecked = cfg.hideFromRecents

        suppressRootSwitchCallback = true
        swRootMode.isChecked = cfg.rootModeEnabled
        suppressRootSwitchCallback = false

        applyExcludeFromRecents(cfg.hideFromRecents)
        requestNotificationPermissionIfNeeded()

        val btnToggleToken = findViewById<Button>(R.id.btnToggleToken)
        btnToggleToken.setOnClickListener {
            tokenVisible = !tokenVisible
            etToken.inputType = if (tokenVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            etToken.setSelection(etToken.text.length)
            btnToggleToken.text = if (tokenVisible) "隐藏" else "显示"
        }

        swHideRecents.setOnCheckedChangeListener { _, isChecked ->
            Storage.setHideFromRecents(this, isChecked)
            applyExcludeFromRecents(isChecked)
            toast(if (isChecked) "已从最近任务隐藏（随时可从桌面图标打开）" else "已恢复在最近任务中显示")
        }

        swRootMode.setOnCheckedChangeListener { _, isChecked ->
            if (suppressRootSwitchCallback) return@setOnCheckedChangeListener
            swRootMode.isEnabled = false
            tvRootStatus.text = if (isChecked) "正在申请 Root 并部署 KernelSU 守护..." else "正在关闭 Root 守护并清理脚本..."
            Thread {
                val (ok, msg) = if (isChecked) {
                    RootHelper.enableRootMode()
                } else {
                    RootHelper.disableRootMode()
                }
                runOnUiThread {
                    swRootMode.isEnabled = true
                    if (ok) {
                        Storage.setRootModeEnabled(this, isChecked)
                        tvRootStatus.text = msg
                        toast(msg)
                        if (isChecked) {
                            val current = readFormConfig(serviceEnabled = true)
                            if (current.candidateBaseUrls().isNotEmpty() && current.token.isNotBlank()) {
                                Storage.saveConfig(this, current)
                                RelayService.start(this)
                            }
                        }
                    } else {
                        suppressRootSwitchCallback = true
                        swRootMode.isChecked = !isChecked
                        suppressRootSwitchCallback = false
                        tvRootStatus.text = msg
                        toast(msg)
                    }
                    refreshUi()
                }
            }.start()
        }

        findViewById<Button>(R.id.btnCheckRoot).setOnClickListener {
            tvRootStatus.text = "正在检测 KernelSU Root 守护状态..."
            Thread {
                val status = RootHelper.inspectStatus()
                runOnUiThread {
                    tvRootStatus.text = status
                    toast(status)
                }
            }.start()
        }

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
            val wasRoot = Storage.loadConfig(this).rootModeEnabled
            RelayService.stop(this)
            toast(if (wasRoot) "后台服务已停止（注意：Root 守护开启时 60 秒内会自动拉活，彻底停止请先关 Root 模式）" else "后台服务已停止")
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

    override fun onResume() {
        super.onResume()
        applyExcludeFromRecents(Storage.loadConfig(this).hideFromRecents)
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

    private fun applyExcludeFromRecents(exclude: Boolean) {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { task ->
                task.setExcludeFromRecents(exclude)
            }
        } catch (_: Exception) {
        }
    }

    private fun readFormConfig(serviceEnabled: Boolean): RelayConfig {
        val mins = etInterval.text.toString().trim().toIntOrNull() ?: 5
        val existing = Storage.loadConfig(this)
        return RelayConfig(
            primaryUrl = etPrimaryUrl.text.toString().trim(),
            backupUrl = etBackupUrl.text.toString().trim(),
            token = etToken.text.toString().trim(),
            pollIntervalMinutes = mins.coerceIn(1, 60),
            serviceEnabled = serviceEnabled,
            hideFromRecents = swHideRecents.isChecked,
            rootModeEnabled = existing.rootModeEnabled
        )
    }

    private fun refreshUi() {
        val cfg = Storage.loadConfig(this)
        val modeLabel = if (cfg.rootModeEnabled) "Root 模式 (KernelSU)" else "基础模式"
        val status = Storage.getStatusText(this)
        val cursor = Storage.getLastSeenId(this)
        tvStatus.text = "模式：$modeLabel\n状态：$status\n已同步游标：#$cursor"

        if (!cfg.rootModeEnabled && tvRootStatus.text.startsWith("当前：")) {
            tvRootStatus.text = "当前：基础模式（无 Root 通用：前台服务 + 定时唤醒）"
        }

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
