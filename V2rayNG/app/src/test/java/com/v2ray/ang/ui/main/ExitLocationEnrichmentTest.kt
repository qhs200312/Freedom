package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.GeoLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExitLocationEnrichmentTest {
    private val known = GeoLocation(ip = "203.0.113.5", ipv4 = "203.0.113.5")

    @Test
    fun retriesLocationWithoutDiscardingOrRequeryingKnownIp() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val enriched = known.copy(country = "Example")
        val result = enrichExitLocation(known, pause = { waits += it }) {
            assertSame(known, it)
            calls++
            if (calls == 1) throw java.io.IOException("temporary timeout")
            if (calls == 2) known else enriched
        }
        assertSame(enriched, result)
        assertEquals(3, calls)
        assertEquals(listOf(1_000L, 2_000L), waits)
    }

    @Test
    fun permanentFailurePreservesIpAndStopsAfterThreeAttempts() = runBlocking {
        var calls = 0
        val result = enrichExitLocation(known, pause = {}) {
            calls++
            null
        }
        assertSame(known, result)
        assertEquals(3, calls)
    }

    @Test
    fun cancellationDoesNotTriggerFurtherLookups() = runBlocking {
        var calls = 0
        try {
            enrichExitLocation(known, pause = { fail("must not retry") }) {
                calls++
                throw CancellationException("disconnected")
            }
            fail("must propagate cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun alreadyEnrichedAddressNeedsNoRequest() = runBlocking {
        val enriched = known.copy(city = "Example")
        assertSame(enriched, enrichExitLocation(enriched) { fail("must not fetch"); null })
    }

    @Test
    fun differentExitCannotReplaceKnownAddress() = runBlocking {
        assertSame(known, enrichExitLocation(known, pause = {}) {
            GeoLocation(ip = "203.0.113.6", country = "Wrong")
        })
    }
}
