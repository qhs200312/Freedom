package com.v2ray.ang.routing

import com.google.gson.JsonObject
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class NativePipelineTest {
    private fun packet(vararg values: Int): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { stream -> values.forEach(stream::writeInt) }
    }.toByteArray()

    @Test fun rejectsTruncatedWrongVersionNegativeAndTrailingPackets() {
        listOf(
            byteArrayOf(),
            packet(0, 1),
            packet(0x46525031, 1, -1),
            packet(0x46525031, 1, 100_001),
            packet(0x46525031, 1, 1, 20),
        ).forEach { bytes ->
            assertThrows(YamlRuleImportException::class.java) {
                NativePacket(bytes).expect(1).strings()
            }
        }
        assertThrows(YamlRuleImportException::class.java) {
            NativePacket(packet(0x46525031, 1, 0)).expect(1).finish()
        }
        assertThrows(YamlRuleImportException::class.java) {
            NativePacket(packet(0x46525031, 3)).expect(1)
        }
    }

    @Test fun binaryBridgePreservesUnicodeRegexAndAllGroups() {
        val parsed = ClashYamlRules.parse(
            "payload:\n- DOMAIN,example.com\n- DOMAIN-KEYWORD,\u4e2d\u6587\n" +
                "- 'DOMAIN-REGEX,\\Qquoted.+\\E'\n- IP-CIDR,192.0.2.1\n- DST-PORT,443\n- NETWORK,UDP",
        )
        assertEquals(listOf("full:example.com", "keyword:\u4e2d\u6587", "regexp:\\Qquoted.+\\E"), parsed.domains)
        assertEquals(listOf("192.0.2.1/32"), parsed.ips)
        assertEquals(listOf("443"), parsed.ports)
        assertEquals(listOf("udp"), parsed.networks)
    }

    @Test fun typedXrayConversionMatchesLegacyJsonAndDoesNotAliasMutableLists() {
        val domains = mutableListOf("full:a.example", "domain:b.example")
        val input = RulesetItem(
            id = "id", remarks = "ignored", domain = domains, ip = listOf("192.0.2.0/24"),
            process = listOf("org.example"), outboundTag = "direct", port = "80-443",
            network = "tcp", protocol = listOf("http"), outboundProfileId = "node",
        )
        val old = JsonUtil.fromJson(JsonUtil.toJson(input), V2rayConfig.RoutingBean.RulesBean::class.java)
        val converted = RoutingRuleConversion.toXray(input)
        assertEquals(old, converted)
        domains.clear()
        assertEquals(listOf("full:a.example", "domain:b.example"), converted.domain)
        assertEquals(
            JsonUtil.fromJson(JsonUtil.toJson(RulesetItem()), V2rayConfig.RoutingBean.RulesBean::class.java),
            RoutingRuleConversion.toXray(RulesetItem()),
        )
    }

    @Test fun domainClassificationPreservesAllKindsOrderAndDuplicates() {
        val pattern = listOf("full:a", "DOMAIN:b", "keyword:k", "regexp:^r", "plain",
            "geosite:private", "geosite:cn", "geosite:ignored", "domain:b")
        val input = List(300) { pattern }.flatten()
        val result = DomainRuleCompiler().compile(input)
        assertEquals(List(300) { "a" }, result.getAsJsonArray("domain").map { it.asString })
        assertEquals(List(300) { listOf("b", "plain", "lan", "local", "localdomain", "home.arpa", "b") }.flatten(),
            result.getAsJsonArray("domain_suffix").map { it.asString })
        assertEquals(List(300) { "k" }, result.getAsJsonArray("domain_keyword").map { it.asString })
        assertEquals(List(300) { "^r" }, result.getAsJsonArray("domain_regex").map { it.asString })
        assertEquals(listOf("geosite-cn"), result.getAsJsonArray("rule_set").map { it.asString })
    }

    @Test fun dnsAndRouteRuleSetsCannotMutateEachOther() {
        val input = List(300) { "geosite:cn" }
        val compiler = DomainRuleCompiler()
        val dns = JsonObject().also { compiler.addTo(it, input) }
        val route = JsonObject().also { compiler.addTo(it, input) }
        route.getAsJsonArray("rule_set").add("geoip-cn")
        assertEquals(listOf("geosite-cn"), dns.getAsJsonArray("rule_set").map { it.asString })
        assertEquals(listOf("different"), DomainRuleCompiler().compile(listOf("domain:different"))
            .getAsJsonArray("domain_suffix").map { it.asString })
    }

    @Test fun unicodeCasePrefixesKeepExistingKotlinSemantics() {
        val input = List(300) { "\u212aeyword:foo" }
        val result = DomainRuleCompiler().compile(input)
        assertEquals(List(300) { "foo" }, result.getAsJsonArray("domain_keyword").map { it.asString })
    }

    @Test fun bulkTextPacketChecksLengthsAndPreservesSurrogatePairs() {
        val parts: Array<Any> = arrayOf(
            intArrayOf(0x46525431, 1, 1, 2),
            charArrayOf('\uD83D', '\uDE00'),
        )
        val packet = NativeTextPacket(parts)
        assertEquals(listOf("\uD83D\uDE00"), packet.strings(50_000) {})
        packet.finish()
        listOf<Array<Any>?>(
            null,
            emptyArray(),
            arrayOf(intArrayOf(0x46525431, 1, 1, 3), charArrayOf('a')),
            arrayOf(intArrayOf(0x46525431, 1, -1), charArrayOf()),
        ).forEach { value ->
            assertThrows(YamlRuleImportException::class.java) { NativeTextPacket(value).strings(50_000) {} }
        }
    }
}
