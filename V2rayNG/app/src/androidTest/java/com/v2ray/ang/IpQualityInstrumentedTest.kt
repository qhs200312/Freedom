package com.v2ray.ang

import android.content.Intent
import android.graphics.Rect
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.ipquality.IpQuality
import com.v2ray.ang.ipquality.IpQualityCacheEntry
import com.v2ray.ang.ipquality.IpQualityPanel
import com.v2ray.ang.ipquality.IpQualityPlatform
import com.v2ray.ang.ipquality.IpQualityRepository
import com.v2ray.ang.ipquality.IpQualitySource
import com.v2ray.ang.ipquality.IpQualityStore
import com.v2ray.ang.ipquality.IpType
import com.v2ray.ang.dto.GeoLocation
import com.v2ray.ang.ui.main.HomeDashboard
import com.v2ray.ang.ui.main.MainUiState
import com.v2ray.ang.ui.settings.SettingsActivity
import com.v2ray.ang.ui.compose.AppTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class IpQualityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun mockQualityDetailsSupportPrivacyAndCacheAcrossThemeChanges() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val calls = AtomicInteger()
        val useProxy = mutableStateOf(false)
        val store = object : IpQualityStore {
            var value: IpQualityCacheEntry? = null
            override fun read(ip: String) = value
            override fun write(ip: String, entry: IpQualityCacheEntry) { value = entry }
        }
        val repository = IpQualityRepository({
            calls.incrementAndGet()
            IpQuality(
                it, IpQualitySource.ipApiIs, IpType.mobile, "Example Mobile", "AS64500",
                isProxy = true, isVpn = true,
            )
        }, store)
        val hiddenBefore = IpQualityPlatform.hidden.value
        val activity = instrumentation.startActivitySync(
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as SettingsActivity
        try {
            IpQualityPlatform.setHidden(false)
            listOf(false to 1f, true to 1.6f).forEach { (dark, scale) ->
                instrumentation.runOnMainSync {
                    activity.setContent {
                        AppTheme(darkTheme = dark) {
                            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                                Surface(Modifier.fillMaxSize()) {
                                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                                        IpQualityPanel("203.0.113.8", useProxy.value, repository)
                                    }
                                }
                            }
                        }
                    }
                }
                waitForText(activity.getString(R.string.ip_quality_title))
                assertTrue("Query must finish", waitUntil { store.value != null })
                clickText(activity.getString(R.string.ip_quality_title))
                waitForText(activity.getString(R.string.ip_quality_organization, "Example Mobile"))
                assertTrue("Query action must remain visible outside the scrolling content",
                    nodesWithText(activity.getString(R.string.ip_quality_query)).any { it.isVisibleToUser })
                screenshot(if (dark) "quality-dark-large-text.png" else "quality-light.png")
                if (!dark) {
                    IpQualityPlatform.setHidden(true)
                    assertTrue("Hidden IP must replace the result and mask the editable field", waitUntil {
                        nodesWithText("*****").any { it.text?.toString() == "*****" } &&
                            nodesWithText("203.0.113.8").none { !it.isPassword }
                    })
                    screenshot("quality-hidden.png")
                    IpQualityPlatform.setHidden(false)
                    clickText(activity.getString(R.string.ip_quality_query))
                    assertTrue("Manual refresh must bypass the successful cache", waitUntil { calls.get() == 2 })
                    waitForText(activity.getString(R.string.ip_quality_organization, "Example Mobile"))
                    instrumentation.runOnMainSync { useProxy.value = true }
                    instrumentation.waitForIdleSync()
                    waitForText(activity.getString(R.string.ip_quality_organization, "Example Mobile"))
                }
                clickText(activity.getString(android.R.string.ok))
            }
            assertEquals("Theme/privacy/proxy changes must not repeat a consumed manual refresh", 2, calls.get())
        } finally {
            IpQualityPlatform.setHidden(hiddenBefore)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun inlineQualityKeepsTheExitIdentityAndDashboardDropsTheLiveChart() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val exit = "203.0.113.123"
        val custom = "198.51.100.8"
        val entries = mutableMapOf<String, IpQualityCacheEntry>()
        val repository = IpQualityRepository({ ip ->
            IpQuality(
                ip, IpQualitySource.ipApiIs,
                if (ip == exit) IpType.residential else IpType.hosting,
                organization = if (ip == exit) "Example Home" else "Custom Hosting",
            )
        }, object : IpQualityStore {
            override fun read(ip: String) = entries[ip]
            override fun write(ip: String, entry: IpQualityCacheEntry) { entries[ip] = entry }
        })
        val hiddenBefore = IpQualityPlatform.hidden.value
        val activity = instrumentation.startActivitySync(
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as SettingsActivity
        try {
            IpQualityPlatform.setHidden(false)
            for (dark in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    activity.setContent {
                        AppTheme(darkTheme = dark) {
                            Surface(Modifier.fillMaxSize()) {
                                HomeDashboard(
                                    uiState = MainUiState(
                                        geoLocation = GeoLocation(exit, country = "Example", region = "Home"),
                                        exitIpLatencyMs = 42,
                                    ),
                                    onAction = {},
                                    ipQualityRepositoryOverride = repository,
                                )
                            }
                        }
                    }
                }
                reveal(activity.getString(R.string.home_active_mode))
                waitForText(activity.getString(R.string.ip_quality_residential))
                screenshot(if (dark) "inline-home-dark.png" else "inline-home-light.png")
                fun bounds(text: String): Rect = Rect().also { rect ->
                    nodesWithText(text).last { it.isVisibleToUser }.getBoundsInScreen(rect)
                }
                val address = bounds(activity.getString(R.string.home_ipv4_value, exit))
                val grade = bounds(activity.getString(R.string.ip_quality_good))
                val type = bounds(activity.getString(R.string.ip_quality_residential))
                assertTrue("Quality must sit beside the IP address", grade.left >= address.right)
                assertTrue("Type must follow the quality grade, or share its merged accessibility node",
                    type.left >= grade.right || type == grade)
                assertTrue("Grade and address must share a row", grade.centerY() in address.top..address.bottom)
                assertTrue("Exit IP must precede runtime status",
                    bounds(activity.getString(R.string.home_exit_ip)).top <
                        bounds(activity.getString(R.string.home_runtime_status)).top)
                assertTrue("Live chart title must be absent", nodesWithText(activity.getString(R.string.home_live_speed)).isEmpty())
                assertTrue("No duplicate quality heading on the card",
                    nodesWithText(activity.getString(R.string.ip_quality_title)).none { it.text?.toString() == activity.getString(R.string.ip_quality_title) })

                clickText(activity.getString(R.string.ip_quality_title))
                waitForText(activity.getString(R.string.ip_quality_organization, "Example Home"))
                val editor = allNodes().first { it.isEditable }
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, custom)
                }))
                clickText(activity.getString(R.string.ip_quality_query))
                waitForText(activity.getString(R.string.ip_quality_organization, "Custom Hosting"))
                clickText(activity.getString(android.R.string.ok))
                waitForText(activity.getString(R.string.ip_quality_residential))
                assertTrue("A custom lookup must not relabel the exit IP",
                    nodesWithText(activity.getString(R.string.ip_quality_hosting)).isEmpty())
            }
        } finally {
            IpQualityPlatform.setHidden(hiddenBefore)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun reveal(text: String) {
        repeat(8) {
            if (nodesWithText(text).any { it.isVisibleToUser }) return
            val scrolled = allNodes().any {
                it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } &&
                    it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            if (!scrolled) {
                val screen = context.resources.displayMetrics
                val x = screen.widthPixels / 2
                val command = "input swipe $x ${screen.heightPixels * 4 / 5} $x ${screen.heightPixels / 3} 250"
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command),
                ).use { it.readBytes() }
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(200)
        }
        assertTrue("Missing visible dashboard text: $text", nodesWithText(text).any { it.isVisibleToUser })
    }

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(25)
        }
        return false
    }
    private fun waitForText(text: String) {
        val found = waitUntil {
            nodesWithText(text).isNotEmpty()
        }
        if (!found) {
            screenshot("quality-ui-failure.png")
            fun labels(node: AccessibilityNodeInfo?, depth: Int = 0): List<String> {
                if (node == null || depth > 16) return emptyList()
                return listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
                    (0 until node.childCount).flatMap { labels(node.getChild(it), depth + 1) }
            }
            println("VISIBLE LABELS: ${labels(instrumentation.uiAutomation.rootInActiveWindow)}")
        }
        assertTrue("Missing UI text: $text", found)
    }
    private fun nodesWithText(text: String): List<AccessibilityNodeInfo> {
        return allNodes().filter { it.text?.toString()?.contains(text) == true ||
            it.contentDescription?.toString()?.contains(text) == true }
    }
    private fun allNodes(): List<AccessibilityNodeInfo> {
        val automation = instrumentation.uiAutomation
        val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
        fun descend(node: AccessibilityNodeInfo?, depth: Int = 0): List<AccessibilityNodeInfo> {
            if (node == null || depth > 24) return emptyList()
            return listOf(node) + (0 until node.childCount).flatMap { descend(node.getChild(it), depth + 1) }
        }
        return roots.flatMap { descend(it) }
    }
    private fun clickText(text: String) {
        val nodes = nodesWithText(text)
        var node: AccessibilityNodeInfo? = nodes.firstOrNull()
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Cannot click $text", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val directory = File(context.filesDir, "ip-quality-screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
