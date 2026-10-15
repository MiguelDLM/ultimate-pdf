package org.ultimatepdf.ui.tools

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ultimatepdf.R
import org.ultimatepdf.core.export.ExportFormat
import org.ultimatepdf.core.export.ExportOptions
import org.ultimatepdf.core.export.PdfExporter
import org.ultimatepdf.core.pdf.PdfThumbnails
import org.ultimatepdf.ui.components.ScreenTopBar
import org.ultimatepdf.ui.components.rememberSaveFileLauncher
import java.io.File

/** Converts a PDF to Word, Excel, plain text or Markdown. */
@Composable
fun ExportScreen(
    initialSource: Uri?,
    thumbnails: PdfThumbnails,
    exporter: PdfExporter,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<SourceInfo?>(null) }
    var format by remember { mutableStateOf(ExportFormat.WORD) }
    var includeImages by remember { mutableStateOf(true) }
    var useOcr by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<String?>(null) }

    val saveLaunchers = ExportFormat.entries.associateWith { f ->
        rememberSaveFileLauncher(mimeType = f.mimeType) { uri ->
            progress = null
            if (uri != null) Toast.makeText(context, R.string.saved_title, Toast.LENGTH_SHORT).show()
        }
    }

    fun export(info: SourceInfo) {
        scope.launch {
            val output = File(context.cacheDir, "export_${System.currentTimeMillis()}.${format.extension}")
            try {
                val result = exporter.export(info.uri, ExportOptions(format, includeImages, useOcr), output) { page, total ->
                    progress = context.getString(R.string.export_progress, page, total)
                }
                when {
                    result.empty -> {
                        progress = null
                        output.delete()
                        Toast.makeText(context, R.string.export_empty, Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        if (result.ocrPages > 0) {
                            Toast.makeText(context, context.getString(R.string.export_done_ocr, result.ocrPages), Toast.LENGTH_LONG).show()
                        }
                        val name = info.name.removeSuffix(".pdf").removeSuffix(".PDF")
                        saveLaunchers.getValue(format).save("$name.${format.extension}", output)
                    }
                }
            } catch (e: Exception) {
                progress = null
                output.delete()
                Toast.makeText(context, R.string.export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    SourcePicker(initialSource, thumbnails, onSource = { source = it }) { pick ->
        Scaffold(topBar = { ScreenTopBar(stringResource(R.string.tool_export_short), onBack) }) { padding ->
            val info = source
            if (info == null) {
                EmptySource(padding, pick)
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                        .navigationBarsPadding()
                ) {
                    SourceCard(info, thumbnails, pick)
                    Column {
                        listOf(
                            ExportFormat.WORD to R.string.export_word,
                            ExportFormat.EXCEL to R.string.export_excel,
                            ExportFormat.TEXT to R.string.export_text,
                            ExportFormat.MARKDOWN to R.string.export_markdown
                        ).forEach { (f, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                RadioButton(selected = format == f, onClick = { format = f })
                                Text(stringResource(label), modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    if (format == ExportFormat.WORD || format == ExportFormat.MARKDOWN) {
                        if (format == ExportFormat.WORD) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.export_images), Modifier.weight(1f))
                                Switch(includeImages, { includeImages = it })
                            }
                        }
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.export_ocr), Modifier.weight(1f))
                            Switch(useOcr, { useOcr = it })
                        }
                        Text(
                            stringResource(R.string.export_ocr_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { export(info) }, enabled = progress == null, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.export_button))
                    }
                }
            }
        }
    }

    progress?.let { message ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(message)
                }
            }
        )
    }
}
