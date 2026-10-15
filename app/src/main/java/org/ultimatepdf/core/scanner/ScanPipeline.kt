package org.ultimatepdf.core.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ultimatepdf.core.model.QuadCorners
import org.ultimatepdf.core.model.ScanFilter
import java.io.File
import kotlin.math.max

/** How captures become pages. */
enum class ScanMode {
    /** One page per capture (batch: capture as many as needed, process at the end). */
    DOCUMENT,
    /** Front and back of a card, combined at real size on one A4 page. */
    ID_CARD,
    /** Each capture is an open book: split into its left and right pages. */
    BOOK
}

/**
 * Turns captured photos (stored as JPEG files) into finished page images:
 * perspective → split (book) → filter → rotation.
 */
class ScanPipeline(
    private val detector: DocumentDetector = DocumentDetector(),
    private val corrector: PerspectiveCorrector = PerspectiveCorrector()
) {
    /** Detected sheet corners in [bitmap] pixels, or a slightly inset full frame. */
    fun detectCorners(bitmap: Bitmap): QuadCorners =
        runCatching { detector.detect(bitmap) }.getOrNull()?.clamped(bitmap.width.toFloat(), bitmap.height.toFloat())
            ?: QuadCorners.defaultFor(bitmap.width.toFloat(), bitmap.height.toFloat(), insetRatio = 0.04f)

    suspend fun loadImage(file: File, maxSize: Int): Bitmap? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return@withContext null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * Processes one capture. [corners] are in the coordinates of an image [sourceWidth] wide;
     * the photo is loaded at up to [maxSize] px and the corners scaled accordingly.
     */
    suspend fun process(
        file: File,
        sourceWidth: Int,
        corners: QuadCorners,
        filter: ScanFilter,
        rotation: Int,
        mode: ScanMode,
        maxSize: Int,
        intensity: Float = 0.5f
    ): List<Bitmap> = withContext(Dispatchers.Default) {
        val photo = loadImage(file, maxSize) ?: return@withContext emptyList()
        val scale = photo.width.toFloat() / sourceWidth
        val flat = corrector.warpPerspective(photo, corners.scaled(scale))
        photo.recycle()
        val parts = if (mode == ScanMode.BOOK) corrector.splitBookSpread(flat).toList() else listOf(flat)
        parts.map { part -> rotate(corrector.applyFilter(part, filter, intensity), rotation) }
    }

    fun composeIdCard(front: Bitmap, back: Bitmap): Bitmap = corrector.composeIdCard(front, back)

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap =
        if (degrees % 360 == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    private fun QuadCorners.scaled(f: Float) = QuadCorners(
        PointF(topLeft.x * f, topLeft.y * f),
        PointF(topRight.x * f, topRight.y * f),
        PointF(bottomRight.x * f, bottomRight.y * f),
        PointF(bottomLeft.x * f, bottomLeft.y * f)
    )
}
