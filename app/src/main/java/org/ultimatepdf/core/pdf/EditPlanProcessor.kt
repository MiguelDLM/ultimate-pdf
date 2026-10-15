package org.ultimatepdf.core.pdf

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ultimatepdf.core.model.EditPlan
import org.ultimatepdf.core.model.PageNumberOptions
import org.ultimatepdf.core.model.WatermarkOptions
import java.io.File
import java.io.FileOutputStream

/**
 * Executes an [EditPlan] and writes the reconstructed, optimized PDF to disk.
 * Integrates AnonPDF's non-destructive commit pipeline with PDFBox-Android.
 */
class EditPlanProcessor(private val context: Context) {

    companion object {
        private const val TAG = "EditPlanProcessor"
    }

    suspend fun applyAndExport(
        primarySourceUri: Uri,
        plan: EditPlan,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Load base document
            val baseInputStream = context.contentResolver.openInputStream(primarySourceUri)
                ?: return@withContext false

            PDDocument.load(baseInputStream).use { sourceDoc ->
                PDDocument().use { targetDoc ->
                    // Track auxiliary documents opened for inserted pages
                    val auxiliaryDocs = mutableMapOf<Uri, PDDocument>()

                    try {
                        for (pageState in plan.activePages) {
                            val sourcePage = if (pageState.sourceDocumentUri != null) {
                                val auxDoc = auxiliaryDocs.getOrPut(pageState.sourceDocumentUri) {
                                    val stream = context.contentResolver.openInputStream(pageState.sourceDocumentUri)
                                        ?: throw IllegalStateException("Cannot open ${pageState.sourceDocumentUri}")
                                    PDDocument.load(stream)
                                }
                                auxDoc.getPage(pageState.sourceIndex.coerceIn(0, auxDoc.numberOfPages - 1))
                            } else {
                                sourceDoc.getPage(pageState.sourceIndex.coerceIn(0, sourceDoc.numberOfPages - 1))
                            }

                            // Import page into target document
                            val importedPage = targetDoc.importPage(sourcePage)

                            // Apply Rotation Delta
                            if (pageState.rotationDelta != 0) {
                                val currentRotation = importedPage.rotation
                                importedPage.rotation = (currentRotation + pageState.rotationDelta) % 360
                            }

                            // Apply Crop
                            if (!pageState.crop.isEmpty) {
                                val mediaBox = importedPage.mediaBox
                                val leftInset = mediaBox.width * pageState.crop.left
                                val rightInset = mediaBox.width * pageState.crop.right
                                val topInset = mediaBox.height * pageState.crop.top
                                val bottomInset = mediaBox.height * pageState.crop.bottom

                                val croppedRect = PDRectangle(
                                    mediaBox.lowerLeftX + leftInset,
                                    mediaBox.lowerLeftY + bottomInset,
                                    mediaBox.width - (leftInset + rightInset),
                                    mediaBox.height - (topInset + bottomInset)
                                )
                                importedPage.cropBox = croppedRect
                            }
                        }

                        // Apply Watermark if defined
                        plan.watermark?.let { wm ->
                            applyWatermark(targetDoc, wm)
                        }

                        // Apply Page Numbers if defined
                        plan.pageNumbers?.let { pn ->
                            applyPageNumbers(targetDoc, pn)
                        }

                        // Save target document to destination file
                        FileOutputStream(outputFile).use { outStream ->
                            targetDoc.save(outStream)
                        }

                    } finally {
                        auxiliaryDocs.values.forEach { aux ->
                            try { aux.close() } catch (e: Exception) { Log.w(TAG, "Error closing aux doc", e) }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply edit plan", e)
            false
        }
    }

    private fun applyWatermark(doc: PDDocument, options: WatermarkOptions) {
        val font = PDType1Font.HELVETICA_BOLD
        val totalPages = doc.numberOfPages

        for (i in 0 until totalPages) {
            val page = doc.getPage(i)
            val mediaBox = page.mediaBox
            val cx = mediaBox.width / 2f
            val cy = mediaBox.height / 2f

            PDPageContentStream(
                doc,
                page,
                PDPageContentStream.AppendMode.APPEND,
                true,
                true
            ).use { cs ->
                val gs = PDExtendedGraphicsState().apply {
                    nonStrokingAlphaConstant = options.opacity
                }
                cs.setGraphicsStateParameters(gs)
                cs.setNonStrokingColor(180, 180, 180)
                cs.beginText()
                cs.setFont(font, options.fontSizePt)

                val textWidth = font.getStringWidth(options.text) / 1000f * options.fontSizePt

                // Rotate text across page diagonal
                val rad = Math.toRadians(options.angleDegrees.toDouble()).toFloat()
                val cos = Math.cos(rad.toDouble()).toFloat()
                val sin = Math.sin(rad.toDouble()).toFloat()

                val matrix = Matrix(cos, sin, -sin, cos, cx - (textWidth / 2f) * cos, cy - (textWidth / 2f) * sin)
                cs.setTextMatrix(matrix)
                cs.showText(options.text)
                cs.endText()
            }
        }
    }

    private fun applyPageNumbers(doc: PDDocument, options: PageNumberOptions) {
        val font = PDType1Font.HELVETICA
        val totalPages = doc.numberOfPages

        for (i in 0 until totalPages) {
            val page = doc.getPage(i)
            val mediaBox = page.mediaBox
            val pageLabel = options.format
                .replace("{page}", (i + 1).toString())
                .replace("{total}", totalPages.toString())

            PDPageContentStream(
                doc,
                page,
                PDPageContentStream.AppendMode.APPEND,
                true,
                true
            ).use { cs ->
                cs.beginText()
                cs.setFont(font, options.fontSizePt)
                cs.setNonStrokingColor(40, 40, 40)

                val textWidth = font.getStringWidth(pageLabel) / 1000f * options.fontSizePt
                val x = (mediaBox.width - textWidth) / 2f
                val y = options.bottomMarginPt

                cs.setTextMatrix(Matrix.getTranslateInstance(x, y))
                cs.showText(pageLabel)
                cs.endText()
            }
        }
    }
}
