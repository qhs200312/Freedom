package com.v2ray.ang.ipquality

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IpQualityMockExampleTest {
    @Test
    fun mockJsonManualExample(): Unit = runBlocking {
        val body = javaClass.getResourceAsStream("/ipquality/mobile.json")!!.bufferedReader().use { it.readText() }
        val transport = IpQualityTransport { source, ip ->
            if (source == IpQualitySource.ipApiIs) IpQualityParser.parse(source, ip, 200, body)
            else IpQualityOutcome.Failure(IpQualityFailure.noType)
        }
        val result = IpQualityScheduler(transport, waveDelayMillis = 0).query("203.0.113.8")
        println("MOCK: ip=${result.ip}, type=${result.type}, level=${result.level}, " +
            "proxy=${result.isProxy}, vpn=${result.isVpn}, source=${result.source}, asn=${result.asn}")
        assertEquals(IpType.mobile, result.type)
        assertEquals(IpQualityLevel.good, result.level)
        assertTrue(result.isProxy && result.isVpn)
        assertFalse(result.inferred)
        assertEquals("Example Mobile", result.organization)
        assertEquals("AS64500", result.asn)
    }
}
