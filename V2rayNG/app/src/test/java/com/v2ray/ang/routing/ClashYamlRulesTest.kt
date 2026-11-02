package com.v2ray.ang.routing

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.SingBoxConfigGenerator
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test

class ClashYamlRulesTest {
    @Test
    fun supportedTypesMapWithoutCombiningIndependentConditions() {
        val parsed = ClashYamlRules.parse("""
            payload:
              - DOMAIN,Exact.Example
              - DOMAIN-SUFFIX,.example.com
              - DOMAIN-KEYWORD,service
              - 'DOMAIN-REGEX,^cdn[0-9]{1,3}\.example$'
              - IP-CIDR,192.0.2.0/24
              - IP-CIDR6,2001:db8::/32
              - DST-PORT,443
              - DST-PORT,8000-8080
              - NETWORK,TCP
              - NETWORK,UDP
        """.trimIndent())
        assertEquals(10, parsed.count)
        assertEquals(listOf("full:exact.example", "domain:example.com", "keyword:service",
            "regexp:^cdn[0-9]{1,3}\\.example$"), parsed.domains)
        assertEquals(listOf("192.0.2.0/24", "2001:db8::/32"), parsed.ips)
        val rules = parsed.toRules("Imported", AppConfig.TAG_DIRECT, null)
        assertEquals(5, rules.size)
        assertTrue(rules.all { it.enabled && it.outboundTag == AppConfig.TAG_DIRECT })
        assertTrue(rules.none { !it.domain.isNullOrEmpty() && !it.ip.isNullOrEmpty() })
        assertEquals(5, rules.map { it.id }.distinct().size)
        assertEquals("tcp,udp", rules.last().network)
    }

    @Test
    fun scalarVariantsCommentsBomAndIndentlessSequencesAreSupported() {
        val inputs = listOf(
            "payload:\n  - DOMAIN-SUFFIX,example.com",
            "\uFEFF# comment\r\n---\r\npayload: # list\r\n    - 'DOMAIN-SUFFIX,example.com' # comment\r\n...",
            "payload:\n- \"DOMAIN-SUFFIX,example.com\"",
        )
        inputs.forEach { assertEquals(listOf("domain:example.com"), ClashYamlRules.parse(it).domains) }
        assertEquals(listOf("keyword:a'b#c"), ClashYamlRules.parse("payload:\n- 'DOMAIN-KEYWORD,a''b#c'").domains)
        assertEquals(listOf("regexp:^foo\\.example#part$"),
            ClashYamlRules.parse("payload:\n- \"DOMAIN-REGEX,^foo\\\\.example#part\\u0024\"").domains)
    }

    @Test
    fun normalizationDeduplicatesRulesWhilePreservingTheirOrder() {
        val parsed = ClashYamlRules.parse("""
            payload:
              - DOMAIN,EXAMPLE.COM.
              - DOMAIN,example.com
              - DOMAIN-SUFFIX,example.com
              - DOMAIN,例子.中国
              - IP-CIDR,192.000.002.001
              - IP-CIDR,192.0.2.1/32
        """.trimIndent())
        assertEquals(4, parsed.count)
        assertEquals(listOf("full:example.com", "domain:example.com", "full:xn--fsqu00a.xn--fiqs8s"),
            parsed.domains)
        assertEquals(listOf("192.0.2.1/32"), parsed.ips)
    }

    @Test
    fun chosenNodeIdentityIsPreservedAcrossEveryGeneratedRule() {
        val rules = ClashYamlRules.parse("payload:\n- DOMAIN,example.com\n- IP-CIDR,192.0.2.0/24")
            .toRules("Meta", "My node", "node-id")
        assertTrue(rules.all { it.outboundTag == "My node" && it.outboundProfileId == "node-id" })
        assertTrue(rules.all { it.remarks!!.startsWith("Meta") })
    }

    @Test
    fun malformedAndUnsupportedDocumentsFailWithPreciseLinesWithoutPartialResults() {
        data class Case(val content: String, val reason: YamlImportFailure, val line: Int)
        val cases = listOf(
            Case("", YamlImportFailure.FORMAT, 0),
            Case("<html>not YAML</html>", YamlImportFailure.FORMAT, 1),
            Case("rules:\n- DOMAIN,example.com,DIRECT", YamlImportFailure.FORMAT, 1),
            Case("payload: [DOMAIN,example.com]", YamlImportFailure.FORMAT, 1),
            Case("payload:\n- &x DOMAIN,example.com", YamlImportFailure.FORMAT, 2),
            Case("payload:\n- *x", YamlImportFailure.FORMAT, 2),
            Case("payload:\n\t- DOMAIN,example.com", YamlImportFailure.FORMAT, 2),
            Case("payload:\n- \"DOMAIN,example.com", YamlImportFailure.FORMAT, 2),
            Case("payload:\n- \"DOMAIN-REGEX,\\q\"", YamlImportFailure.FORMAT, 2),
            Case("payload:\n- DOMAIN,a.example\n  - DOMAIN,b.example", YamlImportFailure.FORMAT, 3),
            Case("payload:\n- DOMAIN,a.example\npayload:\n- DOMAIN,b.example", YamlImportFailure.FORMAT, 3),
            Case("payload:\n- DOMAIN,a.example\n---\npayload:", YamlImportFailure.FORMAT, 3),
            Case("payload:\n- DOMAIN,a.example\n- MATCH", YamlImportFailure.FORMAT, 3),
            Case("payload:\n- DOMAIN,a.example\n- GEOIP,CN", YamlImportFailure.UNSUPPORTED, 3),
            Case("payload:\n- IP-CIDR,192.0.2.0/24,no-resolve", YamlImportFailure.UNSUPPORTED, 2),
            Case("payload:\n- DOMAIN,example.com,PROXY", YamlImportFailure.UNSUPPORTED, 2),
            Case("payload:\n- SRC-IP-CIDR,192.0.2.0/24", YamlImportFailure.UNSUPPORTED, 2),
            Case("payload:\n- DOMAIN,*.example.com", YamlImportFailure.VALUE, 2),
            Case("payload:\n- DOMAIN,", YamlImportFailure.VALUE, 2),
            Case("payload:\n- IP-CIDR,256.0.0.1/32", YamlImportFailure.VALUE, 2),
            Case("payload:\n- IP-CIDR,example.com/24", YamlImportFailure.VALUE, 2),
            Case("payload:\n- IP-CIDR,192.0.2.0/33", YamlImportFailure.VALUE, 2),
            Case("payload:\n- IP-CIDR6,not-an-ip/32", YamlImportFailure.VALUE, 2),
            Case("payload:\n- IP-CIDR6,2001::db8::/32", YamlImportFailure.VALUE, 2),
            Case("payload:\n- DST-PORT,8080-8000", YamlImportFailure.VALUE, 2),
            Case("payload:\n- DST-PORT,65536", YamlImportFailure.VALUE, 2),
            Case("payload:\n- NETWORK,icmp", YamlImportFailure.VALUE, 2),
            Case("payload:\n# empty", YamlImportFailure.EMPTY, 0),
        )
        cases.forEach { case ->
            val error = assertThrows(case.content, YamlRuleImportException::class.java) { ClashYamlRules.parse(case.content) }
            assertEquals(case.content, case.reason, error.failure)
            assertEquals(case.content, case.line, error.line)
        }
    }

    @Test
    fun parsingEnforcesByteAndRuleLimits() {
        assertEquals(YamlImportFailure.TOO_LARGE, assertThrows(YamlRuleImportException::class.java) {
            ClashYamlRules.parse("中".repeat(ClashYamlRules.MAX_BYTES / 3 + 1))
        }.failure)
        assertEquals(YamlImportFailure.TOO_LARGE, assertThrows(YamlRuleImportException::class.java) {
            ClashYamlRules.parse("payload:\n" + "- DOMAIN,example.com\n".repeat(100_001))
        }.failure)
    }

    @Test
    fun coreRegexValidationFailureRetainsTheOriginalLineNumber() {
        val error = assertThrows(YamlRuleImportException::class.java) {
            ClashYamlRules.parse("payload:\n- DOMAIN,valid.example\n- DOMAIN-REGEX,(?=unsupported)") { false }
        }
        assertEquals(YamlImportFailure.VALUE, error.failure)
        assertEquals(3, error.line)
        assertEquals("DOMAIN-REGEX", error.detail)
    }

    @Test
    fun importedRulesGenerateIndependentConditionsInBothNativeConfigurationSchemas() {
        val rules = ClashYamlRules.parse("""
            payload:
              - DOMAIN,exact.example
              - DOMAIN-SUFFIX,example.com
              - DOMAIN-KEYWORD,service
              - 'DOMAIN-REGEX,^cdn\d{1,3}\.example$'
              - IP-CIDR,192.0.2.0/24
              - IP-CIDR6,2001:db8::/32
              - DST-PORT,8443-8445
              - NETWORK,UDP
        """.trimIndent()).toRules("Imported", AppConfig.TAG_PROXY, null)
        val config = JsonUtil.parseString(SingBoxConfigGenerator.generate(
            profile = NodeProfile(
                remarks = "Test", protocol = EConfigType.HTTP, server = "192.0.2.1", port = 8080,
                tlsEnabled = false, insecure = false,
            ),
            socksPort = 10808, httpPort = 10809, logLevel = "warn", routingRules = rules,
        ))!!.asJsonObject
        val generated = config.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }
        val domain = generated.single { item ->
            item.getAsJsonArray("domain")?.any { it.asString == "exact.example" } == true
        }
        assertEquals("exact.example", domain.getAsJsonArray("domain").single().asString)
        assertEquals("example.com", domain.getAsJsonArray("domain_suffix").single().asString)
        assertEquals("service", domain.getAsJsonArray("domain_keyword").single().asString)
        assertFalse(domain.has("ip_cidr"))
        assertEquals(2, generated.single { it.has("ip_cidr") }.getAsJsonArray("ip_cidr").size())
        assertEquals("8443:8445", generated.single { it.has("port_range") }.getAsJsonArray("port_range").single().asString)
        val xray = rules.map { JsonUtil.fromJson(JsonUtil.toJson(it), V2rayConfig.RoutingBean.RulesBean::class.java)!! }
        assertEquals(rules.first().domain, xray.first().domain)
        assertTrue(xray.none { !it.domain.isNullOrEmpty() && !it.ip.isNullOrEmpty() })
        assertEquals("8443-8445", xray.single { !it.port.isNullOrBlank() }.port)
    }

    @Test
    fun metaStylePayloadImportsAWholeDomainProviderAsOneEditableRule() {
        val content = javaClass.getResourceAsStream("/routing/meta-style.yaml")!!
            .bufferedReader().use { it.readText() }
        val parsed = ClashYamlRules.parse(content)
        assertEquals(5, parsed.count)
        assertEquals(1, parsed.toRules("Meta", "proxy", null).size)
        assertTrue(parsed.domains.contains("domain:facebook.com"))
    }
}
