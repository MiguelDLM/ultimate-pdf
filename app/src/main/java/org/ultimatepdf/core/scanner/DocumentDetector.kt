package org.ultimatepdf.core.scanner

import android.graphics.Bitmap
import android.graphics.PointF
import org.ultimatepdf.core.model.QuadCorners
import kotlin.math.sqrt

/**
 * Intelligent document boundary and corner detector inspired by MakeACopy & FairScan.
 * Analyzes image luminance and edge contrasts to find candidate document quadrilaterals.
 */
class DocumentDetector {

    /**
     * Finds the four corner points of a document within a bitmap image.
     * Orders vertices clockwise: Top-Left, Top-Right, Bottom-Right, Bottom-Left.
     */
    fun detectDocumentCorners(bitmap: Bitmap): QuadCorners {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

        // Downsample for rapid edge & luminance profiling
        val sampleW = 200
        val sampleH = 200
        val scaled = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, false)

        val pixels = IntArray(sampleW * sampleH)
        scaled.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)

        // Threshold detection: identify contrast boundaries between document page and background
        var minX = sampleW
        var maxX = 0
        var minY = sampleH
        var maxY = 0

        for (y in 0 until sampleH) {
            for (x in 0 until sampleW) {
                val pixel = pixels[y * sampleW + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val brightness = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

                // High brightness indicates document paper
                if (brightness > 130) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        // Clean up scaled bitmap
        scaled.recycle()

        // If no clear boundary detected, fallback to comfortable margins (8% inset)
        if (maxX - minX < 40 || maxY - minY < 40) {
            return QuadCorners.defaultFor(width, height)
        }

        val scaleFactorX = width / sampleW
        val scaleFactorY = height / sampleH

        val left = (minX * scaleFactorX).coerceIn(0f, width)
        val right = (maxX * scaleFactorX).coerceIn(0f, width)
        val top = (minY * scaleFactorY).coerceIn(0f, height)
        val bottom = (maxY * scaleFactorY).coerceIn(0f, height)

        return QuadCorners(
            topLeft = PointF(left, top),
            topRight = PointF(right, top),
            bottomRight = PointF(right, bottom),
            bottomLeft = PointF(left, bottom)
        )
    }

    /**
     * Orders 4 points clockwise: [TL, TR, BR, BL].
     */
    fun orderCorners(points: List<PointF>): QuadCorners {
        if (points.size != 4) throw IllegalArgumentException("Expected 4 points, got ${points.size}")

        // Sort by y-coordinate
        val sortedByY = points.sortedBy { it.y }
        val topTwo = sortedByY.take(2).sortedBy { it.x }
        val bottomTwo = sortedByY.takeLast(2).sortedBy { it.x }

        return QuadCorners(
            topLeft = topTwo[0],
            topRight = topTwo[1],
            bottomRight = bottomTwo[1],
            bottomLeft = bottomTwo[0]
        )
    }
}
