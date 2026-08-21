package com.v2ray.ang

import com.v2ray.ang.util.YuvLuminanceExtractor
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer

class YuvLuminanceExtractorTest {
    @Test
    fun extractsContiguousLuminancePlane() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6))

        val result = YuvLuminanceExtractor.extract(
            buffer = source,
            width = 3,
            height = 2,
            rowStride = 3,
            pixelStride = 1,
        )

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), result)
    }

    @Test
    fun removesRowPadding() {
        val source = ByteBuffer.wrap(
            byteArrayOf(
                10, 11, 12, 99, 99,
                20, 21, 22, 88, 88,
            )
        )

        val result = YuvLuminanceExtractor.extract(
            buffer = source,
            width = 3,
            height = 2,
            rowStride = 5,
            pixelStride = 1,
        )

        assertArrayEquals(byteArrayOf(10, 11, 12, 20, 21, 22), result)
    }

    @Test
    fun extractsInterleavedLuminanceSamples() {
        val source = ByteBuffer.wrap(
            byteArrayOf(
                1, 90, 2, 90, 3, 90, 90, 90,
                4, 80, 5, 80, 6, 80, 80, 80,
            )
        )

        val result = YuvLuminanceExtractor.extract(
            buffer = source,
            width = 3,
            height = 2,
            rowStride = 8,
            pixelStride = 2,
        )

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), result)
    }
}
