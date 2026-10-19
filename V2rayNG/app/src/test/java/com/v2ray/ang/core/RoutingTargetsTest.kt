package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.*
import org.junit.Test

class RoutingTargetsTest {
    @Test
    fun idBindingDistinguishesDuplicateNamesAndSurvivesRenaming() {
        val first = profile("Same name", "one.example")
        val second = profile("Renamed", "two.example")
        val rule = RulesetItem(outboundTag = "Same name", outboundProfileId = "second")
        val loaded = RoutingTargets.profile(rule, mapOf("first" to first, "second" to second)::get) { first }
        assertSame(second, loaded)
        assertEquals("routing-profile-second", RoutingTargets.tag(rule))
    }

    @Test
    fun missingIdDoesNotSelectAnotherNodeWithTheSameName() {
        val rule = RulesetItem(outboundTag = "Same name", outboundProfileId = "removed")
        assertNull(RoutingTargets.profile(rule, { null }) { profile("Same name", "wrong.example") })
    }

    @Test
    fun legacyNameRulesStillResolveAndBuiltinNamesCanBeBoundAsNodes() {
        val node = profile("proxy", "node.example")
        assertNull(RoutingTargets.profile(RulesetItem(outboundTag = "proxy"), { node }) { node })
        assertSame(node, RoutingTargets.profile(RulesetItem(outboundTag = "Old name"), { null }) { node })
        assertEquals("routing-profile-id", RoutingTargets.tag(
            RulesetItem(outboundTag = "direct", outboundProfileId = "id"),
        ))
        assertEquals(AppConfig.TAG_PROXY, RoutingTargets.tag(RulesetItem()))
    }

    @Test
    fun plainServiceDomainsMatchSubdomainsWithoutChangingExplicitRules() {
        assertEquals(
            listOf("domain:claude.ai", "domain:anthropic.com", "full:exact.example", "regexp:^service\\."),
            RoutingTargets.parseDomains(" claude.ai,\nanthropic.com\r\nfull:exact.example,regexp:^service\\.,claude.ai"),
        )
        assertNull(RoutingTargets.parseDomains(" , \n"))
    }

    @Test
    fun pastedWebsiteUrlsUseTheirHostWithoutPathsPortsOrQueries() {
        assertEquals(
            listOf("domain:ip.net.coffee", "domain:example.com", "full:exact.example", "regexp:https://example"),
            RoutingTargets.parseDomains(
                "https://ip.net.coffee, HTTPS://IP.NET.COFFEE/path?value=1#section,\n" +
                    "http://example.com:8080/path,full:exact.example,regexp:https://example",
            ),
        )
    }

    @Test
    fun repairingStoredUrlsPreservesLegacyKeywordsAndExplicitConditions() {
        assertEquals("domain:ip.net.coffee", RoutingTargets.normalizeWebsiteUrl("https://ip.net.coffee"))
        listOf("google", "full:example.com", "domain:example.com", "geosite:cn", "regexp:https://example", "https://")
            .forEach { assertEquals(it, RoutingTargets.normalizeWebsiteUrl(it)) }
    }

    @Test
    fun subscriptionRefreshRebindsOnlyTheOriginalGroupsNodes() {
        val original = profile("US", "old.example")
        val rules = listOf(
            RulesetItem(outboundProfileId = "old", outboundTag = "US", domain = listOf("domain:claude.ai")),
            RulesetItem(outboundProfileId = "another-group", outboundTag = "US"),
            RulesetItem(outboundTag = AppConfig.TAG_PROXY),
        )
        val updated = RoutingTargets.rebindRules(rules, mapOf("old" to original), mapOf("new" to original.copy(server = "new.example")))
        assertEquals("new", updated[0].outboundProfileId)
        assertEquals(rules[0].domain, updated[0].domain)
        assertSame(rules[1], updated[1])
        assertSame(rules[2], updated[2])
        assertEquals("old", rules[0].outboundProfileId)
    }

    @Test
    fun unchangedConnectionCanBeRenamedButAmbiguousReplacementStaysMissing() {
        val original = profile("US", "same.example")
        val rule = RulesetItem(outboundProfileId = "old", outboundTag = "US")
        val renamed = RoutingTargets.rebindRules(listOf(rule), mapOf("old" to original),
            mapOf("new" to original.copy(remarks = "USA")))
        assertEquals("USA", renamed.single().outboundTag)
        assertEquals("new", renamed.single().outboundProfileId)
        val ambiguous = RoutingTargets.rebindRules(listOf(rule), mapOf("old" to original),
            mapOf("one" to original.copy(server = "one.example"), "two" to original.copy(server = "two.example")))
        assertSame(rule, ambiguous.single())
    }

    private fun profile(name: String, server: String) =
        ProfileItem.create(EConfigType.HTTP).copy(remarks = name, server = server, serverPort = "8080")
}
