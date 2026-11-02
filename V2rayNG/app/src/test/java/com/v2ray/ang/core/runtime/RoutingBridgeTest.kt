package com.v2ray.ang.core.runtime

import com.v2ray.ang.core.CoreSelector
import com.v2ray.ang.core.RuntimeCore
import com.v2ray.ang.core.SingBoxConfigGenerator
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.PreferredCore
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test

class RoutingBridgeTest {
    private fun node(type: EConfigType = EConfigType.VLESS) = ProfileItem(
        configType = type, server = "127.0.0.1", serverPort = "443",
        password = "00000000-0000-0000-0000-000000000001", network = "tcp",
        security = "tls", sni = "example.test", method = "none",
    )

    private fun plan(host: RuntimeCore, default: PreferredCore? = null): RoutingBridgePlan {
        var port = 21000
        return RoutingBridgePlan(host, {
            CoreSelector.select(it, default ?: CoreSelector.builtInDefault(it.protocol))
        }, { port++ })
    }

    @Test
    fun nodeOverrideThenProtocolDefaultSelectTheAuxiliaryCoreInBothDirections() {
        data class Case(val host: RuntimeCore, val node: ProfileItem, val default: PreferredCore?, val bridged: Boolean)
        listOf(
            Case(RuntimeCore.SING_BOX, node(), null, true),
            Case(RuntimeCore.SING_BOX, node().copy(preferredCore = "SING_BOX"), null, false),
            Case(RuntimeCore.SING_BOX, node(), PreferredCore.SING_BOX, false),
            Case(RuntimeCore.SING_BOX, node().copy(preferredCore = "XRAY"), PreferredCore.SING_BOX, true),
            Case(RuntimeCore.XRAY, node(EConfigType.HYSTERIA2), null, true),
            Case(RuntimeCore.XRAY, node(EConfigType.HYSTERIA2).copy(preferredCore = "XRAY"), null, false),
            Case(RuntimeCore.XRAY, node(), null, false),
        ).forEach { case ->
            val plan = plan(case.host, case.default)
            val result = plan.prepare(case.node)
            assertEquals(case.toString(), case.bridged, plan.entries.isNotEmpty())
            if (case.bridged) {
                assertEquals(EConfigType.SOCKS, result.configType)
                assertEquals("127.0.0.1", result.server)
                assertNotNull(result.username)
                assertNotNull(result.password)
            } else assertSame(case.node, result)
        }
    }

    @Test
    fun repeatedRulesReuseOneEndpointButDifferentNodesAreIsolated() {
        val plan = plan(RuntimeCore.SING_BOX)
        val node = node()
        val first = plan.prepare(node)
        val repeated = plan.prepare(node.copy(remarks = "other name", addedTime = 0))
        val different = plan.prepare(node.copy(serverPort = "8443"))
        assertEquals(first.serverPort, repeated.serverPort)
        assertEquals(first.password, repeated.password)
        assertEquals("other name", repeated.remarks)
        assertNotEquals(first.serverPort, different.serverPort)
        assertNotEquals(first.password, different.password)
        assertEquals(2, plan.entries.size)
        assertEquals(EConfigType.VLESS, node.configType)
    }

    @Test
    fun xrayAuxiliaryHasOnlyAuthenticatedLoopbackUdpListenersAndExactInboundRoutes() {
        val plan = plan(RuntimeCore.SING_BOX)
        plan.prepare(node())
        plan.prepare(node().copy(serverPort = "8443"))
        val root = JsonUtil.parseString(RoutingBridgeConfig.xray(plan.entries) {
            V2rayConfig.OutboundBean(protocol = "vless")
        })!!.asJsonObject
        val inbounds = root.getAsJsonArray("inbounds")
        assertEquals(2, inbounds.size())
        inbounds.forEach {
            val inbound = it.asJsonObject
            assertEquals("127.0.0.1", inbound["listen"].asString)
            assertEquals("socks", inbound["protocol"].asString)
            assertEquals("password", inbound.getAsJsonObject("settings")["auth"].asString)
            assertTrue(inbound.getAsJsonObject("settings")["udp"].asBoolean)
            assertEquals(1, inbound.getAsJsonObject("settings").getAsJsonArray("accounts").size())
        }
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules")
        assertEquals(2, rules.size())
        rules.forEach {
            val rule = it.asJsonObject
            assertEquals(rule["outboundTag"].asString, rule.getAsJsonArray("inboundTag").single().asString)
        }
        assertEquals("blackhole", root.getAsJsonArray("outbounds")[0].asJsonObject["protocol"].asString)
        assertFalse(root.has("stats"))
    }

    @Test
    fun singBoxAuxiliaryRoutesMultipleNodesWithoutTunOrUserRoutingRules() {
        val plan = plan(RuntimeCore.XRAY, PreferredCore.SING_BOX)
        plan.prepare(node(EConfigType.HTTP))
        plan.prepare(node(EConfigType.HTTP).copy(serverPort = "8443"))
        val root = JsonUtil.parseString(RoutingBridgeConfig.singBox(plan.entries))!!.asJsonObject
        val listeners = root.getAsJsonArray("inbounds")
        assertEquals(2, listeners.size())
        listeners.forEach {
            assertEquals("socks", it.asJsonObject["type"].asString)
            assertEquals("127.0.0.1", it.asJsonObject["listen"].asString)
            assertEquals(1, it.asJsonObject.getAsJsonArray("users").size())
        }
        val rules = root.getAsJsonObject("route").getAsJsonArray("rules")
        assertEquals(3, rules.size())
        assertEquals("proxy", rules[0].asJsonObject["outbound"].asString)
        assertEquals("bridge-1", rules[1].asJsonObject["outbound"].asString)
        assertEquals("reject", rules[2].asJsonObject["action"].asString)
        assertFalse(root.toString().contains("bridge.invalid"))
    }

    @Test
    fun hy2MainRetainsVlessRuleTagWhileOnlyItsTransportBecomesLocalSocks() {
        val plan = plan(RuntimeCore.SING_BOX)
        val local = plan.prepare(node())
        val root = JsonUtil.parseString(SingBoxConfigGenerator.generate(
            profile = NodeProfile.from(node(EConfigType.HYSTERIA2)),
            socksPort = 10808, httpPort = 10808, logLevel = "warn",
            routingRules = listOf(RulesetItem(domain = listOf("domain:ip.net.coffee"), outboundProfileId = "ca")),
            routingProfiles = mapOf("routing-profile-ca" to NodeProfile.from(local)),
        ))!!.asJsonObject
        val outbounds = root.getAsJsonArray("outbounds").map { it.asJsonObject }
        assertEquals("hysteria2", outbounds.first()["type"].asString)
        val canada = outbounds.single { it["tag"].asString == "routing-profile-ca" }
        assertEquals("socks", canada["type"].asString)
        assertEquals(local.username, canada["username"].asString)
        assertEquals("proxy", root.getAsJsonObject("route")["final"].asString)
        assertEquals("routing-profile-ca", root.getAsJsonObject("route").getAsJsonArray("rules")
            .map { it.asJsonObject }.single { it.has("domain_suffix") }["outbound"].asString)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicatePortAllocationFailsInsteadOfSharingAnotherNodesListener() {
        val plan = RoutingBridgePlan(RuntimeCore.SING_BOX, { RuntimeCore.XRAY }, { 20000 })
        plan.prepare(node())
        plan.prepare(node().copy(serverPort = "8443"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedBrowserDialerDoesNotSilentlyFallBack() {
        plan(RuntimeCore.SING_BOX).prepare(node().copy(browserDialerMode = "webview"))
    }
}
