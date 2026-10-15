package org.ultimatepdf.core.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import org.ultimatepdf.core.model.QuadCorners
import org.ultimatepdf.core.model.ScanFilter
import kotlin.math.hypot
import kotlin.math.max

/**
 * Perspective Warping and Document Enhancement engine inspired by MakeACopy and FairScan.
 * Corrects angular distortions from mobile camera captures and applies document document enhancement filters.
 */
class PerspectiveCorrector {

    /**
     * Warps a quad corner selection into a flat rectangular document image.
     */
    fun warpPerspective(bitmap: Bitmap, corners: QuadCorners): Bitmap {
        val tl = corners.topLeft
        val tr = corners.topRight
        val br = corners.bottomRight
        val bl = corners.bottomLeft

        // Calculate expected output dimensions
        val topWidth = hypot((tr.x - tl.x).toDouble(), (tr.y - tl.y).toDouble())
        val bottomWidth = hypot((br.x - bl.x).toDouble(), (br.y - bl.y).toDouble())
        val targetWidth = max(topWidth, bottomWidth).toInt().coerceAtLeast(100)

        val leftHeight = hypot((bl.x - tl.x).toDouble(), (bl.y - tl.y).toDouble())
        val rightHeight = hypot((br.x - tr.x).toDouble(), (br.y - tr.y).toDouble())
        val targetHeight = max(leftHeight, rightHeight).toInt().coerceAtLeast(100)

        val srcPoints = floatArrayOf(
            tl.x, tl.y,
            tr.x, tr.y,
            br.x, br.y,
            bl.x, bl.y
        )

        val dstPoints = floatArrayOf(
            0f, 0f,
            targetWidth.toFloat(), 0f,
            targetWidth.toFloat(), targetHeight.toFloat(),
            0f, targetHeight.toFloat()
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Inverse mapping draw
        val inverseMatrix = Matrix()
        matrix.invert(inverseMatrix)

        canvas.concat(matrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        return output
    }

    /**
     * Applies enhancement filters (Magic Color, Grayscale, High-contrast B&W).
     */
    fun applyFilter(src: Bitmap, filter: ScanFilter): Bitmap {
        return when (filter) {
            ScanFilter.ORIGINAL -> src

            ScanFilter.GRAYSCALE -> {
                val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                val paint = Paint()
                val cm = ColorMatrix().apply { setSaturation(0f) }
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(src, 0f, 0f, paint)
                out
            }

            ScanFilter.MAGIC_COLOR -> {
                // Boost contrast & slight saturation for readable text and vivid stamps
                val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                val paint = Paint()

                val contrast = 1.35f
                val brightness = -10f
                val cm = ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, brightness,
                        0f, contrast, 0f, 0f, brightness,
                        0f, 0f, contrast, 0f, brightness,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(src, 0f, 0f, paint)
                out
            }

            ScanFilter.BLACK_WHITE -> {
                // High-contrast clean black-and-white thresholding
                val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(src.width * src.height)
                src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)

                // Calculate average luminance threshold
                var sum = 0L
                for (p in pixels) {
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    sum += (r * 299 + g * 587 + b * 114) / 1000
                }
                val threshold = (sum / pixels.size).coerceIn(90L, 160L).toInt()

                for (i in pixels.indices) {
                    val p = pixels[i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val lum = (r * 299 + g * 587 + b * 114) / 1000
                    pixels[i] = if (lum > threshold) -0x1 else -0x1000000 // White or Black
                }
                out.setPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
                out
            }
        }
    }
}
