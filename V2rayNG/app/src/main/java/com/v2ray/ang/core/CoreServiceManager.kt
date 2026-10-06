package com.v2ray.ang.core

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.runtime.CoreLaunchPlan
import com.v2ray.ang.core.runtime.CoreRuntime
import com.v2ray.ang.core.runtime.CoreRuntimeFactory
import com.v2ray.ang.core.runtime.CoreSession
import com.v2ray.ang.core.runtime.CoreSessionState
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.handler.TrafficStatsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.root.LocationPrivacyManager
import com.v2ray.ang.util.RuntimeDiagnostics
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.SoftReference

/** Android service facade. Native APIs and network recovery belong to the selected runtime. */
object CoreServiceManager {
    private val session = CoreSession()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var serviceControl: SoftReference<ServiceControl>? = null

    fun selectedLaunchPlan(): CoreLaunchPlan = CoreRuntimeFactory.selectedPlan()
    fun isRunning(): Boolean = session.isRunning &&
        (session.runtime as? ServiceRuntime)?.control?.isStopRequested() != true
    fun isServiceReady(): Boolean = session.isReady && isRunning()
    fun getRunningServerName(): String = (session.runtime as? ServiceRuntime)?.plan?.profile?.remarks.orEmpty()

    fun markDataPathReady(service: Service): Boolean {
        val ticket = session.token
        if ((session.runtime as? ServiceRuntime)?.service !== service) return false
        return session.markReady(ticket)
    }

    fun startCoreLoop(
        vpnInterface: ParcelFileDescriptor?,
        notifyStartSuccess: Boolean = true,
        plan: CoreLaunchPlan = selectedLaunchPlan(),
    ): Boolean {
        val control = serviceControl?.get() ?: return false
        if (control.isStopRequested()) return false
        val service = control.getService()
        return try {
            val ticket = session.start(service) {
                check(!control.isStopRequested()) { "Service is stopping" }
                val runtimeTicket = session.token
                val backend = CoreRuntimeFactory.create(plan, service, control, vpnInterface) {
                    recoverCore(runtimeTicket)
                }
                ServiceRuntime(backend, service, control, plan)
            } ?: return false
            if (control.isStopRequested()) return false
            if (notifyStartSuccess && session.markReady(ticket)) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            }
            true
        } catch (error: Exception) {
            if (!control.isStopRequested()) {
                RuntimeDiagnostics.core(service, "start_failed core=${plan.core} error=${error.javaClass.simpleName}")
                MessageHelper.sendMsg2UI(
                    service, AppConfig.MSG_STATE_START_FAILURE,
                    error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName,
                )
            }
            false
        }
    }

    /**
     * Invalidate recovery immediately; finish blocking native shutdown on a worker.
     * The service closes its TUN only after this callback.
     */
    fun stopCoreLoop(serviceOverride: Service? = null, onStopped: () -> Unit = {}): Boolean {
        val service = serviceOverride ?: serviceControl?.get()?.getService()
        val ticket = if (service != null) session.requestStop(service) else session.requestStop()
        scope.launch {
            try {
                if (ticket != null) session.finishStop(ticket)
                if (service != null) session.retire(service)
            } catch (error: Exception) {
                service?.let { RuntimeDiagnostics.core(it, "stop_failed error=${error.javaClass.simpleName}") }
            } finally {
                // Release the service-owned TUN and request Android service destruction first.
                // Restart waiters may launch a replacement as soon as serviceControl is cleared.
                runCatching(onStopped)
                val ownsService = serviceControl?.get()?.getService() === service
                if ((ticket != null && session.isLatest(ticket)) ||
                    (ticket == null && ownsService && session.runtime == null)
                ) {
                    service?.let {
                        MessageHelper.sendMsg2UI(it, AppConfig.MSG_STATE_STOP_SUCCESS, "")
                        NotificationManager.cancelNotification(it)
                        RuntimeDiagnostics.core(it, "stopped")
                    }
                    if (serviceControl?.get()?.getService() === service) serviceControl = null
                }
            }
        }
        return service != null
    }

    fun isCoreReachable(): Boolean {
        val ticket = session.token
        for (url in startupProbeUrls(listOf(SettingsManager.getDelayTestUrl()))) {
            if (runCatching { session.measureDelay(ticket, url) }.getOrDefault(-1L) >= 0L) return true
        }
        return false
    }

    fun queryAllOutboundTrafficStats() = session.trafficStats()

    private fun recoverCore(ticket: Long) {
        if (!session.isCurrent(ticket) || session.state != CoreSessionState.READY) return
        val active = session.runtime as? ServiceRuntime ?: return
        scope.launch {
            if (active.control.isStopRequested() || !session.isCurrent(ticket)) return@launch
            try {
                RuntimeDiagnostics.core(active.service, "recover_requested core=${active.type}")
                if (session.reload(ticket)) {
                    MessageHelper.sendMsg2UI(active.service, AppConfig.MSG_STATE_RUNNING, "")
                    RuntimeDiagnostics.core(active.service, "recovered core=${active.type}")
                }
            } catch (error: Exception) {
                if (session.isLatest(ticket) && !active.control.isStopRequested()) {
                    RuntimeDiagnostics.core(active.service, "recover_failed error=${error.javaClass.simpleName}")
                    MessageHelper.sendMsg2UI(
                        active.service, AppConfig.MSG_STATE_START_FAILURE,
                        error.message ?: error.javaClass.simpleName,
                    )
                    active.control.stopService()
                }
            }
        }
    }

    private fun measureDelay() {
        val ticket = session.token
        val active = session.runtime as? ServiceRuntime ?: return
        scope.launch {
            var elapsed = -1L
            var errorText = ""
            for (url in startupProbeUrls(listOf(SettingsManager.getDelayTestUrl(), SettingsManager.getDelayTestUrl(true)))) {
                try {
                    elapsed = session.measureDelay(ticket, url)
                    if (elapsed >= 0L) break
                } catch (error: Exception) {
                    errorText = error.message.orEmpty()
                }
            }
            if (!session.isCurrent(ticket)) return@launch
            val result = if (elapsed >= 0L) {
                active.service.getString(R.string.connection_test_available, elapsed)
            } else {
                active.service.getString(R.string.connection_test_error, errorText)
            }
            MessageHelper.sendMsg2UI(active.service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)
            if (elapsed >= 0L) {
                val exit = SpeedtestManager.getRemoteIPInfo()
                if (exit != null && session.isCurrent(ticket)) {
                    MessageHelper.sendMsg2UI(active.service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, "$result\n$exit")
                }
            }
        }
    }

    /** Common Android resources share the same serialized lifetime as their runtime. */
    private class ServiceRuntime(
        private val backend: CoreRuntime,
        val service: Service,
        val control: ServiceControl,
        val plan: CoreLaunchPlan,
    ) : CoreRuntime by backend {
        private var registered = false
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (session.runtime !== this@ServiceRuntime) return
                when (intent?.getIntExtra("key", 0)) {
                    AppConfig.MSG_REGISTER_CLIENT -> MessageHelper.sendMsg2UI(
                        service,
                        if (isServiceReady()) AppConfig.MSG_STATE_RUNNING else AppConfig.MSG_STATE_NOT_RUNNING,
                        "",
                    )
                    AppConfig.MSG_STATE_STOP -> control.stopService()
                    AppConfig.MSG_STATE_RESTART -> {
                        control.stopService()
                        scope.launch {
                            // Wait for resource ownership to end rather than sleeping a fixed interval.
                            while (serviceControl?.get()?.getService() === service) delay(50)
                            if (serviceControl?.get() == null) LauncherManager.startService(service.applicationContext)
                        }
                    }
                    AppConfig.MSG_MEASURE_DELAY -> measureDelay()
                }
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        backend.onScreenOff()
                        NotificationManager.stopSpeedNotification()
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        backend.onScreenOn()
                        NotificationManager.startSpeedNotification()
                    }
                }
            }
        }

        override fun start() {
            RuntimeDiagnostics.core(service, "starting core=$type protocol=${plan.profile.configType}")
            backend.start()
            if (control.isStopRequested()) throw CancellationException("Service stopped during core startup")
            ContextCompat.registerReceiver(
                service, receiver,
                IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE).apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                }, Utils.receiverFlags(),
            )
            registered = true
            NotificationManager.showNotification(plan.profile)
            TrafficStatsManager.start(service)
            NotificationManager.startSpeedNotification()
            LocationPrivacyManager.onProxyStarted(service)
            RuntimeDiagnostics.core(service, "running core=$type")
        }

        override fun stop() {
            TrafficStatsManager.stop()
            NotificationManager.stopSpeedNotification()
            try {
                backend.stop()
            } finally {
                if (registered) {
                    runCatching { service.unregisterReceiver(receiver) }
                    registered = false
                }
                LocationPrivacyManager.onProxyStopped(service)
                NotificationManager.cancelNotification(service)
                RuntimeDiagnostics.core(service, "runtime_closed core=$type")
            }
        }

        override fun restart(shouldContinue: () -> Boolean): Boolean {
            // Keep the foreground service/receiver alive while Xray replaces its connections.
            TrafficStatsManager.stop()
            val restarted = backend.restart { shouldContinue() && !control.isStopRequested() }
            if (restarted && shouldContinue() && !control.isStopRequested()) {
                TrafficStatsManager.start(service)
                RuntimeDiagnostics.core(service, "runtime_restarted core=$type")
                return true
            }
            return false
        }
    }
}

internal fun startupProbeUrls(configuredUrls: List<String>): List<String> = buildList {
    add(AppConfig.DELAY_TEST_URL)
    addAll(configuredUrls.filter(String::isNotBlank).map(SettingsManager::normalizeDelayTestUrl))
}.distinct()
