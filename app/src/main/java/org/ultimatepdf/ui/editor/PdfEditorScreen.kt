package org.ultimatepdf.ui.editor

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ultimatepdf.core.model.*
import org.ultimatepdf.core.pdf.EditPlanProcessor
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfEditorScreen(
    currentDocument: DocumentModel?,
    editPlanProcessor: EditPlanProcessor,
    isTablet: Boolean = false
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    if (currentDocument == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Open a PDF document in the Viewer tab first to edit pages.")
        }
        return
    }

    // Edit operations history for Undo/Redo
    val opHistory = remember { mutableStateListOf<EditOp>() }
    val redoStack = remember { mutableStateListOf<EditOp>() }

    // Selected pages for batch actions
    val selectedIndices = remember { mutableStateSetOf<Int>() }

    var showWatermarkDialog by remember { mutableStateOf(false) }
    var watermarkText by remember { mutableStateOf("CONFIDENTIAL") }

    var isExporting by remember { mutableStateOf(false) }

    // Splicing/Inserting PDF
    val insertPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val op = InsertPages(
                sourceUri = it,
                targetPosition = if (selectedIndices.isNotEmpty()) selectedIndices.first() else 0,
                pageCount = 1,
                label = it.lastPathSegment ?: "Inserted Document"
            )
            opHistory.add(op)
            redoStack.clear()
        }
    }

    // Recompute plan dynamically by folding ops
    val editPlan = remember(currentDocument, opHistory.size) {
        EditPlanBuilder.build(currentDocument.pageCount, opHistory)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Page Organizer & Plan", fontWeight = FontWeight.Bold)
                        Text(
                            text = "${editPlan.activePages.size} active pages · ${opHistory.size} pending edit(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Undo button
                    IconButton(
                        onClick = {
                            if (opHistory.isNotEmpty()) {
                                val last = opHistory.removeAt(opHistory.lastIndex)
                                redoStack.add(last)
                            }
                        },
                        enabled = opHistory.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo")
                    }

                    // Redo button
                    IconButton(
                        onClick = {
                            if (redoStack.isNotEmpty()) {
                                val next = redoStack.removeAt(redoStack.lastIndex)
                                opHistory.add(next)
                            }
                        },
                        enabled = redoStack.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Redo, contentDescription = "Redo")
                    }

                    // Export / Save button
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isExporting = true
                                val outFile = File(context.cacheDir, "edited_${System.currentTimeMillis()}.pdf")
                                val success = editPlanProcessor.applyAndExport(
                                    primarySourceUri = currentDocument.uri,
                                    plan = editPlan,
                                    outputFile = outFile
                                )
                                isExporting = false
                                if (success) {
                                    Toast.makeText(context, "Saved to ${outFile.name} (${outFile.length() / 1024} KB)", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isExporting && editPlan.hasChanges,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Export")
                        }
                    }
                }
            )
        },
        bottomBar = {
            // Action toolbar for selected pages
            Surface(
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (selectedIndices.isNotEmpty()) {
                                opHistory.add(RotatePages(90, selectedIndices.toSet()))
                                redoStack.clear()
                            }
                        },
                        enabled = selectedIndices.isNotEmpty()
                    ) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Rotate 90°")
                    }

                    IconButton(
                        onClick = {
                            if (selectedIndices.isNotEmpty()) {
                                opHistory.add(DeletePages(selectedIndices.toSet()))
                                redoStack.clear()
                                selectedIndices.clear()
                            }
                        },
                        enabled = selectedIndices.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Page(s)")
                    }

                    IconButton(
                        onClick = {
                            if (selectedIndices.size == 1) {
                                val currentPos = selectedIndices.first()
                                if (currentPos > 0) {
                                    opHistory.add(MovePage(currentPos, currentPos - 1))
                                    redoStack.clear()
                                    selectedIndices.clear()
                                    selectedIndices.add(currentPos - 1)
                                }
                            }
                        },
                        enabled = selectedIndices.size == 1 && selectedIndices.first() > 0
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Move Left")
                    }

                    IconButton(
                        onClick = {
                            if (selectedIndices.size == 1) {
                                val currentPos = selectedIndices.first()
                                if (currentPos < editPlan.pages.lastIndex) {
                                    opHistory.add(MovePage(currentPos, currentPos + 1))
                                    redoStack.clear()
                                    selectedIndices.clear()
                                    selectedIndices.add(currentPos + 1)
                                }
                            }
                        },
                        enabled = selectedIndices.size == 1 && selectedIndices.first() < editPlan.pages.lastIndex
                    ) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Move Right")
                    }

                    IconButton(onClick = {
                        opHistory.add(ReversePages)
                        redoStack.clear()
                    }) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "Reverse Order")
                    }

                    IconButton(onClick = { insertPicker.launch(arrayOf("application/pdf")) }) {
                        Icon(Icons.Default.PostAdd, contentDescription = "Insert PDF")
                    }

                    IconButton(onClick = { showWatermarkDialog = true }) {
                        Icon(Icons.Default.BrandingWatermark, contentDescription = "Watermark")
                    }
                }
            }
        }
    ) { padding ->
        val columns = if (isTablet) 5 else 3

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(editPlan.pages) { index, pageState ->
                PageGridItem(
                    position = index,
                    pageState = pageState,
                    isSelected = selectedIndices.contains(index),
                    onClick = {
                        if (selectedIndices.contains(index)) {
                            selectedIndices.remove(index)
                        } else {
                            selectedIndices.add(index)
                        }
                    }
                )
            }
        }
    }

    if (showWatermarkDialog) {
        AlertDialog(
            onDismissRequest = { showWatermarkDialog = false },
            title = { Text("Add Watermark") },
            text = {
                OutlinedTextField(
                    value = watermarkText,
                    onValueChange = { watermarkText = it },
                    label = { Text("Watermark Text") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    opHistory.add(SetWatermark(WatermarkOptions(text = watermarkText)))
                    redoStack.clear()
                    showWatermarkDialog = false
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWatermarkDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageGridItem(
    position: Int,
    pageState: PageState,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onClick
            )
            .border(
                width = if (isSelected) 2.5.dp else 0.5.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.dp)
            ),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (pageState.deleted) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.rotate(pageState.rotationDelta.toFloat())
            ) {
                Icon(
                    imageVector = if (pageState.deleted) Icons.Default.DeleteOutline else Icons.Default.Description,
                    contentDescription = null,
                    tint = if (pageState.deleted) MaterialTheme.colorScheme.error
                    else if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (pageState.deleted) "Deleted" else "Page ${position + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (pageState.rotationDelta != 0) {
                    Text(
                        text = "${pageState.rotationDelta}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // Top-end checkmark or status badge
            if (isSelected) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(2.dp)
                    )
                }
            }
        }
    }
}
