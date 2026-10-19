package com.v2ray.ang

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.apppicker.AppPickerActivity
import com.v2ray.ang.ui.routing.RoutingEditActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppRoutingUiInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun pickingAnInstalledApplicationAndALocalNodeSavesAnAppOnlyRule() {
        val oldRules = MmkvManager.decodeRoutingRulesets()
        val oldLanguage = MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE)
        val targetId = UUID.randomUUID().toString()
        val nodeName = "应用分流测试节点"
        MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, "zh-rCN")
        MmkvManager.encodeServerConfig(targetId, ProfileItem.create(EConfigType.HTTP).copy(
            remarks = nodeName, server = "127.0.0.1", serverPort = "8081",
        ))
        val monitor = instrumentation.addMonitor(AppPickerActivity::class.java.name, null, false)
        val editor = instrumentation.startActivitySync(
            Intent(context, RoutingEditActivity::class.java)
                .putExtra("app_rule", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        var picker: Activity? = null
        try {
            click("选择应用")
            picker = monitor.waitForActivityWithTimeout(5_000)
            assertNotNull("Installed application picker opened", picker)
            click("搜索")
            await { editable() != null }
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Routing Test Probe")
            }
            assertTrue(editable()!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            await(timeoutMillis = 30_000) { find("com.freedom.routingprobe") != null }
            click("com.freedom.routingprobe")
            await { findNode(root()) { it.isCheckable && it.isChecked } != null }
            screenshot("app-picker.png")
            instrumentation.runOnMainSync { picker!!.finish() }
            await { find("分流节点") != null }
            await { find("Routing Test Probe") != null }
            click("分流节点")
            await { find("取消") != null }
            click(nodeName)
            await { find("取消") == null && find("保存配置") != null }
            screenshot("app-node-rule.png")
            click("保存配置")
            await { MmkvManager.decodeRoutingRulesets().orEmpty().any { it.outboundProfileId == targetId } }
            val rule = MmkvManager.decodeRoutingRulesets()!!.single { it.outboundProfileId == targetId }
            assertEquals(listOf("com.freedom.routingprobe"), rule.process)
            assertNull(rule.domain)
            assertNull(rule.ip)
            assertNull(rule.network)
            assertEquals("Routing Test Probe", rule.remarks)
        } finally {
            instrumentation.runOnMainSync { picker?.finish(); editor.finish() }
            instrumentation.removeMonitor(monitor)
            MmkvManager.encodeRoutingRulesets(oldRules)
            MmkvManager.removeServer(targetId)
            MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, oldLanguage)
        }
    }

    private fun root(): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }

    private fun find(text: String): AccessibilityNodeInfo? =
        findNode(root()) { it.text?.toString()?.contains(text) == true || it.contentDescription?.toString() == text }

    private fun editable(): AccessibilityNodeInfo? = findNode(root()) { it.isEditable }

    private fun findNode(node: AccessibilityNodeInfo?, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (matches(node)) return node
        for (index in 0 until node.childCount) findNode(node.getChild(index), matches)?.let { return it }
        return null
    }

    private fun click(text: String) {
        instrumentation.waitForIdleSync()
        await { find(text) != null }
        var node = find(text)
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("Clickable $text", node)
        assertTrue("Clicked $text", node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun await(timeoutMillis: Long = 8_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        screenshot("ui-failure.png")
        assertTrue("UI state did not settle", condition())
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = File(context.filesDir, "app-routing-results/$name")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
