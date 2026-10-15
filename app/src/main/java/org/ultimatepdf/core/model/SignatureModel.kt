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
