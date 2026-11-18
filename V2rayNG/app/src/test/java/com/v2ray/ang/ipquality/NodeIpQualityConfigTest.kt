package com.v2ray.ang.ipquality

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class NodeIpQualityConfigTest {
    @Test
    fun lookupCannotFollowProductionRoutesOrBindProductionPorts() {
        val raw = """
            {
              "inbounds":[{"listen":"0.0.0.0","port":10808,"protocol":"socks"}],
              "outbounds":[
                {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"node.invalid"}]}},
                {"tag":"direct","protocol":"freedom"}
              ],
              "routing":{"rules":[{"domain":["api4.ipify.org"],"outboundTag":"direct"}]},
              "api":{"tag":"api"},"dns":{"servers":["localhost"]},"metrics":{"listen":"0.0.0.0:9999"},
              "observatory":{},"burstObservatory":{},"reverse":{},"stats":{},"policy":{}
            }
        """.trimIndent()
        val original = JsonParser.parseString(raw).asJsonObject
        val config = JsonParser.parseString(NodeIpQualityConfig.build(raw, 12345, "user", "secret")).asJsonObject
        assertEquals(original["outbounds"], config["outbounds"])
        val listeners = config.getAsJsonArray("inbounds")
        assertEquals(1, listeners.size())
        val listener = listeners[0].asJsonObject
        assertEquals("127.0.0.1", listener["listen"].asString)
        assertEquals(12345, listener["port"].asInt)
        assertEquals("password", listener.getAsJsonObject("settings")["auth"].asString)
        val account = listener.getAsJsonObject("settings").getAsJsonArray("accounts")[0].asJsonObject
        assertEquals("user", account["user"].asString)
        assertEquals("secret", account["pass"].asString)
        val rules = config.getAsJsonObject("routing").getAsJsonArray("rules")
        assertEquals(1, rules.size())
        assertEquals("proxy", rules[0].asJsonObject["outboundTag"].asString)
        listOf("api", "dns", "metrics", "observatory", "burstObservatory", "reverse", "stats", "policy")
            .forEach { assertFalse("Removed $it", config.has(it)) }
    }

    @Test
    fun customConfigWithoutTagsRetainsItsProxyChainAndTransport() {
        val raw = """{"outbounds":[{"protocol":"socks","proxySettings":{"tag":"upstream"}},
            {"tag":"upstream","protocol":"vless"}],"transport":{"tcpSettings":{}}}"""
        val config = JsonParser.parseString(NodeIpQualityConfig.build(raw, 12345, "u", "s")).asJsonObject
        val first = config.getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("quality-target", first["tag"].asString)
        assertEquals("upstream", first.getAsJsonObject("proxySettings")["tag"].asString)
        assertTrue(config.has("transport"))
    }

    @Test
    fun missingOrDirectDefaultCannotBeReportedAsANodesExit() {
        listOf("""{"outbounds":[]}""", """{"outbounds":[{"protocol":"freedom"}]}""",
            """{"outbounds":[{"protocol":"blackhole"}]}""").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                NodeIpQualityConfig.build(raw, 12345, "u", "s")
            }
        }
    }
}
