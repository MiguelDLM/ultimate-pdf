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
    TRIANGLE,
    STAR,
    CLOUD,
    CALLOUT,
    DOUBLE_ARROW,
    ARROW,
    LINE,
    STAMP,
    /** Placed handwritten signature: vector shape inside [PdfAnnotation.bounds]. */
    SIGNATURE,
    /** Permanent blackout redaction covering sensitive content. */
    REDACTION
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
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun offset(dx: Float, dy: Float): PdfRect =
        PdfRect(left + dx, top + dy, right + dx, bottom + dy)
}

/**
 * Standard PDF Annotation representation compatible with PDFBox PDAnnotation and XFDF.
 *
 * Annotations created in the reader use page-normalized coordinates: every x/y (and [quads],
 * [bounds], [inkPoints]) is a fraction 0..1 of the displayed page, with the origin at the
 * top-left corner. [strokeWidth] is likewise a fraction of the page width. They are converted
 * to PDF user space only when written to the file.
 *
 * For [AnnotationType.SIGNATURE], [inkPoints] are the signature contours relative to [bounds]
 * (0..1 inside the box) and [strokeWidth] is a fraction of the box width: 0 means the contours
 * are filled outlines (scanned signatures), > 0 means they are stroked lines (drawn ones).
 *
 * [rotation] specifies the rotation in degrees clockwise (0..360) around the center of [bounds].
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
    val quads: List<PdfRect> = emptyList(), // One rect per text line for highlight / underline / strike
    val author: String = "Ultimate PDF User",
    /** Position in the page's /Annots array when the annotation was read from the file; null if new. */
    val sourceIndex: Int? = null,
    val modifiedTimestamp: Long = System.currentTimeMillis(),
    val rotation: Float = 0f,
    val brushType: String = "ballpoint"
)

