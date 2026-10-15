package org.ultimatepdf.ui.sign

import androidx.compose.ui.res.stringResource
import org.ultimatepdf.R
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.ultimatepdf.core.model.PdfRect
import org.ultimatepdf.core.model.SignatureShape
import org.ultimatepdf.core.signature.SignatureImages

private enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

/**
 * Turns a photo of a signature on paper into a vector signature: the user frames the
 * signature (pre-framed automatically), tunes the ink sensitivity and sees the vectorized
 * result live in the chosen colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignatureCaptureDialog(
    photo: Bitmap,
    colors: List<Color>,
    initialColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (SignatureShape, Color) -> Unit,
    isStamp: Boolean = false
) {
    var crop by remember { mutableStateOf(PdfRect(0.1f, 0.3f, 0.9f, 0.7f)) }
    var sensitivity by remember { mutableFloatStateOf(0.5f) }
    var color by remember { mutableStateOf(initialColor) }
    var shape by remember { mutableStateOf<SignatureShape?>(null) }
    var isProcessing by remember { mutableStateOf(true) }
    var autoFramed by remember { mutableStateOf(false) }

    LaunchedEffect(photo) {
        SignatureImages.detectSignatureArea(photo, 0.5f)?.let {
            crop = PdfRect(it[0], it[1], it[2], it[3])
        }
        autoFramed = true
    }

    LaunchedEffect(crop, sensitivity, autoFramed) {
        if (!autoFramed) return@LaunchedEffect
        isProcessing = true
        delay(120) // Let drags settle before re-tracing.
        shape = SignatureImages.vectorize(photo, floatArrayOf(crop.left, crop.top, crop.right, crop.bottom), sensitivity)
        isProcessing = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Full screen, edge to edge: the Scaffold below handles the system bar insets.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel)) }
                    },
                    title = { Text(stringResource(if (isStamp) R.string.stamp_capture_title else R.string.capture_title)) },
                    actions = {
                        Button(
                            onClick = { shape?.let { onConfirm(it, color) } },
                            enabled = shape != null && !isProcessing,
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text(stringResource(R.string.use)) }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(if (isStamp) R.string.stamp_capture_hint else R.string.capture_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CropArea(
                    photo = photo,
                    crop = crop,
                    onCropChange = { crop = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                Text(stringResource(R.string.capture_result), style = MaterialTheme.typography.titleSmall)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                ) {
                    val result = shape
                    when {
                        result != null -> SignaturePreview(result, color, Modifier.fillMaxSize())
                        isProcessing -> CircularProgressIndicator(Modifier.size(28.dp))
                        else -> Text(
                            stringResource(R.string.capture_no_ink),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.DarkGray,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.capture_sensitivity), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
                    Slider(value = sensitivity, onValueChange = { sensitivity = it }, modifier = Modifier.weight(1f))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text(stringResource(R.string.capture_colour), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(98.dp))
                    SignatureColorPicker(colors, color) { color = it }
                }
            }
        }
    }
}

@Composable
private fun CropArea(
    photo: Bitmap,
    crop: PdfRect,
    onCropChange: (PdfRect) -> Unit,
    modifier: Modifier
) {
    val image = remember(photo) { photo.asImageBitmap() }
    val currentCrop by rememberUpdatedState(crop)
    val handleRadius = with(LocalDensity.current) { 40.dp.toPx() }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val aspect = photo.width.toFloat() / photo.height
        val boxAspect = constraints.maxWidth.toFloat() / constraints.maxHeight
        val fitted = if (aspect > boxAspect) {
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
        } else {
            Modifier
                .fillMaxHeight()
                .aspectRatio(aspect)
        }
        Box(fitted) {
            Image(image, contentDescription = stringResource(R.string.capture_photo), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        val minSize = 0.05f
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val start = currentCrop
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val corners = mapOf(
                                CropHandle.TOP_LEFT to Offset(start.left * w, start.top * h),
                                CropHandle.TOP_RIGHT to Offset(start.right * w, start.top * h),
                                CropHandle.BOTTOM_LEFT to Offset(start.left * w, start.bottom * h),
                                CropHandle.BOTTOM_RIGHT to Offset(start.right * w, start.bottom * h)
                            )
                            val nearest = corners.minBy { (it.value - down.position).getDistance() }
                            val handle = when {
                                (nearest.value - down.position).getDistance() <= handleRadius -> nearest.key
                                down.position.x / w in start.left..start.right &&
                                    down.position.y / h in start.top..start.bottom -> CropHandle.MOVE
                                else -> return@awaitEachGesture
                            }
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                val dx = (change.position.x - down.position.x) / w
                                val dy = (change.position.y - down.position.y) / h
                                onCropChange(
                                    when (handle) {
                                        CropHandle.MOVE -> {
                                            val mx = dx.coerceIn(-start.left, 1f - start.right)
                                            val my = dy.coerceIn(-start.top, 1f - start.bottom)
                                            PdfRect(start.left + mx, start.top + my, start.right + mx, start.bottom + my)
                                        }
                                        CropHandle.TOP_LEFT -> start.copy(
                                            left = (start.left + dx).coerceIn(0f, start.right - minSize),
                                            top = (start.top + dy).coerceIn(0f, start.bottom - minSize)
                                        )
                                        CropHandle.TOP_RIGHT -> start.copy(
                                            right = (start.right + dx).coerceIn(start.left + minSize, 1f),
                                            top = (start.top + dy).coerceIn(0f, start.bottom - minSize)
                                        )
                                        CropHandle.BOTTOM_LEFT -> start.copy(
                                            left = (start.left + dx).coerceIn(0f, start.right - minSize),
                                            bottom = (start.bottom + dy).coerceIn(start.top + minSize, 1f)
                                        )
                                        CropHandle.BOTTOM_RIGHT -> start.copy(
                                            right = (start.right + dx).coerceIn(start.left + minSize, 1f),
                                            bottom = (start.bottom + dy).coerceIn(start.top + minSize, 1f)
                                        )
                                    }
                                )
                            }
                        }
                    }
            ) {
                val r = Rect(crop.left * size.width, crop.top * size.height, crop.right * size.width, crop.bottom * size.height)
                val dim = Color.Black.copy(alpha = 0.55f)
                drawRect(dim, Offset.Zero, Size(size.width, r.top))
                drawRect(dim, Offset(0f, r.bottom), Size(size.width, size.height - r.bottom))
                drawRect(dim, Offset(0f, r.top), Size(r.left, r.height))
                drawRect(dim, Offset(r.right, r.top), Size(size.width - r.right, r.height))
                drawRect(Color.White, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
                listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).forEach { corner ->
                    drawCircle(Color.White, 9.dp.toPx(), corner)
                }
            }
        }
    }
}

@Composable
fun SignatureColorPicker(colors: List<Color>, selected: Color, onSelect: (Color) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { c ->
            val isSelected = c == selected
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    .padding(if (isSelected) 3.dp else 1.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(c)
                    .clickable { onSelect(c) }
            )
        }
    }
}

