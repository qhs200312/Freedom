package com.v2ray.ang.routinghealth

import com.google.gson.JsonParser
import com.v2ray.ang.core.RuntimeCore
import com.v2ray.ang.dto.entities.RulesetItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoutingHealthTest {
    private val endpoint = RoutingHealthEndpoint(
        RoutingHealthStatus("canada", "Canada", listOf("Video")), 12000, "user", "secret",
    )

    @Test
    fun firstSuccessStopsAndFirstFailureUsesIndependentDestination(): Unit = runBlocking {
        for (firstWorks in listOf(true, false)) {
            val calls = mutableListOf<String>()
            val check = RoutingHealthCheck({ passedEndpoint, url ->
                assertEquals(endpoint, passedEndpoint)
                calls += url
                firstWorks || calls.size == 2
            }, { 42L }, 0)
            val result = check.check(endpoint)
            assertEquals(RoutingHealthState.healthy, result.state)
            assertEquals(42L, result.checkedAt)
            assertEquals(if (firstWorks) 1 else 2, calls.size)
            assertEquals(calls.size, calls.distinct().size)
        }
    }

    @Test
    fun twoFailuresRequiredBeforeUnavailableAndMissingTargetNeverProbes(): Unit = runBlocking {
        var calls = 0
        val check = RoutingHealthCheck({ _, _ -> calls++; throw java.io.IOException() }, retryDelayMillis = 0)
        assertEquals(RoutingHealthState.unavailable, check.check(endpoint).state)
        assertEquals(2, calls)
        val missing = endpoint.copy(port = 0, status = endpoint.status.copy(state = RoutingHealthState.missing))
        assertEquals(missing.status, check.check(missing))
        assertEquals(2, calls)
    }

    @Test
    fun cancellationDoesNotBecomeFailureOrStartRetry(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val check = RoutingHealthCheck({ _, _ -> calls++; started.complete(Unit); awaitCancellation() }, retryDelayMillis = 0)
        val job = launch { check.check(endpoint); fail("Cancelled lookup returned a result") }
        started.await()
        job.cancelAndJoin()
        assertEquals(1, calls)
    }

    @Test
    fun bothCoresUseAuthenticatedLoopbackAndPrependOnlyProbeRules() {
        for (core in RuntimeCore.entries) {
            val singBox = core == RuntimeCore.SING_BOX
            val routeKey = if (singBox) "route" else "routing"
            val original = """{"inbounds":[],"outbounds":[{"tag":"canada"},{"tag":"proxy"}],"$routeKey":{"rules":[{"existing":true}]}}"""
            val config = RoutingHealthConfig.inject(original, core, listOf(
                RulesetItem(id = "1", remarks = "Video", outboundTag = "canada"),
                RulesetItem(id = "2", remarks = "News", outboundTag = "canada"),
                RulesetItem(id = "3", outboundTag = "direct"),
                RulesetItem(id = "4", outboundTag = "disabled", enabled = false),
            ), { "Canada" }, { 12000 })
            assertEquals(1, config.endpoints.size)
            assertEquals(listOf("Video", "News"), config.endpoints.single().status.rules)
            val root = JsonParser.parseString(config.content).asJsonObject
            val inbound = root.getAsJsonArray("inbounds").single().asJsonObject
            assertEquals("127.0.0.1", inbound["listen"].asString)
            val account = if (singBox) inbound.getAsJsonArray("users").single().asJsonObject
                else inbound.getAsJsonObject("settings").getAsJsonArray("accounts").single().asJsonObject
            assertEquals(config.endpoints.single().secret, account[if (singBox) "password" else "pass"].asString)
            val rules = root.getAsJsonObject(routeKey).getAsJsonArray("rules")
            assertEquals("canada", rules[0].asJsonObject[if (singBox) "outbound" else "outboundTag"].asString)
            assertEquals(inbound["tag"].asString, rules[0].asJsonObject
                .getAsJsonArray(if (singBox) "inbound" else "inboundTag").single().asString)
            assertTrue(rules[1].asJsonObject["existing"].asBoolean)
            assertEquals(2, root.getAsJsonArray("outbounds").size())
        }
    }

    @Test
    fun policyGroupUsesActualBalancerAndMissingNodeNeverFallsBackToProxy() {
        val config = RoutingHealthConfig.inject(
            """{"inbounds":[],"outbounds":[{"tag":"proxy"}],"routing":{"balancers":[{"tag":"balancer-group"}],"rules":[]}}""",
            RuntimeCore.XRAY, listOf(
                RulesetItem(outboundTag = "group"), RulesetItem(outboundTag = "missing"),
            ), { it.outboundTag }, { 12000 },
        )
        assertEquals(RoutingHealthState.missing, config.endpoints.last().status.state)
        assertEquals(0, config.endpoints.last().port)
        val root = JsonParser.parseString(config.content).asJsonObject
        val rule = root.getAsJsonObject("routing").getAsJsonArray("rules").single().asJsonObject
        assertEquals("balancer-group", rule["balancerTag"].asString)
        assertFalse(rule.has("outboundTag"))
    }

    @Test
    fun duplicatePortsAreRetriedAndListenersAreBounded() {
        var attempts = 0
        val rules = (1..40).map { RulesetItem(outboundTag = "node$it") }
        val outbounds = rules.joinToString { """{"tag":"${it.outboundTag}"}""" }
        val config = RoutingHealthConfig.inject(
            """{"inbounds":[{"port":12000}],"outbounds":[$outbounds],"routing":{"rules":[]}}""",
            RuntimeCore.XRAY, rules, { it.outboundTag }, { 12000 + attempts++ },
        )
        assertEquals(32, config.endpoints.count { it.port > 0 })
        assertEquals(32, config.endpoints.filter { it.port > 0 }.map { it.port }.distinct().size)
        assertTrue(config.endpoints.none { it.port == 12000 })
        assertEquals(8, config.endpoints.count { it.port == 0 && it.status.state == RoutingHealthState.unknown })
    }

    @Test
    fun failedProbePortAllocationDoesNotBreakTheProxyConfig() {
        val original = """{"inbounds":[],"outbounds":[{"tag":"canada"}],"routing":{"rules":[{"existing":true}]}}"""
        val config = RoutingHealthConfig.inject(original, RuntimeCore.XRAY,
            listOf(RulesetItem(outboundTag = "canada")), { "Canada" },
            { throw java.io.IOException("No free port") })
        assertEquals(0, config.endpoints.single().port)
        assertEquals(RoutingHealthState.unknown, config.endpoints.single().status.state)
        val root = JsonParser.parseString(config.content).asJsonObject
        assertEquals(0, root.getAsJsonArray("inbounds").size())
        assertTrue(root.getAsJsonObject("routing").getAsJsonArray("rules").single().asJsonObject["existing"].asBoolean)
    }

    @Test
    fun probeDoesNotStealAnAuxiliaryCorePortBeforeItStarts() {
        for (core in RuntimeCore.entries) {
            val outbound = if (core == RuntimeCore.SING_BOX) {
                """{"tag":"canada","type":"socks","server":"127.0.0.1","server_port":12000}"""
            } else """{"tag":"canada","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":12000}]}}"""
            var nextPort = 12000
            val config = RoutingHealthConfig.inject("""{"inbounds":[],"outbounds":[$outbound]}""",
                core, listOf(RulesetItem(outboundTag = "canada")), { "Canada" }, { nextPort++ })
            assertEquals(12001, config.endpoints.single().port)
        }
    }

    @Test
    fun failedWarningRemainsDuringRetryAndClearsOnRecovery(): Unit = runBlocking {
        var works = false
        val check = RoutingHealthCheck({ _, _ -> works }, retryDelayMillis = 0)
        val failed = check.check(endpoint)
        assertTrue(failed.isUnavailable)
        val retrying = failed.copy(state = RoutingHealthState.retrying)
        assertTrue(retrying.isUnavailable)
        assertTrue(retrying.isChecking)
        works = true
        val recovered = check.check(endpoint)
        assertFalse(recovered.isUnavailable)
        assertFalse(recovered.isChecking)
        assertEquals(RoutingHealthState.healthy, recovered.state)
    }
}
