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
     * Estimates page illumination via morphological dilation (which replaces dark text with
     * the surrounding paper background) followed by a large Gaussian blur. Divides the channel
     * by this background estimate so shadows and lighting gradients flatten out cleanly.
     */
    private fun flattenChannel(channel: Mat): Mat {
        val kernelSize = (max(channel.cols(), channel.rows()) / 35).coerceIn(21, 65).let { if (it % 2 == 0) it + 1 else it }
        val se = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kernelSize.toDouble(), kernelSize.toDouble()))
        val background = Mat()
        Imgproc.dilate(channel, background, se)
        se.release()
        Imgproc.GaussianBlur(background, background, Size(kernelSize.toDouble(), kernelSize.toDouble()), 0.0)

        val fChannel = Mat()
        val fBg = Mat()
        channel.convertTo(fChannel, CvType.CV_32F)
        background.convertTo(fBg, CvType.CV_32F)
        Core.max(fBg, Scalar(1.0), fBg)

        val fNorm = Mat()
        Core.divide(fChannel, fBg, fNorm, 255.0)

        val out = Mat()
        fNorm.convertTo(out, CvType.CV_8U)

        listOf(fChannel, fBg, fNorm, background).forEach { it.release() }
        return out
    }

    /**
     * Magic Color:
     * 1. Illuminates and flattens paper on the Luminance channel in Lab space.
     * 2. Remaps the tone curve so paper becomes pure, uniform white (> 210 -> 255) and text is deepened.
     * 3. Sharpens text edges via unsharp masking without halos.
     * 4. In HSV space, desaturates paper background to eliminate color noise/blotches,
     *    and boosts saturation of authentic colored elements (stamps, signatures, graphics).
     */
    private fun magicColor(rgb: Mat, intensity: Float): Mat {
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val labChannels = mutableListOf<Mat>()
        Core.split(lab, labChannels)

        // Flatten luminance and apply document contrast tone curve
        val flatL = flattenChannel(labChannels[0])
        val whitePoint = (220 - 30 * intensity).toInt().coerceIn(180, 240)
        val blackPoint = (30 + 40 * intensity).toInt().coerceIn(20, 80)

        val lut = ByteArray(256)
        for (i in 0..255) {
            val v = when {
                i >= whitePoint -> 255
                i <= blackPoint -> (i * 0.75).toInt()
                else -> {
                    val t = (i - blackPoint).toFloat() / (whitePoint - blackPoint)
                    (255f * Math.pow(t.toDouble(), 1.05)).toInt().coerceIn(0, 255)
                }
            }
            lut[i] = v.toByte()
        }
        val lutMat = Mat(1, 256, CvType.CV_8U)
        lutMat.put(0, 0, lut)
        val toneL = Mat()
        Core.LUT(flatL, lutMat, toneL)
        lutMat.release()
        flatL.release()

        // Unsharp mask on Luminance for crisp text edges
        val blurL = Mat()
        Imgproc.GaussianBlur(toneL, blurL, Size(0.0, 0.0), 1.3)
        val sharpL = Mat()
        Core.addWeighted(toneL, 1.35, blurL, -0.35, 0.0, sharpL)
        blurL.release()
        toneL.release()

        labChannels[0].release()
        labChannels[0] = sharpL
        Core.merge(labChannels, lab)
        labChannels.forEach { it.release() }

        val intermediateRgb = Mat()
        Imgproc.cvtColor(lab, intermediateRgb, Imgproc.COLOR_Lab2RGB)
        lab.release()

        // Harmonize colors and desaturate paper in HSV space
        val hsv = Mat()
        Imgproc.cvtColor(intermediateRgb, hsv, Imgproc.COLOR_RGB2HSV)
        intermediateRgb.release()

        val hsvChannels = mutableListOf<Mat>()
        Core.split(hsv, hsvChannels)

        // Find paper background: High Value (bright) and Low Saturation (neutral)
        val brightMask = Mat()
        val lowSatMask = Mat()
        val paperBgMask = Mat()
        Core.compare(hsvChannels[2], Scalar(195.0), brightMask, Core.CMP_GT)
        Core.compare(hsvChannels[1], Scalar(50.0), lowSatMask, Core.CMP_LT)
        Core.bitwise_and(brightMask, lowSatMask, paperBgMask)

        // Whiten and purge chromatic noise on paper background
        hsvChannels[1].setTo(Scalar(0.0), paperBgMask)
        brightMask.release()
        lowSatMask.release()
        paperBgMask.release()

        // Boost saturation of authentic colored content (signatures, stamps, illustrations)
        val satMultiplier = 1.25 + 0.35 * intensity
        Core.multiply(hsvChannels[1], Scalar(satMultiplier), hsvChannels[1])

        val outRgb = Mat()
        Core.merge(hsvChannels, hsv)
        hsvChannels.forEach { it.release() }
        Imgproc.cvtColor(hsv, outRgb, Imgproc.COLOR_HSV2RGB)
        hsv.release()
        return outRgb
    }

    private fun noShadow(rgb: Mat, intensity: Float): Mat {
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val channels = mutableListOf<Mat>()
        Core.split(lab, channels)

        val flatL = flattenChannel(channels[0])
        val whiteCutoff = (235 - 20 * intensity).toInt().coerceIn(200, 245)
        val lut = ByteArray(256)
        for (i in 0..255) {
            lut[i] = if (i >= whiteCutoff) 255.toByte() else ((i.toFloat() / whiteCutoff) * 255f).toInt().toByte()
        }
        val lutMat = Mat(1, 256, CvType.CV_8U)
        lutMat.put(0, 0, lut)
        val outL = Mat()
        Core.LUT(flatL, lutMat, outL)
        lutMat.release()
        flatL.release()

        channels[0].release()
        channels[0] = outL
        Core.merge(channels, lab)
        channels.forEach { it.release() }

        val outRgb = Mat()
        Imgproc.cvtColor(lab, outRgb, Imgproc.COLOR_Lab2RGB)
        lab.release()
        return outRgb
    }

    private fun grayscale(rgb: Mat, intensity: Float): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val flat = flattenChannel(gray)
        gray.release()

        val whitePoint = (225 - 30 * intensity).toInt().coerceIn(185, 240)
        val blackPoint = (30 + 35 * intensity).toInt().coerceIn(20, 80)
        val lut = ByteArray(256)
        for (i in 0..255) {
            val v = when {
                i >= whitePoint -> 255
                i <= blackPoint -> (i * 0.75).toInt()
                else -> {
                    val t = (i - blackPoint).toFloat() / (whitePoint - blackPoint)
                    (255f * Math.pow(t.toDouble(), 1.05)).toInt().coerceIn(0, 255)
                }
            }
            lut[i] = v.toByte()
        }
        val lutMat = Mat(1, 256, CvType.CV_8U)
        lutMat.put(0, 0, lut)
        val tone = Mat()
        Core.LUT(flat, lutMat, tone)
        lutMat.release()
        flat.release()

        val blur = Mat()
        Imgproc.GaussianBlur(tone, blur, Size(0.0, 0.0), 1.2)
        val sharp = Mat()
        Core.addWeighted(tone, 1.3, blur, -0.3, 0.0, sharp)
        blur.release()
        tone.release()
        return sharp
    }

    /**
     * Binarization tuned to preserve faint pencil, accents, and thin lines while
     * keeping paper pristine white. Uses background normalization, subtle noise suppression,
     * and adaptive thresholding controlled by [intensity].
     */
    private fun blackAndWhite(rgb: Mat, intensity: Float): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val flat = flattenChannel(gray)
        gray.release()

        val smoothed = Mat()
        Imgproc.GaussianBlur(flat, smoothed, Size(3.0, 3.0), 0.0)
        flat.release()

        val window = (max(smoothed.cols(), smoothed.rows()) / 45).coerceIn(17, 45).let { if (it % 2 == 0) it + 1 else it }
        val c = 5.0 + 8.0 * intensity
        val ink = Mat()
        Imgproc.adaptiveThreshold(smoothed, ink, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, window, c)
        smoothed.release()
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
