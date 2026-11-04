package org.ultimatepdf.core.pdf

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The handful of facts about a document a user usually cares about. Null = unknown. */
data class DocumentDetails(
    val sizeBytes: Long?,
    val path: String?,
    val modifiedMillis: Long?,
    /** Creation date stored inside the PDF (the file system doesn't expose one on Android). */
    val createdMillis: Long?,
    val title: String?,
    val author: String?
)

/** Reads [DocumentDetails] for [uri]; every field is best effort. */
suspend fun readDocumentDetails(context: Context, uri: Uri): DocumentDetails = withContext(Dispatchers.IO) {
    var title: String? = null
    var author: String? = null
    var created: Long? = null
    runCatching {
        run {
            PdfPasswords.load(context, uri).use { doc ->
                val info = doc.documentInformation
                title = info.title?.takeIf { it.isNotBlank() }
                author = info.author?.takeIf { it.isNotBlank() }
                created = info.creationDate?.timeInMillis
            }
        }
    }
    DocumentDetails(
        sizeBytes = querySize(context, uri),
        path = resolvePath(context, uri),
        modifiedMillis = lastModified(context, uri),
        createdMillis = created,
        title = title,
        author = author
    )
}

private fun lastModified(context: Context, uri: Uri): Long? {
    if (uri.scheme == "file") return uri.path?.let { File(it).lastModified().takeIf { t -> t > 0 } }
    return runCatching {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { t -> t > 0 } else null }
    }.getOrNull()
}

/** File size in bytes, or null when the provider can't say (a file descriptor's own size can be -1). */
fun querySize(context: Context, uri: Uri): Long? {
    if (uri.scheme == "file") return uri.path?.let { File(it).length() }
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { size -> size >= 0 }?.let { size -> return size }
        }
    }
    runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
            fd.length.takeIf { it >= 0 }?.let { return it }
        }
    }
    // Last resort: count the bytes.
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
            total
        }
    }.getOrNull()
}

/** A human-readable location: the real path when it can be worked out, otherwise null. */
private fun resolvePath(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") return uri.path
    if (uri.scheme != "content") return null
    fdPath(context, uri)?.let { return it }
    val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
    if (docId != null) {
        when (uri.authority) {
            "com.android.externalstorage.documents" -> {
                val (volume, rel) = docId.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val root = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory().path else "/storage/$volume"
                return "$root/$rel".trimEnd('/')
            }
            "com.android.providers.downloads.documents", "com.android.providers.media.documents" -> {
                if (docId.startsWith("raw:")) return docId.removePrefix("raw:")
                // "msf:1234", "document:1234", "image:1234" or a bare number: a row in MediaStore.
                val id = docId.substringAfter(':').toLongOrNull() ?: docId.toLongOrNull()
                if (id != null) mediaStorePath(context, id)?.let { return it }
            }
        }
    }
    // Media or file URIs from other apps (content://media/external/...).
    runCatching { ContentUris.parseId(uri) }.getOrNull()?.let { id -> mediaStorePath(context, id)?.let { return it } }
    return runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0)?.takeIf { p -> p.startsWith("/") } else null }
    }.getOrNull()
}

/**
 * Asks the kernel where an open file descriptor points (/proc/self/fd/N). Providers backed by
 * real storage (Downloads, MediaStore, file managers) hand out descriptors on the actual file,
 * so this works without storage permissions, which Android 13+ no longer grants for PDFs.
 */
private fun fdPath(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        val target = android.system.Os.readlink("/proc/self/fd/${pfd.fd}")
        normalizeStoragePath(target)
    }
}.getOrNull()

/** Maps internal mount points to the path users know; null for pipes, sockets and app-private files. */
private fun normalizeStoragePath(raw: String): String? {
    val path = raw.removeSuffix(" (deleted)")
    if (!path.startsWith("/")) return null
    val mapped = when {
        path.startsWith("/data/media/") -> "/storage/emulated/" + path.removePrefix("/data/media/")
        path.startsWith("/mnt/user/") -> "/storage/" + path.removePrefix("/mnt/user/").substringAfter('/')
        path.startsWith("/mnt/pass_through/") -> "/storage/" + path.removePrefix("/mnt/pass_through/").substringAfter('/')
        path.startsWith("/mnt/media_rw/") -> "/storage/" + path.removePrefix("/mnt/media_rw/")
        else -> path
    }
    return mapped.takeIf { it.startsWith("/storage/") || it.startsWith("/sdcard/") }
}

private fun mediaStorePath(context: Context, id: Long): String? {
    val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
    val projection = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
    return runCatching {
        context.contentResolver.query(uri, projection, null, null, null)?.use {
            if (!it.moveToFirst()) return@use null
            val data = it.getString(0)
            if (!data.isNullOrBlank() && data.startsWith("/")) return@use data
            val relative = it.getString(1)
            val name = it.getString(2)
            if (relative != null && name != null) Environment.getExternalStorageDirectory().path + "/" + relative.trimEnd('/') + "/" + name
            else null
        }
    }.getOrNull()
}
