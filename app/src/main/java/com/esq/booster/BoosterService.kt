package com.esq.booster

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class BoosterService : Service() {

    companion object {
        const val ACTION_START  = "com.esq.booster.START"
        const val ACTION_STOP   = "com.esq.booster.STOP"
        const val BROADCAST_FPS = "com.esq.booster.FPS"

        const val TARGET_FPS = 90

        // FIX B-07: was toInt()=67; rounded correctly to 68.
        // `fps in 1 until 68` triggers when fps is 1..67 (i.e. fps < 68).
        const val DROP_THRESHOLD = 68

        // FIX B-05: static flag lets MainActivity restore button state on recreate.
        @Volatile var isRunning = false
            private set

        private const val CHANNEL = "esq_ch"
        private const val NOTE_ID = 42
        private const val TAG     = "ESQ.Service"
    }

    private val monitor   = FpsMonitor()
    private val optimizer = SystemOptimizer()
    private val lbm by lazy { LocalBroadcastManager.getInstance(this) }

    // FIX B-06: named serviceScope instead of anonymous CoroutineScope —
    // properly lifecycle-managed and never leaked.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var job: Job? = null
    private var autoRecover = true
    private var recovering  = false

    private val eventLog = ArrayDeque<String>(8)

    // ─────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                autoRecover = intent.getBooleanExtra("auto_recover", true)
                startForeground(NOTE_ID, buildNote("Активен · Arena Breakout 90 FPS"))
                startLoop()
            }
            ACTION_STOP -> {
                stopLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            null -> {
                // FIX B-04: START_STICKY — system restarted us after being killed.
                // Re-apply boost with previous settings.
                startForeground(NOTE_ID, buildNote("Восстановлен · Arena Breakout 90 FPS"))
                startLoop()
            }
        }
        // FIX B-04: START_STICKY so system restarts service if killed during gameplay.
        return START_STICKY
    }

    override fun onDestroy() {
        // FIX B-08: onDestroy no longer calls stopLoop() directly.
        // stopLoop() was already called by ACTION_STOP handler.
        // If destroyed by system without ACTION_STOP, cancel job and schedule
        // async restore so we don't block the main thread (B-01 fix).
        if (job != null) {
            job?.cancel()
            job = null
            isRunning = false
            // FIX B-01: restoreDefaults runs on serviceScope IO thread, not main thread.
            serviceScope.launch {
                optimizer.restoreDefaults()
            }
        }
        // Give the restore coroutine a moment, then cancel scope.
        // Scope will also be GC'd naturally once restore coroutine completes.
        serviceScope.launch {
            delay(2_000)
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    // ─────────────────────────────────────────────
    // Boost loop
    // ─────────────────────────────────────────────

    private fun startLoop() {
        // FIX B-02: guard against duplicate loop if called while already active.
        if (job?.isActive == true) {
            Log.d(TAG, "startLoop() called but already active — skipped")
            return
        }

        isRunning = true  // FIX B-05

        job = serviceScope.launch {  // FIX B-06: uses serviceScope, not anonymous scope
            addLog("🚀 Применяю буст...")
            optimizer.applyBoost()
            addLog("✅ Буст применён (RAM · анимации · CPU · GPU)")

            while (isActive) {
                val fps = monitor.read()

                if (autoRecover && fps in 1 until DROP_THRESHOLD && !recovering) {
                    doRecovery(fps)
                }

                broadcast(fps)
                delay(500)
            }
        }
        Log.d(TAG, "loop started")
    }

    private fun stopLoop() {
        // FIX B-08: guard — prevents double restoreDefaults() if called twice.
        if (job == null) return

        job?.cancel()
        job = null
        isRunning = false  // FIX B-05

        // FIX B-01: restoreDefaults() runs on IO thread, not main thread.
        serviceScope.launch {
            optimizer.restoreDefaults()
            Log.d(TAG, "defaults restored")
        }
        Log.d(TAG, "loop stopped")
    }

    private suspend fun doRecovery(droppedFps: Int) {
        recovering = true
        addLog("⚡ FPS: $droppedFps → восстановление...")
        updateNote("Восстановление FPS ($droppedFps → цель $TARGET_FPS)")

        optimizer.applyBoost()
        delay(400)

        recovering = false
        addLog("✅ Восстановлено")
        updateNote("Активен · Arena Breakout ${TARGET_FPS} FPS")
        Log.d(TAG, "recovery complete, was at $droppedFps fps")
    }

    // ─────────────────────────────────────────────
    // Broadcast
    // ─────────────────────────────────────────────

    private fun broadcast(fps: Int) {
        val intent = Intent(BROADCAST_FPS).apply {
            putExtra("fps",        fps)
            putExtra("recovering", recovering)
            putExtra("last_log",   eventLog.firstOrNull() ?: "")
        }
        lbm.sendBroadcast(intent)
    }

    // ─────────────────────────────────────────────
    // Event log
    // ─────────────────────────────────────────────

    private fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        eventLog.addFirst("[$time] $msg")
        if (eventLog.size > 8) eventLog.removeLast()
    }

    // ─────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "ESQ Booster", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNote(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle("ESQ Booster")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNote(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, buildNote(text))
    }
}
