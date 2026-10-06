package com.v2ray.ang.core.xray

import android.app.Service
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.RuntimeCore
import com.v2ray.ang.core.runtime.CoreRuntime
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BrowserDialerMode
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.service.OemConnectionGuard
import com.v2ray.ang.util.Utils
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.net.InetSocketAddress

internal class XrayRuntime(
    private val service: Service,
    private val control: ServiceControl,
    private val guid: String,
    private val profile: ProfileItem,
    private val tun: ParcelFileDescriptor?,
    private val recover: () -> Unit,
) : CoreRuntime {
    override val type = RuntimeCore.XRAY
    private var controller: CoreController? = null
    private var monitor: NetworkMonitor? = null
    private var guard: OemConnectionGuard? = null
    private var dialer: IDialerService? = null
    @Volatile private var stopping = false
    override val isRunning: Boolean get() = controller?.isRunning == true

    @Synchronized
    override fun start() {
        stopping = false
        CoreNativeManager.initCoreEnv(service)
        val core = controller ?: CoreNativeManager.newCoreController(object : CoreCallbackHandler {
            override fun startup() = 0L
            override fun shutdown(): Long {
                if (!stopping) recover()
                return 0L
            }
            override fun onEmitStatus(l: Long, s: String?) = 0L
        }).also { controller = it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            core.registerProcessFinder(XrayProcessFinder(service))
        }
        val config = CoreConfigManager.getV2rayConfig(service, guid)
        check(config.status) { config.errorMessage.ifBlank { "Invalid Xray configuration" } }
        val dialerMode = BrowserDialerMode.from(profile.browserDialerMode)
        val dialerAddress = if (dialerMode != null) "127.0.0.1:${Utils.findRandomFreePort()}" else ""
        CoreNativeManager.reconcileBrowserDialer(dialerAddress)
        core.startLoop(config.content, tun?.fd ?: 0)
        dialer = when (dialerMode) {
            BrowserDialerMode.OKHTTP -> DialerNativeService()
            BrowserDialerMode.WEBVIEW -> DialerWebviewService()
            else -> null
        }?.also { it.start(service, dialerAddress) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            service.getSystemService(ConnectivityManager::class.java)?.let { connectivity ->
                monitor = NetworkMonitor(connectivity, control::setUnderlyingNetworks, recover)
                    .also { it.register() }
            }
        }
        if (SettingsManager.isOemConnectionGuardEnabled()) {
            guard = OemConnectionGuard(
                context = service,
                shouldMonitor = { !stopping },
                isCoreRunning = { isRunning },
                hasUsableNetwork = { monitor?.hasAvailableNetwork() ?: true },
                isCoreReachable = { measureDelay(SettingsManager.getDelayTestUrl()) >= 0L },
                reloadCore = { recover(); true },
            ).also { it.start() }
        }
    }

    @Synchronized
    override fun stop() {
        stopping = true
        guard?.stop()
        guard = null
        monitor?.unregister()
        monitor = null
        try {
            if (controller?.isRunning == true) controller?.stopLoop()
        } finally {
            // Break the Go -> Java callback -> controller reference cycle at session end.
            controller?.callbackHandler = null
            controller?.registerProcessFinder(null)
            controller = null
            dialer?.stop()
            dialer = null
            CoreNativeManager.reconcileBrowserDialer("")
        }
    }

    @Synchronized
    override fun measureDelay(url: String): Long = controller?.measureDelay(url) ?: -1L

    @Synchronized
    override fun trafficStats(): List<OutboundTrafficStat> =
        controller?.queryAllOutboundTrafficStats().orEmpty().split(';').mapNotNull { entry ->
            val fields = entry.split(',', limit = 3)
            if (fields.size != 3) return@mapNotNull null
            val value = fields[2].toLongOrNull() ?: return@mapNotNull null
            OutboundTrafficStat(fields[0], fields[1], value)
        }

    override fun onScreenOn() { guard?.onScreenOn() }
    override fun onScreenOff() { guard?.onScreenOff() }
}

private class XrayProcessFinder(service: Service) : ProcessFinder {
    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    override fun findProcessByConnection(
        network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long,
    ): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || destIP.isBlank() || destPort == 0L) return -1
        val protocol = when (network) {
            "tcp" -> OsConstants.IPPROTO_TCP
            "udp" -> OsConstants.IPPROTO_UDP
            else -> return -1
        }
        return runCatching {
            connectivity.getConnectionOwnerUid(
                protocol, InetSocketAddress(srcIP, srcPort.toInt()), InetSocketAddress(destIP, destPort.toInt()),
            ).toLong()
        }.getOrDefault(-1)
    }
}
