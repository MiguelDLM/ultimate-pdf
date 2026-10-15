package org.ultimatepdf.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * PDF Utility manager inspired by Swati Images-to-PDF.
 * Implements AES-256 encryption, password removal, document splitting, merging,
 * text stripping, and embedded image extraction.
 */
class PdfSecurityManager(private val context: Context) {

    companion object {
        private const val TAG = "PdfSecurityManager"
        private const val KEY_LENGTH_AES_256 = 256
    }

    /**
     * Protects a PDF document with AES-256 encryption and configurable user/owner passwords.
     */
    suspend fun encryptDocument(
        sourceUri: Uri,
        userPassword: String,
        ownerPassword: String = userPassword,
        canPrint: Boolean = true,
        canExtractContent: Boolean = true,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    val ap = AccessPermission().apply {
                        setCanPrint(canPrint)
                        setCanExtractContent(canExtractContent)
                        setCanModify(false)
                        setCanModifyAnnotations(true)
                    }

                    val spp = StandardProtectionPolicy(ownerPassword, userPassword, ap).apply {
                        encryptionKeyLength = KEY_LENGTH_AES_256
                    }

                    doc.protect(spp)
                    FileOutputStream(outputFile).use { out ->
                        doc.save(out)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error encrypting PDF", e)
            false
        }
    }

    /**
     * Removes password protection from an encrypted PDF.
     */
    suspend fun decryptDocument(
        sourceUri: Uri,
        currentPassword: String,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                PDDocument.load(input, currentPassword).use { doc ->
                    doc.isAllSecurityToBeRemoved = true
                    FileOutputStream(outputFile).use { out ->
                        doc.save(out)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting PDF", e)
            false
        }
    }

    /**
     * Merges multiple source PDF URIs into a single destination file.
     */
    suspend fun mergeDocuments(
        sourceUris: List<Uri>,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        if (sourceUris.isEmpty()) return@withContext false
        try {
            val merger = PDFMergerUtility()
            merger.destinationFileName = outputFile.absolutePath

            val tempFiles = mutableListOf<File>()
            for (uri in sourceUris) {
                val tempFile = File.createTempFile("merge_src_", ".pdf", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { inStream ->
                    FileOutputStream(tempFile).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
                tempFiles.add(tempFile)
                merger.addSource(tempFile)
            }

            merger.mergeDocuments(null)
            tempFiles.forEach { it.delete() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error merging PDF documents", e)
            false
        }
    }

    /**
     * Splits a PDF document by page range and saves individual parts.
     */
    suspend fun splitDocument(
        sourceUri: Uri,
        splitAtPage: Int,
        outputDir: File
    ): List<File> = withContext(Dispatchers.IO) {
        val resultFiles = mutableListOf<File>()
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    val splitter = Splitter().apply {
                        setSplitAtPage(splitAtPage)
                    }
                    val parts = splitter.split(doc)
                    for ((index, partDoc) in parts.withIndex()) {
                        val partFile = File(outputDir, "part_${index + 1}.pdf")
                        FileOutputStream(partFile).use { out ->
                            partDoc.save(out)
                        }
                        partDoc.close()
                        resultFiles.add(partFile)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error splitting PDF", e)
        }
        resultFiles
    }

    /**
     * Strips and extracts all text content from the PDF.
     */
    suspend fun extractText(sourceUri: Uri): String = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    val stripper = PDFTextStripper()
                    return@withContext stripper.getText(doc)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text from PDF", e)
        }
        ""
    }

    /**
     * Extracts all embedded images from the PDF document.
     */
    suspend fun extractImages(sourceUri: Uri, outputDir: File): List<File> = withContext(Dispatchers.IO) {
        val images = mutableListOf<File>()
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    var count = 0
                    for (page in doc.pages) {
                        val resources = page.resources ?: continue
                        for (xObjectName in resources.xObjectNames) {
                            val xObject = resources.getXObject(xObjectName)
                            if (xObject is PDImageXObject) {
                                count++
                                val imgFile = File(outputDir, "extracted_img_$count.jpg")
                                val bitmap = xObject.image
                                FileOutputStream(imgFile).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                }
                                images.add(imgFile)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting images", e)
        }
        images
    }

    /**
     * Compresses PDF file size by downsampling embedded images and re-compressing them as optimized JPEGs.
     */
    suspend fun compressDocument(
        sourceUri: Uri,
        level: CompressionLevel,
        outputFile: File
    ): CompressionResult = withContext(Dispatchers.IO) {
        var originalSize = 0L
        var downsampledCount = 0
        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                originalSize = pfd.statSize
            }
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    for (page in doc.pages) {
                        val resources = page.resources ?: continue
                        for (xObjectName in resources.xObjectNames) {
                            val xObject = resources.getXObject(xObjectName)
                            if (xObject is PDImageXObject) {
                                val origBmp = xObject.image ?: continue
                                val maxSide = maxOf(origBmp.width, origBmp.height)
                                val needsScale = maxSide > level.maxDimension
                                val newBmp = if (needsScale) {
                                    val scale = level.maxDimension.toFloat() / maxSide
                                    val w = (origBmp.width * scale).toInt().coerceAtLeast(1)
                                    val h = (origBmp.height * scale).toInt().coerceAtLeast(1)
                                    Bitmap.createScaledBitmap(origBmp, w, h, true)
                                } else {
                                    origBmp
                                }
                                val newImageXObject = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(doc, newBmp, level.quality)
                                if (needsScale && newBmp !== origBmp) {
                                    newBmp.recycle()
                                }
                                resources.put(xObjectName, newImageXObject)
                                downsampledCount++
                            }
                        }
                    }
                    FileOutputStream(outputFile).use { out ->
                        doc.save(out)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing PDF", e)
        }
        val finalSize = outputFile.length()
        CompressionResult(
            originalSizeBytes = if (originalSize > 0) originalSize else finalSize,
            compressedSizeBytes = finalSize,
            imagesDownsampled = downsampledCount
        )
    }
}

enum class CompressionLevel(val maxDimension: Int, val quality: Float) {
    LOW(2048, 0.85f),
    MEDIUM(1400, 0.70f),
    HIGH(900, 0.50f)
}

data class CompressionResult(
    val originalSizeBytes: Long,
    val compressedSizeBytes: Long,
    val imagesDownsampled: Int
) {
    val savingsPercent: Int
        get() = if (originalSizeBytes > 0 && originalSizeBytes > compressedSizeBytes) {
            (((originalSizeBytes - compressedSizeBytes).toFloat() / originalSizeBytes) * 100).toInt()
        } else 0
}
