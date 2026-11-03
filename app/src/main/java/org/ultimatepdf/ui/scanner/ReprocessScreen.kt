package org.ultimatepdf.ui.scanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ultimatepdf.R
import org.ultimatepdf.ui.components.ScreenTopBar
import org.ultimatepdf.ui.components.displayName
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Runs an existing PDF (old scans, scans from other apps) through the scanner pipeline again:
 * each page becomes an image in the scan review, where filters, crop, straightening, rotation
 * and OCR apply as for a fresh capture. Saving creates a new PDF; the original is untouched.
 */
@Composable
fun ReprocessScreen(source: Uri?, onPdfCreated: (Uri) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val workDir = remember { File(context.cacheDir, "reprocess_${System.currentTimeMillis()}") }
    var pages by remember { mutableStateOf<List<Uri>?>(null) }
    var progress by remember { mutableStateOf(0 to 0) }

    DisposableEffect(Unit) { onDispose { workDir.deleteRecursively() } }
    LaunchedEffect(source) {
        if (source == null) { onBack(); return@LaunchedEffect }
        val rendered = runCatching { renderPages(context, source, workDir) { done, total -> progress = done to total } }.getOrNull()
        if (rendered.isNullOrEmpty()) {
            Toast.makeText(context, R.string.reprocess_failed, Toast.LENGTH_LONG).show()
            onBack()
        } else {
            pages = rendered.map { Uri.fromFile(it) }
        }
    }

    val ready = pages
    if (ready == null) {
        Scaffold(topBar = { ScreenTopBar(stringResource(R.string.tool_reprocess), onBack) }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                val (done, total) = progress
                Text(
                    if (total > 0) stringResource(R.string.reprocess_preparing, done, total) else stringResource(R.string.scan_loading),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }

    val baseName = remember(source) { source?.let { displayName(context, it).substringBeforeLast('.') } ?: "document" }
    DocumentScannerScreen(
        onPdfCreated = onPdfCreated,
        onBack = onBack,
        initialImages = ready,
        fullFrameImages = true,
        suggestedName = context.getString(R.string.reprocess_file_name, baseName)
    )
}

/** Renders every page to a JPEG (long side [maxSize] px, white background) in [dir]. */
private suspend fun renderPages(
    context: android.content.Context,
    uri: Uri,
    dir: File,
    maxSize: Int = MAX_PHOTO_SIZE,
    onProgress: (done: Int, total: Int) -> Unit
): List<File> = withContext(Dispatchers.IO) {
    dir.mkdirs()
    val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext emptyList()
    pfd.use {
        PdfRenderer(it).use { renderer ->
            val total = renderer.pageCount
            (0 until total).map { index ->
                withContext(Dispatchers.Main) { onProgress(index, total) }
                renderer.openPage(index).use { page ->
                    val scale = maxSize.toFloat() / max(page.width, page.height)
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    val file = File(dir, "page_%04d.jpg".format(index))
                    FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }
                    bitmap.recycle()
                    file
                }
            }
        }
    }
}
