package org.ultimatepdf.ui.tools

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ultimatepdf.R
import org.ultimatepdf.core.pdf.PageRef
import org.ultimatepdf.core.pdf.PdfPageOps
import org.ultimatepdf.core.pdf.PdfThumbnails
import org.ultimatepdf.core.pdf.parsePageRanges
import org.ultimatepdf.ui.components.PageThumbnail
import org.ultimatepdf.ui.components.ScreenTopBar
import org.ultimatepdf.ui.components.displayName
import org.ultimatepdf.ui.components.draggableItem
import org.ultimatepdf.ui.components.rememberGridReorderState
import org.ultimatepdf.ui.components.rememberMultiPdfPicker
import org.ultimatepdf.ui.components.rememberPdfPicker
import org.ultimatepdf.ui.components.rememberSaveFileLauncher
import org.ultimatepdf.ui.components.reorderable
import org.ultimatepdf.ui.editor.ActionItem
import org.ultimatepdf.ui.editor.SelectionBadge
import java.io.File

enum class ComposerMode { MERGE, EXTRACT }

private data class SourceFile(val uri: Uri, val name: String, val pageCount: Int, val color: Color)

private data class ComposerPage(val id: Long, val ref: PageRef, val included: Boolean)

/** Muted tags telling apart pages from different files. */
private val SourceColors = listOf(
    Color(0xFF2563EB), Color(0xFFD97706), Color(0xFF059669), Color(0xFFDB2777), Color(0xFF7C3AED), Color(0xFF0891B2)
)

/**
 * Builds a new PDF from chosen pages. MERGE starts with every page of every file included;
 * EXTRACT starts with none. Tap a page to include or exclude it, long-press and drag to set
 * its position; the numbers on the included pages are their order in the result.
 */
@Composable
fun PageComposerScreen(
    mode: ComposerMode,
    initialSources: List<Uri>,
    thumbnails: PdfThumbnails,
    pageOps: PdfPageOps,
    onOpenResult: (Uri) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources = remember { mutableStateListOf<SourceFile>() }
    val pages = remember { mutableStateListOf<ComposerPage>() }
    var nextId by remember { mutableLongStateOf(0L) }
    var onlyIncluded by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }

    fun addSources(uris: List<Uri>) {
        scope.launch {
            for (uri in uris) {
                if (sources.any { it.uri == uri }) continue
                val count = thumbnails.pageCount(uri)
                if (count == 0) {
                    Toast.makeText(context, R.string.error_open_pdf, Toast.LENGTH_SHORT).show()
                    continue
                }
                sources += SourceFile(uri, displayName(context, uri), count, SourceColors[sources.size % SourceColors.size])
                repeat(count) { i -> pages += ComposerPage(nextId++, PageRef(uri, i), included = mode == ComposerMode.MERGE) }
            }
        }
    }

    val pickMany = rememberMultiPdfPicker { addSources(it) }
    val pickOne = rememberPdfPicker { addSources(listOf(it)) }
    val pickSources = if (mode == ComposerMode.MERGE) pickMany else pickOne

    LaunchedEffect(Unit) {
        if (initialSources.isEmpty()) pickSources() else addSources(initialSources)
    }

    val saveLauncher = rememberSaveFileLauncher { uri ->
        isWorking = false
        if (uri != null) savedUri = uri
    }

    val shown = if (onlyIncluded) pages.filter { it.included } else pages
    val includedCount = pages.count { it.included }
    // Output order: position among included pages, in grid order.
    val outputOrder = remember(pages.toList()) {
        pages.filter { it.included }.withIndex().associate { (i, p) -> p.id to i + 1 }
    }

    val gridState = rememberLazyGridState()
    val reorder = rememberGridReorderState(gridState, GridPadding) { from, to ->
        val fromIndex = pages.indexOfFirst { it.id == shown[from].id }
        val toIndex = pages.indexOfFirst { it.id == shown[to].id }
        pages.add(toIndex, pages.removeAt(fromIndex))
    }

    val title = stringResource(if (mode == ComposerMode.MERGE) R.string.tool_merge else R.string.tool_extract_pages)
    Scaffold(
        topBar = {
            ScreenTopBar(title, onBack) {
                Button(
                    onClick = {
                        scope.launch {
                            isWorking = true
                            val out = File(context.cacheDir, "composed_${System.currentTimeMillis()}.pdf")
                            val ok = runCatching { pageOps.compose(pages.filter { it.included }.map { it.ref }, out) }.isSuccess
                            if (!ok) {
                                isWorking = false
                                Toast.makeText(context, R.string.save_failed, Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            val base = sources.firstOrNull()?.name?.removeSuffix(".pdf") ?: "document"
                            val suggested = if (mode == ComposerMode.MERGE) {
                                context.getString(R.string.merge_file_name, base)
                            } else {
                                context.getString(R.string.extract_file_name, base)
                            }
                            saveLauncher.save(suggested, out)
                        }
                    },
                    enabled = includedCount > 0 && !isWorking,
                    modifier = Modifier.padding(end = 8.dp)
                ) { Text(stringResource(R.string.save)) }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(vertical = 6.dp)
                ) {
                    ActionItem(Icons.Default.SelectAll, stringResource(R.string.select_all)) {
                        pages.replaceAll { it.copy(included = true) }
                    }
                    ActionItem(Icons.Default.Deselect, stringResource(R.string.select_none)) {
                        pages.replaceAll { it.copy(included = false) }
                        onlyIncluded = false
                    }
                    ActionItem(Icons.Default.FilterList, stringResource(if (onlyIncluded) R.string.composer_show_all else R.string.composer_only_selected)) {
                        onlyIncluded = !onlyIncluded
                    }
                    ActionItem(Icons.Default.Sort, stringResource(R.string.composer_reset_order)) {
                        val order = sources.map { it.uri }
                        val sorted = pages.sortedWith(compareBy({ order.indexOf(it.ref.uri) }, { it.ref.pageIndex }))
                        pages.clear()
                        pages.addAll(sorted)
                    }
                }
            }
        }
    ) { padding ->
        if (sources.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Button(onClick = pickSources) {
                    Text(stringResource(if (mode == ComposerMode.MERGE) R.string.composer_choose_pdfs else R.string.choose_pdf))
                }
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (mode == ComposerMode.MERGE) {
                SourceChips(
                    sources = sources,
                    onAdd = pickMany,
                    onIncludeAll = { uri, include -> pages.replaceAll { if (it.ref.uri == uri) it.copy(included = include) else it } },
                    onRemove = { uri ->
                        sources.removeAll { it.uri == uri }
                        pages.removeAll { it.ref.uri == uri }
                    }
                )
            } else {
                sources.firstOrNull()?.let { source ->
                    RangeSelector(source.pageCount) { indices ->
                        // Include exactly the typed pages and put them first, in the typed order.
                        val chosen = indices.map { i -> pages.first { it.ref.pageIndex == i }.copy(included = true) }
                        val rest = pages.filter { it.ref.pageIndex !in indices }.map { it.copy(included = false) }
                        pages.clear()
                        pages.addAll(chosen + rest)
                    }
                }
            }
            Text(
                text = stringResource(R.string.composer_count, includedCount, pages.size) + " · " + stringResource(R.string.composer_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .reorderable(reorder),
                contentPadding = GridPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(shown, key = { _, p -> p.id }) { position, page ->
                    val source = sources.firstOrNull { it.uri == page.ref.uri }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .draggableItem(reorder, page.id)
                            .then(if (reorder.isDragging(page.id)) Modifier else Modifier.animateItem())
                    ) {
                        PageThumbnail(
                            page = page.ref,
                            thumbnails = thumbnails,
                            dimmed = !page.included,
                            borderColor = if (page.included) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant,
                            borderWidth = if (page.included) 2.dp else 1.dp,
                            modifier = Modifier.clickable {
                                val index = pages.indexOfFirst { it.id == page.id }
                                pages[index] = page.copy(included = !page.included)
                            }
                        ) {
                            if (page.included) {
                                SelectionBadge(Modifier.align(Alignment.TopEnd), label = outputOrder[page.id]?.toString())
                            } else {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(22.dp)
                                        .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            if (mode == ComposerMode.MERGE && source != null) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(source.color))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                stringResource(R.string.page_short, page.ref.pageIndex + 1),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (isWorking) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }

    savedUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { savedUri = null },
            title = { Text(stringResource(R.string.saved_title)) },
            text = { Text(pluralStringResource(R.plurals.saved_pages, includedCount, includedCount)) },
            confirmButton = {
                TextButton(onClick = {
                    savedUri = null
                    onOpenResult(uri)
                }) { Text(stringResource(R.string.open)) }
            },
            dismissButton = { TextButton(onClick = { savedUri = null }) { Text(stringResource(R.string.done)) } }
        )
    }
}

@Composable
private fun SourceChips(
    sources: List<SourceFile>,
    onAdd: () -> Unit,
    onIncludeAll: (Uri, Boolean) -> Unit,
    onRemove: (Uri) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        sources.forEach { source ->
            var menu by remember { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { menu = true },
                    label = {
                        Text(
                            "${source.name} · ${source.pageCount}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp)
                        )
                    },
                    leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(source.color)) }
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.composer_include_all)) }, onClick = { menu = false; onIncludeAll(source.uri, true) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.composer_exclude_all)) }, onClick = { menu = false; onIncludeAll(source.uri, false) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.composer_remove_file)) }, onClick = { menu = false; onRemove(source.uri) })
                }
            }
        }
        AssistChip(
            onClick = onAdd,
            label = { Text(stringResource(R.string.composer_add_pdf)) },
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
    }
}

@Composable
private fun RangeSelector(pageCount: Int, onApply: (List<Int>) -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = remember(text, pageCount) { parsePageRanges(text, pageCount) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            label = { Text(stringResource(R.string.composer_pages_label)) },
            placeholder = { Text(stringResource(R.string.composer_pages_placeholder)) },
            isError = text.isNotBlank() && parsed == null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { parsed?.let(onApply) }),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = { parsed?.let(onApply) }, enabled = parsed != null) {
            Text(stringResource(R.string.apply))
        }
    }
}

private val GridPadding = PaddingValues(12.dp)
