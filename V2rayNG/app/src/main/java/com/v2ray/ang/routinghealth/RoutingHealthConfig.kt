package com.v2ray.ang.routinghealth

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.RuntimeCore
import com.v2ray.ang.core.RoutingTargets
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils
import java.util.UUID

internal data class RoutingHealthConfig(val content: String, val endpoints: List<RoutingHealthEndpoint>) {
    companion object {
        fun prepare(content: String, core: RuntimeCore): RoutingHealthConfig =
            inject(content, core, MmkvManager.decodeRoutingRulesets().orEmpty(),
                { rule -> RoutingTargets.profile(rule)?.remarks ?: rule.outboundTag },
                Utils::findRandomFreePort)

        /** Probe-only authenticated loopback inbounds route through the actual running outbounds. */
        fun inject(
            content: String,
            core: RuntimeCore,
            rules: List<RulesetItem>,
            name: (RulesetItem) -> String,
            allocatePort: () -> Int,
        ): RoutingHealthConfig {
            val targets = rules.filter { it.enabled && RoutingTargets.tag(it) !in AppConfig.BUILTIN_OUTBOUND_TAGS }
                .groupBy(RoutingTargets::tag)
            if (targets.isEmpty()) return RoutingHealthConfig(content, emptyList())
            val root = JsonParser.parseString(content).asJsonObject
            val singBox = core == RuntimeCore.SING_BOX
            val routeKey = if (singBox) "route" else "routing"
            val route = root.getAsJsonObject(routeKey) ?: JsonObject().also { root.add(routeKey, it) }
            val outbounds = (root.getAsJsonArray("outbounds") ?: JsonArray()).mapNotNull {
                it.asJsonObject.get("tag")?.asString
            }.toSet()
            val balancers = (route.getAsJsonArray("balancers") ?: JsonArray()).mapNotNull {
                it.asJsonObject.get("tag")?.asString
            }.toSet()
            val inbounds = root.getAsJsonArray("inbounds") ?: JsonArray().also { root.add("inbounds", it) }
            val ports = inbounds.mapNotNull {
                it.asJsonObject.get(if (singBox) "listen_port" else "port")?.asInt
            }.toMutableSet()
            // Auxiliary listeners have reserved ports but may not be started yet.
            root.getAsJsonArray("outbounds")?.forEach { value ->
                val outbound = value.asJsonObject
                if (outbound.get("server")?.asString == AppConfig.LOOPBACK) {
                    outbound.get("server_port")?.asInt?.let(ports::add)
                }
                outbound.getAsJsonObject("settings")?.getAsJsonArray("servers")?.forEach { server ->
                    if (server.asJsonObject.get("address")?.asString == AppConfig.LOOPBACK) {
                        server.asJsonObject.get("port")?.asInt?.let(ports::add)
                    }
                }
            }
            val probeRules = JsonArray()
            val endpoints = targets.entries.mapIndexed { index, (tag, matching) ->
                val status = RoutingHealthStatus(tag, name(matching.first()).take(120),
                    matching.map { it.remarks.orEmpty().ifBlank { it.id }.take(120) }.distinct().take(20))
                val balancer = "${AppConfig.TAG_BALANCER_PRE}-$tag"
                if (tag !in outbounds && balancer !in balancers) {
                    return@mapIndexed RoutingHealthEndpoint(status.copy(state = RoutingHealthState.missing))
                }
                // Bound listener/resource usage for unusually large routing configurations.
                if (index >= 32) return@mapIndexed RoutingHealthEndpoint(status)
                var port = runCatching(allocatePort).getOrDefault(0)
                var attempts = 0
                while ((port !in 1..65535 || port in ports) && attempts++ < 10) {
                    port = runCatching(allocatePort).getOrDefault(0)
                }
                if (port !in 1..65535 || !ports.add(port)) return@mapIndexed RoutingHealthEndpoint(status)
                val endpoint = RoutingHealthEndpoint(status, port, UUID.randomUUID().toString(), UUID.randomUUID().toString())
                val inboundTag = "routing-health-${UUID.randomUUID()}"
                inbounds.add(JsonObject().apply {
                    addProperty("tag", inboundTag)
                    addProperty("listen", AppConfig.LOOPBACK)
                    addProperty(if (singBox) "type" else "protocol", "socks")
                    addProperty(if (singBox) "listen_port" else "port", port)
                    val account = JsonObject().apply {
                        addProperty(if (singBox) "username" else "user", endpoint.username)
                        addProperty(if (singBox) "password" else "pass", endpoint.secret)
                    }
                    if (singBox) add("users", JsonArray().apply { add(account) })
                    else add("settings", JsonObject().apply {
                        addProperty("auth", "password")
                        addProperty("udp", false)
                        add("accounts", JsonArray().apply { add(account) })
                    })
                })
                probeRules.add(JsonObject().apply {
                    add(if (singBox) "inbound" else "inboundTag", JsonArray().apply { add(inboundTag) })
                    if (singBox) {
                        addProperty("action", "route")
                        addProperty("outbound", tag)
                    } else {
                        addProperty("type", "field")
                        addProperty(if (tag in outbounds) "outboundTag" else "balancerTag",
                            if (tag in outbounds) tag else balancer)
                    }
                })
                endpoint
            }
            route.getAsJsonArray("rules")?.forEach(probeRules::add)
            route.add("rules", probeRules)
            return RoutingHealthConfig(root.toString(), endpoints)
        }
    }
}
