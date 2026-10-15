package org.ultimatepdf.ui.sign

import androidx.compose.ui.res.stringResource
import org.ultimatepdf.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.ultimatepdf.core.model.PdfPoint
import org.ultimatepdf.core.model.SignatureShape

/** Finger / stylus signature pad producing a stroked vector signature. */
@Composable
fun SignaturePadDialog(
    color: Color,
    onDismiss: () -> Unit,
    onConfirm: (SignatureShape) -> Unit
) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var active by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val strokeWidthPx = with(LocalDensity.current) { 3.dp.toPx() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.pad_title), style = MaterialTheme.typography.titleLarge)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.2f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                ) {
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                // Starts at the touch-down point, so strokes don't lose their first few pixels.
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    down.consume()
                                    active = listOf(down.position)
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                        active = active + change.position
                                    }
                                    if (active.size > 1) strokes += active
                                    active = emptyList()
                                }
                            }
                    ) {
                        // Signature line and hint, like on paper.
                        val lineY = size.height * 0.75f
                        drawLine(Color(0xFFCBD5E1), Offset(size.width * 0.06f, lineY), Offset(size.width * 0.94f, lineY), 1.dp.toPx())
                        (strokes + listOf(active)).forEach { stroke ->
                            if (stroke.size < 2) return@forEach
                            val path = Path().apply {
                                moveTo(stroke[0].x, stroke[0].y)
                                for (i in 1 until stroke.size) lineTo(stroke[i].x, stroke[i].y)
                            }
                            drawPath(path, color, style = Stroke(strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                    if (strokes.isEmpty() && active.isEmpty()) {
                        Text(
                            stringResource(R.string.pad_sign_here),
                            color = Color(0xFF94A3B8),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 24.dp, bottom = 12.dp)
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text(stringResource(R.string.clear)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(
                        onClick = { toShape(strokes, strokeWidthPx)?.let(onConfirm) },
                        enabled = strokes.isNotEmpty()
                    ) { Text(stringResource(R.string.use)) }
                }
            }
        }
    }
}

/** Normalizes pad strokes to their own bounding box (padded by half the stroke width). */
private fun toShape(strokes: List<List<Offset>>, strokeWidthPx: Float): SignatureShape? {
    val all = strokes.flatten()
    if (all.size < 2) return null
    val half = strokeWidthPx / 2f
    val left = all.minOf { it.x } - half
    val top = all.minOf { it.y } - half
    val w = (all.maxOf { it.x } + half - left).coerceAtLeast(1f)
    val h = (all.maxOf { it.y } + half - top).coerceAtLeast(1f)
    return SignatureShape(
        contours = strokes.map { s -> s.map { PdfPoint((it.x - left) / w, (it.y - top) / h) } },
        filled = false,
        strokeWidth = strokeWidthPx / w,
        aspect = w / h
    )
}
