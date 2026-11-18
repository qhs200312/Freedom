package com.v2ray.ang.ipquality

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal object NodeIpQualityConfig {
    /** Replace listeners and routing so lookups use this node, including for custom configs. */
    fun build(raw: String, port: Int, username: String, secret: String): String {
        require(port in 1..65535 && username.isNotBlank() && secret.isNotBlank())
        val root = JsonParser.parseString(raw).asJsonObject
        val outbounds = requireNotNull(root.getAsJsonArray("outbounds"))
        require(outbounds.size() > 0)
        val first = outbounds[0].asJsonObject
        val target = first.get("tag")?.asString?.takeIf { it.isNotBlank() }
            ?: "quality-target".also { first.addProperty("tag", it) }
        require(first.get("protocol")?.asString !in setOf("freedom", "blackhole", "dns")) {
            "A connection test must have a proxy as its default outbound"
        }
        root.add("inbounds", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("tag", "quality-in")
                addProperty("listen", "127.0.0.1")
                addProperty("port", port)
                addProperty("protocol", "socks")
                add("settings", JsonObject().apply {
                    addProperty("auth", "password")
                    addProperty("udp", false)
                    add("accounts", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("user", username)
                            addProperty("pass", secret)
                        })
                    })
                })
            })
        })
        root.add("routing", JsonObject().apply {
            addProperty("domainStrategy", "AsIs")
            add("rules", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "field")
                    add("inboundTag", JsonArray().apply { add("quality-in") })
                    addProperty("outboundTag", target)
                })
            })
        })
        listOf("api", "observatory", "burstObservatory", "reverse", "metrics", "dns", "fakedns",
            "stats", "policy").forEach(root::remove)
        return root.toString()
    }
}
