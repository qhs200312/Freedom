package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SecureStorage
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.root.RootLifecycle
import com.v2ray.ang.root.RootProxyManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.RuntimeDiagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean

/** A root route and its native core must be retired together before a replacement starts. */
class CoreRootService : Service(), ServiceControl {
    companion object {
        const val ACTION_STOP = "com.v2ray.ang.action.ROOT_STOP"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lifecycle = RootLifecycle<CoreRootService>()
    }

    private var setupJob: Job? = null
    private val stopping = AtomicBoolean(false)
    private val teardownStarted = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)
    override fun isStopRequested(): Boolean = stopping.get()
    override fun isStarting(): Boolean = !stopping.get() && setupJob?.isActive == true

    override fun onCreate() {
        super.onCreate()
        if (!SecureStorage.ensureAvailable(this)) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SecureStorage.ensureAvailable(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        NotificationManager.ensureForeground(this)
        if (intent?.action == ACTION_STOP) {
            restartRequested.set(false)
            stopService()
            return START_NOT_STICKY
        }
        if (stopping.get()) {
            restartRequested.set(true)
            return START_NOT_STICKY
        }
        if (setupJob?.isActive == true) return START_NOT_STICKY
        if (lifecycle.isOwner(this) && CoreServiceManager.isServiceReady()) return START_NOT_STICKY

        setupJob = scope.launch {
            lifecycle.start(this@CoreRootService, { stopping.get() }, { previous -> previous.shutdownOwned() }) {
                val startTime = SystemClock.elapsedRealtime()
                CoreServiceManager.serviceControl = SoftReference(this@CoreRootService)
                try {
                    RootProxyManager.stop(this@CoreRootService)
                    checkActive()
                    SettingsManager.refreshRuntimeSocksPort()
                    check(CoreServiceManager.startCoreLoop(null, notifyStartSuccess = false)) {
                        "Root core failed to start"
                    }
                    checkActive()
                    // Public-site reachability is measured by the separate exit/route checks.
                    check(RootProxyManager.start(this@CoreRootService, cleanupBeforeStart = false)) {
                        "Root routing setup failed"
                    }
                    checkActive()
                    check(CoreServiceManager.markDataPathReady(this@CoreRootService)) { "Root session was superseded" }
                    NotificationManager.ensureForeground(this@CoreRootService)
                    MessageHelper.sendMsg2UI(this@CoreRootService, AppConfig.MSG_STATE_START_SUCCESS, "")
                    RuntimeDiagnostics.core(this@CoreRootService,
                        "root_ready mode=${SettingsManager.getProxyMode()} elapsedMs=${SystemClock.elapsedRealtime() - startTime}")
                } catch (error: Exception) {
                    if (!stopping.get()) {
                        LogUtil.e(AppConfig.TAG, "Root startup failed", error)
                        MessageHelper.sendMsg2UI(this@CoreRootService, AppConfig.MSG_STATE_START_FAILURE,
                            getString(R.string.toast_services_failure))
                    }
                    stopping.set(true)
                    shutdownOwned()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun checkActive() {
        check(!stopping.get()) { "Root service is stopping" }
    }

    /** Called only under the process-wide lifecycle lock. */
    private suspend fun shutdownOwned() {
        stopping.set(true)
        try {
            RootProxyManager.stop(this)
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Root route cleanup failed", error)
        } finally {
            val completed = CompletableDeferred<Unit>()
            CoreServiceManager.stopCoreLoop(this, onFinished = { completed.complete(Unit) })
            completed.await()
        }
    }

    override fun stopService() {
        stopping.set(true)
        if (!teardownStarted.compareAndSet(false, true)) return
        scope.launch {
            // Finish a blocking root script before tearing down its installed route.
            lifecycle.stop(this@CoreRootService, cleanupIfUnowned = CoreServiceManager.serviceControl?.get() == null) {
                shutdownOwned()
                NotificationManager.cancelNotification(this@CoreRootService)
                MessageHelper.sendMsg2UI(this@CoreRootService, AppConfig.MSG_STATE_STOP_SUCCESS, "")
            }
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isTransientShortcutTask(rootIntent)) {
            restartRequested.set(false)
            stopService()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopService()
        super.onDestroy()
        if (restartRequested.getAndSet(false)) {
            Handler(Looper.getMainLooper()).post {
                com.v2ray.ang.core.LauncherManager.startService(applicationContext)
            }
        }
    }

    override fun getService(): Service = this
    override fun startService() = Unit
    override fun vpnProtect(socket: Int): Boolean = true
    override fun onBind(intent: Intent?): IBinder? = null
    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let { MyContextWrapper.wrap(it, SettingsManager.getLocale()) })
    }
}
