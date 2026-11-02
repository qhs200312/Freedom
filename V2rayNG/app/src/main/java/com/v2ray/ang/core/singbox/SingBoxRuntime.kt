package com.v2ray.ang.core.singbox

import android.app.Service
import android.os.SystemClock
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.RuntimeCore
import com.v2ray.ang.core.SingBoxConfigGenerator
import com.v2ray.ang.core.SingBoxCoreManager
import com.v2ray.ang.core.SingBoxPlatformInterface
import com.v2ray.ang.core.runtime.CoreRuntime
import com.v2ray.ang.core.runtime.RoutingCoreBridges
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
    fun excludeOwnTrafficForLocalCore()
}

internal class SingBoxRuntime(
    private val service: Service,
    private val control: ServiceControl,
    private val profile: NodeProfile,
    private val recover: () -> Unit,
    private val useNodeCores: Boolean,
) : CoreRuntime {
    override val type = RuntimeCore.SING_BOX
    private val core = SingBoxCoreManager()
    private var trafficBindings = emptyMap<String, com.v2ray.ang.dto.NodeTrafficBinding>()
    private var bridges: RoutingCoreBridges? = null
    override var routingHealthEndpoints = emptyList<com.v2ray.ang.routinghealth.RoutingHealthEndpoint>()
        private set
    override val isRunning: Boolean get() = core.isRunning() && bridges?.isAlive != false

    override fun start() {
        Seq.setContext(service.applicationContext)
        val host = service as? SingBoxTunHost
        val bridges = RoutingCoreBridges(
            service, type, control::vpnProtect, recover, enabled = useNodeCores,
        ).also { this.bridges = it }
        val generated = SingBoxConfigGenerator.generate(
            profile,
            Utils.userAssetPath(service),
            enableTun = host != null,
            onTrafficBindings = { trafficBindings = it },
            prepareRoutingProfile = bridges::prepare,
        )
        val healthConfig = com.v2ray.ang.routinghealth.RoutingHealthConfig.prepare(generated, type)
        routingHealthEndpoints = healthConfig.endpoints
        // Xray has no per-socket Android protect callback. Exclude our UID from the VPN.
        if (bridges.needed) host?.excludeOwnTrafficForLocalCore()
        bridges.start()
        val platform = SingBoxPlatformInterface(
            context = service,
            protectSocket = control::vpnProtect,
            openTunCallback = { options -> host?.openSingBoxTun(options) ?: error("TUN requested outside VPN mode") },
            underlyingNetworkChanged = control::setUnderlyingNetworks,
        )
        core.start(
            service,
            healthConfig.content,
            platform,
        )
    }

    override fun stop() {
        try { core.stop() } finally {
            bridges?.close()
            bridges = null
        }
    }

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

    override fun trafficStats(): List<OutboundTrafficStat> = core.queryTrafficStats().map { stat ->
        val binding = trafficBindings[stat.tag]
        stat.copy(profileIds = binding?.profileIds.orEmpty(), countInTotal = binding?.countInTotal ?: true)
    }
}
