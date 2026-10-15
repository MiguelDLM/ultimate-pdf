package org.ultimatepdf.ui.tools

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ultimatepdf.R
import org.ultimatepdf.core.pdf.PdfPageOps
import org.ultimatepdf.core.pdf.PdfThumbnails
import org.ultimatepdf.core.pdf.SplitPlan
import org.ultimatepdf.core.pdf.SplitPlanner
import org.ultimatepdf.ui.components.ScreenTopBar
import org.ultimatepdf.ui.components.formatBytes
import org.ultimatepdf.ui.components.rememberSaveToFolderLauncher
import java.io.File

private enum class SplitBy { PAGES, PARTS, SIZE }

/**
 * Splits a PDF every N pages, into N files, or into files no bigger than a size. Size
 * splitting measures every page first and refuses limits a single page can't meet.
 */
@Composable
fun SplitScreen(
    initialSource: Uri?,
    thumbnails: PdfThumbnails,
    pageOps: PdfPageOps,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<SourceInfo?>(null) }
    var by by remember { mutableStateOf(SplitBy.PAGES) }
    var input by remember { mutableStateOf("") }
    var pageSizes by remember { mutableStateOf<List<Long>?>(null) }
    var analyzeProgress by remember { mutableStateOf<Float?>(null) }
    var splitProgress by remember { mutableStateOf<Float?>(null) }
    val saveToFolder = rememberSaveToFolderLauncher { saved, folder ->
        splitProgress = null
        if (saved > 0) {
            Toast.makeText(context, context.resources.getQuantityString(R.plurals.split_saved, saved, saved, folder ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    SourcePicker(initialSource, thumbnails, onSource = {
        source = it
        pageSizes = null
    }) { pick ->
        val info = source
        Scaffold(topBar = { ScreenTopBar(stringResource(R.string.tool_split), onBack) }) { padding ->
            if (info == null) {
                EmptySource(padding, pick)
                return@Scaffold
            }

            // Size mode needs per-page sizes; measure them once per file.
            LaunchedEffect(info.uri, by) {
                if (by == SplitBy.SIZE && pageSizes == null) {
                    analyzeProgress = 0f
                    pageSizes = runCatching { pageOps.pageSizes(info.uri) { analyzeProgress = it } }.getOrNull()
                    analyzeProgress = null
                }
            }

            val number = input.replace(',', '.').toDoubleOrNull()
            val plan: SplitPlan? = when (by) {
                SplitBy.PAGES -> number?.toInt()?.let { SplitPlanner.everyNPages(info.pageCount, it) }
                SplitBy.PARTS -> number?.toInt()?.let { SplitPlanner.intoParts(info.pageCount, it) }
                SplitBy.SIZE -> {
                    val sizes = pageSizes
                    if (number == null || sizes == null) null
                    else SplitPlanner.bySize(sizes, (number * 1024 * 1024).toLong())
                }
            }
            val parts = (plan as? SplitPlan.Parts)?.ranges

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SourceCard(info, thumbnails, onChange = pick)

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(
                        SplitBy.PAGES to R.string.split_by_pages,
                        SplitBy.PARTS to R.string.split_by_parts,
                        SplitBy.SIZE to R.string.split_by_size
                    ).forEachIndexed { i, (option, label) ->
                        SegmentedButton(
                            selected = by == option,
                            onClick = {
                                by = option
                                input = ""
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, 3)
                        ) { Text(stringResource(label), maxLines = 1) }
                    }
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { value -> input = value.filter { it.isDigit() || (by == SplitBy.SIZE && (it == '.' || it == ',')) }.take(7) },
                    label = {
                        Text(
                            stringResource(
                                when (by) {
                                    SplitBy.PAGES -> R.string.split_pages_label
                                    SplitBy.PARTS -> R.string.split_parts_label
                                    SplitBy.SIZE -> R.string.split_size_label
                                }
                            )
                        )
                    },
                    suffix = if (by == SplitBy.SIZE) ({ Text("MB") }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (by == SplitBy.SIZE) KeyboardType.Decimal else KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                analyzeProgress?.let {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.split_analyzing), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                    }
                }

                val message: String? = when {
                    input.isBlank() -> null
                    plan == null && by == SplitBy.SIZE && pageSizes == null -> null
                    plan is SplitPlan.PageTooLarge -> stringResource(
                        R.string.split_page_too_large, plan.pageIndex + 1, formatBytes(plan.bytes), "$input MB"
                    )
                    plan == null || plan is SplitPlan.Invalid -> when (by) {
                        SplitBy.PARTS -> stringResource(R.string.split_parts_invalid, info.pageCount)
                        SplitBy.SIZE -> stringResource(R.string.split_size_invalid)
                        SplitBy.PAGES -> stringResource(R.string.split_pages_invalid, info.pageCount)
                    }
                    parts != null && parts.size == 1 -> stringResource(R.string.split_single_part)
                    else -> null
                }
                message?.let { Notice(it, isError = plan !is SplitPlan.Parts) }

                if (parts != null && parts.size > 1) {
                    Text(pluralStringResource(R.plurals.split_result, parts.size, parts.size), style = MaterialTheme.typography.titleSmall)
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            parts.take(MAX_PREVIEW).forEachIndexed { i, range ->
                                val estimate = pageSizes?.takeIf { by == SplitBy.SIZE }?.let { sizes -> range.sumOf { sizes[it] } }
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text(stringResource(R.string.split_file_n, i + 1), modifier = Modifier.weight(1f))
                                    Text(
                                        listOfNotNull(
                                            if (range.first == range.last) stringResource(R.string.page_short, range.first + 1)
                                            else stringResource(R.string.page_range, range.first + 1, range.last + 1),
                                            estimate?.let { "≤ " + formatBytes(it) }
                                        ).joinToString(" · "),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (parts.size > MAX_PREVIEW) {
                                Text(
                                    stringResource(R.string.split_more_files, parts.size - MAX_PREVIEW),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                    if (by == SplitBy.SIZE) Notice(stringResource(R.string.split_size_note), isError = false)
                }

                splitProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }

                Button(
                    onClick = {
                        val ranges = parts ?: return@Button
                        scope.launch {
                            splitProgress = 0f
                            val dir = File(context.cacheDir, "split_${System.currentTimeMillis()}")
                            val files = runCatching {
                                pageOps.split(
                                    info.uri, ranges, dir, info.name.removeSuffix(".pdf"),
                                    maxBytes = if (by == SplitBy.SIZE) (number!! * 1024 * 1024).toLong() else null
                                ) { splitProgress = it }
                            }.getOrNull()
                            if (files.isNullOrEmpty()) {
                                splitProgress = null
                                Toast.makeText(context, R.string.save_failed, Toast.LENGTH_LONG).show()
                            } else {
                                saveToFolder.save(files)
                            }
                        }
                    },
                    enabled = parts != null && parts.size > 1 && splitProgress == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .height(52.dp)
                ) {
                    Text(
                        if (parts != null && parts.size > 1) pluralStringResource(R.plurals.split_action, parts.size, parts.size)
                        else stringResource(R.string.tool_split)
                    )
                }
            }
        }
    }
}

private const val MAX_PREVIEW = 8

@Composable
fun Notice(text: String, isError: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
