package org.ultimatepdf.ui.editor

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ultimatepdf.R
import org.ultimatepdf.core.model.*
import org.ultimatepdf.core.pdf.EditPlanProcessor
import org.ultimatepdf.core.pdf.PageRef
import org.ultimatepdf.core.pdf.PdfThumbnails
import org.ultimatepdf.ui.components.ScreenTopBar
import org.ultimatepdf.ui.components.draggableItem
import org.ultimatepdf.ui.components.rememberGridReorderState
import org.ultimatepdf.ui.components.rememberPdfPicker
import org.ultimatepdf.ui.components.reorderable
import org.ultimatepdf.ui.components.displayName
import java.io.File

/**
 * Page organizer: tap pages to select them, long-press and drag to reorder. Every change is
 * an [EditOp] in a non-destructive plan with undo / redo; saving rewrites the document.
 */
@Composable
fun PdfEditorScreen(
    currentDocument: DocumentModel?,
    editPlanProcessor: EditPlanProcessor,
    thumbnails: PdfThumbnails,
    onSave: suspend (File) -> Boolean,
    onBack: () -> Unit,
    isTablet: Boolean = false
) {
    if (currentDocument == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val ops = remember { mutableStateListOf<EditOp>() }
    val redo = remember { mutableStateListOf<EditOp>() }
    // Selection holds plan indices (positions in plan.pages, deleted pages included).
    val selected = remember { mutableStateSetOf<Int>() }
    var showWatermark by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val plan = remember(currentDocument, ops.size) { EditPlanBuilder.build(currentDocument.pageCount, ops) }
    val visible = remember(plan) { plan.pages.indices.filterNot { plan.pages[it].deleted } }
    // Keys that follow a page through moves, so the grid doesn't re-create every item on drop.
    val keys = remember(plan) {
        val seen = mutableMapOf<String, Int>()
        plan.pages.map { page ->
            val base = "${page.sourceDocumentUri ?: "main"}#${page.sourceIndex}"
            val occurrence = seen.merge(base, 1, Int::plus)!!
            "$base#$occurrence"
        }
    }
    // While dragging, the grid shows this provisional order; the move is committed on release.
    var dragOrder by remember { mutableStateOf<List<Int>?>(null) }
    var draggedPlanIndex by remember { mutableStateOf<Int?>(null) }
    val shown = dragOrder ?: visible

    fun record(op: EditOp) {
        ops += op
        redo.clear()
    }

    val gridState = rememberLazyGridState()
    val reorder = rememberGridReorderState(
        grid = gridState,
        contentPadding = GridPadding,
        onDragEnd = {
            val order = dragOrder
            val dragged = draggedPlanIndex
            if (order != null && dragged != null) {
                val target = visible[order.indexOf(dragged)]
                if (target != dragged) {
                    record(MovePage(dragged, target))
                    selected.clear()
                }
            }
            dragOrder = null
            draggedPlanIndex = null
        }
    ) { from, to ->
        val order = (dragOrder ?: visible).toMutableList()
        if (draggedPlanIndex == null) draggedPlanIndex = order[from]
        order.add(to, order.removeAt(from))
        dragOrder = order
    }

    val insertPicker = rememberPdfPicker { uri ->
        scope.launch {
            val count = thumbnails.pageCount(uri)
            if (count == 0) return@launch
            // Insert after the last selected page, or at the end.
            val at = (selected.maxOrNull()?.plus(1)) ?: plan.pages.size
            record(InsertPages(uri, at, count, displayName(context, uri)))
        }
    }

    Scaffold(
        topBar = {
            ScreenTopBar(stringResource(R.string.tool_organize_short), onBack) {
                IconButton(onClick = { ops.removeLastOrNull()?.let { redo += it } }, enabled = ops.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.undo))
                }
                IconButton(onClick = { redo.removeLastOrNull()?.let { ops += it } }, enabled = redo.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.redo))
                }
                Button(
                    onClick = {
                        scope.launch {
                            isSaving = true
                            val out = File(context.cacheDir, "organized_${System.currentTimeMillis()}.pdf")
                            val ok = editPlanProcessor.applyAndExport(currentDocument.uri, plan, out) && onSave(out)
                            isSaving = false
                            if (!ok) Toast.makeText(context, R.string.save_failed, Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = plan.hasChanges && !isSaving,
                    modifier = Modifier.padding(end = 8.dp)
                ) { Text(stringResource(R.string.save)) }
            }
        },
        bottomBar = {
            OrganizeActions(
                selectedCount = selected.size,
                onRotate = { degrees -> record(RotatePages(degrees, selected.toSet())) },
                onDuplicate = {
                    record(DuplicatePages(selected.toSet()))
                    selected.clear()
                },
                onDelete = {
                    if (selected.size >= visible.size) {
                        Toast.makeText(context, R.string.organize_keep_one, Toast.LENGTH_SHORT).show()
                    } else {
                        record(DeletePages(selected.toSet()))
                        selected.clear()
                    }
                },
                onClearSelection = { selected.clear() },
                onSelectAll = { selected.addAll(visible) },
                onInsert = insertPicker,
                onReverse = { record(ReversePages); selected.clear() },
                onWatermark = { showWatermark = true }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = pluralStringResource(R.plurals.page_count, visible.size, visible.size) + " · " +
                    stringResource(R.string.organize_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (isTablet) 140.dp else 104.dp),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .reorderable(reorder),
                contentPadding = GridPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(shown, key = { _, planIndex -> keys[planIndex] }) { position, planIndex ->
                    val state = plan.pages[planIndex]
                    val isSelected = planIndex in selected
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .draggableItem(reorder, keys[planIndex])
                            .then(if (reorder.isDragging(keys[planIndex])) Modifier else Modifier.animateItem())
                    ) {
                        org.ultimatepdf.ui.components.PageThumbnail(
                            page = PageRef(state.sourceDocumentUri ?: currentDocument.uri, state.sourceIndex),
                            thumbnails = thumbnails,
                            rotation = state.rotationDelta,
                            borderColor = if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant,
                            borderWidth = if (isSelected) 2.5.dp else 1.dp,
                            modifier = Modifier.clickable {
                                if (isSelected) selected.remove(planIndex) else selected.add(planIndex)
                            }
                        ) {
                            if (isSelected) SelectionBadge(Modifier.align(Alignment.TopEnd))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${position + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                            // One-tap rotation of this page, without selecting it first.
                            IconButton(onClick = { record(RotatePages(90, setOf(planIndex))) }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.AutoMirrored.Filled.RotateRight,
                                    contentDescription = stringResource(R.string.organize_rotate_page, position + 1),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showWatermark) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showWatermark = false },
            title = { Text(stringResource(R.string.organize_watermark)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.organize_watermark_text)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        record(SetWatermark(WatermarkOptions(text = text.trim())))
                        showWatermark = false
                    },
                    enabled = text.isNotBlank()
                ) { Text(stringResource(R.string.apply)) }
            },
            dismissButton = { TextButton(onClick = { showWatermark = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (isSaving) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

@Composable
fun SelectionBadge(modifier: Modifier = Modifier, label: String? = null) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .padding(6.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary)
    ) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiary)
        } else {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

/** Selection actions when pages are selected, document actions otherwise. */
@Composable
private fun OrganizeActions(
    selectedCount: Int,
    onRotate: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onInsert: () -> Unit,
    onReverse: () -> Unit,
    onWatermark: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        AnimatedContent(targetState = selectedCount > 0, label = "organize-actions") { hasSelection ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 6.dp)
            ) {
                if (hasSelection) {
                    ActionItem(Icons.Default.Close, stringResource(R.string.organize_selected, selectedCount), onClearSelection)
                    ActionItem(Icons.AutoMirrored.Filled.RotateLeft, stringResource(R.string.organize_rotate_left)) { onRotate(270) }
                    ActionItem(Icons.AutoMirrored.Filled.RotateRight, stringResource(R.string.organize_rotate_right)) { onRotate(90) }
                    ActionItem(Icons.Default.Sync, "180°") { onRotate(180) }
                    ActionItem(Icons.Default.ContentCopy, stringResource(R.string.organize_duplicate), onDuplicate)
                    ActionItem(Icons.Outlined.DeleteOutline, stringResource(R.string.delete), onDelete)
                } else {
                    ActionItem(Icons.Default.SelectAll, stringResource(R.string.select_all), onSelectAll)
                    ActionItem(Icons.Default.PostAdd, stringResource(R.string.organize_insert), onInsert)
                    ActionItem(Icons.Default.SwapVert, stringResource(R.string.organize_reverse), onReverse)
                    ActionItem(Icons.Default.BrandingWatermark, stringResource(R.string.organize_watermark), onWatermark)
                }
            }
        }
    }
}

@Composable
fun ActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

private val GridPadding = PaddingValues(12.dp)
