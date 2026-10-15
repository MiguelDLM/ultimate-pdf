package org.ultimatepdf.ui.tools

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ultimatepdf.R
import org.ultimatepdf.core.pdf.PageRef
import org.ultimatepdf.core.pdf.PdfThumbnails
import org.ultimatepdf.ui.components.PageThumbnail
import org.ultimatepdf.ui.components.displayName
import org.ultimatepdf.ui.components.formatBytes
import org.ultimatepdf.ui.components.rememberPdfPicker

/** The PDF a single-file tool works on. */
data class SourceInfo(val uri: Uri, val name: String, val pageCount: Int, val sizeBytes: Long)

suspend fun loadSourceInfo(context: Context, thumbnails: PdfThumbnails, uri: Uri): SourceInfo? {
    val pages = thumbnails.pageCount(uri)
    if (pages == 0) return null
    val size = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
    }
    return SourceInfo(uri, displayName(context, uri), pages, size)
}

/**
 * Resolves the tool's source: the [initial] document when the tool was opened from the
 * reader, otherwise asks for a file right away. [content] receives a "pick another" action.
 */
@Composable
fun SourcePicker(
    initial: Uri?,
    thumbnails: PdfThumbnails,
    onSource: (SourceInfo) -> Unit,
    content: @Composable (pick: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnSource by rememberUpdatedState(onSource)
    val pick = rememberPdfPicker { uri ->
        scope.launch {
            val info = loadSourceInfo(context, thumbnails, uri)
            if (info != null) currentOnSource(info) else Toast.makeText(context, R.string.error_open_pdf, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(initial) {
        val info = initial?.let { loadSourceInfo(context, thumbnails, it) }
        if (info != null) currentOnSource(info) else pick()
    }
    content(pick)
}

@Composable
fun SourceCard(info: SourceInfo, thumbnails: PdfThumbnails, onChange: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Box(Modifier.width(44.dp)) {
                PageThumbnail(PageRef(info.uri, 0), thumbnails, widthHint = 56.dp, borderColor = MaterialTheme.colorScheme.outlineVariant)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        pluralStringResource(R.plurals.page_count, info.pageCount, info.pageCount),
                        info.sizeBytes.takeIf { it > 0 }?.let(::formatBytes)
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onChange) { Text(stringResource(R.string.change)) }
        }
    }
}

@Composable
fun EmptySource(padding: PaddingValues, pick: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Button(onClick = pick) { Text(stringResource(R.string.choose_pdf)) }
    }
}
