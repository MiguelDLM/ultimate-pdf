package org.ultimatepdf.core.pdf

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ultimatepdf.core.model.PdfRect
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A single character of page text. [rect] is page-normalized (0..1, top-left origin);
 * synthetic word / line separators inserted by the stripper have a null [rect].
 */
data class TextGlyph(val text: String, val rect: PdfRect?)

/**
 * Positioned text of one page in reading order, with the hit-testing helpers that
 * text selection needs.
 */
class PageText(val glyphs: List<TextGlyph>) {

    val isEmpty: Boolean get() = glyphs.none { it.rect != null }

    /** Glyph under (x, y), or the closest one within [maxDistance] (normalized units). */
    fun hitTest(x: Float, y: Float, maxDistance: Float = 0.03f): Int? {
        val index = nearestIndex(x, y) ?: return null
        val r = glyphs[index].rect!!
        val dx = maxOf(r.left - x, 0f, x - r.right)
        val dy = maxOf(r.top - y, 0f, y - r.bottom)
        return if (hypot(dx, dy) <= maxDistance) index else null
    }

    /**
     * Closest glyph to (x, y), preferring glyphs on the text line that contains y so that
     * dragging a selection handle along a line behaves predictably.
     */
    fun nearestIndex(x: Float, y: Float): Int? {
        var best: Int? = null
        var bestScore = Float.MAX_VALUE
        glyphs.forEachIndexed { i, g ->
            val r = g.rect ?: return@forEachIndexed
            val dx = maxOf(r.left - x, 0f, x - r.right)
            val dy = maxOf(r.top - y, 0f, y - r.bottom)
            // Vertical distance weighs more: it decides the line, horizontal decides the column.
            val score = dy * 4f + dx
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    /** Index range of the word containing [index]. */
    fun wordRange(index: Int): IntRange {
        fun isWordChar(i: Int) = glyphs[i].rect != null && glyphs[i].text.isNotBlank()
        if (!isWordChar(index)) return index..index
        var start = index
        var end = index
        while (start > 0 && isWordChar(start - 1)) start--
        while (end < glyphs.lastIndex && isWordChar(end + 1)) end++
        return start..end
    }

    fun textOf(range: IntRange): String =
        range.joinToString("") { glyphs[it].text }.trim()

    /** One rectangle per visual line covered by [range]. */
    fun lineRects(range: IntRange): List<PdfRect> {
        val result = mutableListOf<PdfRect>()
        var current: PdfRect? = null
        for (i in range) {
            val r = glyphs.getOrNull(i)?.rect ?: continue
            val c = current
            current = if (c != null && sameLine(c, r)) {
                PdfRect(minOf(c.left, r.left), minOf(c.top, r.top), maxOf(c.right, r.right), maxOf(c.bottom, r.bottom))
            } else {
                c?.let(result::add)
                r
            }
        }
        current?.let(result::add)
        return result
    }

    private fun sameLine(a: PdfRect, b: PdfRect): Boolean {
        val centerA = (a.top + a.bottom) / 2f
        val centerB = (b.top + b.bottom) / 2f
        val h = minOf(a.height, b.height).coerceAtLeast(0.001f)
        return abs(centerA - centerB) < h * 0.6f && b.left >= a.left - h
    }
}

/**
 * Extracts positioned text with PDFBox so the reader can select, copy and mark up text.
 * Pages are parsed lazily and cached; one [PDDocument] is kept open per opened file.
 */
class PdfTextExtractor(private val context: Context) {

    private val mutex = Mutex()
    private var document: PDDocument? = null
    private var documentUri: Uri? = null
    private val cache = LruCache<Int, PageText>(24)

    companion object {
        private const val TAG = "PdfTextExtractor"
    }

    suspend fun pageText(uri: Uri, pageIndex: Int): PageText = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (uri != documentUri) openLocked(uri)
            cache.get(pageIndex)?.let { return@withLock it }
            val doc = document ?: return@withLock PageText(emptyList())
            val text = try {
                val collector = GlyphCollector()
                collector.sortByPosition = true
                collector.startPage = pageIndex + 1
                collector.endPage = pageIndex + 1
                collector.getText(doc)
                PageText(collector.glyphs)
            } catch (e: Exception) {
                Log.w(TAG, "Text extraction failed for page $pageIndex", e)
                PageText(emptyList())
            }
            cache.put(pageIndex, text)
            text
        }
    }

    /** Drops the parsed document, e.g. after the file was rewritten on disk. */
    suspend fun invalidate() = mutex.withLock { closeLocked() }

    private fun openLocked(uri: Uri) {
        closeLocked()
        documentUri = uri
        document = try {
            context.contentResolver.openInputStream(uri)?.use { PDDocument.load(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to parse document text", e)
            null
        }
    }

    private fun closeLocked() {
        try {
            document?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing PDDocument", e)
        }
        document = null
        documentUri = null
        cache.evictAll()
    }

    private class GlyphCollector : PDFTextStripper() {
        val glyphs = mutableListOf<TextGlyph>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            for (tp in textPositions) {
                val pageW = tp.pageWidth
                val pageH = tp.pageHeight
                if (pageW <= 0f || pageH <= 0f) continue
                val fontSize = if (tp.fontSizeInPt > 0f) tp.fontSizeInPt else tp.heightDir / 0.7f
                val top = tp.yDirAdj - fontSize * 0.8f
                val bottom = tp.yDirAdj + fontSize * 0.22f
                glyphs += TextGlyph(
                    text = tp.unicode ?: "",
                    rect = PdfRect(
                        left = tp.xDirAdj / pageW,
                        top = top / pageH,
                        right = (tp.xDirAdj + tp.widthDirAdj) / pageW,
                        bottom = bottom / pageH
                    )
                )
            }
        }

        override fun writeWordSeparator() {
            glyphs += TextGlyph(" ", null)
        }

        override fun writeLineSeparator() {
            glyphs += TextGlyph("\n", null)
        }
    }
}
