package com.v2ray.ang.ipquality

import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.TestServiceMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class NodeTestTaskTest {
    @Test
    fun qualityIsOptInForBackgroundAndLatencyOnlyTests(): Unit = runBlocking {
        val expected = RealPingEvent.Result("node", 100)
        val events = mutableListOf<RealPingEvent>()
        NodeTestTask.run("node", false,
            measureDelay = { expected },
            lookupQuality = { error("Latency-only tests must not query quality") },
            onEvent = events::add)
        assertEquals(listOf(expected), events)
        assertFalse(TestServiceMessage(key = 0).ipQualityOnly)
        assertFalse(TestServiceMessage(key = 0).checkIpQuality)
    }

    @Test
    fun combinedTestEmitsLatencyBeforeQualityFinishes(): Unit = runBlocking {
        val connection = RealPingEvent.Result("node", 100, "connect")
        val quality = NodeIpQualityResult("203.0.113.8")
        val events = mutableListOf<RealPingEvent>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            NodeTestTask.run("node", false,
                measureDelay = { connection },
                lookupQuality = {
                    started.complete(Unit)
                    release.await()
                    quality
                },
                includeQuality = true,
                onEvent = events::add)
        }
        started.await()
        assertEquals(listOf(connection), events)
        release.complete(Unit)
        job.join()
        assertEquals(listOf(connection, RealPingEvent.QualityResult("node", quality)), events)
    }

    @Test
    fun combinedTestOnlyQueriesReachableNodes(): Unit = runBlocking {
        listOf(-1L, 0L, 100L).forEach { delay ->
            var queries = 0
            val events = mutableListOf<RealPingEvent>()
            NodeTestTask.run("node", false,
                measureDelay = { RealPingEvent.Result("node", delay) },
                lookupQuality = { queries++; NodeIpQualityResult() },
                includeQuality = true,
                onEvent = events::add)
            assertEquals(if (delay >= 0) 1 else 0, queries)
            assertEquals(delay, (events.first() as RealPingEvent.Result).delayMillis)
        }
    }

    @Test
    fun failedQualityLookupPreservesSuccessfulConnection(): Unit = runBlocking {
        val connection = RealPingEvent.Result("node", 100)
        val events = mutableListOf<RealPingEvent>()
        NodeTestTask.run("node", false,
            measureDelay = { connection },
            lookupQuality = { throw IOException("Source unavailable") },
            includeQuality = true,
            onEvent = events::add)
        assertEquals(connection, events.first())
        assertEquals(1, events.count { it is RealPingEvent.Result })
        assertNull((events.last() as RealPingEvent.QualityResult).quality.quality)
    }

    @Test
    fun qualityOnlyDoesNotMeasureOrOverwriteLatency(): Unit = runBlocking {
        val expected = NodeIpQualityResult("203.0.113.8")
        val events = mutableListOf<RealPingEvent>()
        NodeTestTask.run("node", true,
            measureDelay = { error("Quality tests must not measure latency") },
            lookupQuality = { expected },
            onEvent = events::add)
        assertEquals(listOf(RealPingEvent.QualityResult("node", expected)), events)
    }

    @Test
    fun unknownQualityIsNotAConnectionFailure(): Unit = runBlocking {
        val events = mutableListOf<RealPingEvent>()
        NodeTestTask.run("node", true,
            measureDelay = { error("Must not measure latency") },
            lookupQuality = { NodeIpQualityResult() },
            onEvent = events::add)
        val result = events.single()
        assertTrue(result is RealPingEvent.QualityResult)
        assertNull((result as RealPingEvent.QualityResult).quality.quality)
    }

    @Test
    fun qualityCancellationPropagates(): Unit = runBlocking {
        val events = mutableListOf<RealPingEvent>()
        try {
            NodeTestTask.run("node", true,
                measureDelay = { error("Must not measure latency") },
                lookupQuality = { throw CancellationException("cancelled") },
                onEvent = events::add)
            fail("Cancellation must reach the worker")
        } catch (_: CancellationException) {
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun combinedCancellationKeepsAlreadyReportedLatency(): Unit = runBlocking {
        val connection = RealPingEvent.Result("node", 100)
        val events = mutableListOf<RealPingEvent>()
        try {
            NodeTestTask.run("node", false,
                measureDelay = { connection },
                lookupQuality = { throw CancellationException("cancelled") },
                includeQuality = true,
                onEvent = events::add)
            fail("Cancellation must reach the worker")
        } catch (_: CancellationException) {
            assertEquals(listOf(connection), events)
        }
    }
}
