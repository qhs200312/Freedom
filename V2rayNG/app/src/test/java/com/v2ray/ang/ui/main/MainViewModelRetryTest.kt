package com.v2ray.ang.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class MainViewModelRetryTest {
    @Test
    fun proxiedExitIpLookupRetriesWithShortBackoff() {
        assertEquals(listOf(0L, 300L, 700L, 1_500L), exitIpRetryDelays(useProxy = true))
    }

    @Test
    fun directExitIpLookupDoesNotRetry() {
        assertEquals(listOf(0L), exitIpRetryDelays(useProxy = false))
    }
}
