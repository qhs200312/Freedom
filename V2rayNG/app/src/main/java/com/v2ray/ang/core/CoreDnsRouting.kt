package com.v2ray.ang.core

import com.v2ray.ang.dto.V2rayConfig

/** Root helpers deliver DNS to SOCKS, while the Android VPN delivers it to native TUN. */
internal object CoreDnsRouting {
    fun requiresResolver(rootMode: Boolean, localDnsEnabled: Boolean): Boolean =
        rootMode || localDnsEnabled

    fun apply(
        config: V2rayConfig,
        rootMode: Boolean,
        nativeTun: Boolean,
        localDnsEnabled: Boolean,
    ) {
        if (!requiresResolver(rootMode, localDnsEnabled)) return

        val captured = when {
            rootMode -> config.inbounds.filter { it.protocol == "socks" }.also { socks ->
                require(socks.isNotEmpty()) { "Root mode requires a SOCKS inbound for TCP and DNS" }
                socks.forEach { inbound ->
                    val settings = inbound.settings ?: V2rayConfig.InboundBean.InSettingsBean().also {
                        inbound.settings = it
                    }
                    settings.udp = true
                }
            }
            nativeTun -> config.inbounds.filter { it.protocol == "tun" }
            else -> emptyList()
        }

        if (captured.isNotEmpty()) {
            val rule = V2rayConfig.RoutingBean.RulesBean(
                inboundTag = captured.map { it.tag },
                outboundTag = "dns-out",
                port = "53",
            )
            // An unrelated TUN rule must not suppress the root SOCKS rule. Keep DNS ahead
            // of LAN/direct/catch-all rules, and repeated application idempotent.
            config.routing.rules.removeAll { it == rule }
            config.routing.rules.add(0, rule)
        }

        val existing = config.outbounds.firstOrNull { it.tag == "dns-out" }
        require(existing == null || existing.protocol == "dns") { "DNS outbound tag is already in use" }
        if (existing == null) {
            config.outbounds.add(V2rayConfig.OutboundBean(
                protocol = "dns", tag = "dns-out", settings = null, streamSettings = null, mux = null,
            ))
        }
    }
}
