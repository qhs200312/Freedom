package com.v2ray.ang.core.runtime

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.RuntimeCore
import com.v2ray.ang.core.SingBoxConfigGenerator
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.SettingsChangeManager
import org.junit.Assert.*
import org.junit.Test

class RoutingCoreModeTest {
    private val vless = ProfileItem(
        configType = EConfigType.VLESS, server = "example.test", serverPort = "443",
        password = "00000000-0000-0000-0000-000000000001", method = "none", network = "tcp",
    )
    private val rules = listOf(RulesetItem(domain = listOf("domain:example.test"), outboundProfileId = "ca"))
    private fun resolved(profile: ProfileItem) = listOf(CoreConfigContext.ResolvedOutbound(
        "routing-profile-ca", profile, listOf(profile), CoreResolvedType.NORMAL,
    ))

    @Test
    fun unifiedModeDoesNotSelectOrAllocateAuxiliaryNodesInEitherDirection() {
        RuntimeCore.entries.forEach { host ->
            val plan = RoutingBridgePlan(host,
                select = { error("Must not inspect node core in unified mode") },
                allocatePort = { error("Must not allocate a bridge in unified mode") },
                enabled = false,
            )
            assertSame(vless, plan.prepare(vless))
            assertTrue(plan.entries.isEmpty())
        }
    }

    @Test
    fun singleCoreFallsBackForUnsupportedVlessButPerNodeKeepsSingBoxHost() {
        val nodes = resolved(vless.copy(network = "xhttp"))
        assertNotNull(SingBoxConfigGenerator.routingCompatibilityReason(rules, nodes, false))
        assertNull(SingBoxConfigGenerator.routingCompatibilityReason(rules, nodes, true))
        assertNull(SingBoxConfigGenerator.routingCompatibilityReason(rules, resolved(vless), false))
    }

    @Test
    fun ruleAndGroupLimitationsStillApplyWithPerNodeMode() {
        val unsupported = rules.map { it.copy(domain = listOf("geosite:unbundled")) }
        assertNotNull(SingBoxConfigGenerator.routingCompatibilityReason(unsupported, resolved(vless), true))
        val chain = resolved(vless).map { it.copy(resolvedType = CoreResolvedType.PROXYCHAIN) }
        assertNotNull(SingBoxConfigGenerator.routingCompatibilityReason(rules, chain, true))
    }

    @Test
    fun modeIsCapturedForTheWholeLaunchAndExistingDefaultIsPreserved() {
        assertTrue(AppConfig.DEFAULT_ROUTING_NODE_CORES)
        val launch = CoreLaunchPlan("main", vless, RuntimeCore.SING_BOX, useNodeCores = false)
        assertFalse(launch.useNodeCores)
        assertTrue(launch.opensTunThroughPlatform)
    }

    @Test
    fun changingModeRequestsOneRestartThroughTheExistingSettingsFlow() {
        SettingsChangeManager.consumeRestartService()
        SettingsChangeManager.consumeSetupGroupTab()
        SettingsChangeManager.notifySettingChanged(AppConfig.PREF_ROUTING_NODE_CORES)
        assertTrue(SettingsChangeManager.consumeRestartService())
        assertFalse(SettingsChangeManager.consumeRestartService())
        SettingsChangeManager.consumeSetupGroupTab()
    }
}
