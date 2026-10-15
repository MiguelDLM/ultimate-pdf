package org.ultimatepdf.core.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ultimatepdf.core.model.SignatureShape
import kotlin.math.max
import kotlin.math.roundToInt

/** Bitmap plumbing around [SignatureVectorizer]. */
object SignatureImages {

    /** Decodes a photo at most [maxSize] px on its long side, upright according to its EXIF data. */
    suspend fun loadUpright(context: Context, uri: Uri, maxSize: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@withContext null
        val orientation = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (e: Exception) {
            null
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) bitmap else Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true
        )
    }

    /** Luminance of [bitmap] inside the normalized [crop], scaled so the long side is ≤ [workSize]. */
    fun grayscale(bitmap: Bitmap, crop: FloatArray, workSize: Int): Triple<IntArray, Int, Int> {
        val left = (crop[0] * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (crop[1] * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (crop[2] * bitmap.width).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (crop[3] * bitmap.height).roundToInt().coerceIn(top + 1, bitmap.height)
        val cw = right - left
        val ch = bottom - top
        val scale = minOf(1f, workSize.toFloat() / max(cw, ch))
        val w = (cw * scale).roundToInt().coerceAtLeast(1)
        val h = (ch * scale).roundToInt().coerceAtLeast(1)
        val region = Bitmap.createBitmap(bitmap, left, top, cw, ch)
        val scaled = if (w == cw && h == ch) region else Bitmap.createScaledBitmap(region, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h) { i ->
            val c = pixels[i]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        return Triple(gray, w, h)
    }

    suspend fun detectSignatureArea(bitmap: Bitmap, sensitivity: Float): FloatArray? = withContext(Dispatchers.Default) {
        val (gray, w, h) = grayscale(bitmap, floatArrayOf(0f, 0f, 1f, 1f), 600)
        SignatureVectorizer.detectSignatureArea(gray, w, h, sensitivity)
    }

    suspend fun vectorize(bitmap: Bitmap, crop: FloatArray, sensitivity: Float): SignatureShape? = withContext(Dispatchers.Default) {
        val (gray, w, h) = grayscale(bitmap, crop, SignatureVectorizer.WORK_SIZE)
        SignatureVectorizer.vectorize(gray, w, h, sensitivity)
    }
}
