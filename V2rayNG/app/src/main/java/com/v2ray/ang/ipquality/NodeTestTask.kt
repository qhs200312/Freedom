package com.v2ray.ang.ipquality

import com.v2ray.ang.dto.RealPingEvent

/** Keeps quality queries independent of latency measurement and its stored result. */
internal object NodeTestTask {
    suspend fun run(
        guid: String,
        qualityOnly: Boolean,
        measureDelay: suspend () -> RealPingEvent.Result,
        lookupQuality: suspend () -> NodeIpQualityResult,
    ): RealPingEvent = if (qualityOnly) {
        RealPingEvent.QualityResult(guid, lookupQuality())
    } else {
        measureDelay()
    }
}
