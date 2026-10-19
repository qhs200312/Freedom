package com.v2ray.ang.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// APerf's easing, with a separate, deliberately gentle spring for this navigation.
internal val NavigationMaterialEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
internal const val NavigationSpringDamping = 0.82f
internal const val NavigationSpringStiffness = 420f

@Stable
internal class NavigationLensMotionState(
    private val scope: CoroutineScope,
    initialOffset: Float,
) {
    var offsetPx by mutableFloatStateOf(initialOffset)
        private set
    var isDragging by mutableStateOf(false)
        private set
    var isSettling by mutableStateOf(false)
        private set

    private var settlingJob: Job? = null
    private var generation = 0
    private var targetOffset = initialOffset

    fun capture(visibleOffset: Float = offsetPx) {
        generation++
        settlingJob?.cancel()
        settlingJob = null
        isSettling = false
        offsetPx = visibleOffset
    }

    fun beginDrag() {
        capture()
        isDragging = true
    }

    fun dragBy(delta: Float, minOffset: Float, maxOffset: Float) {
        if (isDragging) offsetPx = (offsetPx + delta).coerceIn(minOffset, maxOffset)
    }

    fun snapTo(target: Float) {
        capture()
        isDragging = false
        targetOffset = target
        offsetPx = target
    }

    fun settleTo(target: Float, reduceMotion: Boolean) {
        if (!isDragging && isSettling && targetOffset == target && !reduceMotion) return
        capture()
        isDragging = false
        targetOffset = target
        if (reduceMotion || offsetPx == target) {
            offsetPx = target
            return
        }
        val currentGeneration = generation
        isSettling = true
        settlingJob = scope.launch {
            try {
                Animatable(offsetPx).animateTo(
                    targetValue = target,
                    animationSpec = spring(
                        dampingRatio = NavigationSpringDamping,
                        stiffness = NavigationSpringStiffness,
                    ),
                ) {
                    if (generation == currentGeneration) offsetPx = value
                }
            } finally {
                if (generation == currentGeneration) isSettling = false
            }
        }
    }
}

internal fun nearestNavigationIndex(offsetPx: Float, itemWidthPx: Float, count: Int): Int =
    if (itemWidthPx > 0f && count > 0) {
        (offsetPx / itemWidthPx).roundToInt().coerceIn(0, count - 1)
    } else 0

internal data class NavigationLensStretch(val scaleX: Float, val scaleY: Float)

internal fun navigationLensStretch(
    dragging: Boolean,
    pressed: Boolean,
    speedRatio: Float,
): NavigationLensStretch {
    if (!dragging) {
        val scale = if (pressed) 1.1f else 1f
        return NavigationLensStretch(scale, scale)
    }
    val speed = speedRatio.coerceIn(0f, 1f)
    return NavigationLensStretch(
        scaleX = 1.26f + 0.16f * speed,
        scaleY = 1.24f - 0.12f * speed,
    )
}
