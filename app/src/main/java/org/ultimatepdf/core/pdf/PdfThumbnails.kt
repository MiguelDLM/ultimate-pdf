package org.ultimatepdf.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Small page previews for any PDF, independent of the document open in the reader.
 * Keeps a few renderers open (one per file) and caches bitmaps by byte size.
 */
class PdfThumbnails(private val context: Context) {

    private class Source(val pfd: ParcelFileDescriptor, val renderer: PdfRenderer)

    private val mutex = Mutex()
    private val sources = object : LinkedHashMap<Uri, Source>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, Source>): Boolean {
            if (size <= MAX_OPEN) return false
            close(eldest.value)
            return true
        }
    }
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun pageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        mutex.withLock { sourceLocked(uri)?.renderer?.pageCount ?: 0 }
    }

    /** Page width / height, or null if the file can't be read. */
    suspend fun aspectRatio(uri: Uri, pageIndex: Int): Float? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val renderer = sourceLocked(uri)?.renderer ?: return@withLock null
            if (pageIndex !in 0 until renderer.pageCount) return@withLock null
            renderer.openPage(pageIndex).use { it.width.toFloat() / it.height }
        }
    }

    suspend fun render(uri: Uri, pageIndex: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$uri#$pageIndex@$widthPx"
        cache.get(key)?.let { return@withContext it }
        mutex.withLock {
            cache.get(key)?.let { return@withLock it }
            val renderer = sourceLocked(uri)?.renderer ?: return@withLock null
            if (pageIndex !in 0 until renderer.pageCount) return@withLock null
            try {
                renderer.openPage(pageIndex).use { page ->
                    val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put(key, bitmap)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Thumbnail failed for $uri page $pageIndex", e)
                null
            }
        }
    }

    /** Forgets a file, e.g. after it was rewritten. */
    suspend fun invalidate(uri: Uri) = mutex.withLock {
        sources.remove(uri)?.let(::close)
        cache.snapshot().keys.filter { it.startsWith("$uri#") }.forEach(cache::remove)
    }

    private fun sourceLocked(uri: Uri): Source? {
        sources[uri]?.let { return it }
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            Source(pfd, PdfRenderer(pfd)).also { sources[uri] = it }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open $uri", e)
            null
        }
    }

    private fun close(source: Source) {
        try {
            source.renderer.close()
            source.pfd.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing renderer", e)
        }
    }

    private companion object {
        const val TAG = "PdfThumbnails"
        const val MAX_OPEN = 6
    }
}
