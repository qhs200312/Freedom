package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.GeoLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Recovery of dashboard metadata never restarts either native core. */
internal suspend fun recoverExitIp(
    isCurrent: () -> Boolean,
    pause: suspend (Long) -> Unit = { delay(it) },
    fetch: suspend () -> GeoLocation?,
): GeoLocation? {
    while (isCurrent()) {
        pause(15_000L)
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return null
        val result = try {
            fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return null
        if (result?.ip?.isNotBlank() == true) return result
    }
    return null
}
