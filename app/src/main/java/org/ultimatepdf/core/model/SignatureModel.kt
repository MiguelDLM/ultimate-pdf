package org.ultimatepdf.core.model

import android.graphics.Bitmap

enum class SignatureLevel {
    PADES_B,    // Basic digital signature
    PADES_T,    // With TSA RFC 3161 timestamp
    PADES_LT,   // With Long Term Validation (CRL/OCSP)
    VISUAL_ONLY // Embedded graphical stamp without cryptographic certificate
}

data class DigitalSignatureConfig(
    val pageIndex: Int = 0,
    val visualRect: PdfRect = PdfRect(100f, 100f, 300f, 200f),
    val signerName: String = "",
    val reason: String = "Document Approval",
    val location: String = "",
    val contactInfo: String = "",
    val signatureLevel: SignatureLevel = SignatureLevel.PADES_B,
    val certificateUri: String? = null,
    val certificatePassword: String = "",
    val tsaServerUrl: String = "",
    val handwrittenBitmap: Bitmap? = null
)

/**
 * A reusable vector signature. Points are normalized to the signature's own bounding box
 * (0..1 on both axes); [aspect] is that box's width / height.
 *
 * [filled] shapes are closed outlines filled with the even-odd rule (vectorized photos);
 * otherwise the contours are open strokes [strokeWidth] wide, as a fraction of the box width.
 */
data class SignatureShape(
    val contours: List<List<PdfPoint>>,
    val filled: Boolean,
    val strokeWidth: Float,
    val aspect: Float
) {
    /** Compact text form, used both for the signature library and inside the PDF. */
    fun encode(): String = buildString {
        append("v1;").append(if (filled) 1 else 0).append(';')
        append(strokeWidth).append(';').append(aspect).append(';')
        contours.forEachIndexed { i, contour ->
            if (i > 0) append('|')
            contour.forEachIndexed { j, p ->
                if (j > 0) append(' ')
                append(round4(p.x)).append(',').append(round4(p.y))
            }
        }
    }

    companion object {
        fun decode(data: String): SignatureShape? = try {
            val parts = data.split(';', limit = 5)
            if (parts.size < 5 || parts[0] != "v1") null else SignatureShape(
                filled = parts[1] == "1",
                strokeWidth = parts[2].toFloat(),
                aspect = parts[3].toFloat(),
                contours = parts[4].split('|').filter { it.isNotBlank() }.map { contour ->
                    contour.split(' ').map { point ->
                        val (x, y) = point.split(',')
                        PdfPoint(x.toFloat(), y.toFloat())
                    }
                }
            )
        } catch (e: Exception) {
            null
        }

        private fun round4(v: Float): String = "%.4f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')
    }
}
