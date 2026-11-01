// Logic reference: FlClash (GPL-3.0), independently implemented from the supplied behavior specification.
package com.v2ray.ang.ipquality

enum class IpType { residential, mobile, business, hosting }
enum class IpQualityLevel { good, normal, risky }
enum class IpQualitySource { identMe, ipApiCom, ipQuery, ipLocate, proxyCheck, ipApiIs }
enum class IpQualityFailure { noType, timeout, rateLimited, failed, ipMismatch }

data class IpQuality(
    val ip: String,
    val source: IpQualitySource,
    val type: IpType,
    val organization: String? = null,
    val asn: String? = null,
    val isProxy: Boolean = false,
    val isVpn: Boolean = false,
    val isTor: Boolean = false,
    val isAbuser: Boolean = false,
    val inferred: Boolean = false,
) {
    val level: IpQualityLevel get() = when {
        isTor || isAbuser -> IpQualityLevel.risky
        type == IpType.hosting -> IpQualityLevel.normal
        else -> IpQualityLevel.good
    }
}

sealed interface IpQualityOutcome {
    data class Success(val quality: IpQuality) : IpQualityOutcome
    data class Failure(val status: IpQualityFailure) : IpQualityOutcome
}

class IpQualityException(
    val failures: Map<IpQualitySource, IpQualityFailure>,
    val retryAt: Long? = null,
) : Exception("IP quality lookup failed: " + failures.entries.joinToString { "${it.key}=${it.value}" })

/** Accept only numeric IP literals: no hostname resolution, URLs, zones or query-string injection. */
object IpQualityAddress {
    fun normalize(raw: String): String? {
        val ip = raw.trim().removeSurrounding("[", "]")
        if (ip.contains(':')) {
            if (!ip.matches(Regex("[0-9A-Fa-f:.]+"))) return null
            val parsed = runCatching { java.net.InetAddress.getByName(ip) }.getOrNull() ?: return null
            return parsed.hostAddress?.lowercase()
        }
        val parts = ip.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } ||
                (it.length > 1 && it.startsWith('0')) || (it.toIntOrNull() ?: -1) !in 0..255 }) return null
        return parts.joinToString(".")
    }
}
