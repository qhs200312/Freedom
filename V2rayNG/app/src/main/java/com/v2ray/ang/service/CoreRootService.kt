package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.root.RootProxyManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MyContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.ref.SoftReference

/**
 * Foreground service for the root (system-wide) run modes. Unlike [CoreVpnService] it
 * does not use Android VpnService — traffic is routed by iptables instead
 * (see [RootProxyManager]).
 *
 * The in-process core is started first (so its listener is up and the foreground
 * notification is posted promptly), then the root routing rules are installed off the
 * main thread. On teardown the rules are removed before the core stops.
 */
class CoreRootService : Service(), ServiceControl {

    private var setupJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Root: Service created")
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        LogUtil.i(AppConfig.TAG, "StartCore-Root: command received")

        // Foreground-service intents can be delivered twice (rapid taps, sticky restarts). A
        // duplicate is not a core startup failure and must not tear down a healthy root tunnel.
        if (CoreServiceManager.isRunning() || setupJob?.isActive == true) {
            LogUtil.i(AppConfig.TAG, "StartCore-Root: duplicate start ignored")
            return START_STICKY
        }

        // Start the in-process core first (this also posts the foreground notification),
        // then install the root routing off the main thread.
        if (!CoreServiceManager.startCoreLoop(null, notifyStartSuccess = false)) {
            LogUtil.e(AppConfig.TAG, "StartCore-Root: core failed to start")
            stopService()
            return START_NOT_STICKY
        }

        setupJob = CoroutineScope(Dispatchers.IO).launch {
            // A killed root-service process or an APK update can leave its root helper and
            // policy rules alive. Remove that stale data path before probing the new core;
            // otherwise the probe period itself can leave the whole device offline.
            RootProxyManager.stop(this@CoreRootService)

            var coreReady = false
            for (attempt in 1..2) {
                if (attempt > 1) delay(750L)
                if (CoreServiceManager.isCoreReachable()) {
                    coreReady = true
                    break
                }
                LogUtil.w(AppConfig.TAG, "StartCore-Root: connectivity check $attempt failed")
            }
            if (!coreReady) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: core has no working outbound, stopping")
                MessageHelper.sendMsg2UI(
                    this@CoreRootService,
                    AppConfig.MSG_STATE_START_FAILURE,
                    getString(com.v2ray.ang.R.string.toast_services_failure)
                )
                stopAfterBackgroundFailure()
                return@launch
            }

            var started = false
            for (attempt in 1..2) {
                if (attempt > 1) delay(500L)
                if (RootProxyManager.start(this@CoreRootService)) {
                    started = true
                    break
                }
                LogUtil.w(AppConfig.TAG, "StartCore-Root: root setup attempt $attempt failed")
            }

            if (started) {
                MessageHelper.sendMsg2UI(
                    this@CoreRootService,
                    AppConfig.MSG_STATE_START_SUCCESS,
                    ""
                )
                LogUtil.i(AppConfig.TAG, "StartCore-Root: root mode ready")

            } else {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: failed to start root mode, stopping")
                MessageHelper.sendMsg2UI(
                    this@CoreRootService,
                    AppConfig.MSG_STATE_START_FAILURE,
                    getString(com.v2ray.ang.R.string.toast_services_failure)
                )
                stopAfterBackgroundFailure()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Wait for any in-flight async setup to finish before tearing down. The rules are
        // installed off the main thread and can take seconds (the setup script waits for the
        // tun to appear); if a stop arrives during that window, teardown would run first and
        // the setup would then re-install the rules + tun pointing at a now-dead core,
        // blackholing all traffic until the next start/stop cycle clears it.
        runBlocking { setupJob?.cancelAndJoin() }
        // Remove routing rules BEFORE stopping the core so traffic is never redirected
        // to a dead listener. Synchronous on purpose — leaving rules behind breaks the net.
        RootProxyManager.stop(this)
        CoreServiceManager.stopCoreLoop()
    }

    /** Avoid waiting for the currently executing setup job from onDestroy(). */
    private fun stopAfterBackgroundFailure() {
        RootProxyManager.stop(this)
        CoreServiceManager.stopCoreLoop()
        setupJob = null
        stopSelf()
    }

    override fun getService(): Service = this

    override fun startService() {
        // do nothing
    }

    override fun stopService() {
        stopSelf()
    }

    override fun vpnProtect(socket: Int): Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }
}
