package org.ultimatepdf.ui.sign

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import org.ultimatepdf.core.model.PdfPoint
import org.ultimatepdf.core.model.SignatureShape

/**
 * Draws signature contours (normalized to their box) into [box]. Filled outlines use the
 * even-odd rule so holes in letters stay open; strokes are [strokeWidth] × box width.
 */
fun DrawScope.drawSignature(
    contours: List<List<PdfPoint>>,
    filled: Boolean,
    strokeWidth: Float,
    box: Rect,
    color: Color
) {
    val path = Path().apply {
        fillType = if (filled) PathFillType.EvenOdd else PathFillType.NonZero
        for (contour in contours) {
            if (contour.size < 2) continue
            moveTo(box.left + contour[0].x * box.width, box.top + contour[0].y * box.height)
            for (i in 1 until contour.size) {
                lineTo(box.left + contour[i].x * box.width, box.top + contour[i].y * box.height)
            }
            if (filled) close()
        }
    }
    if (filled) {
        drawPath(path, color)
    } else {
        drawPath(
            path,
            color,
            style = Stroke(width = strokeWidth * box.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/** A signature scaled to fit and centred in the available space. */
@Composable
fun SignaturePreview(shape: SignatureShape, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val pad = size.minDimension * 0.08f
        val availW = size.width - pad * 2
        val availH = size.height - pad * 2
        if (availW <= 0f || availH <= 0f) return@Canvas
        val w = minOf(availW, availH * shape.aspect)
        val h = w / shape.aspect
        val box = Rect((size.width - w) / 2f, (size.height - h) / 2f, (size.width + w) / 2f, (size.height + h) / 2f)
        drawSignature(shape.contours, shape.filled, shape.strokeWidth, box, color)
    }
}
