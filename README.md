# Pocket Notify

A self-hosted, zero-cloud, general-purpose notification hub consisting of:

1. **Local Relay Server (`server/relay.py`)** — zero-dependency Python 3 HTTP + SSE + SQLite notification gateway running on your PC. Any local script, AI agent harness (Antigravity, Codex, dsh), or background job pushes notifications via one simple HTTP POST.
2. **Native Android Client (`android/`)** — lightweight Kotlin resident app designed for reliable delivery on aggressive battery-saving Android skins (e.g., Xiaomi HyperOS) without relying on FCM or third-party cloud push services.
   - Resident Foreground Service with live SSE stream
   - `AlarmManager.setExactAndAllowWhileIdle` periodic Doze-piercing backup sync
   - Dual endpoint support (Primary LAN URL + Backup Tailscale/Remote URL)
   - Dynamic per-channel Android notification channels + tap-to-open `click_url`

## Quick Start

### 1. Run the Local Relay Server

Copy `server/config.example.json` to a local runtime directory (do not commit secrets), set a strong random `token`, and start:

```powershell
python -X utf8 server/relay.py --config path/to/relay-config.json
```

### 2. Push a Notification from Any Service

```bash
curl -X POST http://127.0.0.1:18765/api/v1/push \
  -H "Authorization: Bearer <YOUR_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "tencent-mail",
    "title": "New Email",
    "body": "From: example@company.com\nSubject: Proposal Update",
    "priority": "high",
    "click_url": "https://antigravity.google.com",
    "dedup_key": "mail-unique-id-1"
  }'
```

### 3. API Endpoints

| Method | Path | Auth | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/health` | Bearer | Verify connectivity & server version |
| `POST` | `/api/v1/push` | Bearer | Enqueue & broadcast a notification event (supports optional `dedup_key`) |
| `GET` | `/api/v1/events?since_id=<N>&limit=<M>` | Bearer | Fetch events with `id > since_id` (ordered ascending) |
| `GET` | `/api/v1/stream?since_id=<N>` | Bearer | Server-Sent Events (SSE) live stream with 25s heartbeat |

### 4. Build Android APK

Every push to `main` triggers `.github/workflows/build-apk.yml` and publishes `pocket-notify-debug.apk` as a workflow artifact.

## Android Setup Tips (Xiaomi HyperOS)

1. Install the APK and grant **Notifications** permission.
2. Enter your PC's LAN URL (e.g. `http://192.168.x.x:18765`), optional Tailscale URL, and Bearer Token.
3. Tap **Test Connection**, then enable **Background Service**.
4. In Xiaomi Settings -> App Info -> Pocket Notify:
   - Battery saver: **No restrictions**
   - Autostart: **Enabled**
   - Lock the app in Recents.
