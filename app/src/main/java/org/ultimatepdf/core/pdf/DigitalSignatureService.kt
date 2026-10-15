package org.ultimatepdf.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.ultimatepdf.core.model.DigitalSignatureConfig
import org.ultimatepdf.core.model.SignatureLevel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Enterprise Digital Signature Service inspired by Open-PDF-Sign.
 * Implements PAdES / PKCS#7 digital signatures using BouncyCastle and PDFBox,
 * combined with visible signature stamping (handwritten capture + cryptographic identity).
 */
class DigitalSignatureService(private val context: Context) {

    companion object {
        private const val TAG = "DigitalSignatureService"
    }

    /**
     * Signs a PDF document with visual stamp and cryptographic PAdES / PKCS#7 signature.
     */
    suspend fun signDocument(
        sourceUri: Uri,
        config: DigitalSignatureConfig,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: return@withContext false

            PDDocument.load(inputStream).use { doc ->
                val targetPageIndex = config.pageIndex.coerceIn(0, doc.numberOfPages - 1)
                val targetPage = doc.getPage(targetPageIndex)

                // 1. Render Visual Signature Stamp onto target page
                drawVisualSignatureStamp(doc, targetPage, config)

                // 2. If certificate is provided, apply cryptographic PAdES / PKCS#7 signature
                if (config.certificateUri != null && config.signatureLevel != SignatureLevel.VISUAL_ONLY) {
                    applyCryptographicSignature(doc, config)
                }

                FileOutputStream(outputFile).use { out ->
                    doc.save(out)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error signing document", e)
            false
        }
    }

    private fun drawVisualSignatureStamp(
        doc: PDDocument,
        page: com.tom_roush.pdfbox.pdmodel.PDPage,
        config: DigitalSignatureConfig
    ) {
        val rect = config.visualRect
        val pageHeight = page.mediaBox.height

        // Transform screen coords to PDF coords (bottom-left origin)
        val x = rect.left
        val y = pageHeight - rect.bottom
        val w = rect.width
        val h = rect.height

        PDPageContentStream(
            doc,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        ).use { cs ->
            // Draw background bounding box
            cs.setNonStrokingColor(245, 247, 250)
            cs.setStrokingColor(180, 190, 205)
            cs.setLineWidth(1.0f)
            cs.addRect(x, y, w, h)
            cs.fillAndStroke()

            // Draw handwritten signature bitmap if present
            config.handwrittenBitmap?.let { bitmap ->
                val pdImg = JPEGFactory.createFromImage(doc, bitmap, 0.9f)
                val imgW = w * 0.45f
                val imgH = h * 0.8f
                cs.drawImage(pdImg, x + 8f, y + (h - imgH) / 2f, imgW, imgH)
            }

            // Draw metadata text (Signer, Date, Reason)
            val font = PDType1Font.HELVETICA_BOLD
            val smallFont = PDType1Font.HELVETICA
            val textStartX = if (config.handwrittenBitmap != null) x + (w * 0.48f) else x + 10f

            cs.setNonStrokingColor(30, 41, 59)
            cs.beginText()
            cs.setFont(font, 9f)
            cs.newLineAtOffset(textStartX, y + h - 16f)
            cs.showText("Digitally Signed by:")
            cs.endText()

            cs.beginText()
            cs.setFont(smallFont, 8f)
            cs.newLineAtOffset(textStartX, y + h - 28f)
            cs.showText(config.signerName.ifBlank { "Verified Signer" })
            cs.endText()

            cs.beginText()
            cs.setFont(smallFont, 7f)
            cs.newLineAtOffset(textStartX, y + h - 40f)
            val dateFormat = SimpleDateFormat("yyyy.MM.dd HH:mm:ss Z", Locale.US)
            cs.showText("Date: ${dateFormat.format(Date())}")
            cs.endText()

            if (config.reason.isNotBlank()) {
                cs.beginText()
                cs.setFont(smallFont, 7f)
                cs.newLineAtOffset(textStartX, y + h - 52f)
                cs.showText("Reason: ${config.reason.take(28)}")
                cs.endText()
            }
        }
    }

    private fun applyCryptographicSignature(doc: PDDocument, config: DigitalSignatureConfig) {
        val certUri = Uri.parse(config.certificateUri)
        val certStream = context.contentResolver.openInputStream(certUri) ?: return

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(certStream, config.certificatePassword.toCharArray())

        val alias = keyStore.aliases().nextElement()
        val privateKey = keyStore.getKey(alias, config.certificatePassword.toCharArray()) as PrivateKey
        val certificateChain = keyStore.getCertificateChain(alias)

        val pdSignature = PDSignature().apply {
            setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
            setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
            name = config.signerName
            location = config.location
            reason = config.reason
            signDate = Calendar.getInstance()
        }

        val signatureInterface = SignatureInterface { contentStream ->
            signContentStream(contentStream, privateKey, certificateChain)
        }

        doc.addSignature(pdSignature, signatureInterface, SignatureOptions())
    }

    private fun signContentStream(
        content: InputStream,
        privateKey: PrivateKey,
        chain: Array<Certificate>
    ): ByteArray {
        val certList = chain.map { it as X509Certificate }
        val certStore = JcaCertStore(certList)

        val gen = CMSSignedDataGenerator()
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(privateKey)

        gen.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(
                JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
            ).build(contentSigner, certList[0])
        )
        gen.addCertificates(certStore)

        val contentBytes = content.readBytes()
        val msg = CMSProcessableByteArray(contentBytes)
        val signedData = gen.generate(msg, false)

        return signedData.encoded
    }
}
