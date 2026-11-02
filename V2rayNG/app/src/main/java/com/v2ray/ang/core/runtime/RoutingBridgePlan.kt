package com.v2ray.ang.core.runtime

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreSelector
import com.v2ray.ang.core.RuntimeCore
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils
import java.util.UUID

internal data class RoutingBridgeEntry(
    val tag: String,
    val original: ProfileItem,
    val local: ProfileItem,
)

/** One authenticated loopback listener per distinct node, one auxiliary core per session. */
internal class RoutingBridgePlan(
    private val host: RuntimeCore,
    private val select: (NodeProfile) -> RuntimeCore = CoreSelector::selectConfigured,
    private val allocatePort: () -> Int = Utils::findRandomFreePort,
    private val enabled: Boolean = true,
) {
    private val nodes = linkedMapOf<ProfileItem, RoutingBridgeEntry>()
    val entries: List<RoutingBridgeEntry> get() = nodes.values.toList()
    val target: RuntimeCore get() = if (host == RuntimeCore.SING_BOX) RuntimeCore.XRAY else RuntimeCore.SING_BOX

    fun prepare(profile: ProfileItem): ProfileItem {
        if (!enabled) return profile
        if (select(NodeProfile.from(profile)) == host) return profile
        require(profile.browserDialerMode.isNullOrBlank()) {
            "Browser dialer nodes cannot run in an auxiliary core"
        }
        val identity = profile.duplicateIdentity()
        val entry = nodes.getOrPut(identity) {
            val port = allocatePort()
            require(port in 1..65535 && nodes.values.none { it.local.serverPort == port.toString() }) {
                "Unable to allocate a distinct local core port"
            }
            RoutingBridgeEntry("bridge-${nodes.size}", profile.copy(), ProfileItem(
                configType = EConfigType.SOCKS,
                remarks = profile.remarks,
                server = AppConfig.LOOPBACK,
                serverPort = port.toString(),
                username = UUID.randomUUID().toString(),
                password = UUID.randomUUID().toString(),
                preferredCore = host.name,
            ))
        }
        return entry.local.copy(remarks = profile.remarks)
    }
}
