package com.esq.booster

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuHelper {

    /** True only when Shizuku binder is alive AND permission is granted */
    val isReady: Boolean
        get() = try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }

    /** True when Shizuku service is running (regardless of permission) */
    val isRunning: Boolean
        get() = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
}
