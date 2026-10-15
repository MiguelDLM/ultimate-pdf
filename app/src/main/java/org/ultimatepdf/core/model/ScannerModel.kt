package org.ultimatepdf.core.model

import android.graphics.PointF

enum class ScanFilter {
    ORIGINAL,
    /** Shadows removed, colours kept and slightly boosted. */
    MAGIC_COLOR,
    /** Uneven lighting flattened; colours otherwise untouched. */
    NO_SHADOW,
    GRAYSCALE,
    /** Adaptive (Sauvola) binarization: photocopy look. */
    BLACK_WHITE
}

data class QuadCorners(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF
) {
    fun toList(): List<PointF> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun clamped(width: Float, height: Float): QuadCorners = QuadCorners(
        topLeft = PointF(topLeft.x.coerceIn(0f, width), topLeft.y.coerceIn(0f, height)),
        topRight = PointF(topRight.x.coerceIn(0f, width), topRight.y.coerceIn(0f, height)),
        bottomRight = PointF(bottomRight.x.coerceIn(0f, width), bottomRight.y.coerceIn(0f, height)),
        bottomLeft = PointF(bottomLeft.x.coerceIn(0f, width), bottomLeft.y.coerceIn(0f, height))
    )

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

/** A recognized line of text, its box in the page image's pixels, and its words. */
data class OcrLine(val text: String, val box: PdfRect, val words: List<OcrRecognizedWord>)

/** OCR output for one page image. */
data class OcrPage(val imageWidth: Int, val imageHeight: Int, val lines: List<OcrLine>) {
    val words: List<OcrRecognizedWord> get() = lines.flatMap { it.words }
    val text: String get() = lines.joinToString("\n") { it.text }
}

data class OcrRecognizedWord(
    val text: String,
    val boundingBox: PdfRect, // In normalized 0..1 or PDF point space
    val confidence: Float = 1.0f
)
