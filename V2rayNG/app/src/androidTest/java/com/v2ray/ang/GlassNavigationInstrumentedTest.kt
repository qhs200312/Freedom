package com.v2ray.ang

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.SystemClock
import android.content.Intent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.v2ray.ang.ui.compose.AppTheme
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.ui.main.MainBottomNavigation
import com.v2ray.ang.ui.main.MainDestination
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassNavigationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val selected = mutableStateOf(MainDestination.Dashboard)
    private val backdropChanged = mutableStateOf(false)
    private val solidBackdrop = mutableStateOf(false)
    private val fineBackdrop = mutableStateOf(false)
    private val navigationColorChanged = mutableStateOf(false)
    @Volatile private var navigationBounds = Rect.Zero
    private val density get() = context.resources.displayMetrics.density

    @Test
    fun tabsSupportKeyboardSelectionWhenSystemAnimationsAreDisabled() {
        val oldScale = shell("settings get global animator_duration_scale").trim()
        shell("settings put global animator_duration_scale 0")
        cancelGesture()
        var activity: MainActivity? = null
        try {
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            navigationBounds = Rect.Zero
            instrumentation.runOnMainSync {
                selected.value = MainDestination.Dashboard
                backdropChanged.value = false
                solidBackdrop.value = true
                fineBackdrop.value = false
                navigationColorChanged.value = false
                activity.setContent { NavigationFixture(360, false) }
            }
            await { navigationBounds.width > 0f }
            settle()
            // A hardware key switches Compose from touch mode to keyboard focus mode.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            settle()
            for (destination in listOf(MainDestination.Settings, MainDestination.Nodes, MainDestination.Dashboard)) {
                val label = activity.getString(destination.labelRes)
                instrumentation.uiAutomation.clearCache()
                val node = findFocusableTab(instrumentation.uiAutomation.rootInActiveWindow, label)
                if (node == null) {
                    val tree = StringBuilder()
                    fun dump(current: AccessibilityNodeInfo?, depth: Int) {
                        if (current == null) return
                        tree.appendLine("${" ".repeat(depth)}${current.className} text=${current.text} " +
                            "desc=${current.contentDescription} focusable=${current.isFocusable} " +
                            "focused=${current.isFocused} clickable=${current.isClickable}")
                        for (index in 0 until current.childCount) dump(current.getChild(index), depth + 1)
                    }
                    dump(instrumentation.uiAutomation.rootInActiveWindow, 0)
                    File(context.filesDir, "glass-regression-results/keyboard-hierarchy.txt").writeText(tree.toString())
                    screenshot("keyboard-failure.png").recycle()
                }
                assertTrue("The $label tab must be keyboard focusable", node != null)
                assertTrue("Focus $label", node!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await { selected.value == destination }
            }
            settle()
            screenshot("keyboard-reduced-motion.png").recycle()
        } finally {
            cancelGesture()
            instrumentation.runOnMainSync { activity?.finish() }
            if (oldScale == "null") shell("settings delete global animator_duration_scale")
            else shell("settings put global animator_duration_scale $oldScale")
        }
    }

    @Test
    fun cancellingADragRetainsThePageAndTheNextDragStillNavigates() {
        cancelGesture()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            navigationBounds = Rect.Zero
            instrumentation.runOnMainSync {
                selected.value = MainDestination.Dashboard
                backdropChanged.value = false
                solidBackdrop.value = true
                fineBackdrop.value = false
                navigationColorChanged.value = false
                activity.setContent { NavigationFixture(360, false) }
            }
            await { navigationBounds.width > 0f }
            settle()
            val bounds = navigationBounds
            val itemWidth = (bounds.width - 76f * density) / 3f
            val firstX = bounds.left + 38f * density + itemWidth / 2f
            val y = bounds.top + 29f * density
            val resting = screenshot("motion-before-cancel.png")
            var downTime = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, firstX, y, downTime)
            for (step in 1..12) {
                touch(MotionEvent.ACTION_MOVE, firstX + itemWidth * step / 15f, y, downTime)
                Thread.sleep(16)
            }
            assertEquals("A drag is a preview until release", MainDestination.Dashboard, selected.value)
            touch(MotionEvent.ACTION_CANCEL, firstX + itemWidth * 0.8f, y, downTime)
            settle()
            assertEquals("Cancelling a drag must not commit the preview", MainDestination.Dashboard, selected.value)
            val cancelled = screenshot("motion-after-cancel.png")
            val region = Rect(bounds.left, bounds.top - 8f * density, bounds.right, bounds.top + 66f * density)
            assertTrue("Cancellation must restore the original lens position",
                changedPixels(resting, cancelled, region) < 30)
            resting.recycle()
            cancelled.recycle()

            downTime = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, firstX, y, downTime)
            for (step in 1..12) {
                touch(MotionEvent.ACTION_MOVE, firstX + itemWidth * step / 12f, y, downTime)
                Thread.sleep(16)
            }
            touch(MotionEvent.ACTION_UP, firstX + itemWidth, y, downTime)
            await { selected.value == MainDestination.Nodes }
            settle()
            screenshot("motion-after-next-drag.png").recycle()
        } finally {
            cancelGesture()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun glassRefractsNavigationAndSideContentButNotTheMainPage() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        cancelGesture()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            for (width in listOf(360, 740)) {
                if (width * density > context.resources.displayMetrics.widthPixels) continue
                for (dark in listOf(false, true)) {
                    navigationBounds = Rect.Zero
                    instrumentation.runOnMainSync {
                        selected.value = MainDestination.Dashboard
                        backdropChanged.value = false
                        solidBackdrop.value = false
                        fineBackdrop.value = false
                        navigationColorChanged.value = false
                        activity.setContent {
                            key(width, dark) {
                                NavigationFixture(width, dark)
                            }
                        }
                    }
                    await { navigationBounds.width > 0f }
                    settle()
                    awaitBackgroundFrame(dark)
                    val prefix = "${width}-${if (dark) "dark" else "light"}"
                    val resting = screenshot("$prefix-resting.png")
                    assertIconVisible(resting)
                    resting.recycle()

                    val bounds = navigationBounds
                    val itemWidth = (bounds.width - 76f * density) / 3f
                    val firstX = bounds.left + 38f * density + itemWidth / 2f
                    val y = bounds.top + 29f * density
                    val downTime = SystemClock.uptimeMillis()
                    touch(MotionEvent.ACTION_DOWN, firstX, y, downTime)
                    for (step in 1..18) {
                        touch(MotionEvent.ACTION_MOVE, firstX + itemWidth * step / 18f, y, downTime)
                        Thread.sleep(16)
                        if (step == 9) {
                            settle()
                            screenshot("$prefix-between-tabs.png").recycle()
                        }
                    }
                    settle()
                    instrumentation.runOnMainSync { solidBackdrop.value = true }
                    settle()
                    val cleanForeground = screenshot("$prefix-single-foreground.png")
                    assertSingleCenterIcon(cleanForeground, firstX + itemWidth)
                    assertVerticalRimReflection(cleanForeground, firstX + itemWidth)
                    assertNoTrailingIconOutsideLens(cleanForeground, firstX + itemWidth, itemWidth)
                    cleanForeground.recycle()
                    instrumentation.runOnMainSync { solidBackdrop.value = false }
                    settle()
                    val dragging = screenshot("$prefix-dragging.png")
                    instrumentation.runOnMainSync { backdropChanged.value = true }
                    settle()
                    awaitBackgroundFrame(dark)
                    val changed = screenshot("$prefix-live-backdrop.png")
                    val lensRegion = Rect(
                        firstX + itemWidth - 30f * density,
                        bounds.top + 18f * density,
                        firstX + itemWidth + 30f * density,
                        bounds.top + 38f * density,
                    )
                    assertTrue(
                        "Main-page text/icons must not enter the central navigation lens",
                        changedPixels(dragging, changed, lensRegion) < 10,
                    )
                    instrumentation.runOnMainSync { navigationColorChanged.value = true }
                    settle()
                    val changedNavigation = screenshot("$prefix-live-navigation.png")
                    assertTrue(
                        "The lens must sample the actual navigation icons instead of a static copy",
                        changedPixels(changed, changedNavigation, lensRegion) > 80,
                    )
                    assertVerticalRimUpdates(changed, changedNavigation, firstX + itemWidth)
                    changed.recycle()
                    changedNavigation.recycle()
                    instrumentation.runOnMainSync { navigationColorChanged.value = false }
                    settle()
                    dragging.recycle()
                    touch(MotionEvent.ACTION_UP, firstX + itemWidth, y, downTime)
                    await { selected.value == MainDestination.Nodes }
                    settle()
                    screenshot("$prefix-released.png").recycle()

                    val lastX = firstX + itemWidth * 2f
                    val tapTime = SystemClock.uptimeMillis()
                    touch(MotionEvent.ACTION_DOWN, lastX, y, tapTime)
                    touch(MotionEvent.ACTION_UP, lastX, y, tapTime)
                    await { selected.value == MainDestination.Settings }
                    assertEquals(MainDestination.Settings, selected.value)
                    settle()

                    for (leftEdge in listOf(true, false)) {
                        val startX = if (leftEdge) lastX else firstX
                        val endX = if (leftEdge) bounds.left + 8f * density
                            else bounds.right - 8f * density
                        val edgeDownTime = SystemClock.uptimeMillis()
                        touch(MotionEvent.ACTION_DOWN, startX, y, edgeDownTime)
                        for (step in 1..24) {
                            touch(
                                MotionEvent.ACTION_MOVE,
                                startX + (endX - startX) * step / 24f,
                                y,
                                edgeDownTime,
                            )
                            Thread.sleep(20)
                        }
                        settle()
                        awaitBackgroundFrame(dark)
                        val edgeName = if (leftEdge) "left-edge" else "right-edge"
                        val edgeImage = screenshot("$prefix-$edgeName.png")
                        val edgeRect = Rect(
                            if (leftEdge) bounds.left + 8f * density else bounds.right - 28f * density,
                            bounds.top + 14f * density,
                            if (leftEdge) bounds.left + 28f * density else bounds.right - 8f * density,
                            bounds.top + 44f * density,
                        )
                        instrumentation.runOnMainSync {
                            backdropChanged.value = !backdropChanged.value
                        }
                        settle()
                        awaitBackgroundFrame(dark)
                        val updatedEdgeImage = screenshot("$prefix-$edgeName-live.png")
                        assertTrue(
                            "The lens at either end must sample live page content outside the tab track",
                            changedPixels(edgeImage, updatedEdgeImage, edgeRect) > 30,
                        )
                        edgeImage.recycle()
                        updatedEdgeImage.recycle()
                        touch(MotionEvent.ACTION_UP, endX, y, edgeDownTime)
                        val expected = if (leftEdge) MainDestination.Dashboard else MainDestination.Settings
                        await { selected.value == expected }
                        settle()
                    }
                }
            }
        } finally {
            cancelGesture()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun pressingTheSelectedLensChangesItsMaterialAndCancellationRestoresIt() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        cancelGesture()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            for (dark in listOf(false, true)) {
                navigationBounds = Rect.Zero
                instrumentation.runOnMainSync {
                    selected.value = MainDestination.Dashboard
                    backdropChanged.value = false
                    solidBackdrop.value = true
                    fineBackdrop.value = false
                    navigationColorChanged.value = false
                    activity.setContent { key(dark) { NavigationFixture(360, dark) } }
                }
                await { navigationBounds.width > 0f }
                settle()
                awaitBackgroundFrame(dark)
                val bounds = navigationBounds
                val itemWidth = (bounds.width - 76f * density) / 3f
                val firstX = bounds.left + 38f * density + itemWidth / 2f
                val y = bounds.top + 29f * density
                val prefix = "press-${if (dark) "dark" else "light"}"
                val resting = screenshot("$prefix-resting.png")
                val downTime = SystemClock.uptimeMillis()
                touch(MotionEvent.ACTION_DOWN, firstX, y, downTime)
                settle()
                val pressed = screenshot("$prefix-held.png")
                assertEquals("Holding the lens must not navigate", MainDestination.Dashboard, selected.value)
                assertIconVisible(pressed)
                val region = Rect(
                    firstX - itemWidth / 2f,
                    bounds.top - 4f * density,
                    firstX + itemWidth / 2f,
                    bounds.top + 62f * density,
                )
                assertTrue("The held lens must have visible material feedback",
                    changedPixels(resting, pressed, region) > 80)
                touch(MotionEvent.ACTION_CANCEL, firstX, y, downTime)
                settle()
                val released = screenshot("$prefix-cancelled.png")
                assertEquals("Cancelling a press must retain the destination",
                    MainDestination.Dashboard, selected.value)
                assertTrue("Cancellation must restore the resting material",
                    changedPixels(resting, released, region) < 20)
                resting.recycle()
                pressed.recycle()
                released.recycle()
            }
        } finally {
            cancelGesture()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun mainPageStripesAreExcludedFromTheRestingAndDraggingDroplet() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        cancelGesture()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                selected.value = MainDestination.Dashboard
                fineBackdrop.value = true
                activity.setContent { NavigationFixture(360, false) }
            }
            await { navigationBounds.width > 0f }
            settle()
            val bounds = navigationBounds
            val itemWidth = (bounds.width - 76f * density) / 3f
            val firstX = bounds.left + 38f * density + itemWidth / 2f
            val resting = screenshot("clear-detail-resting.png")
            assertMainPageDetailExcluded(resting, firstX)
            assertRestingGrey(resting, firstX)
            resting.recycle()

            val y = bounds.top + 29f * density
            val downTime = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, firstX, y, downTime)
            for (step in 1..18) {
                touch(MotionEvent.ACTION_MOVE, firstX + itemWidth * step / 18f, y, downTime)
                Thread.sleep(16)
            }
            settle()
            val dragging = screenshot("clear-detail-dragging.png")
            assertMainPageDetailExcluded(dragging, firstX + itemWidth)
            dragging.recycle()
            touch(MotionEvent.ACTION_UP, firstX + itemWidth, y, downTime)
            await { selected.value == MainDestination.Nodes }
        } finally {
            cancelGesture()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Composable
    private fun NavigationFixture(width: Int, dark: Boolean) {
        AppTheme(darkTheme = dark) {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme.copy(
                    secondary = if (navigationColorChanged.value) Color(0xFF3479E9)
                        else MaterialTheme.colorScheme.secondary,
                ),
                typography = MaterialTheme.typography,
                shapes = MaterialTheme.shapes,
            ) {
                val fixtureBackground = when {
                    dark && backdropChanged.value -> Color(0xFF283D39)
                    dark -> Color(0xFF17191C)
                    backdropChanged.value -> Color(0xFFF4CCE3)
                    else -> Color(0xFFF5F6F8)
                }
                val currentBackground = rememberUpdatedState(fixtureBackground)
                val backdrop = rememberLayerBackdrop {
                    drawRect(currentBackground.value)
                    drawContent()
                }
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize()
                            .background(fixtureBackground)
                            .layerBackdrop(backdrop)
                            .drawBehind {
                                if (fineBackdrop.value) {
                                    drawRect(Color.White)
                                    val stripeWidth = 2.dp.toPx()
                                    for (stripe in 0..(size.width / stripeWidth).toInt() step 2) {
                                        drawRect(
                                            Color.Black,
                                            topLeft = Offset(stripe * stripeWidth, 0f),
                                            size = Size(stripeWidth, size.height),
                                        )
                                    }
                                }
                            },
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        repeat(if (solidBackdrop.value || fineBackdrop.value) 0 else 25) { index ->
                            Row(
                                Modifier.fillMaxWidth().height(34.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                repeat(4) {
                                    Icon(
                                        painterResource(R.drawable.ic_dns_24dp),
                                        contentDescription = null,
                                        tint = if (backdropChanged.value) Color(0xFFE54CA5) else Color(0xFF24B89A),
                                        modifier = Modifier.size(26.dp),
                                    )
                                    Text(
                                        "WiFi ${if (backdropChanged.value) "ABC" else "123"} / $index",
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                    MainBottomNavigation(
                        selectedDestination = selected.value,
                        onDestinationSelected = { selected.value = it },
                        backdrop = backdrop,
                        modifier = Modifier.width(width.dp)
                            .align(Alignment.BottomCenter)
                            .onGloballyPositioned { navigationBounds = it.boundsInWindow() },
                    )
                }
            }
        }
    }

    private fun assertIconVisible(bitmap: Bitmap) {
        val bounds = navigationBounds
        var orangePixels = 0
        val left = (bounds.left + 38f * density).toInt()
        val right = (bounds.left + 38f * density + (bounds.width - 76f * density) / 3f).toInt()
        for (y in (bounds.top + 8f * density).toInt() until (bounds.top + 48f * density).toInt()) {
            for (x in left until right) {
                val color = bitmap.getPixel(x, y)
                if (isOrange(color)) orangePixels++
            }
        }
        assertTrue("The real selected icon/label must not disappear behind the glass", orangePixels > 80)
    }

    private fun assertNoTrailingIconOutsideLens(bitmap: Bitmap, centerX: Float, itemWidth: Float) {
        val bounds = navigationBounds
        var trailingPixels = 0
        val halfWidth = (itemWidth - 8f * density) * 1.46f / 2f
        for (y in (bounds.top - 12f * density).toInt() until (bounds.top + 70f * density).toInt()) {
            for (x in (centerX - halfWidth - 16f * density).toInt() until
                (centerX + halfWidth + 16f * density).toInt()
            ) {
                if (abs(x - centerX) > halfWidth + 2f * density && isOrange(bitmap.getPixel(x, y))) {
                    trailingPixels++
                }
            }
        }
        assertTrue("Refracted icons must not leave a trailing copy outside the moving lens", trailingPixels <= 10)
    }

    private fun assertSingleCenterIcon(bitmap: Bitmap, centerX: Float) {
        var iconPixels = 0
        var widestSpan = 0
        for (y in (navigationBounds.top + 8f * density).toInt() until
            (navigationBounds.top + 32f * density).toInt()
        ) {
            val pixels = ((centerX - 30f * density).toInt() until (centerX + 30f * density).toInt())
                .filter { isOrange(bitmap.getPixel(it, y)) }
            iconPixels += pixels.size
            if (pixels.isNotEmpty()) widestSpan = maxOf(widestSpan, pixels.last() - pixels.first())
        }
        assertTrue("The icon must still be rendered at the center of the lens", iconPixels > 80)
        assertTrue(
            "The center icon must not contain an additional unfiltered or trailing copy",
            widestSpan < 32f * density,
        )
    }

    private fun verticalRimRegions(centerX: Float): List<Rect> = listOf(
        Rect(
            centerX - 25f * density,
            navigationBounds.top - 6f * density,
            centerX + 25f * density,
            navigationBounds.top + 4f * density,
        ),
        Rect(
            centerX - 25f * density,
            navigationBounds.top + 54f * density,
            centerX + 25f * density,
            navigationBounds.top + 64f * density,
        ),
    )

    private fun assertVerticalRimReflection(bitmap: Bitmap, centerX: Float) {
        for ((index, region) in verticalRimRegions(centerX).withIndex()) {
            var iconPixels = 0
            for (y in region.top.toInt() until region.bottom.toInt()) {
                for (x in region.left.toInt() until region.right.toInt()) {
                    if (isOrange(bitmap.getPixel(x, y))) iconPixels++
                }
            }
            assertTrue(
                "${if (index == 0) "Top" else "Bottom"} rim must visibly refract navigation glyphs ($iconPixels)",
                iconPixels > 10,
            )
        }
    }

    private fun assertVerticalRimUpdates(first: Bitmap, second: Bitmap, centerX: Float) {
        for ((index, region) in verticalRimRegions(centerX).withIndex()) {
            assertTrue(
                "${if (index == 0) "Top" else "Bottom"} rim must sample live navigation content",
                changedPixels(first, second, region) > 10,
            )
        }
    }

    private fun isOrange(color: Int): Boolean = AndroidColor.red(color) > 180 &&
        AndroidColor.green(color) in 65..185 && AndroidColor.blue(color) < 110

    private fun assertMainPageDetailExcluded(bitmap: Bitmap, centerX: Float) {
        var darkPixels = 0
        var total = 0
        // A black/white page must not become the reflection source inside the tab track.
        for (y in (navigationBounds.top + 18f * density).toInt() until
            (navigationBounds.top + 30f * density).toInt()
        ) {
            for (x in (centerX + 20f * density).toInt() until (centerX + 32f * density).toInt()) {
                val color = bitmap.getPixel(x, y)
                val brightness = (AndroidColor.red(color) + AndroidColor.green(color) +
                    AndroidColor.blue(color)) / 3
                if (brightness < 45) darkPixels++
                total++
            }
        }
        assertTrue(
            "Main-page stripes must not appear inside the navigation-only droplet " +
                "(dark=$darkPixels, total=$total)",
            darkPixels <= total / 100,
        )
    }

    private fun assertRestingGrey(bitmap: Bitmap, centerX: Float) {
        val color = bitmap.getPixel(
            (centerX + 24f * density).toInt(),
            (navigationBounds.top + 25f * density).toInt(),
        )
        val r = AndroidColor.red(color)
        val g = AndroidColor.green(color)
        val b = AndroidColor.blue(color)
        assertTrue(
            "The resting droplet must have a pale neutral grey surface",
            r in 220..245 && abs(r - g) < 5 && abs(g - b) < 8,
        )
    }

    private fun changedPixels(first: Bitmap, second: Bitmap, region: Rect): Int {
        var count = 0
        for (y in region.top.toInt() until region.bottom.toInt()) {
            for (x in region.left.toInt() until region.right.toInt()) {
                val a = first.getPixel(x, y)
                val b = second.getPixel(x, y)
                val delta = abs(AndroidColor.red(a) - AndroidColor.red(b)) +
                    abs(AndroidColor.green(a) - AndroidColor.green(b)) +
                    abs(AndroidColor.blue(a) - AndroidColor.blue(b))
                if (delta > 45) count++
            }
        }
        return count
    }

    private fun awaitBackgroundFrame(dark: Boolean) {
        val expected = when {
            dark && backdropChanged.value -> 0xFF283D39.toInt()
            dark -> 0xFF17191C.toInt()
            backdropChanged.value -> 0xFFF4CCE3.toInt()
            else -> 0xFFF5F6F8.toInt()
        }
        // Main-thread idle does not guarantee that the GPU has presented the updated scene.
        val deadline = SystemClock.uptimeMillis() + 10_000L
        var actual: Int
        do {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            actual = bitmap.getPixel(
                (2f * density).toInt(),
                (navigationBounds.top + 30f * density).toInt(),
            )
            bitmap.recycle()
            val delta = abs(AndroidColor.red(actual) - AndroidColor.red(expected)) +
                abs(AndroidColor.green(actual) - AndroidColor.green(expected)) +
                abs(AndroidColor.blue(actual) - AndroidColor.blue(expected))
            if (delta < 20) return
            Thread.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("The updated page background must be presented before sampling the lens", expected, actual)
    }

    private fun touch(action: Int, x: Float, y: Float, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private fun findFocusableTab(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        fun hasLabel(current: AccessibilityNodeInfo): Boolean {
            if (current.text?.toString() == label || current.contentDescription?.toString() == label) return true
            for (index in 0 until current.childCount) {
                current.getChild(index)?.let { if (hasLabel(it)) return true }
            }
            return false
        }
        if (node.isFocusable && hasLabel(node)) return node
        for (index in 0 until node.childCount) {
            findFocusableTab(node.getChild(index), label)?.let { return it }
        }
        return null
    }

    private fun cancelGesture() {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try {
            instrumentation.uiAutomation.injectInputEvent(event, true)
        } finally {
            event.recycle()
        }
    }

    private fun screenshot(name: String): Bitmap {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(context.filesDir, "glass-regression-results").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(500)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (!condition()) {
            assertTrue("Timed out waiting for navigation state", SystemClock.uptimeMillis() < deadline)
            Thread.sleep(50)
        }
    }
}
