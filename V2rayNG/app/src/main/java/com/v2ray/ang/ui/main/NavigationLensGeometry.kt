package com.v2ray.ang.ui.main

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect

internal data class NavigationLensGeometry(
    val trackWidth: Float,
    val trackHeight: Float,
    val lensWidth: Float,
    val verticalInset: Float,
    val scaleX: Float,
    val restingInset: Float,
    val edgeOutset: Float,
    val scaleY: Float = scaleX,
) {
    private val lensHeight = (trackHeight - verticalInset * 2f).coerceAtLeast(0f)
    private val scaledWidth = lensWidth * scaleX
    private val scaledHeight = lensHeight * scaleY
    private val radius = minOf(lensWidth, lensHeight) / 2f

    val cornerRadius = CornerRadius(radius * scaleX, radius * scaleY)

    val minOffset = -edgeOutset - restingInset + (scaledWidth - lensWidth) / 2f
    val maxOffset = trackWidth + edgeOutset - restingInset - (scaledWidth + lensWidth) / 2f

    fun boundsAt(offset: Float): Rect {
        val centerX = offset + restingInset + lensWidth / 2f
        val centerY = trackHeight / 2f
        return Rect(
            left = centerX - scaledWidth / 2f,
            top = centerY - scaledHeight / 2f,
            right = centerX + scaledWidth / 2f,
            bottom = centerY + scaledHeight / 2f,
        )
    }
}
