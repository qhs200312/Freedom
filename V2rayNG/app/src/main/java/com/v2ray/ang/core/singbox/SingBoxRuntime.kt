package com.v2ray.ang.core.singbox

import android.app.Service
import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.RuntimeCore
import com.v2ray.ang.core.SingBoxConfigGenerator
import com.v2ray.ang.core.SingBoxCoreManager
import com.v2ray.ang.core.SingBoxPlatformInterface
import com.v2ray.ang.core.runtime.CoreRuntime
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.Utils
import go.Seq
import libbox.TunOptions

interface SingBoxTunHost {
    fun openSingBoxTun(options: TunOptions): Int
}

internal class SingBoxRuntime(
    private val service: Service,
    private val control: ServiceControl,
    private val profile: NodeProfile,
) : CoreRuntime {
    override val type = RuntimeCore.SING_BOX
    private val core = SingBoxCoreManager()
    override val isRunning: Boolean get() = core.isRunning()

    override fun start() {
        // No Xray controller, environment initialization, watchdog or network reload path.
        Seq.setContext(service.applicationContext)
        val host = service as? SingBoxTunHost
        val platform = SingBoxPlatformInterface(
            context = service,
            protectSocket = control::vpnProtect,
            openTunCallback = { options -> host?.openSingBoxTun(options) ?: error("TUN requested outside VPN mode") },
            underlyingNetworkChanged = control::setUnderlyingNetworks,
        )
        core.start(
            service,
            SingBoxConfigGenerator.generate(profile, Utils.userAssetPath(service), enableTun = host != null),
            platform,
        )
    }

    override fun stop() = core.stop()

    override fun measureDelay(url: String): Long {
        val started = SystemClock.elapsedRealtime()
        val reachable = HttpUtil.isUrlReachable(UrlContentRequest(
            url = url,
            timeout = 5_000,
            socksPort = SettingsManager.getSocksPort(),
            proxyUsername = SettingsManager.getSocksUsername(),
            proxyPassword = SettingsManager.getSocksPassword(),
        ))
        return if (reachable) SystemClock.elapsedRealtime() - started else -1
    }

    override fun trafficStats(): List<OutboundTrafficStat> {
        val (up, down) = core.queryTrafficDelta()
        return listOf(
            OutboundTrafficStat(AppConfig.TAG_PROXY, AppConfig.UPLINK, up),
            OutboundTrafficStat(AppConfig.TAG_PROXY, AppConfig.DOWNLINK, down),
        )
    }
}
