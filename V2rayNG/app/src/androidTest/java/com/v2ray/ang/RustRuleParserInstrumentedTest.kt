package com.v2ray.ang

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.routing.ClashYamlRules
import com.v2ray.ang.routing.RoutingRuleLimits
import com.v2ray.ang.routing.RustRuleParser
import com.v2ray.ang.routing.YamlImportFailure
import com.v2ray.ang.routing.YamlRuleImportException
import com.v2ray.ang.ui.routing.RoutingEditActivity
import com.v2ray.ang.ui.routing.RoutingSettingsViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class RustRuleParserInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun fiftyThousandRulesParseSaveAndOpenWithoutBlockingTheMainLooper() {
        val oldRules = MmkvManager.decodeRoutingRulesets()
        val file = fixture(50_000)
        val handler = Handler(Looper.getMainLooper())
        val active = AtomicBoolean(true)
        val ticks = AtomicInteger()
        val maxGap = AtomicLong()
        var lastTick = SystemClock.uptimeMillis()
        val pulse = object : Runnable {
            override fun run() {
                if (!active.get()) return
                val now = SystemClock.uptimeMillis()
                maxGap.updateAndGet { maxOf(it, now - lastTick) }
                lastTick = now
                ticks.incrementAndGet()
                handler.postDelayed(this, 16)
            }
        }
        var model: RoutingSettingsViewModel? = null
        var activity: RoutingEditActivity? = null
        try {
            handler.post(pulse)
            MmkvManager.encodeRoutingRulesets(mutableListOf())
            instrumentation.runOnMainSync {
                model = RoutingSettingsViewModel(context.applicationContext as Application)
                model!!.openYamlImport()
                model!!.loadYamlFile(Uri.fromFile(file))
            }
            val started = SystemClock.uptimeMillis()
            await { model!!.yamlImport.value.parsed != null || model!!.yamlImport.value.error != null }
            assertNull(model!!.yamlImport.value.error)
            assertEquals(50_000, model!!.yamlImport.value.parsed!!.count)
            val parsedMs = SystemClock.uptimeMillis() - started
            instrumentation.runOnMainSync { model!!.saveYamlImport("direct", null) }
            await { !model!!.yamlImport.value.visible }
            val saved = MmkvManager.decodeRoutingRulesets()!!.single()
            assertEquals(50_000, saved.domain!!.size)
            assertFalse(RoutingRuleLimits.canEdit(saved.domain))
            assertTrue(RoutingRuleLimits.summary(saved.domain!!).length < 400)
            activity = instrumentation.startActivitySync(
                Intent(context, RoutingEditActivity::class.java)
                    .putExtra("position", 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as RoutingEditActivity
            val expected = activity.getString(R.string.routing_yaml_large_editor)
            await { hasText(instrumentation.uiAutomation.rootInActiveWindow, expected) }
            assertTrue("Main looper stalled for ${maxGap.get()} ms", maxGap.get() < 1_500)
            assertTrue("Main looper did not receive progress pulses", ticks.get() >= 2)
            File(context.getExternalFilesDir(null), "rust-rules-performance.txt").writeText(
                "rules=50000\nparse_ms=$parsedMs\nmain_max_gap_ms=${maxGap.get()}\nmain_ticks=${ticks.get()}\nlarge_editor=summary\n",
            )
        } finally {
            active.set(false)
            handler.removeCallbacks(pulse)
            instrumentation.runOnMainSync { activity?.finish(); model?.closeYamlImport() }
            file.delete()
            MmkvManager.encodeRoutingRulesets(oldRules)
        }
    }

    @Test
    fun cancellingNativeWorkAndRejectingOversizedPastesLeavesStorageUntouched() = runBlocking {
        val oldRules = MmkvManager.decodeRoutingRulesets()
        val file = fixture(50_000)
        try {
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                RustRuleParser.parseFile(file) { true }
            }
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            lateinit var model: RoutingSettingsViewModel
            instrumentation.runOnMainSync {
                model = RoutingSettingsViewModel(context.applicationContext as Application)
                model.openYamlImport()
                model.updateYamlInput("payload:\n- DOMAIN,example.com")
                model.previewYaml()
            }
            await { model.yamlImport.value.parsed != null }
            instrumentation.runOnMainSync {
                model.updateYamlInput("x".repeat(ClashYamlRules.MAX_PASTE_CHARS + 1))
            }
            assertEquals(YamlImportFailure.PASTE_TOO_LARGE, model.yamlImport.value.error!!.failure)
            assertNull(model.yamlImport.value.parsed)
            assertTrue(model.yamlImport.value.input.length < ClashYamlRules.MAX_PASTE_CHARS)
            assertEquals(oldRules, MmkvManager.decodeRoutingRulesets())
            instrumentation.runOnMainSync { model.closeYamlImport() }
        } finally { file.delete() }
    }

    @Test
    fun overlongLineIsRejectedByRustWithItsSourceLineNumber() {
        val error = assertThrows(YamlRuleImportException::class.java) {
            ClashYamlRules.parse("payload:\n- DOMAIN-KEYWORD," + "x".repeat(17 * 1024))
        }
        assertEquals(YamlImportFailure.TOO_LARGE, error.failure)
        assertEquals(2, error.line)
    }

    private fun fixture(count: Int): File = File(context.cacheDir, "rust-rules-$count.yaml").apply {
        bufferedWriter().use { writer ->
            writer.appendLine("payload:")
            repeat(count) { writer.appendLine("  - DOMAIN-SUFFIX,d$it.example") }
        }
    }

    private fun hasText(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.toString() == text) return true
        return (0 until node.childCount).any { hasText(node.getChild(it), text) }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for Rust import", condition())
    }
}
