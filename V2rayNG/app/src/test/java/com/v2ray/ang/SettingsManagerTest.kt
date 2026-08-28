package com.v2ray.ang

import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.handler.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsManagerTest {
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
}
