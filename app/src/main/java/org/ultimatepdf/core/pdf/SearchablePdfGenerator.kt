package org.ultimatepdf.core.pdf

import android.graphics.Bitmap
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ultimatepdf.core.model.ScannedPage
import java.io.File
import java.io.FileOutputStream

/**
 * Creates dual-layer Searchable PDFs inspired by MakeACopy.
 * Embeds high-resolution scanned imagery in the background, overlaid with
 * an invisible selectable text layer using ISO 32000 [RenderingMode.NEITHER].
 */
class SearchablePdfGenerator {

    companion object {
        private const val TAG = "SearchablePdfGenerator"
        private const val JPEG_QUALITY = 0.85f
    }

    suspend fun createSearchablePdf(
        pages: List<ScannedPage>,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext false

        try {
            PDDocument().use { doc ->
                for (scannedPage in pages) {
                    val bitmap = scannedPage.processedImage ?: scannedPage.rawImage
                    val imgWidth = bitmap.width.toFloat()
                    val imgHeight = bitmap.height.toFloat()

                    // PDF standard coordinate points (72 points per inch)
                    // Let standard A4 or image aspect ratio drive dimensions
                    val pageRect = PDRectangle(imgWidth * 72f / 300f, imgHeight * 72f / 300f)
                    val pdPage = PDPage(pageRect)
                    doc.addPage(pdPage)

                    // 1. Draw scanned image background
                    val pdImage = JPEGFactory.createFromImage(doc, bitmap, JPEG_QUALITY)
                    PDPageContentStream(doc, pdPage).use { cs ->
                        cs.drawImage(pdImage, 0f, 0f, pageRect.width, pageRect.height)

                        // 2. Overlay invisible text layer for OCR words
                        if (scannedPage.ocrWords.isNotEmpty()) {
                            cs.setRenderingMode(RenderingMode.NEITHER) // Invisible but selectable/searchable
                            val font = PDType1Font.HELVETICA

                            val scaleX = pageRect.width / imgWidth
                            val scaleY = pageRect.height / imgHeight

                            for (word in scannedPage.ocrWords) {
                                if (word.text.isBlank()) continue

                                val box = word.boundingBox
                                // Convert image coordinates (top-left origin) to PDF coordinates (bottom-left origin)
                                val xPdf = box.left * scaleX
                                val yPdf = pageRect.height - (box.bottom * scaleY)
                                val boxWidthPdf = box.width * scaleX
                                val boxHeightPdf = box.height * scaleY

                                val fontSize = (boxHeightPdf * 0.85f).coerceAtLeast(4f)
                                cs.beginText()
                                cs.setFont(font, fontSize)

                                // Adjust horizontal scale factor if text width differs from box width
                                val estimatedWidth = font.getStringWidth(word.text) / 1000f * fontSize
                                val horizontalScale = if (estimatedWidth > 0) {
                                    (boxWidthPdf / estimatedWidth).coerceIn(0.5f, 2.0f)
                                } else 1.0f

                                val textMatrix = Matrix(
                                    horizontalScale, 0f,
                                    0f, 1f,
                                    xPdf, yPdf
                                )
                                cs.setTextMatrix(textMatrix)
                                cs.showText(word.text)
                                cs.endText()
                            }
                        }
                    }
                }

                FileOutputStream(outputFile).use { out ->
                    doc.save(out)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error generating searchable PDF", e)
            false
        }
    }
}
