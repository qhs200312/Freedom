package com.v2ray.ang.core

import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxProtocolConfigTest {
    @Test
    fun supportedProtocolsGenerateExpectedOutboundTypes() {
        val profiles = listOf(
            base(EConfigType.VMESS).copy(password = UUID, method = "auto"),
            base(EConfigType.VLESS).copy(password = UUID, method = "none"),
            base(EConfigType.TROJAN).copy(password = "secret", security = "tls", tlsEnabled = true),
            base(EConfigType.SHADOWSOCKS).copy(password = "secret", method = "aes-256-gcm"),
            base(EConfigType.SOCKS).copy(username = "user", password = "secret"),
            base(EConfigType.HTTP).copy(username = "user", password = "secret"),
        )

        val expectedTypes = listOf("vmess", "vless", "trojan", "shadowsocks", "socks", "http")
        profiles.zip(expectedTypes).forEach { (profile, expectedType) ->
            assertTrue(CoreSelector.canGenerateSingBox(profile))
            val root = generate(profile)
            val outbound = root.getAsJsonArray("outbounds")[0].asJsonObject
            assertEquals(expectedType, outbound.get("type").asString)
        }
    }

    @Test
    fun vlessWebsocketRealityMapsTransportAndTls() {
        val profile = base(EConfigType.VLESS).copy(
            password = UUID,
            method = "none",
            network = "ws",
            host = "cdn.example.com",
            path = "/ws",
            security = "reality",
            tlsEnabled = true,
            sni = "www.example.com",
            fingerprint = "chrome",
            realityPublicKey = "public-key",
            realityShortId = "abcd",
        )
        val outbound = generate(profile).getAsJsonArray("outbounds")[0].asJsonObject

        assertEquals("ws", outbound.getAsJsonObject("transport").get("type").asString)
        assertEquals("/ws", outbound.getAsJsonObject("transport").get("path").asString)
        assertTrue(outbound.getAsJsonObject("tls").getAsJsonObject("reality").get("enabled").asBoolean)
    }

    @Test
    fun wireGuardUsesEndpointSchema() {
        val profile = base(EConfigType.WIREGUARD).copy(
            wireGuardPrivateKey = KEY_ONE,
            wireGuardPublicKey = KEY_TWO,
            wireGuardLocalAddresses = listOf("10.0.0.2/32"),
            wireGuardReserved = listOf(1, 2, 3),
            wireGuardMtu = 1420,
        )
        val root = generate(profile)

        assertEquals("wireguard", root.getAsJsonArray("endpoints")[0].asJsonObject.get("type").asString)
        assertEquals("direct", root.getAsJsonArray("outbounds")[0].asJsonObject.get("type").asString)
    }

    @Test
    fun xrayOnlyTransportIsRejected() {
        val profile = base(EConfigType.VLESS).copy(password = UUID, network = "xhttp")
        assertFalse(CoreSelector.canGenerateSingBox(profile))
    }

    @Test
    fun geminiLiveIsAllowedBeforeSharedGoogleApiBlock() {
        val root = JsonUtil.parseString(
            SingBoxConfigGenerator.generate(
                profile = base(EConfigType.VLESS).copy(password = UUID, method = "none"),
                socksPort = 10808,
                httpPort = 10809,
                logLevel = "warn",
                blockGoogleLocation = true,
            )
        )!!.asJsonObject
        val rules = root.getAsJsonObject("route").getAsJsonArray("rules")
        val liveRuleIndex = rules.indexOfFirst {
            it.asJsonObject.getAsJsonArray("domain")?.any { domain ->
                domain.asString == "robinfrontend-pa.googleapis.com"
            } == true
        }
        val blockRuleIndex = rules.indexOfFirst {
            it.asJsonObject.getAsJsonArray("domain")?.any { domain ->
                domain.asString == "www.googleapis.com"
            } == true
        }

        assertTrue(liveRuleIndex >= 0)
        assertTrue(blockRuleIndex > liveRuleIndex)
        assertEquals("proxy", rules[liveRuleIndex].asJsonObject.get("outbound").asString)
        assertEquals("reject", rules[blockRuleIndex].asJsonObject.get("action").asString)
    }

    @Test
    fun sharedMapsSdkEndpointsAreProxiedOrStrictlyBlocked() {
        fun sharedRule(strictBlock: Boolean) = JsonUtil.parseString(
            SingBoxConfigGenerator.generate(
                profile = base(EConfigType.VLESS).copy(password = UUID, method = "none"),
                socksPort = 10808,
                httpPort = 10809,
                logLevel = "warn",
                blockGoogleMaps = true,
                strictBlockGoogleMapsSdk = strictBlock,
            )
        )!!.asJsonObject.getAsJsonObject("route").getAsJsonArray("rules").first {
            it.asJsonObject.getAsJsonArray("domain")?.any { domain ->
                domain.asString == "clients4.google.com"
            } == true
        }.asJsonObject

        val proxied = sharedRule(strictBlock = false)
        assertEquals("route", proxied.get("action").asString)
        assertEquals("proxy", proxied.get("outbound").asString)

        val blocked = sharedRule(strictBlock = true)
        assertEquals("reject", blocked.get("action").asString)
        assertEquals(
            listOf("clients4.google.com", "csi.gstatic.com"),
            blocked.getAsJsonArray("domain").map { it.asString },
        )
    }

    @Test
    fun sharedMapsSdkEndpointsAreBlockedByDefault() {
        val root = JsonUtil.parseString(SingBoxConfigGenerator.generate(
            profile = base(EConfigType.VLESS).copy(password = UUID, method = "none"),
            socksPort = 10808,
            httpPort = 10809,
            logLevel = "warn",
            blockGoogleMaps = true,
        ))!!.asJsonObject
        val rule = root.getAsJsonObject("route").getAsJsonArray("rules").first {
            it.asJsonObject.getAsJsonArray("domain")?.any { domain ->
                domain.asString == "clients4.google.com"
            } == true
        }.asJsonObject
        assertEquals("reject", rule.get("action").asString)
    }

    private fun generate(profile: NodeProfile) = JsonUtil.parseString(
        SingBoxConfigGenerator.generate(
            profile = profile,
            socksPort = 10808,
            httpPort = 10809,
            logLevel = "warn",
        )
    )!!.asJsonObject

    private fun base(protocol: EConfigType) = NodeProfile(
        remarks = protocol.name,
        protocol = protocol,
        server = "example.com",
        port = 443,
        tlsEnabled = false,
        insecure = false,
    )

    companion object {
        private const val UUID = "11111111-1111-1111-1111-111111111111"
        private const val KEY_ONE = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
        private const val KEY_TWO = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="
    }
}
