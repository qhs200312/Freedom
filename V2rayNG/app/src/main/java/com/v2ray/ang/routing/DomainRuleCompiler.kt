package com.v2ray.ang.routing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.IdentityHashMap

/** One configuration build owns this cache; rule edits cannot leave stale global entries. */
internal class DomainRuleCompiler {
    private val prepared = IdentityHashMap<List<String>, JsonObject>()

    fun addTo(target: JsonObject, domains: List<String>) {
        val conditions = prepared[domains] ?: compile(domains).also { prepared[domains] = it }
        conditions.entrySet().forEach { (key, value) ->
            // Domain arrays are immutable during assembly; rule_set may be extended by IP conditions.
            target.add(key, if (key == "rule_set") value.deepCopy() else value)
        }
    }

    internal fun compile(domains: List<String>): JsonObject {
        val groups = List(4) { JsonArray() }
        var cn = false
        domains.forEach { value ->
            when {
                value.equals("geosite:private", true) ->
                    listOf("lan", "local", "localdomain", "home.arpa").forEach(groups[1]::add)
                value.startsWith("full:", true) -> groups[0].add(value.substringAfter(':'))
                value.startsWith("keyword:", true) -> groups[2].add(value.substringAfter(':'))
                value.startsWith("regexp:", true) -> groups[3].add(value.substringAfter(':'))
                value.startsWith("domain:", true) -> groups[1].add(value.substringAfter(':'))
                value.equals("geosite:google", true) -> GOOGLE_DOMAINS.forEach(groups[1]::add)
                value.equals("geosite:cn", true) -> cn = true
                !value.startsWith("geosite:", true) -> groups[1].add(value)
            }
        }
        return JsonObject().apply {
            listOf("domain", "domain_suffix", "domain_keyword", "domain_regex").forEachIndexed { index, key ->
                if (groups[index].size() > 0) add(key, groups[index])
            }
            if (cn) add("rule_set", JsonArray().apply { add("geosite-cn") })
        }
    }

    private companion object {
        val GOOGLE_DOMAINS = listOf("google.com", "googleapis.com", "googleapis.cn", "gstatic.com",
            "googleusercontent.com", "ggpht.com", "googlevideo.com", "youtube.com", "youtu.be", "ytimg.com")
    }
}
