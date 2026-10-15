package org.ultimatepdf.ui.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ultimatepdf.core.model.DocumentModel
import org.ultimatepdf.core.pdf.PdfEngine

/**
 * Standard, fluid PDF Reader and Editor Workspace.
 *
 * Defaults to a clean, distraction-free "Simple Reader" mode with continuous vertical
 * scrolling, smooth pinch-to-zoom, and dark mode reading.
 *
 * A prominent Pencil button in the TOP-LEFT corner toggles Edit Mode, revealing the full
 * editing suite (Page Organizer, Annotate, Forms, Digital Signatures, Tools).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    currentDocument: DocumentModel?,
    pdfEngine: PdfEngine,
    onDocumentLoaded: (DocumentModel) -> Unit,
    isEditMode: Boolean,
    onToggleEditMode: () -> Unit,
    onNavigateToTool: (String) -> Unit = {},
    isTablet: Boolean = false
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val screenWidthPx = with(density) { (configuration.screenWidthDp.dp).roundToPx() }
    val renderTargetWidth = if (isTablet) (screenWidthPx * 1.5f).toInt() else screenWidthPx

    var invertColors by remember { mutableStateOf(false) }
    var isLoadingDocument by remember { mutableStateOf(false) }

    // Global Zoom state
    var scale by remember { mutableFloatStateOf(1f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }

    val listState = rememberLazyListState()
    val currentVisiblePage by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                isLoadingDocument = true
                try {
                    val doc = pdfEngine.openDocument(it)
                    onDocumentLoaded(doc)
                    scale = 1f
                    panOffsetX = 0f
                    panOffsetY = 0f
                    listState.scrollToItem(0)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isLoadingDocument = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // PENCIL BUTTON IN THE TOP-LEFT CORNER (Toggles between Reader and Edit Mode)
                    FilledTonalIconButton(
                        onClick = onToggleEditMode,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isEditMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (isEditMode) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(42.dp)
                    ) {
                        Icon(
                            imageVector = if (isEditMode) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = if (isEditMode) "Done / Return to Reader Mode" else "Enter Edit Mode",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                title = {
                    Column(modifier = Modifier.padding(start = 6.dp)) {
                        Text(
                            text = currentDocument?.name ?: "Ultimate PDF",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isEditMode) "Editing Mode · Tap ✓ when done"
                            else if (currentDocument != null) "Reading · Page ${currentVisiblePage + 1} of ${currentDocument.pageCount}"
                            else "Simple Reader",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isEditMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Invert Colors (Dark Mode for comfortable reading)
                    IconButton(onClick = { invertColors = !invertColors }) {
                        Icon(
                            imageVector = if (invertColors) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle Night / Dark Mode",
                            tint = if (invertColors) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Reset Zoom
                    if (scale != 1f) {
                        IconButton(onClick = {
                            scale = 1f
                            panOffsetX = 0f
                            panOffsetY = 0f
                        }) {
                            Icon(Icons.Default.FitScreen, contentDescription = "Reset Zoom")
                        }
                    }

                    // Open Document Button
                    FilledTonalButton(
                        onClick = { filePicker.launch(arrayOf("application/pdf")) },
                        modifier = Modifier.padding(end = 8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Edit Mode Subheader Options Strip (Shown only when Edit Mode is active)
            AnimatedVisibility(
                visible = isEditMode,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EditQuickAction(
                            icon = Icons.Default.GridView,
                            label = "Pages",
                            onClick = { onNavigateToTool("editor") }
                        )
                        EditQuickAction(
                            icon = Icons.Default.Draw,
                            label = "Annotate",
                            onClick = { onNavigateToTool("annotate") }
                        )
                        EditQuickAction(
                            icon = Icons.Default.DynamicForm,
                            label = "Forms",
                            onClick = { onNavigateToTool("forms") }
                        )
                        EditQuickAction(
                            icon = Icons.Default.EditNote,
                            label = "Sign",
                            onClick = { onNavigateToTool("sign") }
                        )
                        EditQuickAction(
                            icon = Icons.Default.AutoAwesome,
                            label = "Tools",
                            onClick = { onNavigateToTool("tools") }
                        )
                    }
                }
            }

            // Main Reader Workspace
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(if (invertColors) Color(0xFF121212) else Color(0xFFE5E7EB))
            ) {
                if (currentDocument == null) {
                    // Empty Welcome State
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(96.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.MenuBook,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = "Simple PDF Reader",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Open any PDF to read with continuous smooth scroll. Tap the pencil icon at the top-left to enter edit mode.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                            Button(
                                onClick = { filePicker.launch(arrayOf("application/pdf")) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Choose PDF Document")
                            }
                        }
                    }
                } else {
                    // Normal Continuous Vertical Reader
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val newScale = (scale * zoom).coerceIn(1f, 4.5f)
                                    scale = newScale
                                    if (newScale > 1f) {
                                        panOffsetX += pan.x
                                        panOffsetY += pan.y
                                    } else {
                                        panOffsetX = 0f
                                        panOffsetY = 0f
                                    }
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (scale > 1.2f) {
                                            scale = 1f
                                            panOffsetX = 0f
                                            panOffsetY = 0f
                                        } else {
                                            scale = 2.2f
                                        }
                                    }
                                )
                            }
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = panOffsetX,
                                    translationY = panOffsetY
                                ),
                            contentPadding = PaddingValues(vertical = 12.dp, horizontal = if (isTablet) 32.dp else 10.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            items(
                                count = currentDocument.pageCount,
                                key = { it }
                            ) { pageIndex ->
                                ContinuousPdfPageItem(
                                    pageIndex = pageIndex,
                                    totalPages = currentDocument.pageCount,
                                    pdfEngine = pdfEngine,
                                    targetWidth = renderTargetWidth,
                                    invertColors = invertColors,
                                    isTablet = isTablet
                                )
                            }
                        }

                        // Floating Page Pill Indicator (Shows Page X of Total while reading)
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                            shadowElevation = 4.dp
                        ) {
                            Text(
                                text = "${currentVisiblePage + 1} / ${currentDocument.pageCount}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                if (isLoadingDocument) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/**
 * Individual page component inside the continuous reader list.
 * Loads and renders lazily as the user scrolls.
 */
@Composable
fun ContinuousPdfPageItem(
    pageIndex: Int,
    totalPages: Int,
    pdfEngine: PdfEngine,
    targetWidth: Int,
    invertColors: Boolean,
    isTablet: Boolean
) {
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var aspectRatio by remember { mutableFloatStateOf(0.707f) } // Standard A4 default (1 / sqrt(2))
    var isRendering by remember { mutableStateOf(true) }

    LaunchedEffect(pageIndex, targetWidth, invertColors) {
        isRendering = true
        try {
            val dims = pdfEngine.getPageDimensions(pageIndex)
            if (dims.second > 0) {
                aspectRatio = dims.first.toFloat() / dims.second.toFloat()
            }
            val targetHeight = (targetWidth / aspectRatio).toInt()
            val bmp = pdfEngine.renderPage(
                pageIndex = pageIndex,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                invertColors = invertColors
            )
            pageBitmap = bmp
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isRendering = false
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(if (isTablet) 0.85f else 0.98f)
                .aspectRatio(aspectRatio)
                .shadow(elevation = 6.dp, shape = RoundedCornerShape(4.dp))
                .border(
                    width = 0.5.dp,
                    color = if (invertColors) Color.DarkGray else Color.LightGray,
                    shape = RoundedCornerShape(4.dp)
                ),
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (invertColors) Color.Black else Color.White
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (pageBitmap != null) {
                    Image(
                        bitmap = pageBitmap!!.asImageBitmap(),
                        contentDescription = "Page ${pageIndex + 1}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                if (isRendering && pageBitmap == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Subtle Page Number indicator below each page
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Page ${pageIndex + 1}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun EditQuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}
