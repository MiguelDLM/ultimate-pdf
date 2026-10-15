package org.ultimatepdf.ui.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.ultimatepdf.R
import org.ultimatepdf.core.model.QuadCorners
import org.ultimatepdf.core.scanner.DocumentDetector
import org.ultimatepdf.core.scanner.ScanMode
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Camera viewfinder with the detected sheet outlined live. Every shot goes straight into the
 * batch; processing happens later in review.
 */
@Composable
fun ScanCamera(
    session: ScanSession,
    onCaptured: (Bitmap) -> Unit,
    onImport: () -> Unit,
    onReview: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val detector = remember { DocumentDetector() }
    // Live outline, normalized (0..1) to the upright camera frame.
    var outline by remember { mutableStateOf<List<PointF>?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }

    // Shutter flash animation
    var flashActive by remember { mutableStateOf(false) }
    val flashAlpha by animateFloatAsState(targetValue = if (flashActive) 0.85f else 0f, animationSpec = tween(180), label = "flash")

    // Flying captured thumbnail animation ("screen shoot" shrink-and-fly effect)
    var flyingThumb by remember { mutableStateOf<Bitmap?>(null) }
    val flyAnim = remember { Animatable(0f) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            )
            .build()
    }
    val previewView = remember {
        PreviewView(context).apply {
            // Whole frame visible: what you see is what gets captured.
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            var lastRun = 0L
            var misses = 0
            analysis.setAnalyzer(analysisExecutor) { image ->
                val now = System.currentTimeMillis()
                if (now - lastRun < ANALYSIS_INTERVAL_MS) {
                    image.close()
                    return@setAnalyzer
                }
                lastRun = now
                val detected = runCatching { detectInFrame(detector, image) }.getOrNull()
                image.close()
                ContextCompat.getMainExecutor(context).execute {
                    if (detected != null) {
                        misses = 0
                        // Ease towards the new outline so it doesn't jitter.
                        outline = outline?.zip(detected) { a, b -> PointF(a.x + (b.x - a.x) * 0.6f, a.y + (b.y - a.y) * 0.6f) } ?: detected
                    } else if (++misses > 3) {
                        outline = null
                    }
                }
            }
            provider.unbindAll()
            camera = runCatching {
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, imageCapture)
            }.getOrNull()
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            analysisExecutor.shutdown()
            captureExecutor.shutdown()
        }
    }

    fun capture() {
        if (isCapturing) return
        isCapturing = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        flashActive = true
        scope.launch {
            delay(50)
            flashActive = false
        }
        imageCapture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val photo = runCatching { uprightBitmap(image) }.getOrNull()
                image.close()
                ContextCompat.getMainExecutor(context).execute {
                    isCapturing = false
                    if (photo != null) {
                        val thumbW = 240
                        val thumbH = (240f * photo.height / photo.width).toInt().coerceAtLeast(100)
                        val thumb = Bitmap.createScaledBitmap(photo, thumbW, thumbH, true)
                        flyingThumb = thumb
                        scope.launch {
                            flyAnim.snapTo(0f)
                            flyAnim.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
                            flyingThumb = null
                        }
                        onCaptured(photo)
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                ContextCompat.getMainExecutor(context).execute { isCapturing = false }
            }
        })
    }

    val count = session.captures.size
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        if (session.mode == ScanMode.ID_CARD) {
            IdCardGuideOverlay(step = count.coerceAtMost(1), modifier = Modifier.fillMaxSize())
        } else {
            OutlineOverlay(outline, Modifier.fillMaxSize())
        }

        // Shutter flash effect
        if (flashAlpha > 0.01f) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha)))
        }

        // Screenshot flying animation towards the bottom corner thumbnail
        if (flyingThumb != null) {
            val p = flyAnim.value
            val scale = 0.82f * (1f - p) + 0.16f * p
            val alpha = (1f - p * 0.7f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                        translationX = p * (size.width * 0.36f)
                        translationY = p * (size.height * 0.42f)
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    flyingThumb!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = 240.dp, height = 320.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(3.dp, Color.White, RoundedCornerShape(16.dp))
                )
            }
        }

        // Top: back, flash, mode, step indicator & hints.
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                }) {
                    Icon(
                        if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = stringResource(R.string.scan_flash),
                        tint = Color.White
                    )
                }
            }
            ModeSelector(
                mode = session.mode,
                enabled = count == 0,
                onMode = { session.mode = it },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            if (session.mode == ScanMode.ID_CARD) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StepBadge(
                        label = stringResource(R.string.scan_id_step_1),
                        isCurrent = count == 0,
                        isDone = count > 0
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("➔", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    StepBadge(
                        label = stringResource(R.string.scan_id_step_2),
                        isCurrent = count == 1,
                        isDone = count >= 2
                    )
                }
            }

            Text(
                text = stringResource(
                    when {
                        session.mode == ScanMode.ID_CARD && count == 0 -> R.string.scan_id_align_front
                        session.mode == ScanMode.ID_CARD && count == 1 -> R.string.scan_id_front_captured
                        session.mode == ScanMode.ID_CARD -> R.string.scan_id_both_captured
                        session.mode == ScanMode.BOOK -> R.string.scan_hint_book
                        else -> R.string.scan_hint_document
                    }
                ),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 4.dp, start = 16.dp, end = 16.dp)
            )
        }

        // Bottom: ID card thumbnails (if in ID mode) + import, shutter, batch.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .navigationBarsPadding()
                .padding(vertical = 12.dp)
        ) {
            if (session.mode == ScanMode.ID_CARD) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    IdCardSlot(
                        title = stringResource(R.string.scan_id_front),
                        capture = session.captures.getOrNull(0),
                        isTarget = count == 0,
                        onRetake = {
                            if (session.captures.isNotEmpty()) session.remove(session.captures[0])
                        }
                    )
                    IdCardSlot(
                        title = stringResource(R.string.scan_id_back),
                        capture = session.captures.getOrNull(1),
                        isTarget = count == 1,
                        onRetake = {
                            if (session.captures.size >= 2) session.remove(session.captures[1])
                        }
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onImport, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = stringResource(R.string.scan_import), tint = Color.White)
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(if (isCapturing) Color.Gray else Color.White)
                        .clickable(enabled = !isCapturing && !(session.mode == ScanMode.ID_CARD && count >= 2)) { capture() }
                ) {}
                BatchButton(session, onReview)
            }
        }
    }
}

@Composable
private fun StepBadge(label: String, isCurrent: Boolean, isDone: Boolean) {
    val bg = when {
        isDone -> Color(0xFF2B8A3E)
        isCurrent -> MaterialTheme.colorScheme.tertiary
        else -> Color.White.copy(alpha = 0.2f)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = Modifier.padding(2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            if (isDone) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
    }
}

@Composable
private fun IdCardSlot(
    title: String,
    capture: ScanCapture?,
    isTarget: Boolean,
    onRetake: () -> Unit
) {
    val borderColor = when {
        isTarget -> MaterialTheme.colorScheme.tertiary
        capture != null -> Color(0xFF40C057)
        else -> Color.White.copy(alpha = 0.4f)
    }
    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 62.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(if (isTarget) 2.5.dp else 1.5.dp, borderColor, RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(enabled = capture != null, onClick = onRetake),
        contentAlignment = Alignment.Center
    ) {
        if (capture != null) {
            Image(
                capture.thumbnail.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f))
            )
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Color(0xFF40C057),
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
            )
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    if (isTarget) Icons.Default.CameraAlt else Icons.Default.AccountBox,
                    contentDescription = null,
                    tint = if (isTarget) Color.White else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isTarget) Color.White else Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun ModeSelector(mode: ScanMode, enabled: Boolean, onMode: (ScanMode) -> Unit, modifier: Modifier) {
    val options = listOf(
        ScanMode.DOCUMENT to R.string.scan_mode_document,
        ScanMode.ID_CARD to R.string.scan_mode_id,
        ScanMode.BOOK to R.string.scan_mode_book
    )
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (option, label) ->
            SegmentedButton(
                selected = mode == option,
                onClick = { onMode(option) },
                enabled = enabled || mode == option,
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Color.White,
                    activeContentColor = Color.Black,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = Color.White,
                    activeBorderColor = Color.White,
                    inactiveBorderColor = Color.White.copy(alpha = 0.6f),
                    disabledInactiveContainerColor = Color.Transparent,
                    disabledInactiveContentColor = Color.White.copy(alpha = 0.4f),
                    disabledInactiveBorderColor = Color.White.copy(alpha = 0.3f)
                )
            ) { Text(stringResource(label), maxLines = 1) }
        }
    }
}

@Composable
private fun BatchButton(session: ScanSession, onReview: () -> Unit) {
    val last = session.captures.lastOrNull()
    val badgeScale = remember { Animatable(1f) }
    LaunchedEffect(session.captures.size) {
        if (session.captures.isNotEmpty()) {
            badgeScale.animateTo(1.5f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            badgeScale.animateTo(1.0f, spring())
        }
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, Color.White, RoundedCornerShape(10.dp))
            .clickable(enabled = last != null, onClick = onReview),
        contentAlignment = Alignment.Center
    ) {
        if (last != null) {
            Image(last.thumbnail.asImageBitmap(), contentDescription = stringResource(R.string.scan_review), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Badge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .graphicsLayer {
                        scaleX = badgeScale.value
                        scaleY = badgeScale.value
                    }
            ) { Text("${session.captures.size}") }
        }
    }
}

@Composable
private fun IdCardGuideOverlay(step: Int, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.tertiary
    val cardRatio = 85.6f / 54f // ISO/IEC 7810 ID-1 aspect ratio ~ 1.585
    val cornerRadius = 14.dp

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val cardWidth = widthPx * 0.88f
        val cardHeight = cardWidth / cardRatio
        val cardLeft = (widthPx - cardWidth) / 2f
        val cardTop = (heightPx - cardHeight) / 2f - 40f
        val cardRect = Rect(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight)

        Canvas(Modifier.fillMaxSize()) {
            val rrect = RoundRect(cardRect, CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()))

            // Dark translucent surround outside card cutout
            clipPath(Path().apply { addRoundRect(rrect) }, clipOp = ClipOp.Difference) {
                drawRect(Color.Black.copy(alpha = 0.62f))
            }

            // Card border
            drawRoundRect(
                color = Color.White.copy(alpha = 0.85f),
                topLeft = cardRect.topLeft,
                size = cardRect.size,
                cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
                style = Stroke(width = 2.dp.toPx())
            )

            // 4-corner brackets (reticles)
            val bracketLen = 26.dp.toPx()
            val bracketStroke = 4.dp.toPx()

            // Top-Left
            drawLine(accent, Offset(cardRect.left, cardRect.top + bracketLen), Offset(cardRect.left, cardRect.top), strokeWidth = bracketStroke)
            drawLine(accent, Offset(cardRect.left, cardRect.top), Offset(cardRect.left + bracketLen, cardRect.top), strokeWidth = bracketStroke)

            // Top-Right
            drawLine(accent, Offset(cardRect.right - bracketLen, cardRect.top), Offset(cardRect.right, cardRect.top), strokeWidth = bracketStroke)
            drawLine(accent, Offset(cardRect.right, cardRect.top), Offset(cardRect.right, cardRect.top + bracketLen), strokeWidth = bracketStroke)

            // Bottom-Right
            drawLine(accent, Offset(cardRect.right, cardRect.bottom - bracketLen), Offset(cardRect.right, cardRect.bottom), strokeWidth = bracketStroke)
            drawLine(accent, Offset(cardRect.right, cardRect.bottom), Offset(cardRect.right - bracketLen, cardRect.bottom), strokeWidth = bracketStroke)

            // Bottom-Left
            drawLine(accent, Offset(cardRect.left + bracketLen, cardRect.bottom), Offset(cardRect.left, cardRect.bottom), strokeWidth = bracketStroke)
            drawLine(accent, Offset(cardRect.left, cardRect.bottom), Offset(cardRect.left, cardRect.bottom - bracketLen), strokeWidth = bracketStroke)
        }

        // Inside card watermark guide
        Box(
            modifier = Modifier
                .offset(
                    x = with(LocalDensity.current) { cardLeft.toDp() },
                    y = with(LocalDensity.current) { cardTop.toDp() }
                )
                .size(
                    width = with(LocalDensity.current) { cardWidth.toDp() },
                    height = with(LocalDensity.current) { cardHeight.toDp() }
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (step == 0) {
                // Front watermark: photo box on left, text lines on right
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight(0.65f)
                            .aspectRatio(0.78f)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(Modifier.fillMaxWidth(0.85f).height(10.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(5.dp)))
                        Box(Modifier.fillMaxWidth(0.6f).height(8.dp).background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp)))
                        Box(Modifier.fillMaxWidth(0.75f).height(8.dp).background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp)))
                    }
                }
            } else {
                // Back watermark: magnetic stripe on top, chip/signature box below
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(4.dp))
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(22.dp)
                                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        )
                        Spacer(Modifier.width(16.dp))
                        Box(
                            modifier = Modifier
                                .size(width = 44.dp, height = 30.dp)
                                .border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(if (step == 0) R.string.scan_id_step_1 else R.string.scan_id_step_2),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(if (step == 0) R.string.scan_id_align_front else R.string.scan_id_align_back),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

@Composable
private fun OutlineOverlay(outline: List<PointF>?, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        val points = outline ?: return@Canvas
        // The preview is fit-centered with the camera's 3:4 portrait aspect.
        val frameAspect = 3f / 4f
        val (w, h) = if (size.width / size.height > frameAspect) size.height * frameAspect to size.height else size.width to size.width / frameAspect
        val ox = (size.width - w) / 2f
        val oy = (size.height - h) / 2f
        val path = Path().apply {
            points.forEachIndexed { i, p ->
                val x = ox + p.x * w
                val y = oy + p.y * h
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(path, accent.copy(alpha = 0.22f))
        drawPath(path, accent, style = Stroke(width = 3.dp.toPx()))
        points.forEach { p -> drawCircle(Color.White, 6.dp.toPx(), Offset(ox + p.x * w, oy + p.y * h)) }
    }
}

/** Runs detection on the frame's luminance plane; returns corners normalized to the upright frame. */
private fun detectInFrame(detector: DocumentDetector, image: ImageProxy): List<PointF>? {
    val plane = image.planes[0]
    val width = image.width
    val height = image.height
    val gray = Mat(height, width, CvType.CV_8UC1)
    val buffer = plane.buffer
    val row = ByteArray(width)
    for (y in 0 until height) {
        buffer.position(y * plane.rowStride)
        buffer.get(row, 0, width)
        gray.put(y, 0, row)
    }
    val quad: QuadCorners? = detector.detectInGray(gray)
    gray.release()
    quad ?: return null
    val rotation = image.imageInfo.rotationDegrees
    return quad.toList().map { p ->
        val u = p.x / width
        val v = p.y / height
        when (rotation) {
            90 -> PointF(1f - v, u)
            180 -> PointF(1f - u, 1f - v)
            270 -> PointF(v, 1f - u)
            else -> PointF(u, v)
        }
    }
}

/** The captured photo upright and at most [MAX_PHOTO_SIZE] px on its long side. */
private fun uprightBitmap(image: ImageProxy): Bitmap {
    val raw = image.toBitmap()
    val scale = min(1f, MAX_PHOTO_SIZE.toFloat() / max(raw.width, raw.height))
    val matrix = Matrix().apply {
        postScale(scale, scale)
        postRotate(image.imageInfo.rotationDegrees.toFloat())
    }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also { if (it !== raw) raw.recycle() }
}

private const val ANALYSIS_INTERVAL_MS = 120L
const val MAX_PHOTO_SIZE = 2400
