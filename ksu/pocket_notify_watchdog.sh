#!/system/bin/sh
# Pocket Notify KernelSU boot autostart & keep-alive watchdog
# Installed to /data/adb/service.d/pocket_notify_watchdog.sh by Pocket Notify Root Mode.

PKG="com.pocketnotify.app"
SVC="com.pocketnotify.app/.RelayService"
ACTION="com.pocketnotify.app.ACTION_START"
FLAG="/data/adb/pocket_notify_root.enabled"
PIDFILE="/data/adb/pocket_notify_watchdog.pid"
INTERVAL=60

# Single-instance guard
if [ -f "$PIDFILE" ]; then
    OLD_PID=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$OLD_PID" ] && [ -d "/proc/$OLD_PID" ]; then
        CMDLINE=$(tr '\0' ' ' < "/proc/$OLD_PID/cmdline" 2>/dev/null)
        case "$CMDLINE" in
            *pocket_notify_watchdog*) exit 0 ;;
        esac
    fi
fi
echo $$ > "$PIDFILE"

apply_policy() {
    dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1
    cmd appops set "$PKG" RUN_IN_BACKGROUND allow >/dev/null 2>&1
    cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
    cmd appops set "$PKG" START_FOREGROUND allow >/dev/null 2>&1
    cmd appops set "$PKG" SCHEDULE_EXACT_ALARM allow >/dev/null 2>&1
    am set-standby-bucket "$PKG" active >/dev/null 2>&1
}

protect_oom() {
    for p in $(pidof "$PKG" 2>/dev/null); do
        if [ -w "/proc/$p/oom_score_adj" ]; then
            echo -900 > "/proc/$p/oom_score_adj" 2>/dev/null
        fi
    done
}

is_service_running() {
    dumpsys activity services "$SVC" 2>/dev/null | grep -q "ServiceRecord{.*$PKG/\.RelayService"
}

# Wait for Android boot completion (only delay when actually booting)
if [ "$(getprop sys.boot_completed)" != "1" ]; then
    while [ "$(getprop sys.boot_completed)" != "1" ]; do
        sleep 5
    done
    sleep 15
fi

apply_policy

while true; do
    if [ ! -f "$FLAG" ] || [ "$(cat "$FLAG" 2>/dev/null)" != "1" ]; then
        rm -f "$PIDFILE"
        exit 0
    fi

    if pm path "$PKG" >/dev/null 2>&1; then
        if ! is_service_running; then
            apply_policy
            am start-foreground-service -n "$SVC" -a "$ACTION" >/dev/null 2>&1 || \
            am startservice -n "$SVC" -a "$ACTION" >/dev/null 2>&1
            sleep 3
        fi
        protect_oom
    fi

    sleep "$INTERVAL"
done
