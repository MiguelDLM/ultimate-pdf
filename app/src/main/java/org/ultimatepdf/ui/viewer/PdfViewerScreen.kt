package org.ultimatepdf.ui.viewer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.ultimatepdf.core.signature.SignatureImages
import org.ultimatepdf.ui.sign.SignatureCaptureDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.ultimatepdf.R
import org.ultimatepdf.core.model.AnnotationType
import org.ultimatepdf.core.model.DocumentModel
import org.ultimatepdf.core.pdf.PageText
import org.ultimatepdf.core.pdf.PdfAnnotationStore
import org.ultimatepdf.core.pdf.PdfEngine
import org.ultimatepdf.core.pdf.PdfTextExtractor
import org.ultimatepdf.core.model.SignatureShape
import org.ultimatepdf.core.signature.SavedStamp
import org.ultimatepdf.core.signature.SignatureLibrary
import org.ultimatepdf.core.signature.StampKind
import org.ultimatepdf.core.signature.StampLibrary
import org.ultimatepdf.ui.sign.rememberVectorizeFlow
import org.ultimatepdf.ui.stamps.CustomTextStampDialog
import org.ultimatepdf.ui.stamps.StampImageSourceDialog
import org.ultimatepdf.ui.components.copyToUri
import org.ultimatepdf.ui.navigation.AppDestination
import org.ultimatepdf.ui.sign.SignatureColorPicker
import org.ultimatepdf.ui.sign.SignaturePickerSheet
import java.io.File

/**
 * Continuous PDF reader with an integrated markup mode.
 *
 * Reading: back on the left, night mode / edit / overflow on the right. Long-press any text to
 * select it and copy, highlight, underline, strike or comment on it.
 *
 * Editing (pencil): a bottom toolbar with text highlight, pen, marker, note and eraser that
 * work directly on the pages, undo / redo, and a "More" sheet with the document tools.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    document: DocumentModel,
    pdfEngine: PdfEngine,
    textExtractor: PdfTextExtractor,
    annotationStore: PdfAnnotationStore,
    signatureLibrary: SignatureLibrary,
    stampLibrary: StampLibrary,
    session: AnnotationSession,
    isEditMode: Boolean,
    onEditModeChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onOpenAnother: () -> Unit,
    onNavigateToTool: (AppDestination) -> Unit,
    onDocumentReloaded: (DocumentModel) -> Unit,
    isTablet: Boolean = false
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    var invertColors by rememberSaveable { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var pendingLeaveAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showGoToPage by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showSignatureSheet by remember { mutableStateOf(false) }
    var pendingSaveAsFile by remember { mutableStateOf<File?>(null) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val listState = rememberLazyListState()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val zoomRenderFactor by remember { derivedStateOf { if (scale > 1.4f) 2 else 1 } }
    val renderTargetWidth = (if (isTablet) (screenWidthPx * 0.85f).toInt() else screenWidthPx) * zoomRenderFactor

    val tool = session.tool
    val isDrawingTool = tool == MarkupTool.PEN || tool == MarkupTool.MARKER ||
        tool == MarkupTool.ERASER || tool == MarkupTool.TEXT_HIGHLIGHT ||
        tool == MarkupTool.RECTANGLE || tool == MarkupTool.CIRCLE ||
        tool == MarkupTool.TRIANGLE || tool == MarkupTool.STAR ||
        tool == MarkupTool.CLOUD || tool == MarkupTool.CALLOUT ||
        tool == MarkupTool.ARROW || tool == MarkupTool.DOUBLE_ARROW || tool == MarkupTool.LINE ||
        tool == MarkupTool.STAMP || tool == MarkupTool.REDACTION ||
        tool == MarkupTool.NOTE
    val isInteractingWithContent = isDrawingTool ||
        session.selection != null ||
        session.selectedAnnotation != null ||
        session.noteDraft != null ||
        tool != MarkupTool.NONE


    LaunchedEffect(document.uri) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    // PdfRenderer doesn't draw annotations, so the markup layer draws the file's own ones too.
    LaunchedEffect(document.uri, document.revision) {
        session.load(annotationStore.read(document.uri))
    }

    fun reloadAfterWrite(uri: Uri) {
        coroutineScope.launch {
            onDocumentReloaded(pdfEngine.openDocument(uri))
        }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { target: Uri? ->
        val source = pendingSaveAsFile
        pendingSaveAsFile = null
        if (target == null || source == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            try {
                copyToUri(context, source, target)
                pdfEngine.close()
                textExtractor.invalidate()
                reloadAfterWrite(target)
                Toast.makeText(context, R.string.viewer_saved_copy, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.viewer_save_error, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    var showCustomTextStampDialog by remember { mutableStateOf(false) }
    var showStampImageSourceDialog by remember { mutableStateOf(false) }

    // Photo → vector flow for stamps; new stamps are saved to the library (and listed in Settings).
    val stampFlow = rememberVectorizeFlow(
        isStamp = true,
        colors = StampColors,
        color = session.stampColor,
        onColorChange = { session.stampColor = it },
        onShape = { shape ->
            coroutineScope.launch {
                val name = context.getString(R.string.stamp_default_name, stampLibrary.items.size + 1)
                stampLibrary.addImage(shape, session.stampColor.argb(), name)
                session.selectedStampShape = shape
                session.selectedStampText = ""
                session.tool = MarkupTool.STAMP
            }
        }
    )

    /** Writes pending annotations into the PDF, overwriting it, or falls back to "Save as". */
    suspend fun saveAnnotations(): Boolean {
        if (!session.hasUnsavedChanges) return true
        isSaving = true
        session.selection = null
        try {
            val output = File(context.cacheDir, "annotated_${System.currentTimeMillis()}.pdf")
            try {
                annotationStore.write(document.uri, session.annotations.toList(), output)
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.viewer_save_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                return false
            }
            // The renderer keeps the file open; release it before rewriting the same file.
            pdfEngine.close()
            textExtractor.invalidate()
            val overwritten = try {
                copyToUri(context, output, document.uri)
                true
            } catch (e: Exception) {
                false
            }
            if (overwritten) {
                output.delete()
                reloadAfterWrite(document.uri)
                Toast.makeText(context, R.string.saved_title, Toast.LENGTH_SHORT).show()
                return true
            }
            // Read-only source (e.g. opened from another app): keep the original and ask where to save.
            onDocumentReloaded(pdfEngine.openDocument(document.uri))
            pendingSaveAsFile = output
            saveAsLauncher.launch(document.name.removeSuffix(".pdf") + " (annotated).pdf")
            return false
        } finally {
            isSaving = false
        }
    }

    /**
     * Drops [shape] in the middle of the page at the centre of the screen, 35% of the page wide,
     * and selects it so it can be dragged into place right away.
     */
    suspend fun placeSignature(shape: SignatureShape) {
        val layout = listState.layoutInfo
        val centerY = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
        val item = layout.visibleItemsInfo.firstOrNull { centerY in it.offset..(it.offset + it.size) }
            ?: layout.visibleItemsInfo.firstOrNull()
            ?: return
        val (pw, ph) = pdfEngine.getPageDimensions(item.index)
        val pageAspect = pw.toFloat() / ph.toFloat()
        val width = 0.35f
        val height = (width * pageAspect / shape.aspect).coerceAtMost(0.3f)
        val fittedWidth = height * shape.aspect / pageAspect
        val cy = ((centerY - item.offset).toFloat() / item.size).coerceIn(height / 2, 1f - height / 2)
        val annotation = org.ultimatepdf.core.model.PdfAnnotation(
            pageIndex = item.index,
            type = AnnotationType.SIGNATURE,
            bounds = org.ultimatepdf.core.model.PdfRect(
                0.5f - fittedWidth / 2, cy - height / 2, 0.5f + fittedWidth / 2, cy + height / 2
            ),
            colorArgb = session.signatureColor.argb(),
            strokeWidth = if (shape.filled) 0f else shape.strokeWidth,
            inkPoints = shape.contours
        )
        session.add(annotation)
        session.selectedSignatureId = annotation.id
    }

    fun leaveDocument(action: () -> Unit) {
        if (session.hasUnsavedChanges) pendingLeaveAction = action else action()
    }

    BackHandler {
        when {
            session.selection != null -> session.selection = null
            session.selectedAnnotationId != null -> session.selectedAnnotationId = null
            isEditMode -> {
                session.tool = MarkupTool.NONE
                onEditModeChange(false)
            }
            else -> leaveDocument(onClose)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (isEditMode) {
                            session.tool = MarkupTool.NONE
                            onEditModeChange(false)
                        } else {
                            leaveDocument(onClose)
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    Column {
                        Text(
                            text = document.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isEditMode) stringResource(R.string.viewer_editing) else stringResource(R.string.viewer_page_of, currentPage + 1, document.pageCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isEditMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (isEditMode) {
                        session.selectedAnnotation?.let { selected ->
                            IconButton(onClick = {
                                val dup = selected.copy(
                                    id = java.util.UUID.randomUUID().toString(),
                                    bounds = selected.bounds.offset(0.02f, 0.02f)
                                )
                                session.add(dup)
                                session.selectedAnnotationId = dup.id
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.duplicate))
                            }
                            IconButton(onClick = {
                                session.remove(selected)
                            }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        IconButton(onClick = session::undo, enabled = session.canUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.undo))
                        }
                        IconButton(onClick = session::redo, enabled = session.canRedo) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.redo))
                        }
                        FilledIconButton(
                            onClick = {
                                coroutineScope.launch {
                                    if (saveAnnotations()) {
                                        session.tool = MarkupTool.NONE
                                        onEditModeChange(false)
                                    }
                                }
                            },
                            enabled = !isSaving,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.done))
                        }
                    } else {
                        if (session.hasUnsavedChanges) {
                            IconButton(
                                onClick = { coroutineScope.launch { saveAnnotations() } },
                                enabled = !isSaving
                            ) {
                                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.viewer_save_annotations), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(onClick = { invertColors = !invertColors }) {
                            Icon(
                                imageVector = if (invertColors) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = stringResource(R.string.viewer_night_mode)
                            )
                        }
                        FilledTonalIconButton(onClick = { onEditModeChange(true) }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
                        }
                        ReaderOverflowMenu(
                            onOpenAnother = { leaveDocument(onOpenAnother) },
                            onGoToPage = { showGoToPage = true },
                            onTools = { showMoreSheet = true }
                        )
                    }
                }
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = isEditMode,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                MarkupToolbar(
                    session = session,
                    onSign = {
                        session.tool = MarkupTool.NONE
                        session.selection = null
                        showSignatureSheet = true
                    },
                    onMore = { showMoreSheet = true },
                    stamps = stampLibrary.items,
                    onCustomTextStamp = { showCustomTextStampDialog = true },
                    onCustomImageStamp = { showStampImageSourceDialog = true }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(if (invertColors) Color(0xFF121212) else MaterialTheme.colorScheme.surfaceContainerHighest)
                .clipToBounds()
                .pointerInput(isInteractingWithContent) {
                    // Pinch-zoom and two-finger pan run in the Initial pass so they win over list
                    // scrolling and page tools; one finger pans sideways only while zoomed and not interacting with content.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.count { it.pressed }
                            val maxX = size.width * (scale - 1f) / 2f
                            if (pressed >= 2) {
                                val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                val z = newScale / scale
                                val c = event.calculateCentroid(useCurrent = true) - Offset(size.width / 2f, size.height / 2f)
                                val pan = event.calculatePan()
                                val x = c.x * (1 - z) + offsetX * z + pan.x
                                val y = c.y * (1 - z) + offsetY * z + pan.y
                                val limitX = size.width * (newScale - 1f) / 2f
                                val limitY = size.height * (newScale - 1f) / 2f
                                scale = newScale
                                offsetX = x.coerceIn(-limitX, limitX)
                                offsetY = y.coerceIn(-limitY, limitY)
                                // Vertical movement beyond the zoom margin scrolls the document.
                                val overflowY = y - offsetY
                                if (overflowY != 0f) listState.dispatchRawDelta(-overflowY / newScale)
                                event.changes.forEach { it.consume() }
                            } else if (pressed == 1 && scale > 1f && !isInteractingWithContent && !event.changes.any { it.isConsumed }) {
                                offsetX = (offsetX + event.calculatePan().x).coerceIn(-maxX, maxX)
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(isInteractingWithContent) {
                    if (isInteractingWithContent) return@pointerInput
                    detectTapGestures(onDoubleTap = { tap ->
                        if (scale > 1.2f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            val target = 2.2f
                            val c = tap - Offset(size.width / 2f, size.height / 2f)
                            scale = target
                            offsetX = (-c.x * (target - 1f)).coerceIn(-size.width * 0.6f, size.width * 0.6f)
                            offsetY = (-c.y * (target - 1f)).coerceIn(-size.height * 0.6f, size.height * 0.6f)
                        }
                    })
                }
        ) {
            LazyColumn(
                state = listState,
                userScrollEnabled = !isInteractingWithContent,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    ),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = if (isTablet) 24.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(count = document.pageCount, key = { it }) { pageIndex ->
                    ReaderPage(
                        pageIndex = pageIndex,
                        document = document,
                        pdfEngine = pdfEngine,
                        textExtractor = textExtractor,
                        targetWidth = renderTargetWidth,
                        invertColors = invertColors,
                        session = session,
                        isTablet = isTablet
                    )
                }
            }

            val isThicknessApplicable = isEditMode && (
                session.tool in listOf(
                    MarkupTool.PEN, MarkupTool.MARKER,
                    MarkupTool.RECTANGLE, MarkupTool.CIRCLE, MarkupTool.TRIANGLE,
                    MarkupTool.STAR, MarkupTool.CLOUD, MarkupTool.CALLOUT,
                    MarkupTool.ARROW, MarkupTool.DOUBLE_ARROW, MarkupTool.LINE
                )
            )
            AnimatedVisibility(
                visible = isThicknessApplicable,
                enter = fadeIn() + slideInHorizontally { -it },
                exit = fadeOut() + slideOutHorizontally { -it },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
            ) {
                val isMarker = session.tool == MarkupTool.MARKER || (session.tool == MarkupTool.PEN && session.brushType == BrushType.MARKER)
                val currentPt = when {
                    isMarker -> widthFractionToPoints(session.markerWidth)
                    session.tool in listOf(
                        MarkupTool.RECTANGLE, MarkupTool.CIRCLE, MarkupTool.TRIANGLE,
                        MarkupTool.STAR, MarkupTool.CLOUD, MarkupTool.CALLOUT,
                        MarkupTool.ARROW, MarkupTool.DOUBLE_ARROW, MarkupTool.LINE
                    ) -> widthFractionToPoints(session.shapeWidth)
                    else -> widthFractionToPoints(session.penWidth)
                }
                val currentColor = when {
                    isMarker -> session.markerColor
                    session.tool in listOf(
                        MarkupTool.RECTANGLE, MarkupTool.CIRCLE, MarkupTool.TRIANGLE,
                        MarkupTool.STAR, MarkupTool.CLOUD, MarkupTool.CALLOUT,
                        MarkupTool.ARROW, MarkupTool.DOUBLE_ARROW, MarkupTool.LINE
                    ) -> session.shapeColor
                    else -> session.penColor
                }
                val range = if (isMarker) 8f..45f else 1f..25f

                VerticalThicknessSlider(
                    currentPt = currentPt,
                    rangePt = range,
                    color = currentColor,
                    onValueChange = { newPt ->
                        val fraction = pointsToWidthFraction(newPt)
                        when {
                            isMarker -> session.markerWidth = fraction
                            session.tool in listOf(
                                MarkupTool.RECTANGLE, MarkupTool.CIRCLE, MarkupTool.TRIANGLE,
                                MarkupTool.STAR, MarkupTool.CLOUD, MarkupTool.CALLOUT,
                                MarkupTool.ARROW, MarkupTool.DOUBLE_ARROW, MarkupTool.LINE
                            ) -> session.shapeWidth = fraction
                            else -> session.penWidth = fraction
                        }
                    }
                )
            }

            val selection = session.selection
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedVisibility(
                    visible = selection != null && tool != MarkupTool.TEXT_HIGHLIGHT,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut()
                ) {
                    SelectionActionBar(
                        onCopy = {
                            selection?.let { sel ->
                                coroutineScope.launch {
                                    val text = textExtractor.pageText(document.uri, sel.pageIndex).textOf(sel.range)
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("PDF text", text))
                                    session.selection = null
                                }
                            }
                        },
                        onMarkup = { type ->
                            selection?.let { sel ->
                                coroutineScope.launch {
                                    val text = textExtractor.pageText(document.uri, sel.pageIndex)
                                    session.addTextMarkup(sel.pageIndex, text, sel.range, type)
                                    session.selection = null
                                }
                            }
                        },
                        onComment = {
                            selection?.let { sel ->
                                coroutineScope.launch {
                                    val text = textExtractor.pageText(document.uri, sel.pageIndex)
                                    val quads = text.lineRects(sel.range)
                                    if (quads.isNotEmpty()) {
                                        session.noteDraft = org.ultimatepdf.core.model.PdfAnnotation(
                                            pageIndex = sel.pageIndex,
                                            type = AnnotationType.HIGHLIGHT,
                                            bounds = quads.first(),
                                            colorArgb = session.highlightColor.argb(),
                                            quads = quads
                                        )
                                    }
                                    session.selection = null
                                }
                            }
                        }
                    )
                }

                if (selection == null) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.clickable { showGoToPage = true }
                    ) {
                        Text(
                            text = "${currentPage + 1} / ${document.pageCount}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            if (isSaving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (showSignatureSheet) {
        SignaturePickerSheet(
            library = signatureLibrary,
            colors = SignatureColors,
            color = session.signatureColor,
            onColorChange = { session.signatureColor = it },
            onDismiss = { showSignatureSheet = false },
            onPick = { shape ->
                showSignatureSheet = false
                coroutineScope.launch { placeSignature(shape) }
            }
        )
    }

    if (showMoreSheet) {
        DocumentToolsSheet(
            onDismiss = { showMoreSheet = false },
            onTool = { destination ->
                showMoreSheet = false
                leaveDocument { onNavigateToTool(destination) }
            }
        )
    }

    if (showCustomTextStampDialog) {
        CustomTextStampDialog(
            initialText = session.selectedStampText,
            initialColor = session.stampColor,
            onDismiss = { showCustomTextStampDialog = false },
            onConfirm = { text, color ->
                showCustomTextStampDialog = false
                session.stampColor = color
                val color = session.stampColor.argb()
                if (stampLibrary.items.none { it.kind == StampKind.TEXT && it.text == text && it.colorArgb == color }) {
                    coroutineScope.launch { stampLibrary.addText(text, color) }
                }
                session.selectedStampText = text
                session.selectedStampShape = null
                session.tool = MarkupTool.STAMP
            }
        )
    }

    if (showStampImageSourceDialog) {
        StampImageSourceDialog(
            onDismiss = { showStampImageSourceDialog = false },
            onCamera = stampFlow.fromCamera,
            onGallery = stampFlow.fromGallery
        )
    }

    session.noteDraft?.let { draft ->
        NoteDialog(
            draft = draft,
            isExisting = session.annotations.any { it.id == draft.id },
            onDismiss = { session.noteDraft = null },
            onSave = { text ->
                // An edited note is rewritten as a new annotation; the stored one is removed.
                val updated = draft.copy(text = text, modifiedTimestamp = System.currentTimeMillis(), sourceIndex = null)
                val existing = session.annotations.firstOrNull { it.id == draft.id }
                if (existing != null) session.replace(existing, updated) else session.add(updated)
                session.noteDraft = null
            },
            onDelete = {
                session.annotations.firstOrNull { it.id == draft.id }?.let(session::remove)
                session.noteDraft = null
            }
        )
    }

    pendingLeaveAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingLeaveAction = null },
            title = { Text(stringResource(R.string.viewer_unsaved_title)) },
            text = { Text(stringResource(R.string.viewer_unsaved_text)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingLeaveAction = null
                    coroutineScope.launch { if (saveAnnotations()) action() }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingLeaveAction = null }) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = {
                        pendingLeaveAction = null
                        session.discard()
                        action()
                    }) { Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }

    if (showGoToPage) {
        GoToPageDialog(
            pageCount = document.pageCount,
            onDismiss = { showGoToPage = false },
            onGo = { page ->
                showGoToPage = false
                coroutineScope.launch { listState.scrollToItem(page) }
            }
        )
    }
}

@Composable
private fun ReaderPage(
    pageIndex: Int,
    document: DocumentModel,
    pdfEngine: PdfEngine,
    textExtractor: PdfTextExtractor,
    targetWidth: Int,
    invertColors: Boolean,
    session: AnnotationSession,
    isTablet: Boolean
) {
    var pageBitmap by remember(document.revision) { mutableStateOf<Bitmap?>(null) }
    var aspectRatio by remember { mutableFloatStateOf(0.707f) } // A4 until the real size is known
    var pageText by remember(document.revision) { mutableStateOf<PageText?>(null) }

    LaunchedEffect(pageIndex, targetWidth, invertColors, document.revision) {
        try {
            val (w, h) = pdfEngine.getPageDimensions(pageIndex)
            if (h > 0) aspectRatio = w.toFloat() / h.toFloat()
            pageBitmap = pdfEngine.renderPage(
                pageIndex = pageIndex,
                targetWidth = targetWidth,
                targetHeight = (targetWidth / aspectRatio).toInt(),
                invertColors = invertColors
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LaunchedEffect(pageIndex, document.revision) {
        // Skip pages that only fly by while scrolling fast.
        delay(300)
        pageText = textExtractor.pageText(document.uri, pageIndex)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth(if (isTablet) 0.85f else 1f)
            .aspectRatio(aspectRatio)
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(2.dp))
            .background(if (invertColors) Color.Black else Color.White),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = pageBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.page_short, pageIndex + 1),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        }
        PageMarkupLayer(
            pageIndex = pageIndex,
            pageAspect = aspectRatio,
            pageText = pageText,
            session = session,
            invertColors = invertColors
        )
    }
}

@Composable
private fun ReaderOverflowMenu(
    onOpenAnother: () -> Unit,
    onGoToPage: () -> Unit,
    onTools: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.viewer_go_to_page)) },
                leadingIcon = { Icon(Icons.Default.Numbers, contentDescription = null) },
                onClick = { expanded = false; onGoToPage() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.viewer_open_another)) },
                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                onClick = { expanded = false; onOpenAnother() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tools_title)) },
                leadingIcon = { Icon(Icons.Default.Apps, contentDescription = null) },
                onClick = { expanded = false; onTools() }
            )
        }
    }
}

@Composable
private fun MarkupToolbar(
    session: AnnotationSession,
    stamps: List<SavedStamp>,
    onSign: () -> Unit,
    onMore: () -> Unit,
    onCustomTextStamp: () -> Unit,
    onCustomImageStamp: () -> Unit
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            ToolOptionsRow(
                session = session,
                stamps = stamps,
                onCustomTextStamp = onCustomTextStamp,
                onCustomImageStamp = onCustomImageStamp
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolButton(Icons.Default.BorderColor, stringResource(R.string.tool_highlight), session, MarkupTool.TEXT_HIGHLIGHT)
                val isDrawing = session.tool == MarkupTool.PEN || session.tool == MarkupTool.MARKER
                ToolbarItem(Icons.Default.Draw, stringResource(R.string.tool_draw), isDrawing) {
                    session.selection = null
                    session.tool = if (isDrawing) MarkupTool.NONE else session.lastDrawTool
                }
                val isShape = session.tool == MarkupTool.RECTANGLE || session.tool == MarkupTool.CIRCLE ||
                    session.tool == MarkupTool.TRIANGLE || session.tool == MarkupTool.STAR ||
                    session.tool == MarkupTool.CLOUD || session.tool == MarkupTool.CALLOUT ||
                    session.tool == MarkupTool.ARROW || session.tool == MarkupTool.DOUBLE_ARROW ||
                    session.tool == MarkupTool.LINE
                ToolbarItem(Icons.Default.Category, stringResource(R.string.tool_shapes), isShape) {
                    session.selection = null
                    session.tool = if (isShape) MarkupTool.NONE else MarkupTool.RECTANGLE
                }
                ToolButton(Icons.Default.Approval, stringResource(R.string.tool_stamp), session, MarkupTool.STAMP)
                ToolButton(Icons.Default.Block, stringResource(R.string.tool_redact), session, MarkupTool.REDACTION)
                ToolButton(Icons.Default.AddComment, stringResource(R.string.tool_note), session, MarkupTool.NOTE)
                ToolbarItem(Icons.Default.HistoryEdu, stringResource(R.string.tool_sign), false, onSign)
                ToolButton(Icons.Default.CleaningServices, stringResource(R.string.tool_eraser), session, MarkupTool.ERASER)
                ToolbarItem(icon = Icons.Default.Apps, label = stringResource(R.string.tool_more), selected = false, onClick = onMore)
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    session: AnnotationSession,
    tool: MarkupTool
) {
    ToolbarItem(
        icon = icon,
        label = label,
        selected = session.tool == tool,
        onClick = {
            session.selection = null
            session.tool = if (session.tool == tool) MarkupTool.NONE else tool
        }
    )
}

@Composable
private fun ToolbarItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Colour and thickness for the active tool, plus a reminder of how to scroll while drawing. */
@Composable
private fun ToolOptionsRow(
    session: AnnotationSession,
    stamps: List<SavedStamp>,
    onCustomTextStamp: () -> Unit,
    onCustomImageStamp: () -> Unit
) {
    val tool = session.tool
    AnimatedVisibility(visible = tool != MarkupTool.NONE) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (tool) {
                    MarkupTool.TEXT_HIGHLIGHT -> ColorSwatches(HighlightColors, session.highlightColor) { session.highlightColor = it }
                    MarkupTool.PEN, MarkupTool.MARKER -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val currentBrush = if (session.tool == MarkupTool.MARKER) BrushType.MARKER else session.brushType
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                Triple(BrushType.BALLPOINT, Icons.Default.Edit, R.string.brush_pen),
                                Triple(BrushType.PENCIL, Icons.Default.Draw, R.string.brush_pencil),
                                Triple(BrushType.FOUNTAIN, Icons.Default.HistoryEdu, R.string.brush_fountain),
                                Triple(BrushType.BRUSH, Icons.Default.Brush, R.string.brush_brush),
                                Triple(BrushType.MARKER, Icons.Default.BorderColor, R.string.brush_marker)
                            ).forEach { (brush, icon, labelRes) ->
                                val isSelected = currentBrush == brush
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        session.brushType = brush
                                        if (brush == BrushType.MARKER) {
                                            session.tool = MarkupTool.MARKER
                                            session.lastDrawTool = MarkupTool.MARKER
                                        } else {
                                            session.tool = MarkupTool.PEN
                                            session.lastDrawTool = MarkupTool.PEN
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    label = { Text(stringResource(labelRes)) }
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f, fill = false)) {
                                val colors = if (currentBrush == BrushType.MARKER) HighlightColors else PenColors
                                val selectedColor = if (currentBrush == BrushType.MARKER) session.markerColor else session.penColor
                                ColorSwatches(colors, selectedColor) { color ->
                                    if (currentBrush == BrushType.MARKER) {
                                        session.markerColor = color
                                    } else {
                                        session.penColor = color
                                    }
                                }
                            }
                        }
                        Text(
                            text = stringResource(R.string.hint_drawing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    MarkupTool.RECTANGLE, MarkupTool.CIRCLE, MarkupTool.TRIANGLE, MarkupTool.STAR,
                    MarkupTool.CLOUD, MarkupTool.CALLOUT, MarkupTool.ARROW, MarkupTool.DOUBLE_ARROW,
                    MarkupTool.LINE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        data class ShapeItem(val tool: MarkupTool, val icon: androidx.compose.ui.graphics.vector.ImageVector, val labelRes: Int)
                        val shapes = listOf(
                            ShapeItem(MarkupTool.RECTANGLE, Icons.Default.CropSquare, R.string.tool_shape_rect),
                            ShapeItem(MarkupTool.CIRCLE, Icons.Default.Circle, R.string.tool_shape_circle),
                            ShapeItem(MarkupTool.TRIANGLE, Icons.Default.ChangeHistory, R.string.tool_shape_triangle),
                            ShapeItem(MarkupTool.STAR, Icons.Default.StarOutline, R.string.tool_shape_star),
                            ShapeItem(MarkupTool.CLOUD, Icons.Default.CloudQueue, R.string.tool_shape_cloud),
                            ShapeItem(MarkupTool.CALLOUT, Icons.Default.ChatBubbleOutline, R.string.tool_shape_callout),
                            ShapeItem(MarkupTool.ARROW, Icons.AutoMirrored.Filled.ArrowForward, R.string.tool_shape_arrow),
                            ShapeItem(MarkupTool.DOUBLE_ARROW, Icons.Default.SwapHoriz, R.string.tool_shape_double_arrow),
                            ShapeItem(MarkupTool.LINE, Icons.Default.HorizontalRule, R.string.tool_shape_line)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            shapes.forEach { shape ->
                                val isSelected = tool == shape.tool
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clickable { session.tool = shape.tool }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            shape.icon,
                                            contentDescription = stringResource(shape.labelRes),
                                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f, fill = false)) {
                                ColorSwatches(DocumentColors, session.shapeColor) { color ->
                                    session.shapeColor = color
                                    session.selectedAnnotation?.takeIf {
                                        it.type in listOf(
                                            AnnotationType.RECTANGLE, AnnotationType.CIRCLE, AnnotationType.TRIANGLE,
                                            AnnotationType.STAR, AnnotationType.CLOUD, AnnotationType.CALLOUT,
                                            AnnotationType.ARROW, AnnotationType.DOUBLE_ARROW, AnnotationType.LINE
                                        )
                                    }?.let { selected ->
                                        session.update(selected.copy(colorArgb = color.argb()))
                                    }
                                }
                            }
                        }
                        Text(stringResource(R.string.hint_shape), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MarkupTool.STAMP -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val predefinedStamps = PredefinedStampResIds.map { stringResource(it) }
                        if (session.selectedStampText.isEmpty() && session.selectedStampShape == null) {
                            session.selectedStampText = predefinedStamps.first()
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Action: + Texto
                            AssistChip(
                                onClick = onCustomTextStamp,
                                leadingIcon = {
                                    Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text(stringResource(R.string.stamp_custom_text)) }
                            )
                            // Action: + Imagen
                            AssistChip(
                                onClick = onCustomImageStamp,
                                leadingIcon = {
                                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text(stringResource(R.string.stamp_from_image)) }
                            )

                            // The user's saved stamps (also managed in Settings)
                            stamps.forEach { stamp ->
                                if (stamp.kind == StampKind.IMAGE && stamp.shape != null) {
                                    FilterChip(
                                        selected = session.selectedStampShape == stamp.shape,
                                        onClick = {
                                            session.selectedStampShape = stamp.shape
                                            session.selectedStampText = ""
                                            session.stampColor = Color(stamp.colorArgb)
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
                                        },
                                        label = { Text(stamp.name) }
                                    )
                                } else {
                                    FilterChip(
                                        selected = session.selectedStampShape == null && session.selectedStampText == stamp.text,
                                        onClick = {
                                            session.selectedStampShape = null
                                            session.selectedStampText = stamp.text
                                            session.stampColor = Color(stamp.colorArgb)
                                        },
                                        label = { Text(stamp.name) }
                                    )
                                }
                            }

                            // Predefined Stamps
                            predefinedStamps.forEach { stamp ->
                                val isSelected = session.selectedStampShape == null && session.selectedStampText == stamp
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        session.selectedStampShape = null
                                        session.selectedStampText = stamp
                                    },
                                    label = { Text(stamp) }
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f, fill = false)) {
                                ColorSwatches(StampColors, session.stampColor) { color ->
                                    session.stampColor = color
                                    session.selectedAnnotation?.takeIf { it.type == AnnotationType.STAMP }?.let { selected ->
                                        session.update(selected.copy(colorArgb = color.argb()))
                                    }
                                }
                            }
                        }
                        Text(stringResource(R.string.hint_stamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MarkupTool.REDACTION -> Text(
                        text = stringResource(R.string.hint_redact),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    else -> Text(
                        text = when (tool) {
                            MarkupTool.NOTE -> stringResource(R.string.hint_note)
                            MarkupTool.ERASER -> stringResource(R.string.hint_eraser)
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
fun ColorSwatches(colors: List<Color>, selected: Color, onSelect: (Color) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        colors.forEach { color ->
            val isSelected = color == selected
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    .padding(if (isSelected) 3.dp else 1.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(color)
                    .clickable { onSelect(color) }
            )
        }
    }
}

@Composable
private fun VerticalThicknessSlider(
    currentPt: Float,
    rangePt: ClosedFloatingPointRange<Float>,
    color: Color,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var isDragging by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier
                .width(44.dp)
                .height(180.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val hPx = constraints.maxHeight.toFloat()
                val wPx = constraints.maxWidth.toFloat()
                val vertPadPx = with(density) { 18.dp.toPx() }
                val usableHeight = (hPx - 2 * vertPadPx).coerceAtLeast(1f)

                // Current fraction (0 at bottom, 1 at top)
                val fraction = ((currentPt - rangePt.start) / (rangePt.endInclusive - rangePt.start)).coerceIn(0f, 1f)
                val thumbY = vertPadPx + (1f - fraction) * usableHeight
                val cx = wPx / 2f

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(rangePt) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                isDragging = true
                                val t = (1f - ((down.position.y - vertPadPx) / usableHeight)).coerceIn(0f, 1f)
                                onValueChange(rangePt.start + t * (rangePt.endInclusive - rangePt.start))

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        isDragging = false
                                        break
                                    }
                                    change.consume()
                                    val dragT = (1f - ((change.position.y - vertPadPx) / usableHeight)).coerceIn(0f, 1f)
                                    onValueChange(rangePt.start + dragT * (rangePt.endInclusive - rangePt.start))
                                }
                            }
                        }
                ) {
                    val topWidthPx = 18.dp.toPx()
                    val botWidthPx = 3.5.dp.toPx()

                    // Draw the vertical tapered bar (wedge) widening upwards
                    val wedgePath = Path().apply {
                        moveTo(cx - botWidthPx / 2f, hPx - vertPadPx)
                        lineTo(cx + botWidthPx / 2f, hPx - vertPadPx)
                        lineTo(cx + topWidthPx / 2f, vertPadPx)
                        lineTo(cx - topWidthPx / 2f, vertPadPx)
                        close()
                    }

                    // Background track (subtle neutral wedge)
                    drawPath(
                        path = wedgePath,
                        color = Color.Gray.copy(alpha = 0.25f)
                    )

                    // Filled wedge with the active stroke color
                    drawPath(
                        path = wedgePath,
                        color = color.copy(alpha = 0.70f)
                    )

                    // Outer border of the wedge for crispness
                    drawPath(
                        path = wedgePath,
                        color = Color.Black.copy(alpha = 0.15f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
                    )

                    // Thumb knob
                    val thumbRadius = 13.dp.toPx()
                    val thumbCenter = Offset(cx, thumbY)

                    // Outer shadow & white pill ring
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.20f),
                        radius = thumbRadius + 1.dp.toPx(),
                        center = thumbCenter
                    )
                    drawCircle(
                        color = Color.White,
                        radius = thumbRadius,
                        center = thumbCenter
                    )
                    drawCircle(
                        color = Color.LightGray.copy(alpha = 0.5f),
                        radius = thumbRadius,
                        center = thumbCenter,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
                    )

                    // Inner circle representing the real line thickness
                    val sampleDotRadius = (currentPt * 0.45f).coerceIn(2.5.dp.toPx(), thumbRadius - 3.dp.toPx())
                    drawCircle(
                        color = color,
                        radius = sampleDotRadius,
                        center = thumbCenter
                    )
                }
            }
        }

        // Floating label displaying the exact points value
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.90f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp
        ) {
            val label = if (currentPt < 2f) {
                String.format(java.util.Locale.US, "%.1f pt", currentPt)
            } else {
                "${currentPt.roundToInt()} pt"
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun SelectionActionBar(
    onCopy: () -> Unit,
    onMarkup: (AnnotationType) -> Unit,
    onComment: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarItem(Icons.Default.ContentCopy, stringResource(R.string.copy), false, onCopy)
            ToolbarItem(Icons.Default.BorderColor, stringResource(R.string.tool_highlight), false) { onMarkup(AnnotationType.HIGHLIGHT) }
            ToolbarItem(Icons.Default.FormatUnderlined, stringResource(R.string.sel_underline), false) { onMarkup(AnnotationType.UNDERLINE) }
            ToolbarItem(Icons.Default.FormatStrikethrough, stringResource(R.string.sel_strike), false) { onMarkup(AnnotationType.STRIKETHROUGH) }
            ToolbarItem(Icons.Default.AddComment, stringResource(R.string.sel_comment), false, onComment)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentToolsSheet(onDismiss: () -> Unit, onTool: (AppDestination) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            listOf(
                R.string.viewer_more_document to AppDestination.documentTools,
                R.string.viewer_more_file to AppDestination.fileTools
            ).forEach { (header, tools) ->
                Text(
                    text = stringResource(header),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp)
                )
                tools.forEach { destination ->
                    ListItem(
                        headlineContent = { Text(stringResource(destination.title)) },
                        supportingContent = { Text(stringResource(destination.description)) },
                        leadingContent = { Icon(destination.icon, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onTool(destination) }
                    )
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}

@Composable
private fun NoteDialog(
    draft: org.ultimatepdf.core.model.PdfAnnotation,
    isExisting: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit
) {
    var text by remember(draft.id) { mutableStateOf(draft.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isExisting) R.string.note_edit else R.string.note_add)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.note_placeholder)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (isExisting) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

@Composable
private fun GoToPageDialog(pageCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var input by remember { mutableStateOf("") }
    val page = input.toIntOrNull()?.takeIf { it in 1..pageCount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_go_to_page)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(R.string.viewer_page_field, pageCount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        },
        confirmButton = {
            TextButton(onClick = { page?.let { onGo(it - 1) } }, enabled = page != null) { Text(stringResource(R.string.go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
