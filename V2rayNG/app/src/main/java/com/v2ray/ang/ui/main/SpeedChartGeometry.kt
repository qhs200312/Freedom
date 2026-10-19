package com.v2ray.ang.ui.main

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.abs

internal const val SpeedChartSampleIntervalMs = 3_000L
internal const val SpeedChartWindowSeconds = 144
private const val NanosPerSecond = 1_000_000_000L

/** Monotone cubic handles keep the line within each pair of measured values. */
internal fun speedChartTangents(values: List<Float>): List<Float> {
    if (values.size < 2) return List(values.size) { 0f }
    val slopes = values.zipWithNext { a, b -> b - a }
    return values.indices.map { index ->
        when (index) {
            0 -> slopes.first()
            values.lastIndex -> slopes.last()
            else -> {
                val before = slopes[index - 1]
                val after = slopes[index]
                if (before * after <= 0f) 0f else 2f * before * after / (before + after)
            }
        }
    }
}

internal data class SpeedChartSample(
    val timeNanos: Long,
    val upload: Float,
    val download: Float,
)

/** A delayed viewport reveals measured segments at a constant speed, without morphing history. */
@Stable
internal class SpeedChartMotion(
    initialTimeNanos: Long = System.nanoTime(),
    initialUpload: Long = 0,
    initialDownload: Long = 0,
) {
    val windowNanos = SpeedChartWindowSeconds * NanosPerSecond
    val sampleIntervalNanos = SpeedChartSampleIntervalMs * 1_000_000L
    var samples by mutableStateOf(seed(initialTimeNanos, initialUpload, initialDownload))
        private set
    var displayEndNanos by mutableLongStateOf(initialTimeNanos - sampleIntervalNanos)
        private set
    var maximum by mutableFloatStateOf(measuredMaximum())
        private set
    private var lastFrameNanos = initialTimeNanos
    private var targetMaximum = maximum
    val needsContinuousFrames: Boolean
        get() {
            val first = samples.first()
            return samples.any { it.upload != first.upload || it.download != first.download } ||
                abs(maximum - targetMaximum) > max(1f, targetMaximum * 0.001f)
        }
    val incomingTangentFraction: Float
        get() {
            val t = ((lastFrameNanos - samples.last().timeNanos) / 800_000_000.0)
                .coerceIn(0.0, 1.0).toFloat()
            return t * t * (3f - 2f * t)
        }

    fun advance(timeNanos: Long, upload: Long, download: Long, animate: Boolean = true) {
        if (timeNanos < lastFrameNanos) return
        val elapsedNanos = timeNanos - lastFrameNanos
        lastFrameNanos = timeNanos
        val stale = timeNanos - samples.last().timeNanos > windowNanos
        if (stale) {
            // A stale foreground history must not animate through a long background interval.
            samples = seed(timeNanos, upload, download)
            targetMaximum = measuredMaximum()
        } else if (timeNanos - samples.last().timeNanos >= sampleIntervalNanos) {
            val cutoff = timeNanos - windowNanos - 3 * sampleIntervalNanos
            samples = samples.dropWhile { it.timeNanos < cutoff } + SpeedChartSample(
                timeNanos, upload.coerceAtLeast(0).toFloat(), download.coerceAtLeast(0).toFloat(),
            )
            targetMaximum = measuredMaximum()
        }
        displayEndNanos = timeNanos - if (animate) sampleIntervalNanos else 0
        maximum = if (!animate || stale) {
            targetMaximum
        } else {
            val timeConstantSeconds = if (targetMaximum > maximum) 0.35 else 5.0
            val frameStep = elapsedNanos.coerceAtMost(32_000_000L)
            val amount = (1.0 - exp(-frameStep.toDouble() / NanosPerSecond / timeConstantSeconds)).toFloat()
            maximum + (targetMaximum - maximum) * amount
        }.coerceAtLeast(1f)
    }

    fun horizontalFraction(sampleTimeNanos: Long): Float =
        (1.0 + (sampleTimeNanos - displayEndNanos).toDouble() / windowNanos).toFloat()

    private fun measuredMaximum(): Float =
        samples.maxOfOrNull { max(it.upload, it.download) }?.coerceAtLeast(1f) ?: 1f

    private fun seed(timeNanos: Long, upload: Long, download: Long): List<SpeedChartSample> {
        val count = SpeedChartWindowSeconds * 1_000L / SpeedChartSampleIntervalMs + 3
        return (0..count.toInt()).map { index ->
            SpeedChartSample(
                timeNanos - (count - index) * sampleIntervalNanos,
                if (index == count.toInt()) upload.coerceAtLeast(0).toFloat() else 0f,
                if (index == count.toInt()) download.coerceAtLeast(0).toFloat() else 0f,
            )
        }
    }
}
