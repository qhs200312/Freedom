package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.enums.ProxyMode
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CoreDnsRoutingTest {
    private fun fixture(udp: Boolean = true) = V2rayConfig(
        log = V2rayConfig.LogBean(loglevel = "warning"),
        inbounds = arrayListOf(V2rayConfig.InboundBean(
            tag = "socks", port = 10808, protocol = "socks", listen = "127.0.0.1",
            settings = V2rayConfig.InboundBean.InSettingsBean(auth = "noauth", udp = udp),
        )),
        outbounds = arrayListOf(
            V2rayConfig.OutboundBean(
                tag = AppConfig.TAG_PROXY, protocol = "vless",
                settings = V2rayConfig.OutboundBean.OutSettingsBean(
                    address = "127.0.0.1", port = 11443,
                    id = "11111111-1111-4111-8111-111111111111",
                    encryption = "none", flow = "xtls-rprx-vision",
                ),
                streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean(
                    network = "tcp", security = "reality",
                    realitySettings = V2rayConfig.OutboundBean.StreamSettingsBean.TlsSettingsBean(
                        serverName = "localhost", fingerprint = "chrome",
                        publicKey = "fixture-key", shortId = "abcd",
                    ),
                ),
                mux = null,
            ),
        ),
        dns = V2rayConfig.DnsBean(
            servers = arrayListOf("tcp://127.0.0.1:11553"), tag = AppConfig.TAG_DNS,
        ),
        routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = arrayListOf(
            V2rayConfig.RoutingBean.RulesBean(
                inboundTag = listOf(AppConfig.TAG_DNS), outboundTag = AppConfig.TAG_PROXY,
            ),
        )),
    )

    @Test
    fun unrelatedTunDnsRuleCannotSuppressRootSocksDns() {
        val config = fixture()
        config.routing.rules.add(0, V2rayConfig.RoutingBean.RulesBean(
            inboundTag = listOf("tun"), outboundTag = "dns-out", port = "53",
        ))
        // This was the old guard: the TUN rule incorrectly counted as configured root DNS.
        assertTrue(config.routing.rules.any { it.outboundTag == "dns-out" && it.port == "53" })
        assertFalse(config.routing.rules.any { it.inboundTag == listOf("socks") && it.port == "53" })
        export("legacy.json", config.also { it.outbounds.add(dnsOutbound()) })

        CoreDnsRouting.apply(config, rootMode = true, nativeTun = false, localDnsEnabled = true)

        assertEquals(listOf("socks"), config.routing.rules.first().inboundTag)
        assertEquals("dns-out", config.routing.rules.first().outboundTag)
        assertEquals("53", config.routing.rules.first().port)
        export("fixed.json", config)
    }

    @Test
    fun bothRootModesOverridePrivateAndCatchAllRulesWithLocalDnsOnOrOff() {
        for (mode in listOf(ProxyMode.ROOT_TUN, ProxyMode.TPROXY)) {
            for (enabled in listOf(false, true)) {
                val config = fixture()
                val bypass = V2rayConfig.RoutingBean.RulesBean(
                    ip = listOf("10.0.0.0/8"), outboundTag = AppConfig.TAG_DIRECT,
                )
                config.routing.rules.add(0, bypass)
                config.outbounds.add(V2rayConfig.OutboundBean(tag = AppConfig.TAG_DIRECT, protocol = "freedom", mux = null))
                CoreDnsRouting.apply(config, mode != ProxyMode.STANDARD, false, enabled)
                val first = config.routing.rules.first()
                assertEquals(listOf("socks"), first.inboundTag)
                assertEquals("dns-out", first.outboundTag)
                assertNull(first.ip)
                assertNull(first.domain)
                assertTrue(config.routing.rules.indexOf(bypass) > 0)
                if (!enabled) export("${mode.value}-dns-off.json", config)
            }
        }
    }

    @Test
    fun rootRequiresUdpButPreservesSocksAuthentication() {
        val config = fixture(udp = false)
        val settings = requireNotNull(config.inbounds.single().settings)
        settings.auth = "password"
        settings.accounts = listOf(V2rayConfig.InboundBean.InSettingsBean.SocksAccountBean("user", "secret"))
        CoreDnsRouting.apply(config, true, false, false)
        assertEquals(true, settings.udp)
        assertEquals("password", settings.auth)
        assertEquals("secret", settings.accounts?.single()?.pass)
    }

    @Test
    fun androidVpnHijacksActualTunWithoutChangingSocksUdpPreference() {
        val config = fixture(udp = false)
        config.inbounds.add(V2rayConfig.InboundBean("tun", null, "tun"))
        CoreDnsRouting.apply(config, false, true, true)
        assertEquals(listOf("tun"), config.routing.rules.first().inboundTag)
        assertEquals(false, config.inbounds.first().settings?.udp)
        assertFalse(config.routing.rules.any { it.inboundTag == listOf("socks") && it.port == "53" })
    }

    @Test
    fun disabledVpnLocalDnsDoesNotInjectAHijackOrDnsOutbound() {
        val config = fixture()
        val before = JsonUtil.toJson(config)
        CoreDnsRouting.apply(config, false, true, false)
        assertEquals(before, JsonUtil.toJson(config))
    }

    @Test
    fun proxyOnlyLocalDnsDoesNotInventATunOrInterceptSocks() {
        val config = fixture()
        CoreDnsRouting.apply(config, false, false, true)
        assertFalse(config.routing.rules.any { it.port == "53" })
        assertEquals(1, config.outbounds.count { it.protocol == "dns" })
    }

    @Test
    fun repeatedApplicationHasOneFirstRuleAndOneDnsOutbound() {
        val config = fixture()
        repeat(3) { CoreDnsRouting.apply(config, true, false, true) }
        assertEquals(1, config.routing.rules.count { it.port == "53" && it.inboundTag == listOf("socks") })
        assertEquals(1, config.outbounds.count { it.tag == "dns-out" })
    }

    @Test
    fun realityVisionAndUpstreamDnsRoutingRemainUnchanged() {
        val config = fixture()
        val outbound = JsonUtil.toJson(config.outbounds.first())
        val dns = JsonUtil.toJson(config.dns)
        val upstream = config.routing.rules.single()
        CoreDnsRouting.apply(config, true, false, true)
        assertEquals(outbound, JsonUtil.toJson(config.outbounds.first()))
        assertEquals(dns, JsonUtil.toJson(config.dns))
        assertTrue(config.routing.rules.contains(upstream))
        assertFalse(AppConfig.TAG_DNS in config.routing.rules.first().inboundTag.orEmpty())
    }

    @Test
    fun missingSocksInboundFailsBeforeReportingRootReady() {
        val config = fixture()
        config.inbounds.clear()
        assertThrows(IllegalArgumentException::class.java) {
            CoreDnsRouting.apply(config, true, false, true)
        }
    }

    @Test
    fun rootWinsOverAStaleVpnModeAndUsesEveryActualSocksTag() {
        val config = fixture()
        config.inbounds.single().tag = "custom-socks"
        config.inbounds.add(V2rayConfig.InboundBean("second-socks", 10809, "socks"))
        config.inbounds.add(V2rayConfig.InboundBean("tun", null, "tun"))
        CoreDnsRouting.apply(config, true, true, true)
        assertEquals(listOf("custom-socks", "second-socks"), config.routing.rules.first().inboundTag)
        assertTrue(config.inbounds.filter { it.protocol == "socks" }.all { it.settings?.udp == true })
    }

    @Test
    fun singBoxAlsoRequiresDnsInRootModesEvenIfTheOldVpnSettingWasOff() {
        for (root in listOf(false, true)) {
            for (setting in listOf(false, true)) {
                assertEquals(root || setting, CoreDnsRouting.requiresResolver(root, setting))
            }
        }
    }

    private fun dnsOutbound() = V2rayConfig.OutboundBean(protocol = "dns", tag = "dns-out", mux = null)

    private fun export(name: String, config: V2rayConfig) {
        System.getenv("FREEDOM_ROOT_DNS_FIXTURES")?.let {
            File(it).mkdirs()
            File(it, name).writeText(JsonUtil.toJson(config))
        }
    }
}
