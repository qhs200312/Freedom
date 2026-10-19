package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test

class NodeRoutingConfigTest {
    @Test
    fun eachServiceUsesItsOwnNodeAndUnmatchedTrafficKeepsTheSelectedNode() {
        val rules = listOf(
            rule("claude.ai", "local"),
            rule("anthropic.com", "local"),
            rule("instagram.com", "subscription"),
        )
        val root = generate(rules, mapOf(
            "routing-profile-local" to node("local.example"),
            "routing-profile-subscription" to node("airport.example"),
        ))
        val outbounds = root.getAsJsonArray("outbounds").associate { it.asJsonObject.get("tag").asString to it.asJsonObject }
        assertEquals(4, outbounds.size)
        assertEquals("default.example", outbounds["proxy"]!!.get("server").asString)
        assertEquals("local.example", outbounds["routing-profile-local"]!!.get("server").asString)
        assertEquals("airport.example", outbounds["routing-profile-subscription"]!!.get("server").asString)
        val route = root.getAsJsonObject("route")
        assertEquals("proxy", route.get("final").asString)
        val serviceRules = route.getAsJsonArray("rules").map { it.asJsonObject }.filter { it.has("domain_suffix") }
        assertEquals(listOf("routing-profile-local", "routing-profile-local", "routing-profile-subscription"),
            serviceRules.map { it.get("outbound").asString })
    }

    @Test
    fun removedNodesFallBackAndDisabledRulesDoNotLoadUnusedNodes() {
        val rules = listOf(rule("claude.ai", "removed"), rule("disabled.example", "disabled").copy(enabled = false))
        val root = generate(rules, mapOf("routing-profile-disabled" to node("unused.example")))
        assertEquals(listOf("proxy", "direct"),
            root.getAsJsonArray("outbounds").map { it.asJsonObject.get("tag").asString })
        val routed = root.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals("proxy", routed.first { it.has("domain_suffix") }.get("outbound").asString)
        assertFalse(root.toString().contains("disabled.example"))
    }

    @Test
    fun selectedNodeChangesDoNotRetargetAssignedServices() {
        val rules = listOf(rule("claude.ai", "fixed"))
        val profiles = mapOf("routing-profile-fixed" to node("fixed.example"))
        val first = generate(rules, profiles, node("first.example"))
        val second = generate(rules, profiles, node("second.example"))
        val firstOutbounds = first.getAsJsonArray("outbounds")
        val secondOutbounds = second.getAsJsonArray("outbounds")
        assertNotEquals(firstOutbounds[0], secondOutbounds[0])
        assertEquals(firstOutbounds[1], secondOutbounds[1])
    }

    @Test
    fun routingNodeHostnamesHaveBootstrapWhenTheSelectedNodeUsesAnIp() {
        val root = generate(listOf(rule("claude.ai", "domain")),
            mapOf("routing-profile-domain" to node("node.example")), node("127.0.0.1"))
        assertTrue(root.getAsJsonObject("dns").getAsJsonArray("servers")
            .any { it.asJsonObject.get("tag").asString == "bootstrap-local" })
        assertFalse(root.getAsJsonArray("outbounds")[0].asJsonObject.has("domain_resolver"))
        assertEquals("bootstrap-local", root.getAsJsonArray("outbounds")[1].asJsonObject.get("domain_resolver").asString)
    }

    @Test
    fun wireguardTargetsUseIndependentEndpointTags() {
        val wireguard = node("127.0.0.1").copy(
            protocol = EConfigType.WIREGUARD,
            wireGuardPrivateKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
            wireGuardPublicKey = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
            wireGuardLocalAddresses = listOf("10.0.0.2/32"),
        )
        val root = generate(listOf(rule("claude.ai", "wg")), mapOf("routing-profile-wg" to wireguard))
        assertEquals("routing-profile-wg", root.getAsJsonArray("endpoints").single().asJsonObject.get("tag").asString)
        assertEquals(2, root.getAsJsonArray("outbounds").size())
    }

    @Test
    fun unsupportedRoutingNodeRequestsCoreFallbackButMissingNodesDoNot() {
        val rules = listOf(rule("claude.ai", "xray"))
        val xrayOnly = node("node.example").copy(protocol = EConfigType.VLESS, password = "id", network = "xhttp")
        assertNotNull(SingBoxConfigGenerator.unsupportedRoutingReason(rules, mapOf("routing-profile-xray" to xrayOnly)))
        assertNull(SingBoxConfigGenerator.unsupportedRoutingReason(rules))
        assertNull(SingBoxConfigGenerator.unsupportedRoutingReason(rules.map { it.copy(enabled = false) },
            mapOf("routing-profile-xray" to xrayOnly)))
    }

    @Test
    fun xrayMissingTargetFallsBackToSelectedPolicyGroupBalancer() {
        val rule = V2rayConfig.RoutingBean.RulesBean(outboundTag = "routing-profile-removed")
        CoreConfigManager.applyRoutingTarget(rule, emptySet(), mapOf(AppConfig.TAG_PROXY to "selected-balancer"))
        assertNull(rule.outboundTag)
        assertEquals("selected-balancer", rule.balancerTag)
        val fixed = V2rayConfig.RoutingBean.RulesBean(outboundTag = "routing-profile-local")
        CoreConfigManager.applyRoutingTarget(fixed, setOf("routing-profile-local"), emptyMap())
        assertEquals("routing-profile-local", fixed.outboundTag)
        assertNull(fixed.balancerTag)
    }

    private fun generate(rules: List<RulesetItem>, profiles: Map<String, NodeProfile>, selected: NodeProfile = node("default.example")) =
        JsonUtil.parseString(SingBoxConfigGenerator.generate(
            profile = selected, socksPort = 10808, httpPort = 10809, logLevel = "warn",
            routingRules = rules, routingProfiles = profiles,
        ))!!.asJsonObject

    private fun rule(domain: String, id: String) =
        RulesetItem(domain = listOf("domain:$domain"), outboundTag = "Same display name", outboundProfileId = id)

    private fun node(server: String) = NodeProfile(
        remarks = "Same display name", protocol = EConfigType.HTTP, server = server, port = 8080,
        tlsEnabled = false, insecure = false,
    )
}
