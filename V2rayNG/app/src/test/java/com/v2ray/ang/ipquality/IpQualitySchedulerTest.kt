package com.v2ray.ang.ipquality

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class IpQualitySchedulerTest {
    private val ip = "203.0.113.8"
    private fun result(source: IpQualitySource, inferred: Boolean = false) =
        IpQualityOutcome.Success(IpQuality(ip, source, IpType.residential, inferred = inferred))

    @Test
    fun explicitTypeBeatsInferredAndCancelsOtherInFlightRequests(): Unit = runBlocking {
        val started = mutableListOf<IpQualitySource>()
        val cancelled = mutableListOf<IpQualitySource>()
        val transport = IpQualityTransport { source, _ ->
            started += source
            when (source) {
                IpQualitySource.identMe -> result(source, inferred = true)
                IpQualitySource.ipQuery -> { delay(10); result(source) }
                else -> try { awaitCancellation() } finally { cancelled += source }
            }
        }
        val winner = IpQualityScheduler(transport, waveDelayMillis = 0).query(ip)
        assertEquals(IpQualitySource.ipQuery, winner.source)
        assertEquals(IpQualityScheduler.waves.first().toSet(), started.toSet())
        assertEquals(listOf(IpQualitySource.ipApiCom), cancelled)
    }

    @Test
    fun simultaneousRepliesDoNotTurnAWinnerIntoAClosedChannelFailure(): Unit = runBlocking {
        withContext(Dispatchers.Default) {
            repeat(40) {
                val scheduler = IpQualityScheduler(IpQualityTransport { source, _ ->
                    result(source, inferred = source == IpQualitySource.identMe)
                }, waveDelayMillis = 0)
                assertFalse(scheduler.query(ip).inferred)
            }
        }
    }

    @Test
    fun subsequentWavesRequireAllEarlierResultsToFailOrBeInferred(): Unit = runBlocking {
        val calls = mutableListOf<IpQualitySource>()
        val transport = IpQualityTransport { source, _ ->
            calls += source
            when (source) {
                IpQualitySource.identMe -> result(source, true)
                IpQualitySource.ipLocate -> result(source)
                else -> IpQualityOutcome.Failure(IpQualityFailure.noType)
            }
        }
        assertEquals(IpQualitySource.ipLocate, IpQualityScheduler(transport, waveDelayMillis = 0).query(ip).source)
        assertEquals(IpQualityScheduler.waves.take(2).flatten(), calls)
    }

    @Test
    fun inferredFallbackIsKeptUntilAllQuotaOrderedWavesFinish(): Unit = runBlocking {
        val calls = mutableListOf<IpQualitySource>()
        val transport = IpQualityTransport { source, _ ->
            calls += source
            if (source == IpQualitySource.ipApiCom) result(source, true)
            else IpQualityOutcome.Failure(IpQualityFailure.rateLimited)
        }
        val quality = IpQualityScheduler(transport, waveDelayMillis = 0).query(ip)
        assertTrue(quality.inferred)
        assertEquals(IpQualitySource.ipApiCom, quality.source)
        assertEquals(IpQualityScheduler.waves.flatten(), calls)
        assertEquals(IpQualitySource.proxyCheck, calls.last())
    }

    @Test
    fun singleSourceTimeoutsAreReportedForEveryAttempt(): Unit = runBlocking {
        val scheduler = IpQualityScheduler(IpQualityTransport { _, _ -> awaitCancellation() }, 20, 0)
        try {
            scheduler.query(ip)
            fail("All timeouts must fail")
        } catch (error: IpQualityException) {
            assertEquals(IpQualitySource.entries.toSet(), error.failures.keys)
            assertTrue(error.failures.values.all { it == IpQualityFailure.timeout })
        }
    }

    @Test
    fun parentCancellationStopsRequestsAndDoesNotStartLaterWaves(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val cancelled = AtomicInteger()
        val transport = IpQualityTransport { _, _ ->
            if (calls.incrementAndGet() == 3) started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.incrementAndGet() }
        }
        val job = launch { IpQualityScheduler(transport).query(ip) }
        started.await()
        job.cancelAndJoin()
        assertEquals(3, calls.get())
        assertEquals(3, cancelled.get())
    }

    @Test
    fun laterWavesRespectTheirEarliestOneSecondOffsets(): Unit = runBlocking {
        val starts = mutableListOf<Pair<IpQualitySource, Long>>()
        val base = System.nanoTime()
        val scheduler = IpQualityScheduler(IpQualityTransport { source, _ ->
            starts += source to (System.nanoTime() - base)
            IpQualityOutcome.Failure(IpQualityFailure.failed)
        }, waveDelayMillis = 20)
        try { scheduler.query(ip) } catch (_: IpQualityException) {}
        listOf(IpQualitySource.ipLocate, IpQualitySource.ipApiIs, IpQualitySource.proxyCheck).forEachIndexed { index, source ->
            assertTrue(starts.toString(), starts.single { it.first == source }.second / 1_000_000 >= (index + 1) * 20)
        }
    }

    @Test
    fun repositoryCachesThreeDaysAndRefreshBypassesSuccess(): Unit = runBlocking {
        var time = 1000L
        var calls = 0
        val store = MemoryStore()
        val repository = IpQualityRepository(
            { calls++; result(IpQualitySource.ipQuery).quality }, store, { time },
        )
        repository.get(ip)
        time += IpQualityRepository.CACHE_MILLIS - 1
        repository.get(ip)
        assertEquals(1, calls)
        repository.get(ip, true)
        assertEquals(2, calls)
        time += IpQualityRepository.CACHE_MILLIS
        repository.get(ip)
        assertEquals(3, calls)
    }

    @Test
    fun failureCooldownSurvivesRepositoryRecreationAndCannotBeRefreshSpammed(): Unit = runBlocking {
        var time = 1000L
        var calls = 0
        val store = MemoryStore()
        val query: suspend (String) -> IpQuality = {
            calls++
            throw IpQualityException(IpQualitySource.entries.associateWith { IpQualityFailure.rateLimited })
        }
        suspend fun attempt(repository: IpQualityRepository) {
            try { repository.get(ip, true); fail("Expected cooldown") } catch (error: IpQualityException) {
                assertNotNull(error.retryAt)
                assertEquals(6, error.failures.size)
            }
        }
        attempt(IpQualityRepository(query, store) { time })
        time += IpQualityRepository.COOLDOWN_MILLIS - 1
        attempt(IpQualityRepository(query, store) { time })
        assertEquals(1, calls)
        time++
        attempt(IpQualityRepository(query, store) { time })
        assertEquals(2, calls)
    }

    @Test
    fun simultaneousSameIpQueriesAreCoalescedAndCancelledQueriesAreNotCached(): Unit = runBlocking {
        val store = MemoryStore()
        var calls = 0
        val repository = IpQualityRepository({ delay(10); calls++; result(IpQualitySource.ipQuery).quality }, store)
        coroutineScope {
            (1..8).map { async { repository.get(ip) } }.forEach { it.await() }
        }
        assertEquals(1, calls)
        val other = IpQualityRepository({ awaitCancellation() }, store)
        val job = launch { other.get("203.0.113.9") }
        delay(10)
        job.cancelAndJoin()
        assertFalse(store.values.containsKey("203.0.113.9"))
    }

    private class MemoryStore : IpQualityStore {
        val values = mutableMapOf<String, IpQualityCacheEntry>()
        override fun read(ip: String) = values[ip]
        override fun write(ip: String, entry: IpQualityCacheEntry) { values[ip] = entry }
    }
}
