package com.v2ray.ang.routinghealth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal enum class RoutingHealthState { unknown, checking, retrying, healthy, unavailable, missing }

internal data class RoutingHealthStatus(
    val tag: String,
    val name: String,
    val rules: List<String>,
    val state: RoutingHealthState = RoutingHealthState.unknown,
    val checkedAt: Long = 0,
) {
    val isUnavailable: Boolean get() = state in setOf(
        RoutingHealthState.unavailable, RoutingHealthState.missing, RoutingHealthState.retrying,
    )
    val isChecking: Boolean get() = state == RoutingHealthState.checking || state == RoutingHealthState.retrying
}

internal data class RoutingHealthSnapshot(
    val session: String,
    val statuses: List<RoutingHealthStatus>,
)

internal data class RoutingHealthEndpoint(
    val status: RoutingHealthStatus,
    val port: Int = 0,
    val username: String = "",
    val secret: String = "",
)

/** Two independent HTTPS destinations reduce single-site false alarms. No direct fallback. */
internal class RoutingHealthCheck(
    private val probe: suspend (RoutingHealthEndpoint, String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val retryDelayMillis: Long = 800,
) {
    suspend fun check(endpoint: RoutingHealthEndpoint): RoutingHealthStatus {
        if (endpoint.port == 0) return endpoint.status
        for ((index, url) in urls.withIndex()) {
            if (index > 0) delay(retryDelayMillis)
            val reached = try {
                probe(endpoint, url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (reached) return endpoint.status.copy(state = RoutingHealthState.healthy, checkedAt = now())
        }
        return endpoint.status.copy(state = RoutingHealthState.unavailable, checkedAt = now())
    }

    companion object {
        val urls = listOf("https://cp.cloudflare.com/generate_204", "https://www.gstatic.com/generate_204")
    }
}
