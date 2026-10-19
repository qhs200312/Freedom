package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReachabilityRaceTest {
    @Test
    fun healthyFallbackCompletesWithoutWaitingForTheSlowPrimary() {
        val primaryStarted = CountDownLatch(1)
        val releasePrimary = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()
        try {
            val result = caller.submit<Boolean> {
                firstReachable(listOf("slow", "fast")) { url ->
                    if (url == "slow") {
                        primaryStarted.countDown()
                        releasePrimary.await()
                        false
                    } else {
                        assertTrue(primaryStarted.await(1, TimeUnit.SECONDS))
                        true
                    }
                }
            }
            assertTrue(result.get(2, TimeUnit.SECONDS))
        } finally {
            releasePrimary.countDown()
            caller.shutdownNow()
        }
    }

    @Test
    fun failedAndEmptyCandidatesCannotReportSuccess() {
        assertFalse(firstReachable(emptyList()) { error("No probe should be started") })
        assertFalse(firstReachable(listOf("a", "b")) { false })
        assertFalse(firstReachable(listOf("a")) { throw IllegalStateException("Unreachable") })
    }

    @Test
    fun deadlineCancelsBlockedProbes() {
        val cancelled = CountDownLatch(1)
        assertFalse(firstReachable(listOf("blocked"), timeoutMs = 100) {
            try { CountDownLatch(1).await(); false } finally { cancelled.countDown() }
        })
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
    }
}
