package com.v2ray.ang.core.nowhere

import org.junit.Assert.*
import org.junit.Test

class NowhereEndpointTest {
    private val key = "0123456789abcdef0123456789abcdef"

    @Test
    fun shareLinksPreserveTransportMaskingTrustAndUnicodeNames() {
        val endpoint = NowhereEndpoint(
            "2001:db8::1", 2000, key, "udp", false, "relay.example", "a".repeat(64), "Test + Node",
        )
        assertEquals(endpoint, NowhereEndpoint.parse(endpoint.shareLink()))
        assertFalse(endpoint.toString().contains(key))
    }

    @Test
    fun firstRecognizedDuplicateWinsAndLocalListenerCannotBeOverridden() {
        val endpoint = NowhereEndpoint.parse(
            "nowhere://$key@relay.example:2000?net=udp&net=tcp&morph=0&morph=1&in=0.0.0.0:1234",
        )
        val vector = endpoint.vectorUrl(23456, "user", "pass")
        assertTrue(vector.contains("/udp:2000?"))
        assertTrue(vector.contains("in=user:pass@127.0.0.1:23456"))
        assertTrue(vector.endsWith("&morph=0"))
        assertFalse(vector.contains("0.0.0.0"))
    }

    @Test
    fun invalidCredentialsPortsAndTrustSettingsAreRejected() {
        listOf(
            "nowhere://short@host:2000",
            "nowhere://$key:password@host:2000",
            "nowhere://$key@host:0",
            "nowhere://$key@host:65536",
            "nowhere://$key@host:2000/path",
            "nowhere://$key@host:2000?net=quic",
            "nowhere://$key@host:2000?pin=invalid",
            "nowhere://$key@host:2000?morph=2&morph=1",
        ).forEach { uri -> assertTrue(runCatching { NowhereEndpoint.parse(uri) }.isFailure) }
    }
}
