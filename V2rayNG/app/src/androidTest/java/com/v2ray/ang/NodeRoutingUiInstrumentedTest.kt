package com.v2ray.ang

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.compose.AppTheme
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.ui.routing.RoutingEditScreen
import com.v2ray.ang.ui.routing.RoutingNodeOption
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NodeRoutingUiInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun searchableNodePickerSavesAirportAndLocalIdsAndClearsBindingsForDefault() {
        val oldLanguage = MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE)
        MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, "zh-rCN")
        val nodes = listOf(
            RoutingNodeOption("airport", "美国节点", "测试机场", "192.0.2.1:443"),
            RoutingNodeOption("local", "美国节点", "本地节点", "192.0.2.2:443"),
        )
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val uiContext = activity
        try {
            for (dark in listOf(false, true)) {
                var saved: RulesetItem? = null
                instrumentation.runOnMainSync {
                    activity.setContent {
                        key(dark) {
                            CompositionLocalProvider(
                                LocalActivityResultRegistryOwner provides activity,
                            ) {
                                AppTheme(darkTheme = dark) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                        Box(Modifier.width(360.dp).fillMaxHeight()) {
                                            RoutingEditScreen(
                                                position = -1,
                                                initial = RulesetItem(remarks = "Claude", domain = listOf("claude.ai", "anthropic.com"), outboundTag = "proxy"),
                                                nodeOptions = nodes,
                                                canUseProcess = false,
                                                onBackClick = {},
                                                onSave = { saved = it; true },
                                                onDelete = {},
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val prefix = if (dark) "dark" else "light"
                openPicker(uiContext.getString(R.string.routing_target_title))
                await { findEditable() != null }
                screenshot("$prefix-picker.png")
                setSearch("本地")
                await { findText("本地节点") != null }
                screenshot("$prefix-local-search.png")
                click("美国节点")
                await { findText(uiContext.getString(R.string.menu_item_save_config)) != null }
                click(uiContext.getString(R.string.menu_item_save_config))
                await { saved != null }
                assertEquals("local", saved!!.outboundProfileId)
                assertEquals(listOf("domain:claude.ai", "domain:anthropic.com"), saved!!.domain)
                screenshot("$prefix-local-assigned.png")

                openPicker(uiContext.getString(R.string.routing_target_title))
                setSearch("测试机场")
                await { findText("测试机场") != null }
                click("美国节点")
                saved = null
                click(uiContext.getString(R.string.menu_item_save_config))
                await { saved != null }
                assertEquals("airport", saved!!.outboundProfileId)

                openPicker(uiContext.getString(R.string.routing_target_title))
                click(uiContext.getString(R.string.routing_target_current))
                saved = null
                click(uiContext.getString(R.string.menu_item_save_config))
                await { saved != null }
                assertNull(saved!!.outboundProfileId)
                assertEquals("proxy", saved!!.outboundTag)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, oldLanguage)
        }
    }

    private fun openPicker(title: String) {
        click(title)
        await { findText("取消") != null }
    }

    private fun setSearch(value: String) {
        await { findEditable() != null }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        assertTrue(findEditable()!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        instrumentation.waitForIdleSync()
    }

    private fun click(text: String) {
        instrumentation.waitForIdleSync()
        await { findText(text) != null }
        var node = findText(text)
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("Clickable accessibility node for $text", node)
        if (!node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            // Compose may invalidate a virtual accessibility node during recomposition.
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            assertFalse("Visible click bounds for $text", bounds.isEmpty)
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0,
                ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try {
                    assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                } finally {
                    event.recycle()
                }
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun findText(text: String): AccessibilityNodeInfo? =
        find(activeRoot()) {
            it.text?.toString()?.contains(text) == true || it.contentDescription?.toString() == text
        }

    private fun findEditable(): AccessibilityNodeInfo? =
        find(activeRoot()) { it.isEditable }

    private fun activeRoot(): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }

    private fun find(node: AccessibilityNodeInfo?, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (matches(node)) return node
        for (index in 0 until node.childCount) {
            find(node.getChild(index), matches)?.let { return it }
        }
        return null
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(80)
        }
        screenshot("failed-state.png")
        val hierarchy = StringBuilder()
        fun dump(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            hierarchy.appendLine("${" ".repeat(depth)}${node.className} text=${node.text} description=${node.contentDescription} editable=${node.isEditable} clickable=${node.isClickable} bounds=$bounds")
            for (index in 0 until node.childCount) dump(node.getChild(index), depth + 1)
        }
        dump(instrumentation.uiAutomation.rootInActiveWindow, 0)
        File(context.filesDir, "node-routing-results/failed-hierarchy.txt").writeText(hierarchy.toString())
        assertTrue("UI state did not settle", condition())
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = File(context.filesDir, "node-routing-results/$name")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
