package com.pocketnotify.app

import android.content.Context
import org.json.JSONArray

object Storage {
    private const val PREFS_NAME = "pocket_notify_prefs"
    private const val KEY_PRIMARY_URL = "primary_url"
    private const val KEY_BACKUP_URL = "backup_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_POLL_MINUTES = "poll_minutes"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val KEY_HIDE_RECENTS = "hide_from_recents"
    private const val KEY_ROOT_MODE = "root_mode_enabled"
    private const val KEY_QUICK_WEB_URL = "quick_web_url"
    private const val KEY_LAST_SEEN_ID = "last_seen_id"
    private const val KEY_STATUS_TEXT = "status_text"
    private const val KEY_HISTORY = "history_json"
    private const val MAX_HISTORY = 80

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadConfig(context: Context): RelayConfig {
        val p = prefs(context)
        return RelayConfig(
            primaryUrl = p.getString(KEY_PRIMARY_URL, "") ?: "",
            backupUrl = p.getString(KEY_BACKUP_URL, "") ?: "",
            token = p.getString(KEY_TOKEN, "") ?: "",
            pollIntervalMinutes = p.getInt(KEY_POLL_MINUTES, 5).coerceIn(1, 60),
            serviceEnabled = p.getBoolean(KEY_SERVICE_ENABLED, false),
            hideFromRecents = p.getBoolean(KEY_HIDE_RECENTS, false),
            rootModeEnabled = p.getBoolean(KEY_ROOT_MODE, false),
            quickWebUrl = p.getString(KEY_QUICK_WEB_URL, "") ?: ""
        )
    }

    fun saveConfig(context: Context, config: RelayConfig) {
        prefs(context).edit()
            .putString(KEY_PRIMARY_URL, config.primaryUrl.trim().trimEnd('/'))
            .putString(KEY_BACKUP_URL, config.backupUrl.trim().trimEnd('/'))
            .putString(KEY_TOKEN, config.token.trim())
            .putInt(KEY_POLL_MINUTES, config.pollIntervalMinutes.coerceIn(1, 60))
            .putBoolean(KEY_SERVICE_ENABLED, config.serviceEnabled)
            .putBoolean(KEY_HIDE_RECENTS, config.hideFromRecents)
            .putBoolean(KEY_ROOT_MODE, config.rootModeEnabled)
            .putString(KEY_QUICK_WEB_URL, config.quickWebUrl.trim())
            .apply()
    }

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    fun setHideFromRecents(context: Context, hide: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_RECENTS, hide).apply()
    }

    fun setRootModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ROOT_MODE, enabled).apply()
    }

    @Synchronized
    fun getLastSeenId(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SEEN_ID, 0L)

    @Synchronized
    fun updateLastSeenId(context: Context, id: Long) {
        val current = getLastSeenId(context)
        if (id > current) {
            prefs(context).edit().putLong(KEY_LAST_SEEN_ID, id).apply()
        }
    }

    fun getStatusText(context: Context): String =
        prefs(context).getString(KEY_STATUS_TEXT, "未启动") ?: "未启动"

    fun setStatusText(context: Context, status: String) {
        prefs(context).edit().putString(KEY_STATUS_TEXT, status).apply()
    }

    @Synchronized
    fun recordEvent(context: Context, event: NotifyEvent): Boolean {
        val currentCursor = getLastSeenId(context)
        if (event.id > 0 && event.id <= currentCursor) {
            return false
        }
        val list = loadHistory(context).toMutableList()
        if (event.id > 0 && list.any { it.id == event.id }) {
            updateLastSeenId(context, event.id)
            return false
        }
        list.add(0, event)
        while (list.size > MAX_HISTORY) {
            list.removeAt(list.lastIndex)
        }
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit()
            .putString(KEY_HISTORY, arr.toString())
            .apply()
        if (event.id > 0) {
            updateLastSeenId(context, event.id)
        }
        return true
    }

    @Synchronized
    fun loadHistory(context: Context): List<NotifyEvent> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = mutableListOf<NotifyEvent>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                result.add(NotifyEvent.fromJson(item))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }
}
