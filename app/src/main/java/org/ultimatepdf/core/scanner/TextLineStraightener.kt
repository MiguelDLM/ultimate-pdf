package org.ultimatepdf.core.scanner

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Flattens bent, curled, folded or wavy pages by straightening their lines of text.
 *
 * The perspective warp assumes a flat sheet; anything that isn't flat leaves curved text lines.
 * This finds those lines, measures how far each one bends away from straight, interpolates that
 * vertical displacement across the whole page and remaps the image so every line becomes straight.
 *
 * It only corrects vertical displacement (what makes lines look curved). Horizontal
 * foreshortening on steep curls (letters squeezed towards a book spine) cannot be recovered
 * from a single photo without a 3D model, so it is left as is.
 *
 * Needs text: pages with fewer than [MIN_LINES] usable lines are returned unchanged (null).
 */
internal object TextLineStraightener {

    private const val WORK_SIZE = 1000.0
    private const val MIN_LINES = 4
    private const val GRID_W = 48
    private const val GRID_H = 64

    private class Line(val xs: FloatArray, val ys: FloatArray) {
        val target = ys.average().toFloat()
        val x0 = xs.first()
        val x1 = xs.last()
        fun yAt(x: Float): Float = interp(x, xs, ys)
    }

    /** Returns a straightened copy of [src] (RGB), or null when there isn't enough text to measure. */
    fun straighten(src: Mat): Mat? {
        val fullW = src.cols()
        val fullH = src.rows()
        val scale = min(1.0, WORK_SIZE / max(fullW, fullH))
        val small = Mat()
        Imgproc.resize(src, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
        small.release()

        val lines = detectLines(gray)
        val w = gray.cols()
        val h = gray.rows()
        gray.release()
        if (lines.size < MIN_LINES) return null

        val grid = displacementGrid(lines, w, h) ?: return null

        // Upscale the coarse displacement field to full resolution (and to full-size pixels).
        val d = Mat()
        Imgproc.resize(grid, d, Size(fullW.toDouble(), fullH.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
        grid.release()
        Core.multiply(d, org.opencv.core.Scalar(1.0 / scale), d)

        val col = Mat(fullH, 1, CvType.CV_32F)
        col.put(0, 0, FloatArray(fullH) { it.toFloat() })
        val yIdx = Mat()
        Core.repeat(col, 1, fullW, yIdx)
        col.release()
        Core.add(d, yIdx, d) // d is now mapY
        yIdx.release()

        val row = Mat(1, fullW, CvType.CV_32F)
        row.put(0, 0, FloatArray(fullW) { it.toFloat() })
        val mapX = Mat()
        Core.repeat(row, fullH, 1, mapX)
        row.release()

        val out = Mat()
        Imgproc.remap(src, out, mapX, d, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        mapX.release()
        d.release()
        return out
    }

    /** Text lines as sampled centre lines (x ascending), in [gray] coordinates. */
    private fun detectLines(gray: Mat): List<Line> {
        val w = gray.cols()
        val h = gray.rows()
        val block = max(15, max(w, h) / 40) or 1
        val ink = Mat()
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, block, 12.0)
        val speck = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_OPEN, speck)
        speck.release()

        // Smear characters horizontally so each line of text becomes one blob.
        val kx = max(9, w / 45)
        val blobs = Mat()
        val closeK = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kx.toDouble(), 1.0))
        Imgproc.morphologyEx(ink, blobs, Imgproc.MORPH_CLOSE, closeK)
        closeK.release()
        val dilateK = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size((kx / 2).toDouble(), 1.0))
        Imgproc.dilate(blobs, blobs, dilateK)
        dilateK.release()

        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(blobs, labels, stats, centroids, 8, CvType.CV_32S)
        blobs.release()
        centroids.release()

        val labelArr = IntArray(w * h)
        labels.get(0, 0, labelArr)
        labels.release()
        val inkArr = ByteArray(w * h)
        ink.get(0, 0, inkArr)
        ink.release()
        val statArr = IntArray(n * 5)
        stats.get(0, 0, statArr)
        stats.release()

        val lines = mutableListOf<Line>()
        for (i in 1 until n) {
            val bx = statArr[i * 5 + Imgproc.CC_STAT_LEFT]
            val by = statArr[i * 5 + Imgproc.CC_STAT_TOP]
            val bw = statArr[i * 5 + Imgproc.CC_STAT_WIDTH]
            val bh = statArr[i * 5 + Imgproc.CC_STAT_HEIGHT]
            // Long, thin blobs only: lines of text, not pictures, rules or noise.
            if (bw < w * 0.12 || bh > h * 0.06 || bh < 3 || bw < 4 * bh) continue

            val step = max(2, bw / 40)
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            var cx = 0
            while (cx < bw) {
                var top = -1
                var bottom = -1
                for (yy in 0 until bh) {
                    val rowOff = (by + yy) * w
                    var hit = false
                    for (xx in cx until min(cx + step, bw)) {
                        val idx = rowOff + bx + xx
                        if (labelArr[idx] == i && inkArr[idx].toInt() != 0) { hit = true; break }
                    }
                    if (hit) {
                        if (top < 0) top = yy
                        bottom = yy
                    }
                }
                if (top >= 0 && bottom > top) {
                    xs += bx + cx + step / 2f
                    // Middle of the ink extent: stable against ascenders/descenders.
                    ys += by + (top + bottom) / 2f
                }
                cx += step
            }
            if (xs.size < 6) continue
            cleanLine(xs.toFloatArray(), ys.toFloatArray())?.let { lines += it }
        }
        return lines
    }

    /** Drops outliers against a cubic fit, then median + mean smooths the samples. */
    private fun cleanLine(xs: FloatArray, ys: FloatArray): Line? {
        val fit = polyfit(xs, ys, 3) ?: return null
        val residuals = FloatArray(xs.size) { ys[it] - polyval(fit, xs[it]) }
        val mean = residuals.average()
        val std = sqrt(residuals.sumOf { (it - mean) * (it - mean) } / residuals.size).toFloat()
        val limit = max(2f, 2.5f * std)
        val keep = residuals.indices.filter { abs(residuals[it]) < limit }
        if (keep.size < 6) return null
        val kx = FloatArray(keep.size) { xs[keep[it]] }
        val ky = FloatArray(keep.size) { ys[keep[it]] }

        val med = FloatArray(ky.size) { k ->
            val from = max(0, k - 2)
            val to = min(ky.size - 1, k + 2)
            val window = (from..to).map { ky[it] }.sorted()
            window[window.size / 2]
        }
        val smooth = FloatArray(med.size) { k ->
            (med[max(0, k - 1)] + med[k] + med[min(med.size - 1, k + 1)]) / 3f
        }
        return Line(kx, smooth)
    }

    /**
     * Vertical displacement (source y − straight y) on a coarse grid in [w]×[h] coordinates:
     * per grid column, interpolated between the lines that cover it; constant beyond the first
     * and last line; empty columns copied from the nearest measured one.
     */
    private fun displacementGrid(lines: List<Line>, w: Int, h: Int): Mat? {
        val grid = Array(GRID_H) { FloatArray(GRID_W) { Float.NaN } }
        val gy = FloatArray(GRID_H) { (it + 0.5f) * h / GRID_H }
        for (j in 0 until GRID_W) {
            val x = (j + 0.5f) * w / GRID_W
            val pts = lines.mapNotNull { line ->
                val margin = (line.x1 - line.x0) * 0.05f
                if (x < line.x0 - margin || x > line.x1 + margin) null
                else line.target to (line.yAt(x) - line.target)
            }.sortedBy { it.first }
            if (pts.size < 2) continue
            val ty = FloatArray(pts.size) { pts[it].first }
            val dv = FloatArray(pts.size) { pts[it].second }
            for (r in 0 until GRID_H) grid[r][j] = interp(gy[r], ty, dv)
        }
        val measured = (0 until GRID_W).filter { !grid[0][it].isNaN() }
        if (measured.size < GRID_W * 0.3) return null
        for (j in 0 until GRID_W) {
            if (!grid[0][j].isNaN()) continue
            val src = measured.minBy { abs(it - j) }
            for (r in 0 until GRID_H) grid[r][j] = grid[r][src]
        }
        val limit = 0.1f * h
        val flat = FloatArray(GRID_W * GRID_H) { grid[it / GRID_W][it % GRID_W].coerceIn(-limit, limit) }
        val mat = Mat(GRID_H, GRID_W, CvType.CV_32F)
        mat.put(0, 0, flat)
        Imgproc.GaussianBlur(mat, mat, Size(0.0, 0.0), 1.0)
        return mat
    }

    // --- Small numeric helpers -------------------------------------------------------------

    /** Linear interpolation over ascending [xs]; constant beyond the ends. */
    private fun interp(x: Float, xs: FloatArray, ys: FloatArray): Float {
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        var hi = xs.indexOfFirst { it >= x }
        if (hi <= 0) hi = 1
        val lo = hi - 1
        val span = xs[hi] - xs[lo]
        if (span <= 0f) return ys[lo]
        return ys[lo] + (ys[hi] - ys[lo]) * (x - xs[lo]) / span
    }

    /** Least-squares polynomial on x normalised to [-1, 1]; coefficients + normalisation. */
    private class Poly(val c: DoubleArray, val mid: Double, val half: Double)

    private fun polyfit(xs: FloatArray, ys: FloatArray, degree: Int): Poly? {
        val mid = (xs.first() + xs.last()) / 2.0
        val half = max(1.0, (xs.last() - xs.first()) / 2.0)
        val m = degree + 1
        val a = Array(m) { DoubleArray(m + 1) }
        for (k in xs.indices) {
            val t = (xs[k] - mid) / half
            val pw = DoubleArray(2 * m) { 1.0 }
            for (p in 1 until 2 * m) pw[p] = pw[p - 1] * t
            for (r in 0 until m) {
                for (c in 0 until m) a[r][c] += pw[r + c]
                a[r][m] += pw[r] * ys[k]
            }
        }
        // Gaussian elimination with partial pivoting.
        for (col in 0 until m) {
            val pivot = (col until m).maxBy { abs(a[it][col]) }
            if (abs(a[pivot][col]) < 1e-9) return null
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            for (r in 0 until m) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                for (c in col..m) a[r][c] -= f * a[col][c]
            }
        }
        return Poly(DoubleArray(m) { a[it][m] / a[it][it] }, mid, half)
    }

    private fun polyval(p: Poly, x: Float): Float {
        val t = (x - p.mid) / p.half
        var v = 0.0
        for (k in p.c.indices.reversed()) v = v * t + p.c[k]
        return v.toFloat()
    }
}
