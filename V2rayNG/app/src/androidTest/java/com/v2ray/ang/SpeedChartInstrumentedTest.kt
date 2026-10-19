package com.v2ray.ang

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.ui.compose.AppTheme
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.ui.main.SpeedChartMotion
import com.v2ray.ang.ui.main.SpeedChartWindowSeconds
import com.v2ray.ang.ui.main.SpeedChartAxisWidth
import com.v2ray.ang.ui.main.SpeedHistoryChart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SpeedChartInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val output = File(context.filesDir, "speed-chart-regression").apply { mkdirs() }
    @Volatile private var bounds = Rect.Zero

    @Test
    fun renderedCrestsKeepMovingAcrossSamplesInLightAndDarkLayouts() {
        val oldScale = shell("settings get global animator_duration_scale").trim()
        shell("settings put global animator_duration_scale 1")
        var activity: MainActivity? = null
        try {
            activity = launch()
            val measurements = StringBuilder()
            for ((width, dark) in listOf(360 to false, 520 to true)) {
                val motion = seededMotion()
                val owner = FixtureOwner()
                show(activity, motion, owner, width, dark)
                await { bounds.width > 0 && endTime(motion) > motion.samples.last().timeNanos - 3_000_000_000L }
                SystemClock.sleep(900)
                val positions = mutableListOf<Capture>()
                repeat(7) { index ->
                    val started = SystemClock.elapsedRealtimeNanos()
                    val bitmap = instrumentation.uiAutomation.takeScreenshot()
                    val completed = SystemClock.elapsedRealtimeNanos()
                    positions += Capture(started, completed, greenCrestX(bitmap))
                    File(output, "motion-$width-$index.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                    if (index < 6) SystemClock.sleep(600)
                }
                val graphWidth = bounds.width - SpeedChartAxisWidth.value * context.resources.displayMetrics.density
                positions.zipWithNext().forEach { (before, after) ->
                    // Screenshot acquisition is asynchronous; the visible frame can fall anywhere in this interval.
                    val minimumSeconds = (after.started - before.completed) / 1_000_000_000.0
                    val maximumSeconds = (after.completed - before.started) / 1_000_000_000.0
                    val minimum = graphWidth * minimumSeconds / SpeedChartWindowSeconds
                    val maximum = graphWidth * maximumSeconds / SpeedChartWindowSeconds
                    val actual = before.x - after.x
                    measurements.appendLine("$width dark=$dark expected=$minimum..$maximum actual=$actual")
                    File(output, "rendered-motion.txt").writeText(measurements.toString())
                    assertTrue("Crest stopped or moved backwards: $actual", actual > 0.35)
                    assertTrue("Crest jumped instead of scrolling: expected=$minimum..$maximum actual=$actual",
                        actual in (minimum - 1.8)..(maximum + 1.8))
                }
                File(output, "rendered-motion.txt").writeText(measurements.toString())
            }
        } finally {
            close(activity)
            restoreScale(oldScale)
        }
    }

    @Test
    fun inactiveLifecycleStopsFrameUpdatesAndResumesAtCurrentTime() {
        val activity = launch()
        try {
            val motion = seededMotion()
            val owner = FixtureOwner()
            show(activity, motion, owner, 360, false)
            await { bounds.width > 0 }
            SystemClock.sleep(250)
            instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.CREATED }
            val paused = endTime(motion)
            SystemClock.sleep(600)
            assertEquals(paused, endTime(motion))
            instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.RESUMED }
            await { endTime(motion) > paused + 500_000_000L }
        } finally {
            close(activity)
        }
    }

    @Test
    fun disablingSystemAnimationsStopsContinuousChartMotion() {
        val oldScale = shell("settings get global animator_duration_scale").trim()
        shell("settings put global animator_duration_scale 0")
        var activity: MainActivity? = null
        try {
            activity = launch()
            val motion = seededMotion()
            val owner = FixtureOwner()
            show(activity, motion, owner, 360, false)
            val lastSample = motion.samples.last().timeNanos
            await { endTime(motion) > lastSample }
            val before = endTime(motion)
            SystemClock.sleep(600)
            assertEquals(before, endTime(motion))
        } finally {
            close(activity)
            restoreScale(oldScale)
        }
    }

    private fun seededMotion(): SpeedChartMotion {
        val now = System.nanoTime()
        val start = now - 150_000_000_000L
        val motion = SpeedChartMotion(initialTimeNanos = start)
        for (index in 1..50) {
            val download = when (index) {
                28, 32 -> 160_000L
                29, 31 -> 640_000L
                30 -> 1_000_000L
                else -> 20_000L
            }
            val upload = when (index) {
                34, 38 -> 60_000L
                35, 37 -> 180_000L
                36 -> 350_000L
                else -> 10_000L
            }
            motion.advance(start + index * 3_000_000_000L, upload, download, animate = false)
        }
        return motion
    }

    private fun launch(): MainActivity {
        // The instrumentation launcher waits for idle; mount the animated fixture after that wait.
        val previousScale = shell("settings get global animator_duration_scale").trim()
        shell("settings put global animator_duration_scale 0")
        try {
            val activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            instrumentation.runOnMainSync { activity.setContent {} }
            return activity
        } finally {
            restoreScale(previousScale)
        }
    }

    private fun close(activity: MainActivity?) {
        instrumentation.runOnMainSync {
            activity?.setContent {}
            activity?.finish()
        }
    }

    private fun show(
        activity: MainActivity, motion: SpeedChartMotion, owner: FixtureOwner, width: Int, dark: Boolean,
    ) {
        bounds = Rect.Zero
        instrumentation.runOnMainSync {
            owner.registry.currentState = Lifecycle.State.RESUMED
            activity.setContent {
                AppTheme(darkTheme = dark) {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        Box(
                            Modifier.fillMaxSize().background(if (dark) Color(0xFF161B1D) else Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.width(width.dp)) {
                                SpeedHistoryChart(
                                    uploadSpeed = 10_000, downloadSpeed = 20_000, motion = motion,
                                    modifier = Modifier.onGloballyPositioned { bounds = it.boundsInWindow() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun greenCrestX(bitmap: Bitmap): Double {
        val density = context.resources.displayMetrics.density
        val left = (bounds.left + SpeedChartAxisWidth.value * density).toInt().coerceAtLeast(0)
        val right = bounds.right.toInt().coerceAtMost(bitmap.width)
        val top = bounds.top.toInt().coerceAtLeast(0)
        val bottom = (bounds.top + 70 * density).toInt().coerceAtMost(bitmap.height)
        var weightedX = 0.0
        var total = 0.0
        for (y in top until bottom) {
            for (x in left until right) {
                val pixel = bitmap.getPixel(x, y)
                if (PixelColor.green(pixel) > PixelColor.red(pixel) + 45 &&
                    PixelColor.green(pixel) > PixelColor.blue(pixel) + 20) {
                    val weight = (bottom - y).toDouble()
                    weightedX += weight * x
                    total += weight
                }
            }
        }
        assertTrue("Download curve is not visible", total > 20)
        return weightedX / total
    }

    private fun endTime(motion: SpeedChartMotion): Long {
        var time = 0L
        instrumentation.runOnMainSync { time = motion.displayEndNanos }
        return time
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (!condition()) {
            assertTrue("Chart did not reach the expected state", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(30)
        }
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }

    private fun restoreScale(value: String) {
        shell(if (value == "null") "settings delete global animator_duration_scale"
        else "settings put global animator_duration_scale $value")
    }

    private class FixtureOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private data class Capture(val started: Long, val completed: Long, val x: Double)
}
