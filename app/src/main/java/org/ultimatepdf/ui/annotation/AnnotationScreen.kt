package org.ultimatepdf.ui.annotation

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ultimatepdf.core.model.AnnotationType
import org.ultimatepdf.core.model.DocumentModel
import org.ultimatepdf.core.pdf.PdfEngine

data class DrawnStroke(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float,
    val type: AnnotationType
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationScreen(
    currentDocument: DocumentModel?,
    pdfEngine: PdfEngine,
    isTablet: Boolean = false
) {
    if (currentDocument == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Open a PDF in the Viewer tab to start annotating.")
        }
        return
    }

    var currentPageIndex by remember { mutableIntStateOf(0) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedTool by remember { mutableStateOf(AnnotationType.INK_PEN) }
    var selectedColor by remember { mutableStateOf(Color(0xFFFFEB3B)) } // Highlighter default
    var strokeWidth by remember { mutableFloatStateOf(6f) }

    val strokes = remember { mutableStateListOf<DrawnStroke>() }
    var currentPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    LaunchedEffect(currentDocument, currentPageIndex) {
        try {
            val bmp = pdfEngine.renderPage(
                pageIndex = currentPageIndex,
                targetWidth = 1400,
                targetHeight = 2000
            )
            currentBitmap = bmp
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Annotations & Markup", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo Mark")
                    }
                    IconButton(onClick = { strokes.clear() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear All Marks")
                    }
                }
            )
        },
        bottomBar = {
            // Floating tool and palette selector
            Surface(
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Tool Selection Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = selectedTool == AnnotationType.INK_PEN,
                            onClick = {
                                selectedTool = AnnotationType.INK_PEN
                                selectedColor = Color(0xFF1976D2)
                                strokeWidth = 4f
                            },
                            label = { Text("Pen") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )

                        FilterChip(
                            selected = selectedTool == AnnotationType.HIGHLIGHT,
                            onClick = {
                                selectedTool = AnnotationType.HIGHLIGHT
                                selectedColor = Color(0xFFFFEB3B)
                                strokeWidth = 18f
                            },
                            label = { Text("Highlight") },
                            leadingIcon = { Icon(Icons.Default.Highlight, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )

                        FilterChip(
                            selected = selectedTool == AnnotationType.RECTANGLE,
                            onClick = {
                                selectedTool = AnnotationType.RECTANGLE
                                selectedColor = Color(0xFFD32F2F)
                                strokeWidth = 4f
                            },
                            label = { Text("Box") },
                            leadingIcon = { Icon(Icons.Default.CheckBoxOutlineBlank, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )

                        FilterChip(
                            selected = selectedTool == AnnotationType.STICKY_NOTE,
                            onClick = {
                                selectedTool = AnnotationType.STICKY_NOTE
                                selectedColor = Color(0xFFFF9800)
                                strokeWidth = 2f
                            },
                            label = { Text("Note") },
                            leadingIcon = { Icon(Icons.Default.StickyNote2, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }

                    // Color Palette Selector Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val palette = listOf(
                            Color(0xFFFFEB3B), // Yellow
                            Color(0xFF4CAF50), // Green
                            Color(0xFF2196F3), // Blue
                            Color(0xFFE91E63), // Pink
                            Color(0xFFF44336), // Red
                            Color(0xFF212121)  // Black
                        )

                        palette.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 6.dp)
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(
                                        width = if (selectedColor == c) 2.5.dp else 0.5.dp,
                                        color = if (selectedColor == c) MaterialTheme.colorScheme.primary else Color.Gray,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = c }
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFE2E8F0)),
            contentAlignment = Alignment.Center
        ) {
            // Document Page image
            currentBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Annotation Drawing Layer
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(selectedTool, selectedColor, strokeWidth) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                currentPoints = listOf(offset)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                currentPoints = currentPoints + change.position
                            },
                            onDragEnd = {
                                if (currentPoints.isNotEmpty()) {
                                    val finalColor = if (selectedTool == AnnotationType.HIGHLIGHT) {
                                        selectedColor.copy(alpha = 0.45f)
                                    } else {
                                        selectedColor
                                    }
                                    strokes.add(
                                        DrawnStroke(
                                            points = currentPoints,
                                            color = finalColor,
                                            strokeWidth = strokeWidth,
                                            type = selectedTool
                                        )
                                    )
                                    currentPoints = emptyList()
                                }
                            }
                        )
                    }
            ) {
                // Render committed strokes
                strokes.forEach { stroke ->
                    if (stroke.points.size > 1) {
                        val path = Path().apply {
                            moveTo(stroke.points[0].x, stroke.points[0].y)
                            for (i in 1 until stroke.points.size) {
                                lineTo(stroke.points[i].x, stroke.points[i].y)
                            }
                        }
                        drawPath(
                            path = path,
                            color = stroke.color,
                            style = Stroke(width = stroke.strokeWidth)
                        )
                    }
                }

                // Render in-progress active stroke
                if (currentPoints.size > 1) {
                    val activePath = Path().apply {
                        moveTo(currentPoints[0].x, currentPoints[0].y)
                        for (i in 1 until currentPoints.size) {
                            lineTo(currentPoints[i].x, currentPoints[i].y)
                        }
                    }
                    val activeColor = if (selectedTool == AnnotationType.HIGHLIGHT) {
                        selectedColor.copy(alpha = 0.45f)
                    } else selectedColor

                    drawPath(
                        path = activePath,
                        color = activeColor,
                        style = Stroke(width = strokeWidth)
                    )
                }
            }
        }
    }
}
