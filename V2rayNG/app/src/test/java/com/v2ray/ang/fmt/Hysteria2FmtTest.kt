package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Hysteria2FmtTest {

    @Test
    fun parseReadsBandwidthParameters() {
        val config = Hysteria2Fmt.parse(
            "hysteria2://password@example.com:443?downmbps=200&upmbps=100#test"
        )

        assertEquals("200Mbps", config.bandwidthDown)
        assertEquals("100Mbps", config.bandwidthUp)
    }

    @Test
    fun parseReadsBrutalBandwidthParameters() {
        val config = Hysteria2Fmt.parse(
            "hysteria2://password@example.com:443?brutalDown=200Mbps&brutalUp=100Mbps#test"
        )

        assertEquals("200Mbps", config.bandwidthDown)
        assertEquals("100Mbps", config.bandwidthUp)
    }

    @Test
    fun parseReadsBrutalBandwidthParametersIgnoringCase() {
        val config = Hysteria2Fmt.parse(
            "hysteria2://password@example.com:443?BRUTALDOWN=200&BRUTALUP=100#test"
        )

        assertEquals("200Mbps", config.bandwidthDown)
        assertEquals("100Mbps", config.bandwidthUp)
    }

    @Test
    fun parseReadsBandwidthAndPortHoppingFromFinalMask() {
        val finalMask = """{
            "udp":[{"type":"salamander","settings":{"password":"secret"}}],
            "quicParams":{
                "congestion":"force-brutal",
                "brutalUp":"100+mbps",
                "brutalDown":"200+mbps",
                "udpHop":{"ports":"30000-40000","interval":"30"}
            }
        }""".trimIndent()
        val config = Hysteria2Fmt.parse(
            "hysteria2://password@example.com:443?security=tls&fm=${Utils.encodeURIComponent(finalMask)}#test"
        )

        assertEquals("200Mbps", config.bandwidthDown)
        assertEquals("100Mbps", config.bandwidthUp)
        assertEquals("30000-40000", config.portHopping)
        assertEquals("30", config.portHoppingInterval)
    }

    @Test
    fun topLevelBandwidthAndPortTakePriorityOverFinalMask() {
        val finalMask = """{
            "quicParams":{
                "brutalUp":"10+mbps",
                "brutalDown":"20+mbps",
                "udpHop":{"ports":"10000-20000","interval":"30"}
            }
        }""".trimIndent()
        val config = Hysteria2Fmt.parse(
            "hysteria2://password@example.com:443" +
                "?upmbps=100&downmbps=200&mport=30000-40000" +
                "&fm=${Utils.encodeURIComponent(finalMask)}#test"
        )

        assertEquals("200Mbps", config.bandwidthDown)
        assertEquals("100Mbps", config.bandwidthUp)
        assertEquals("30000-40000", config.portHopping)
        assertEquals("30", config.portHoppingInterval)
    }

    @Test
    fun compactPortHoppingSubscriptionUsesCompatibleDefaults() {
        val config = Hysteria2Fmt.parse(
            "hysteria2://password@example.com:443" +
                "?security=tls&obfs=salamander&obfs-password=secret" +
                "&mport=30000-40000#us2"
        )

        assertEquals("100Mbps", config.bandwidthDown)
        assertEquals("100Mbps", config.bandwidthUp)
        assertEquals("30000-40000", config.portHopping)
        assertEquals("30", config.portHoppingInterval)
    }

    @Test
    fun simpleSubscriptionWithoutPortHoppingKeepsBandwidthUnset() {
        val config = Hysteria2Fmt.parse(
            "hysteria2://password@example.com:443?security=tls#test"
        )

        assertEquals(null, config.bandwidthDown)
        assertEquals(null, config.bandwidthUp)
        assertEquals(null, config.portHoppingInterval)
    }

    @Test
    fun toUriWritesBandwidthParameters() {
        val config = ProfileItem.create(EConfigType.HYSTERIA2).apply {
            remarks = "test"
            server = "example.com"
            serverPort = "443"
            password = "password"
            bandwidthDown = "200Mbps"
            bandwidthUp = "100Mbps"
        }

        val uri = Hysteria2Fmt.toUri(config)

        assertTrue(uri.contains("downmbps=200"))
        assertTrue(uri.contains("upmbps=100"))
    }
}
