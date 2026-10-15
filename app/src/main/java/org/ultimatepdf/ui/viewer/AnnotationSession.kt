package org.ultimatepdf.ui.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.ultimatepdf.R
import org.ultimatepdf.core.model.AnnotationType
import org.ultimatepdf.core.model.PdfAnnotation
import org.ultimatepdf.core.model.PdfPoint
import org.ultimatepdf.core.model.PdfRect
import kotlin.math.hypot

/** Tools of the edit-mode bottom toolbar. [NONE] means scroll / select text. */
enum class MarkupTool {
    NONE,
    TEXT_HIGHLIGHT,
    PEN,
    MARKER,
    NOTE,
    RECTANGLE,
    CIRCLE,
    TRIANGLE,
    STAR,
    CLOUD,
    CALLOUT,
    DOUBLE_ARROW,
    ARROW,
    LINE,
    STAMP,
    REDACTION,
    ERASER
}

/** Writing and drawing brush types */
enum class BrushType(val id: String, val labelRes: Int) {
    BALLPOINT("ballpoint", R.string.brush_pen),
    PENCIL("pencil", R.string.brush_pencil),
    FOUNTAIN("fountain", R.string.brush_fountain),
    BRUSH("brush", R.string.brush_brush),
    MARKER("marker", R.string.brush_marker)
}

/** Predefined rubber stamp resource IDs */
val PredefinedStampResIds = listOf(
    R.string.stamp_approved,
    R.string.stamp_confidential,
    R.string.stamp_copy,
    R.string.stamp_draft,
    R.string.stamp_rejected,
    R.string.stamp_paid
)

/** A text selection inside one page, as glyph indices into that page's [org.ultimatepdf.core.pdf.PageText]. */
data class TextSelection(val pageIndex: Int, val anchor: Int, val focus: Int) {
    val range: IntRange get() = minOf(anchor, focus)..maxOf(anchor, focus)
}

val HighlightColors = listOf(
    Color(0xFFFFE066), Color(0xFF8CE99A), Color(0xFF74C0FC), Color(0xFFFFA8D5),
    Color(0xFFFFB86B), Color(0xFFD0BFFF), Color(0xFFFFC9C9), Color(0xFFA5D8FF)
)

val DocumentColors = listOf(
    Color(0xFF111111), // Carbon black
    Color(0xFF495057), // Slate grey
    Color(0xFFE03131), // Vivid red
    Color(0xFFC92A2A), // Stamp crimson
    Color(0xFFF08C00), // Warm orange
    Color(0xFFFAB005), // Amber gold
    Color(0xFF2F9E44), // Clover green
    Color(0xFF0CA678), // Emerald green
    Color(0xFF15AABF), // Turquoise / Cyan
    Color(0xFF1971C2), // Royal blue
    Color(0xFF364FC7), // Indigo
    Color(0xFF7048E8), // Purple
    Color(0xFFD6336C), // Magenta
    Color(0xFFFFFFFF)  // White
)

val StampColors = listOf(
    Color(0xFFC92A2A), // Classic stamp red
    Color(0xFF1971C2), // Royal blue
    Color(0xFF2F9E44), // Verified green
    Color(0xFF111111), // Carbon black
    Color(0xFF7048E8), // Purple
    Color(0xFFE8590C)  // Dark orange
)

val SignatureColors = listOf(
    Color(0xFF111111), Color(0xFF1C3FAA), Color(0xFF0B1F4D), Color(0xFFC92A2A), Color(0xFF2F9E44)
)

val PenColors = DocumentColors

/**
 * Stroke width conversion utilities:
 * Standard PDF reference page width is 600 points (standard A4/Letter size).
 * [PdfAnnotation.strokeWidth] is normalized to 0..1 fraction of page width.
 * 1 pt = 1 / 600 ≈ 0.00167f.
 */
const val StandardPdfPageWidthPt = 600f

fun pointsToWidthFraction(pt: Float): Float = (pt / StandardPdfPageWidthPt).coerceIn(0.001f, 0.08f)

fun widthFractionToPoints(fraction: Float): Float {
    return if (fraction >= 0.5f) fraction.coerceIn(1f, 40f)
    else (fraction * StandardPdfPageWidthPt).coerceIn(1f, 40f)
}

val PenWidthPresetsPt = listOf(1.5f, 3f, 5f, 8f, 14f)
val PenWidths = PenWidthPresetsPt.map { pointsToWidthFraction(it) }

val MarkerWidthPresetsPt = listOf(10f, 18f, 28f, 40f)
val MarkerWidths = MarkerWidthPresetsPt.map { pointsToWidthFraction(it) }

/**
 * The editable annotations of the open document (those read from the file plus new ones),
 * the tool settings, and an undo / redo history of additions and deletions.
 */
class AnnotationSession {

    private sealed interface Change {
        data class Added(val annotation: PdfAnnotation) : Change
        data class Removed(val annotation: PdfAnnotation, val index: Int) : Change
        data class Replaced(val before: PdfAnnotation, val after: PdfAnnotation) : Change
    }

    val annotations = mutableStateListOf<PdfAnnotation>()
    private val undoStack = mutableStateListOf<Change>()
    private val redoStack = mutableStateListOf<Change>()

    var tool by mutableStateOf(MarkupTool.NONE)
    var selection by mutableStateOf<TextSelection?>(null)

    var highlightColor by mutableStateOf(HighlightColors[0])
    /** Pen or marker, whichever the Draw button used last. */
    var lastDrawTool by mutableStateOf(MarkupTool.PEN)
    var penColor by mutableStateOf(PenColors[0])
    var markerColor by mutableStateOf(HighlightColors[0])
    var penWidth by mutableStateOf(PenWidths[1])
    var markerWidth by mutableStateOf(MarkerWidths[1])
    var brushType by mutableStateOf(BrushType.BALLPOINT)

    /** Selected predefined or custom stamp text */
    var selectedStampText by mutableStateOf("")

    /** Selected vector stamp shape to place (null if placing a text stamp) */
    var selectedStampShape by mutableStateOf<org.ultimatepdf.core.model.SignatureShape?>(null)

    /** Color for stamps */
    var stampColor by mutableStateOf(StampColors[0])

    var shapeColor by mutableStateOf(PenColors[2])
    var shapeWidth by mutableStateOf(PenWidths[1])

    /** Note being created or edited; drives the note dialog. */
    var noteDraft by mutableStateOf<PdfAnnotation?>(null)

    /** Placed annotation currently selected for moving, resizing, rotating or recolouring. */
    var selectedAnnotationId by mutableStateOf<String?>(null)

    val selectedAnnotation: PdfAnnotation?
        get() = selectedAnnotationId?.let { id -> annotations.firstOrNull { it.id == id } }

    /** Backward-compatible alias for signatures */
    var selectedSignatureId: String?
        get() = selectedAnnotationId
        set(value) { selectedAnnotationId = value }

    val selectedSignature: PdfAnnotation?
        get() = selectedAnnotation

    var signatureColor by mutableStateOf(SignatureColors[0])

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    /** Undo depth matching the file's content; -1 once that state can't be reached again. */
    private var savedDepth by mutableStateOf(0)

    val hasUnsavedChanges: Boolean get() = undoStack.size != savedDepth

    fun forPage(pageIndex: Int): List<PdfAnnotation> = annotations.filter { it.pageIndex == pageIndex }

    fun add(annotation: PdfAnnotation) {
        annotations += annotation
        record(Change.Added(annotation))
    }

    fun remove(annotation: PdfAnnotation) {
        val index = annotations.indexOfFirst { it.id == annotation.id }
        if (index < 0) return
        if (selectedAnnotationId == annotation.id) selectedAnnotationId = null
        annotations.removeAt(index)
        record(Change.Removed(annotation, index))
    }

    /** Replaces an annotation in place (note text edits); undoable as remove + add. */
    fun replace(old: PdfAnnotation, new: PdfAnnotation) {
        remove(old)
        add(new)
    }

    /**
     * Shows [preview] in place of the annotation with the same id without recording history,
     * for live dragging; finish with [commitEdit].
     */
    fun previewEdit(preview: PdfAnnotation) {
        val index = annotations.indexOfFirst { it.id == preview.id }
        if (index >= 0) annotations[index] = preview
    }

    /** Records one undoable step from [before] to the annotation's current state. */
    fun commitEdit(before: PdfAnnotation) {
        val after = annotations.firstOrNull { it.id == before.id } ?: return
        if (after != before) record(Change.Replaced(before, after))
    }

    /** Undoable in-place change that keeps the id (e.g. recolouring an annotation). */
    fun update(new: PdfAnnotation) {
        val before = annotations.firstOrNull { it.id == new.id } ?: return
        previewEdit(new)
        commitEdit(before)
    }

    fun undo() {
        val change = undoStack.removeLastOrNull() ?: return
        revert(change)
        redoStack += change
    }

    fun redo() {
        val change = redoStack.removeLastOrNull() ?: return
        apply(change)
        undoStack += change
    }

    /** Starts a fresh session from the annotations stored in the (re)opened file. */
    fun load(fromFile: List<PdfAnnotation>) {
        annotations.clear()
        annotations.addAll(fromFile)
        savedDepth = 0
        undoStack.clear()
        redoStack.clear()
        selection = null
        noteDraft = null
        selectedAnnotationId = null
    }

    fun discard() = load(emptyList())

    private fun record(change: Change) {
        if (undoStack.size < savedDepth) savedDepth = -1
        undoStack += change
        redoStack.clear()
    }

    private fun apply(change: Change) {
        when (change) {
            is Change.Added -> annotations += change.annotation
            is Change.Removed -> annotations.removeAll { it.id == change.annotation.id }
            is Change.Replaced -> previewEdit(change.after)
        }
    }

    private fun revert(change: Change) {
        when (change) {
            is Change.Added -> annotations.removeAll { it.id == change.annotation.id }
            is Change.Removed -> annotations.add(change.index.coerceAtMost(annotations.size), change.annotation)
            is Change.Replaced -> previewEdit(change.before)
        }
    }
}

fun Color.argb(): Int = toArgb()

/** Proportionally scales an annotation to [newBounds], updating ink contours and vertices. */
fun PdfAnnotation.withResizedBounds(newBounds: PdfRect): PdfAnnotation {
    if (bounds.width <= 0.0001f || bounds.height <= 0.0001f) return copy(bounds = newBounds)
    return when (type) {
        AnnotationType.SIGNATURE -> copy(bounds = newBounds)
        AnnotationType.INK_PEN,
        AnnotationType.ARROW,
        AnnotationType.DOUBLE_ARROW,
        AnnotationType.LINE -> {
            val oldW = bounds.width.coerceAtLeast(0.0001f)
            val oldH = bounds.height.coerceAtLeast(0.0001f)
            val newPoints = inkPoints.map { stroke ->
                stroke.map { p ->
                    val nx = (p.x - bounds.left) / oldW
                    val ny = (p.y - bounds.top) / oldH
                    PdfPoint(
                        (newBounds.left + nx * newBounds.width).coerceIn(0f, 1f),
                        (newBounds.top + ny * newBounds.height).coerceIn(0f, 1f)
                    )
                }
            }
            copy(bounds = newBounds, inkPoints = newPoints)
        }
        else -> copy(bounds = newBounds)
    }
}

/** Whether a page-normalized point hits [annotation]; [tolerance] is in normalized units. */
fun PdfAnnotation.hitTest(x: Float, y: Float, tolerance: Float, pageAspect: Float): Boolean {
    // Un-rotate touch point if annotation is rotated:
    val testX: Float
    val testY: Float
    if (rotation != 0f) {
        val cx = bounds.centerX
        val cy = bounds.centerY
        val rad = Math.toRadians(-rotation.toDouble())
        val dx = x - cx
        val dy = (y - cy) / pageAspect
        val cosR = kotlin.math.cos(rad)
        val sinR = kotlin.math.sin(rad)
        testX = (dx * cosR - dy * sinR).toFloat() + cx
        testY = (dx * sinR + dy * cosR).toFloat() * pageAspect + cy
    } else {
        testX = x
        testY = y
    }

    fun inRect(r: PdfRect, pad: Float) =
        testX >= r.left - pad && testX <= r.right + pad && testY >= r.top - pad && testY <= r.bottom + pad

    return when {
        type == AnnotationType.SIGNATURE ||
        type == AnnotationType.RECTANGLE ||
        type == AnnotationType.CIRCLE ||
        type == AnnotationType.TRIANGLE ||
        type == AnnotationType.STAR ||
        type == AnnotationType.CLOUD ||
        type == AnnotationType.CALLOUT ||
        type == AnnotationType.STAMP ||
        type == AnnotationType.REDACTION -> inRect(bounds, tolerance)
        type == AnnotationType.ARROW ||
        type == AnnotationType.DOUBLE_ARROW ||
        type == AnnotationType.LINE -> inRect(bounds, tolerance) || inkPoints.any { stroke ->
            stroke.any { p ->
                hypot(p.x - testX, (p.y - testY) / pageAspect) <= tolerance + strokeWidth / 2
            }
        }
        quads.isNotEmpty() -> quads.any { inRect(it, tolerance / 2) }
        inkPoints.isNotEmpty() -> inRect(bounds, tolerance) || inkPoints.any { stroke ->
            stroke.any { p ->
                hypot(p.x - testX, (p.y - testY) / pageAspect) <= tolerance + strokeWidth / 2
            }
        }
        else -> hypot(bounds.left - testX, (bounds.top - testY) / pageAspect) <= tolerance * 1.5f
    }
}

fun List<PdfPoint>.toStrokeAnnotation(
    pageIndex: Int,
    color: Color,
    width: Float,
    opacity: Float,
    brushType: String = "ballpoint"
): PdfAnnotation {
    val xs = map { it.x }
    val ys = map { it.y }
    val safeWidth = if (width >= 0.5f) pointsToWidthFraction(width) else width.coerceIn(0.001f, 0.08f)
    return PdfAnnotation(
        pageIndex = pageIndex,
        type = org.ultimatepdf.core.model.AnnotationType.INK_PEN,
        bounds = PdfRect(xs.min(), ys.min(), xs.max(), ys.max()),
        colorArgb = color.argb(),
        strokeWidth = safeWidth,
        opacity = opacity,
        inkPoints = listOf(this),
        brushType = brushType
    )
}

