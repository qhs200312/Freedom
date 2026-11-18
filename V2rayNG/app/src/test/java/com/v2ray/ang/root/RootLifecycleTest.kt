package com.v2ray.ang.root

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RootLifecycleTest {
    @Test
    fun replacementWaitsForOldRouteAndCoreAndOldStopCannotRemoveNewRoute(): Unit = runBlocking {
        val lifecycle = RootLifecycle<Any>()
        val old = Any()
        val next = Any()
        val events = mutableListOf<String>()
        lifecycle.start(old, { false }, { fail("No previous owner") }) { events += "old-start" }
        val entered = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val replacement = async {
            lifecycle.start(next, { false }, {
                assertSame(old, it)
                events += "old-rules-stop"
                entered.complete(Unit)
                cleaned.await()
                events += "old-core-closed"
            }) { events += "new-start" }
        }
        entered.await()
        val oldStop = launch { lifecycle.stop(old) { fail("Old service must not tear down the replacement") } }
        yield()
        assertEquals(listOf("old-start", "old-rules-stop"), events)
        cleaned.complete(Unit)
        replacement.await()
        oldStop.join()
        assertEquals(listOf("old-start", "old-rules-stop", "old-core-closed", "new-start"), events)
        assertTrue(lifecycle.isOwner(next))
    }

    @Test
    fun stopWaitsUntilSetupReturnsAndCleansOnlyOnce(): Unit = runBlocking {
        val lifecycle = RootLifecycle<Any>()
        val owner = Any()
        val setupEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var installed = false
        var cleaned = 0
        val startup = launch {
            lifecycle.start(owner, { false }, {}) {
                setupEntered.complete(Unit)
                release.await()
                installed = true
            }
        }
        setupEntered.await()
        val stop = launch { lifecycle.stop(owner) { assertTrue(installed); cleaned++ } }
        yield()
        assertEquals(0, cleaned)
        release.complete(Unit)
        startup.join()
        stop.join()
        lifecycle.stop(owner) { cleaned++ }
        assertEquals(1, cleaned)
        assertFalse(lifecycle.isOwner(owner))
    }

    @Test
    fun cancelledStartCannotCreateRulesAndUnownedCleanupDoesNotAffectLiveOwner(): Unit = runBlocking {
        val lifecycle = RootLifecycle<Any>()
        val owner = Any()
        lifecycle.start(owner, { true }, { fail() }) { fail("Cancelled setup") }
        assertFalse(lifecycle.isOwner(owner))
        var cleaned = 0
        lifecycle.stop(owner, cleanupIfUnowned = true) { cleaned++ }
        assertEquals(1, cleaned)
        lifecycle.start(owner, { false }, {}) {}
        lifecycle.stop(Any(), cleanupIfUnowned = true) { fail("Live owner must be preserved") }
        assertTrue(lifecycle.isOwner(owner))
    }

    @Test
    fun cleanupFailureReleasesLockAndAllowsAnotherStart(): Unit = runBlocking {
        val lifecycle = RootLifecycle<Any>()
        val owner = Any()
        lifecycle.start(owner, { false }, {}) {}
        try {
            lifecycle.stop(owner) { throw IllegalStateException("cleanup failed") }
            fail()
        } catch (_: IllegalStateException) {}
        assertFalse(lifecycle.isOwner(owner))
        val next = Any()
        lifecycle.start(next, { false }, { fail("Old owner leaked") }) {}
        assertTrue(lifecycle.isOwner(next))
    }
}
