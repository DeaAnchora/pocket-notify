package com.pocketnotify.app

import org.json.JSONObject

data class NotifyEvent(
    val id: Long,
    val channel: String,
    val title: String,
    val body: String,
    val priority: String,
    val clickUrl: String,
    val createdAt: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("channel", channel)
        put("title", title)
        put("body", body)
        put("priority", priority)
        put("click_url", clickUrl)
        put("created_at", createdAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): NotifyEvent = NotifyEvent(
            id = obj.optLong("id", 0L),
            channel = obj.optString("channel", "default").ifBlank { "default" },
            title = obj.optString("title", ""),
            body = obj.optString("body", ""),
            priority = obj.optString("priority", "default"),
            clickUrl = obj.optString("click_url", ""),
            createdAt = obj.optString("created_at", "")
        )
    }
}

data class RelayConfig(
    val primaryUrl: String,
    val backupUrl: String,
    val token: String,
    val pollIntervalMinutes: Int,
    val serviceEnabled: Boolean,
    val hideFromRecents: Boolean = false,
    val rootModeEnabled: Boolean = false
) {
    fun candidateBaseUrls(): List<String> = listOf(primaryUrl, backupUrl)
        .map { it.trim().trimEnd('/') }
        .filter { it.startsWith("http://") || it.startsWith("https://") }
        .distinct()
}
