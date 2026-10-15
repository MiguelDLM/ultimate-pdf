package org.ultimatepdf.core.export

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ultimatepdf.core.pdf.PdfContentEditor
import org.ultimatepdf.core.pdf.PdfThumbnails
import org.ultimatepdf.core.pdf.TableDataExtractor
import org.ultimatepdf.core.scanner.OcrEngine
import java.io.File

enum class ExportFormat(val extension: String, val mimeType: String) {
    WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    EXCEL("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    TEXT("txt", "text/plain"),
    MARKDOWN("md", "text/markdown")
}

class ExportOptions(
    val format: ExportFormat,
    val includeImages: Boolean = true,
    /** Recognize text on pages that have none (scans) so they export too. */
    val useOcr: Boolean = true
)

/** Result of an export: the file, and how many pages needed OCR. */
class ExportResult(val file: File, val pages: Int, val ocrPages: Int, val empty: Boolean)

/**
 * Converts a PDF into editable formats. Pages with real text are read straight from the
 * content; pages without (scans) are recognized with OCR when [ExportOptions.useOcr] is set.
 */
class PdfExporter(
    private val context: Context,
    private val editor: PdfContentEditor,
    private val ocr: OcrEngine,
    private val thumbnails: PdfThumbnails,
    private val tables: TableDataExtractor
) {

    private class PageData(
        val widthPt: Float,
        val heightPt: Float,
        val paragraphs: List<Para>,
        val pictures: List<Picture>,
        val ocr: Boolean
    )

    /** [top] is the page-normalized vertical position, used to interleave pictures and text. */
    private class Para(val text: String, val sizePt: Float, val top: Float, val heading: Boolean)

    private class Picture(val bytes: ByteArray, val isPng: Boolean, val widthPt: Float, val heightPt: Float, val top: Float)

    suspend fun export(
        source: Uri,
        options: ExportOptions,
        output: File,
        onProgress: (page: Int, total: Int) -> Unit
    ): ExportResult = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "export_src_${System.currentTimeMillis()}.pdf")
        try {
            context.contentResolver.openInputStream(source)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("Cannot read $source")
            val total = PDDocument.load(temp).use { it.numberOfPages }
            val tempUri = Uri.fromFile(temp)
            val pages = mutableListOf<PageData>()
            val ocrTables = mutableMapOf<Int, List<List<String>>>()
            for (i in 0 until total) {
                onProgress(i + 1, total)
                pages += readPage(temp, tempUri, i, options, ocrTables)
            }
            val hasContent = pages.any { it.paragraphs.isNotEmpty() || it.pictures.isNotEmpty() }
            when (options.format) {
                ExportFormat.WORD -> writeWord(pages, output)
                ExportFormat.EXCEL -> writeExcel(tempUri, pages, ocrTables, output)
                ExportFormat.TEXT -> output.writeText(pages.joinToString("\n\n") { page -> page.paragraphs.joinToString("\n\n") { it.text } })
                ExportFormat.MARKDOWN -> output.writeText(markdown(pages))
            }
            ExportResult(output, total, pages.count { it.ocr }, !hasContent)
        } finally {
            thumbnails.invalidate(Uri.fromFile(temp))
            temp.delete()
        }
    }

    private suspend fun readPage(
        file: File,
        uri: Uri,
        index: Int,
        options: ExportOptions,
        ocrTables: MutableMap<Int, List<List<String>>>
    ): PageData {
        val content = editor.scan(file, index)
        val pictures = if (options.includeImages && options.format != ExportFormat.EXCEL) {
            runCatching { editor.pageImages(file, index) }.getOrDefault(emptyList())
                .map { Picture(it.bytes, it.isPng, it.widthPt, it.heightPt, it.box.top) }
        } else {
            emptyList()
        }
        if (content.lines.isNotEmpty() || content.boxes.isNotEmpty() || !options.useOcr) {
            val lines = content.lines.map { RawLine(it.text, it.box.top, it.box.left, it.box.height, it.fontSize) } +
                content.boxes.map { RawLine(it.text, it.box.top, it.box.left, it.box.height, it.fontSize) }
            return PageData(content.widthPt, content.heightPt, paragraphs(lines, content.heightPt), pictures, false)
        }
        // No text layer: render the page and recognize it.
        val bitmap = thumbnails.render(uri, index, OCR_WIDTH)
        val recognized = bitmap?.let { runCatching { ocr.recognize(it) }.getOrNull() }
        if (recognized == null || bitmap == null) return PageData(content.widthPt, content.heightPt, emptyList(), pictures, false)
        val h = recognized.imageHeight.toFloat()
        val w = recognized.imageWidth.toFloat()
        val lines = recognized.lines.map {
            RawLine(it.text, it.box.top / h, it.box.left / w, it.box.height / h, it.box.height / h * content.heightPt * 0.78f)
        }
        if (options.format == ExportFormat.EXCEL) {
            ocrTables[index] = tables.extractTableFromOcr(recognized, index).rows.map { it.texts }
        }
        return PageData(content.widthPt, content.heightPt, paragraphs(lines, content.heightPt), pictures, true)
    }

    private class RawLine(val text: String, val top: Float, val left: Float, val height: Float, val sizePt: Float)

    /** Joins consecutive lines of the same size into paragraphs; big lines become headings. */
    private fun paragraphs(lines: List<RawLine>, pageHeightPt: Float): List<Para> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedWith(compareBy<RawLine> { Math.round(it.top * 200f) }.thenBy { it.left })
        val body = sorted.map { it.sizePt }.sorted().let { it[it.size / 2] }.coerceAtLeast(6f)
        val result = mutableListOf<Para>()
        var text = StringBuilder()
        var first: RawLine? = null
        var last: RawLine? = null
        fun flush() {
            val start = first ?: return
            val size = start.sizePt.coerceIn(8f, 40f)
            result += Para(text.toString().trim(), size, start.top, start.sizePt > body * 1.25f)
            text = StringBuilder()
            first = null
            last = null
        }
        for (line in sorted) {
            val prev = last
            val gapPt = if (prev != null) (line.top - (prev.top + prev.height)) * pageHeightPt else 0f
            val sameBlock = prev != null && kotlin.math.abs(line.sizePt - prev.sizePt) < body * 0.15f &&
                gapPt < prev.sizePt * 0.8f && line.left <= prev.left + 0.35f
            if (!sameBlock) flush()
            if (text.isNotEmpty()) text.append(' ')
            text.append(line.text)
            if (first == null) first = line
            last = line
        }
        flush()
        return result
    }

    private fun writeWord(pages: List<PageData>, output: File) {
        val blocks = mutableListOf<DocBlock>()
        pages.forEachIndexed { i, page ->
            if (i > 0) blocks += DocBlock.PageBreak
            val items = page.paragraphs.map { it.top to (it as Any) } + page.pictures.map { it.top to (it as Any) }
            for ((_, item) in items.sortedBy { it.first }) {
                when (item) {
                    is Para -> blocks += DocBlock.Paragraph(item.text, if (item.heading) item.sizePt else item.sizePt.coerceIn(9f, 14f), item.heading)
                    is Picture -> blocks += DocBlock.Picture(item.bytes, item.isPng, item.widthPt, item.heightPt)
                }
            }
        }
        val first = pages.firstOrNull()
        DocxWriter.write(blocks, first?.widthPt ?: 595f, first?.heightPt ?: 842f, output)
    }

    private suspend fun writeExcel(uri: Uri, pages: List<PageData>, ocrTables: Map<Int, List<List<String>>>, output: File) {
        val digital = runCatching { tables.extractTablesFromPdf(uri) }.getOrDefault(emptyList())
        val sheets = mutableListOf<XlsxWriter.Sheet>()
        pages.forEachIndexed { i, page ->
            val rows = if (page.ocr) {
                ocrTables[i].orEmpty()
            } else {
                digital.firstOrNull { it.pageIndex == i }?.rows?.map { it.texts }.orEmpty()
            }.filter { row -> row.any { it.isNotBlank() } }
            if (rows.isNotEmpty()) sheets += XlsxWriter.Sheet("Page ${i + 1}", rows)
        }
        if (sheets.isEmpty()) sheets += XlsxWriter.Sheet("Sheet", pages.flatMap { p -> p.paragraphs.map { listOf(it.text) } })
        XlsxWriter.write(sheets, output)
    }

    private fun markdown(pages: List<PageData>): String =
        pages.joinToString("\n\n---\n\n") { page ->
            page.paragraphs.joinToString("\n\n") { if (it.heading) "## ${it.text}" else it.text }
        }

    private companion object {
        const val OCR_WIDTH = 1800
    }
}
