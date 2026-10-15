package org.ultimatepdf

import org.junit.Assert.*
import org.junit.Test
import org.ultimatepdf.core.model.OcrLine
import org.ultimatepdf.core.model.OcrPage
import org.ultimatepdf.core.model.OcrRecognizedWord
import org.ultimatepdf.core.model.PdfRect
import org.ultimatepdf.core.pdf.TableDataExtractor

class TableDataExtractorTest {

    @Test
    fun `test table reconstruction and export to CSV, TSV and HTML`() {
        val extractor = TableDataExtractor()

        // Simulate a 2-row table with 3 columns:
        // Row 1: Item | Qty | Price
        // Row 2: Apples | 5 | $10.00
        val line1Words = listOf(
            OcrRecognizedWord("Item", PdfRect(10f, 20f, 50f, 40f)),
            OcrRecognizedWord("Qty", PdfRect(150f, 20f, 180f, 40f)),
            OcrRecognizedWord("Price", PdfRect(300f, 20f, 350f, 40f))
        )
        val line2Words = listOf(
            OcrRecognizedWord("Apples", PdfRect(10f, 60f, 70f, 80f)),
            OcrRecognizedWord("5", PdfRect(150f, 60f, 160f, 80f)),
            OcrRecognizedWord("$10.00", PdfRect(300f, 60f, 360f, 80f))
        )

        val ocrPage = OcrPage(
            imageWidth = 500,
            imageHeight = 500,
            lines = listOf(
                OcrLine("Item Qty Price", PdfRect(10f, 20f, 350f, 40f), line1Words),
                OcrLine("Apples 5 $10.00", PdfRect(10f, 60f, 360f, 80f), line2Words)
            )
        )

        val table = extractor.extractTableFromOcr(ocrPage)

        assertEquals(2, table.rows.size)
        assertEquals(listOf("Item", "Qty", "Price"), table.rows[0].texts)
        assertEquals(listOf("Apples", "5", "$10.00"), table.rows[1].texts)

        val csv = table.toCsv()
        assertTrue(csv.contains("Item,Qty,Price"))
        assertTrue(csv.contains("Apples,5,$10.00"))

        val tsv = table.toTsv()
        assertTrue(tsv.contains("Item\tQty\tPrice"))
        assertTrue(tsv.contains("Apples\t5\t$10.00"))

        val html = table.toHtml("Invoice")
        assertTrue(html.contains("<th>Item</th>"))
        assertTrue(html.contains("<td>Apples</td>"))
    }
}
