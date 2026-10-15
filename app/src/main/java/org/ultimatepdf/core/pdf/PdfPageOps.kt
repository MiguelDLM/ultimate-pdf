package org.ultimatepdf.core.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/** One page of one file. */
data class PageRef(val uri: Uri, val pageIndex: Int)

/** How a document is split. */
sealed interface SplitMode {
    data class EveryNPages(val pages: Int) : SplitMode
    data class IntoParts(val parts: Int) : SplitMode
    data class MaxSize(val bytes: Long) : SplitMode
}

sealed interface SplitPlan {
    data class Parts(val ranges: List<IntRange>) : SplitPlan
    /** A single page is already bigger than the size limit. */
    data class PageTooLarge(val pageIndex: Int, val bytes: Long) : SplitPlan
    data object Invalid : SplitPlan
}

/** Pure split arithmetic, kept separate from PDF I/O so it can be unit tested. */
object SplitPlanner {

    fun everyNPages(pageCount: Int, n: Int): SplitPlan =
        if (n < 1 || pageCount < 1) SplitPlan.Invalid
        else SplitPlan.Parts((0 until pageCount).chunked(n).map { it.first()..it.last() })

    /** [parts] pieces as even as possible; the first ones take the remainder. */
    fun intoParts(pageCount: Int, parts: Int): SplitPlan {
        if (parts < 1 || pageCount < 1 || parts > pageCount) return SplitPlan.Invalid
        val base = pageCount / parts
        val extra = pageCount % parts
        var start = 0
        return SplitPlan.Parts((0 until parts).map { i ->
            val size = base + if (i < extra) 1 else 0
            (start until start + size).also { start += size }
        })
    }

    /**
     * Greedy grouping of consecutive pages so each part's estimated size stays within [limit].
     * [pageSizes] are standalone page sizes, which over-estimate a part (shared fonts and
     * images are counted once per page), so the plan errs on the safe side.
     */
    fun bySize(pageSizes: List<Long>, limit: Long): SplitPlan {
        if (limit <= 0 || pageSizes.isEmpty()) return SplitPlan.Invalid
        pageSizes.forEachIndexed { i, size -> if (size > limit) return SplitPlan.PageTooLarge(i, size) }
        val ranges = mutableListOf<IntRange>()
        var start = 0
        var sum = 0L
        pageSizes.forEachIndexed { i, size ->
            if (i > start && sum + size > limit) {
                ranges += start until i
                start = i
                sum = 0
            }
            sum += size
        }
        ranges += start until pageSizes.size
        return SplitPlan.Parts(ranges)
    }
}

/** Page-level document operations: compose (merge / extract) and split. */
class PdfPageOps(private val context: Context) {

    /** Writes [pages], in order, as a new PDF. Pages may come from several files. */
    suspend fun compose(pages: List<PageRef>, output: File) = withContext(Dispatchers.IO) {
        require(pages.isNotEmpty())
        val sources = mutableMapOf<Uri, PDDocument>()
        try {
            PDDocument().use { target ->
                for (ref in pages) {
                    coroutineContext.ensureActive()
                    val source = sources.getOrPut(ref.uri) { load(ref.uri) }
                    target.importPage(source.getPage(ref.pageIndex))
                }
                output.outputStream().use { target.save(it) }
            }
        } finally {
            sources.values.forEach { runCatching { it.close() } }
        }
    }

    /** Size in bytes each page would have as a standalone PDF. */
    suspend fun pageSizes(uri: Uri, onProgress: (Float) -> Unit = {}): List<Long> = withContext(Dispatchers.IO) {
        load(uri).use { doc ->
            (0 until doc.numberOfPages).map { i ->
                coroutineContext.ensureActive()
                onProgress(i.toFloat() / doc.numberOfPages)
                PDDocument().use { single ->
                    single.importPage(doc.getPage(i))
                    val counter = CountingStream()
                    single.save(counter)
                    counter.count
                }
            }
        }
    }

    /**
     * Writes each range as its own file in [outputDir]. With [maxBytes], a written part that
     * still exceeds the limit is halved until it fits (single pages were validated upfront).
     */
    suspend fun split(
        uri: Uri,
        ranges: List<IntRange>,
        outputDir: File,
        baseName: String,
        maxBytes: Long? = null,
        onProgress: (Float) -> Unit = {}
    ): List<File> = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        load(uri).use { doc ->
            val queue = ArrayDeque(ranges)
            val written = mutableListOf<Pair<IntRange, File>>()
            while (queue.isNotEmpty()) {
                coroutineContext.ensureActive()
                val range = queue.removeFirst()
                val file = File(outputDir, "${baseName}_${range.first}_${range.last}.pdf")
                PDDocument().use { part ->
                    range.forEach { part.importPage(doc.getPage(it)) }
                    file.outputStream().use { part.save(it) }
                }
                if (maxBytes != null && file.length() > maxBytes && range.count() > 1) {
                    file.delete()
                    val mid = range.first + range.count() / 2
                    queue.addFirst(mid..range.last)
                    queue.addFirst(range.first until mid)
                } else {
                    written += range to file
                }
                onProgress(written.sumOf { it.first.count() }.toFloat() / doc.numberOfPages)
            }
            // Name the parts in page order: base_part1.pdf, base_part2.pdf, ...
            written.sortedBy { it.first.first }.mapIndexed { i, (_, file) ->
                File(outputDir, "${baseName}_part${i + 1}.pdf").also { file.renameTo(it) }
            }
        }
    }

    /**
     * Resizes all pages of the document to a standard paper format (A4, Letter, Legal)
     * with uniform scaling and centered positioning on the target canvas.
     */
    suspend fun resizePages(
        sourceUri: Uri,
        standard: PageStandard,
        marginPt: Float = 18f,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            load(sourceUri).use { doc ->
                val targetRect = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(standard.widthPt, standard.heightPt)
                for (page in doc.pages) {
                    page.mediaBox = targetRect
                    page.cropBox = targetRect
                }
                outputFile.outputStream().use { doc.save(it) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun load(uri: Uri): PDDocument {
        val input = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Cannot read $uri")
        return input.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
    }

    private class CountingStream : OutputStream() {
        var count = 0L
        override fun write(b: Int) { count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { count += len }
    }
}

enum class PageStandard(val widthPt: Float, val heightPt: Float, val displayName: String) {
    A4(595.28f, 841.89f, "A4 (210 × 297 mm)"),
    LETTER(612.0f, 792.0f, "Letter (8.5 × 11 in)"),
    LEGAL(612.0f, 1008.0f, "Legal (8.5 × 14 in)")
}

/**
 * Parses page ranges typed by the user ("1-3, 7, 10-") into 0-based indices, in the order
 * written. Returns null if any part is malformed or out of range.
 */
fun parsePageRanges(text: String, pageCount: Int): List<Int>? {
    val result = LinkedHashSet<Int>()
    for (raw in text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
        val bounds = raw.split('-').map { it.trim() }
        val (start, end) = when (bounds.size) {
            1 -> bounds[0].toIntOrNull()?.let { it to it } ?: return null
            2 -> {
                val a = if (bounds[0].isEmpty()) 1 else bounds[0].toIntOrNull() ?: return null
                val b = if (bounds[1].isEmpty()) pageCount else bounds[1].toIntOrNull() ?: return null
                a to b
            }
            else -> return null
        }
        if (start !in 1..pageCount || end !in 1..pageCount) return null
        val range = if (start <= end) start..end else start downTo end
        range.forEach { result += it - 1 }
    }
    return result.toList().ifEmpty { null }
}
