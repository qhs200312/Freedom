package com.v2ray.ang.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedChartGeometryTest {
    @Test
    fun flatPeakGetsZeroTangentInsteadOfOvershooting() {
        val tangents = speedChartTangents(listOf(0f, 10f, 0f))
        assertEquals(10f, tangents[0], 0f)
        assertEquals(0f, tangents[1], 0f)
        assertEquals(-10f, tangents[2], 0f)
    }

    @Test
    fun historyMovesAtConstantSpeedThroughoutTheEntireSampleInterval() {
        val motion = SpeedChartMotion(initialTimeNanos = 0)
        val timestamp = -30_000_000_000L
        var previousX = motion.horizontalFraction(timestamp)
        repeat(500) { index ->
            motion.advance((index + 1) * 16_000_000L, 20, 80)
            val x = motion.horizontalFraction(timestamp)
            assertEquals(0.016f / SpeedChartWindowSeconds, previousX - x, 0.0000001f)
            previousX = x
        }
    }

    @Test
    fun newMeasurementsDoNotMoveOrFlattenPreviouslyMeasuredPeaks() {
        val motion = SpeedChartMotion(initialTimeNanos = 0, initialDownload = 8000)
        motion.advance(2_999_000_000L, 0, 10)
        val before = motion.horizontalFraction(0)
        motion.advance(3_001_000_000L, 0, 10)
        assertEquals(0.002f / SpeedChartWindowSeconds, before - motion.horizontalFraction(0), 0.0000001f)
        assertEquals(8000f, motion.samples.single { it.timeNanos == 0L }.download)
    }

    @Test
    fun newSamplesAreRevealedFromOutsideTheRightEdge() {
        val motion = SpeedChartMotion(initialTimeNanos = 0)
        motion.advance(3_000_000_000L, 20, 80)
        assertTrue(motion.horizontalFraction(motion.samples.last().timeNanos) > 1f)
        motion.advance(6_000_000_000L, 30, 90)
        assertEquals(1f, motion.horizontalFraction(3_000_000_000L), 0.000001f)
    }

    @Test
    fun verticalScaleApproachesNewPeakWithoutAnInstantJump() {
        val motion = SpeedChartMotion(initialTimeNanos = 0, initialDownload = 100)
        motion.advance(2_990_000_000L, 0, 100)
        motion.advance(3_006_000_000L, 0, 10_000)
        assertTrue(motion.maximum > 100f)
        assertTrue(motion.maximum < 1000f)
        repeat(125) { motion.advance(3_022_000_000L + it * 16_000_000L, 0, 10_000) }
        assertTrue(motion.maximum > 9900f)
        assertEquals(10_000f, motion.samples.last().download, 0f)
    }

    @Test
    fun historyIsBoundedAndDoesNotReplayLongBackgroundGaps() {
        val motion = SpeedChartMotion(initialTimeNanos = 0, initialDownload = 100)
        repeat(1000) { motion.advance((it + 1) * 3_000_000_000L, 0, 10) }
        assertTrue(motion.samples.size <= 53)
        val resumed = 4_000_000_000_000L
        motion.advance(resumed, 7, 21)
        assertEquals(resumed - 3_000_000_000L, motion.displayEndNanos)
        assertEquals(21f, motion.samples.last().download, 0f)
        assertEquals(21f, motion.maximum, 0f)
    }

    @Test
    fun aFlatHistorySleepsUntilThereIsVisibleMotion() {
        val motion = SpeedChartMotion(initialTimeNanos = 0)
        assertTrue(!motion.needsContinuousFrames)
        motion.advance(3_000_000_000L, 0, 0)
        assertTrue(!motion.needsContinuousFrames)
        motion.advance(6_000_000_000L, 0, 100)
        assertTrue(motion.needsContinuousFrames)
    }

    @Test
    fun reducedMotionUsesLatestTimestampAndInvalidClockFramesAreIgnored() {
        val motion = SpeedChartMotion(initialTimeNanos = 0)
        motion.advance(3_000_000_000L, -20, -30, animate = false)
        assertEquals(3_000_000_000L, motion.displayEndNanos)
        assertEquals(0f, motion.samples.last().upload, 0f)
        motion.advance(2_000_000_000L, 10, 30)
        assertEquals(3_000_000_000L, motion.displayEndNanos)
        assertEquals(1f, motion.maximum, 0f)
    }
}
