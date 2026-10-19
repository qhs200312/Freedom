package com.v2ray.ang

import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.RuntimeDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class AppRoutingVpnInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val probe = "com.freedom.routingprobe"
    private val defaultProbe = "com.freedom.routingdefaultprobe"

    @Test
    fun actualVpnRoutesOneAppToItsNodeAndAnotherAppToTheSelectedNodeInBothCores() {
        assertNull("Grant the isolated test application VPN permission before this test", VpnService.prepare(context))
        val oldRules = MmkvManager.decodeRoutingRulesets()
        val oldSelected = MmkvManager.getSelectServer()
        val mainId = UUID.randomUUID().toString()
        val targetId = UUID.randomUUID().toString()
        val bools = listOf(
            AppConfig.PREF_ROOT_MODE_ENABLE, AppConfig.PREF_PER_APP_PROXY, AppConfig.PREF_BYPASS_APPS,
            AppConfig.PREF_ROUTE_ONLY_ENABLED, AppConfig.PREF_LOCAL_DNS_ENABLED,
            AppConfig.PREF_BLOCK_GOOGLE_LOCATION_ENDPOINTS, AppConfig.PREF_BLOCK_GOOGLE_MAPS_SERVICES,
        ).associateWith { MmkvManager.decodeSettingsBool(it) }
        val oldMode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE)
        val oldApps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            bools.keys.forEach { MmkvManager.encodeSettings(it, it == AppConfig.PREF_PER_APP_PROXY) }
            MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, mutableSetOf(probe, defaultProbe))
            for (core in listOf("XRAY", "SING_BOX")) {
                RecordingProxy("selected").use { selected ->
                    RecordingProxy("assigned").use { assigned ->
                        MmkvManager.encodeServerConfig(mainId, httpNode(selected.port).copy(preferredCore = core))
                        MmkvManager.encodeServerConfig(targetId, httpNode(assigned.port))
                        MmkvManager.setSelectServer(mainId)
                        MmkvManager.encodeRoutingRulesets(mutableListOf(
                            // The conflicting domain rule comes first in storage; the application wins.
                            RulesetItem(domain = listOf("full:routing-probe.invalid"), outboundTag = "proxy"),
                            RulesetItem(process = listOf(probe), outboundProfileId = targetId, outboundTag = "Assigned"),
                            RulesetItem(process = listOf("com.freedom.app.that.is.not.installed"), outboundTag = "block"),
                        ))
                        if (core == "XRAY") {
                            val config = CoreConfigManager.getV2rayConfig(context, mainId)
                            assertTrue(config.errorMessage, config.status)
                            val root = JsonUtil.parseString(config.content)!!.asJsonObject
                            val appRules = root.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }.filter { it.has("process") }
                            assertEquals("An uninstalled application's rule must be omitted", 1, appRules.size)
                            val uid = context.packageManager.getPackageUid(probe, 0).toString()
                            assertEquals(uid, appRules.single().getAsJsonArray("process")[0].asString)
                            assertTrue(root.getAsJsonArray("inbounds").first { it.asJsonObject.get("tag").asString == "tun" }
                                .asJsonObject.getAsJsonObject("sniffing").get("routeOnly").asBoolean)
                        }
                        RuntimeDiagnostics.clear(context)
                        assertTrue(LauncherManager.startService(context))
                        await("VPN ready for $core") {
                            RuntimeDiagnostics.read(context).any { it.contains("vpn_ready core=$core") }
                        }
                        try {
                            val response = shell("am instrument -w $probe/.RoutingProbe")
                            assertTrue("Assigned app in $core: $response", response.contains("assigned"))
                            val defaultResponse = shell("am instrument -w $defaultProbe/com.freedom.routingprobe.RoutingProbe")
                            assertTrue("Unmatched application in $core: $defaultResponse", defaultResponse.contains("selected"))
                            assertEquals("Assigned requests in $core", 1, assigned.requests.get())
                            assertEquals("Selected requests in $core", 1, selected.requests.get())
                        } finally {
                            LauncherManager.stopService(context)
                            await("VPN stopped") { shell("dumpsys activity services ${context.packageName}").contains("CoreVpnService").not() }
                        }
                    }
                }
            }
        } finally {
            LauncherManager.stopService(context)
            MmkvManager.encodeRoutingRulesets(oldRules)
            MmkvManager.removeServer(mainId)
            MmkvManager.removeServer(targetId)
            oldSelected?.let(MmkvManager::setSelectServer)
            bools.forEach { (key, value) -> MmkvManager.encodeSettings(key, value) }
            MmkvManager.encodeSettings(AppConfig.PREF_MODE, oldMode)
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, oldApps ?: mutableSetOf())
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun httpNode(port: Int) = ProfileItem.create(EConfigType.HTTP)
        .copy(remarks = "App routing test", server = "127.0.0.1", serverPort = port.toString())

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(120)
        }
        fail("$message; diagnostics: ${RuntimeDiagnostics.read(context).take(8)}")
    }

    private class RecordingProxy(private val label: String) : AutoCloseable {
        private val server = ServerSocket(0)
        val port get() = server.localPort
        val requests = AtomicInteger()
        private val worker = thread(isDaemon = true, name = "app-routing-proxy-$label") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                try { socket.use {
                    it.soTimeout = 5_000
                    val reader = it.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    if (request.startsWith("CONNECT ")) {
                        // Ignore the application's independent HTTPS connectivity checks.
                        if (!request.contains("198.18.0.10:80") && !request.contains("routing-probe.invalid:80")) {
                            return@use
                        }
                        it.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                        it.getOutputStream().flush()
                        reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { }
                    }
                    requests.incrementAndGet()
                    it.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${label.length}\r\nConnection: close\r\n\r\n$label".toByteArray(),
                    )
                    it.getOutputStream().flush()
                } } catch (_: java.io.IOException) { }
            }
        }

        override fun close() {
            server.close()
            worker.join(1_000)
        }
    }
}
