package com.v2ray.ang

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.v2ray.ang.util.QrLuminanceDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class QrLuminanceDecoderTest {
    @Test
    fun decodesLongNodeQrCode() {
        val node = longNode()
        val image = encode(node)

        assertEquals(node, QrLuminanceDecoder.decode(image.pixels, image.width, image.height))
    }

    @Test
    fun decodesInvertedLongNodeQrCode() {
        val node = longNode()
        val image = encode(node)
        val inverted = image.pixels.map { (255 - it.toUByte().toInt()).toByte() }.toByteArray()

        assertEquals(node, QrLuminanceDecoder.decode(inverted, image.width, image.height))
    }

    @Test
    fun reconstructsVersion40QrRenderedAtOnePointFivePixelsPerModule() {
        val prefix = "vless://00000000-0000-4000-8000-000000000000@example.com:443?encryption=none&"
        val node = prefix + "a".repeat(2_934 - prefix.length)
        val modules = 177
        val matrix = QRCodeWriter().encode(
            node,
            BarcodeFormat.QR_CODE,
            modules,
            modules,
            mapOf(
                EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 0,
                EncodeHintType.QR_VERSION to 40,
            ),
        )
        val width = 300
        val height = 301
        val qrWidth = 268
        val qrHeight = 269
        val left = 16
        val top = 16
        val pixels = ByteArray(width * height) { 0xff.toByte() }
        for (y in 0 until qrHeight) {
            for (x in 0 until qrWidth) {
                val moduleX = x * modules / qrWidth
                val moduleY = y * modules / qrHeight
                if (matrix[moduleX, moduleY]) {
                    pixels[(top + y) * width + left + x] = 0
                }
            }
        }

        assertEquals(node, QrLuminanceDecoder.decode(pixels, width, height))
    }

    private fun longNode(): String = buildString {
        append("vless://00000000-0000-4000-8000-000000000000@example.com:443")
        append("?encryption=none&security=reality&type=tcp&sni=example.com&fp=chrome")
        append("&pbk=")
        append("a".repeat(1_800))
        append("#Long-QR-Node")
    }

    private fun encode(content: String): LuminanceImage {
        val size = 1_200
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 2,
            ),
        )
        val pixels = ByteArray(size * size) { index ->
            if (matrix[index % size, index / size]) 0 else 0xff.toByte()
        }
        return LuminanceImage(size, size, pixels)
    }

    private data class LuminanceImage(
        val width: Int,
        val height: Int,
        val pixels: ByteArray,
    )
}
