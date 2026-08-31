package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import com.google.gson.JsonObject
import java.net.URI

object Hysteria2Fmt : FmtBase() {
    private const val COMPACT_BRUTAL_BANDWIDTH = "100Mbps"
    private const val DEFAULT_PORT_HOPPING_INTERVAL = "30"

    /**
     * Parses a Hysteria2 URI string into a ProfileItem object.
     *
     * @param str the Hysteria2 URI string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem {
        val config = ProfileItem.create(EConfigType.HYSTERIA2)

        val uri = URI(Utils.fixIllegalUrl(str))
        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let { it.ifEmpty { "none" } }
        config.server = uri.idnHost
        config.serverPort = uri.port.toString()
        config.password = uri.userInfo
        config.security = AppConfig.TLS
        config.network = NetworkType.HYSTERIA.type

        if (!uri.rawQuery.isNullOrEmpty()) {
            val queryParam = getQueryParam(uri)

            getItemFormQuery(config, queryParam)

            config.security = queryParam["security"] ?: AppConfig.TLS
            config.obfsPassword = queryParam["obfs-password"]
            config.portHopping = queryParam["mport"]
            config.pinnedCA256 = queryParam["pinSHA256"]
            // Hysteria2 share links commonly carry these optional Brutal limits.
            // The values are expressed in Mbps by the URI convention, while the
            // core config accepts a unit-bearing duration-like bandwidth string.
            config.bandwidthDown = findQueryValue(
                queryParam,
                "downmbps",
                "brutalDown",
                "brutal_down"
            )?.let(::parseBandwidth)
            config.bandwidthUp = findQueryValue(
                queryParam,
                "upmbps",
                "brutalUp",
                "brutal_up"
            )?.let(::parseBandwidth)
            applyFinalMaskFields(config)
            applyCompactSubscriptionDefaults(config)

        }

        return config
    }

    /**
     * Converts a ProfileItem object to a URI string.
     *
     * @param config the ProfileItem object to convert
     * @return the converted URI string
     */
    fun toUri(config: ProfileItem): String {
        val dicQuery = HashMap<String, String>()

        config.security.let { if (it != null) dicQuery["security"] = it }
        config.sni?.nullIfBlank()?.let { dicQuery["sni"] = it }
        config.alpn?.nullIfBlank()?.let { dicQuery["alpn"] = it }
        config.insecure.let { dicQuery["insecure"] = if (it == true) "1" else "0" }

        if (config.obfsPassword.isNotNullEmpty()) {
            dicQuery["obfs"] = "salamander"
            dicQuery["obfs-password"] = config.obfsPassword.orEmpty()
        }
        if (config.portHopping.isNotNullEmpty()) {
            dicQuery["mport"] = config.portHopping.orEmpty()
        }
        if (config.pinnedCA256.isNotNullEmpty()) {
            dicQuery["pinSHA256"] = config.pinnedCA256.orEmpty()
        }
        config.bandwidthDown?.nullIfBlank()?.let { dicQuery["downmbps"] = toBandwidthParam(it) }
        config.bandwidthUp?.nullIfBlank()?.let { dicQuery["upmbps"] = toBandwidthParam(it) }

        return toUri(config, config.password, dicQuery)
    }

    private fun parseBandwidth(value: String): String {
        val trimmed = value.trim()
        val numericValue = Regex("(?i)^(\\d+(?:\\.\\d+)?)(?:\\s*\\+?\\s*mbps)?$")
            .matchEntire(trimmed)
            ?.groupValues
            ?.get(1)
        return numericValue?.let { "${it}Mbps" } ?: trimmed
    }

    private fun toBandwidthParam(value: String): String {
        val trimmed = value.trim()
        return trimmed.replace(Regex("(?i)\\s*mbps$"), "")
    }

    private fun findQueryValue(queryParam: Map<String, String>, vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            queryParam.entries
                .firstOrNull { it.key.equals(key, ignoreCase = true) }
                ?.value
                ?.nullIfBlank()
        }
    }

    private fun applyFinalMaskFields(config: ProfileItem) {
        val finalMask = JsonUtil.parseString(config.finalMask) ?: return
        val quicParams = finalMask.objectValue("quicParams") ?: return

        if (config.bandwidthDown.isNullOrBlank()) {
            config.bandwidthDown = quicParams.stringValue("brutalDown")?.let(::parseBandwidth)
        }
        if (config.bandwidthUp.isNullOrBlank()) {
            config.bandwidthUp = quicParams.stringValue("brutalUp")?.let(::parseBandwidth)
        }

        val udpHop = quicParams.objectValue("udpHop") ?: return
        if (config.portHopping.isNullOrBlank()) {
            config.portHopping = udpHop.stringValue("ports")
        }
        if (config.portHoppingInterval.isNullOrBlank()) {
            config.portHoppingInterval = udpHop.stringValue("interval")
        }
    }

    private fun applyCompactSubscriptionDefaults(config: ProfileItem) {
        val isCompactPortHoppingProfile = config.finalMask.isNullOrBlank() &&
            !config.obfsPassword.isNullOrBlank() &&
            !config.portHopping.isNullOrBlank()
        if (!isCompactPortHoppingProfile) return

        if (config.bandwidthDown.isNullOrBlank()) {
            config.bandwidthDown = COMPACT_BRUTAL_BANDWIDTH
        }
        if (config.bandwidthUp.isNullOrBlank()) {
            config.bandwidthUp = COMPACT_BRUTAL_BANDWIDTH
        }
        if (config.portHoppingInterval.isNullOrBlank()) {
            config.portHoppingInterval = DEFAULT_PORT_HOPPING_INTERVAL
        }
    }

    private fun JsonObject.objectValue(key: String): JsonObject? {
        val element = entrySet().firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        return element?.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun JsonObject.stringValue(key: String): String? {
        val element = entrySet().firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        return element
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.nullIfBlank()
    }
}
