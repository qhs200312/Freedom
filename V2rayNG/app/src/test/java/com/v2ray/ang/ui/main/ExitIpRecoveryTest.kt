package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.GeoLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExitIpRecoveryTest {
    @Test
    fun transientFailureRecoversWithoutCoreRestart() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val recovered = recoverExitIp({ true }, pause = { waits += it }) {
            calls++
            if (calls < 3) null else GeoLocation(ip = "203.0.113.1")
        }
        assertEquals("203.0.113.1", recovered?.ip)
        assertEquals(listOf(15_000L, 15_000L, 15_000L), waits)
    }

    @Test
    fun stopsWhenDashboardLeavesForeground() = runBlocking {
        var visible = true
        val result = recoverExitIp({ visible }, pause = { visible = false }) {
            fail("must not query in background")
            null
        }
        assertNull(result)
    }

    @Test
    fun oldSessionCannotPublishLateResult() = runBlocking {
        var current = true
        assertNull(recoverExitIp({ current }, pause = {}) {
            current = false
            GeoLocation(ip = "203.0.113.1")
        })
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedIntoRetry() {
        runBlocking {
            recoverExitIp({ true }, pause = {}) { throw CancellationException("stopped") }
        }
    }
}
