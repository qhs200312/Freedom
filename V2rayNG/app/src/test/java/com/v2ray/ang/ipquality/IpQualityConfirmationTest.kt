package com.v2ray.ang.ipquality

import com.google.gson.Gson
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IpQualityConfirmationTest {
    private val ip = "203.0.113.8"
    private fun parse(source: IpQualitySource, body: String) =
        (IpQualityParser.parse(source, ip, 200, body) as IpQualityOutcome.Success).quality
    private fun business() = parse(IpQualitySource.identMe, """{"ip":"$ip","type":"business"}""")
    private val failed = IpQualityOutcome.Failure(IpQualityFailure.failed)

    @Test
    fun onlyIdentMeBusinessRequiresConfirmation() {
        for (type in listOf("business", "BUSINESS", "isp", "hosting", "mobile", "education")) {
            val quality = parse(IpQualitySource.identMe, """{"ip":"$ip","type":"$type"}""")
            assertEquals(type, type.equals("business", true), quality.typeNeedsConfirmation)
        }
        for (source in listOf(IpQualitySource.ipLocate, IpQualitySource.ipApiIs)) {
            val quality = parse(source, """{"company":{"type":"business"},"asn":{"type":"isp"}}""")
            assertEquals(IpType.business, quality.type)
            assertFalse(quality.typeNeedsConfirmation)
        }
    }

    @Test
    fun laterProviderResolvesProvisionalBusinessWithoutQueryingExpensiveSources(): Unit = runBlocking {
        for ((declaration, expected) in listOf("isp" to IpType.residential, "business" to IpType.business)) {
            val calls = mutableListOf<IpQualitySource>()
            val scheduler = IpQualityScheduler(IpQualityTransport { source, _ ->
                calls += source
                when (source) {
                    IpQualitySource.identMe -> IpQualityOutcome.Success(business())
                    IpQualitySource.ipQuery -> IpQualityParser.parse(source, ip, 200,
                        """{"risk":{"is_datacenter":false},"isp":{"org":"Example ISP"}}""")
                    IpQualitySource.ipLocate -> IpQualityParser.parse(source, ip, 200,
                        """{"company":{"type":"$declaration"},"asn":{"type":"isp"},"privacy":{"is_hosting":false}}""")
                    else -> failed
                }
            }, waveDelayMillis = 0)
            val quality = scheduler.query(ip)
            assertEquals(expected, quality.type)
            assertEquals(IpQualitySource.ipLocate, quality.source)
            assertFalse(quality.typeNeedsConfirmation)
            assertEquals(IpQualityScheduler.waves.take(2).flatten(), calls)
        }
    }

    @Test
    fun hostingWinsAndCancelsPendingRequest(): Unit = runBlocking {
        var cancelled = false
        val quality = IpQualityScheduler(IpQualityTransport { source, _ ->
            when (source) {
                IpQualitySource.identMe -> IpQualityOutcome.Success(business())
                IpQualitySource.ipQuery -> {
                    delay(10)
                    IpQualityOutcome.Success(IpQuality(ip, source, IpType.hosting))
                }
                else -> try { awaitCancellation() } finally { cancelled = true }
            }
        }, waveDelayMillis = 0).query(ip)
        assertEquals(IpType.hosting, quality.type)
        assertTrue(cancelled)
    }

    @Test
    fun unconfirmedDeclarationBeatsInferredFallbackRegardlessOfArrivalOrder(): Unit = runBlocking {
        for (businessFirst in listOf(true, false)) {
            val calls = mutableListOf<IpQualitySource>()
            val quality = IpQualityScheduler(IpQualityTransport { source, _ ->
                calls += source
                when (source) {
                    IpQualitySource.identMe -> {
                        if (!businessFirst) delay(10)
                        IpQualityOutcome.Success(business())
                    }
                    IpQualitySource.ipQuery -> {
                        if (businessFirst) delay(10)
                        IpQualityOutcome.Success(IpQuality(ip, source, IpType.residential, inferred = true))
                    }
                    else -> failed
                }
            }, waveDelayMillis = 0).query(ip)
            assertEquals(IpType.business, quality.type)
            assertTrue(quality.typeNeedsConfirmation)
            assertEquals(IpQualityScheduler.waves.flatten(), calls)
        }
    }

    @Test
    fun oldSerializedSuccessIsRequeriedButOldFailureCooldownIsPreserved(): Unit = runBlocking {
        val gson = Gson()
        var entry = gson.fromJson(
            """{"savedAt":1000,"quality":{"ip":"$ip","source":"identMe","type":"business"}}""",
            IpQualityCacheEntry::class.java,
        )
        assertEquals(0, entry.classificationVersion)
        val store = object : IpQualityStore {
            override fun read(ip: String) = entry
            override fun write(ip: String, value: IpQualityCacheEntry) { entry = value }
        }
        var calls = 0
        val repository = IpQualityRepository({
            calls++
            IpQuality(ip, IpQualitySource.ipLocate, IpType.residential)
        }, store) { 2000L }
        assertEquals(IpType.residential, repository.get(ip).type)
        assertEquals(CURRENT_CLASSIFICATION_VERSION, entry.classificationVersion)
        repository.get(ip)
        assertEquals(1, calls)
        entry = gson.fromJson("""{"savedAt":1000,"failures":{"identMe":"rateLimited"}}""",
            IpQualityCacheEntry::class.java)
        try {
            repository.get(ip, refresh = true)
            fail("Legacy failure must retain cooldown")
        } catch (error: IpQualityException) {
            assertEquals(1000L + IpQualityRepository.COOLDOWN_MILLIS, error.retryAt)
        }
        assertEquals(1, calls)
    }
}
