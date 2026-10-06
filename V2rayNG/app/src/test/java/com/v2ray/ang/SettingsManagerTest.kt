package com.v2ray.ang

import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.handler.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsManagerTest {
    @Test
    fun googleProbeSettingsAreReplacedWithoutChangingOtherHosts() {
        listOf(null, "", "https://www.google.com/generate_204",
            "https://google.com/generate_204", AppConfig.LEGACY_DELAY_TEST_URL,
            "https://connectivitycheck.gstatic.com/generate_204",
        ).forEach {
            assertEquals(AppConfig.DELAY_TEST_URL, SettingsManager.normalizeDelayTestUrl(it))
        }
        listOf("https://example.org/check", "https://google.com.example.org/check").forEach {
            assertEquals(it, SettingsManager.normalizeDelayTestUrl(it))
        }
    }

    @Test
    fun googleProxyRuleIsExpandedWithoutChangingCustomFields() {
        val googleRule = RulesetItem(
            id = "google-rule",
            remarks = "Custom Google rule",
            domain = listOf("geosite:google", "domain:custom.example"),
            outboundTag = AppConfig.TAG_PROXY,
            network = "tcp,udp",
            locked = true,
        )
        val customRule = RulesetItem(
            id = "custom-rule",
            domain = listOf("domain:example.com"),
            outboundTag = AppConfig.TAG_DIRECT,
        )
        val rulesets = mutableListOf(googleRule, customRule)

        assertTrue(SettingsManager.ensureGoogleProxyCoverage(rulesets))

        assertEquals("google-rule", rulesets[0].id)
        assertEquals("Custom Google rule", rulesets[0].remarks)
        assertEquals("tcp,udp", rulesets[0].network)
        assertEquals(true, rulesets[0].locked)
        assertTrue(rulesets[0].domain.orEmpty().contains("domain:google.com"))
        assertTrue(rulesets[0].domain.orEmpty().contains("domain:googleapis.cn"))
        assertEquals("domain:custom.example", rulesets[0].domain.orEmpty()[1])
        assertEquals(listOf("geoip:google"), rulesets[1].ip)
        assertEquals(AppConfig.TAG_PROXY, rulesets[1].outboundTag)
        assertEquals(customRule, rulesets[2])
    }

    @Test
    fun migrationIsIdempotentAndDoesNotDuplicateExistingGoogleIpRule() {
        val googleRule = RulesetItem(
            domain = listOf(
                "GEOSITE:GOOGLE",
                "domain:google.com",
                "domain:googleapis.com",
                "domain:googleapis.cn",
                "domain:gstatic.com",
                "domain:googleusercontent.com",
                "domain:ggpht.com",
                "domain:googlevideo.com",
                "domain:youtube.com",
                "domain:youtu.be",
                "domain:ytimg.com",
            ),
            outboundTag = AppConfig.TAG_PROXY,
        )
        val googleIpRule = RulesetItem(
            ip = listOf("geoip:google", "geoip:example"),
            outboundTag = AppConfig.TAG_PROXY,
        )
        val rulesets = mutableListOf(googleRule, googleIpRule)

        assertFalse(SettingsManager.ensureGoogleProxyCoverage(rulesets))
        assertEquals(2, rulesets.size)
        assertEquals(1, rulesets.sumOf { rule ->
            rule.ip.orEmpty().count { it.equals("geoip:google", ignoreCase = true) }
        })
    }

    @Test
    fun disabledGoogleRuleDoesNotCreateAnEnabledIpRule() {
        val rulesets = mutableListOf(
            RulesetItem(
                domain = listOf("geosite:google"),
                outboundTag = AppConfig.TAG_PROXY,
                enabled = false,
            )
        )

        assertTrue(SettingsManager.ensureGoogleProxyCoverage(rulesets))
        assertEquals(1, rulesets.size)
        assertFalse(rulesets.single().enabled)
    }

    @Test
    fun customRulesWithoutGoogleGeositeAreLeftUntouched() {
        val rulesets = mutableListOf(
            RulesetItem(
                domain = listOf("domain:google.com"),
                outboundTag = AppConfig.TAG_DIRECT,
            )
        )
        val original = rulesets.map { it.copy() }

        assertFalse(SettingsManager.ensureGoogleProxyCoverage(rulesets))
        assertEquals(original, rulesets)
    }

    @Test
    fun missingChinaRulesAreInsertedBeforeCatchAllProxy() {
        val catchAll = RulesetItem(
            remarks = "Final proxy",
            port = "0-65535",
            outboundTag = AppConfig.TAG_PROXY,
        )
        val rulesets = mutableListOf(
            RulesetItem(domain = listOf("domain:example.com"), outboundTag = AppConfig.TAG_PROXY),
            catchAll,
        )

        assertTrue(SettingsManager.ensureChinaDirectCoverage(rulesets))

        assertEquals(listOf(AppConfig.GEOIP_CN), rulesets[1].ip)
        assertEquals(AppConfig.TAG_DIRECT, rulesets[1].outboundTag)
        assertEquals(listOf(AppConfig.GEOSITE_CN), rulesets[2].domain)
        assertEquals(AppConfig.TAG_DIRECT, rulesets[2].outboundTag)
        assertEquals(catchAll, rulesets[3])
    }

    @Test
    fun existingChinaRulesAreNotOverriddenOrDuplicated() {
        val customIpRule = RulesetItem(
            ip = listOf("GEOIP:CN"),
            outboundTag = AppConfig.TAG_PROXY,
            enabled = false,
        )
        val customDomainRule = RulesetItem(
            domain = listOf("GEOSITE:CN"),
            outboundTag = AppConfig.TAG_BLOCKED,
        )
        val rulesets = mutableListOf(customIpRule, customDomainRule)

        assertFalse(SettingsManager.ensureChinaDirectCoverage(rulesets))
        assertEquals(listOf(customIpRule, customDomainRule), rulesets)
    }

    @Test
    fun migrationAddsOnlyTheMissingChinaRule() {
        val existing = RulesetItem(
            ip = listOf(AppConfig.GEOIP_CN),
            outboundTag = AppConfig.TAG_DIRECT,
        )
        val rulesets = mutableListOf(existing)

        assertTrue(SettingsManager.ensureChinaDirectCoverage(rulesets))
        assertEquals(existing, rulesets[0])
        assertEquals(listOf(AppConfig.GEOSITE_CN), rulesets[1].domain)
    }
}
