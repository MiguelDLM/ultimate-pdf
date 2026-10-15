package org.ultimatepdf.core.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ultimatepdf.core.model.OcrPage
import org.ultimatepdf.core.model.PdfRect
import java.io.File
import kotlin.math.abs

/**
 * A cell in a reconstructed document table.
 */
data class TableCell(
    val text: String,
    val bounds: PdfRect
)

/**
 * A row in a reconstructed table containing cells ordered left-to-right.
 */
data class TableRow(
    val cells: List<TableCell>
) {
    val texts: List<String> get() = cells.map { it.text.trim() }
}

/**
 * Reconstructed tabular data from a PDF or OCR page.
 */
data class ExtractedTable(
    val pageIndex: Int,
    val rows: List<TableRow>
) {
    val isEmpty: Boolean get() = rows.isEmpty() || rows.all { it.cells.isEmpty() }

    /**
     * Exports table to standard RFC 4180 CSV (compatible with Excel, LibreOffice Calc, etc.).
     */
    fun toCsv(delimiter: String = ","): String {
        return rows.joinToString("\n") { row ->
            row.texts.joinToString(delimiter) { cell ->
                if (cell.contains(delimiter) || cell.contains("\"") || cell.contains("\n")) {
                    "\"" + cell.replace("\"", "\"\"") + "\""
                } else {
                    cell
                }
            }
        }
    }

    /**
     * Exports to Tab-Separated Values (TSV), ideal for direct copy-paste into Excel/Sheets.
     */
    fun toTsv(): String = toCsv(delimiter = "\t")

    /**
     * Exports to HTML Table format, opened natively as formatted tables in MS Word and Excel.
     */
    fun toHtml(title: String = "Extracted Table"): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n")
        sb.append("<title>").append(escapeHtml(title)).append("</title>\n")
        sb.append("<style>\n")
        sb.append("body { font-family: sans-serif; margin: 20px; }\n")
        sb.append("table { border-collapse: collapse; width: 100%; margin-bottom: 20px; }\n")
        sb.append("th, td { border: 1px solid #ddd; padding: 8px 12px; text-align: left; }\n")
        sb.append("tr:nth-child(even) { background-color: #f9f9f9; }\n")
        sb.append("tr:first-child { background-color: #f2f2f2; font-weight: bold; }\n")
        sb.append("</style>\n</head>\n<body>\n")
        sb.append("<h2>").append(escapeHtml(title)).append("</h2>\n")
        sb.append("<table>\n")
        rows.forEachIndexed { rowIndex, row ->
            sb.append("  <tr>\n")
            val tag = if (rowIndex == 0) "th" else "td"
            for (text in row.texts) {
                sb.append("    <").append(tag).append(">")
                    .append(escapeHtml(text))
                    .append("</").append(tag).append(">\n")
            }
            sb.append("  </tr>\n")
        }
        sb.append("</table>\n</body>\n</html>")
        return sb.toString()
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}

/**
 * Reconstructs tabular data and structured text blocks from PDF documents and OCR results.
 */
class TableDataExtractor(private val context: Context? = null) {

    /**
     * Extracts tables and structured text from all pages of a digital PDF.
     */
    suspend fun extractTablesFromPdf(uri: Uri): List<ExtractedTable> = withContext(Dispatchers.IO) {
        val ctx = context ?: throw IllegalStateException("Context required for PDF I/O")
        val input = ctx.contentResolver.openInputStream(uri) ?: return@withContext emptyList()
        input.use { stream ->
            PDDocument.load(stream).use { doc ->
                (0 until doc.numberOfPages).map { pageIndex ->
                    val collector = TableWordStripper()
                    collector.sortByPosition = true
                    collector.startPage = pageIndex + 1
                    collector.endPage = pageIndex + 1
                    collector.getText(doc)
                    reconstructTable(collector.words, pageIndex)
                }
            }
        }
    }

    /**
     * Extracts tabular data from an OCR recognition result.
     */
    fun extractTableFromOcr(ocrPage: OcrPage, pageIndex: Int = 0): ExtractedTable {
        val words = ocrPage.words.map { word ->
            ExtractedWord(
                text = word.text,
                bounds = word.boundingBox
            )
        }
        return reconstructTable(words, pageIndex)
    }

    /**
     * Core table reconstruction algorithm:
     * 1. Clusters words into visual rows by vertical coordinate alignment.
     * 2. Within each row, clusters consecutive words into cells by comparing horizontal gaps.
     * 3. Aligns columns globally across rows.
     */
    private fun reconstructTable(words: List<ExtractedWord>, pageIndex: Int): ExtractedTable {
        if (words.isEmpty()) return ExtractedTable(pageIndex, emptyList())

        // 1. Group words into horizontal lines
        val sortedWords = words.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val lineClusters = mutableListOf<MutableList<ExtractedWord>>()

        for (word in sortedWords) {
            val center = (word.bounds.top + word.bounds.bottom) / 2f
            val height = word.bounds.height.coerceAtLeast(1f)

            val matchedLine = lineClusters.find { line ->
                val lineAvgCenter = line.map { (it.bounds.top + it.bounds.bottom) / 2f }.average().toFloat()
                abs(center - lineAvgCenter) < height * 0.55f
            }

            if (matchedLine != null) {
                matchedLine.add(word)
            } else {
                lineClusters.add(mutableListOf(word))
            }
        }

        // Sort lines top to bottom
        lineClusters.sortBy { line -> line.minOf { it.bounds.top } }

        // Estimate typical character / word spacing to distinguish inter-word vs inter-column gaps
        val rowList = mutableListOf<TableRow>()
        for (line in lineClusters) {
            line.sortBy { it.bounds.left }
            if (line.isEmpty()) continue

            val avgHeight = line.map { it.bounds.height }.average().toFloat().coerceAtLeast(8f)
            val columnGapThreshold = avgHeight * 1.6f // gaps wider than ~1.6x font height define column bounds

            val cells = mutableListOf<TableCell>()
            var currentCellWords = mutableListOf<ExtractedWord>()

            for (i in line.indices) {
                val currentWord = line[i]
                if (currentCellWords.isEmpty()) {
                    currentCellWords.add(currentWord)
                } else {
                    val prevWord = currentCellWords.last()
                    val gap = currentWord.bounds.left - prevWord.bounds.right
                    if (gap > columnGapThreshold) {
                        // Emit completed cell
                        cells.add(createCell(currentCellWords))
                        currentCellWords = mutableListOf(currentWord)
                    } else {
                        currentCellWords.add(currentWord)
                    }
                }
            }
            if (currentCellWords.isNotEmpty()) {
                cells.add(createCell(currentCellWords))
            }

            if (cells.isNotEmpty()) {
                rowList.add(TableRow(cells))
            }
        }

        return ExtractedTable(pageIndex, rowList)
    }

    private fun createCell(words: List<ExtractedWord>): TableCell {
        val text = words.joinToString(" ") { it.text }
        val bounds = PdfRect(
            left = words.minOf { it.bounds.left },
            top = words.minOf { it.bounds.top },
            right = words.maxOf { it.bounds.right },
            bottom = words.maxOf { it.bounds.bottom }
        )
        return TableCell(text, bounds)
    }

    private data class ExtractedWord(val text: String, val bounds: PdfRect)

    private class TableWordStripper : PDFTextStripper() {
        val words = mutableListOf<ExtractedWord>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            // Group contiguous characters into words
            val currentWord = StringBuilder()
            var left = 0f
            var top = 0f
            var right = 0f
            var bottom = 0f
            var hasChar = false

            for (tp in textPositions) {
                val str = tp.unicode ?: continue
                if (str.isBlank()) {
                    if (hasChar && currentWord.isNotBlank()) {
                        words.add(ExtractedWord(currentWord.toString(), PdfRect(left, top, right, bottom)))
                        currentWord.clear()
                        hasChar = false
                    }
                    continue
                }

                val fontSize = if (tp.fontSizeInPt > 0f) tp.fontSizeInPt else tp.heightDir / 0.7f
                val charTop = tp.yDirAdj - fontSize * 0.8f
                val charBottom = tp.yDirAdj + fontSize * 0.22f
                val charLeft = tp.xDirAdj
                val charRight = tp.xDirAdj + tp.widthDirAdj

                if (!hasChar) {
                    left = charLeft
                    top = charTop
                    right = charRight
                    bottom = charBottom
                    hasChar = true
                } else {
                    left = minOf(left, charLeft)
                    top = minOf(top, charTop)
                    right = maxOf(right, charRight)
                    bottom = maxOf(bottom, charBottom)
                }
                currentWord.append(str)
            }

            if (hasChar && currentWord.isNotBlank()) {
                words.add(ExtractedWord(currentWord.toString(), PdfRect(left, top, right, bottom)))
            }
        }
    }
}
