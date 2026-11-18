package com.v2ray.ang.ipquality

import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.TestServiceMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NodeTestTaskTest {
    @Test
    fun ordinaryConnectionTestNeverQueriesQuality(): Unit = runBlocking {
        val expected = RealPingEvent.Result("node", 100)
        assertEquals(expected, NodeTestTask.run("node", false,
            measureDelay = { expected },
            lookupQuality = { error("Connection tests must not query quality") }))
        assertFalse(TestServiceMessage(key = 0).ipQualityOnly)
    }

    @Test
    fun qualityOnlyDoesNotMeasureOrOverwriteLatency(): Unit = runBlocking {
        val expected = NodeIpQualityResult("203.0.113.8")
        assertEquals(RealPingEvent.QualityResult("node", expected),
            NodeTestTask.run("node", true,
                measureDelay = { error("Quality tests must not measure latency") },
                lookupQuality = { expected }))
    }

    @Test
    fun unknownQualityIsNotAConnectionFailure(): Unit = runBlocking {
        val result = NodeTestTask.run("node", true,
            measureDelay = { error("Must not measure latency") },
            lookupQuality = { NodeIpQualityResult() })
        assertTrue(result is RealPingEvent.QualityResult)
        assertNull((result as RealPingEvent.QualityResult).quality.quality)
    }

    @Test
    fun qualityCancellationPropagates(): Unit = runBlocking {
        try {
            NodeTestTask.run("node", true,
                measureDelay = { error("Must not measure latency") },
                lookupQuality = { throw CancellationException("cancelled") })
            fail("Cancellation must reach the worker")
        } catch (_: CancellationException) {
            // The worker owns cancellation and native resource cleanup.
        }
    }
}
