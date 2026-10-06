package com.v2ray.ang.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMonitorTest {
    @Test
    fun acceptsOnlyValidatedPhysicalInternetNetworks() {
        assertTrue(NetworkMonitor.isEligibleUpstream(false, true, true))
        assertFalse(NetworkMonitor.isEligibleUpstream(true, true, true))
        assertFalse(NetworkMonitor.isEligibleUpstream(false, false, true))
        assertFalse(NetworkMonitor.isEligibleUpstream(false, true, false))
    }
}
