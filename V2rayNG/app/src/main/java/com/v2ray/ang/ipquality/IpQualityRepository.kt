package com.v2ray.ang.ipquality

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class IpQualityCacheEntry(
    val savedAt: Long,
    val quality: IpQuality? = null,
    val failures: Map<IpQualitySource, IpQualityFailure>? = null,
)

interface IpQualityStore {
    fun read(ip: String): IpQualityCacheEntry?
    fun write(ip: String, entry: IpQualityCacheEntry)
}

class IpQualityRepository(
    private val query: suspend (String) -> IpQuality,
    private val store: IpQualityStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun get(rawIp: String, refresh: Boolean = false): IpQuality {
        val ip = requireNotNull(IpQualityAddress.normalize(rawIp)) { "Invalid IP literal" }
        return locks.getOrPut(ip) { Mutex() }.withLock {
            val cached = store.read(ip)
            val age = cached?.let { now() - it.savedAt } ?: -1L
            if (cached != null && age >= 0) {
                if (!refresh && age < CACHE_MILLIS && cached.quality?.ip == ip) return@withLock cached.quality
                // Refresh bypasses successful cache entries, but never the quota-protecting failure cooldown.
                if (age < COOLDOWN_MILLIS && cached.failures != null) {
                    throw IpQualityException(cached.failures, cached.savedAt + COOLDOWN_MILLIS)
                }
            }
            try {
                query(ip).also { store.write(ip, IpQualityCacheEntry(now(), quality = it)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IpQualityException) {
                val time = now()
                store.write(ip, IpQualityCacheEntry(time, failures = error.failures))
                throw IpQualityException(error.failures, time + COOLDOWN_MILLIS)
            }
        }
    }

    companion object {
        const val CACHE_MILLIS = 3L * 24 * 60 * 60 * 1000
        const val COOLDOWN_MILLIS = 5L * 60 * 1000
    }
}
