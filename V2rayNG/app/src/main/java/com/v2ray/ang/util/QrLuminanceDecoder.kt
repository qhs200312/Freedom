package com.v2ray.ang.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ResultPoint
import com.google.zxing.ResultPointCallback
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.detector.FinderPattern
import kotlin.math.floor
import kotlin.math.roundToInt

internal object QrLuminanceDecoder {
    private val reader = MultiFormatReader()
    private val baseHints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
    )
    private val denseThresholds = intArrayOf(32, 64, 96, 128, 160, 192, 224)

    @Synchronized
    fun decode(luminance: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || luminance.size.toLong() < width.toLong() * height) {
            return null
        }

        val source = PlanarYUVLuminanceSource(
            luminance,
            width,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        val resultPoints = mutableListOf<ResultPoint>()
        val hints = HashMap(baseHints).apply {
            put(DecodeHintType.NEED_RESULT_POINT_CALLBACK, ResultPointCallback(resultPoints::add))
        }
        return try {
            decodeSource(source, hints)
                ?: decodeSource(source.invert(), hints)
                ?: decodeDenseQr(source, resultPoints)
        } finally {
            reader.reset()
        }
    }

    private fun decodeSource(
        source: LuminanceSource,
        hints: Map<DecodeHintType, *>,
    ): String? {
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun decodeDenseQr(
        source: LuminanceSource,
        resultPoints: List<ResultPoint>,
    ): String? {
        val finders = resultPoints
            .filterIsInstance<FinderPattern>()
            .fold(mutableListOf<FinderPattern>()) { unique, candidate ->
                val duplicate = unique.any {
                    ResultPoint.distance(it, candidate) < maxOf(1f, candidate.estimatedModuleSize)
                }
                if (!duplicate) unique.add(candidate)
                unique
            }
        if (finders.size < 3) return null

        val selected = finders.take(3)
        val averageModuleSize = selected.map { it.estimatedModuleSize }.average().toFloat()
        if (averageModuleSize <= 0f || averageModuleSize > 4f) return null

        val ordered = arrayOf<ResultPoint>(selected[0], selected[1], selected[2])
        ResultPoint.orderBestPatterns(ordered)
        val bottomLeft = ordered[0]
        val topLeft = ordered[1]
        val topRight = ordered[2]
        val averageDistance = (
            ResultPoint.distance(topLeft, topRight) +
                ResultPoint.distance(topLeft, bottomLeft)
            ) / 2f
        val estimatedDimension = averageDistance / averageModuleSize + 7f
        val estimatedVersion = ((estimatedDimension - 17f) / 4f).roundToInt().coerceIn(1, 40)
        val versions = listOf(0, -1, 1, -2, 2, -3, 3, -4, 4)
            .map { (estimatedVersion + it).coerceIn(1, 40) }
            .distinct()

        val pureHints = HashMap(baseHints).apply {
            put(DecodeHintType.PURE_BARCODE, true)
        }
        for (version in versions) {
            val dimension = 17 + version * 4
            for (threshold in denseThresholds) {
                val rebuilt = rebuildQrGrid(
                    source = source,
                    dimension = dimension,
                    topLeft = topLeft,
                    topRight = topRight,
                    bottomLeft = bottomLeft,
                    threshold = threshold,
                )
                val text = decodeSource(rebuilt, pureHints)
                if (!text.isNullOrEmpty()) return text
            }
        }
        return null
    }

    private fun rebuildQrGrid(
        source: LuminanceSource,
        dimension: Int,
        topLeft: ResultPoint,
        topRight: ResultPoint,
        bottomLeft: ResultPoint,
        threshold: Int,
    ): PlanarYUVLuminanceSource {
        val scale = 2
        val quietZone = 4
        val outputSize = (dimension + quietZone * 2) * scale
        val output = ByteArray(outputSize * outputSize) { 0xff.toByte() }
        val input = source.matrix
        val denominator = dimension - 7f
        val horizontalX = (topRight.x - topLeft.x) / denominator
        val horizontalY = (topRight.y - topLeft.y) / denominator
        val verticalX = (bottomLeft.x - topLeft.x) / denominator
        val verticalY = (bottomLeft.y - topLeft.y) / denominator

        for (row in 0 until dimension) {
            for (column in 0 until dimension) {
                val moduleX = column - 3f
                val moduleY = row - 3f
                val sourceX = floor(
                    topLeft.x + moduleX * horizontalX + moduleY * verticalX
                ).toInt()
                val sourceY = floor(
                    topLeft.y + moduleX * horizontalY + moduleY * verticalY
                ).toInt()
                if (sourceX !in 0 until source.width || sourceY !in 0 until source.height) continue
                if (input[sourceY * source.width + sourceX].toUByte().toInt() >= threshold) continue

                val outputX = (column + quietZone) * scale
                val outputY = (row + quietZone) * scale
                val firstRow = outputY * outputSize + outputX
                val secondRow = firstRow + outputSize
                output[firstRow] = 0
                output[firstRow + 1] = 0
                output[secondRow] = 0
                output[secondRow + 1] = 0
            }
        }
        return PlanarYUVLuminanceSource(
            output,
            outputSize,
            outputSize,
            0,
            0,
            outputSize,
            outputSize,
            false,
        )
    }
}
