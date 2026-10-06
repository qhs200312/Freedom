package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.GeoLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal suspend fun enrichExitLocation(
    known: GeoLocation,
    pause: suspend (Long) -> Unit = { delay(it) },
    fetch: suspend (GeoLocation) -> GeoLocation?,
): GeoLocation {
    if (known.hasLocationDetails) return known
    for (waitMs in listOf(0L, 1_000L, 2_000L)) {
        if (waitMs > 0L) pause(waitMs)
        currentCoroutineContext().ensureActive()
        val result = try {
            fetch(known)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        currentCoroutineContext().ensureActive()
        if (result?.ip == known.ip && result.hasLocationDetails) return result
    }
    return known
}
