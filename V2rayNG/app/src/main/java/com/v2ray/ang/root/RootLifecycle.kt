package com.v2ray.ang.root

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes complete route/core transactions, including replacement-service teardown. */
internal class RootLifecycle<T : Any> {
    private val lock = Mutex()
    @Volatile private var owner: T? = null
    fun isOwner(candidate: T): Boolean = owner === candidate

    suspend fun start(candidate: T, cancelled: () -> Boolean, retire: suspend (T) -> Unit, setup: suspend () -> Unit) {
        lock.withLock {
            if (cancelled()) return@withLock
            val previous = owner
            if (previous != null && previous !== candidate) {
                retire(previous)
                owner = null
            }
            if (cancelled()) return@withLock
            owner = candidate
            setup()
        }
    }

    suspend fun stop(candidate: T, cleanupIfUnowned: Boolean = false, teardown: suspend () -> Unit) {
        lock.withLock {
            if (owner !== candidate && !(owner == null && cleanupIfUnowned)) return@withLock
            try { teardown() } finally { owner = null }
        }
    }
}
