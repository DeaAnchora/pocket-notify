package com.pocketnotify.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object RootHelper {
    private const val PKG = "com.pocketnotify.app"
    private const val SVC = "com.pocketnotify.app/.RelayService"
    private const val ACTION = "com.pocketnotify.app.ACTION_START"
    private const val SERVICE_D_DIR = "/data/adb/service.d"
    private const val WATCHDOG_PATH = "/data/adb/service.d/pocket_notify_watchdog.sh"
    private const val FLAG_PATH = "/data/adb/pocket_notify_root.enabled"
    private const val PID_PATH = "/data/adb/pocket_notify_watchdog.pid"

    data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runSu(command: String, timeoutSec: Long = 15L): ShellResult {
        return try {
            val proc = ProcessBuilder("su", "-c", command).start()
            val outBuf = StringBuilder()
            val errBuf = StringBuilder()
            val tOut = Thread {
                BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                    lines.forEach { outBuf.appendLine(it) }
                }
            }
            val tErr = Thread {
                BufferedReader(InputStreamReader(proc.errorStream)).useLines { lines ->
                    lines.forEach { errBuf.appendLine(it) }
                }
            }
            tOut.start()
            tErr.start()
            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return ShellResult(-1, outBuf.toString().trim(), "su command timed out")
            }
            tOut.join(1000)
            tErr.join(1000)
            ShellResult(proc.exitValue(), outBuf.toString().trim(), errBuf.toString().trim())
        } catch (e: Exception) {
            ShellResult(-1, "", "${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    fun hasRootAccess(): Boolean {
        val res = runSu("id -u", timeoutSec = 8L)
        return res.exitCode == 0 && res.stdout.trim() == "0"
    }

    private fun watchdogScriptContent(): String = """
#!/system/bin/sh
PKG="$PKG"
SVC="$SVC"
ACTION="$ACTION"
FLAG="$FLAG_PATH"
PIDFILE="$PID_PATH"
INTERVAL=60

if [ -f "${'$'}PIDFILE" ]; then
    OLD_PID=${'$'}(cat "${'$'}PIDFILE" 2>/dev/null)
    if [ -n "${'$'}OLD_PID" ] && [ -d "/proc/${'$'}OLD_PID" ]; then
        CMDLINE=${'$'}(tr '\0' ' ' < "/proc/${'$'}OLD_PID/cmdline" 2>/dev/null)
        case "${'$'}CMDLINE" in
            *pocket_notify_watchdog*) exit 0 ;;
        esac
    fi
fi
echo ${'$'}${'$'} > "${'$'}PIDFILE"

apply_policy() {
    dumpsys deviceidle whitelist +"${'$'}PKG" >/dev/null 2>&1
    cmd appops set "${'$'}PKG" RUN_IN_BACKGROUND allow >/dev/null 2>&1
    cmd appops set "${'$'}PKG" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
    cmd appops set "${'$'}PKG" START_FOREGROUND allow >/dev/null 2>&1
    cmd appops set "${'$'}PKG" SCHEDULE_EXACT_ALARM allow >/dev/null 2>&1
    am set-standby-bucket "${'$'}PKG" active >/dev/null 2>&1
}

protect_oom() {
    for p in ${'$'}(pidof "${'$'}PKG" 2>/dev/null); do
        if [ -w "/proc/${'$'}p/oom_score_adj" ]; then
            echo -900 > "/proc/${'$'}p/oom_score_adj" 2>/dev/null
        fi
    done
}

is_service_running() {
    dumpsys activity services "${'$'}SVC" 2>/dev/null | grep -q "ServiceRecord{.*${'$'}PKG/\.RelayService"
}

if [ "${'$'}(getprop sys.boot_completed)" != "1" ]; then
    while [ "${'$'}(getprop sys.boot_completed)" != "1" ]; do
        sleep 5
    done
    sleep 15
fi

apply_policy

while true; do
    if [ ! -f "${'$'}FLAG" ] || [ "${'$'}(cat "${'$'}FLAG" 2>/dev/null)" != "1" ]; then
        rm -f "${'$'}PIDFILE"
        exit 0
    fi

    if pm path "${'$'}PKG" >/dev/null 2>&1; then
        if ! is_service_running; then
            apply_policy
            am start-foreground-service -n "${'$'}SVC" -a "${'$'}ACTION" >/dev/null 2>&1 || \
            am startservice -n "${'$'}SVC" -a "${'$'}ACTION" >/dev/null 2>&1
            sleep 3
        fi
        protect_oom
    fi

    sleep "${'$'}INTERVAL"
done
""".trimStart()

    fun enableRootMode(): Pair<Boolean, String> {
        if (!hasRootAccess()) {
            return false to "未获取到 Root 权限，请先在 KernelSU 管理器里为 Pocket Notify 开启超级用户权限"
        }

        val script = watchdogScriptContent()
        val installCmd = """
            mkdir -p $SERVICE_D_DIR &&
            printf '1\n' > $FLAG_PATH &&
            cat << 'EOF_PN_WD' > $WATCHDOG_PATH
$script
EOF_PN_WD
            chmod 755 $WATCHDOG_PATH &&
            dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1;
            cmd appops set $PKG RUN_IN_BACKGROUND allow >/dev/null 2>&1;
            cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1;
            cmd appops set $PKG START_FOREGROUND allow >/dev/null 2>&1;
            cmd appops set $PKG SCHEDULE_EXACT_ALARM allow >/dev/null 2>&1;
            am set-standby-bucket $PKG active >/dev/null 2>&1;
            for p in $(pidof $PKG 2>/dev/null); do echo -900 > /proc/${'$'}p/oom_score_adj 2>/dev/null; done;
            if [ -f $PID_PATH ]; then
                OLD=$(cat $PID_PATH 2>/dev/null)
                [ -n "${'$'}OLD" ] && kill "${'$'}OLD" 2>/dev/null || true
                rm -f $PID_PATH
            fi
            nohup /system/bin/sh $WATCHDOG_PATH >/dev/null 2>&1 </dev/null &
            sleep 1
            [ -x $WATCHDOG_PATH ] && echo OK_INSTALLED
        """.trimIndent()

        val res = runSu(installCmd, timeoutSec = 15L)
        return if (res.exitCode == 0 && res.stdout.contains("OK_INSTALLED")) {
            true to "KernelSU Root 守护已安装并启动（开机自启 + 60秒巡检自动拉活 + 系统白名单 + OOM保护）"
        } else {
            false to "Root 安装失败 (exit=${res.exitCode}): ${res.stderr.ifBlank { res.stdout }}"
        }
    }

    fun disableRootMode(): Pair<Boolean, String> {
        if (!hasRootAccess()) {
            return false to "未获取到 Root 权限，无法清理 /data/adb/service.d 脚本"
        }
        val cmd = """
            printf '0\n' > $FLAG_PATH 2>/dev/null || true
            if [ -f $PID_PATH ]; then
                OLD=$(cat $PID_PATH 2>/dev/null)
                [ -n "${'$'}OLD" ] && kill "${'$'}OLD" 2>/dev/null || true
            fi
            rm -f $WATCHDOG_PATH $FLAG_PATH $PID_PATH
            echo OK_REMOVED
        """.trimIndent()
        val res = runSu(cmd, timeoutSec = 10L)
        return if (res.exitCode == 0 && res.stdout.contains("OK_REMOVED")) {
            true to "Root 模式已关闭，已移除 KernelSU 开机自启与守护脚本（切回基础模式）"
        } else {
            false to "清理 Root 守护失败: ${res.stderr.ifBlank { res.stdout }}"
        }
    }

    fun inspectStatus(): String {
        if (!hasRootAccess()) {
            return "Root 权限：未授权（如需启用 Root 模式，请在 KernelSU 中授权本应用）"
        }
        val cmd = """
            HAS_SCRIPT=$([ -x $WATCHDOG_PATH ] && echo yes || echo no)
            FLAG_VAL=$(cat $FLAG_PATH 2>/dev/null || echo 0)
            WD_ALIVE=no
            if [ -f $PID_PATH ]; then
                PID=$(cat $PID_PATH 2>/dev/null)
                if [ -n "${'$'}PID" ] && [ -d "/proc/${'$'}PID" ]; then
                    WD_ALIVE="yes(pid=${'$'}PID)"
                fi
            fi
            OOM=$(for p in $(pidof $PKG 2>/dev/null); do cat /proc/${'$'}p/oom_score_adj 2>/dev/null; break; done)
            echo "script=${'$'}HAS_SCRIPT flag=${'$'}FLAG_VAL watchdog=${'$'}WD_ALIVE oom=${'$'}{OOM:-n/a}"
        """.trimIndent()
        val res = runSu(cmd, timeoutSec = 10L)
        return if (res.exitCode == 0) {
            "Root 守护状态：${res.stdout}"
        } else {
            "Root 状态检测失败：${res.stderr}"
        }
    }
}
