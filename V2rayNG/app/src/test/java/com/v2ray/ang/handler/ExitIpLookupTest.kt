package com.v2ray.ang.handler

import org.junit.Assert.*
import org.junit.Test

class ExitIpLookupTest {
    private val url = "https://api.ip.sb/geoip"

    @Test
    fun primaryTimeoutStillUsesIpv4Backup() {
        val requests = mutableListOf<String>()
        val info = ExitIpLookup.lookup(url) { endpoint ->
            requests.add(endpoint)
            when (endpoint) {
                "https://ipv4.icanhazip.com" -> "203.0.113.10\n"
                "$url/203.0.113.10" -> """{"ip":"203.0.113.10","country":"Example"}"""
                else -> null
            }
        }
        assertEquals("203.0.113.10", info?.ip)
        assertEquals("Example", info?.country)
        assertTrue(requests.contains("https://ipv4.icanhazip.com"))
    }

    @Test
    fun failedGeoLookupPreservesVerifiedIpv4() {
        val info = ExitIpLookup.lookup(url) { endpoint ->
            when (endpoint) {
                url -> """{"ip":"2001:db8::1","country":"Wrong"}"""
                "https://ipv4.icanhazip.com" -> "203.0.113.10"
                "$url/203.0.113.10" -> """{"ip":"2001:db8::2","country":"Wrong"}"""
                else -> null
            }
        }
        assertEquals("203.0.113.10", info?.ip)
        assertNull(info?.country)
    }

    @Test
    fun customEndpointIsNotBlindlyExtended() {
        val custom = "https://example.test/location?token=secret"
        val requests = mutableListOf<String>()
        val info = ExitIpLookup.lookup(custom) { endpoint ->
            requests.add(endpoint)
            if (endpoint == "https://api4.ipify.org") "203.0.113.20" else null
        }
        assertEquals("203.0.113.20", info?.ip)
        assertFalse(requests.any { it.contains("secret/") })
    }

    @Test
    fun validIpv4PrimaryDoesNotNeedBackup() {
        var calls = 0
        val info = ExitIpLookup.lookup(url) {
            calls++
            """{"query":"203.0.113.30"}"""
        }
        assertEquals("203.0.113.30", info?.ip)
        assertEquals(1, calls)
    }

    @Test
    fun rejectsIpv6AndMalformedIpv4() {
        listOf("2001:db8::1", "256.1.2.3", "1.2.3", "1.2.3.4.5", "01.2.3.4", "1.2.-3.4")
            .forEach { assertFalse(it, ExitIpLookup.isIpv4(it)) }
        assertTrue(ExitIpLookup.isIpv4("203.0.113.10"))
    }

    @Test
    fun fastLookupDoesNotWaitForSlowGeoEndpoint() {
        val started = System.nanoTime()
        val info = ExitIpLookup.lookupFast(url, timeoutMs = 2_000L) { endpoint ->
            when (endpoint) {
                url -> {
                    Thread.sleep(5_000L)
                    null
                }
                "https://ipv4.icanhazip.com" -> "203.0.113.40"
                else -> null
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertEquals("203.0.113.40", info?.ip)
        assertTrue("lookup took ${elapsedMs}ms", elapsedMs < 1_500L)
    }

    @Test
    fun dualLookupReturnsIpv4AndIpv6() {
        val result = ExitIpLookup.lookupDualFast(url) { endpoint ->
            when (endpoint) {
                "https://ipv4.icanhazip.com" -> "203.0.113.50"
                "https://ipv6.icanhazip.com" -> "2001:db8::50"
                else -> null
            }
        }

        assertEquals("203.0.113.50", result?.ipv4)
        assertEquals("2001:db8::50", result?.ipv6)
    }

    @Test
    fun ipv4OnlyLookupReturnsWithoutWaitingForOtherFamilies() {
        val started = System.nanoTime()
        val result = ExitIpLookup.lookupDualFast(
            url = url,
            timeoutMs = 2_000L,
            includeIpv6 = false,
        ) { endpoint ->
            when (endpoint) {
                "https://ipv4.icanhazip.com" -> "203.0.113.51"
                else -> {
                    Thread.sleep(2_000L)
                    null
                }
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertEquals("203.0.113.51", result?.ipv4)
        assertTrue("lookup took ${elapsedMs}ms", elapsedMs < 1_000L)
    }

    @Test
    fun ipv6GeoDoesNotOverrideAvailableIpv4LocationPriority() {
        val result = ExitIpLookup.lookupDualFast(url) { endpoint ->
            when (endpoint) {
                url -> """{"ip":"2001:db8::60","country":"Wrong IPv6 Place"}"""
                "https://ipv4.icanhazip.com" -> "203.0.113.60"
                else -> null
            }
        }

        assertEquals("203.0.113.60", result?.ipv4)
        assertEquals("2001:db8::60", result?.ipv6)
        assertNull(result?.geoInfo)
    }

    @Test
    fun validatesIpv6Addresses() {
        assertTrue(ExitIpLookup.isIpv6("2001:db8::1"))
        assertTrue(ExitIpLookup.isIpv6("2001:db8::1%wlan0"))
        assertFalse(ExitIpLookup.isIpv6("203.0.113.1"))
        assertFalse(ExitIpLookup.isIpv6("not-an-ip"))
    }
}
