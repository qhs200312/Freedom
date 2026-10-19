package com.v2ray.ang.fmt

import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.*
import org.junit.Test

class AnyTlsFmtTest {
    @Test
    fun officialLinksDefaultToPort443AndVerifiedTls() {
        val profile = AnyTlsFmt.parse("anytls://letmein@example.com/?sni=real.example.com")!!
        assertEquals(EConfigType.ANYTLS, profile.configType)
        assertEquals("443", profile.serverPort)
        assertEquals("letmein", profile.password)
        assertEquals("real.example.com", profile.sni)
        assertEquals("tls", profile.security)
        assertFalse(profile.insecure!!)
        assertNull(profile.anytlsMinIdleSession)
    }

    @Test
    fun percentEncodingIpv6AndSessionSettingsRoundTrip() {
        val profile = AnyTlsFmt.parse(
            "anytls://p%40ss%3A%2B%2520@[2001:db8::1]:8443/?" +
                "sni=tls.example&insecure=1&alpn=h2%2Chttp%2F1.1&fp=chrome&" +
                "idle_session_check_interval=1m30s&idle_session_timeout=45s&" +
                "min_idle_session=3&client_metadata=test%2Bclient#Test%20%2B%20Node",
        )!!
        assertEquals("p@ss:+%20", profile.password)
        assertEquals("2001:db8::1", profile.server)
        assertEquals("Test + Node", profile.remarks)
        val restored = AnyTlsFmt.parse("anytls://${AnyTlsFmt.toUri(profile)}")!!
        assertEquals(profile.copy(addedTime = 0), restored.copy(addedTime = 0))
    }

    @Test
    fun queryFlagsWithoutValuesAndUnknownExtensionsDoNotCrash() {
        val profile = AnyTlsFmt.parse(
            "anytls://secret@server.example?unknown&another=a=b=c&insecure=0&insecure=1",
        )!!
        assertFalse(profile.insecure!!)
        assertEquals("AnyTLS", profile.remarks)
    }

    @Test
    fun malformedEndpointsAndSessionSettingsAreRejected() {
        listOf(
            "anytls://host",
            "anytls://@host",
            "anytls://secret@host:0",
            "anytls://secret@host:65536",
            "anytls://secret@host/path",
            "anytls://secret@host?insecure=bad",
            "anytls://secret@host?min_idle_session=-1",
            "anytls://secret@host?min_idle_session=1.5",
            "anytls://secret@host?idle_session_timeout=30",
            "anytls://secret@host?idle_session_timeout=0s",
            "anytls://secret@host?idle_session_check_interval=-5s",
        ).forEach { assertNull(it, AnyTlsFmt.parse(it)) }
    }
}
