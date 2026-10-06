package com.v2ray.ang

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.core.SingBoxConfigGenerator
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.dto.entities.RulesetItem
import corebundle.Corebundle
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.dto.UrlContentRequest
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DualCoreNativeInstrumentedTest {
    @Test
    fun nativeSingBoxSocksAuthenticationCarriesActualHttpData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = Corebundle.newSingBoxController(context.filesDir.absolutePath, null)
        val socksPort = Utils.findRandomFreePort()
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            val responseTask = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    assertTrue(reader.readLine().startsWith("GET /"))
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write(
                        "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                    )
                    socket.getOutputStream().flush()
                }
            }
            try {
                controller.start("""{
                    "log":{"level":"warn"},
                    "inbounds":[{"type":"mixed","listen":"127.0.0.1","listen_port":$socksPort,
                        "users":[{"username":"user","password":"secret"}]}],
                    "outbounds":[{"type":"direct","tag":"direct"}],
                    "route":{"final":"direct"}
                }""")
                val request = UrlContentRequest(
                    url = "http://127.0.0.1:${server.localPort}/", timeout = 5_000,
                    socksPort = socksPort, proxyUsername = "user", proxyPassword = "secret",
                )
                assertTrue(HttpUtil.isUrlReachable(request))
                responseTask.get(6, TimeUnit.SECONDS)
                controller.stop()
                assertTrue(!HttpUtil.isUrlReachable(request.copy(timeout = 500)))
            } finally {
                controller.close()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun runtimeSocksPortIsReadFromSharedStorageWithoutProcessCache() {
        val previousDynamic = MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
        val previousPort = MmkvManager.decodeSettingsString("runtime_socks_port")
        try {
            MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_SOCKS_PORT, true)
            MmkvManager.encodeSettings("runtime_socks_port", "32100")
            assertEquals(32100, SettingsManager.getSocksPort())
            MmkvManager.encodeSettings("runtime_socks_port", "32200")
            assertEquals(32200, SettingsManager.getSocksPort())
        } finally {
            MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_SOCKS_PORT, previousDynamic)
            MmkvManager.encodeSettings("runtime_socks_port", previousPort.orEmpty())
        }
    }

    @Test
    fun bundledCoresExposeExpectedVersionsAndAcceptHy2Config() {
        assertTrue(Libv2ray.checkVersionX().contains("Xray-core v26.9.9"))
        assertEquals("1.14.0", Corebundle.singBoxVersion())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SettingsManager.initAssets(context, context.assets)
        val config = SingBoxConfigGenerator.generate(
            profile = NodeProfile(
                remarks = "native-check",
                protocol = EConfigType.HYSTERIA2,
                server = "example.com",
                port = 443,
                password = "test-password",
                tlsEnabled = true,
                sni = "example.com",
                insecure = false,
                alpn = listOf("h3"),
                obfsType = "salamander",
                obfsPassword = "test-obfs",
                upMbps = 100,
                downMbps = 100,
                hopPorts = "30000-40000",
                hopInterval = "30",
            ),
            socksPort = 20808,
            httpPort = 20809,
            logLevel = "warn",
            routingRules = listOf(
                RulesetItem(ip = listOf("geoip:private"), outboundTag = "direct"),
                RulesetItem(domain = listOf("geosite:private"), outboundTag = "direct"),
                RulesetItem(ip = listOf("geoip:cn"), outboundTag = "direct"),
                RulesetItem(domain = listOf("geosite:cn"), outboundTag = "direct"),
                RulesetItem(port = "0-65535", outboundTag = "proxy"),
            ),
            localDnsEnabled = true,
            remoteDnsServers = listOf("https://cloudflare-dns.com/dns-query"),
            domesticDnsServers = listOf("223.5.5.5"),
            ruleSetDir = Utils.userAssetPath(context),
        )
        Corebundle.checkSingBoxConfig(config)

        val protocolProfiles = listOf(
            NodeProfile(
                remarks = "vmess", protocol = EConfigType.VMESS, server = "example.com", port = 443,
                password = "11111111-1111-1111-1111-111111111111", method = "auto",
                tlsEnabled = false, insecure = false,
            ),
            NodeProfile(
                remarks = "vless", protocol = EConfigType.VLESS, server = "example.com", port = 443,
                password = "11111111-1111-1111-1111-111111111111", method = "none",
                network = "ws", host = "example.com", path = "/ws",
                tlsEnabled = false, insecure = false,
            ),
            NodeProfile(
                remarks = "trojan", protocol = EConfigType.TROJAN, server = "example.com", port = 443,
                password = "secret", security = "tls", tlsEnabled = true, sni = "example.com", insecure = false,
            ),
            NodeProfile(
                remarks = "shadowsocks", protocol = EConfigType.SHADOWSOCKS, server = "example.com", port = 8388,
                password = "secret", method = "aes-256-gcm", tlsEnabled = false, insecure = false,
            ),
            NodeProfile(
                remarks = "socks", protocol = EConfigType.SOCKS, server = "example.com", port = 1080,
                username = "user", password = "secret", tlsEnabled = false, insecure = false,
            ),
            NodeProfile(
                remarks = "http", protocol = EConfigType.HTTP, server = "example.com", port = 8080,
                username = "user", password = "secret", tlsEnabled = false, insecure = false,
            ),
            NodeProfile(
                remarks = "wireguard", protocol = EConfigType.WIREGUARD, server = "1.1.1.1", port = 51820,
                tlsEnabled = false, insecure = false,
                wireGuardPrivateKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
                wireGuardPublicKey = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
                wireGuardLocalAddresses = listOf("10.0.0.2/32"),
                wireGuardReserved = listOf(0, 0, 0),
            ),
        )
        protocolProfiles.forEach { profile ->
            val protocolConfig = SingBoxConfigGenerator.generate(
                profile = profile,
                socksPort = 21808,
                httpPort = 21809,
                logLevel = "warn",
                localDnsEnabled = false,
            )
            Corebundle.checkSingBoxConfig(protocolConfig)
        }

        val controller = Corebundle.newSingBoxController(context.filesDir.absolutePath, null)
        try {
            controller.start(config)
            assertTrue(controller.isRunning)
            assertTrue(controller.uplinkTotal() >= 0L)
            assertTrue(controller.downlinkTotal() >= 0L)
            controller.stop()
        } finally {
            controller.close()
        }

        Seq.setContext(context)
        Libv2ray.initCoreEnv(context.filesDir.absolutePath, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val xray = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(l: Long, s: String?): Long = 0
        })
        xray.startLoop(
            """{
                "log":{"loglevel":"none"},
                "inbounds":[{"listen":"127.0.0.1","port":20810,"protocol":"socks","settings":{"udp":true}}],
                "outbounds":[
                    {"protocol":"freedom","tag":"proxy"},
                    {"protocol":"freedom","tag":"direct"},
                    {"protocol":"blackhole","tag":"block"}
                ],
                "routing":{"rules":[
                    {"type":"field","network":"udp","protocol":["stun"],"outboundTag":"proxy"},
                    {"type":"field","network":"udp","domain":["full:stun.l.google.com","full:stun.cloudflare.com"],"outboundTag":"proxy"}
                ]}
            }""".trimIndent(),
            0,
        )
        assertTrue(xray.isRunning)
        xray.stopLoop()
    }
}
