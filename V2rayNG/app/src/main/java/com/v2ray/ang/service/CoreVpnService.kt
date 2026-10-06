package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.singbox.SingBoxTunHost
import com.v2ray.ang.core.singbox.SingBoxVpnConfigurator
import com.v2ray.ang.core.xray.XrayVpnConfigurator
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.RuntimeDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import libbox.TunOptions
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean

/** Owns Android permissions and the TUN lifetime, not either core's native control flow. */
@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl, SingBoxTunHost {
    private val tunLock = Any()
    private var tun: ParcelFileDescriptor? = null
    private val stopping = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startupJob: Job? = null
    @Volatile private var serviceReady = false
    @Volatile private var platformOwnsTun = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground(this)
        if (stopping.get()) return START_NOT_STICKY
        if (serviceReady || !starting.compareAndSet(false, true)) return START_STICKY
        CoreServiceManager.serviceControl = SoftReference(this)
        startupJob = serviceScope.launch {
            try {
                checkActive()
                val plan = CoreServiceManager.selectedLaunchPlan()
                platformOwnsTun = plan.opensTunThroughPlatform
                SettingsManager.refreshRuntimeSocksPort()
                val descriptor = if (platformOwnsTun) null else establishTun {
                    XrayVpnConfigurator.configure(it)
                }
                checkActive()
                if (!CoreServiceManager.startCoreLoop(descriptor, notifyStartSuccess = false, plan = plan)) {
                    checkActive()
                    error("Core failed to start")
                }
                checkActive()
                check(synchronized(tunLock) { tun != null }) { "Core did not establish the VPN interface" }
                // Running describes an established service. Public endpoint health is measured separately.
                if (!CoreServiceManager.markDataPathReady(this@CoreVpnService)) {
                    checkActive()
                    error("Core session was superseded")
                }
                serviceReady = true
                RootLanSharing.startClientSharing(this@CoreVpnService)
                MessageHelper.sendMsg2UI(this@CoreVpnService, AppConfig.MSG_STATE_START_SUCCESS, "")
                RuntimeDiagnostics.core(this@CoreVpnService, "vpn_ready core=${plan.core}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!stopping.get()) {
                    MessageHelper.sendMsg2UI(
                        this@CoreVpnService, AppConfig.MSG_STATE_START_FAILURE,
                        error.message ?: error.javaClass.simpleName,
                    )
                    stopService()
                }
            } finally {
                starting.set(false)
            }
        }
        return START_STICKY
    }

    private fun checkActive() {
        if (stopping.get()) throw CancellationException("VPN service is stopping")
    }

    private fun establishTun(configure: (Builder) -> Unit): ParcelFileDescriptor = synchronized(tunLock) {
        checkActive()
        check(prepare(this) == null) { "VPN permission is missing" }
        val builder = Builder()
        configure(builder)
        configurePerAppProxy(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(AppConfig.LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
        val descriptor = builder.establish() ?: error("Failed to establish VPN interface")
        if (stopping.get()) {
            descriptor.close()
            throw CancellationException("VPN stopped while establishing interface")
        }
        tun?.close()
        tun = descriptor
        descriptor
    }

    override fun openSingBoxTun(options: TunOptions): Int =
        establishTun { SingBoxVpnConfigurator.configure(it, options) }.fd

    private fun configurePerAppProxy(builder: Builder) {
        val selection = vpnAppSelection(
            self = BuildConfig.APPLICATION_ID,
            nativeSingBox = platformOwnsTun,
            perApp = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY),
            bypass = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS),
            apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET).orEmpty(),
        )
        selection.apps.forEach { packageName ->
            try {
                if (selection.bypass) builder.addDisallowedApplication(packageName)
                else builder.addAllowedApplication(packageName)
            } catch (error: PackageManager.NameNotFoundException) {
                LogUtil.w(AppConfig.TAG, "VPN package is no longer installed", error)
            }
        }
    }

    override fun isStopRequested(): Boolean = stopping.get()
    override fun getService(): Service = this
    override fun startService() { onStartCommand(null, 0, 0) }
    override fun vpnProtect(socket: Int): Boolean = protect(socket)
    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean =
        super<VpnService>.setUnderlyingNetworks(networks)

    override fun stopService() {
        if (!stopping.compareAndSet(false, true)) return
        serviceReady = false
        startupJob?.cancel()
        RootLanSharing.stopClientSharing(this)
        CoreServiceManager.stopCoreLoop(this) {
            synchronized(tunLock) {
                runCatching { tun?.close() }
                tun = null
            }
            stopSelf()
        }
    }

    override fun onRevoke() { stopService() }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isTransientShortcutTask(rootIntent)) stopService()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopService()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let { MyContextWrapper.wrap(it, SettingsManager.getLocale()) })
    }
}

internal fun isTransientShortcutTask(rootIntent: Intent?): Boolean =
    isTransientShortcutClassName(rootIntent?.component?.className)

internal fun isTransientShortcutClassName(className: String?): Boolean =
    className?.contains(".ui.shortcut.") == true

internal data class VpnAppSelection(val bypass: Boolean, val apps: Set<String>)

internal fun vpnAppSelection(
    self: String,
    nativeSingBox: Boolean,
    perApp: Boolean,
    bypass: Boolean,
    apps: Set<String>,
): VpnAppSelection {
    if (!perApp || apps.isEmpty()) {
        return VpnAppSelection(true, if (nativeSingBox) emptySet() else setOf(self))
    }
    val selected = apps.toMutableSet()
    if (bypass == nativeSingBox) selected.remove(self) else selected.add(self)
    return VpnAppSelection(bypass, selected)
}
