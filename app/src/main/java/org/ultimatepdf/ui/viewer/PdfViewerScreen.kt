package org.ultimatepdf.ui.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ultimatepdf.core.model.DocumentModel
import org.ultimatepdf.core.pdf.PdfEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    currentDocument: DocumentModel?,
    pdfEngine: PdfEngine,
    onDocumentLoaded: (DocumentModel) -> Unit,
    isTablet: Boolean = false
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentPageIndex by remember { mutableIntStateOf(0) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var invertColors by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    // Zoom and pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                isLoading = true
                try {
                    val doc = pdfEngine.openDocument(it)
                    onDocumentLoaded(doc)
                    currentPageIndex = 0
                    scale = 1f
                    offsetX = 0f
                    offsetY = 0f
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isLoading = false
                }
            }
        }
    }

    // Render active page when pageIndex, document or invertColors changes
    LaunchedEffect(currentDocument, currentPageIndex, invertColors) {
        if (currentDocument != null && currentDocument.pageCount > 0) {
            isLoading = true
            try {
                val targetW = if (isTablet) 1800 else 1200
                val targetH = if (isTablet) 2400 else 1600
                currentBitmap = pdfEngine.renderPage(
                    pageIndex = currentPageIndex,
                    targetWidth = targetW,
                    targetHeight = targetH,
                    invertColors = invertColors
                )
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        } else {
            currentBitmap = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentDocument?.name ?: "Ultimate PDF Viewer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        if (currentDocument != null) {
                            Text(
                                text = "Page ${currentPageIndex + 1} of ${currentDocument.pageCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { invertColors = !invertColors }) {
                        Icon(
                            imageVector = if (invertColors) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle Dark Mode",
                            tint = if (invertColors) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }) {
                        Icon(Icons.Default.FitScreen, contentDescription = "Reset Zoom")
                    }
                    FilledTonalButton(
                        onClick = { filePicker.launch(arrayOf("application/pdf")) },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open")
                    }
                }
            )
        }
    ) { padding ->
        if (currentDocument == null) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "No PDF Loaded",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Open a PDF document to view, edit, fill forms or sign.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { filePicker.launch(arrayOf("application/pdf")) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose PDF File")
                    }
                }
            }
        } else {
            // Document loaded view
            if (isTablet) {
                // Tablet Master-Detail Two-Pane Layout
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    // Left Pane: Thumbnail list
                    Surface(
                        modifier = Modifier
                            .width(220.dp)
                            .fillMaxHeight(),
                        tonalElevation = 2.dp
                    ) {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(currentDocument.pageCount) { index ->
                                ThumbnailItem(
                                    pageIndex = index,
                                    isSelected = index == currentPageIndex,
                                    onClick = {
                                        currentPageIndex = index
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                )
                            }
                        }
                    }

                    VerticalDivider()

                    // Right Pane: Main Page Viewport with Pinch-to-zoom
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        MainPageCanvas(
                            bitmap = currentBitmap,
                            isLoading = isLoading,
                            scale = scale,
                            offsetX = offsetX,
                            offsetY = offsetY,
                            onTransform = { s, oX, oY ->
                                scale = (scale * s).coerceIn(0.8f, 5.0f)
                                offsetX += oX
                                offsetY += oY
                            }
                        )

                        // Tablet Page navigation overlay floating controls
                        FloatingPageControls(
                            currentPage = currentPageIndex,
                            totalPages = currentDocument.pageCount,
                            onPrevious = { if (currentPageIndex > 0) currentPageIndex-- },
                            onNext = { if (currentPageIndex < currentDocument.pageCount - 1) currentPageIndex++ },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                        )
                    }
                }
            } else {
                // Phone Layout: Single viewport with bottom thumbnail strip
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        MainPageCanvas(
                            bitmap = currentBitmap,
                            isLoading = isLoading,
                            scale = scale,
                            offsetX = offsetX,
                            offsetY = offsetY,
                            onTransform = { s, oX, oY ->
                                scale = (scale * s).coerceIn(0.8f, 5.0f)
                                offsetX += oX
                                offsetY += oY
                            }
                        )

                        FloatingPageControls(
                            currentPage = currentPageIndex,
                            totalPages = currentDocument.pageCount,
                            onPrevious = { if (currentPageIndex > 0) currentPageIndex-- },
                            onNext = { if (currentPageIndex < currentDocument.pageCount - 1) currentPageIndex++ },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                        )
                    }

                    // Bottom horizontal thumbnail strip
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp),
                        tonalElevation = 3.dp
                    ) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(currentDocument.pageCount) { index ->
                                ThumbnailItem(
                                    pageIndex = index,
                                    isSelected = index == currentPageIndex,
                                    onClick = {
                                        currentPageIndex = index
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MainPageCanvas(
    bitmap: Bitmap?,
    isLoading: Boolean,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    onTransform: (Float, Float, Float) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onTransform(zoom, pan.x, pan.y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "PDF Page",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    ),
                contentScale = ContentScale.Fit
            )
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun ThumbnailItem(
    pageIndex: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(80.dp)
            .height(100.dp)
            .clickable(onClick = onClick)
            .border(
                width = if (isSelected) 2.dp else 0.5.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${pageIndex + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun FloatingPageControls(
    currentPage: Int,
    totalPages: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 6.dp,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPrevious,
                enabled = currentPage > 0
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Page")
            }

            Text(
                text = "${currentPage + 1} / $totalPages",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            IconButton(
                onClick = onNext,
                enabled = currentPage < totalPages - 1
            ) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Next Page")
            }
        }
    }
}
