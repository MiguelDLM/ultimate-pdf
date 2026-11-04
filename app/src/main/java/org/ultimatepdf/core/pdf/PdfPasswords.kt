package org.ultimatepdf.core.pdf

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** Thrown when a PDF needs a password to open; [wrongPassword] is true if one was already tried and failed. */
class PasswordRequiredException(val wrongPassword: Boolean) : Exception("PDF is password protected")

/**
 * In-memory registry of the passwords the user typed for protected PDFs (never written to disk).
 *
 * Everything that parses a PDF goes through [load] / [openStream] so protected files work in every tool.
 * [load] keeps the file's protection when saved (annotations stay in a still-protected file);
 * [openStream] yields an unprotected copy, for read-only uses such as export, OCR and rendering.
 * Android's [android.graphics.pdf.PdfRenderer] can't open protected files before API 35, so
 * [openRenderFd] hands it a decrypted copy kept in the app cache.
 */
object PdfPasswords {

    private val passwords = ConcurrentHashMap<Uri, String>()

    fun remember(uri: Uri, password: String) { passwords[uri] = password }

    fun hasPassword(uri: Uri): Boolean = passwords.containsKey(uri)

    fun forget(uri: Uri) {
        passwords.remove(uri)
        // The decrypted copy must not outlive the password.
        // (Context-free: the cache dir is tracked when a copy is created.)
        decryptedFiles.remove(uri)?.delete()
    }

    private val decryptedFiles = ConcurrentHashMap<Uri, File>()

    /** Parses [uri] with its remembered password (or none). Throws [PasswordRequiredException] if that isn't enough. */
    fun load(context: Context, uri: Uri): PDDocument {
        val known = passwords[uri]
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot read $uri")
        return try {
            input.use { PDDocument.load(it, known ?: "", MemoryUsageSetting.setupTempFileOnly()) }
        } catch (e: InvalidPasswordException) {
            throw PasswordRequiredException(wrongPassword = known != null)
        }
    }

    /** True if [uri] needs a password that hasn't been supplied yet (or the supplied one is wrong). */
    fun needsPassword(context: Context, uri: Uri): Boolean = try {
        load(context, uri).close()
        false
    } catch (e: PasswordRequiredException) {
        true
    }

    /** Checks [password] against [uri]; on success remembers it. */
    fun tryUnlock(context: Context, uri: Uri, password: String): Boolean {
        val input = context.contentResolver.openInputStream(uri) ?: return false
        return try {
            input.use { PDDocument.load(it, password, MemoryUsageSetting.setupTempFileOnly()).close() }
            remember(uri, password)
            true
        } catch (e: InvalidPasswordException) {
            false
        }
    }

    /** A stream with the file's bytes, decrypted if it is protected. Caller closes it. */
    fun openStream(context: Context, uri: Uri): InputStream? {
        val known = passwords[uri]
        val raw = context.contentResolver.openInputStream(uri) ?: return null
        if (known == null && !isEncrypted(context, uri)) return raw
        raw.close()
        val copy = decryptedCopy(context, uri, refresh = false)
        return FileInputStream(copy)
    }

    /** A descriptor [android.graphics.pdf.PdfRenderer] can read: the file itself, or a decrypted copy. */
    fun openRenderFd(context: Context, uri: Uri, refresh: Boolean = false): ParcelFileDescriptor? {
        if (passwords[uri] == null) return context.contentResolver.openFileDescriptor(uri, "r")
        val copy = decryptedCopy(context, uri, refresh)
        return ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun isEncrypted(context: Context, uri: Uri): Boolean = passwords[uri] != null || needsPassword(context, uri)

    @Synchronized
    private fun decryptedCopy(context: Context, uri: Uri, refresh: Boolean): File {
        decryptedFiles[uri]?.let { if (!refresh && it.exists()) return it }
        decryptedFiles.remove(uri)?.delete()
        val dir = File(context.cacheDir, "unlocked").apply { mkdirs() }
        val file = File(dir, "u_${System.nanoTime()}.pdf")
        load(context, uri).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            file.outputStream().use { doc.save(it) }
        }
        decryptedFiles[uri] = file
        return file
    }

    /** Removes decrypted copies left by an earlier run of the app. */
    fun clearStaleCopies(context: Context) {
        File(context.cacheDir, "unlocked").listFiles()?.forEach { it.delete() }
    }
}
