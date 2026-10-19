package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.NodeTrafficBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil

/** Capture identities from the same outbound plan that builds the running core. */
internal object NodeTrafficBindings {
    fun custom(content: String, guid: String): Map<String, NodeTrafficBinding> = runCatching {
        val outbounds = JsonUtil.parseString(content)?.asJsonObject?.getAsJsonArray("outbounds")
            ?.map { it.asJsonObject }.orEmpty()
        val intermediate = outbounds.flatMap { outbound ->
            listOfNotNull(
                outbound.getAsJsonObject("proxySettings")?.get("tag")?.asString,
                outbound.getAsJsonObject("streamSettings")?.getAsJsonObject("sockopt")?.get("dialerProxy")?.asString,
            )
        }.toSet()
        outbounds.mapNotNull { outbound ->
            val tag = outbound.get("tag")?.asString ?: return@mapNotNull null
            val protocol = outbound.get("protocol")?.asString
            if (protocol in setOf("freedom", "blackhole", "dns", "loopback")) return@mapNotNull null
            tag to NodeTrafficBinding(if (tag in intermediate) emptyList() else listOf(guid),
                countInTotal = tag !in intermediate)
        }.toMap()
    }.getOrDefault(emptyMap())

    fun storedProfiles(): Map<String, ProfileItem> =
        MmkvManager.decodeAllServerList().mapNotNull { id ->
            MmkvManager.decodeServerConfig(id)?.let { id to it }
        }.toMap()

    fun resolve(
        primaryId: String?,
        outbounds: List<CoreConfigContext.ResolvedOutbound>,
        profiles: Map<String, ProfileItem>,
    ): Map<String, NodeTrafficBinding> = buildMap {
        fun id(profile: ProfileItem): String? = profiles.entries.firstOrNull { it.value == profile }?.key
        primaryId?.let { put(AppConfig.TAG_PROXY, NodeTrafficBinding(listOf(it))) }
        outbounds.forEach { outbound ->
            val owner = when {
                outbound.tag == AppConfig.TAG_PROXY -> primaryId
                outbound.tag.startsWith("routing-profile-") -> outbound.tag.removePrefix("routing-profile-")
                else -> id(outbound.profile)
            }
            when (outbound.resolvedType) {
                CoreResolvedType.NORMAL -> put(outbound.tag, NodeTrafficBinding(
                    listOfNotNull(owner, outbound.resolvedProfiles.firstOrNull()?.let {
                        if (profiles[owner] == it) owner else id(it)
                    }).distinct(),
                ))
                CoreResolvedType.PROXYCHAIN -> outbound.resolvedProfiles.forEachIndexed { index, profile ->
                    val tag = if (index == 0) outbound.tag else "${AppConfig.TAG_PROXY}-${outbound.tag}-$index"
                    put(tag, NodeTrafficBinding(
                        listOfNotNull(owner.takeIf { index == 0 }, id(profile)).distinct(),
                        countInTotal = index == 0,
                    ))
                }
                CoreResolvedType.POLICYGROUP -> outbound.resolvedProfiles.forEachIndexed { index, profile ->
                    put("${AppConfig.TAG_PROXY}-${outbound.tag}-${index + 1}-${profile.remarks.trim()}",
                        NodeTrafficBinding(listOfNotNull(owner, id(profile)).distinct()))
                }
            }
        }
    }
}
