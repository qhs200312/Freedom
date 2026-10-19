package com.v2ray.ang.core

import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.routing.RoutingMatchMode
import com.v2ray.ang.ui.routing.routingMatchMode
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test

class AppRoutingConfigTest {
    private val node = NodeProfile(
        remarks = "Selected", protocol = EConfigType.HTTP, server = "127.0.0.1",
        port = 8080, tlsEnabled = false, insecure = false,
    )

    @Test
    fun appSelectionOverridesDomainRulesWithoutChangingTheDefaultNode() {
        val domain = RulesetItem(domain = listOf("domain:example.com"), outboundTag = "proxy")
        val app = RulesetItem(process = listOf("com.example.video"), outboundProfileId = "local")
        val root = generate(listOf(domain, app))
        val route = root.getAsJsonObject("route")
        val rules = route.getAsJsonArray("rules").map { it.asJsonObject }
        val appIndex = rules.indexOfFirst { it.has("package_name") }
        assertTrue(appIndex < rules.indexOfFirst { it.has("domain_suffix") })
        assertEquals("com.example.video", rules[appIndex].getAsJsonArray("package_name")[0].asString)
        assertEquals("routing-profile-local", rules[appIndex].get("outbound").asString)
        assertEquals("tun-in", rules[appIndex].getAsJsonArray("inbound")[0].asString)
        assertTrue(route.get("find_process").asBoolean)
        assertEquals("proxy", route.get("final").asString)
    }

    @Test
    fun appRulesAreSkippedOutsideTunRatherThanBecomingGlobalRules() {
        val root = generate(listOf(RulesetItem(process = listOf("com.example.video"), outboundProfileId = "local")), tun = false)
        assertFalse(root.toString().contains("package_name"))
        assertFalse(root.toString().contains("routing-profile-local"))
        assertFalse(root.getAsJsonObject("route").has("find_process"))
        assertEquals("proxy", root.getAsJsonObject("route").get("final").asString)
    }

    @Test
    fun appsCanUseDirectOrBlockWithoutAffectingOtherApplications() {
        val root = generate(listOf(
            RulesetItem(process = listOf("com.example.direct"), outboundTag = "direct"),
            RulesetItem(process = listOf("com.example.block"), outboundTag = "block"),
        ))
        val apps = root.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }.filter { it.has("package_name") }
        assertEquals("direct", apps[0].get("outbound").asString)
        assertEquals("reject", apps[1].get("action").asString)
        assertFalse(apps[1].has("outbound"))
        assertEquals("proxy", root.getAsJsonObject("route").get("final").asString)
    }

    @Test
    fun appAndDomainConstraintsRemainCombinedAndDoNotAffectGlobalDns() {
        val root = generate(listOf(RulesetItem(
            process = listOf("com.example.video"), domain = listOf("domain:example.com"), outboundTag = "direct",
        )), dns = true)
        val app = root.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }.single { it.has("package_name") }
        assertEquals("example.com", app.getAsJsonArray("domain_suffix")[0].asString)
        assertEquals(0, root.getAsJsonObject("dns").getAsJsonArray("rules").size())
    }

    @Test
    fun orderingKeepsPriorityWithinAppsAndPreservesAdvancedExistingRules() {
        val domain = RulesetItem(domain = listOf("domain:example.com"))
        val first = RulesetItem(process = listOf("com.example.first"))
        val second = RulesetItem(process = listOf("com.example.second"))
        assertEquals(listOf(first, second, domain), RoutingTargets.orderedRules(listOf(domain, first, second)))
        assertEquals(RoutingMatchMode.DOMAINS, routingMatchMode(domain))
        assertEquals(RoutingMatchMode.APPS, routingMatchMode(first))
        assertEquals(RoutingMatchMode.ADVANCED, routingMatchMode(first.copy(domain = domain.domain)))
    }

    private fun generate(rules: List<RulesetItem>, tun: Boolean = true, dns: Boolean = false) =
        JsonUtil.parseString(SingBoxConfigGenerator.generate(
            profile = node, socksPort = 10808, httpPort = 10809, logLevel = "warn",
            routingRules = rules,
            routingProfiles = mapOf("routing-profile-local" to node.copy(port = 8081)),
            enableTun = tun, localDnsEnabled = dns,
        ))!!.asJsonObject
}
