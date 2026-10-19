package com.v2ray.ang

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.SingBoxConfigGenerator
import com.v2ray.ang.core.SingBoxCoreManager
import com.v2ray.ang.core.SingBoxPlatformInterface
import com.v2ray.ang.core.anytls.AnyTlsBridges
import com.v2ray.ang.core.runtime.XrayOutboundBridges
import com.v2ray.ang.core.singbox.SingBoxEnvironment
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AnyTlsFmt
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.server.ServerAnyTlsActivity
import com.v2ray.ang.util.Utils
import libbox.CommandServer
import libbox.CommandServerHandler
import libbox.Libbox
import libbox.OverrideOptions
import libbox.SystemProxyStatus
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AnyTlsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val certificate get() = instrumentation.context.assets.open("anytls/test-cert.pem")
        .bufferedReader().use { it.readText() }
    private val key get() = instrumentation.context.assets.open("anytls/test-key.pem")
        .bufferedReader().use { it.readText() }

    @Test
    fun subscriptionImportOpensDedicatedEditorAndExportsLink() {
        val subscription = UUID.randomUUID().toString()
        var activity: ServerAnyTlsActivity? = null
        try {
            val result = AngConfigManager.importBatchConfig(
                "anytls://p%40ss%2Bword@relay.example:443?sni=tls.example#AnyTLS%20test",
                subscription, true,
            )
            assertEquals(1, result.configCount)
            val guid = MmkvManager.decodeServerList(subscription).single()
            val profile = MmkvManager.decodeServerConfig(guid)!!
            assertEquals(EConfigType.ANYTLS, profile.configType)
            assertEquals("p@ss+word", profile.password)
            assertEquals(profile.password, AnyTlsFmt.parse("anytls://${AnyTlsFmt.toUri(profile)}")!!.password)
            activity = instrumentation.startActivitySync(
                Intent(context, ServerAnyTlsActivity::class.java)
                    .putExtra("guid", guid).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as ServerAnyTlsActivity
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val directory = File(context.filesDir, "anytls-regression").apply { mkdirs() }
            File(directory, "editor.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            MmkvManager.removeServerViaSubid(subscription)
        }
    }

    @Test
    fun nativeAnyTlsCarriesTcpAndUdpWithVerifiedTls() {
        val remotePort = Utils.findRandomFreePort()
        nativeServer(remotePort).use {
            val local = localProfile(Utils.findRandomFreePort())
            nativeService(clientConfig(remotePort, local, trustTestCertificate = true)).use {
                tcpRoundTrip(local)
                udpRoundTrip(local)
            }
        }
    }

    @Test
    fun wrongPasswordAndUntrustedCertificateAreRejected() {
        val remotePort = Utils.findRandomFreePort()
        nativeServer(remotePort).use {
            for (wrongPassword in listOf(false, true)) {
                val local = localProfile(Utils.findRandomFreePort())
                val config = clientConfig(remotePort, local, trustTestCertificate = wrongPassword)
                if (wrongPassword) config.getAsJsonArray("outbounds")[0].asJsonObject
                    .addProperty("password", "incorrect")
                nativeService(config).use {
                    assertTrue("Invalid credentials or certificate were accepted",
                        runCatching { tcpRoundTrip(local) }.isFailure)
                }
            }
        }
    }

    @Test
    fun authenticatedBridgeReusesNodeAndClosesItsListener() {
        val remotePort = Utils.findRandomFreePort()
        nativeServer(remotePort).use {
            val bridges = AnyTlsBridges(context)
            val profile = anytlsProfile(remotePort)
            val local = try {
                val local = bridges.prepare(profile)
                assertEquals(local, bridges.prepare(profile.copy(remarks = "Other label")))
                tcpRoundTrip(local)
                udpRoundTrip(local)
                Socket(loopback, local.serverPort!!.toInt()).use { socket ->
                    socket.soTimeout = 2000
                    socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                    assertEquals(5, socket.getInputStream().read())
                    assertEquals(255, socket.getInputStream().read())
                }
                local
            } finally {
                bridges.close()
            }
            assertTrue(runCatching { Socket(loopback, local.serverPort!!.toInt()).close() }.isFailure)
        }
    }

    @Test
    fun xraySelectedAndAssignedAnyTlsNodesForwardActualPayloads() {
        val settings = listOf(
            AppConfig.PREF_ROOT_MODE_ENABLE, AppConfig.PREF_LOCAL_DNS_ENABLED,
            AppConfig.PREF_FAKE_DNS_ENABLED, AppConfig.PREF_BLOCK_GOOGLE_LOCATION_ENDPOINTS,
            AppConfig.PREF_BLOCK_GOOGLE_MAPS_SERVICES,
        )
        val previous = settings.associateWith { MmkvManager.decodeSettingsBool(it) }
        val rules = MmkvManager.decodeRoutingRulesets()
        val mainId = UUID.randomUUID().toString()
        val assignedId = UUID.randomUUID().toString()
        val remotePort = Utils.findRandomFreePort()
        try {
            settings.forEach { MmkvManager.encodeSettings(it, it == AppConfig.PREF_ROOT_MODE_ENABLE) }
            CoreNativeManager.initCoreEnv(context)
            nativeServer(remotePort).use {
                val valid = anytlsProfile(remotePort)
                MmkvManager.encodeServerConfig(assignedId, valid)
                for (assigned in listOf(false, true)) {
                    MmkvManager.encodeServerConfig(mainId, if (assigned) valid.copy(password = "wrong") else valid)
                    MmkvManager.encodeRoutingRulesets(if (assigned) mutableListOf(
                        RulesetItem(ip = listOf("127.0.0.1/32"), outboundProfileId = assignedId),
                    ) else mutableListOf())
                    XrayOutboundBridges(context).use { bridges ->
                        val result = CoreConfigManager.getV2rayConfig(context, mainId, bridges::prepare)
                        assertTrue(result.errorMessage, result.status)
                        assertEquals(EConfigType.ANYTLS, MmkvManager.decodeServerConfig(mainId)!!.configType)
                        val config = JsonParser.parseString(result.content).asJsonObject
                        val local = localProfile(Utils.findRandomFreePort())
                        config.add("inbounds", JsonParser.parseString(
                            """[{"protocol":"socks","tag":"socks","listen":"127.0.0.1","port":${local.serverPort},
                            "settings":{"auth":"password","accounts":[{"user":"${local.username}","pass":"${local.password}"}],"udp":true}}]""",
                        ))
                        val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
                            override fun startup() = 0L
                            override fun shutdown() = 0L
                            override fun onEmitStatus(l: Long, s: String?) = 0L
                        })
                        try {
                            controller.startLoop(config.toString(), 0)
                            tcpRoundTrip(local)
                            udpRoundTrip(local)
                            assertTrue(controller.queryAllOutboundTrafficStats().contains(",downlink,"))
                        } finally {
                            controller.stopLoop()
                        }
                    }
                }
            }
        } finally {
            MmkvManager.encodeRoutingRulesets(rules)
            settings.forEach { MmkvManager.encodeSettings(it, previous[it]!!) }
            MmkvManager.removeServer(mainId)
            MmkvManager.removeServer(assignedId)
        }
    }

    @Test
    fun bridgeDoesNotInterruptMainSingBoxOrItsTrafficSubscription() {
        val remotePort = Utils.findRandomFreePort()
        nativeServer(remotePort).use {
            val local = localProfile(Utils.findRandomFreePort())
            val main = SingBoxCoreManager()
            try {
                main.start(context, clientConfig(remotePort, local, true).toString(), platform())
                AnyTlsBridges(context).use { bridges ->
                    tcpRoundTrip(bridges.prepare(anytlsProfile(remotePort)))
                    tcpRoundTrip(local)
                    udpRoundTrip(local)
                    val deadline = SystemClock.elapsedRealtime() + 5000
                    var received = 0L
                    while (SystemClock.elapsedRealtime() < deadline && received == 0L) {
                        SystemClock.sleep(100)
                        received += main.queryTrafficStats().filter {
                            it.tag == AppConfig.TAG_PROXY && it.direction == "downlink"
                        }.sumOf { it.value }
                    }
                    assertTrue("Main traffic subscription was interrupted", received > 0)
                    assertTrue(main.isRunning())
                }
                tcpRoundTrip(local)
            } finally {
                main.stop()
            }
        }
    }

    private fun anytlsProfile(port: Int) =
        AnyTlsFmt.parse("anytls://secret@127.0.0.1:$port?sni=localhost&insecure=1#Local%20test")!!

    private fun localProfile(port: Int) = ProfileItem(
        configType = EConfigType.SOCKS, server = "127.0.0.1", serverPort = port.toString(),
        username = "test-user", password = "test-password",
    )

    private fun clientConfig(remotePort: Int, local: ProfileItem, trustTestCertificate: Boolean): JsonObject {
        val profile = NodeProfile.from(anytlsProfile(remotePort).copy(
            insecure = false, anytlsIdleSessionCheckInterval = "1s",
            anytlsIdleSessionTimeout = "2s", anytlsMinIdleSession = 1,
        ))
        return JsonParser.parseString(SingBoxConfigGenerator.generate(
            profile, socksPort = local.serverPort!!.toInt(), httpPort = local.serverPort!!.toInt(),
            logLevel = "error", socksUsername = local.username, socksPassword = local.password,
        )).asJsonObject.apply {
            if (trustTestCertificate) getAsJsonArray("outbounds")[0].asJsonObject
                .getAsJsonObject("tls").addProperty("certificate", certificate)
        }
    }

    private fun nativeServer(port: Int): Closeable = nativeService(JsonObject().apply {
        add("log", JsonObject().apply { addProperty("level", "error") })
        add("inbounds", JsonArray().apply { add(JsonObject().apply {
            addProperty("type", "anytls")
            addProperty("listen", "127.0.0.1")
            addProperty("listen_port", port)
            add("users", JsonParser.parseString("""[{"name":"test","password":"secret"}]"""))
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("certificate", certificate)
                addProperty("key", key)
            })
        }) })
        add("outbounds", JsonParser.parseString("""[{"type":"direct","tag":"direct"}]"""))
        add("route", JsonParser.parseString("""{"final":"direct"}"""))
    })

    private fun platform() = SingBoxPlatformInterface(context, { true }, { error("Unexpected TUN") })

    private fun nativeService(config: JsonObject): Closeable {
        SingBoxEnvironment.initialize(context)
        val cacheFile = File(context.cacheDir, "anytls-test-${UUID.randomUUID()}.db")
        config.add("experimental", JsonObject().apply {
            add("cache_file", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("path", cacheFile.absolutePath)
            })
        })
        Libbox.checkConfig(config.toString())
        val platform = platform()
        var server: CommandServer? = null
        try {
            server = Libbox.newCommandServer(object : CommandServerHandler {
                override fun serviceStop() = Unit
                override fun serviceReload() = Unit
                override fun getSystemProxyStatus(): SystemProxyStatus? = null
                override fun setSystemProxyEnabled(enabled: Boolean) = Unit
                override fun triggerNativeCrash() = Unit
                override fun writeDebugMessage(message: String?) = Unit
                override fun connectSSHAgent(): Int = -1
            }, platform)
            server.startOrReloadService(config.toString(), OverrideOptions())
            val started = server
            return Closeable {
                try {
                    started.closeService()
                } finally {
                    started.close()
                    platform.close()
                    cacheFile.delete()
                }
            }
        } catch (error: Exception) {
            server?.close()
            platform.close()
            cacheFile.delete()
            throw error
        }
    }

    private fun tcpRoundTrip(profile: ProfileItem) {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, loopback).use { target ->
            target.soTimeout = 5000
            val expected = "Freedom AnyTLS TCP payload".toByteArray()
            val echo = executor.submit {
                target.accept().use { socket ->
                    socket.soTimeout = 5000
                    val payload = ByteArray(expected.size)
                    DataInputStream(socket.getInputStream()).readFully(payload)
                    assertArrayEquals(expected, payload)
                    socket.getOutputStream().write(payload.reversedArray())
                }
            }
            try {
                socks(profile).use { socket ->
                    socket.getOutputStream().write(request(1, target.localPort))
                    assertEquals(0, readReply(socket).second)
                    socket.getOutputStream().write(expected)
                    val received = ByteArray(expected.size)
                    DataInputStream(socket.getInputStream()).readFully(received)
                    assertArrayEquals(expected.reversedArray(), received)
                }
                echo.get(6, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun udpRoundTrip(profile: ProfileItem) {
        val executor = Executors.newSingleThreadExecutor()
        DatagramSocket(0, loopback).use { target ->
            target.soTimeout = 5000
            val expected = "Freedom AnyTLS UDP payload".toByteArray()
            val echo = executor.submit {
                val packet = DatagramPacket(ByteArray(2048), 2048)
                target.receive(packet)
                assertArrayEquals(expected, packet.data.copyOf(packet.length))
                target.send(DatagramPacket(expected, expected.size, packet.socketAddress))
            }
            try {
                socks(profile).use { socket ->
                    socket.getOutputStream().write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
                    val (relay, code) = readReply(socket)
                    assertEquals(0, code)
                    DatagramSocket(0, loopback).use { udp ->
                        udp.soTimeout = 5000
                        val bytes = byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1,
                            (target.localPort shr 8).toByte(), target.localPort.toByte()) + expected
                        udp.send(DatagramPacket(bytes, bytes.size, loopback, relay.port))
                        val response = DatagramPacket(ByteArray(2048), 2048)
                        udp.receive(response)
                        assertArrayEquals(expected, response.data.copyOfRange(10, response.length))
                    }
                }
                echo.get(6, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun socks(profile: ProfileItem): Socket = Socket().apply {
        connect(InetSocketAddress(loopback, profile.serverPort!!.toInt()), 1000)
        soTimeout = 5000
        val output = getOutputStream()
        val input = DataInputStream(getInputStream())
        output.write(byteArrayOf(5, 1, 2))
        assertEquals(5, input.readUnsignedByte())
        assertEquals(2, input.readUnsignedByte())
        val user = profile.username!!.toByteArray()
        val pass = profile.password!!.toByteArray()
        output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
        assertEquals(1, input.readUnsignedByte())
        assertEquals(0, input.readUnsignedByte())
    }

    private fun request(command: Int, port: Int) =
        byteArrayOf(5, command.toByte(), 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte())

    private fun readReply(socket: Socket): Pair<InetSocketAddress, Int> {
        val input = DataInputStream(socket.getInputStream())
        assertEquals(5, input.readUnsignedByte())
        val code = input.readUnsignedByte()
        input.readUnsignedByte()
        val length = when (input.readUnsignedByte()) {
            1 -> 4
            4 -> 16
            3 -> input.readUnsignedByte()
            else -> error("Invalid SOCKS address")
        }
        input.readFully(ByteArray(length))
        return InetSocketAddress(loopback, input.readUnsignedShort()) to code
    }
}
