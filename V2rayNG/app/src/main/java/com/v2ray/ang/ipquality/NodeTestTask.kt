package com.v2ray.ang.ipquality

import com.v2ray.ang.dto.RealPingEvent
import kotlinx.coroutines.CancellationException

/** Emits latency first, then quality without changing the connection result on lookup failure. */
internal object NodeTestTask {
    suspend fun run(
        guid: String,
        qualityOnly: Boolean,
        measureDelay: suspend () -> RealPingEvent.Result,
        lookupQuality: suspend () -> NodeIpQualityResult,
        includeQuality: Boolean = false,
        onEvent: (RealPingEvent) -> Unit,
    ) {
        if (!qualityOnly) {
            val connection = measureDelay()
            onEvent(connection)
            if (!includeQuality || connection.delayMillis < 0L) return
        }
        val quality = try {
            lookupQuality()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NodeIpQualityResult()
        }
        onEvent(RealPingEvent.QualityResult(guid, quality))
    }
}
