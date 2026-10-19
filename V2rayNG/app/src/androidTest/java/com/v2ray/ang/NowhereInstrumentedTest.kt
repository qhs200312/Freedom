package com.v2ray.ang

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import com.v2ray.ang.ui.server.ServerNowhereActivity
import com.v2ray.ang.core.nowhere.NowhereBridges
import com.v2ray.ang.core.nowhere.NowhereEndpoint
import com.v2ray.ang.core.nowhere.NowhereProcess
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.fmt.NowhereFmt
import com.v2ray.ang.util.JsonUtil
import com.google.gson.JsonParser
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NowhereInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = "0123456789abcdef0123456789abcdef"
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun importedNodeOpensInTheNowhereEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val guid = UUID.randomUUID().toString()
        var activity: ServerNowhereActivity? = null
        try {
            MmkvManager.encodeServerConfig(guid, NowhereFmt.parse(
                NowhereEndpoint(
                    "relay.example", 2000, key, carrier = "udp", pin = "a".repeat(64), name = "Nowhere test",
                ).shareLink(),
            )!!)
            activity = instrumentation.startActivitySync(
                Intent(context, ServerNowhereActivity::class.java)
                    .putExtra("guid", guid).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as ServerNowhereActivity
            instrumentation.waitForIdleSync()
            SystemClock.sleep(600)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(screenshot)
            val directory = File(context.filesDir, "nowhere-regression").apply { mkdirs() }
            File(directory, "editor.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            assertEquals(EConfigType.NOWHERE, MmkvManager.decodeServerConfig(guid)?.configType)
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            MmkvManager.removeServer(guid)
        }
    }

    @Test
    fun xrayUsesNowhereForTheSelectedNodeAndARoutingTarget() {
        val oldRules = MmkvManager.decodeRoutingRulesets()
        val mainId = UUID.randomUUID().toString()
        val assignedId = UUID.randomUUID().toString()
        val settingKeys = listOf(
            AppConfig.PREF_ROOT_MODE_ENABLE, AppConfig.PREF_LOCAL_DNS_ENABLED,
            AppConfig.PREF_FAKE_DNS_ENABLED, AppConfig.PREF_BLOCK_GOOGLE_LOCATION_ENDPOINTS,
            AppConfig.PREF_BLOCK_GOOGLE_MAPS_SERVICES,
        )
        val oldSettings = settingKeys.associateWith { MmkvManager.decodeSettingsBool(it) }
        val portalPort = freePort()
        try {
            settingKeys.forEach { MmkvManager.encodeSettings(it, it == AppConfig.PREF_ROOT_MODE_ENABLE) }
            CoreNativeManager.initCoreEnv(context)
            NowhereProcess(context).use { portal ->
                portal.start("portal://$key@127.0.0.1:$portalPort?morph=1", portalPort)
                val pin = fingerprint(portalPort, true)
                val selected = NowhereFmt.parse(
                    NowhereEndpoint("127.0.0.1", portalPort, key, pin = pin, name = "Selected").shareLink(),
                )!!
                val assigned = selected.copy(network = "udp", remarks = "Assigned")
                MmkvManager.encodeServerConfig(mainId, selected)
                MmkvManager.encodeServerConfig(assignedId, assigned)
                for (useAssigned in listOf(false, true)) {
                    MmkvManager.encodeRoutingRulesets(if (useAssigned) mutableListOf(
                        RulesetItem(
                            ip = listOf("127.0.0.1/32"), outboundTag = "Assigned", outboundProfileId = assignedId,
                        ),
                    ) else mutableListOf())
                    NowhereBridges(context).use { bridges ->
                        val result = CoreConfigManager.getV2rayConfig(context, mainId, bridges::prepare)
                        assertTrue(result.errorMessage, result.status)
                        assertEquals(EConfigType.NOWHERE, MmkvManager.decodeServerConfig(mainId)?.configType)
                        val json = JsonUtil.parseString(result.content)!!.asJsonObject
                        val proxyOutbounds = json.getAsJsonArray("outbounds").filter {
                            it.asJsonObject.get("protocol")?.asString == "socks"
                        }
                        assertEquals(if (useAssigned) 2 else 1, proxyOutbounds.size)
                        val inboundPort = freePort()
                        json.add("inbounds", JsonParser.parseString(
                            """[{"protocol":"socks","tag":"socks","listen":"127.0.0.1","port":$inboundPort,
                                "settings":{"auth":"password","accounts":[{"user":"test","pass":"password"}],"udp":true}}]""",
                        ))
                        val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
                            override fun startup() = 0L
                            override fun shutdown() = 0L
                            override fun onEmitStatus(l: Long, s: String?) = 0L
                        })
                        try {
                            controller.startLoop(json.toString(), 0)
                            tcpRoundTrip(ProfileItem(
                                configType = EConfigType.SOCKS, server = "127.0.0.1",
                                serverPort = inboundPort.toString(), username = "test", password = "password",
                            ))
                            assertTrue(controller.queryAllOutboundTrafficStats().contains(",downlink,"))
                        } finally {
                            controller.stopLoop()
                        }
                    }
                }
            }
        } finally {
            MmkvManager.encodeRoutingRulesets(oldRules)
            MmkvManager.removeServer(mainId)
            MmkvManager.removeServer(assignedId)
            oldSettings.forEach { (key, value) -> MmkvManager.encodeSettings(key, value) }
        }
    }

    @Test
    fun tlsAndQuicCarryTcpAndUdpWithMorphOnAndOff() {
        for (morph in listOf(false, true)) {
            val portalPort = freePort()
            NowhereProcess(context).use { portal ->
                portal.start("portal://$key@127.0.0.1:$portalPort?morph=${if (morph) 1 else 0}", portalPort)
                val pin = fingerprint(portalPort, morph)
                for (carrier in listOf("tcp", "udp")) {
                    val endpoint = NowhereEndpoint("127.0.0.1", portalPort, key, carrier, morph, pin = pin)
                    NowhereBridges(context).use { bridges ->
                        val profile = ProfileItem(
                            configType = EConfigType.NOWHERE,
                            server = endpoint.host, serverPort = endpoint.port.toString(),
                            password = endpoint.key, network = carrier, pinnedCA256 = pin, nowhereMorph = morph,
                        )
                        val local = bridges.prepare(profile)
                        assertEquals(EConfigType.NOWHERE, profile.configType)
                        assertEquals(EConfigType.SOCKS, local.configType)
                        assertEquals(local, bridges.prepare(profile.copy(remarks = "same endpoint")))
                        tcpRoundTrip(local)
                        udpRoundTrip(local)
                        assertTrue(bridges.isAlive)
                    }
                }
            }
        }
    }

    @Test
    fun untrustedCertificateAndWrongSharedKeyCannotForwardTraffic() {
        val portalPort = freePort()
        NowhereProcess(context).use { portal ->
            portal.start("portal://$key@127.0.0.1:$portalPort?morph=0", portalPort)
            val pin = fingerprint(portalPort, false)
            for ((candidateKey, candidatePin) in listOf(key to "0".repeat(64), "f".repeat(32) to pin)) {
                val endpoint = NowhereEndpoint("127.0.0.1", portalPort, candidateKey, morph = false, pin = candidatePin)
                val localPort = freePort()
                NowhereProcess(context).use { vector ->
                    vector.start(endpoint.vectorUrl(localPort, "test", "password"), localPort)
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(loopback, localPort), 1000)
                        socket.soTimeout = 7000
                        authenticate(socket, "test", "password")
                        socket.getOutputStream().write(request(1, portalPort))
                        val result = runCatching { readReply(socket) }
                        assertTrue("Untrusted peers must fail closed", result.isFailure || result.getOrNull()?.second != 0)
                    }
                }
            }
        }
    }

    private fun fingerprint(port: Int, morph: Boolean): String {
        val process = NowhereProcess.command(
            context, "fingerprint", "nowhere://$key@127.0.0.1:$port?morph=${if (morph) 1 else 0}",
        ).redirectErrorStream(true).start()
        try {
            assertTrue("Local certificate query timed out", process.waitFor(8, TimeUnit.SECONDS))
            val result = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(result, 0, process.exitValue())
            return Regex("(?m)^[0-9a-f]{64}$").find(result)?.value
                ?: error("Expected one local certificate fingerprint")
        } finally {
            process.destroy()
        }
    }

    private fun tcpRoundTrip(profile: ProfileItem) {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, loopback).use { target ->
            target.soTimeout = 8000
            val expected = "Freedom Nowhere TCP payload".toByteArray()
            val echo = executor.submit {
                target.accept().use { socket ->
                    socket.soTimeout = 8000
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
                echo.get(8, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun udpRoundTrip(profile: ProfileItem) {
        val executor = Executors.newSingleThreadExecutor()
        DatagramSocket(0, loopback).use { target ->
            target.soTimeout = 8000
            val expected = "Freedom Nowhere UDP payload".toByteArray()
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
                        udp.soTimeout = 8000
                        val header = byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1,
                            (target.localPort shr 8).toByte(), target.localPort.toByte())
                        val bytes = header + expected
                        udp.send(DatagramPacket(bytes, bytes.size, loopback, relay.port))
                        val response = DatagramPacket(ByteArray(2048), 2048)
                        udp.receive(response)
                        assertArrayEquals(expected, response.data.copyOfRange(10, response.length))
                    }
                }
                echo.get(8, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun socks(profile: ProfileItem): Socket = Socket().apply {
        connect(InetSocketAddress(loopback, profile.serverPort!!.toInt()), 1000)
        soTimeout = 8000
        authenticate(this, profile.username!!, profile.password!!)
    }

    private fun authenticate(socket: Socket, username: String, password: String) {
        val output = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())
        output.write(byteArrayOf(5, 1, 2))
        assertEquals(5, input.readUnsignedByte())
        assertEquals(2, input.readUnsignedByte())
        val user = username.toByteArray()
        val pass = password.toByteArray()
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
        val address = ByteArray(length)
        input.readFully(address)
        val port = input.readUnsignedShort()
        return InetSocketAddress(loopback, port) to code
    }

    private fun freePort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }
}
