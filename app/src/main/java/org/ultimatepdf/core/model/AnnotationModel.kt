package org.ultimatepdf.core.model

import java.util.UUID

enum class AnnotationType {
    HIGHLIGHT,
    UNDERLINE,
    STRIKETHROUGH,
    INK_PEN,
    STICKY_NOTE,
    TEXT_BOX,
    RECTANGLE,
    CIRCLE,
    ARROW,
    STAMP
}

data class PdfPoint(val x: Float, val y: Float)

data class PdfRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

/**
 * Standard PDF Annotation representation compatible with PDFBox PDAnnotation and XFDF.
 */
data class PdfAnnotation(
    val id: String = UUID.randomUUID().toString(),
    val pageIndex: Int,
    val type: AnnotationType,
    val bounds: PdfRect,
    val colorArgb: Int = 0xFFFFEB3B.toInt(), // Default yellow for highlights
    val strokeWidth: Float = 2.0f,
    val opacity: Float = 1.0f,
    val text: String = "",
    val inkPoints: List<List<PdfPoint>> = emptyList(), // For freehand pen / ink sketches
    val author: String = "Ultimate PDF User",
    val modifiedTimestamp: Long = System.currentTimeMillis()
)
