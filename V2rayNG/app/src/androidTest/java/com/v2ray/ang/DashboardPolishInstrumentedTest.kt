package com.v2ray.ang

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.GeoLocation
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.ui.compose.AppTheme
import com.v2ray.ang.ui.main.HomeDashboard
import com.v2ray.ang.ui.main.MainAction
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.ui.main.MainViewModel
import com.v2ray.ang.ui.main.MainUiState
import com.v2ray.ang.util.QRCodeDecoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class DashboardPolishInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val output = File(context.filesDir, "dashboard-three-cards-regression").apply { mkdirs() }

    @Test
    fun mapAndCardsScrollTogetherUnderTransparentSystemBars() {
        val guid = UUID.randomUUID().toString()
        val previousSelected = MmkvManager.getSelectServer()
        val previousTheme = MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0").orEmpty()
        val previousLanguage = MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE, "auto").orEmpty()
        val language = InstrumentationRegistry.getArguments().getString("dashboardLanguage", previousLanguage)
        MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, language)
        val profile = ProfileItem(
            configType = EConfigType.HYSTERIA2, remarks = "Dashboard layout fixture",
            server = "192.0.2.1", serverPort = "443",
        )
        MmkvManager.encodeServerConfig(guid, profile)
        MmkvManager.encodeServerTestDelayMillis(guid, 37L)
        MmkvManager.setSelectServer(guid)
        var activity: MainActivity? = null
        try {
            for (dark in listOf(false, true)) {
                instrumentation.runOnMainSync { ThemeManager.setThemeMode(if (dark) "2" else "1") }
                activity = launch()
                lateinit var model: MainViewModel
                instrumentation.runOnMainSync { model = ViewModelProvider(activity!!)[MainViewModel::class.java] }
                await { model.uiState.value.selectedServerProtocol == "HYSTERIA2" }
                assertEquals(37L, model.uiState.value.selectedServerDelayMillis)
                await { !model.uiState.value.isLocating }
                repeat(2) { swipeDown() }
                await { findText(activity!!.getString(R.string.home_not_connected)) != null }
                SystemClock.sleep(1600L)
                assertEquals(0L, model.uiState.value.traffic.sessionTotal)
                val name = "${activity!!.resources.configuration.screenWidthDp}-$language-${if (dark) "dark" else "light"}"
                assertSystemBars(activity!!, dark)
                val initialStatusBounds = Rect()
                findText(activity!!.getString(R.string.home_not_connected))!!.getBoundsInScreen(initialStatusBounds)
                val before = instrumentation.uiAutomation.takeScreenshot()
                screenshot("$name-top.png")
                val headings = listOf(R.string.home_traffic_information, R.string.home_runtime_status,
                    R.string.home_exit_ip).map(activity!!::getString)
                val seenHeadings = headings.filter { findText(it) != null }.toMutableSet()
                val configuration = activity!!.resources.configuration
                val scrollPasses = if (configuration.screenWidthDp > configuration.screenHeightDp) 6 else 3
                repeat(scrollPasses) { index ->
                    swipeUp()
                    seenHeadings.addAll(headings.filter { findText(it) != null })
                    assertSystemBars(activity!!, dark)
                    if (index == 0) screenshot("$name-middle.png")
                }
                val after = instrumentation.uiAutomation.takeScreenshot()
                assertStatusBarBackground(activity!!, before, after, unchanged = false)
                after.recycle()
                assertEquals("Every information card should be reachable", headings.toSet(), seenHeadings)
                assertNull(findText("Current node"))
                assertNull(findText("当前节点"))
                assertNull(findText("Dashboard layout fixture"))
                val exitBounds = Rect()
                val navigationBounds = Rect()
                findText(activity!!.getString(R.string.home_exit_latency))!!.getBoundsInScreen(exitBounds)
                findText(activity!!.getString(R.string.main_nav_dashboard))!!.getBoundsInScreen(navigationBounds)
                assertTrue("Exit information is hidden by navigation", exitBounds.bottom < navigationBounds.top)
                screenshot("$name-bottom.png")
                repeat(scrollPasses) { swipeDown() }
                SystemClock.sleep(1600L)
                assertSystemBars(activity!!, dark)
                val returnedStatusBounds = Rect()
                findText(activity!!.getString(R.string.home_not_connected))!!.getBoundsInScreen(returnedStatusBounds)
                assertTrue("The header did not return to its original position",
                    kotlin.math.abs(initialStatusBounds.top - returnedStatusBounds.top) <= 2)
                val returned = instrumentation.uiAutomation.takeScreenshot()
                assertStatusBarBackground(activity!!, before, returned, unchanged = true)
                returned.recycle()
                before.recycle()
                click(activity!!.getString(R.string.main_nav_nodes))
                await { findText("filter") != null &&
                    findText("Dashboard layout fixture") != null }
                screenshot("$name-selected-node.png")
                close(activity!!)
                activity = null
            }
        } finally {
            activity?.let(::close)
            MmkvManager.removeServer(guid)
            previousSelected?.let(MmkvManager::setSelectServer)
            MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, previousLanguage)
            instrumentation.runOnMainSync { ThemeManager.setThemeMode(previousTheme) }
        }
    }

    @Test
    fun longIpv6AndUnavailableStatesRemainReadableAndRefreshKeepsItsAction() {
        val activity = launch()
        // Documentation-only address: never part of production state or used for a request.
        val address = "2001:0db8:1234:5678:90ab:cdef:1234:5678"
        val state = mutableStateOf(MainUiState(geoLocation = GeoLocation(ip = address)))
        val actions = mutableListOf<MainAction>()
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    AppTheme(darkTheme = true) {
                        HomeDashboard(
                            uiState = state.value,
                            onAction = { actions += it },
                            mapContentTopPadding = WindowInsets.statusBars.union(WindowInsets.displayCutout)
                                .asPaddingValues().calculateTopPadding() + 16.dp,
                            contentBottomPadding = 110.dp,
                        )
                    }
                }
            }
            await { findText(activity.getString(R.string.home_connect)) != null }
            click(activity.getString(R.string.home_connect))
            assertEquals(listOf(MainAction.ToggleService), actions)
            repeat(3) { swipeUp() }
            val ipText = activity.getString(R.string.home_ipv6_value, address)
            await { findText(ipText) != null }
            val ipBounds = Rect()
            findText(ipText)!!.getBoundsInScreen(ipBounds)
            val metrics = context.resources.displayMetrics
            assertTrue("IPv6 exceeds the card's left margin", ipBounds.left >= 16 * metrics.density)
            assertTrue("IPv6 exceeds the card's right margin",
                ipBounds.right <= metrics.widthPixels - 16 * metrics.density)
            screenshot("${activity.resources.configuration.screenWidthDp}-ipv6-dark.png")
            click(activity.getString(R.string.home_refresh_exit_ip))
            assertEquals(MainAction.RefreshExitLocation, actions.last())
            instrumentation.runOnMainSync { state.value = MainUiState(isLocating = true) }
            repeat(2) { swipeUp() }
            screenshot("${activity.resources.configuration.screenWidthDp}-querying-dark.png")
            await {
                findText(activity.getString(R.string.home_locating)) != null &&
                    findText(activity.getString(R.string.home_refresh_exit_ip)) == null
            }
            instrumentation.runOnMainSync { state.value = MainUiState(exitIpFailed = true) }
            repeat(2) { swipeUp() }
            screenshot("${activity.resources.configuration.screenWidthDp}-failure-before-assertion.png")
            await { findText(activity.getString(R.string.home_exit_connection_failed)) != null }
            screenshot("${activity.resources.configuration.screenWidthDp}-unavailable-dark.png")
        } finally {
            close(activity)
        }
    }

    @Test
    fun scannedHttpSubscriptionRequiresConsentAndImportsAfterConfirmation() {
        val requests = AtomicInteger()
        val profileLink = "hysteria2://qr-test-password@192.0.2.123:443?sni=test.example&insecure=0#QR%20Import%20Fixture"
        val body = android.util.Base64.encodeToString(profileLink.toByteArray(), android.util.Base64.NO_WRAP)
        val previousSelected = MmkvManager.getSelectServer()
        val previousSubscriptions = MmkvManager.decodeSubsList().toSet()
        ServerSocket(0).use { server ->
            val fixtureUrl = "http://localhost:${server.localPort}/sub"
            val worker = thread(isDaemon = true, name = "subscription-qr-fixture") {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                    socket.use {
                        it.soTimeout = 5_000
                        val reader = it.getInputStream().bufferedReader()
                        reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        requests.incrementAndGet()
                        it.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(),
                        )
                        it.getOutputStream().flush()
                    }
                }
            }
            val bitmap = QRCodeDecoder.createQRCode(fixtureUrl)!!
            val scanned = QRCodeDecoder.syncDecodeQRCode(bitmap)
            bitmap.recycle()
            assertEquals(fixtureUrl, scanned)
            val activity = launch()
            lateinit var model: MainViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(activity)[MainViewModel::class.java] }
            try {
                instrumentation.runOnMainSync { model.onAction(MainAction.ImportBatchConfig(scanned!!)) }
                await { findText(activity.getString(R.string.subscription_http_import_title)) != null }
                assertEquals(0, requests.get())
                screenshot("http-import-consent.png")
                click(activity.getString(android.R.string.cancel))
                await { model.uiState.value.pendingHttpImport == null }
                assertEquals(0, requests.get())
                assertFalse(MmkvManager.decodeSubscriptions().any { it.subscription.url == fixtureUrl })

                instrumentation.runOnMainSync { model.onAction(MainAction.ImportBatchConfig(scanned!!)) }
                click(activity.getString(R.string.subscription_http_import_confirm))
                await {
                    MmkvManager.decodeSubscriptions().any {
                        it.subscription.url == fixtureUrl && MmkvManager.decodeServerList(it.guid).isNotEmpty()
                    }
                }
                val subscription = MmkvManager.decodeSubscriptions().single { it.subscription.url == fixtureUrl }
                assertTrue(subscription.subscription.allowInsecureUrl)
                val imported = MmkvManager.decodeServerList(subscription.guid)
                assertEquals(1, imported.size)
                assertEquals("QR Import Fixture", MmkvManager.decodeServerConfig(imported.single())?.remarks)
                assertEquals(EConfigType.HYSTERIA2, MmkvManager.decodeServerConfig(imported.single())?.configType)
                assertTrue(requests.get() > 0)
                screenshot("http-import-success.png")
            } finally {
                close(activity)
                MmkvManager.decodeSubsList().filter { it !in previousSubscriptions }.forEach(MmkvManager::removeSubscription)
                previousSelected?.let(MmkvManager::setSelectServer)
                server.close()
                worker.join(1000L)
            }
        }
    }

    private fun launch(): MainActivity = instrumentation.startActivitySync(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) as MainActivity

    private fun close(activity: MainActivity) = instrumentation.runOnMainSync {
        activity.setContent {}
        activity.finish()
    }

    private fun findText(text: String): AccessibilityNodeInfo? {
        val automation = instrumentation.uiAutomation
        // Compose can redraw before UiAutomation invalidates its cached text nodes.
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        else automation.serviceInfo = automation.serviceInfo
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            repeat(node.childCount) { visit(node.getChild(it))?.let { match -> return match } }
            return null
        }
        return visit(automation.rootInActiveWindow)
    }

    private fun click(text: String) {
        await { findText(text) != null }
        var node = findText(text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Cannot click $text", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun swipeUp() {
        val metrics = context.resources.displayMetrics
        swipe((metrics.heightPixels * 0.75).toInt(), (metrics.heightPixels * 0.38).toInt())
    }

    private fun swipeDown() {
        val metrics = context.resources.displayMetrics
        swipe((metrics.heightPixels * 0.38).toInt(), (metrics.heightPixels * 0.75).toInt())
    }

    private fun swipe(from: Int, to: Int) {
        val x = context.resources.displayMetrics.widthPixels / 2
        instrumentation.uiAutomation.executeShellCommand("input swipe $x $from $x $to 400").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        SystemClock.sleep(450)
    }

    @Suppress("DEPRECATION")
    private fun assertSystemBars(activity: MainActivity, dark: Boolean) {
        instrumentation.runOnMainSync {
            assertEquals(android.graphics.Color.TRANSPARENT, activity.window.statusBarColor)
            assertEquals(!dark, WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .isAppearanceLightStatusBars)
            if (Build.VERSION.SDK_INT >= 29) assertFalse(activity.window.isStatusBarContrastEnforced)
            assertTrue(ViewCompat.getRootWindowInsets(activity.window.decorView)!!
                .isVisible(WindowInsetsCompat.Type.statusBars()))
        }
    }

    private fun assertStatusBarBackground(
        activity: MainActivity,
        before: Bitmap,
        after: Bitmap,
        unchanged: Boolean,
    ) {
        var statusHeight = 0
        instrumentation.runOnMainSync {
            statusHeight = ViewCompat.getRootWindowInsets(activity.window.decorView)!!
                .getInsets(WindowInsetsCompat.Type.statusBars()).top
        }
        assertTrue(statusHeight > 0)
        var differentPixels = 0
        var comparedPixels = 0
        // Exclude system icons; a scrolled-away map must no longer occupy this strip.
        for (x in before.width * 4 / 10 until before.width * 6 / 10 step 3) {
            for (y in 2 until statusHeight - 2 step 3) {
                if (before.getPixel(x, y) != after.getPixel(x, y)) differentPixels++
                comparedPixels++
            }
        }
        // Lazy recreation can rasterize boundary pixels differently after returning to the top.
        if (unchanged) assertTrue("Returning to the top did not restore the map",
            differentPixels < comparedPixels * 0.05)
        else assertTrue("The map is still fixed behind the status bar", differentPixels > 10)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= deadline) {
                screenshot("timeout.png")
                val text = StringBuilder()
                fun visit(node: AccessibilityNodeInfo?) {
                    node ?: return
                    text.appendLine("${node.text} | ${node.contentDescription}")
                    repeat(node.childCount) { visit(node.getChild(it)) }
                }
                visit(instrumentation.uiAutomation.rootInActiveWindow)
                File(output, "timeout-tree.txt").writeText(text.toString())
                fail("Dashboard did not reach expected state")
            }
            SystemClock.sleep(50)
        }
    }

    private fun screenshot(name: String) {
        SystemClock.sleep(350)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
