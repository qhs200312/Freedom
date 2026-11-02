package com.v2ray.ang.routing

import com.v2ray.ang.dto.entities.RulesetItem
import org.junit.Assert.*
import org.junit.Test
import java.util.AbstractList

class RoutingRuleLimitsTest {
    @Test
    fun largeRulesOnlyReadThreeItemsWhenProducingListSummaries() {
        var reads = 0
        val values = object : AbstractList<String>() {
            override val size = 50_000
            override fun get(index: Int): String {
                check(index < 3) { "A summary must not traverse the whole rule" }
                reads++
                return "domain:d$index.example"
            }
        }
        assertFalse(RoutingRuleLimits.canEdit(values))
        assertEquals(0, reads)
        assertTrue(RoutingRuleLimits.summary(values).contains("50000"))
        assertEquals(3, reads)
    }

    @Test
    fun longConditionsCannotEnterTheEditableTextField() {
        assertTrue(RoutingRuleLimits.canEdit(listOf("domain:example.com")))
        assertFalse(RoutingRuleLimits.canEdit(listOf("a".repeat(RoutingRuleLimits.MAX_EDIT_CHARS + 1))))
        assertTrue(RoutingRuleLimits.summary(listOf("a".repeat(100_000))).length <= 96)
    }

    @Test
    fun repeatedImportsCannotAccumulateUnlimitedStoredRules() {
        val large = RulesetItem(domain = List(50_000) { "domain:d$it.example" })
        RoutingRuleLimits.checkStoredRules(listOf(large))
        assertEquals(YamlImportFailure.TOO_LARGE, assertThrows(YamlRuleImportException::class.java) {
            RoutingRuleLimits.checkStoredRules(listOf(large, large))
        }.failure)
        assertThrows(YamlRuleImportException::class.java) {
            RoutingRuleLimits.checkStoredRules(List(2_001) { RulesetItem(port = "443") })
        }
    }
}
