# Pocket Notify

A self-hosted, zero-cloud, general-purpose notification hub consisting of:

1. **Local Relay Server (`server/relay.py`)** — zero-dependency Python 3 HTTP + SSE + SQLite notification gateway running on your PC. Any local script, AI agent harness, or background job pushes notifications via one simple HTTP POST.
2. **Native Android Client (`android/`)** — lightweight Kotlin resident app with two keep-alive modes and no dependency on FCM or third-party cloud push services:
   - **Basic Mode (No Root)**: Resident Foreground Service (live SSE stream) + `AlarmManager.setExactAndAllowWhileIdle` Doze-piercing backup sync + battery optimization exemption.
   - **Root Mode (KernelSU / Magisk `su`)**: Installs a transparent watchdog script to `/data/adb/service.d/pocket_notify_watchdog.sh` (source also in [`ksu/pocket_notify_watchdog.sh`](ksu/pocket_notify_watchdog.sh)) providing:
     - True boot-time autostart via KernelSU `service.d`
     - Automatic system Doze whitelist, `appops` background allow, and `active` standby bucket
     - 60-second lightweight watchdog that detects if `RelayService` is stopped and automatically restarts it
     - Process `oom_score_adj = -900` protection against LMKD kills
   - **Hide from Recents Toggle**: Optional switch (`setExcludeFromRecents`) to hide the app from Android's Recents screen so it cannot be accidentally swiped away.
   - **Dual Endpoints & Dynamic Channels**: Primary LAN URL + Backup P2P/VPN URL (e.g., Tailscale), dynamic per-channel Android notification channels, and tap-to-open `click_url`.

## Quick Start

### 1. Run the Local Relay Server

Copy `server/config.example.json` to a local runtime directory (do not commit secrets), set a strong random `token`, and start:

```bash
python server/relay.py --config path/to/relay-config.json
```

### 2. Push a Notification from Any Service

```bash
curl -X POST http://127.0.0.1:18765/api/v1/push \
  -H "Authorization: Bearer <YOUR_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "mail",
    "title": "New Email",
    "body": "From: sender@example.com\nSubject: Project Update",
    "priority": "high",
    "click_url": "https://example.com",
    "dedup_key": "unique-event-id-1"
  }'
```

### 3. API Endpoints

| Method | Path | Auth | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/health` | Bearer | Verify connectivity & server version |
| `POST` | `/api/v1/push` | Bearer | Enqueue & broadcast a notification event (supports optional `dedup_key`) |
| `GET` | `/api/v1/events?since_id=<N>&limit=<M>` | Bearer | Fetch events with `id > since_id` (ordered ascending) |
| `GET` | `/api/v1/stream?since_id=<N>` | Bearer | Server-Sent Events (SSE) live stream with 25s heartbeat |

### 4. Build Android APK & Optional Stable Signing

Every push to `main` triggers `.github/workflows/build-apk.yml`, runs server unit tests, builds the Android APK, verifies the launchable activity via `aapt2`, and publishes `pocket-notify-debug-apk`.

To sign CI builds with a consistent certificate (so updates can be installed directly over previous versions without uninstalling), add these optional repository secrets:

- `ANDROID_KEYSTORE_B64` (base64-encoded PKCS12 `.p12` keystore)
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`

When omitted, Gradle automatically falls back to the standard debug keystore.

## Android Setup

### Basic Mode (Non-Root)
1. Install the APK and grant **Notifications** permission.
2. Enter your PC's LAN URL (e.g. `http://192.168.x.x:18765`), optional Tailscale URL, and Bearer Token.
3. Tap **Test Connection** -> **Ignore Battery Optimizations** -> **Save & Start**.
4. Optionally enable **Hide from Recents** to prevent accidental swipe-to-close.

### Root Mode (KernelSU)
1. Open **KernelSU Manager** and grant Superuser permission to **Pocket Notify** (`com.pocketnotify.app`).
2. Inside Pocket Notify, enable **Root Mode (KernelSU)**.
3. Tap **Check Root Watchdog** to verify `/data/adb/service.d/pocket_notify_watchdog.sh` and the active watchdog PID.
4. Turning off the switch cleanly stops the watchdog and removes all `/data/adb/` files installed by the app.
