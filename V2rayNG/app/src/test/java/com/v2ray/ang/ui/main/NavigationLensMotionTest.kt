package com.v2ray.ang.ui.main

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationLensMotionTest {
    @Test
    fun releaseSelectsTheNearestTabWithoutAFlingProjection() {
        assertEquals(0, nearestNavigationIndex(49f, 100f, 3))
        assertEquals(1, nearestNavigationIndex(51f, 100f, 3))
        assertEquals(1, nearestNavigationIndex(149f, 100f, 3))
        assertEquals(2, nearestNavigationIndex(151f, 100f, 3))
        assertEquals(0, nearestNavigationIndex(-300f, 100f, 3))
        assertEquals(2, nearestNavigationIndex(600f, 100f, 3))
    }

    @Test
    fun aNewDragInterruptsTheSpringAtItsCurrentVisiblePosition() = runBlocking {
        val frameClock = BroadcastFrameClock()
        val motion = NavigationLensMotionState(CoroutineScope(coroutineContext + frameClock), 0f)
        motion.settleTo(200f, reduceMotion = false)
        yield()
        repeat(5) { index ->
            frameClock.sendFrame((index + 1) * 16_000_000L)
            yield()
        }
        val captured = motion.offsetPx
        assertTrue(captured > 0f && captured < 200f)
        motion.beginDrag()
        motion.dragBy(23f, -30f, 250f)
        repeat(6) { index ->
            frameClock.sendFrame((index + 6) * 16_000_000L)
            yield()
        }
        assertEquals(captured + 23f, motion.offsetPx, 0.001f)
        assertTrue(motion.isDragging)
        assertTrue(!motion.isSettling)
        motion.snapTo(0f)
    }

    @Test
    fun reducedMotionRestoresTheCommittedPositionWithoutASpring() = runBlocking {
        val motion = NavigationLensMotionState(this, 100f)
        motion.beginDrag()
        motion.dragBy(47f, -30f, 250f)
        motion.settleTo(100f, reduceMotion = true)
        assertEquals(100f, motion.offsetPx, 0f)
        assertTrue(!motion.isDragging && !motion.isSettling)
    }

    @Test
    fun aFasterDragStretchesHorizontallyAndCompressesVertically() {
        val slow = navigationLensStretch(true, false, 0f)
        val fast = navigationLensStretch(true, false, 1f)
        assertTrue(fast.scaleX > slow.scaleX)
        assertTrue(fast.scaleY < slow.scaleY)
        assertTrue(fast.scaleY > 1f)
    }

    @Test
    fun speedIsBoundedAndDoesNotAffectTheReleasedShape() {
        assertEquals(
            navigationLensStretch(true, false, 1f),
            navigationLensStretch(true, false, 20f),
        )
        assertEquals(
            navigationLensStretch(true, false, 0f),
            navigationLensStretch(true, false, -1f),
        )
        assertEquals(NavigationLensStretch(1f, 1f), navigationLensStretch(false, false, 1f))
        assertEquals(NavigationLensStretch(1.1f, 1.1f), navigationLensStretch(false, true, 1f))
    }
}
