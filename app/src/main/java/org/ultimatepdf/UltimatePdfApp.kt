package org.ultimatepdf

import android.app.Application
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Ultimate PDF Application entry point.
 * Initializes PDFBox Android native resources and registers BouncyCastle security provider
 * for PAdES / PKCS#7 digital signatures.
 */
class UltimatePdfApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            // Initialize PDFBox-Android font and glyph resources
            PDFBoxResourceLoader.init(applicationContext)
            Log.i(TAG, "PDFBoxResourceLoader initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PDFBoxResourceLoader", e)
        }

        try {
            // Register BouncyCastle provider for cryptographic PDF signing and AES-256 encryption
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
            Log.i(TAG, "BouncyCastle security provider registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register BouncyCastle provider", e)
        }
    }

    companion object {
        private const val TAG = "UltimatePdfApp"
        lateinit var instance: UltimatePdfApp
            private set
    }
}
