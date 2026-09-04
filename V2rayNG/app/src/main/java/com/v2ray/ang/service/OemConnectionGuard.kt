package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the foreground proxy responsive on Android variants that aggressively freeze background
 * processes. It intentionally lives in the existing daemon process instead of starting a second
 * proxy process, which would compete for the same VPN interface and core ports.
 */
class OemConnectionGuard(
    context: Context,
    private val isCoreRunning: () -> Boolean,
    private val hasUsableNetwork: () -> Boolean,
    private val isCoreReachable: () -> Boolean,
    private val reloadCore: () -> Boolean,
    manufacturer: String = Build.MANUFACTURER,
    brand: String = Build.BRAND,
) {
    companion object {
        internal const val SCREEN_OFF_CHECK_INTERVAL_MS = 90_000L
        internal const val MIN_CHECK_GAP_MS = 15_000L

        internal fun isSupportedVendor(manufacturer: String?, brand: String?): Boolean {
            val identity = listOfNotNull(manufacturer, brand)
                .joinToString(" ")
                .lowercase(Locale.ROOT)
            return listOf(
                "oppo", "oneplus", "realme", "vivo", "iqoo",
                "xiaomi", "redmi", "poco",
            ).any(identity::contains)
        }
    }

    private val appContext = context.applicationContext
    private val enabledForDevice = isSupportedVendor(manufacturer, brand)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val checking = AtomicBoolean(false)

    private var scope: CoroutineScope? = null
    private var periodicJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var screenOff = false
    @Volatile private var lastCheckAt = 0L

    fun start() {
        if (!enabledForDevice || scope != null) return

        screenOff = powerManager?.isInteractive == false
        acquireWakeLock()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        periodicJob = scope?.launch {
            while (isActive) {
                delay(SCREEN_OFF_CHECK_INTERVAL_MS)
                if (screenOff) checkNow("screen-off watchdog")
            }
        }
        LogUtil.i(AppConfig.TAG, "ConnectionGuard: enabled for ${Build.MANUFACTURER}/${Build.BRAND}")
    }

    fun onScreenOff() {
        screenOff = true
    }

    fun onScreenOn() {
        screenOff = false
        checkAsync("screen-on recovery")
    }

    fun stop() {
        periodicJob?.cancel()
        periodicJob = null
        scope?.cancel()
        scope = null
        checking.set(false)
        releaseWakeLock()
    }

    private fun checkAsync(reason: String) {
        scope?.launch { checkNow(reason) }
    }

    private fun checkNow(reason: String) {
        if (!isCoreRunning() || !hasUsableNetwork()) return

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCheckAt < MIN_CHECK_GAP_MS) return
        if (!checking.compareAndSet(false, true)) return
        lastCheckAt = now

        try {
            if (isCoreReachable()) return
            LogUtil.w(AppConfig.TAG, "ConnectionGuard: core is unreachable after $reason; reloading")
            reloadCore()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "ConnectionGuard: health check failed", e)
        } finally {
            checking.set(false)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val manager = powerManager ?: return
        try {
            wakeLock = manager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Freedom:OemConnectionGuard",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            wakeLock = null
            LogUtil.w(AppConfig.TAG, "ConnectionGuard: unable to acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "ConnectionGuard: unable to release wake lock: ${e.message}")
        } finally {
            wakeLock = null
        }
    }
}
