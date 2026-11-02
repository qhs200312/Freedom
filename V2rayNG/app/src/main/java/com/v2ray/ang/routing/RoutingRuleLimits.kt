package com.v2ray.ang.routing

import com.v2ray.ang.dto.entities.RulesetItem

internal object RoutingRuleLimits {
    const val MAX_EDIT_CHARS = 32 * 1024
    const val MAX_EDIT_ITEMS = 1_000

    fun canEdit(values: List<String>?): Boolean {
        if (values == null) return true
        if (values.size > MAX_EDIT_ITEMS) return false
        var count = 0
        for (value in values) {
            count += value.length + 1
            if (count > MAX_EDIT_CHARS) return false
        }
        return true
    }

    fun summary(values: List<String>): String =
        values.take(3).joinToString(", ") { it.take(96) } +
            if (values.size > 3) " … (${values.size})" else ""

    fun checkStoredRules(rules: List<RulesetItem>) {
        if (rules.size > 2_000) throw YamlRuleImportException(YamlImportFailure.TOO_LARGE)
        var entries = 0L
        var characters = 0L
        var regexes = 0
        var ports = 0
        for (rule in rules) {
            if (!rule.port.isNullOrBlank()) ports++
            regexes += rule.domain.orEmpty().count { it.startsWith("regexp:") }
            if (regexes > 256 || ports > 512) throw YamlRuleImportException(YamlImportFailure.TOO_LARGE)
            entries++
            characters += rule.remarks.orEmpty().length + rule.port.orEmpty().length + rule.network.orEmpty().length
            for (values in listOf(rule.domain, rule.ip, rule.process, rule.protocol)) {
                for (value in values.orEmpty()) {
                    entries++
                    characters += value.length
                    if (entries > 100_000 || characters > 4 * 1024 * 1024) {
                        throw YamlRuleImportException(YamlImportFailure.TOO_LARGE, detail = "stored rules budget")
                    }
                }
            }
        }
    }
}
