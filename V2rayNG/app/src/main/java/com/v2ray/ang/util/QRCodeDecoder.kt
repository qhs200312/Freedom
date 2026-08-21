package com.v2ray.ang.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter

/**
 * QR code decoder utility.
 */
object QRCodeDecoder {
    /**
     * Creates a QR code bitmap from the given text.
     *
     * @param text The text to encode in the QR code.
     * @param size The size of the QR code bitmap.
     * @return The generated QR code bitmap, or null if an error occurs.
     */
    fun createQRCode(text: String, size: Int = 800): Bitmap? {
        return runCatching {
            val hints = mapOf(EncodeHintType.CHARACTER_SET to Charsets.UTF_8)
            val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val pixels = IntArray(size * size) { i ->
                if (bitMatrix.get(i % size, i / size)) 0xff000000.toInt() else 0xffffffff.toInt()
            }
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, size, 0, 0, size, size)
            }
        }.getOrNull()
    }

    /**
     * Decodes a QR code from a local image file. This method is time-consuming and should be called in a background thread.
     *
     * @param picturePath The local path of the image file to decode.
     * @return The content of the QR code, or null if decoding fails.
     */
    fun syncDecodeQRCode(picturePath: String): String? {
        return syncDecodeQRCode(getDecodeAbleBitmap(picturePath))
    }

    /**
     * Decodes a QR code from a bitmap. This method is time-consuming and should be called in a background thread.
     *
     * @param bitmap The bitmap to decode.
     * @return The content of the QR code, or null if decoding fails.
     */
    fun syncDecodeQRCode(bitmap: Bitmap?): String? {
        return bitmap?.let {
            runCatching {
                val pixels = IntArray(it.width * it.height).also { array ->
                    it.getPixels(array, 0, it.width, 0, 0, it.width, it.height)
                }
                val source = RGBLuminanceSource(it.width, it.height, pixels)
                QrLuminanceDecoder.decode(source.matrix, source.width, source.height)
            }.getOrNull()
        }
    }

    /**
     * Converts a local image file to a bitmap that can be decoded as a QR code. The image is compressed to avoid being too large.
     *
     * @param picturePath The local path of the image file.
     * @return The decoded bitmap, or null if an error occurs.
     */
    private fun getDecodeAbleBitmap(picturePath: String): Bitmap? {
        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(picturePath, options)
            var sampleSize = options.outHeight / 400
            if (sampleSize <= 0) {
                sampleSize = 1
            }
            options.inSampleSize = sampleSize
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(picturePath, options)
        } catch (e: Exception) {
            null
        }
    }
}
