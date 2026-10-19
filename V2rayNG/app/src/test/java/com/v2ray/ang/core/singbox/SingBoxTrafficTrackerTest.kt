package com.v2ray.ang.core.singbox

import com.v2ray.ang.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class SingBoxTrafficTrackerTest {
    @Test
    fun activeUpdatesAndClosedConnectionsKeepTheirOutbound() {
        val tracker = SingBoxTrafficTracker()
        tracker.record("direct-1", AppConfig.TAG_DIRECT, 10, 20, closed = false)
        tracker.addDelta("direct-1", 5, 7)
        tracker.record("proxy-1", "node-tag", 2, 3, closed = false)
        tracker.record("proxy-1", "node-tag", 9, 14, closed = true)

        val stats = tracker.drain().associateBy { it.tag to it.direction }
        assertEquals(15L, stats[AppConfig.TAG_DIRECT to AppConfig.UPLINK]?.value)
        assertEquals(27L, stats[AppConfig.TAG_DIRECT to AppConfig.DOWNLINK]?.value)
        assertEquals(9L, stats["node-tag" to AppConfig.UPLINK]?.value)
        assertEquals(14L, stats["node-tag" to AppConfig.DOWNLINK]?.value)
        assertEquals(emptyList<Any>(), tracker.drain())
    }

    @Test
    fun dnsConnectionsAreExcluded() {
        val tracker = SingBoxTrafficTracker()
        tracker.record("dns-1", "direct", 100, 200, closed = true, ignored = true)
        assertEquals(emptyList<Any>(), tracker.drain())
    }

    @Test
    fun differentRoutingOutboundsRemainSeparateAcrossDeltaAndClosedEvents() {
        val tracker = SingBoxTrafficTracker()
        tracker.record("us", "routing-profile-us", 10, 20, closed = false)
        tracker.record("jp", "routing-profile-jp", 30, 40, closed = false)
        tracker.addDelta("us", 5, 7)
        tracker.record("us", "routing-profile-us", 15, 27, closed = true)
        val stats = tracker.drain().associateBy { it.tag to it.direction }
        assertEquals(15L, stats["routing-profile-us" to AppConfig.UPLINK]?.value)
        assertEquals(27L, stats["routing-profile-us" to AppConfig.DOWNLINK]?.value)
        assertEquals(40L, stats["routing-profile-jp" to AppConfig.DOWNLINK]?.value)
    }
}
