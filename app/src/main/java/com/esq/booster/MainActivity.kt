package com.esq.booster

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_CODE = 100
    }

    // Views
    private lateinit var tvFps:        TextView
    private lateinit var tvStatus:     TextView
    private lateinit var tvCpu:        TextView
    private lateinit var tvRam:        TextView
    private lateinit var tvRecovery:   TextView
    private lateinit var tvLog:        TextView
    private lateinit var progressFps:  ProgressBar
    private lateinit var btnBoost:     Button
    private lateinit var switchRecover: SwitchCompat

    private val optimizer = SystemOptimizer()
    private var lastStatRefreshMs = 0L

    // FIX B-03: track last log entry sent to UI — skip broadcast if unchanged.
    private var lastDisplayedLog = ""

    // FIX B-11: runtime notification permission launcher (Android 13+).
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this,
                "Уведомления отключены — фоновый буст не будет виден в шторке",
                Toast.LENGTH_LONG).show()
        }
    }

    // ─────────────────────────────────────────────
    // Shizuku permission listener
    // ─────────────────────────────────────────────

    private val shizukuPermResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == SHIZUKU_CODE && result == PackageManager.PERMISSION_GRANTED) {
            onShizukuReady()
        } else {
            setStatus("❌ Shizuku: разрешение отклонено")
        }
    }

    // ─────────────────────────────────────────────
    // Broadcast receiver: FPS updates from BoosterService
    // ─────────────────────────────────────────────

    private val fpsReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val fps      = intent.getIntExtra("fps", 0)
            val recover  = intent.getBooleanExtra("recovering", false)
            val lastLog  = intent.getStringExtra("last_log") ?: ""
            updateFpsUI(fps, recover, lastLog)
        }
    }

    // ─────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        requestNotificationPermission()   // FIX B-11
        setupShizuku()

        // FIX B-05: restore button state from static service flag on any recreate.
        syncButtonState()

        btnBoost.setOnClickListener {
            if (!BoosterService.isRunning) startBoost() else stopBoost()
        }

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(fpsReceiver, IntentFilter(BoosterService.BROADCAST_FPS))
    }

    override fun onResume() {
        super.onResume()
        // FIX B-05: re-sync on every resume (back from game, etc.)
        syncButtonState()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermResult)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(fpsReceiver)
    }

    // ─────────────────────────────────────────────
    // View binding
    // ─────────────────────────────────────────────

    private fun bindViews() {
        tvFps         = findViewById(R.id.tv_fps)
        tvStatus      = findViewById(R.id.tv_status)
        tvCpu         = findViewById(R.id.tv_cpu)
        tvRam         = findViewById(R.id.tv_ram)
        tvRecovery    = findViewById(R.id.tv_recovery)
        tvLog         = findViewById(R.id.tv_log)
        progressFps   = findViewById(R.id.progress_fps)
        btnBoost      = findViewById(R.id.btn_boost)
        switchRecover = findViewById(R.id.switch_auto_recover)
    }

    // ─────────────────────────────────────────────
    // FIX B-11: runtime POST_NOTIFICATIONS (Android 13 / API 33+)
    // ─────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ─────────────────────────────────────────────
    // Shizuku setup
    // ─────────────────────────────────────────────

    private fun setupShizuku() {
        Shizuku.addRequestPermissionResultListener(shizukuPermResult)

        if (!ShizukuHelper.isRunning) {
            setStatus("⚠ Shizuku не запущен")
            return
        }
        if (ShizukuHelper.isReady) {
            onShizukuReady()
        } else {
            try {
                Shizuku.requestPermission(SHIZUKU_CODE)
            } catch (e: Exception) {
                setStatus("⚠ Ошибка Shizuku: ${e.message}")
            }
        }
    }

    private fun onShizukuReady() {
        setStatus("✅ Shizuku подключён")
        refreshStats()
    }

    // ─────────────────────────────────────────────
    // FIX B-05: single source of truth — BoosterService.isRunning
    // ─────────────────────────────────────────────

    private fun syncButtonState() {
        if (BoosterService.isRunning) {
            btnBoost.text = "■  СТОП"
            btnBoost.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.fps_bad)
        } else {
            btnBoost.text = "▶  BOOST"
            btnBoost.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.card_bg)
        }
    }

    // ─────────────────────────────────────────────
    // Boost control
    // ─────────────────────────────────────────────

    private fun startBoost() {
        if (!ShizukuHelper.isReady) {
            setStatus("⚠ Shizuku не готов")
            return
        }

        startForegroundService(
            Intent(this, BoosterService::class.java).apply {
                action = BoosterService.ACTION_START
                putExtra("auto_recover", switchRecover.isChecked)
            }
        )
        syncButtonState()  // reflects BoosterService.isRunning = true set in service
    }

    private fun stopBoost() {
        startService(Intent(this, BoosterService::class.java).apply {
            action = BoosterService.ACTION_STOP
        })

        // Reset FPS UI immediately — service will stop broadcasting.
        tvFps.text = "--"
        tvFps.setTextColor(ContextCompat.getColor(this, R.color.esq_blue))
        progressFps.progress = 0
        tvRecovery.text = ""
        lastDisplayedLog = ""          // FIX B-03: reset log dedupe state
        tvLog.text = "— ожидание —"

        syncButtonState()
    }

    // ─────────────────────────────────────────────
    // UI update from broadcast
    // ─────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun updateFpsUI(fps: Int, recovering: Boolean, lastLog: String) {
        // FPS number
        tvFps.text = if (fps > 0) fps.toString() else "--"

        // Progress bar
        progressFps.progress =
            if (fps > 0) (fps * 100 / BoosterService.TARGET_FPS).coerceIn(0, 100) else 0

        // Color indicator
        val colorRes = when {
            fps >= 80 -> R.color.fps_good
            fps >= 60 -> R.color.fps_warning
            fps >  0  -> R.color.fps_bad
            else      -> R.color.esq_blue
        }
        tvFps.setTextColor(ContextCompat.getColor(this, colorRes))

        // Recovery indicator
        tvRecovery.text = if (recovering) "🔄 Восстановление FPS..." else ""

        // FIX B-03: only append to log when the entry actually changes.
        if (lastLog.isNotEmpty() && lastLog != lastDisplayedLog) {
            lastDisplayedLog = lastLog
            val prev = tvLog.text.toString().let {
                if (it == "— ожидание —") "" else it
            }
            val merged = (lastLog + if (prev.isNotEmpty()) "\n$prev" else "")
                .lines().take(7).joinToString("\n")
            tvLog.text = merged
        }

        // Refresh system stats every 10 seconds
        val now = System.currentTimeMillis()
        if (now - lastStatRefreshMs > 10_000L) {
            lastStatRefreshMs = now
            refreshStats()
        }
    }

    private fun refreshStats() {
        lifecycleScope.launch(Dispatchers.IO) {
            val gov = optimizer.readGovernor()
            val ram = optimizer.getFreeRam(this@MainActivity)
            withContext(Dispatchers.Main) {
                tvCpu.text = gov
                tvRam.text = "$ram MB"
            }
        }
    }

    private fun setStatus(s: String) { tvStatus.text = s }
}
