package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import java.net.URI
import java.util.Locale

internal object RoutingTargets {
    fun orderedRules(rules: List<RulesetItem>): List<RulesetItem> {
        val (apps, other) = rules.partition { !it.process.isNullOrEmpty() }
        return apps + other
    }

    fun tag(rule: RulesetItem): String =
        rule.outboundProfileId?.takeIf(String::isNotBlank)?.let { "routing-profile-$it" }
            ?: rule.outboundTag.ifBlank { AppConfig.TAG_PROXY }

    fun profile(
        rule: RulesetItem,
        byId: (String) -> ProfileItem? = MmkvManager::decodeServerConfig,
        byRemarks: (String) -> ProfileItem? = SettingsManager::getServerViaRemarks,
    ): ProfileItem? {
        val id = rule.outboundProfileId?.takeIf(String::isNotBlank)
        // An explicit ID must not silently bind to a different node with the same name.
        if (id != null) return byId(id)
        if (tag(rule) in AppConfig.BUILTIN_OUTBOUND_TAGS) return null
        return byRemarks(rule.outboundTag)
    }

    fun parseDomains(value: String): List<String>? =
        value.split(',', '\n', '\r')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::normalizeWebsiteUrl)
            .map { if (':' in it) it else "domain:$it" }
            .distinct()
            .takeIf(List<String>::isNotEmpty)

    fun normalizeWebsiteUrl(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true) &&
            !trimmed.startsWith("http://", ignoreCase = true)) return value
        val host = runCatching { URI(trimmed).host }.getOrNull()?.takeIf(String::isNotBlank)
            ?: return value
        return "domain:${host.lowercase(Locale.ROOT)}"
    }

    fun rebindRules(
        rules: List<RulesetItem>,
        previousProfiles: Map<String, ProfileItem>,
        importedProfiles: Map<String, ProfileItem>,
    ): List<RulesetItem> = rules.map { rule ->
        val previous = previousProfiles[rule.outboundProfileId] ?: return@map rule
        val identity = previous.copy(preferredCore = null).duplicateIdentity()
        val identical = importedProfiles.filterValues {
            it.copy(preferredCore = null).duplicateIdentity() == identity
        }
        val sameName = importedProfiles.filterValues {
            previous.remarks.isNotBlank() && it.remarks.trim().equals(previous.remarks.trim(), true)
        }
        val replacement = identical.entries.singleOrNull()
            ?: sameName.entries.singleOrNull()
            ?: return@map rule
        rule.copy(outboundProfileId = replacement.key, outboundTag = replacement.value.remarks)
    }
}
