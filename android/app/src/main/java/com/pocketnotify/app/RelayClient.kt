package com.pocketnotify.app

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object RelayClient {
    data class HealthResult(val ok: Boolean, val endpoint: String, val latestId: Long, val detail: String)
    data class FetchResult(val ok: Boolean, val endpoint: String, val events: List<NotifyEvent>, val error: String = "")

    private fun openConnection(urlStr: String, token: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection)
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    fun checkHealth(config: RelayConfig): HealthResult {
        val urls = config.candidateBaseUrls()
        if (urls.isEmpty() || config.token.isBlank()) {
            return HealthResult(false, "", 0L, "请先填写有效的服务地址和 Token")
        }
        var lastErr = "无法连接服务"
        for (base in urls) {
            var conn: HttpURLConnection? = null
            try {
                conn = openConnection("$base/api/v1/health", config.token, 5000, 6000)
                conn.requestMethod = "GET"
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                if (code == 200) {
                    val obj = JSONObject(text)
                    val latest = obj.optLong("latest_id", 0L)
                    val ver = obj.optString("version", "")
                    return HealthResult(true, base, latest, "连接成功 ($base, v$ver, latest_id=$latest)")
                } else {
                    lastErr = "$base 返回 HTTP $code"
                }
            } catch (e: Exception) {
                lastErr = "$base: ${e.javaClass.simpleName}: ${e.message ?: ""}"
            } finally {
                conn?.disconnect()
            }
        }
        return HealthResult(false, "", 0L, lastErr)
    }

    fun fetchEvents(config: RelayConfig, sinceId: Long): FetchResult {
        val urls = config.candidateBaseUrls()
        if (urls.isEmpty() || config.token.isBlank()) {
            return FetchResult(false, "", emptyList(), "未配置服务地址或 Token")
        }
        var lastErr = "无法拉取事件"
        for (base in urls) {
            var conn: HttpURLConnection? = null
            try {
                conn = openConnection("$base/api/v1/events?since_id=$sinceId&limit=50", config.token, 6000, 10000)
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code == 200) {
                    val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    val obj = JSONObject(text)
                    val arr = obj.optJSONArray("events")
                    val events = mutableListOf<NotifyEvent>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            events.add(NotifyEvent.fromJson(item))
                        }
                    }
                    return FetchResult(true, base, events)
                } else {
                    lastErr = "$base HTTP $code"
                }
            } catch (e: Exception) {
                lastErr = "${e.javaClass.simpleName}: ${e.message ?: ""}"
            } finally {
                conn?.disconnect()
            }
        }
        return FetchResult(false, "", emptyList(), lastErr)
    }

    fun sendSelfTest(config: RelayConfig): Pair<Boolean, String> {
        val urls = config.candidateBaseUrls()
        if (urls.isEmpty() || config.token.isBlank()) {
            return false to "请先填写有效的服务地址和 Token"
        }
        var lastErr = "发送测试失败"
        for (base in urls) {
            var conn: HttpURLConnection? = null
            try {
                conn = openConnection("$base/api/v1/push", config.token, 5000, 8000)
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val payload = JSONObject().apply {
                    put("channel", "system")
                    put("title", "Pocket Notify 测试推送")
                    put("body", "如果你在锁屏上看到这条通知，说明手机与本机中继网关已经彻底打通！")
                    put("priority", "high")
                }
                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                    it.write(payload.toString())
                    it.flush()
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    return true to "已向 $base 发送测试通知"
                }
                lastErr = "$base 返回 HTTP $code"
            } catch (e: Exception) {
                lastErr = "$base: ${e.javaClass.simpleName}"
            } finally {
                conn?.disconnect()
            }
        }
        return false to lastErr
    }

    /**
     * Opens a blocking SSE stream against the first reachable endpoint and delivers events
     * until cancelled or the connection closes / times out.
     */
    fun streamOnce(
        config: RelayConfig,
        sinceIdProvider: () -> Long,
        isCancelled: () -> Boolean,
        onConnected: (String) -> Unit,
        onEvent: (NotifyEvent) -> Unit
    ): String {
        val urls = config.candidateBaseUrls()
        if (urls.isEmpty() || config.token.isBlank()) {
            return "未配置服务地址或 Token"
        }
        var lastErr = "所有服务地址均不可达"
        for (base in urls) {
            if (isCancelled()) return "已停止"
            var conn: HttpURLConnection? = null
            try {
                val since = sinceIdProvider()
                conn = openConnection("$base/api/v1/stream?since_id=$since", config.token, 8000, 45000)
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.setRequestProperty("Cache-Control", "no-cache")
                val code = conn.responseCode
                if (code != 200) {
                    lastErr = "$base HTTP $code"
                    continue
                }
                onConnected(base)
                BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    val dataBuf = StringBuilder()
                    while (!isCancelled()) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("data:") -> {
                                if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                                dataBuf.append(line.substring(5).trimStart())
                            }
                            line.isEmpty() -> {
                                if (dataBuf.isNotEmpty()) {
                                    val jsonText = dataBuf.toString()
                                    dataBuf.setLength(0)
                                    try {
                                        val ev = NotifyEvent.fromJson(JSONObject(jsonText))
                                        onEvent(ev)
                                    } catch (_: Exception) {
                                        // Ignore malformed frame
                                    }
                                }
                            }
                            else -> {
                                // Heartbeat comment (": heartbeat") or event/id field
                            }
                        }
                    }
                }
                return "连接断开，准备重连 ($base)"
            } catch (e: Exception) {
                lastErr = "$base: ${e.javaClass.simpleName}"
            } finally {
                conn?.disconnect()
            }
        }
        return lastErr
    }
}
