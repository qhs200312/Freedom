package com.v2ray.ang

import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.dto.V2rayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreConfigManagerTest {
    @Test
    fun googleLocationBlockRuleUsesOnlyCapturedHosts() {
        val rule = CoreConfigManager.googleLocationBlockRule(enabled = true)

        assertEquals(AppConfig.TAG_BLOCKED, rule?.outboundTag)
        assertEquals("443", rule?.port)
        assertEquals("tcp,udp", rule?.network)
        assertEquals(
            listOf(
                "full:mapsmobilesdks-pa.googleapis.com",
                "full:www.googleapis.com",
            ),
            rule?.domain,
        )
        assertTrue(rule?.domain.orEmpty().none { it.contains("robinfrontend") })
        assertTrue(rule?.domain.orEmpty().none { it.contains("clients4.google.com") })
    }

    @Test
    fun googleLocationBlockRuleIsAbsentWhenDisabled() {
        assertNull(CoreConfigManager.googleLocationBlockRule(enabled = false))
    }

    @Test
    fun googleMapsBlockRuleUsesOnlyMapSpecificHosts() {
        val rule = CoreConfigManager.googleMapsBlockRule(enabled = true)

        assertEquals(AppConfig.TAG_BLOCKED, rule?.outboundTag)
        assertEquals("443", rule?.port)
        assertTrue(rule?.domain.orEmpty().contains("full:maps.googleapis.com"))
        assertTrue(rule?.domain.orEmpty().contains("full:mapsmobilesdks-pa.googleapis.com"))
        assertTrue(rule?.domain.orEmpty().contains("full:tile.googleapis.com"))
        assertTrue(rule?.domain.orEmpty().contains("full:places.googleapis.com"))
        assertTrue(rule?.domain.orEmpty().none { it == "full:www.google.com" })
        assertTrue(rule?.domain.orEmpty().none { it == "full:clients4.google.com" })
        assertTrue(rule?.domain.orEmpty().none { it == "full:csi.gstatic.com" })
    }

    @Test
    fun googleMapsBlockRuleIsAbsentWhenDisabled() {
        assertNull(CoreConfigManager.googleMapsBlockRule(enabled = false))
    }

    @Test
    fun directRuleSplitsUdpToBlockAndKeepsTcpDirect() {
        val rule = routingRule(outboundTag = AppConfig.TAG_DIRECT, network = "tcp,udp")

        val result = CoreConfigManager.applyNonProxiedUdpPolicy(rule, enabled = true)

        assertEquals(2, result.size)
        assertEquals(AppConfig.TAG_BLOCKED, result[0].outboundTag)
        assertEquals("udp", result[0].network)
        assertEquals(listOf("socks", "tun", "http"), result[0].inboundTag)
        assertEquals(AppConfig.TAG_DIRECT, result[1].outboundTag)
        assertEquals("tcp", result[1].network)
    }

    @Test
    fun tcpOnlyDirectRuleIsKeptAsDirect() {
        val rule = routingRule(outboundTag = AppConfig.TAG_DIRECT, network = "tcp")

        val result = CoreConfigManager.applyNonProxiedUdpPolicy(rule, enabled = true)

        assertEquals(1, result.size)
        assertEquals(AppConfig.TAG_DIRECT, result.single().outboundTag)
        assertEquals("tcp", result.single().network)
    }

    @Test
    fun proxyUdpRuleIsNotChanged() {
        val rule = routingRule(outboundTag = AppConfig.TAG_PROXY, network = "udp")

        val result = CoreConfigManager.applyNonProxiedUdpPolicy(rule, enabled = true)

        assertSame(rule, result.single())
    }

    @Test
    fun unknownNetworkIsNotChanged() {
        val rule = routingRule(outboundTag = AppConfig.TAG_DIRECT, network = "quic")

        val result = CoreConfigManager.applyNonProxiedUdpPolicy(rule, enabled = true)

        assertSame(rule, result.single())
    }

    @Test
    fun mixedUnknownNetworkIsNotPartiallyChanged() {
        val rule = routingRule(outboundTag = AppConfig.TAG_DIRECT, network = "udp,quic")

        val result = CoreConfigManager.applyNonProxiedUdpPolicy(rule, enabled = true)

        assertSame(rule, result.single())
    }

    @Test
    fun existingUserInboundScopeIsPreserved() {
        val rule = routingRule(outboundTag = AppConfig.TAG_DIRECT, network = "tcp,udp")
            .copy(inboundTag = listOf("tun"))

        val result = CoreConfigManager.applyNonProxiedUdpPolicy(rule, enabled = true)

        assertEquals(listOf("tun"), result[0].inboundTag)
        assertEquals(listOf("tun"), result[1].inboundTag)
    }

    @Test
    fun internalInboundRuleIsNotChanged() {
        val rule = routingRule(outboundTag = AppConfig.TAG_DIRECT, network = "udp")
            .copy(inboundTag = listOf(AppConfig.TAG_DNS))

        val result = CoreConfigManager.applyNonProxiedUdpPolicy(rule, enabled = true)

        assertSame(rule, result.single())
    }

    private fun routingRule(
        outboundTag: String,
        network: String,
    ) = V2rayConfig.RoutingBean.RulesBean(
        domain = listOf("example.com"),
        outboundTag = outboundTag,
        network = network,
    )
}
