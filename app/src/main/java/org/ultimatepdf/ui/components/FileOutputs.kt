package org.ultimatepdf.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ultimatepdf.R
import java.io.File

/** Copies a finished temp file to a location the user picks ("Save as"). */
class SaveFileLauncher internal constructor(private val launch: (String, File) -> Unit) {
    fun save(suggestedName: String, file: File) = launch(suggestedName, file)
}

@Composable
fun rememberSaveFileLauncher(
    mimeType: String = "application/pdf",
    onResult: (Uri?) -> Unit
): SaveFileLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    var pending by remember { mutableStateOf<File?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { target ->
        val source = pending
        pending = null
        if (target == null || source == null) {
            currentOnResult(null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val ok = runCatching { copyToUri(context, source, target) }.isSuccess
            source.delete()
            currentOnResult(if (ok) target else null)
        }
    }
    return remember(launcher) {
        SaveFileLauncher { name, file ->
            pending = file
            launcher.launch(name)
        }
    }
}

/** Writes several finished files into a folder the user picks. Reports how many were saved. */
class SaveToFolderLauncher internal constructor(private val launch: (List<File>) -> Unit) {
    fun save(files: List<File>) = launch(files)
}

@Composable
fun rememberSaveToFolderLauncher(onResult: (saved: Int, folderName: String?) -> Unit): SaveToFolderLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    var pending by remember { mutableStateOf<List<File>>(emptyList()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val files = pending
        pending = emptyList()
        if (tree == null) {
            currentOnResult(0, null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val folder = DocumentFile.fromTreeUri(context, tree)
            var saved = 0
            withContext(Dispatchers.IO) {
                for (file in files) {
                    val mime = if (file.extension == "pdf") "application/pdf" else "image/${file.extension.replace("jpg", "jpeg")}"
                    val target = folder?.createFile(mime, file.nameWithoutExtension) ?: continue
                    if (runCatching { copyToUri(context, file, target.uri) }.isSuccess) saved++
                    file.delete()
                }
            }
            currentOnResult(saved, folder?.name)
        }
    }
    return remember(launcher) {
        SaveToFolderLauncher { files ->
            pending = files
            launcher.launch(null)
        }
    }
}

/** Picks one PDF and keeps read/write access to it across launches (for recents). */
@Composable
fun rememberPdfPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        keepAccess(context, uri)
        currentOnPicked(uri)
    }
    return { launcher.launch(arrayOf("application/pdf")) }
}

/** Picks several PDFs at once. */
@Composable
fun rememberMultiPdfPicker(onPicked: (List<Uri>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { keepAccess(context, it) }
        if (uris.isNotEmpty()) currentOnPicked(uris)
    }
    return { launcher.launch(arrayOf("application/pdf")) }
}

fun keepAccess(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    } catch (_: SecurityException) {
        // Some providers only grant read access; reading is enough for most tools.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}

suspend fun copyToUri(context: Context, source: File, target: Uri) = withContext(Dispatchers.IO) {
    val out = context.contentResolver.openOutputStream(target, "wt") ?: throw IllegalStateException("Cannot write $target")
    out.use { stream -> source.inputStream().use { it.copyTo(stream) } }
}

/** Display name of a content URI, falling back to its last path segment. */
fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}

fun sharePdf(context: Context, uri: Uri, name: String? = null) {
    try {
        val fileName = name ?: displayName(context, uri)
        val shareUri = if (uri.scheme == "file") {
            val file = File(uri.path ?: "")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else if (uri.scheme == "content") {
            val cleanName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "document.pdf" }
            val cacheFile = File(context.cacheDir, "shared_$cleanName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
        } else {
            uri
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.share)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.share_error, e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

fun deleteFileFromDevice(context: Context, uri: Uri): Boolean {
    return try {
        if (uri.scheme == "file") {
            File(uri.path ?: "").delete()
        } else if (uri.scheme == "content") {
            val docDeleted = runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }.getOrDefault(false)
            if (docDeleted) true
            else {
                val docFile = DocumentFile.fromSingleUri(context, uri)
                if (docFile != null && docFile.exists() && docFile.delete()) {
                    true
                } else {
                    context.contentResolver.delete(uri, null, null) > 0
                }
            }
        } else {
            false
        }
    } catch (_: Exception) {
        false
    }
}
