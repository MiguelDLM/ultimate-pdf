package org.ultimatepdf.ui.scanner

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ultimatepdf.R
import org.ultimatepdf.core.model.QuadCorners
import org.ultimatepdf.core.model.ScanFilter
import org.ultimatepdf.core.scanner.ScanMode
import org.ultimatepdf.core.scanner.ScanPipeline
import org.ultimatepdf.ui.components.ScreenTopBar
import org.ultimatepdf.ui.editor.ActionItem

private const val PREVIEW_SIZE = 1400

private val FilterLabels = listOf(
    ScanFilter.ORIGINAL to R.string.scan_filter_original,
    ScanFilter.MAGIC_COLOR to R.string.scan_magic,
    ScanFilter.NO_SHADOW to R.string.scan_filter_no_shadow,
    ScanFilter.GRAYSCALE to R.string.scan_gray,
    ScanFilter.BLACK_WHITE to R.string.scan_bw
)

/** Adjust each capture before saving: crop corners, filter, rotation, order of work. */
@Composable
fun ScanReview(
    session: ScanSession,
    pipeline: ScanPipeline,
    onAddMore: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    val capture = session.captures.getOrNull(session.selected)
    var cropping by remember { mutableStateOf(false) }
    var showComposedId by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            ScreenTopBar(pluralStringResource(R.plurals.scan_pages, session.captures.size, session.captures.size), onBack) {
                Button(
                    onClick = onSave,
                    enabled = session.isComplete && !cropping,
                    modifier = Modifier.padding(end = 8.dp)
                ) { Text(stringResource(R.string.save)) }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (session.mode == ScanMode.ID_CARD && session.captures.size == 2 && !cropping) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    SegmentedButton(
                        selected = !showComposedId,
                        onClick = { showComposedId = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text(stringResource(R.string.scan_id_view_sides), maxLines = 1) }
                    SegmentedButton(
                        selected = showComposedId,
                        onClick = { showComposedId = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text(stringResource(R.string.scan_id_view_composed), maxLines = 1) }
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                if (capture == null) return@Box
                if (cropping) {
                    CropEditor(capture, pipeline, onDone = { cropping = false })
                } else {
                    ProcessedPreview(capture, session.mode, pipeline, showComposedId, session)
                }
            }

            if (capture != null && !cropping) {
                if (session.mode == ScanMode.ID_CARD && !session.isComplete) {
                    Text(
                        stringResource(R.string.scan_id_needs_back),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                FilterRow(
                    selected = capture.filter,
                    onSelect = { capture.filter = it },
                    onApplyToAll = {
                        session.defaultFilter = capture.filter
                        session.captures.forEach {
                            it.filter = capture.filter
                            it.filterIntensity = capture.filterIntensity
                        }
                    }
                )
                if (capture.filter != ScanFilter.ORIGINAL) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.scan_filter_tuning),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Slider(
                            value = capture.filterIntensity,
                            onValueChange = { capture.filterIntensity = it },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${(capture.filterIntensity * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    ActionItem(Icons.Default.Crop, stringResource(R.string.scan_crop)) { cropping = true }
                    ActionItem(Icons.AutoMirrored.Filled.RotateRight, stringResource(R.string.organize_rotate_right)) {
                        capture.rotation = (capture.rotation + 90) % 360
                    }
                    ActionItem(Icons.Outlined.DeleteOutline, stringResource(R.string.delete)) { session.remove(capture) }
                    ActionItem(Icons.Default.AddAPhoto, stringResource(R.string.scan_add_more), onAddMore)
                }
                Thumbnails(session)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.scan_ocr), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.scan_ocr_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = session.ocrEnabled, onCheckedChange = { session.ocrEnabled = it })
                }
                Spacer(Modifier.navigationBarsPadding().height(8.dp))
            }
        }
    }
}

@Composable
private fun ProcessedPreview(
    capture: ScanCapture,
    mode: ScanMode,
    pipeline: ScanPipeline,
    showComposedId: Boolean = false,
    session: ScanSession? = null
) {
    var zoomScale by remember(capture.id, capture.filter, capture.rotation, showComposedId) { mutableFloatStateOf(1f) }
    var panOffset by remember(capture.id, capture.filter, capture.rotation, showComposedId) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(capture.id, capture.corners, capture.filter, capture.filterIntensity, capture.rotation, mode) {
        capture.preview = pipeline.process(
            capture.file,
            capture.width,
            capture.corners,
            capture.filter,
            capture.rotation,
            mode,
            PREVIEW_SIZE,
            capture.filterIntensity
        )
    }

    var composedBitmap by remember(showComposedId, session?.captures?.size) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(showComposedId, capture.filter, capture.filterIntensity, capture.rotation, capture.corners) {
        if (showComposedId && mode == ScanMode.ID_CARD && session != null && session.captures.size == 2) {
            val c0 = session.captures[0]
            val c1 = session.captures[1]
            val front = pipeline.process(c0.file, c0.width, c0.corners, c0.filter, c0.rotation, ScanMode.DOCUMENT, PREVIEW_SIZE, c0.filterIntensity).firstOrNull()
            val back = pipeline.process(c1.file, c1.width, c1.corners, c1.filter, c1.rotation, ScanMode.DOCUMENT, PREVIEW_SIZE, c1.filterIntensity).firstOrNull()
            if (front != null && back != null) {
                composedBitmap = pipeline.composeIdCard(front, back)
            }
        } else {
            composedBitmap = null
        }
    }

    val displayPages = if (showComposedId && composedBitmap != null) listOf(composedBitmap!!) else capture.preview
    if (displayPages.isEmpty()) {
        CircularProgressIndicator()
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(capture.id, showComposedId) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (zoomScale * zoom).coerceIn(1f, 5f)
                    zoomScale = newScale
                    if (newScale > 1f) {
                        val maxPanX = size.width * (newScale - 1f) / 2f
                        val maxPanY = size.height * (newScale - 1f) / 2f
                        panOffset = Offset(
                            (panOffset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                            (panOffset.y + pan.y).coerceIn(-maxPanY, maxPanY)
                        )
                    } else {
                        panOffset = Offset.Zero
                    }
                }
            }
            .pointerInput(capture.id, showComposedId) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (zoomScale > 1.2f) {
                        zoomScale = 1f
                        panOffset = Offset.Zero
                    } else {
                        zoomScale = 2.5f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val delta = tap - center
                        val maxPanX = size.width * 1.5f / 2f
                        val maxPanY = size.height * 1.5f / 2f
                        panOffset = Offset(
                            (-delta.x * 1.5f).coerceIn(-maxPanX, maxPanX),
                            (-delta.y * 1.5f).coerceIn(-maxPanY, maxPanY)
                        )
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = panOffset.x
                    translationY = panOffset.y
                }
        ) {
            displayPages.forEach { page ->
                Image(
                    page.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }

        if (zoomScale > 1.05f) {
            Surface(
                onClick = {
                    zoomScale = 1f
                    panOffset = Offset.Zero
                },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                tonalElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.ZoomOutMap, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.scan_zoom_reset, zoomScale),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterRow(selected: ScanFilter, onSelect: (ScanFilter) -> Unit, onApplyToAll: () -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        items(FilterLabels.size) { i ->
            val (filter, label) = FilterLabels[i]
            FilterChip(selected = filter == selected, onClick = { onSelect(filter) }, label = { Text(stringResource(label)) })
        }
        item { TextButton(onClick = onApplyToAll) { Text(stringResource(R.string.scan_apply_all)) } }
    }
}

@Composable
private fun Thumbnails(session: ScanSession) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        itemsIndexed(session.captures, key = { _, c -> c.id }) { index, capture ->
            val isSelected = index == session.selected
            Box(
                Modifier
                    .size(width = 52.dp, height = 68.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(if (isSelected) 2.5.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .clickable { session.selected = index }
            ) {
                Image(capture.thumbnail.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(topEnd = 6.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
    }
}

/** Drag the four corners over the original photo; "Auto" re-detects, "Full" uses the whole photo. */
@Composable
private fun CropEditor(capture: ScanCapture, pipeline: ScanPipeline, onDone: () -> Unit) {
    var image by remember(capture.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(capture.id) { image = pipeline.loadImage(capture.file, PREVIEW_SIZE) }
    val photo = image
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            if (photo == null) {
                CircularProgressIndicator()
            } else {
                CornerEditor(photo, capture)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedButton(onClick = {
                photo?.let { p ->
                    val scale = capture.width.toFloat() / p.width
                    capture.corners = pipeline.detectCorners(p).scaled(scale).clamped(capture.width.toFloat(), capture.height.toFloat())
                }
            }) { Text(stringResource(R.string.scan_crop_auto)) }
            OutlinedButton(onClick = {
                capture.corners = QuadCorners.defaultFor(capture.width.toFloat(), capture.height.toFloat(), insetRatio = 0f)
            }) { Text(stringResource(R.string.scan_crop_full)) }
            Spacer(Modifier.weight(1f))
            Button(onClick = onDone) { Text(stringResource(R.string.done)) }
        }
    }
}

@Composable
private fun CornerEditor(photo: Bitmap, capture: ScanCapture) {
    val accent = MaterialTheme.colorScheme.tertiary
    val density = LocalDensity.current
    val marginDp = 28.dp
    val marginPx = with(density) { marginDp.toPx() }
    val touchRadius = with(density) { 48.dp.toPx() }

    LaunchedEffect(capture.id) {
        capture.corners = capture.corners.clamped(capture.width.toFloat(), capture.height.toFloat())
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val maxAvailableW = (constraints.maxWidth.toFloat() - marginPx * 2).coerceAtLeast(10f)
        val maxAvailableH = (constraints.maxHeight.toFloat() - marginPx * 2).coerceAtLeast(10f)
        val photoAspect = photo.width.toFloat() / photo.height.toFloat()

        val (imgW, imgH) = if (maxAvailableW / maxAvailableH > photoAspect) {
            (maxAvailableH * photoAspect) to maxAvailableH
        } else {
            maxAvailableW to (maxAvailableW / photoAspect)
        }

        val canvasW = imgW + marginPx * 2
        val canvasH = imgH + marginPx * 2
        val photoBitmap = remember(photo) { photo.asImageBitmap() }

        Canvas(
            Modifier
                .size(with(density) { canvasW.toDp() }, with(density) { canvasH.toDp() })
                .pointerInput(capture.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val scaleX = imgW / capture.width.toFloat()
                        val scaleY = imgH / capture.height.toFloat()
                        val corners = capture.corners.clamped(capture.width.toFloat(), capture.height.toFloat()).toList()
                        val screenCorners = corners.map { Offset(marginPx + it.x * scaleX, marginPx + it.y * scaleY) }
                        val nearest = screenCorners.indices.minByOrNull { i ->
                            (screenCorners[i] - down.position).getDistance()
                        } ?: return@awaitEachGesture

                        if ((screenCorners[nearest] - down.position).getDistance() > touchRadius) {
                            return@awaitEachGesture
                        }
                        down.consume()
                        val workingCorners = corners.toMutableList()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val x = ((change.position.x - marginPx) / scaleX).coerceIn(0f, capture.width.toFloat())
                            val y = ((change.position.y - marginPx) / scaleY).coerceIn(0f, capture.height.toFloat())
                            workingCorners[nearest] = PointF(x, y)
                            capture.corners = QuadCorners(workingCorners[0], workingCorners[1], workingCorners[2], workingCorners[3])
                        }
                    }
                }
        ) {
            // 1. Draw photo bitmap centered with marginPx margin
            drawImage(
                image = photoBitmap,
                dstOffset = androidx.compose.ui.unit.IntOffset(kotlin.math.round(marginPx).toInt(), kotlin.math.round(marginPx).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(kotlin.math.round(imgW).toInt(), kotlin.math.round(imgH).toInt()),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium
            )

            // 2. Draw quadrilateral
            val scaleX = imgW / capture.width.toFloat()
            val scaleY = imgH / capture.height.toFloat()
            val corners = capture.corners.clamped(capture.width.toFloat(), capture.height.toFloat()).toList()
            val points = corners.map { Offset(marginPx + it.x * scaleX, marginPx + it.y * scaleY) }
            val path = Path().apply {
                points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                close()
            }
            drawPath(path, accent.copy(alpha = 0.22f))
            drawPath(path, accent, style = Stroke(2.5.dp.toPx()))

            // 3. Draw corner handles
            points.forEach {
                drawCircle(Color.White, 14.dp.toPx(), it)
                drawCircle(accent, 10.dp.toPx(), it)
            }
        }
    }
}

private fun QuadCorners.scaled(f: Float) = QuadCorners(
    PointF(topLeft.x * f, topLeft.y * f),
    PointF(topRight.x * f, topRight.y * f),
    PointF(bottomRight.x * f, bottomRight.y * f),
    PointF(bottomLeft.x * f, bottomLeft.y * f)
)
