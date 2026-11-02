package com.v2ray.ang.routing

import com.v2ray.ang.dto.entities.RulesetItem
import java.io.File
import java.util.UUID

internal enum class YamlImportFailure { FORMAT, UNSUPPORTED, VALUE, EMPTY, TOO_LARGE, URL, HTTP, READ, TIMEOUT, CANCELLED, BUSY, PASTE_TOO_LARGE }

internal class YamlRuleImportException(
    val failure: YamlImportFailure,
    val line: Int = 0,
    val detail: String = "",
) : IllegalArgumentException("$failure at line $line: $detail")

internal data class ParsedYamlRules(
    val domains: List<String>,
    val ips: List<String>,
    val ports: List<String>,
    val networks: List<String>,
) {
    val count: Int get() = domains.size + ips.size + ports.size + networks.size

    fun toRules(name: String, outbound: String, profileId: String?): List<RulesetItem> {
        // Domain and IP conditions in one native rule mean AND, so keep them in separate rules.
        val rules = buildList {
            if (domains.isNotEmpty()) add(RulesetItem(domain = domains))
            if (ips.isNotEmpty()) add(RulesetItem(ip = ips))
            ports.forEach { add(RulesetItem(port = it)) }
            if (networks.isNotEmpty()) add(RulesetItem(network = networks.joinToString(",")))
        }
        return rules.mapIndexed { index, rule ->
            rule.copy(
                id = UUID.randomUUID().toString(),
                remarks = if (rules.size == 1) name else "$name ${index + 1}",
                outboundTag = outbound,
                outboundProfileId = profileId,
            )
        }
    }
}

/** All rule parsing is performed by the bounded Rust parser; this is a small JVM adapter. */
internal object ClashYamlRules {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_PASTE_CHARS = 64 * 1024

    fun parse(content: String, validateRegex: (String) -> Boolean = { true }): ParsedYamlRules {
        if (content.length > MAX_BYTES) throw YamlRuleImportException(YamlImportFailure.TOO_LARGE)
        val file = File.createTempFile("freedom-rules-", ".yaml")
        return try {
            file.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            RustRuleParser.parseBlocking(file, validateRegex)
        } finally { file.delete() }
    }
}
