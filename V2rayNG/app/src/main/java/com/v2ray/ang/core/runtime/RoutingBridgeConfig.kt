package com.v2ray.ang.core.runtime

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreOutboundBuilder
import com.v2ray.ang.core.SingBoxConfigGenerator
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.util.JsonUtil

internal object RoutingBridgeConfig {
    fun xray(
        entries: List<RoutingBridgeEntry>,
        convert: (ProfileItem) -> V2rayConfig.OutboundBean? = CoreOutboundBuilder::convert,
    ): String {
        require(entries.isNotEmpty())
        return JsonObject().apply {
            add("log", JsonObject().apply { addProperty("loglevel", "warning") })
            add("inbounds", JsonArray().apply {
                entries.forEach { entry ->
                    add(JsonObject().apply {
                        addProperty("tag", entry.tag)
                        addProperty("listen", AppConfig.LOOPBACK)
                        addProperty("port", entry.local.serverPort!!.toInt())
                        addProperty("protocol", "socks")
                        add("settings", JsonObject().apply {
                            addProperty("auth", "password")
                            addProperty("udp", true)
                            addProperty("ip", AppConfig.LOOPBACK)
                            add("accounts", JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("user", entry.local.username)
                                    addProperty("pass", entry.local.password)
                                })
                            })
                        })
                    })
                }
            })
            add("outbounds", JsonArray().apply {
                // Unexpected inbound traffic must not select an arbitrary remote node.
                add(JsonObject().apply {
                    addProperty("tag", "blocked")
                    addProperty("protocol", "blackhole")
                })
                entries.forEach { entry ->
                    val outbound = requireNotNull(convert(entry.original)) { "Unsupported auxiliary Xray node" }
                    outbound.tag = entry.tag
                    add(JsonUtil.parseString(JsonUtil.toJson(outbound)))
                }
            })
            add("routing", JsonObject().apply {
                addProperty("domainStrategy", "AsIs")
                add("rules", JsonArray().apply {
                    entries.forEach { entry ->
                        add(JsonObject().apply {
                            addProperty("type", "field")
                            add("inboundTag", JsonArray().apply { add(entry.tag) })
                            addProperty("outboundTag", entry.tag)
                        })
                    }
                })
            })
        }.toString()
    }

    fun singBox(entries: List<RoutingBridgeEntry>): String {
        require(entries.isNotEmpty())
        val first = entries.first()
        val rest = entries.drop(1)
        val root = requireNotNull(JsonUtil.parseString(SingBoxConfigGenerator.generate(
            profile = NodeProfile.from(first.original),
            socksPort = first.local.serverPort!!.toInt(),
            httpPort = first.local.serverPort!!.toInt(),
            logLevel = "warn",
            routingRules = rest.map { RulesetItem(domain = listOf("full:bridge.invalid"), outboundTag = it.tag) },
            routingProfiles = rest.associate { it.tag to NodeProfile.from(it.original) },
        ))).asJsonObject
        root.add("inbounds", JsonArray().apply {
            entries.forEach { entry ->
                add(JsonObject().apply {
                    addProperty("type", "socks")
                    addProperty("tag", entry.tag)
                    addProperty("listen", AppConfig.LOOPBACK)
                    addProperty("listen_port", entry.local.serverPort!!.toInt())
                    add("users", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("username", entry.local.username)
                            addProperty("password", entry.local.password)
                        })
                    })
                })
            }
        })
        root.add("route", JsonObject().apply {
            addProperty("auto_detect_interface", true)
            add("rules", JsonArray().apply {
                entries.forEachIndexed { index, entry ->
                    add(JsonObject().apply {
                        add("inbound", JsonArray().apply { add(entry.tag) })
                        addProperty("action", "route")
                        addProperty("outbound", if (index == 0) AppConfig.TAG_PROXY else entry.tag)
                    })
                }
                add(JsonObject().apply { addProperty("action", "reject") })
            })
        })
        return root.toString()
    }
}
