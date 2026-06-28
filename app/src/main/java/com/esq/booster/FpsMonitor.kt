package com.esq.booster

class FpsMonitor {

    companion object {
        // Arena Breakout package name.
        // If your region uses a different variant (e.g. .na), change here.
        const val AB_PACKAGE = "com.proximabeta.mf.uamo"
    }

    private val optimizer = SystemOptimizer()

    /**
     * Returns current FPS of Arena Breakout.
     * Returns 0 if the game is not in the foreground or data is unavailable.
     * Must be called from a background thread.
     */
    fun read(): Int {
        val fps = fromGfxInfo()
        if (fps > 0) return fps
        return fromSurfaceFlinger() // fallback
    }

    // ─────────────────────────────────────────────
    // Primary: gfxinfo framestats
    // Works as ADB shell (Shizuku any mode)
    // Returns per-frame VSync timestamps for last 120 frames
    // ─────────────────────────────────────────────

    private fun fromGfxInfo(): Int {
        val out = optimizer.exec("dumpsys gfxinfo $AB_PACKAGE framestats 2>/dev/null")
        if (out.isBlank()) return 0

        val start = out.indexOf("---PROFILEDATA---")
        val end   = out.indexOf("---END---", if (start >= 0) start else 0)
        if (start < 0 || end <= start) return 0

        // Lines after header: Flags,IntendedVsync,Vsync,...
        // IntendedVsync = column index 1 (0-based)
        val lines = out.substring(start + 17, end).trim().split("\n")
        if (lines.size < 3) return 0 // header + at least 2 data rows needed

        val timestamps = mutableListOf<Long>()
        for (i in 1 until lines.size) {
            val cols = lines[i].trim().split(",")
            if (cols.size > 1) {
                val v = cols[1].trim().toLongOrNull()
                if (v != null && v > 0) timestamps.add(v)
            }
        }
        return calcFps(timestamps)
    }

    // ─────────────────────────────────────────────
    // Fallback: SurfaceFlinger latency
    // ─────────────────────────────────────────────

    private fun fromSurfaceFlinger(): Int {
        // Try the most common layer name pattern first
        val layerPatterns = listOf(
            "SurfaceView[$AB_PACKAGE]",
            "SurfaceView - $AB_PACKAGE"
        )
        for (pattern in layerPatterns) {
            val out = optimizer.exec(
                "dumpsys SurfaceFlinger --latency \"$pattern\" 2>/dev/null"
            )
            if (out.isBlank()) continue

            val lines = out.trim().split("\n")
            if (lines.size < 3) continue

            // Line 0 = refresh period (ns), lines 1+ = timestamp triplets
            val timestamps = mutableListOf<Long>()
            for (i in 1 until lines.size) {
                val cols = lines[i].trim().split("\\s+".toRegex())
                if (cols.size >= 2) {
                    val v = cols[1].toLongOrNull()
                    if (v != null && v != Long.MAX_VALUE && v > 0) timestamps.add(v)
                }
            }
            val fps = calcFps(timestamps)
            if (fps > 0) return fps
        }
        return 0
    }

    // ─────────────────────────────────────────────
    // FPS calculation from nanosecond timestamps
    // ─────────────────────────────────────────────

    private fun calcFps(ts: List<Long>): Int {
        if (ts.size < 2) return 0
        val sorted = ts.sorted()
        val diffs = mutableListOf<Long>()
        for (i in 1 until sorted.size) {
            val d = sorted[i] - sorted[i - 1]
            // Sanity: 4ms (250fps max) to 100ms (10fps min)
            if (d in 4_000_000L..100_000_000L) diffs.add(d)
        }
        if (diffs.isEmpty()) return 0
        val avgNs = diffs.average()
        return (1_000_000_000.0 / avgNs).toInt().coerceIn(1, 120)
    }
}
