package org.ultimatepdf.core.pdf

import android.graphics.Bitmap
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ultimatepdf.core.model.OcrPage
import java.io.File
import java.io.FileOutputStream

/** One finished scan page: the cleaned image and, optionally, its recognized text. */
class ScanOutputPage(val image: Bitmap, val ocr: OcrPage?, val isBlackAndWhite: Boolean = false)

/**
 * Builds a PDF from scanned images with an invisible, selectable text layer placed exactly
 * over each recognized word (rendering mode "neither"), so the result can be searched,
 * selected and copied like a digital document.
 */
class SearchablePdfGenerator {

    companion object {
        private const val TAG = "SearchablePdfGenerator"
        private const val JPEG_QUALITY = 0.85f
        /** Pages are A4-wide (595 pt); height follows the image's aspect ratio. */
        private const val PAGE_WIDTH_PT = 595f
        private val UNICODE_FONTS = listOf("/system/fonts/Roboto-Regular.ttf", "/system/fonts/NotoSans-Regular.ttf")
    }

    suspend fun createSearchablePdf(pages: List<ScanOutputPage>, outputFile: File) = withContext(Dispatchers.IO) {
        require(pages.isNotEmpty())
        PDDocument().use { doc ->
            val font = loadFont(doc)
            for (scan in pages) {
                val image = scan.image
                val pageRect = PDRectangle(PAGE_WIDTH_PT, PAGE_WIDTH_PT * image.height / image.width)
                val page = PDPage(pageRect)
                doc.addPage(page)
                // Black & white pages compress far better (and stay sharp) as lossless images.
                val pdImage = if (scan.isBlackAndWhite) LosslessFactory.createFromImage(doc, image)
                else JPEGFactory.createFromImage(doc, image, JPEG_QUALITY)
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(pdImage, 0f, 0f, pageRect.width, pageRect.height)
                    scan.ocr?.let { writeTextLayer(cs, font, it, pageRect) }
                }
            }
            FileOutputStream(outputFile).use { doc.save(it) }
        }
    }

    private fun writeTextLayer(cs: PDPageContentStream, font: PDFont, ocr: OcrPage, pageRect: PDRectangle) {
        val scale = pageRect.width / ocr.imageWidth
        cs.setRenderingMode(RenderingMode.NEITHER)
        for (word in ocr.words) {
            val text = encodable(font, word.text)
            if (text.isBlank()) continue
            val box = word.boundingBox
            val height = box.height * scale
            val fontSize = (height * 0.85f).coerceAtLeast(2f)
            val naturalWidth = runCatching { font.getStringWidth(text) / 1000f * fontSize }.getOrDefault(0f)
            // Stretch the invisible word to its box so selection highlights match the image.
            val stretch = if (naturalWidth > 0f) box.width * scale / naturalWidth else 1f
            cs.beginText()
            cs.setFont(font, fontSize)
            cs.setTextMatrix(Matrix(stretch, 0f, 0f, 1f, box.left * scale, pageRect.height - box.bottom * scale + height * 0.15f))
            cs.showText(text)
            cs.endText()
        }
    }

    /** An embedded Unicode font when the system has one; Helvetica (Latin-1 only) otherwise. */
    private fun loadFont(doc: PDDocument): PDFont {
        for (path in UNICODE_FONTS) {
            val file = File(path)
            if (!file.exists()) continue
            try {
                return PDType0Font.load(doc, file)
            } catch (e: Exception) {
                Log.w(TAG, "Can't load $path", e)
            }
        }
        return PDType1Font.HELVETICA
    }

    /** Drops characters the font can't encode instead of failing the whole document. */
    private fun encodable(font: PDFont, text: String): String {
        if (runCatching { font.encode(text) }.isSuccess) return text
        return text.filter { c -> runCatching { font.encode(c.toString()) }.isSuccess }
    }
}
