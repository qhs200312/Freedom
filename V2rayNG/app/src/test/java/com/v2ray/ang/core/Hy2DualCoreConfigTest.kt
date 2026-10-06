package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.PreferredCore
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Hy2DualCoreConfigTest {
    @Test
    fun oldXrayProfileMapsToCoreNeutralFields() {
        val oldMask = """{
            "udp":[{"type":"salamander","settings":{"password":"obfs-secret"}}],
            "quicParams":{"brutalUp":"100Mbps","brutalDown":"200Mbps","udpHop":{"ports":"30000-40000","interval":"30"}}
        }"""
        val node = NodeProfile.from(ProfileItem.create(EConfigType.HYSTERIA2).copy(
            server = "example.com", serverPort = "443", password = "auth", security = "tls", finalMask = oldMask
        ))

        assertEquals(100, node.upMbps)
        assertEquals(200, node.downMbps)
        assertEquals("30000-40000", node.hopPorts)
        assertEquals("30", node.hopInterval)
        assertEquals("obfs-secret", node.obfsPassword)
    }

    @Test
    fun nodeProfileGeneratesSingBoxHysteria2Schema() {
        val config = SingBoxConfigGenerator.generate(
            profile = standardNode(), socksPort = 10808, httpPort = 10809, logLevel = "warn"
        )
        val root = JsonUtil.parseString(config)!!.asJsonObject
        val outbound = root.getAsJsonArray("outbounds")[0].asJsonObject

        assertEquals("hysteria2", outbound.get("type").asString)
        assertEquals("30000:40000", outbound.getAsJsonArray("server_ports")[0].asString)
        assertEquals("30s", outbound.get("hop_interval").asString)
        assertEquals(100, outbound.get("up_mbps").asInt)
        assertEquals(200, outbound.get("down_mbps").asInt)
        assertEquals("salamander", outbound.getAsJsonObject("obfs").get("type").asString)
        assertEquals("example.com", outbound.getAsJsonObject("tls").get("server_name").asString)
        assertEquals(1200, outbound.get("initial_packet_size").asInt)
        assertTrue(outbound.get("disable_path_mtu_discovery").asBoolean)
        assertFalse(config.contains("force-brutal"))
    }

    @Test
    fun localDnsUsesCurrentSingBoxDnsSchema() {
        val config = SingBoxConfigGenerator.generate(
            profile = standardNode(),
            socksPort = 10808,
            httpPort = 10809,
            logLevel = "warn",
            routingRules = listOf(
                com.v2ray.ang.dto.entities.RulesetItem(
                    domain = listOf("domain:example.cn"),
                    outboundTag = "direct",
                )
            ),
            localDnsEnabled = true,
            remoteDnsServers = listOf("https://cloudflare-dns.com/dns-query"),
            domesticDnsServers = listOf("223.5.5.5"),
        )
        val root = JsonUtil.parseString(config)!!.asJsonObject
        val servers = root.getAsJsonObject("dns").getAsJsonArray("servers")
        val remote = servers[0].asJsonObject
        val domestic = servers[1].asJsonObject

        assertEquals(3, servers.size())
        assertEquals("bootstrap-local", servers[2].asJsonObject.get("tag").asString)
        assertEquals("https", remote.get("type").asString)
        assertEquals("1.1.1.1", remote.get("server").asString)
        assertEquals("/dns-query", remote.get("path").asString)
        assertEquals("proxy", remote.get("detour").asString)
        assertEquals("cloudflare-dns.com", remote.getAsJsonObject("tls").get("server_name").asString)
        assertFalse(remote.has("domain_resolver"))
        assertEquals("udp", domestic.get("type").asString)
        assertFalse(domestic.has("detour"))
        assertEquals("remote-dns-0", root.getAsJsonObject("dns").get("final").asString)
        assertTrue(root.getAsJsonObject("route").getAsJsonArray("rules").any {
            it.asJsonObject.get("action")?.asString == "hijack-dns"
        })
        assertEquals("hijack-dns", root.getAsJsonObject("route")
            .getAsJsonArray("rules")[0].asJsonObject.get("action").asString)
    }

    @Test
    fun fakeDnsSettingIsIgnoredForSingBoxCompatibility() {
        val config = SingBoxConfigGenerator.generate(
            profile = standardNode(),
            socksPort = 10808,
            httpPort = 10809,
            logLevel = "warn",
            localDnsEnabled = true,
            fakeDnsEnabled = true,
            remoteDnsServers = listOf("https://cloudflare-dns.com/dns-query"),
            domesticDnsServers = listOf("223.5.5.5"),
        )
        val root = JsonUtil.parseString(config)!!.asJsonObject
        val dns = root.getAsJsonObject("dns")
        assertTrue(dns.getAsJsonArray("servers").none {
            it.asJsonObject.get("type")?.asString == "fakeip"
        })
        assertTrue(root.get("experimental") == null)
    }

    @Test
    fun stunIsSniffedAndForcedThroughProxy() {
        val config = SingBoxConfigGenerator.generate(
            profile = standardNode(),
            socksPort = 10808,
            httpPort = 10809,
            logLevel = "warn",
        )
        val rules = JsonUtil.parseString(config)!!.asJsonObject
            .getAsJsonObject("route").getAsJsonArray("rules")

        assertTrue(rules.any {
            it.asJsonObject.get("action")?.asString == "route" &&
                it.asJsonObject.get("protocol")?.asString == "stun" &&
                it.asJsonObject.get("outbound")?.asString == "proxy"
        })
        assertTrue(rules.any {
            it.asJsonObject.getAsJsonArray("domain")?.any { domain ->
                domain.asString == "stun.cloudflare.com"
            } == true && it.asJsonObject.get("outbound")?.asString == "proxy"
        })
        assertTrue(rules.none { it.asJsonObject.get("action")?.asString == "reject" })
    }

    @Test
    fun unsupportedGeoipRuleDoesNotBecomeCatchAllProxy() {
        val config = SingBoxConfigGenerator.generate(
            profile = standardNode(),
            socksPort = 10808,
            httpPort = 10809,
            logLevel = "warn",
            routingRules = listOf(
                RulesetItem(
                    remarks = "Google IP",
                    ip = listOf("geoip:google"),
                    outboundTag = AppConfig.TAG_PROXY,
                ),
                RulesetItem(
                    remarks = "China IP",
                    ip = listOf(AppConfig.GEOIP_CN),
                    outboundTag = AppConfig.TAG_DIRECT,
                ),
            ),
            ruleSetDir = java.io.File("src/main/assets").absolutePath,
        )
        val rules = JsonUtil.parseString(config)!!.asJsonObject
            .getAsJsonObject("route").getAsJsonArray("rules")

        assertTrue(rules.none { rule ->
            val objectRule = rule.asJsonObject
            objectRule.entrySet().map { it.key }.toSet() == setOf("action", "outbound") &&
                objectRule.get("outbound")?.asString == AppConfig.TAG_PROXY
        })
        assertTrue(rules.any { rule ->
            rule.asJsonObject.getAsJsonArray("rule_set")?.any { it.asString == "geoip-cn" } == true
        })
    }

    @Test
    fun nativeVpnConfigIncludesTunAndKeepsLocalSocksInbound() {
        val config = SingBoxConfigGenerator.generate(
            profile = standardNode(),
            socksPort = 10808,
            httpPort = 10809,
            logLevel = "warn",
            enableTun = true,
            tunMtu = 1500,
            tunAddresses = listOf("10.10.14.1/30", "fc00::10:10:14:1/126"),
        )
        val root = JsonUtil.parseString(config)!!.asJsonObject
        val inbounds = root.getAsJsonArray("inbounds")
        val tun = inbounds.first { it.asJsonObject.get("type")?.asString == "tun" }.asJsonObject

        assertEquals("gvisor", tun.get("stack").asString)
        assertTrue(tun.get("auto_route").asBoolean)
        assertEquals("native", tun.get("dns_mode").asString)
        assertEquals("1.1.1.1", tun.getAsJsonArray("dns_address")[0].asString)
        assertEquals("0.0.0.0/0", tun.getAsJsonArray("route_address")[0].asString)
        assertEquals(1500, tun.get("mtu").asInt)
        assertTrue(tun.getAsJsonArray("address").any { it.asString == "10.10.14.1/30" })
        assertTrue(inbounds.any { it.asJsonObject.get("type")?.asString == "mixed" })
        assertTrue(root.getAsJsonObject("route").get("auto_detect_interface").asBoolean)
        assertTrue(root.getAsJsonObject("route").getAsJsonArray("rules").any { rule ->
            rule.asJsonObject.get("action")?.asString == "sniff" &&
                rule.asJsonObject.getAsJsonArray("sniffer")?.any { it.asString == "tls" } == true
        })
    }

    @Test
    fun domainNodeBootstrapNeverDependsOnItsOwnProxy() {
        val config = SingBoxConfigGenerator.generate(
            profile = standardNode(), socksPort = 10808, httpPort = 10809,
            logLevel = "warn", localDnsEnabled = true,
        )
        val root = JsonUtil.parseString(config)!!.asJsonObject
        val proxy = root.getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("bootstrap-local", proxy.get("domain_resolver").asString)
        val bootstrap = root.getAsJsonObject("dns").getAsJsonArray("servers")
            .first { it.asJsonObject.get("tag").asString == "bootstrap-local" }.asJsonObject
        assertEquals("local", bootstrap.get("type").asString)
        assertFalse(bootstrap.has("detour"))
    }

    @Test
    fun domainNodeHasBootstrapEvenWhenApplicationDnsIsDisabled() {
        val config = SingBoxConfigGenerator.generate(
            profile = standardNode(), socksPort = 10808, httpPort = 10809,
            logLevel = "warn", localDnsEnabled = false,
        )
        val root = JsonUtil.parseString(config)!!.asJsonObject
        assertEquals("bootstrap-local", root.getAsJsonObject("dns").get("final").asString)
        assertEquals(1, root.getAsJsonObject("dns").getAsJsonArray("servers").size())
    }

    @Test
    fun nodeProfileGeneratesCurrentXrayUdpHopFinalMask() {
        val profile = ProfileItem.create(EConfigType.HYSTERIA2).apply {
            server = "example.com"
            serverPort = "443"
            password = "auth"
            network = NetworkType.HYSTERIA.type
            portHopping = "30000-40000"
            portHoppingInterval = "30"
            obfsPassword = "obfs-secret"
            bandwidthUp = "100Mbps"
            bandwidthDown = "200Mbps"
        }
        val stream = V2rayConfig.OutboundBean.StreamSettingsBean()
        CoreOutboundBuilder.populateTransportSettings(stream, profile)
        val json = JsonUtil.parseString(JsonUtil.toJson(stream.finalmask))!!.asJsonObject
        val masks = json.getAsJsonArray("udp")

        assertTrue(masks.any { it.asJsonObject.get("type").asString == "salamander" })
        val hop = masks.first { it.asJsonObject.get("type").asString == "udphop" }.asJsonObject
        assertEquals("intervalRemote", hop.getAsJsonObject("settings").get("mode").asString)
        assertEquals("30000-40000", hop.getAsJsonObject("settings").get("remotePorts").asString)
        assertNotNull(json.getAsJsonObject("quicParams"))
        assertFalse(json.getAsJsonObject("quicParams").has("udpHop"))
    }

    @Test
    fun explicitCoreChoiceIsPersistedInProfile() {
        val item = ProfileItem.create(EConfigType.HYSTERIA2).copy(preferredCore = "SING_BOX")
        assertEquals(PreferredCore.SING_BOX, NodeProfile.from(item).preferredCore)
    }

    private fun standardNode() = NodeProfile(
        remarks = "hy2",
        protocol = EConfigType.HYSTERIA2,
        server = "example.com",
        port = 443,
        password = "auth",
        tlsEnabled = true,
        sni = "example.com",
        insecure = false,
        alpn = listOf("h3"),
        obfsType = "salamander",
        obfsPassword = "obfs-secret",
        upMbps = 100,
        downMbps = 200,
        hopPorts = "30000-40000",
        hopInterval = "30",
    )
}
