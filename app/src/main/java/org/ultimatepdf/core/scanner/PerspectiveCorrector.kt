package org.ultimatepdf.core.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.ultimatepdf.core.model.QuadCorners
import org.ultimatepdf.core.model.ScanFilter
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Flattens photographed pages and cleans them up for reading and OCR.
 */
class PerspectiveCorrector {

    /** Maps the [corners] quadrilateral to an upright rectangle (homography). */
    fun warpPerspective(bitmap: Bitmap, corners: QuadCorners): Bitmap {
        val (tl, tr, br, bl) = corners.toList()
        val width = max(hypot(tr.x - tl.x, tr.y - tl.y), hypot(br.x - bl.x, br.y - bl.y)).roundToInt().coerceAtLeast(64)
        val height = max(hypot(bl.x - tl.x, bl.y - tl.y), hypot(br.x - tr.x, br.y - tr.y)).roundToInt().coerceAtLeast(64)

        val src = MatOfPoint2f(*corners.toList().map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val dst = MatOfPoint2f(
            Point(0.0, 0.0), Point(width.toDouble(), 0.0),
            Point(width.toDouble(), height.toDouble()), Point(0.0, height.toDouble())
        )
        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val input = toMat(bitmap)
        val output = Mat()
        Imgproc.warpPerspective(input, output, transform, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        input.release()
        transform.release()
        return toBitmap(output)
    }

    /** Variance of the Laplacian of the page area normalised to ~1000 px: the classic focus measure. */
    fun sharpness(bitmap: Bitmap, corners: QuadCorners): Double {
        val xs = corners.toList().map { it.x }; val ys = corners.toList().map { it.y }
        val x0 = xs.min().toInt().coerceIn(0, bitmap.width - 2); val y0 = ys.min().toInt().coerceIn(0, bitmap.height - 2)
        val x1 = xs.max().toInt().coerceIn(x0 + 1, bitmap.width); val y1 = ys.max().toInt().coerceIn(y0 + 1, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0)
        val mat = toMat(crop)
        if (crop !== bitmap) crop.recycle()
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
        mat.release()
        val f = 1000.0 / max(gray.cols(), gray.rows())
        if (f < 1.0) Imgproc.resize(gray, gray, Size(), f, f, Imgproc.INTER_AREA)
        val lap = Mat()
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)
        val mean = org.opencv.core.MatOfDouble(); val std = org.opencv.core.MatOfDouble()
        Core.meanStdDev(lap, mean, std)
        val v = std.toArray()[0].let { it * it }
        listOf(gray, lap, mean, std).forEach { it.release() }
        return v
    }

    fun applyFilter(src: Bitmap, filter: ScanFilter, intensity: Float = 0.5f): Bitmap {
        if (filter == ScanFilter.ORIGINAL) return src
        val rgb = toMat(src)
        val result = when (filter) {
            ScanFilter.MAGIC_COLOR -> magicColor(rgb, intensity)
            ScanFilter.NO_SHADOW -> noShadow(rgb, intensity)
            ScanFilter.GRAYSCALE -> grayscale(rgb, intensity)
            ScanFilter.BLACK_WHITE -> blackAndWhite(rgb, intensity)
            ScanFilter.ORIGINAL -> rgb
        }
        if (result !== rgb) rgb.release()
        return toBitmap(result)
    }

    /**
     * Splits a photographed book spread into its two pages at the spine: the darkest
     * column (the gutter's shadow) within the middle third of the image, and then applies
     * cylindrical dewarping to flatten the paper curvature near the spine.
     */
    fun splitBookSpread(spread: Bitmap): Pair<Bitmap, Bitmap> {
        val rgb = toMat(spread)
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        rgb.release()
        val columns = Mat()
        Core.reduce(gray, columns, 0, Core.REDUCE_AVG, CvType.CV_32F)
        val from = spread.width / 3
        val to = spread.width * 2 / 3
        var spine = spread.width / 2
        var darkest = Double.MAX_VALUE
        for (x in from until to) {
            // Smooth over a few columns so a single dark text column doesn't win.
            var sum = 0.0
            var n = 0
            for (dx in -6..6) {
                val c = x + dx
                if (c in 0 until spread.width) { sum += columns.get(0, c)[0]; n++ }
            }
            val value = sum / n
            if (value < darkest) { darkest = value; spine = x }
        }
        gray.release()
        columns.release()
        val left = Bitmap.createBitmap(spread, 0, 0, spine, spread.height)
        val right = Bitmap.createBitmap(spread, spine, 0, spread.width - spine, spread.height)
        val dewarpedLeft = dewarpPage(left, isRightPage = false)
        val dewarpedRight = dewarpPage(right, isRightPage = true)
        if (dewarpedLeft !== left) left.recycle()
        if (dewarpedRight !== right) right.recycle()
        return dewarpedLeft to dewarpedRight
    }

    /**
     * Dewarp a book page using a cylindrical surface model.
     * Flattens curvature and horizontal compression towards the book spine.
     */
    fun dewarpPage(page: Bitmap, isRightPage: Boolean): Bitmap {
        val mat = toMat(page)
        val dewarpedMat = dewarpCylinder(mat, isRightPage)
        mat.release()
        return toBitmap(dewarpedMat)
    }

    /**
     * Cylinder dewarping: flattens the curved paper surface near the book spine.
     * For the left page, the spine is on the right edge; for the right page, on the left edge.
     * A non-linear remapping expands horizontally compressed text lines and straightens
     * the vertical gutter curvature.
     */
    fun dewarpCylinder(src: Mat, isRightPage: Boolean): Mat {
        val w = src.cols()
        val h = src.rows()
        if (w < 32 || h < 32) return src.clone()

        val mapX = Mat(h, w, CvType.CV_32F)
        val mapY = Mat(h, w, CvType.CV_32F)

        val mapXArray = FloatArray(w * h)
        val mapYArray = FloatArray(w * h)

        val curveWidth = (w * 0.38f).coerceAtLeast(10f)

        for (y in 0 until h) {
            val normY = (y - h / 2f) / (h / 2f)
            val rowOffset = y * w
            for (x in 0 until w) {
                val distToSpine = if (isRightPage) x.toFloat() else (w - 1 - x).toFloat()
                var srcX = x.toFloat()
                var srcY = y.toFloat()

                if (distToSpine < curveWidth) {
                    val t = (distToSpine / curveWidth).coerceIn(0f, 1f)
                    val compressionFactor = Math.pow(t.toDouble(), 1.45).toFloat()
                    val newDist = compressionFactor * curveWidth

                    srcX = if (isRightPage) newDist else (w - 1f - newDist)

                    val sagFactor = (1f - t) * (1f - t) * 0.05f * h
                    srcY = y + normY * sagFactor
                }

                mapXArray[rowOffset + x] = srcX.coerceIn(0f, (w - 1).toFloat())
                mapYArray[rowOffset + x] = srcY.coerceIn(0f, (h - 1).toFloat())
            }
        }

        mapX.put(0, 0, mapXArray)
        mapY.put(0, 0, mapYArray)

        val dst = Mat()
        Imgproc.remap(src, dst, mapX, mapY, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)

        mapX.release()
        mapY.release()
        return dst
    }

    /**
     * Places both sides of an ID card at real size (85.6 × 54 mm, ISO/IEC 7810 ID-1) on one
     * A4 page at 300 dpi, front above back, like a photocopy.
     */
    fun composeIdCard(front: Bitmap, back: Bitmap): Bitmap {
        val page = Bitmap.createBitmap(A4_WIDTH_PX, A4_HEIGHT_PX, Bitmap.Config.ARGB_8888)
        page.eraseColor(Color.WHITE)
        val canvas = Canvas(page)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val cardW = mmToPx(85.6f)
        val cardH = mmToPx(54f)
        listOf(front to 0.30f, back to 0.62f).forEach { (side, centerY) ->
            // Fit the side within the card size, keeping its aspect ratio.
            val scale = min(cardW / side.width, cardH / side.height)
            val w = side.width * scale
            val h = side.height * scale
            val left = (A4_WIDTH_PX - w) / 2f
            val top = A4_HEIGHT_PX * centerY - h / 2f
            canvas.drawBitmap(side, null, RectF(left, top, left + w, top + h), paint)
        }
        return page
    }

    // --- Filters -------------------------------------------------------------------------

    /**
     * Paper-white normalisation. Estimates the page illumination ONLY from pixels that look like
     * paper (bright, low saturation, locally brightest) using normalised convolution, then divides
     * each RGB channel by that estimate. Large uniformly coloured or dark areas are not paper, so
     * they are excluded from the estimate and keep their colour instead of being bleached to white
     * (which is what a plain max-filter background model does). Per-channel division also removes
     * the colour cast of the lighting. Returns a new 3-channel RGB mat.
     */
    private fun normalizeIllumination(rgb: Mat): Mat {
        val scale = min(1.0, 220.0 / max(rgb.cols(), rgb.rows()))
        val small = Mat()
        Imgproc.resize(rgb, small, Size(), scale, scale, Imgproc.INTER_AREA)

        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
        val hsv = Mat()
        Imgproc.cvtColor(small, hsv, Imgproc.COLOR_RGB2HSV)
        val hsvCh = mutableListOf<Mat>()
        Core.split(hsv, hsvCh)
        hsv.release()

        // Reference paper level: 90th percentile of luminance.
        val bytes = ByteArray(gray.cols() * gray.rows())
        gray.get(0, 0, bytes)
        val sorted = bytes.map { it.toInt() and 0xFF }.sorted()
        val ref = sorted[(sorted.size * 0.9).toInt().coerceIn(0, sorted.size - 1)].toDouble().coerceAtLeast(1.0)

        val bigK = (max(gray.cols(), gray.rows()) / 5).let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(3)
        val se = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(bigK.toDouble(), bigK.toDouble()))
        val localMax = Mat()
        Imgproc.dilate(gray, localMax, se)
        se.release()
        val localMax08 = Mat()
        localMax.convertTo(localMax08, CvType.CV_8U, 0.8)
        localMax.release()

        val m1 = Mat(); val m2 = Mat(); val m3 = Mat(); val mask = Mat()
        Core.compare(gray, Scalar(0.55 * ref), m1, Core.CMP_GE)
        Core.compare(hsvCh[1], Scalar(77.0), m2, Core.CMP_LT)
        Core.compare(gray, localMax08, m3, Core.CMP_GE)
        Core.bitwise_and(m1, m2, mask)
        Core.bitwise_and(mask, m3, mask)
        listOf(m1, m2, m3, localMax08, gray).forEach { it.release() }
        hsvCh.forEach { it.release() }

        val total = small.cols() * small.rows()
        if (Core.countNonZero(mask) < total * 0.01) {
            mask.release(); small.release()
            return rgb.clone()
        }

        val maskF = Mat()
        mask.convertTo(maskF, CvType.CV_32F, 1.0 / 255.0)
        val sigma = max(small.cols(), small.rows()) / 12.0
        val den = Mat()
        Imgproc.GaussianBlur(maskF, den, Size(0.0, 0.0), sigma)
        val eps = 0.02

        val smallCh = mutableListOf<Mat>()
        Core.split(small, smallCh)
        val fullCh = mutableListOf<Mat>()
        Core.split(rgb, fullCh)
        val outCh = mutableListOf<Mat>()
        for (c in 0 until 3) {
            val refC = Core.mean(smallCh[c], mask).`val`[0].coerceAtLeast(1.0)
            val f = Mat()
            smallCh[c].convertTo(f, CvType.CV_32F)
            Core.multiply(f, maskF, f)
            Imgproc.GaussianBlur(f, f, Size(0.0, 0.0), sigma)
            Core.add(f, Scalar(eps * refC), f)
            val d = Mat()
            Core.add(den, Scalar(eps), d)
            Core.divide(f, d, f)
            d.release()

            val illum = Mat()
            Imgproc.resize(f, illum, Size(rgb.cols().toDouble(), rgb.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            f.release()
            Core.max(illum, Scalar(40.0), illum)
            val gain = Mat()
            Core.divide(255.0, illum, gain)
            illum.release()

            val chF = Mat()
            fullCh[c].convertTo(chF, CvType.CV_32F)
            Core.multiply(chF, gain, chF)
            gain.release()
            val o = Mat()
            chF.convertTo(o, CvType.CV_8U)
            chF.release()
            outCh.add(o)
        }
        val out = Mat()
        Core.merge(outCh, out)
        listOf(mask, maskF, den, small).forEach { it.release() }
        (smallCh + fullCh + outCh).forEach { it.release() }
        return out
    }

    /** Gentle white-clip / contrast curve; near-white snaps to pure white, mid tones keep their level. */
    private fun curveLut(whitePoint: Int, blackPoint: Int, gamma: Double): Mat {
        val lut = ByteArray(256)
        for (i in 0..255) {
            val t = ((i - blackPoint).toDouble() / (whitePoint - blackPoint)).coerceIn(0.0, 1.0)
            lut[i] = (255.0 * Math.pow(t, gamma)).toInt().coerceIn(0, 255).toByte()
        }
        val m = Mat(1, 256, CvType.CV_8U)
        m.put(0, 0, lut)
        return m
    }

    /** Light unsharp mask applied on luminance only so colours are untouched. */
    private fun sharpenLuma(rgb: Mat, amount: Double): Mat {
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val ch = mutableListOf<Mat>()
        Core.split(lab, ch)
        val blur = Mat()
        Imgproc.GaussianBlur(ch[0], blur, Size(0.0, 0.0), 1.1)
        val sharp = Mat()
        Core.addWeighted(ch[0], 1.0 + amount, blur, -amount, 0.0, sharp)
        blur.release()
        ch[0].release()
        ch[0] = sharp
        Core.merge(ch, lab)
        ch.forEach { it.release() }
        val out = Mat()
        Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2RGB)
        lab.release()
        return out
    }

    /**
     * Magic Color: per-channel paper-white normalisation, a gentle contrast curve applied equally to
     * R/G/B (keeps hues and flat colour fills intact), a light saturation lift and luma-only sharpening.
     */
    private fun magicColor(rgb: Mat, intensity: Float): Mat {
        val norm = normalizeIllumination(rgb)
        val lut = curveLut(whitePoint = (250 - 10 * intensity).toInt(), blackPoint = (6 + 14 * intensity).toInt(), gamma = 1.0 + 0.18 * intensity)
        val toned = Mat()
        Core.LUT(norm, lut, toned)
        lut.release()
        norm.release()

        val hsv = Mat()
        Imgproc.cvtColor(toned, hsv, Imgproc.COLOR_RGB2HSV)
        toned.release()
        val ch = mutableListOf<Mat>()
        Core.split(hsv, ch)
        Core.multiply(ch[1], Scalar(1.04 + 0.18 * intensity), ch[1])
        Core.merge(ch, hsv)
        ch.forEach { it.release() }
        val colored = Mat()
        Imgproc.cvtColor(hsv, colored, Imgproc.COLOR_HSV2RGB)
        hsv.release()

        val out = sharpenLuma(colored, 0.4 + 0.2 * intensity)
        colored.release()
        return out
    }

    /** Evens out lighting and shadows only; colours and contrast are left as photographed. */
    private fun noShadow(rgb: Mat, intensity: Float): Mat {
        val norm = normalizeIllumination(rgb)
        val lut = curveLut(whitePoint = (252 - 8 * intensity).toInt(), blackPoint = 0, gamma = 1.0)
        val out = Mat()
        Core.LUT(norm, lut, out)
        lut.release()
        norm.release()
        return out
    }

    private fun grayscale(rgb: Mat, intensity: Float): Mat {
        val norm = normalizeIllumination(rgb)
        val gray = Mat()
        Imgproc.cvtColor(norm, gray, Imgproc.COLOR_RGB2GRAY)
        norm.release()
        val lut = curveLut(whitePoint = (248 - 12 * intensity).toInt(), blackPoint = (6 + 20 * intensity).toInt(), gamma = 1.0 + 0.2 * intensity)
        val tone = Mat()
        Core.LUT(gray, lut, tone)
        lut.release()
        gray.release()

        val blur = Mat()
        Imgproc.GaussianBlur(tone, blur, Size(0.0, 0.0), 1.1)
        val sharp = Mat()
        Core.addWeighted(tone, 1.4, blur, -0.4, 0.0, sharp)
        blur.release()
        tone.release()
        return sharp
    }

    /**
     * Binarization tuned to preserve faint pencil, accents, and thin lines while keeping paper
     * pristine white. Adaptive threshold for fine ink, OR-ed with a global dark cut so large solid
     * dark areas stay black instead of turning into hollow outlines.
     */
    private fun blackAndWhite(rgb: Mat, intensity: Float): Mat {
        val norm = normalizeIllumination(rgb)
        val gray = Mat()
        Imgproc.cvtColor(norm, gray, Imgproc.COLOR_RGB2GRAY)
        norm.release()

        val smoothed = Mat()
        Imgproc.GaussianBlur(gray, smoothed, Size(3.0, 3.0), 0.0)
        gray.release()

        val window = (max(smoothed.cols(), smoothed.rows()) / 45).coerceIn(17, 45).let { if (it % 2 == 0) it + 1 else it }
        val c = 5.0 + 8.0 * intensity
        val ink = Mat()
        Imgproc.adaptiveThreshold(smoothed, ink, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, window, c)
        val dark = Mat()
        Core.compare(smoothed, Scalar(70.0 + 20.0 * intensity), dark, Core.CMP_LT)
        smoothed.release()
        Core.bitwise_not(dark, dark)
        Core.bitwise_and(ink, dark, ink)
        dark.release()
        return ink
    }

    // --- Conversions ---------------------------------------------------------------------

    private fun toMat(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false), rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        return rgb
    }

    /** Accepts 1-channel (gray) or 3-channel RGB mats; releases [mat]. */
    private fun toBitmap(mat: Mat): Bitmap {
        val rgba = Mat()
        Imgproc.cvtColor(mat, rgba, if (mat.channels() == 1) Imgproc.COLOR_GRAY2RGBA else Imgproc.COLOR_RGB2RGBA)
        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        mat.release()
        return bitmap
    }

    companion object {
        const val A4_WIDTH_PX = 2480
        const val A4_HEIGHT_PX = 3508
        private const val SAUVOLA_K = 0.2
        private const val SAUVOLA_R = 128.0

        private fun mmToPx(mm: Float) = mm / 25.4f * 300f
    }
}
