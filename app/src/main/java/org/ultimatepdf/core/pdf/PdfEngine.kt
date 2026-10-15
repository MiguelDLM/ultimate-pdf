package org.ultimatepdf.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ultimatepdf.core.model.AcroFieldType
import org.ultimatepdf.core.model.AcroFormField
import org.ultimatepdf.core.model.DocumentModel
import org.ultimatepdf.core.model.PdfRect
import java.io.File
import java.io.FileOutputStream

/**
 * High-performance PDF Engine combining Android's native hardware-accelerated [PdfRenderer]
 * for sub-second rendering, alongside [PDDocument] (PDFBox-Android) for structural AcroForms,
 * security, and low-level object manipulation.
 *
 * Synchronized with [Mutex] to guarantee thread safety since native [PdfRenderer] only
 * permits a single open page at any point in time.
 */
class PdfEngine(private val context: Context) {

    private var currentPfd: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private val bitmapCache: LruCache<String, Bitmap> = LruCache(48)
    private val renderMutex = Mutex()

    companion object {
        private const val TAG = "PdfEngine"
    }

    /**
     * Opens a PDF document from an Android content URI.
     */
    suspend fun openDocument(uri: Uri): DocumentModel = withContext(Dispatchers.IO) {
        close()

        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Cannot open FileDescriptor for $uri")
        currentPfd = pfd

        val renderer = PdfRenderer(pfd)
        pdfRenderer = renderer

        val fileName = queryFileName(uri)
        val fileSize = pfd.statSize

        DocumentModel(
            uri = uri,
            name = fileName,
            pageCount = renderer.pageCount,
            sizeBytes = fileSize
        )
    }

    /**
     * Retrieves dimensions (width to height) of a given page to allow smooth layout placeholders.
     */
    suspend fun getPageDimensions(pageIndex: Int): Pair<Int, Int> = withContext(Dispatchers.Default) {
        renderMutex.withLock {
            val renderer = pdfRenderer ?: return@withContext Pair(595, 842)
            if (pageIndex !in 0 until renderer.pageCount) return@withContext Pair(595, 842)
            try {
                val page = renderer.openPage(pageIndex)
                val w = page.width
                val h = page.height
                page.close()
                Pair(w, h)
            } catch (e: Exception) {
                Pair(595, 842)
            }
        }
    }

    /**
     * Renders a specific page to a Bitmap at the requested target dimensions.
     * Thread-safe with Mutex and backed by an LruCache for instant smooth scrolling.
     */
    suspend fun renderPage(
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int,
        invertColors: Boolean = false
    ): Bitmap = withContext(Dispatchers.Default) {
        val cacheKey = "$pageIndex-${targetWidth}x$targetHeight-inv=$invertColors"
        synchronized(bitmapCache) {
            bitmapCache.get(cacheKey)?.let { return@withContext it }
        }

        val finalBitmap = renderMutex.withLock {
            // Double check cache under lock
            synchronized(bitmapCache) {
                bitmapCache.get(cacheKey)?.let { return@withLock it }
            }

            val renderer = pdfRenderer ?: throw IllegalStateException("PdfRenderer not initialized")
            if (pageIndex !in 0 until renderer.pageCount) {
                throw IndexOutOfBoundsException("Page index $pageIndex out of bounds")
            }

            val page = renderer.openPage(pageIndex)
            try {
                val pageWidth = page.width
                val pageHeight = page.height
                val scale = minOf(targetWidth.toFloat() / pageWidth, targetHeight.toFloat() / pageHeight)
                    .coerceAtLeast(0.1f)
                val outW = (pageWidth * scale).toInt().coerceAtLeast(1)
                val outH = (pageHeight * scale).toInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                if (invertColors) {
                    invertBitmapColors(bitmap)
                } else {
                    bitmap
                }
            } finally {
                page.close()
            }
        }

        synchronized(bitmapCache) {
            bitmapCache.put(cacheKey, finalBitmap)
        }
        finalBitmap
    }

    /**
     * Reads all interactive AcroForms from the PDF using PDFBox-Android.
     */
    suspend fun extractAcroForms(uri: Uri): List<AcroFormField> = withContext(Dispatchers.IO) {
        val formsList = mutableListOf<AcroFormField>()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { pdDoc ->
                    val acroForm = pdDoc.documentCatalog.acroForm ?: return@withContext emptyList()
                    for (field in acroForm.fields) {
                        val name = field.fullyQualifiedName ?: field.partialName ?: "Field"
                        val value = field.valueAsString ?: ""
                        val isReadOnly = field.isReadOnly
                        val isRequired = field.isRequired

                        val fieldType = when (field) {
                            is PDTextField -> AcroFieldType.TEXT
                            is PDCheckBox -> AcroFieldType.CHECKBOX
                            is PDRadioButton -> AcroFieldType.RADIO_BUTTON
                            is PDChoice -> AcroFieldType.COMBOBOX
                            else -> AcroFieldType.TEXT
                        }

                        val options = if (field is PDChoice) {
                            field.optionsDisplayValues ?: field.options ?: emptyList()
                        } else emptyList()

                        // Retrieve widget annotation coordinates if available
                        val widget = field.widgets.firstOrNull()
                        val bounds = widget?.rectangle?.let { r ->
                            PdfRect(r.lowerLeftX, r.upperRightY, r.upperRightX, r.lowerLeftY)
                        }

                        formsList.add(
                            AcroFormField(
                                fullyQualifiedName = name,
                                partialName = field.partialName ?: name,
                                fieldType = fieldType,
                                currentValue = value,
                                options = options,
                                isReadOnly = isReadOnly,
                                isRequired = isRequired,
                                bounds = bounds
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting AcroForms", e)
        }
        formsList
    }

    /**
     * Saves filled form values back into a new PDF document.
     */
    suspend fun saveFormValues(
        sourceUri: Uri,
        fieldValues: Map<String, String>,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                PDDocument.load(inputStream).use { pdDoc ->
                    val acroForm = pdDoc.documentCatalog.acroForm ?: return@withContext false
                    for ((fieldName, value) in fieldValues) {
                        val field = acroForm.getField(fieldName)
                        field?.setValue(value)
                    }
                    // Refresh appearance streams so changes are rendered on any viewer
                    acroForm.refreshAppearances()
                    FileOutputStream(outputFile).use { out ->
                        pdDoc.save(out)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save form values", e)
            false
        }
    }

    private fun invertBitmapColors(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = Paint()
        val matrix = ColorMatrix(
            floatArrayOf(
                -1.0f,  0.0f,  0.0f, 0.0f, 255.0f,
                 0.0f, -1.0f,  0.0f, 0.0f, 255.0f,
                 0.0f,  0.0f, -1.0f, 0.0f, 255.0f,
                 0.0f,  0.0f,  0.0f, 1.0f,   0.0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun queryFileName(uri: Uri): String {
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    fun close() {
        try {
            pdfRenderer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing PdfRenderer", e)
        }
        pdfRenderer = null

        try {
            currentPfd?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ParcelFileDescriptor", e)
        }
        currentPfd = null
        synchronized(bitmapCache) {
            bitmapCache.evictAll()
        }
    }
}
