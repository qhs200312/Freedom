package com.v2ray.ang.ipquality

import com.google.gson.Gson
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NodeIpQualityLookupTest {
    private val ip = "203.0.113.8"

    @Test
    fun labelsDescribeTheTestedExitAndRetainClassificationFlags(): Unit = runBlocking {
        listOf(
            IpQuality(ip, IpQualitySource.ipQuery, IpType.residential),
            IpQuality(ip, IpQualitySource.ipQuery, IpType.hosting),
            IpQuality(ip, IpQualitySource.ipApiIs, IpType.mobile, isAbuser = true),
            IpQuality(ip, IpQualitySource.identMe, IpType.business, typeNeedsConfirmation = true),
            IpQuality(ip, IpQualitySource.ipApiCom, IpType.residential, inferred = true),
        ).forEach { expected ->
            val result = NodeIpQualityLookup({ " $ip\n" }, { requested ->
                assertEquals(ip, requested)
                expected
            }, now = { 100 }).lookup()
            assertEquals(ip, result.ip)
            assertEquals(expected, result.quality)
            assertEquals(100L, result.checkedAt)
        }
    }

    @Test
    fun missingOrInvalidExitNeverFallsBackToTheConnectedNode(): Unit = runBlocking {
        listOf(null, "", "example.com", "<html>blocked</html>").forEach { raw ->
            val result = NodeIpQualityLookup({ raw }, { error("Must not query another IP") }).lookup()
            assertNull(result.ip)
            assertNull(result.quality)
        }
    }

    @Test
    fun failedQualityLookupKeepsTheExitWithoutInventingAType(): Unit = runBlocking {
        val result = NodeIpQualityLookup({ ip }, {
            throw IpQualityException(mapOf(IpQualitySource.ipQuery to IpQualityFailure.rateLimited))
        }).lookup()
        assertEquals(ip, result.ip)
        assertNull(result.quality)
    }

    @Test
    fun mismatchedClassificationIsNeverAttachedToANode(): Unit = runBlocking {
        val result = NodeIpQualityLookup({ ip }, {
            IpQuality("198.51.100.5", IpQualitySource.ipQuery, IpType.hosting)
        }).lookup()
        assertEquals(ip, result.ip)
        assertNull(result.quality)
    }

    @Test
    fun timeoutReturnsUnknownAndCancelsLookup(): Unit = runBlocking {
        var cancelled = false
        val result = NodeIpQualityLookup({ ip }, {
            try { awaitCancellation() } finally { cancelled = true }
        }, timeoutMillis = 30).lookup()
        assertNull(result.quality)
        assertEquals(ip, result.ip)
        assertTrue(cancelled)
    }

    @Test
    fun cancellingTheBatchDoesNotPublishAFailureResult(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        var published = false
        var cancelled = false
        val job = launch {
            NodeIpQualityLookup({
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled = true }
            }, { error("Not reached") }).lookup()
            published = true
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertFalse(published)
    }

    @Test
    fun persistedResultsExpireAndOldAffiliationsRemainCompatible() {
        val gson = Gson()
        val old = gson.fromJson("""{"testDelayMillis":128,"testMethod":"connect"}""",
            ServerAffiliationInfo::class.java)
        assertNull(old.ipQuality)
        val quality = IpQuality(ip, IpQualitySource.ipQuery, IpType.hosting)
        val affiliation = old.copy(ipQuality = NodeIpQualityResult(ip, quality, 100))
        val restored = gson.fromJson(gson.toJson(affiliation), ServerAffiliationInfo::class.java)
        assertEquals(affiliation, restored)
        val result = requireNotNull(restored.ipQuality)
        assertFalse(result.isFresh(99))
        assertTrue(result.isFresh(100))
        assertTrue(result.isFresh(100 + IpQualityRepository.CACHE_MILLIS - 1))
        assertFalse(result.isFresh(100 + IpQualityRepository.CACHE_MILLIS))
    }
}
