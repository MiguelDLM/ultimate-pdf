package org.ultimatepdf.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import org.ultimatepdf.core.model.AnnotationType
import org.ultimatepdf.core.model.PdfAnnotation
import org.ultimatepdf.core.model.PdfPoint
import org.ultimatepdf.core.model.PdfRect
import org.ultimatepdf.core.pdf.PageText
import org.ultimatepdf.ui.sign.drawSignature
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

const val MarkerOpacity = 0.4f
val UnderlineColor = Color(0xFF1971C2)
val StrikeColor = Color(0xFFE03131)
val NoteColor = Color(0xFFF59F00)

private enum class Handle { START, END }


/**
 * Transparent layer stacked on a rendered page. Draws pending annotations and the text
 * selection, and turns touches into selection, ink, notes or erasing depending on the tool.
 *
 * Single-finger gestures belong to this layer; multi-touch is left to the reader for
 * pinch-zoom and two-finger scrolling while a drawing tool is active.
 */
@Composable
fun PageMarkupLayer(
    pageIndex: Int,
    pageAspect: Float,
    pageText: PageText?,
    session: AnnotationSession,
    invertColors: Boolean,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val currentText by rememberUpdatedState(pageText)
    var liveStroke by remember { mutableStateOf<List<PdfPoint>?>(null) }
    var liveShape by remember { mutableStateOf<Pair<MarkupTool, PdfRect>?>(null) }
    var liveLine by remember { mutableStateOf<Pair<MarkupTool, List<PdfPoint>>?>(null) }
    val selectionColor = MaterialTheme.colorScheme.primary
    val notePainter = rememberVectorPainter(Icons.AutoMirrored.Filled.Comment)
    val tool = session.tool

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(tool, pageIndex) {
                val handleRadius = 28.dp.toPx()
                val touchSlop = viewConfiguration.touchSlop
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                val tolerance = 16.dp.toPx() / size.width

                fun norm(o: Offset) = PdfPoint(
                    (o.x / size.width).coerceIn(0f, 1f),
                    (o.y / size.height).coerceIn(0f, 1f)
                )

                fun eraseAt(p: PdfPoint) {
                    session.forPage(pageIndex)
                        .lastOrNull { it.hitTest(p.x, p.y, tolerance, pageAspect) }
                        ?.let(session::remove)
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // If multiple fingers are already down (pinch/zoom started), ignore single-finger drawing
                    if (currentEvent.changes.count { it.pressed } > 1) return@awaitEachGesture
                    val text = currentText

                    // Dragging a handle of the current selection takes priority over any tool.
                    val selection = session.selection
                    if (selection != null && selection.pageIndex == pageIndex && text != null) {
                        val rects = text.lineRects(selection.range)
                        val handle = handleAt(down.position, rects, size.width.toFloat(), size.height.toFloat(), handleRadius)
                        if (handle != null) {
                            down.consume()
                            trackDrag(down.id) { pos ->
                                val p = norm(pos)
                                val index = text.nearestIndex(p.x, p.y) ?: return@trackDrag
                                val current = session.selection ?: return@trackDrag
                                session.selection = if (handle == Handle.START) {
                                    current.copy(anchor = current.range.last, focus = index)
                                } else {
                                    current.copy(anchor = current.range.first, focus = index)
                                }
                            }
                            return@awaitEachGesture
                        }
                    }

                    // A selected annotation (signature, stamp, shape, stroke): move, resize via corners, or rotate
                    val selected = session.selectedAnnotation
                    if (selected != null && selected.pageIndex == pageIndex) {
                        val b = selected.bounds
                        val box = Rect(b.left * size.width, b.top * size.height, b.right * size.width, b.bottom * size.height)
                        val center = box.center
                        val cornerRadius = handleRadius.coerceAtLeast(32.dp.toPx())

                        // Un-rotate touch point to align with axis-aligned bounding box coordinates
                        val rad = Math.toRadians(-selected.rotation.toDouble())
                        val cosR = kotlin.math.cos(rad).toFloat()
                        val sinR = kotlin.math.sin(rad).toFloat()
                        fun unrotate(p: Offset): Offset {
                            val dx = p.x - center.x
                            val dy = p.y - center.y
                            return Offset(center.x + dx * cosR - dy * sinR, center.y + dx * sinR + dy * cosR)
                        }

                        val unrotDown = unrotate(down.position)
                        val rotHandleUnrot = Offset(center.x, box.top - 28.dp.toPx())

                        val isRotHandle = (unrotDown - rotHandleUnrot).getDistance() <= cornerRadius
                        // Corner handles are pulled inside the page when the box touches its edge, so the
                        // whole touch target can receive events. Take the nearest one in reach.
                        val handleCentres = cornerHandleCentres(box, selected.rotation, size.width.toFloat(), size.height.toFloat(), cornerRadius)
                        val nearestCorner = handleCentres.indices
                            .map { it to (handleCentres[it] - down.position).getDistance() }
                            .filter { it.second <= cornerRadius }
                            .minByOrNull { it.second }
                            ?.first
                        val touchedCorner = if (isRotHandle) -1 else nearestCorner // -1 = ROTATION
                        val cornerPoints = listOf(box.topLeft, box.topRight, box.bottomRight, box.bottomLeft)
                        val isInside = box.inflate(10.dp.toPx()).contains(unrotDown)

                        if (touchedCorner != null || isInside) {
                            down.consume()
                            if (touchedCorner == -1) {
                                // Direct rotation
                                trackDrag(down.id) { pos ->
                                    val angleRad = atan2((pos.y - center.y).toDouble(), (pos.x - center.x).toDouble())
                                    val deg = Math.toDegrees(angleRad) + 90.0
                                    val normalizedDeg = ((deg % 360.0 + 360.0) % 360.0).toFloat()
                                    session.previewEdit(selected.copy(rotation = normalizedDeg))
                                }
                                session.commitEdit(selected)
                                return@awaitEachGesture
                            } else if (touchedCorner != null) {
                                // Corner resizing
                                val minWidth = 24.dp.toPx()
                                val minHeight = 24.dp.toPx()
                                // Relative to where the handle was grabbed, so the box doesn't jump under the finger.
                                val grabShift = unrotDown - cornerPoints[touchedCorner]
                                trackDrag(down.id) { pos ->
                                    val unrotPos = unrotate(pos) - grabShift
                                    val newBox = when (touchedCorner) {
                                        0 -> { // TOP_LEFT
                                            val r = box.right
                                            val btm = box.bottom
                                            Rect(unrotPos.x.coerceAtMost(r - minWidth), unrotPos.y.coerceAtMost(btm - minHeight), r, btm)
                                        }
                                        1 -> { // TOP_RIGHT
                                            val l = box.left
                                            val btm = box.bottom
                                            Rect(l, unrotPos.y.coerceAtMost(btm - minHeight), unrotPos.x.coerceAtLeast(l + minWidth), btm)
                                        }
                                        2 -> { // BOTTOM_RIGHT
                                            val l = box.left
                                            val t = box.top
                                            Rect(l, t, unrotPos.x.coerceAtLeast(l + minWidth), unrotPos.y.coerceAtLeast(t + minHeight))
                                        }
                                        3 -> { // BOTTOM_LEFT
                                            val r = box.right
                                            val t = box.top
                                            Rect(unrotPos.x.coerceAtMost(r - minWidth), t, r, unrotPos.y.coerceAtLeast(t + minHeight))
                                        }
                                        else -> box
                                    }
                                    val newBounds = PdfRect(
                                        (newBox.left / size.width).coerceIn(0f, 1f),
                                        (newBox.top / size.height).coerceIn(0f, 1f),
                                        (newBox.right / size.width).coerceIn(0f, 1f),
                                        (newBox.bottom / size.height).coerceIn(0f, 1f)
                                    )
                                    session.previewEdit(selected.withResizedBounds(newBounds))
                                }
                                session.commitEdit(selected)
                                return@awaitEachGesture
                            } else {
                                // Translation / Move
                                var lastPos = down.position
                                trackDrag(down.id) { pos ->
                                    val delta = pos - lastPos
                                    lastPos = pos
                                    val curBounds = session.selectedAnnotation?.bounds ?: b
                                    val dx = (delta.x / size.width).coerceIn(-curBounds.left, 1f - curBounds.right)
                                    val dy = (delta.y / size.height).coerceIn(-curBounds.top, 1f - curBounds.bottom)
                                    val movedBounds = curBounds.offset(dx, dy)
                                    session.previewEdit(selected.withResizedBounds(movedBounds))
                                }
                                session.commitEdit(selected)
                                return@awaitEachGesture
                            }
                        }

                        // Tapped outside handles: check if tapping another annotation, otherwise deselect
                        val p = norm(down.position)
                        val other = session.forPage(pageIndex)
                            .lastOrNull { it.id != selected.id && it.hitTest(p.x, p.y, tolerance, pageAspect) }
                        if (other != null) {
                            down.consume()
                            session.selectedAnnotationId = other.id
                            return@awaitEachGesture
                        } else if (tool == MarkupTool.NONE) {
                            session.selectedAnnotationId = null
                        }
                    }

                    // Direct tap selection & immediate drag on an existing annotation when in NONE or STAMP mode
                    val pDown = norm(down.position)
                    val tappedOnPage = session.forPage(pageIndex)
                        .lastOrNull { it.hitTest(pDown.x, pDown.y, tolerance, pageAspect) }
                    if (tappedOnPage != null && (tool == MarkupTool.NONE || tool == MarkupTool.STAMP)) {
                        down.consume()
                        session.selectedAnnotationId = tappedOnPage.id
                        session.tool = MarkupTool.NONE
                        if (tappedOnPage.type == AnnotationType.STICKY_NOTE) {
                            session.noteDraft = tappedOnPage
                            return@awaitEachGesture
                        }
                        var lastPos = down.position
                        var moved = false
                        trackDrag(down.id) { pos ->
                            val delta = pos - lastPos
                            if (delta.getDistance() > touchSlop) moved = true
                            lastPos = pos
                            val curBounds = session.selectedAnnotation?.bounds ?: tappedOnPage.bounds
                            val dx = (delta.x / size.width).coerceIn(-curBounds.left, 1f - curBounds.right)
                            val dy = (delta.y / size.height).coerceIn(-curBounds.top, 1f - curBounds.bottom)
                            val movedBounds = curBounds.offset(dx, dy)
                            session.previewEdit(tappedOnPage.withResizedBounds(movedBounds))
                        }
                        if (moved) {
                            session.commitEdit(tappedOnPage)
                        }
                        return@awaitEachGesture
                    }

                    when (tool) {
                        MarkupTool.PEN, MarkupTool.MARKER -> {
                            val points = mutableListOf(norm(down.position))
                            var drawing = false
                            var cancelled = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || event.changes.count { it.pressed } > 1) {
                                    liveStroke = null
                                    cancelled = true
                                    break
                                }
                                if (!change.pressed) {
                                    if (!cancelled && points.isNotEmpty()) {
                                        val currentBrush = if (tool == MarkupTool.MARKER) BrushType.MARKER else session.brushType
                                        val isMarker = currentBrush == BrushType.MARKER
                                        val rawW = if (isMarker) session.markerWidth else session.penWidth
                                        val safeW = if (rawW >= 0.5f) pointsToWidthFraction(rawW) else rawW.coerceIn(0.001f, 0.08f)
                                        val opacity = when (currentBrush) {
                                            BrushType.MARKER -> MarkerOpacity
                                            BrushType.PENCIL -> 0.85f
                                            BrushType.BRUSH -> 0.80f
                                            else -> 1f
                                        }
                                        val finalPoints = if (points.size == 1) {
                                            listOf(points[0], PdfPoint(points[0].x + 0.0001f, points[0].y + 0.0001f))
                                        } else {
                                            points.toList()
                                        }
                                        val annot = finalPoints.toStrokeAnnotation(
                                            pageIndex = pageIndex,
                                            color = if (isMarker) session.markerColor else session.penColor,
                                            width = safeW,
                                            opacity = opacity,
                                            brushType = currentBrush.id
                                        )
                                        session.add(annot)
                                    }
                                    liveStroke = null
                                    break
                                }
                                if (!drawing && (change.position - down.position).getDistance() < touchSlop) continue
                                drawing = true
                                change.consume()
                                val newP = norm(change.position)
                                val lastP = points.last()
                                val dx = (newP.x - lastP.x) * size.width
                                val dy = (newP.y - lastP.y) * size.height
                                if (dx * dx + dy * dy >= 4f) {
                                    points += newP
                                    liveStroke = points.toList()
                                }
                            }
                        }

                        MarkupTool.ERASER -> {
                            eraseAt(norm(down.position))
                            down.consume()
                            trackDrag(down.id) { pos -> eraseAt(norm(pos)) }
                        }

                        MarkupTool.NOTE -> {
                            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                            up.consume()
                            val p = norm(up.position)
                            val existing = session.forPage(pageIndex)
                                .lastOrNull { it.type == AnnotationType.STICKY_NOTE && it.hitTest(p.x, p.y, tolerance, pageAspect) }
                            session.noteDraft = existing ?: PdfAnnotation(
                                pageIndex = pageIndex,
                                type = AnnotationType.STICKY_NOTE,
                                bounds = PdfRect(p.x, p.y, p.x, p.y),
                                colorArgb = NoteColor.argb()
                            )
                        }

                        MarkupTool.TEXT_HIGHLIGHT -> {
                            if (text == null || text.isEmpty) return@awaitEachGesture
                            val start = norm(down.position)
                            val anchor = text.hitTest(start.x, start.y, maxDistance = 0.05f) ?: return@awaitEachGesture
                            var selecting = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || event.changes.count { it.pressed } > 1) {
                                    session.selection = null
                                    break
                                }
                                if (!change.pressed) {
                                    val range = if (selecting) session.selection?.range else text.wordRange(anchor)
                                    if (range != null) {
                                        session.addTextMarkup(pageIndex, text, range, AnnotationType.HIGHLIGHT)
                                        change.consume()
                                    }
                                    session.selection = null
                                    break
                                }
                                if (!selecting && (change.position - down.position).getDistance() < touchSlop) continue
                                selecting = true
                                change.consume()
                                val p = norm(change.position)
                                val focus = text.nearestIndex(p.x, p.y) ?: continue
                                session.selection = TextSelection(pageIndex, anchor, focus)
                            }
                        }

                        MarkupTool.RECTANGLE,
                        MarkupTool.CIRCLE,
                        MarkupTool.TRIANGLE,
                        MarkupTool.STAR,
                        MarkupTool.CLOUD,
                        MarkupTool.CALLOUT,
                        MarkupTool.REDACTION -> {
                            val start = norm(down.position)
                            var dragPos = start
                            var cancelled = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || event.changes.count { it.pressed } > 1) {
                                    // Multi-touch pinch-zoom detected: cancel shape creation immediately
                                    liveShape = null
                                    cancelled = true
                                    break
                                }
                                if (!change.pressed) {
                                    if (!cancelled) {
                                        val r = PdfRect(minOf(start.x, dragPos.x), minOf(start.y, dragPos.y), maxOf(start.x, dragPos.x), maxOf(start.y, dragPos.y))
                                        if (r.width > 0.015f && r.height > 0.015f) {
                                            val type = when (tool) {
                                                MarkupTool.CIRCLE -> AnnotationType.CIRCLE
                                                MarkupTool.TRIANGLE -> AnnotationType.TRIANGLE
                                                MarkupTool.STAR -> AnnotationType.STAR
                                                MarkupTool.CLOUD -> AnnotationType.CLOUD
                                                MarkupTool.CALLOUT -> AnnotationType.CALLOUT
                                                MarkupTool.REDACTION -> AnnotationType.REDACTION
                                                else -> AnnotationType.RECTANGLE
                                            }
                                            val annot = PdfAnnotation(
                                                pageIndex = pageIndex,
                                                type = type,
                                                bounds = r,
                                                colorArgb = if (tool == MarkupTool.REDACTION) Color.Black.argb() else session.shapeColor.argb(),
                                                strokeWidth = session.shapeWidth,
                                                opacity = 1f
                                            )
                                            session.add(annot)
                                        }
                                    }
                                    liveShape = null
                                    break
                                }
                                change.consume()
                                dragPos = norm(change.position)
                                liveShape = tool to PdfRect(minOf(start.x, dragPos.x), minOf(start.y, dragPos.y), maxOf(start.x, dragPos.x), maxOf(start.y, dragPos.y))
                            }
                        }

                        MarkupTool.ARROW,
                        MarkupTool.DOUBLE_ARROW,
                        MarkupTool.LINE -> {
                            val start = norm(down.position)
                            var end = start
                            var cancelled = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || event.changes.count { it.pressed } > 1) {
                                    // Multi-touch pinch-zoom detected: cancel arrow/line immediately
                                    liveLine = null
                                    cancelled = true
                                    break
                                }
                                if (!change.pressed) {
                                    if (!cancelled) {
                                        val dist = hypot(end.x - start.x, end.y - start.y)
                                        if (dist > 0.015f) {
                                            val type = when (tool) {
                                                MarkupTool.ARROW -> AnnotationType.ARROW
                                                MarkupTool.DOUBLE_ARROW -> AnnotationType.DOUBLE_ARROW
                                                else -> AnnotationType.LINE
                                            }
                                            val b = PdfRect(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y))
                                            val annot = PdfAnnotation(
                                                pageIndex = pageIndex,
                                                type = type,
                                                bounds = b,
                                                colorArgb = session.shapeColor.argb(),
                                                strokeWidth = session.shapeWidth,
                                                inkPoints = listOf(listOf(start, end))
                                            )
                                            session.add(annot)
                                        }
                                    }
                                    liveLine = null
                                    break
                                }
                                change.consume()
                                end = norm(change.position)
                                liveLine = tool to listOf(start, end)
                            }
                        }

                        MarkupTool.STAMP -> {
                            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                            up.consume()
                            val p = norm(up.position)
                            val shape = session.selectedStampShape
                            val annot = if (shape != null) {
                                val width = 0.35f
                                val height = (width * pageAspect / shape.aspect).coerceAtMost(0.35f)
                                val fittedWidth = (height * shape.aspect / pageAspect).coerceAtMost(0.5f)
                                val b = PdfRect(
                                    (p.x - fittedWidth / 2f).coerceIn(0f, 1f - fittedWidth),
                                    (p.y - height / 2f).coerceIn(0f, 1f - height),
                                    (p.x + fittedWidth / 2f).coerceIn(fittedWidth, 1f),
                                    (p.y + height / 2f).coerceIn(height, 1f)
                                )
                                PdfAnnotation(
                                    pageIndex = pageIndex,
                                    type = AnnotationType.STAMP,
                                    bounds = b,
                                    text = "STAMP",
                                    colorArgb = session.stampColor.argb(),
                                    strokeWidth = if (shape.filled) 0f else shape.strokeWidth,
                                    inkPoints = shape.contours
                                )
                            } else {
                                val textToUse = session.selectedStampText.ifEmpty { "APPROVED" }
                                val charCount = textToUse.length
                                val stampW = (0.035f * charCount + 0.12f).coerceIn(0.28f, 0.65f)
                                val stampH = 0.08f
                                val b = PdfRect(
                                    (p.x - stampW / 2).coerceIn(0f, 1f - stampW),
                                    (p.y - stampH / 2).coerceIn(0f, 1f - stampH),
                                    (p.x + stampW / 2).coerceIn(stampW, 1f),
                                    (p.y + stampH / 2).coerceIn(stampH, 1f)
                                )
                                PdfAnnotation(
                                    pageIndex = pageIndex,
                                    type = AnnotationType.STAMP,
                                    bounds = b,
                                    text = textToUse,
                                    colorArgb = session.stampColor.argb(),
                                    strokeWidth = 2.5f
                                )
                            }
                            session.add(annot)
                            session.selectedAnnotationId = annot.id
                            session.tool = MarkupTool.NONE
                        }

                        MarkupTool.NONE -> {
                            var timedOut = true
                            val up = withTimeoutOrNull(longPressTimeout) {
                                waitForUpOrCancellation().also { timedOut = false }
                            }
                            if (!timedOut) {
                                if (up == null) return@awaitEachGesture // Scroll or zoom took over.
                                val p = norm(up.position)
                                val tapped = session.forPage(pageIndex)
                                    .lastOrNull { it.hitTest(p.x, p.y, tolerance, pageAspect) }
                                if (tapped != null) {
                                    up.consume()
                                    if (tapped.type == AnnotationType.STICKY_NOTE) {
                                        session.noteDraft = tapped
                                    } else {
                                        session.selection = null
                                        session.selectedAnnotationId = tapped.id
                                    }
                                    return@awaitEachGesture
                                } else {
                                    session.selectedAnnotationId = null
                                    session.selection = null
                                }
                                return@awaitEachGesture
                            }

                            // Long press: select the word under the finger, then drag to extend.
                            val p = norm(down.position)
                            val anchor = text?.hitTest(p.x, p.y) ?: return@awaitEachGesture
                            val word = text.wordRange(anchor)
                            session.selection = TextSelection(pageIndex, word.first, word.last)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            down.consume()
                            trackDrag(down.id) { pos ->
                                val q = norm(pos)
                                val focus = text.nearestIndex(q.x, q.y) ?: return@trackDrag
                                session.selection = TextSelection(
                                    pageIndex,
                                    anchor = if (focus < word.first) word.last else word.first,
                                    focus = focus
                                )
                            }
                        }
                    }
                }
            }
    ) {
        val pageAnnotations = session.annotations.filter { it.pageIndex == pageIndex }
        for (annotation in pageAnnotations) {
            drawAnnotation(annotation, invertColors) { topLeft, side ->
                drawCircle(Color(annotation.colorArgb), radius = side / 2f, center = topLeft + Offset(side / 2f, side / 2f))
                translate(topLeft.x + side * 0.2f, topLeft.y + side * 0.2f) {
                    with(notePainter) {
                        draw(Size(side * 0.6f, side * 0.6f), colorFilter = ColorFilter.tint(Color.White))
                    }
                }
            }
        }

        // Draw selection bounding box, corner scale handles, and rotation knob for selected annotation
        session.selectedAnnotation?.takeIf { it.pageIndex == pageIndex }?.let { selected ->
            val b = selected.bounds
            val box = Rect(b.left * size.width, b.top * size.height, b.right * size.width, b.bottom * size.height)
            val center = box.center

            rotate(degrees = selected.rotation, pivot = center) {
                // Dashed bounding rectangle
                drawRect(
                    selectionColor,
                    box.topLeft,
                    box.size,
                    style = Stroke(1.8.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                )
                // Rotation stalk line and rotation knob
                val stalkTop = Offset(box.center.x, box.top - 28.dp.toPx())
                drawLine(
                    color = selectionColor,
                    start = Offset(box.center.x, box.top),
                    end = stalkTop,
                    strokeWidth = 1.8.dp.toPx()
                )
                drawCircle(Color.White, 12.dp.toPx(), stalkTop)
                drawCircle(selectionColor, 8.5.dp.toPx(), stalkTop)
                drawCircle(Color.White, 4.dp.toPx(), stalkTop)
            }
            run {
                val bb = selected.bounds
                val handleBox = Rect(bb.left * size.width, bb.top * size.height, bb.right * size.width, bb.bottom * size.height)
                cornerHandleCentres(handleBox, selected.rotation, size.width, size.height, 32.dp.toPx()).forEach { c ->
                    drawCircle(Color.White, 11.dp.toPx(), c)
                    drawCircle(selectionColor, 7.5.dp.toPx(), c)
                }
            }
        }

        liveStroke?.let { points ->
            val currentBrush = if (tool == MarkupTool.MARKER) BrushType.MARKER else session.brushType
            val isMarker = currentBrush == BrushType.MARKER
            val rawW = if (isMarker) session.markerWidth else session.penWidth
            val safeW = if (rawW >= 0.5f) pointsToWidthFraction(rawW) else rawW.coerceIn(0.001f, 0.08f)
            val opacity = when (currentBrush) {
                BrushType.MARKER -> MarkerOpacity
                BrushType.PENCIL -> 0.85f
                BrushType.BRUSH -> 0.80f
                else -> 1f
            }
            drawInk(
                points = points,
                color = if (isMarker) session.markerColor else session.penColor,
                width = safeW * size.width,
                opacity = opacity,
                brushType = currentBrush.id,
                invertColors = invertColors
            )
        }

        liveShape?.let { (shapeTool, r) ->
            val rawW = session.shapeWidth
            val safeW = if (rawW >= 0.5f) pointsToWidthFraction(rawW) else rawW.coerceIn(0.001f, 0.08f)
            val strokePx = (safeW * size.width).coerceAtLeast(1.5f)
            val rect = Rect(r.left * size.width, r.top * size.height, r.right * size.width, r.bottom * size.height)
            val type = when (shapeTool) {
                MarkupTool.CIRCLE -> AnnotationType.CIRCLE
                MarkupTool.TRIANGLE -> AnnotationType.TRIANGLE
                MarkupTool.STAR -> AnnotationType.STAR
                MarkupTool.CLOUD -> AnnotationType.CLOUD
                MarkupTool.CALLOUT -> AnnotationType.CALLOUT
                MarkupTool.REDACTION -> AnnotationType.REDACTION
                else -> AnnotationType.RECTANGLE
            }
            if (type == AnnotationType.REDACTION) {
                drawRect(
                    color = Color.Black.copy(alpha = 0.85f),
                    topLeft = rect.topLeft,
                    size = rect.size
                )
            } else {
                drawGeometricShape(type, rect, session.shapeColor, strokePx)
            }
        }

        liveLine?.let { (lineTool, pts) ->
            if (pts.size >= 2) {
                val rawW = session.shapeWidth
                val safeW = if (rawW >= 0.5f) pointsToWidthFraction(rawW) else rawW.coerceIn(0.001f, 0.08f)
                val strokePx = (safeW * size.width).coerceAtLeast(1.5f)
                val start = Offset(pts[0].x * size.width, pts[0].y * size.height)
                val end = Offset(pts[1].x * size.width, pts[1].y * size.height)
                when (lineTool) {
                    MarkupTool.ARROW -> drawArrowPath(start, end, session.shapeColor, strokePx, isDoubleArrow = false)
                    MarkupTool.DOUBLE_ARROW -> drawArrowPath(start, end, session.shapeColor, strokePx, isDoubleArrow = true)
                    else -> drawLine(color = session.shapeColor, start = start, end = end, strokeWidth = strokePx, cap = StrokeCap.Round)
                }
            }
        }

        val selection = session.selection
        val text = pageText
        if (selection != null && selection.pageIndex == pageIndex && text != null) {
            val rects = text.lineRects(selection.range)
            rects.forEach { r ->
                drawRect(
                    color = selectionColor.copy(alpha = 0.28f),
                    topLeft = Offset(r.left * size.width, r.top * size.height),
                    size = Size(r.width * size.width, r.height * size.height)
                )
            }
            // Handles only make sense for a long-press selection, not while dragging a highlight.
            if (tool != MarkupTool.TEXT_HIGHLIGHT && rects.isNotEmpty()) {
                val radius = 8.dp.toPx()
                val first = rects.first()
                val last = rects.last()
                drawSelectionHandle(Offset(first.left * size.width, first.bottom * size.height), radius, selectionColor, left = true)
                drawSelectionHandle(Offset(last.right * size.width, last.bottom * size.height), radius, selectionColor, left = false)
            }
        }
    }
}

fun AnnotationSession.addTextMarkup(pageIndex: Int, text: PageText, range: IntRange, type: AnnotationType) {
    val quads = text.lineRects(range)
    if (quads.isEmpty()) return
    val color = when (type) {
        AnnotationType.UNDERLINE -> UnderlineColor
        AnnotationType.STRIKETHROUGH -> StrikeColor
        else -> highlightColor
    }
    add(
        PdfAnnotation(
            pageIndex = pageIndex,
            type = type,
            bounds = PdfRect(quads.minOf { it.left }, quads.minOf { it.top }, quads.maxOf { it.right }, quads.maxOf { it.bottom }),
            colorArgb = color.argb(),
            quads = quads,
            text = ""
        )
    )
}

/** Follows one pointer until it lifts, consuming its moves. Stops early on multi-touch. */
private suspend fun AwaitPointerEventScope.trackDrag(pointerId: PointerId, onMove: (Offset) -> Unit) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return
        if (!change.pressed) {
            change.consume()
            return
        }
        if (event.changes.count { it.pressed } > 1) return
        change.consume()
        onMove(change.position)
    }
}

/**
 * Screen positions of the four corner handles (top-left, top-right, bottom-right, bottom-left)
 * of a possibly rotated [box]. A handle that would sit on or past the page edge is pulled
 * inside by [radius], so its touch target stays within the page, which is all the markup
 * layer can receive touches on.
 */
private fun cornerHandleCentres(box: Rect, rotationDeg: Float, width: Float, height: Float, radius: Float): List<Offset> {
    val rad = Math.toRadians(rotationDeg.toDouble())
    val cosF = kotlin.math.cos(rad).toFloat()
    val sinF = kotlin.math.sin(rad).toFloat()
    val c = box.center
    return listOf(box.topLeft, box.topRight, box.bottomRight, box.bottomLeft).map { p ->
        val dx = p.x - c.x
        val dy = p.y - c.y
        val x = c.x + dx * cosF - dy * sinF
        val y = c.y + dx * sinF + dy * cosF
        Offset(x.coerceIn(minOf(radius, width / 2f), maxOf(width - radius, width / 2f)), y.coerceIn(minOf(radius, height / 2f), maxOf(height - radius, height / 2f)))
    }
}

private fun handleAt(pos: Offset, rects: List<PdfRect>, w: Float, h: Float, radius: Float): Handle? {
    if (rects.isEmpty()) return null
    // Handles hang below the line, so the touch target is centred a little lower.
    val start = Offset(rects.first().left * w, rects.first().bottom * h + radius / 2)
    val end = Offset(rects.last().right * w, rects.last().bottom * h + radius / 2)
    val dStart = (pos - start).getDistance()
    val dEnd = (pos - end).getDistance()
    return when {
        dEnd <= radius && dEnd <= dStart -> Handle.END
        dStart <= radius -> Handle.START
        else -> null
    }
}

private fun DrawScope.drawSelectionHandle(anchor: Offset, radius: Float, color: Color, left: Boolean) {
    val center = Offset(anchor.x + if (left) -radius else radius, anchor.y + radius)
    drawCircle(color, radius, center)
    // Square corner pointing at the text, like the platform selection handles.
    drawRect(
        color,
        topLeft = Offset(if (left) center.x else anchor.x, anchor.y),
        size = Size(radius, radius)
    )
}

fun DrawScope.drawArrowPath(
    start: Offset,
    end: Offset,
    color: Color,
    strokeWidthPx: Float,
    isDoubleArrow: Boolean = false
) {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val dist = hypot(dx, dy)
    if (dist < 4f) return

    val ux = dx / dist
    val uy = dy / dist

    // Dynamic, well-proportioned solid arrowhead:
    val headLen = (strokeWidthPx * 3.6f).coerceIn(14f, 70f).coerceAtMost(dist * 0.45f)
    val headWidth = (headLen * 0.95f).coerceIn(12f, 60f)

    // Calculate where shaft starts and ends so it never pokes through the arrowhead
    val shaftEnd = Offset(end.x - ux * (headLen * 0.75f), end.y - uy * (headLen * 0.75f))
    val shaftStart = if (isDoubleArrow) {
        Offset(start.x + ux * (headLen * 0.75f), start.y + uy * (headLen * 0.75f))
    } else {
        start
    }

    // Draw the main shaft line
    drawLine(
        color = color,
        start = shaftStart,
        end = shaftEnd,
        strokeWidth = strokeWidthPx,
        cap = StrokeCap.Round
    )

    // Draw solid filled arrowhead
    fun drawHead(tip: Offset, dirX: Float, dirY: Float) {
        val normX = -dirY
        val normY = dirX
        val baseCenter = Offset(tip.x - dirX * headLen, tip.y - dirY * headLen)
        val p1 = Offset(baseCenter.x + normX * (headWidth / 2f), baseCenter.y + normY * (headWidth / 2f))
        val p2 = Offset(baseCenter.x - normX * (headWidth / 2f), baseCenter.y - normY * (headWidth / 2f))

        val path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            close()
        }
        drawPath(path, color)
    }

    drawHead(end, ux, uy)
    if (isDoubleArrow) {
        drawHead(start, -ux, -uy)
    }
}

fun createCloudPath(rect: Rect): Path {
    val path = Path()
    val arcCountX = (rect.width / 24f).toInt().coerceIn(3, 12)
    val arcCountY = (rect.height / 24f).toInt().coerceIn(2, 10)
    val stepX = rect.width / arcCountX
    val stepY = rect.height / arcCountY

    path.moveTo(rect.left, rect.top)
    for (i in 0 until arcCountX) {
        val x1 = rect.left + i * stepX
        val x2 = x1 + stepX
        path.quadraticTo((x1 + x2) / 2f, rect.top - stepX * 0.35f, x2, rect.top)
    }
    for (i in 0 until arcCountY) {
        val y1 = rect.top + i * stepY
        val y2 = y1 + stepY
        path.quadraticTo(rect.right + stepY * 0.35f, (y1 + y2) / 2f, rect.right, y2)
    }
    for (i in 0 until arcCountX) {
        val x1 = rect.right - i * stepX
        val x2 = x1 - stepX
        path.quadraticTo((x1 + x2) / 2f, rect.bottom + stepX * 0.35f, x2, rect.bottom)
    }
    for (i in 0 until arcCountY) {
        val y1 = rect.bottom - i * stepY
        val y2 = y1 - stepY
        path.quadraticTo(rect.left - stepY * 0.35f, (y1 + y2) / 2f, rect.left, y2)
    }
    path.close()
    return path
}

fun createCalloutPath(rect: Rect): Path {
    val path = Path()
    val cr = 8f
    val bubbleBottom = rect.bottom - (rect.height * 0.22f).coerceIn(12f, 28f)
    val tailWidth = (rect.width * 0.18f).coerceIn(12f, 24f)
    val tailStartX = rect.left + rect.width * 0.25f

    path.moveTo(rect.left + cr, rect.top)
    path.lineTo(rect.right - cr, rect.top)
    path.quadraticTo(rect.right, rect.top, rect.right, rect.top + cr)
    path.lineTo(rect.right, bubbleBottom - cr)
    path.quadraticTo(rect.right, bubbleBottom, rect.right - cr, bubbleBottom)
    path.lineTo(tailStartX + tailWidth, bubbleBottom)
    path.lineTo(rect.left + rect.width * 0.15f, rect.bottom)
    path.lineTo(tailStartX, bubbleBottom)
    path.lineTo(rect.left + cr, bubbleBottom)
    path.quadraticTo(rect.left, bubbleBottom, rect.left, bubbleBottom - cr)
    path.lineTo(rect.left, rect.top + cr)
    path.quadraticTo(rect.left, rect.top, rect.left + cr, rect.top)
    path.close()
    return path
}

fun DrawScope.drawGeometricShape(
    type: AnnotationType,
    rect: Rect,
    color: Color,
    strokeWidthPx: Float
) {
    val style = Stroke(width = strokeWidthPx, join = StrokeJoin.Round, cap = StrokeCap.Round)
    when (type) {
        AnnotationType.RECTANGLE -> {
            drawRect(color = color, topLeft = rect.topLeft, size = rect.size, style = style)
        }
        AnnotationType.CIRCLE -> {
            drawOval(color = color, topLeft = rect.topLeft, size = rect.size, style = style)
        }
        AnnotationType.TRIANGLE -> {
            val path = Path().apply {
                moveTo(rect.center.x, rect.top)
                lineTo(rect.right, rect.bottom)
                lineTo(rect.left, rect.bottom)
                close()
            }
            drawPath(path, color, style = style)
        }
        AnnotationType.STAR -> {
            val cx = rect.center.x
            val cy = rect.center.y
            val rOuter = minOf(rect.width, rect.height) / 2f
            val rInner = rOuter * 0.382f
            val path = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) rOuter else rInner
                val angle = Math.toRadians((i * 36 - 90).toDouble())
                val x = (cx + r * kotlin.math.cos(angle)).toFloat()
                val y = (cy + r * kotlin.math.sin(angle)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color, style = style)
        }
        AnnotationType.CLOUD -> {
            val path = createCloudPath(rect)
            drawPath(path, color, style = style)
        }
        AnnotationType.CALLOUT -> {
            val path = createCalloutPath(rect)
            drawPath(path, color, style = style)
        }
        AnnotationType.REDACTION -> {
            drawRect(color = Color.Black, topLeft = rect.topLeft, size = rect.size)
        }
        else -> Unit
    }
}

private fun DrawScope.drawAnnotation(
    annotation: PdfAnnotation,
    invertColors: Boolean,
    drawNote: DrawScope.(Offset, Float) -> Unit
) {
    val w = size.width
    val h = size.height
    val color = Color(annotation.colorArgb)
    val b = annotation.bounds
    val rect = Rect(b.left * w, b.top * h, b.right * w, b.bottom * h)

    rotate(degrees = annotation.rotation, pivot = rect.center) {
        when (annotation.type) {
            AnnotationType.HIGHLIGHT -> annotation.quads.forEach { r ->
                drawRoundRect(
                    color = if (invertColors) color.copy(alpha = 0.45f) else color,
                    topLeft = Offset(r.left * w, r.top * h),
                    size = Size(r.width * w, r.height * h),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    blendMode = if (invertColors) BlendMode.SrcOver else BlendMode.Multiply
                )
            }
            AnnotationType.UNDERLINE, AnnotationType.STRIKETHROUGH -> annotation.quads.forEach { r ->
                val thickness = (r.height * h * 0.08f).coerceAtLeast(1.5f)
                val y = if (annotation.type == AnnotationType.UNDERLINE) {
                    r.bottom * h - thickness
                } else {
                    (r.top + r.height * 0.55f) * h
                }
                drawLine(color, Offset(r.left * w, y), Offset(r.right * w, y), strokeWidth = thickness)
            }
            AnnotationType.INK_PEN -> annotation.inkPoints.forEach { stroke ->
                val rawW = annotation.strokeWidth
                val safeW = if (rawW >= 0.5f) pointsToWidthFraction(rawW) else rawW.coerceIn(0.001f, 0.08f)
                drawInk(stroke, color, safeW * w, annotation.opacity, annotation.brushType, invertColors)
            }
            AnnotationType.SIGNATURE -> {
                drawSignature(
                    contours = annotation.inkPoints,
                    filled = annotation.strokeWidth == 0f,
                    strokeWidth = annotation.strokeWidth,
                    box = rect,
                    color = if (invertColors) Color(1f - color.red, 1f - color.green, 1f - color.blue) else color
                )
            }
            AnnotationType.STICKY_NOTE -> {
                val side = 26.dp.toPx()
                drawNote(Offset(rect.left - side / 2, rect.top - side / 2), side)
            }
            AnnotationType.RECTANGLE,
            AnnotationType.CIRCLE,
            AnnotationType.TRIANGLE,
            AnnotationType.STAR,
            AnnotationType.CLOUD,
            AnnotationType.CALLOUT -> {
                val rawW = annotation.strokeWidth
                val safeW = if (rawW >= 0.5f) pointsToWidthFraction(rawW) else rawW.coerceIn(0.001f, 0.08f)
                val strokePx = (safeW * w).coerceAtLeast(1.5f)
                drawGeometricShape(annotation.type, rect, color, strokePx)
            }
            AnnotationType.LINE -> {
                val rawW = annotation.strokeWidth
                val safeW = if (rawW >= 0.5f) pointsToWidthFraction(rawW) else rawW.coerceIn(0.001f, 0.08f)
                val strokePx = (safeW * w).coerceAtLeast(1.5f)
                val stroke = annotation.inkPoints.firstOrNull()
                if (stroke != null && stroke.size >= 2) {
                    drawLine(
                        color = color,
                        start = Offset(stroke[0].x * w, stroke[0].y * h),
                        end = Offset(stroke[1].x * w, stroke[1].y * h),
                        strokeWidth = strokePx,
                        cap = StrokeCap.Round
                    )
                }
            }
            AnnotationType.ARROW -> {
                val rawW = annotation.strokeWidth
                val safeW = if (rawW >= 0.5f) pointsToWidthFraction(rawW) else rawW.coerceIn(0.001f, 0.08f)
                val strokePx = (safeW * w).coerceAtLeast(1.5f)
                val stroke = annotation.inkPoints.firstOrNull()
                if (stroke != null && stroke.size >= 2) {
                    drawArrowPath(
                        start = Offset(stroke[0].x * w, stroke[0].y * h),
                        end = Offset(stroke[1].x * w, stroke[1].y * h),
                        color = color,
                        strokeWidthPx = strokePx,
                        isDoubleArrow = false
                    )
                }
            }
            AnnotationType.DOUBLE_ARROW -> {
                val rawW = annotation.strokeWidth
                val safeW = if (rawW >= 0.5f) pointsToWidthFraction(rawW) else rawW.coerceIn(0.001f, 0.08f)
                val strokePx = (safeW * w).coerceAtLeast(1.5f)
                val stroke = annotation.inkPoints.firstOrNull()
                if (stroke != null && stroke.size >= 2) {
                    drawArrowPath(
                        start = Offset(stroke[0].x * w, stroke[0].y * h),
                        end = Offset(stroke[1].x * w, stroke[1].y * h),
                        color = color,
                        strokeWidthPx = strokePx,
                        isDoubleArrow = true
                    )
                }
            }
            AnnotationType.STAMP -> {
                if (annotation.inkPoints.isNotEmpty()) {
                    drawSignature(
                        contours = annotation.inkPoints,
                        filled = annotation.strokeWidth == 0f,
                        strokeWidth = annotation.strokeWidth,
                        box = rect,
                        color = if (invertColors) Color(1f - color.red, 1f - color.green, 1f - color.blue) else color
                    )
                } else {
                    val strokePx = (rect.height * 0.055f).coerceIn(1.5f, 6.dp.toPx())
                    val outerRadius = (rect.height * 0.12f).coerceIn(2.dp.toPx(), 12.dp.toPx())
                    drawRoundRect(
                        color = color,
                        topLeft = rect.topLeft,
                        size = rect.size,
                        cornerRadius = CornerRadius(outerRadius),
                        style = Stroke(width = strokePx)
                    )
                    val innerMargin = (rect.height * 0.08f).coerceIn(2.dp.toPx(), 8.dp.toPx())
                    val innerRect = rect.deflate(innerMargin)
                    if (innerRect.width > 0 && innerRect.height > 0) {
                        val innerStroke = (strokePx * 0.45f).coerceAtLeast(1.dp.toPx())
                        drawRoundRect(
                            color = color.copy(alpha = 0.5f),
                            topLeft = innerRect.topLeft,
                            size = innerRect.size,
                            cornerRadius = CornerRadius((outerRadius - innerMargin).coerceAtLeast(1.dp.toPx())),
                            style = Stroke(width = innerStroke)
                        )
                    }
                    val textToDraw = annotation.text.ifEmpty { "APPROVED" }
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            textSize = (rect.height * 0.45f).coerceAtLeast(10f)
                            isFakeBoldText = true
                            setColor(annotation.colorArgb)
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        val textWidth = paint.measureText(textToDraw)
                        val maxWidth = (rect.width - innerMargin * 4f).coerceAtLeast(10f)
                        if (textWidth > maxWidth && textWidth > 0f) {
                            paint.textSize = (paint.textSize * (maxWidth / textWidth)).coerceAtLeast(8f)
                        }
                        val textY = rect.center.y - (paint.descent() + paint.ascent()) / 2
                        drawText(textToDraw, rect.center.x, textY, paint)
                    }
                }
            }
            AnnotationType.REDACTION -> {
                drawRect(
                    color = Color.Black,
                    topLeft = rect.topLeft,
                    size = rect.size
                )
            }
            else -> Unit
        }
    }
}

private fun DrawScope.drawInk(
    points: List<PdfPoint>,
    color: Color,
    width: Float,
    opacity: Float,
    brushType: String,
    invertColors: Boolean
) {
    if (points.isEmpty()) return
    val w = size.width
    val h = size.height

    if (points.size == 1) {
        val center = Offset(points[0].x * w, points[0].y * h)
        val radius = (width / 2f).coerceAtLeast(1.5f)
        when (brushType) {
            "marker" -> drawRect(
                color = color,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                alpha = if (!invertColors) 1f else opacity,
                blendMode = if (!invertColors) BlendMode.Multiply else BlendMode.SrcOver
            )
            "pencil" -> {
                drawCircle(color, radius = radius * 0.9f, center = center, alpha = opacity * 0.65f)
                drawCircle(color, radius = radius * 0.45f, center = center, alpha = opacity * 0.9f)
            }
            "brush" -> {
                drawCircle(color, radius = radius * 1.4f, center = center, alpha = opacity * 0.25f)
                drawCircle(color, radius = radius * 0.8f, center = center, alpha = opacity * 0.85f)
            }
            else -> drawCircle(
                color = color,
                radius = radius,
                center = center,
                alpha = opacity
            )
        }
        return
    }

    val path = Path().apply {
        moveTo(points[0].x * w, points[0].y * h)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            quadraticTo(
                prev.x * w, prev.y * h,
                (prev.x + cur.x) / 2f * w, (prev.y + cur.y) / 2f * h
            )
        }
        lineTo(points.last().x * w, points.last().y * h)
    }

    when (brushType) {
        "marker" -> {
            drawPath(
                path = path,
                color = color,
                alpha = if (!invertColors) 1f else opacity,
                style = Stroke(width = width, cap = StrokeCap.Square, join = StrokeJoin.Round),
                blendMode = if (!invertColors) BlendMode.Multiply else BlendMode.SrcOver
            )
        }
        "pencil" -> {
            // Graphite texture: soft base line + rough textured dash pass simulating paper tooth
            drawPath(
                path = path,
                color = color,
                alpha = opacity * 0.60f,
                style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            val dashPattern = floatArrayOf(width * 0.8f + 2.5f, 2f, width * 1.5f + 3f, 3f)
            drawPath(
                path = path,
                color = color,
                alpha = opacity * 0.50f,
                style = Stroke(
                    width = (width * 0.82f).coerceAtLeast(1.2f),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(dashPattern, 0f)
                )
            )
        }
        "brush" -> {
            // Painterly watercolor/ink wash: soft translucent outer feather + rich body + solid core
            drawPath(
                path = path,
                color = color,
                alpha = opacity * 0.18f,
                style = Stroke(width = width * 1.55f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = path,
                color = color,
                alpha = opacity * 0.38f,
                style = Stroke(width = width * 1.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = path,
                color = color,
                alpha = opacity * 0.82f,
                style = Stroke(width = width * 0.70f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        "fountain" -> {
            // Chiseled 45-degree calligraphy nib: dynamic ribbon thinning/thickening with direction
            val nibLen = width * 0.85f
            val rad45 = Math.toRadians(45.0)
            val nx = (cos(rad45) * (nibLen / 2f)).toFloat()
            val ny = (sin(rad45) * (nibLen / 2f)).toFloat()

            val ribbon = Path()
            val leftPoints = mutableListOf<Offset>()
            val rightPoints = mutableListOf<Offset>()
            for (p in points) {
                val px = p.x * w
                val py = p.y * h
                leftPoints += Offset(px - nx, py - ny)
                rightPoints += Offset(px + nx, py + ny)
            }
            if (leftPoints.isNotEmpty()) {
                ribbon.moveTo(leftPoints[0].x, leftPoints[0].y)
                for (i in 1 until leftPoints.size) {
                    val prev = leftPoints[i - 1]
                    val cur = leftPoints[i]
                    ribbon.quadraticTo(prev.x, prev.y, (prev.x + cur.x) / 2f, (prev.y + cur.y) / 2f)
                }
                ribbon.lineTo(leftPoints.last().x, leftPoints.last().y)
                ribbon.lineTo(rightPoints.last().x, rightPoints.last().y)
                for (i in rightPoints.size - 2 downTo 0) {
                    val prev = rightPoints[i + 1]
                    val cur = rightPoints[i]
                    ribbon.quadraticTo(prev.x, prev.y, (prev.x + cur.x) / 2f, (prev.y + cur.y) / 2f)
                }
                ribbon.lineTo(rightPoints[0].x, rightPoints[0].y)
                ribbon.close()
                drawPath(path = ribbon, color = color, alpha = opacity)
            }
            // Fine spine to ensure complete continuity
            drawPath(
                path = path,
                color = color,
                alpha = opacity * 0.70f,
                style = Stroke(width = (width * 0.25f).coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        else -> {
            // Default "ballpoint" / pen
            drawPath(
                path = path,
                color = color,
                alpha = opacity,
                style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}
