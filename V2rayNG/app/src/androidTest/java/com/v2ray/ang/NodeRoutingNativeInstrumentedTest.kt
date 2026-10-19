package com.v2ray.ang

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.SingBoxConfigGenerator
import com.v2ray.ang.core.SingBoxCoreManager
import com.v2ray.ang.core.SingBoxPlatformInterface
import com.v2ray.ang.dto.NodeTrafficBinding
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.handler.NodeTrafficLedger
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.routing.loadRoutingNodeOptions
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import corebundle.Corebundle
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class NodeRoutingNativeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bothCoresSendAssignedServicesAndUnmatchedRequestsToDifferentNodes() {
        val oldRules = MmkvManager.decodeRoutingRulesets()
        val oldSelected = MmkvManager.getSelectServer()
        val mainId = UUID.randomUUID().toString()
        val targetId = UUID.randomUUID().toString()
        val subscriptionId = UUID.randomUUID().toString()
        val settings = mapOf(
            AppConfig.PREF_LOCAL_DNS_ENABLED to true,
            AppConfig.PREF_FAKE_DNS_ENABLED to false,
            AppConfig.PREF_ROOT_MODE_ENABLE to false,
            AppConfig.PREF_BLOCK_GOOGLE_LOCATION_ENDPOINTS to true,
            AppConfig.PREF_BLOCK_GOOGLE_MAPS_SERVICES to true,
        ).mapValues { (key, default) -> MmkvManager.decodeSettingsBool(key, default) }
        try {
            settings.keys.forEach { MmkvManager.encodeSettings(it, it == AppConfig.PREF_ROOT_MODE_ENABLE) }
            MmkvManager.encodeSubscription(subscriptionId, SubscriptionItem(remarks = "Routing test subscription"))
            Seq.setContext(context)
            Libv2ray.initCoreEnv(context.filesDir.absolutePath, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
            for (singBox in listOf(false, true)) {
                RecordingHttpProxy("selected").use { selectedProxy ->
                    RecordingHttpProxy("assigned").use { assignedProxy ->
                        val primary = httpNode("Selected local node", selectedProxy.port)
                        val assigned = httpNode("Assigned subscription node", assignedProxy.port).copy(subscriptionId = subscriptionId)
                        MmkvManager.encodeServerConfig(mainId, primary)
                        MmkvManager.encodeServerConfig(targetId, assigned)
                        MmkvManager.setSelectServer(mainId)
                        MmkvManager.encodeRoutingRulesets(mutableListOf(
                            RulesetItem(
                                remarks = "Claude", domain = listOf("https://claude.ai/path?source=test", "domain:anthropic.com"),
                                outboundTag = assigned.remarks, outboundProfileId = targetId,
                            ),
                        ))
                        assertEquals(
                            listOf("domain:claude.ai", "domain:anthropic.com"),
                            MmkvManager.decodeRoutingRulesets()!!.single().domain,
                        )
                        val catalog = loadRoutingNodeOptions(context)
                        assertTrue(catalog.any { it.id == mainId && it.group == context.getString(R.string.subscription_group_local) })
                        assertTrue(catalog.any { it.id == targetId && it.group == "Routing test subscription" })

                        val socksPort = Utils.findRandomFreePort()
                        var stop: (() -> Unit)? = null
                        var readStats: (() -> List<OutboundTrafficStat>)? = null
                        var bindings = emptyMap<String, NodeTrafficBinding>()
                        try {
                            if (singBox) {
                                val config = SingBoxConfigGenerator.generate(NodeProfile.from(primary, mainId),
                                    onTrafficBindings = { bindings = it })
                                val root = JsonUtil.parseString(config)!!.asJsonObject
                                root.getAsJsonArray("inbounds")[0].asJsonObject.addProperty("listen_port", socksPort)
                                // The null platform stub has no interface monitor; these proxies are loopback-only.
                                root.getAsJsonObject("route").addProperty("auto_detect_interface", false)
                                java.io.File(context.filesDir, "node-routing-singbox.json").writeText(root.toString())
                                Corebundle.checkSingBoxConfig(root.toString())
                                val controller = SingBoxCoreManager()
                                stop = { controller.stop() }
                                readStats = { controller.queryTrafficStats() }
                                controller.start(context, root.toString(), SingBoxPlatformInterface(
                                    context, protectSocket = { true }, openTunCallback = { error("No TUN in local proxy test") },
                                ))
                            } else {
                                val result = CoreConfigManager.getV2rayConfig(context, mainId)
                                assertTrue(result.errorMessage, result.status)
                                bindings = result.trafficBindings
                                val root = JsonUtil.parseString(result.content)!!.asJsonObject
                                root.add("inbounds", JsonParser.parseString(
                                    """[{"protocol":"socks","tag":"socks","listen":"127.0.0.1","port":$socksPort,"settings":{"auth":"noauth"}}]""",
                                ).asJsonArray)
                                java.io.File(context.filesDir, "node-routing-xray.json").writeText(root.toString())
                                val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
                                    override fun startup(): Long = 0
                                    override fun shutdown(): Long = 0
                                    override fun onEmitStatus(l: Long, s: String?): Long = 0
                                })
                                stop = { controller.stopLoop() }
                                readStats = {
                                    controller.queryAllOutboundTrafficStats().split(';').mapNotNull { entry ->
                                        val fields = entry.split(',', limit = 3)
                                        if (fields.size != 3) null else fields[2].toLongOrNull()?.let {
                                            OutboundTrafficStat(fields[0], fields[1], it)
                                        }
                                    }
                                }
                                controller.startLoop(root.toString(), 0)
                            }
                            listOf("claude.ai", "api.claude.ai", "anthropic.com").forEach { host ->
                                val response = requestThroughSocks(socksPort, host)
                                assertTrue("Assigned node must carry $host (sing-box=$singBox, selected=${selectedProxy.requests}, assigned=${assignedProxy.requests}): $response",
                                    response.contains("assigned"))
                            }
                            assertTrue("Unmatched service must follow selected node",
                                requestThroughSocks(socksPort, "other.example").contains("selected"))
                            assertEquals(3, assignedProxy.requests.get())
                            assertEquals(1, selectedProxy.requests.get())
                            val ledger = NodeTrafficLedger()
                            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
                            do {
                                ledger.record(readStats!!.invoke().map { stat ->
                                    stat.copy(profileIds = bindings[stat.tag]?.profileIds.orEmpty())
                                })
                                if ((ledger.snapshot()[mainId]?.downlink ?: 0L) > 0L &&
                                    (ledger.snapshot()[targetId]?.downlink ?: 0L) > 0L) break
                                android.os.SystemClock.sleep(100)
                            } while (android.os.SystemClock.uptimeMillis() < deadline)
                            assertTrue("Selected node traffic missing, sing-box=$singBox: ${ledger.snapshot()}",
                                (ledger.snapshot()[mainId]?.downlink ?: 0L) > 0L)
                            assertTrue("Assigned node traffic missing, sing-box=$singBox: ${ledger.snapshot()}",
                                (ledger.snapshot()[targetId]?.downlink ?: 0L) > 0L)
                        } finally {
                            stop?.invoke()
                        }
                    }
                }
            }
        } finally {
            MmkvManager.encodeRoutingRulesets(oldRules)
            MmkvManager.removeServer(mainId)
            MmkvManager.removeSubscription(subscriptionId)
            oldSelected?.let(MmkvManager::setSelectServer)
            settings.forEach { (key, value) -> MmkvManager.encodeSettings(key, value) }
        }
    }

    @Test
    fun subscriptionRefreshPreservesTheAssignedService() {
        val oldRules = MmkvManager.decodeRoutingRulesets()
        val oldSelected = MmkvManager.getSelectServer()
        val subscriptionId = UUID.randomUUID().toString()
        val link = "vless://11111111-1111-1111-1111-111111111111@192.0.2.2:443?security=none&type=tcp&encryption=none"
        try {
            MmkvManager.encodeSubscription(subscriptionId, SubscriptionItem(remarks = "Refresh test"))
            AngConfigManager.importBatchConfig("$link#US", subscriptionId, false)
            val oldId = MmkvManager.decodeServerList(subscriptionId).single()
            MmkvManager.encodeRoutingRulesets(mutableListOf(
                RulesetItem(outboundProfileId = oldId, outboundTag = "US", domain = listOf("domain:claude.ai")),
            ))
            AngConfigManager.importBatchConfig("$link#Renamed", subscriptionId, false)
            val newId = MmkvManager.decodeServerList(subscriptionId).single()
            assertNotEquals(oldId, newId)
            val rule = MmkvManager.decodeRoutingRulesets()!!.single()
            assertEquals(newId, rule.outboundProfileId)
            assertEquals("Renamed", rule.outboundTag)
            assertEquals(listOf("domain:claude.ai"), rule.domain)
        } finally {
            MmkvManager.encodeRoutingRulesets(oldRules)
            MmkvManager.removeSubscription(subscriptionId)
            oldSelected?.let(MmkvManager::setSelectServer)
        }
    }

    @Test
    fun singBoxAcceptsMixedProtocolTargetsWithHostnameResolvers() {
        val selected = NodeProfile.from(httpNode("Selected", 8080))
        val wireguard = selected.copy(
            protocol = EConfigType.WIREGUARD,
            server = "wg.example",
            port = 51820,
            wireGuardPrivateKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
            wireGuardPublicKey = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
            wireGuardLocalAddresses = listOf("10.0.0.2/32"),
        )
        val vless = selected.copy(
            protocol = EConfigType.VLESS,
            server = "vless.example",
            password = "11111111-1111-1111-1111-111111111111",
            network = "ws",
            path = "/proxy",
        )
        val targets = mapOf(
            "routing-profile-wg" to wireguard,
            "routing-profile-vless" to vless,
        )
        val config = SingBoxConfigGenerator.generate(
            profile = selected,
            socksPort = 10808,
            httpPort = 10809,
            logLevel = "warn",
            routingRules = targets.keys.map { tag ->
                RulesetItem(domain = listOf("domain:${tag}.example"), outboundTag = tag)
            },
            routingProfiles = targets,
        )
        Corebundle.checkSingBoxConfig(config)
    }

    private fun httpNode(name: String, port: Int) =
        ProfileItem.create(EConfigType.HTTP).copy(remarks = name, server = "127.0.0.1", serverPort = port.toString())

    private fun requestThroughSocks(port: Int, host: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 5_000
            val output = socket.getOutputStream()
            val input = java.io.DataInputStream(socket.getInputStream())
            output.write(byteArrayOf(5, 1, 0))
            output.flush()
            assertEquals(5, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            val domain = host.toByteArray(Charsets.US_ASCII)
            output.write(byteArrayOf(5, 1, 0, 3, domain.size.toByte()) + domain + byteArrayOf(0, 80))
            output.flush()
            assertEquals(5, input.readUnsignedByte())
            assertEquals("SOCKS connection must succeed for $host", 0, input.readUnsignedByte())
            input.readUnsignedByte()
            val addressLength = when (input.readUnsignedByte()) {
                1 -> 4
                3 -> input.readUnsignedByte()
                4 -> 16
                else -> error("Invalid SOCKS reply")
            }
            input.readFully(ByteArray(addressLength + 2))
            output.write("GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            return input.readBytes().toString(Charsets.UTF_8)
        }
    }

    private class RecordingHttpProxy(private val label: String) : AutoCloseable {
        private val server = ServerSocket(0)
        val port get() = server.localPort
        val requests = AtomicInteger()
        private val worker = thread(isDaemon = true, name = "routing-test-$label") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                socket.use {
                    it.soTimeout = 5_000
                    val reader = it.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val output = it.getOutputStream()
                    if (request.startsWith("CONNECT ")) {
                        output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                        output.flush()
                        reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { }
                    }
                    requests.incrementAndGet()
                    output.write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${label.length}\r\nConnection: close\r\n\r\n$label".toByteArray(),
                    )
                    output.flush()
                }
            }
        }

        override fun close() {
            server.close()
            worker.join(1_000)
        }
    }
}
