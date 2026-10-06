package com.v2ray.ang.core.runtime

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class CoreSessionState { STOPPED, STARTING, RUNNING, READY, RELOADING, STOPPING }

/**
 * Serializes native operations. Stop invalidates pending startup/recovery immediately,
 * before waiting for a blocking native operation to finish.
 */
internal class CoreSession {
    private val lock = ReentrantLock()
    private val commands = Any()
    private val revision = AtomicLong()
    @Volatile private var requestedOwner: Any? = null
    private var runtimeOwner: Any? = null
    @Volatile var state = CoreSessionState.STOPPED
        private set
    @Volatile var runtime: CoreRuntime? = null
        private set
    @Volatile private var activeRevision = 0L

    val token: Long get() = activeRevision
    val isRunning: Boolean
        get() = activeRevision == revision.get() &&
            state != CoreSessionState.STOPPING && runtime?.isRunning == true
    val isReady: Boolean get() = state == CoreSessionState.READY && isRunning
    fun isLatest(token: Long): Boolean = token == revision.get()

    fun isCurrent(token: Long): Boolean =
        token == revision.get() && token == activeRevision && runtime != null

    fun start(owner: Any = Unit, factory: () -> CoreRuntime): Long? {
        val ticket = synchronized(commands) {
            requestedOwner = owner
            revision.incrementAndGet()
        }
        return lock.withLock {
            if (ticket != revision.get()) return@withLock null
            closeRuntime()
            state = CoreSessionState.STARTING
            activeRevision = ticket
            try {
                val next = factory()
                runtime = next
                runtimeOwner = owner
                if (ticket != revision.get()) {
                    closeRuntime()
                    return@withLock null
                }
                next.start()
                if (ticket != revision.get()) {
                    closeRuntime()
                    return@withLock null
                }
                check(next.isRunning) { "Core did not start" }
                state = CoreSessionState.RUNNING
                ticket
            } catch (error: Exception) {
                runCatching { closeRuntime() }
                if (ticket == revision.get()) state = CoreSessionState.STOPPED
                throw error
            }
        }
    }

    fun markReady(ticket: Long): Boolean = lock.withLock {
        if (!isCurrent(ticket) || !isRunning) return@withLock false
        state = CoreSessionState.READY
        true
    }

    fun requestStop(owner: Any? = requestedOwner): Long? = synchronized(commands) {
        if (owner !== requestedOwner) return@synchronized null
        state = CoreSessionState.STOPPING
        revision.incrementAndGet()
    }

    fun finishStop(ticket: Long) = lock.withLock {
        if (ticket != revision.get()) return@withLock
        try {
            closeRuntime()
        } finally {
            state = CoreSessionState.STOPPED
            synchronized(commands) {
                if (ticket == revision.get()) requestedOwner = null
            }
        }
    }

    /** An old Android service may be destroyed after a replacement requested ownership. */
    fun retire(owner: Any) = lock.withLock {
        if (runtimeOwner === owner) closeRuntime()
    }

    fun reload(ticket: Long): Boolean = lock.withLock {
        if (!isCurrent(ticket) || state != CoreSessionState.READY) return@withLock false
        val current = runtime ?: return@withLock false
        state = CoreSessionState.RELOADING
        try {
            if (!current.restart { isCurrent(ticket) }) return@withLock false
            if (!isCurrent(ticket)) {
                current.stop()
                return@withLock false
            }
            check(current.isRunning) { "Core did not restart" }
            state = CoreSessionState.READY
            true
        } catch (error: Exception) {
            runCatching { closeRuntime() }
            if (ticket == revision.get()) state = CoreSessionState.STOPPED
            throw error
        }
    }

    fun measureDelay(ticket: Long, url: String): Long = lock.withLock {
        if (!isCurrent(ticket) || !isRunning) return@withLock -1
        runtime?.measureDelay(url) ?: -1
    }

    fun trafficStats() = if (lock.tryLock()) {
        try {
            if (isRunning) runtime?.trafficStats().orEmpty() else emptyList()
        } finally {
            lock.unlock()
        }
    } else emptyList()

    private fun closeRuntime() {
        val previous = runtime
        runtime = null
        runtimeOwner = null
        previous?.stop()
    }
}
