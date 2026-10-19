package com.v2ray.ang.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationLensGeometryTest {
    @Test
    fun expandedLensCanReachBothScreenEdgesWithoutClippingItsOutline() {
        for (width in listOf(320f, 360f, 412f, 740f, 852f)) {
            for (scale in listOf(1f, 1.15f, 1.35f)) {
                val trackWidth = width - 76f
                val geometry = NavigationLensGeometry(
                    trackWidth = trackWidth,
                    trackHeight = 58f,
                    lensWidth = trackWidth / 3f - 8f,
                    verticalInset = 0f,
                        scaleX = scale,
                    restingInset = 4f,
                    edgeOutset = 30f,
                )
                val left = geometry.boundsAt(geometry.minOffset)
                val right = geometry.boundsAt(geometry.maxOffset)
                assertEquals(8f, left.left + 38f, 0.001f)
                assertEquals(width - 8f, right.right + 38f, 0.001f)
                assertTrue(geometry.minOffset < geometry.maxOffset)
                assertEquals(29f, left.center.y, 0.001f)
                assertEquals(58f * scale, left.height, 0.001f)
            }
        }
    }

    @Test
    fun dragOnNarrowScreensExtendsPastTheFirstAndLastTabCenters() {
        val trackWidth = 360f - 76f
        val geometry = NavigationLensGeometry(
            trackWidth, 58f, trackWidth / 3f - 8f, 0f, 1.35f, 4f, 30f,
        )
        assertTrue(geometry.minOffset < 0f)
        assertTrue(geometry.maxOffset > trackWidth / 3f * 2f)
    }

    @Test
    fun restingMaskMatchesTheInsetCapsuleAndDoesNotChangeTheTabCenter() {
        val geometry = NavigationLensGeometry(284f, 58f, 284f / 3f - 8f, 4f, 1f, 4f, 30f)
        val bounds = geometry.boundsAt(0f)
        assertEquals(4f, bounds.left, 0.001f)
        assertEquals(4f, bounds.top, 0.001f)
        assertEquals(54f, bounds.bottom, 0.001f)
        assertEquals(284f / 6f, bounds.center.x, 0.001f)
    }

    @Test
    fun speedStretchKeepsTheMaskAndBothScreenEdgesAligned() {
        for (speed in listOf(0f, 0.5f, 1f)) {
            val stretch = navigationLensStretch(true, false, speed)
            val geometry = NavigationLensGeometry(
                trackWidth = 284f,
                trackHeight = 58f,
                lensWidth = 284f / 3f - 8f,
                verticalInset = 0f,
                scaleX = stretch.scaleX,
                restingInset = 4f,
                edgeOutset = 30f,
                scaleY = stretch.scaleY,
            )
            assertEquals(8f, geometry.boundsAt(geometry.minOffset).left + 38f, 0.001f)
            assertEquals(352f, geometry.boundsAt(geometry.maxOffset).right + 38f, 0.001f)
            assertEquals(58f * stretch.scaleY, geometry.boundsAt(0f).height, 0.001f)
            assertEquals(29f * stretch.scaleX, geometry.cornerRadius.x, 0.001f)
            assertEquals(29f * stretch.scaleY, geometry.cornerRadius.y, 0.001f)
        }
    }
}
