package com.v2ray.ang.ipquality

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class NodeIpQualityResult(
    val ip: String? = null,
    val quality: IpQuality? = null,
    val checkedAt: Long = System.currentTimeMillis(),
) {
    fun isFresh(now: Long): Boolean = now - checkedAt in 0 until IpQualityRepository.CACHE_MILLIS
}

/** A failed enrichment must never turn a successful connection test into a failed node. */
internal class NodeIpQualityLookup(
    private val exitIp: suspend () -> String?,
    private val classify: suspend (String) -> IpQuality,
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = 40_000,
) {
    suspend fun lookup(): NodeIpQualityResult {
        var ip: String? = null
        val quality = withTimeoutOrNull(timeoutMillis) {
            try {
                ip = exitIp()?.let(IpQualityAddress::normalize)
                ip?.let { address -> classify(address).takeIf {
                    IpQualityAddress.normalize(it.ip) == address
                } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        return NodeIpQualityResult(ip, quality, now())
    }
}
