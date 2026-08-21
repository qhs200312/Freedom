package com.v2ray.ang.util

import java.nio.ByteBuffer

internal object YuvLuminanceExtractor {
    fun extract(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): ByteArray {
        require(width > 0 && height > 0)
        require(rowStride > 0 && pixelStride > 0)

        val source = buffer.duplicate()
        val baseOffset = source.position()
        val lastByteOffset = baseOffset +
            (height - 1) * rowStride +
            (width - 1) * pixelStride
        require(lastByteOffset < source.limit())

        return ByteArray(width * height).also { luminance ->
            if (rowStride == width && pixelStride == 1) {
                source.get(luminance)
                return@also
            }

            var outputOffset = 0
            repeat(height) { row ->
                val rowOffset = baseOffset + row * rowStride
                repeat(width) { column ->
                    luminance[outputOffset++] = source.get(rowOffset + column * pixelStride)
                }
            }
        }
    }
}
