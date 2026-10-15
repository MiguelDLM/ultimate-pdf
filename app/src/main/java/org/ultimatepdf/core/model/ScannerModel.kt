package org.ultimatepdf.core.model

import android.graphics.Bitmap
import android.graphics.PointF

enum class ScanFilter {
    ORIGINAL,
    MAGIC_COLOR,
    GRAYSCALE,
    BLACK_WHITE
}

data class QuadCorners(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF
) {
    fun toList(): List<PointF> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    companion object {
        fun defaultFor(width: Float, height: Float, insetRatio: Float = 0.08f): QuadCorners {
            val insetX = width * insetRatio
            val insetY = height * insetRatio
            return QuadCorners(
                topLeft = PointF(insetX, insetY),
                topRight = PointF(width - insetX, insetY),
                bottomRight = PointF(width - insetX, height - insetY),
                bottomLeft = PointF(insetX, height - insetY)
            )
        }
    }
}

data class OcrRecognizedWord(
    val text: String,
    val boundingBox: PdfRect, // In normalized 0..1 or PDF point space
    val confidence: Float = 1.0f
)

data class ScannedPage(
    val id: String,
    val rawImage: Bitmap,
    val corners: QuadCorners,
    val processedImage: Bitmap? = null,
    val filter: ScanFilter = ScanFilter.MAGIC_COLOR,
    val ocrWords: List<OcrRecognizedWord> = emptyList()
)
