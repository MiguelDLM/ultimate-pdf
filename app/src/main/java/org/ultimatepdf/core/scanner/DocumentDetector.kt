package org.ultimatepdf.core.scanner

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.ultimatepdf.core.model.QuadCorners
import kotlin.math.max

/**
 * Finds the four corners of a sheet of paper in a photo or camera frame.
 *
 * Edges (Canny with thresholds derived from the image's median) are closed into contours and
 * the largest convex 4-point polygon wins. White paper on a light desk often has weak edges,
 * so a second pass segments the bright paper with Otsu's threshold.
 */
class DocumentDetector {

    /** Corners in [bitmap] pixels, or null when no sheet-like shape is found. */
    fun detect(bitmap: Bitmap): QuadCorners? {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        return detectInGray(gray).also { gray.release() }
    }

    /**
     * Corners in [gray]'s pixels (any size; it is downscaled internally). Used directly on the
     * camera's luminance plane for live detection.
     */
    fun detectInGray(gray: Mat): QuadCorners? {
        val scale = WORK_SIZE / max(gray.cols(), gray.rows()).toDouble()
        val small = Mat()
        Imgproc.resize(gray, small, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(small, small, Size(5.0, 5.0), 0.0)
        val minArea = small.rows() * small.cols() * MIN_AREA_RATIO

        val quad = findQuad(edges(small), minArea) ?: findQuad(otsu(small), minArea)
        small.release()
        return quad?.let { points ->
            orderCorners(points.map { PointF((it.x / scale).toFloat(), (it.y / scale).toFloat()) })
                .clamped(gray.cols().toFloat(), gray.rows().toFloat())
        }
    }

    private fun edges(small: Mat): Mat {
        val median = median(small)
        val edges = Mat()
        Imgproc.Canny(small, edges, max(10.0, 0.66 * median), max(30.0, 1.33 * median))
        // Close small gaps so the sheet outline becomes one contour.
        Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)), org.opencv.core.Point(-1.0, -1.0), 2)
        return edges
    }

    private fun otsu(small: Mat): Mat {
        val mask = Mat()
        Imgproc.threshold(small, mask, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0)))
        return mask
    }

    private fun findQuad(binary: Mat, minArea: Double): List<org.opencv.core.Point>? {
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(binary, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        binary.release()
        val candidates = contours
            .map { it to Imgproc.contourArea(it) }
            .filter { it.second >= minArea }
            .sortedByDescending { it.second }
            .take(8)
        for ((contour, _) in candidates) {
            val curve = MatOfPoint2f(*contour.toArray())
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(curve, approx, 0.02 * Imgproc.arcLength(curve, true), true)
            val points = approx.toArray()
            if (points.size == 4 && Imgproc.isContourConvex(MatOfPoint(*points))) return points.toList()
        }
        // No clean quadrilateral: take the rotated bounding box of the biggest shape.
        val biggest = candidates.firstOrNull()?.first ?: return null
        val box = Imgproc.minAreaRect(MatOfPoint2f(*biggest.toArray()))
        val corners = arrayOfNulls<org.opencv.core.Point>(4)
        box.points(corners)
        return corners.filterNotNull()
    }

    private fun median(gray: Mat): Double {
        val hist = Mat()
        Imgproc.calcHist(listOf(gray), MatOfInt(0), Mat(), hist, MatOfInt(256), org.opencv.core.MatOfFloat(0f, 256f))
        val total = gray.total()
        var cumulative = 0.0
        for (i in 0 until 256) {
            cumulative += hist.get(i, 0)[0]
            if (cumulative >= total / 2.0) return i.toDouble().also { hist.release() }
        }
        hist.release()
        return 127.0
    }

    /**
     * Orders 4 points clockwise: [TL, TR, BR, BL]. Top-left has the smallest x + y and
     * bottom-right the largest; top-right has the smallest y − x and bottom-left the largest.
     * This holds even for sheets turned up to ~45°, where sorting by y alone mixes corners up.
     */
    fun orderCorners(points: List<PointF>): QuadCorners {
        if (points.size != 4) throw IllegalArgumentException("Expected 4 points, got ${points.size}")
        val bySum = points.sortedBy { it.x + it.y }
        val byDiff = points.sortedBy { it.y - it.x }
        val ordered = QuadCorners(bySum.first(), byDiff.first(), bySum.last(), byDiff.last())
        if (ordered.toList().distinctBy { it.x to it.y }.size == 4) return ordered

        // Degenerate shapes can pick a point twice; fall back to sorting by y.
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

    private companion object {
        /** Detection runs on images this big (longest side); plenty for corners, and fast. */
        const val WORK_SIZE = 640.0
        /** A sheet must cover at least this share of the frame. */
        const val MIN_AREA_RATIO = 0.12
    }
}
