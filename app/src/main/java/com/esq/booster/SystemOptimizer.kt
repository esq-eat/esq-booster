package com.esq.booster

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class SystemOptimizer {

    companion object {
        private const val TAG          = "ESQ.Optimizer"
        private const val EXEC_TIMEOUT = 5L // seconds
    }

    // ─────────────────────────────────────────────
    // Shell execution via Shizuku
    // FIX B-09: stderr now drained concurrently → no pipe-buffer deadlock
    // FIX B-10: waitFor with timeout → no infinite hang
    // ─────────────────────────────────────────────

    fun exec(cmd: String): String {
        return try {
            if (!ShizukuHelper.isReady) return ""

            val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)

            // Drain stderr on a separate thread to prevent 64KB buffer deadlock
            val stderrThread = Thread {
                try { p.errorStream.use { it.readBytes() } } catch (_: Exception) {}
            }.also { it.isDaemon = true; it.start() }

            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }

            val finished = p.waitFor(EXEC_TIMEOUT, TimeUnit.SECONDS)
            if (!finished) {
                p.destroy()
                Log.w(TAG, "exec timeout: $cmd")
            }

            stderrThread.join(500)
            out
        } catch (e: Exception) {
            Log.w(TAG, "exec failed [${e.javaClass.simpleName}]: ${e.message}")
            ""
        }
    }

    // ─────────────────────────────────────────────
    // Boost — applied on start and on FPS drop
    // ─────────────────────────────────────────────

    fun applyBoost() {
        // 1. Kill background processes (ADB shell — works in any Shizuku mode)
        exec("am kill-all")

        // 2. Disable animations (ADB shell)
        exec("settings put global window_animation_scale 0.0")
        exec("settings put global transition_animation_scale 0.0")
        exec("settings put global animator_duration_scale 0.0")

        // 3. CPU → performance (requires root Shizuku; silently fails otherwise)
        setCpuGovernor("performance")

        // 4. GPU → performance (requires root Shizuku; tries 7 common driver paths)
        setGpuGovernor("performance")

        // 5. Raise Arena Breakout process priority (ADB shell)
        exec("renice -n -10 \$(pidof ${FpsMonitor.AB_PACKAGE} 2>/dev/null) 2>/dev/null || true")

        // 6. Disable MSM thermal throttle (root Shizuku only)
        exec("echo 0 > /sys/module/msm_thermal/parameters/enabled 2>/dev/null || true")

        Log.d(TAG, "boost applied")
    }

    fun restoreDefaults() {
        exec("settings put global window_animation_scale 1.0")
        exec("settings put global transition_animation_scale 1.0")
        exec("settings put global animator_duration_scale 1.0")
        setCpuGovernor("schedutil")
        setGpuGovernor("simple_ondemand")
        Log.d(TAG, "defaults restored")
    }

    // ─────────────────────────────────────────────
    // CPU governor
    // ─────────────────────────────────────────────

    fun setCpuGovernor(gov: String) {
        exec("""
            for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_governor; do
                echo $gov > ${'$'}f 2>/dev/null
            done
        """.trimIndent())
    }

    fun readGovernor(): String =
        exec("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null")
            .trim()
            .ifEmpty { "N/A" }

    // ─────────────────────────────────────────────
    // GPU governor — 7 common driver paths
    // ─────────────────────────────────────────────

    fun setGpuGovernor(gov: String) {
        val paths = listOf(
            "/sys/class/devfreq/soc:qcom,gpubw/governor",
            "/sys/class/devfreq/gpubw/governor",
            "/sys/kernel/gpu/gpu_governor",
            "/sys/devices/platform/kgsl-3d0.0/kgsl/kgsl-3d0/devfreq/governor",
            "/sys/bus/platform/drivers/kgsl/kgsl-3d0/devfreq/governor",
            "/sys/class/misc/mali0/device/devfreq/governor",
            "/sys/devices/platform/13000000.mali/devfreq/13000000.mali/governor"
        )
        paths.forEach { exec("echo $gov > $it 2>/dev/null") }
    }

    // ─────────────────────────────────────────────
    // RAM (no Shizuku required)
    // ─────────────────────────────────────────────

    fun getFreeRam(ctx: Context): Long {
        val info = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.availMem / 1_048_576L
    }
}
