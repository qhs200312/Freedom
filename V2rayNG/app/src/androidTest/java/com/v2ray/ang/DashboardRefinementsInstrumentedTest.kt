package com.v2ray.ang

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.dto.NodeTrafficTotal
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.service.RealPingWorkerService
import com.v2ray.ang.ui.compose.AppTheme
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.ui.main.MainViewModel
import com.v2ray.ang.ui.main.ServerListItem
import com.v2ray.ang.ui.main.SpeedHistoryChart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DashboardRefinementsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun chartAndTrafficBadgesFitWithLargeTextAndBothSystemBarThemes() {
        val scale = shell("settings get global animator_duration_scale").trim()
        shell("settings put global animator_duration_scale 0")
        val activity = launch()
        try {
            for (dark in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    activity.setContent {
                        AppTheme(darkTheme = dark) {
                            val density = LocalDensity.current
                            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                                Column(Modifier.fillMaxSize()
                                    .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                                    .statusBarsPadding().padding(12.dp).width(320.dp)) {
                                    ServerListItem(
                                        remarks = "UDP node", statistics = "127.0.0.1:443", typeDescription = "HYSTERIA2",
                                        testResult = "Ping 23 ms", testDelayMillis = 23, isSelected = true,
                                        subscriptionRemarks = "Local", doubleColumnDisplay = true,
                                        onClick = {}, onEdit = {}, onShare = {}, onRemove = {}, onMore = {},
                                        modifier = Modifier.width(320.dp),
                                        traffic = NodeTrafficTotal(1024 * 1024, 10 * 1024 * 1024),
                                    )
                                    SpeedHistoryChart(2048, 1_000_000, Modifier.width(320.dp))
                                }
                            }
                        }
                    }
                }
                await { find { it.text?.toString()?.contains("11.0") == true } != null }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(500)
                instrumentation.runOnMainSync {
                    assertEquals(!dark, WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                        .isAppearanceLightStatusBars)
                    if (Build.VERSION.SDK_INT >= 29) assertFalse(activity.window.isStatusBarContrastEnforced)
                }
                screenshot(if (dark) "dark-large-text.png" else "light-large-text.png")
            }
        } finally {
            close(activity)
            shell(if (scale == "null") "settings delete global animator_duration_scale"
                else "settings put global animator_duration_scale $scale")
        }
    }

    @Test
    fun udpOnlyNodeCanBePingedWithoutAListeningTcpPort() {
        val guid = UUID.randomUUID().toString()
        val done = CountDownLatch(1)
        var result: RealPingEvent.Result? = null
        MmkvManager.encodeServerConfig(guid, ProfileItem(
            configType = EConfigType.HYSTERIA2, server = "127.0.0.1", serverPort = "9", remarks = "UDP ping fixture",
        ))
        val worker = RealPingWorkerService(context, listOf(guid), pingOnly = true) {
            if (it is RealPingEvent.Result) { result = it; done.countDown() }
        }
        try {
            worker.start()
            assertTrue("UDP ping did not finish", done.await(8, TimeUnit.SECONDS))
            assertEquals("ping", result?.method)
            assertTrue("UDP-only node was incorrectly rejected: $result", (result?.delayMillis ?: -1) > 0)
        } finally {
            worker.cancel()
            MmkvManager.removeServer(guid)
        }
    }

    @Test
    fun exportAndDeleteOperateOnlyOnTheSelectedNodeAndAllExportOpensFilePicker() {
        val selected = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val oldSelected = MmkvManager.getSelectServer()
        val oldAll = MmkvManager.decodeSettingsBool(AppConfig.PREF_GROUP_ALL_DISPLAY)
        MmkvManager.encodeSettings(AppConfig.PREF_GROUP_ALL_DISPLAY, true)
        MmkvManager.encodeServerConfig(selected, ProfileItem(
            configType = EConfigType.VLESS, remarks = "Selected fixture", server = "192.0.2.1", serverPort = "443",
            password = "11111111-1111-1111-1111-111111111111", network = "tcp", security = "none",
        ))
        MmkvManager.encodeServerConfig(other, ProfileItem(
            configType = EConfigType.VLESS, remarks = "Other fixture", server = "192.0.2.2", serverPort = "443",
            password = "22222222-2222-2222-2222-222222222222", network = "tcp", security = "none",
        ))
        MmkvManager.setSelectServer(selected)
        val activity = launch()
        try {
            lateinit var viewModel: MainViewModel
            instrumentation.runOnMainSync {
                viewModel = ViewModelProvider(activity)[MainViewModel::class.java]
            }
            await { viewModel.uiState.value.groups.isNotEmpty() }
            instrumentation.runOnMainSync { viewModel.triggerLocateSelectedServer() }
            await { find { it.text?.toString() == "Selected fixture" } != null }
            click("More")
            click(activity.getString(R.string.title_export_selected_profile))
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            var exported = ""
            instrumentation.runOnMainSync { exported = clipboard.primaryClip?.getItemAt(0)?.text.toString() }
            assertTrue(exported, exported.contains("192.0.2.1"))
            assertFalse(exported, exported.contains("192.0.2.2"))
            click("More")
            click(activity.getString(R.string.title_export_all_profiles))
            await { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            screenshot("all-config-backup-picker.png")
            shell("input keyevent 4")
            shell("input keyevent 4")
            instrumentation.runOnMainSync {
                activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            await { find { it.text?.toString() == "Selected fixture" } != null }
            click("More")
            click(activity.getString(R.string.title_delete_selected_profile))
            click(activity.getString(R.string.action_delete))
            await { MmkvManager.decodeServerConfig(selected) == null }
            assertNotNull(MmkvManager.decodeServerConfig(other))
            await { find { it.text?.toString() == "Selected fixture" } == null }
            screenshot("after-selected-delete.png")
        } finally {
            close(activity)
            MmkvManager.removeServer(selected)
            MmkvManager.removeServer(other)
            oldSelected?.let(MmkvManager::setSelectServer)
            MmkvManager.encodeSettings(AppConfig.PREF_GROUP_ALL_DISPLAY, oldAll)
        }
    }

    private fun launch() = instrumentation.startActivitySync(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) as MainActivity

    private fun close(activity: MainActivity) = instrumentation.runOnMainSync {
        activity.setContent {}
        activity.finish()
    }

    private fun click(text: String) {
        await { find { it.text?.toString() == text || it.contentDescription?.toString() == text } != null }
        var node = find { it.text?.toString() == text || it.contentDescription?.toString() == text }
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Cannot click $text", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (predicate(node)) return node
            repeat(node.childCount) { visit(node.getChild(it))?.let { match -> return match } }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            assertTrue("UI did not reach expected state", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(50)
        }
    }

    private fun screenshot(name: String) {
        val dir = File(context.filesDir, "refinements-regression").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
    }
}
