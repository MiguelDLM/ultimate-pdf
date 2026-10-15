package org.ultimatepdf.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ultimatepdf.R
import org.ultimatepdf.core.model.DocumentModel
import org.ultimatepdf.core.model.PdfRect
import org.ultimatepdf.core.pdf.FontFamilyKind
import org.ultimatepdf.core.pdf.PageContent
import org.ultimatepdf.core.pdf.PdfContentEditor
import org.ultimatepdf.core.pdf.PdfThumbnails
import org.ultimatepdf.core.pdf.TextAlignment
import org.ultimatepdf.core.pdf.TextBox
import org.ultimatepdf.core.pdf.TextBoxSpec
import org.ultimatepdf.core.pdf.TextLine
import org.ultimatepdf.core.pdf.TextStyle
import org.ultimatepdf.core.signature.SignatureImages
import org.ultimatepdf.ui.components.ScreenTopBar
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class EditMode { EDIT, ADD_TEXT, IMAGES }

/** The object whose handles are showing. */
private sealed interface Selected {
    data class Image(val id: Int) : Selected
    data class Box(val id: Int) : Selected
}

/** What the text dialog is for: rewriting a [line], re-editing a [box], or creating one at ([nx], [ny]). */
private data class TextDraft(val line: TextLine?, val box: TextBox?, val nx: Float, val ny: Float)

private val TextColors = listOf(
    0xFF000000.toInt(), 0xFF1E4DB7.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt(), 0xFFEF6C00.toInt(), 0xFFFFFFFF.toInt()
)

/**
 * Edits the text and images of the open document, page by page. Every change produces a new
 * version of a working copy (so undo is just going back one), and "Save" replaces the document.
 *
 * Text written here lives in text boxes that stay editable: select one to move it, drag its
 * corners to resize it, or open it again to change the text, style and alignment.
 */
@Composable
fun ContentEditorScreen(
    currentDocument: DocumentModel?,
    editor: PdfContentEditor,
    thumbnails: PdfThumbnails,
    onSave: suspend (File) -> Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workDir = remember { File(context.cacheDir, "content_edit_${System.currentTimeMillis()}").apply { mkdirs() } }
    val versions = remember { mutableStateListOf<File>() }
    var counter by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(currentDocument?.pageCount ?: 0) }
    var mode by remember { mutableStateOf(EditMode.EDIT) }
    var content by remember { mutableStateOf<PageContent?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selected by remember { mutableStateOf<Selected?>(null) }
    // Selection to restore once the next version of the page has been scanned.
    var pending by remember { mutableStateOf<(PageContent) -> Selected?>({ null }) }
    var draft by remember { mutableStateOf<TextDraft?>(null) }
    var objectDraft by remember { mutableStateOf<PdfRect?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val current = versions.lastOrNull()
    val modified = versions.size > 1

    DisposableEffect(Unit) { onDispose { workDir.deleteRecursively() } }

    LaunchedEffect(currentDocument?.uri) {
        val doc = currentDocument ?: return@LaunchedEffect
        val first = File(workDir, "v0.pdf")
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(doc.uri)?.use { input -> first.outputStream().use { input.copyTo(it) } }
        }
        versions += first
        busy = false
    }

    LaunchedEffect(current, page) {
        val file = current ?: return@LaunchedEffect
        selected = null
        objectDraft = null
        val uri = Uri.fromFile(file)
        pageCount = thumbnails.pageCount(uri).takeIf { it > 0 } ?: pageCount
        bitmap = thumbnails.render(uri, page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)), 1500)
        content = runCatching { editor.scan(file, page) }.getOrNull()
        content?.let { selected = pending(it) }
        pending = { null }
    }

    fun runOp(then: (PageContent) -> Selected? = { null }, block: suspend (File, File) -> Unit) {
        val input = current ?: return
        scope.launch {
            busy = true
            val output = File(workDir, "v${++counter}.pdf")
            try {
                pending = then
                withContext(Dispatchers.IO) { block(input, output) }
                if (output.exists() && output.length() > 0) versions += output
            } catch (e: Exception) {
                Toast.makeText(context, R.string.edit_failed, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val image = SignatureImages.loadUpright(context, uri, 2000)
            val pageContent = content
            if (image == null || pageContent == null) {
                Toast.makeText(context, R.string.sig_image_error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val width = 0.4f
            val height = (width * pageContent.widthPt / pageContent.heightPt * image.height / image.width).coerceAtMost(0.8f)
            val box = PdfRect(0.5f - width / 2, 0.5f - height / 2, 0.5f + width / 2, 0.5f + height / 2)
            mode = EditMode.IMAGES
            runOp(then = { c -> c.images.lastIndex.takeIf { it >= 0 }?.let { Selected.Image(it) } }) { input, output ->
                editor.addImage(input, output, page, image, box)
            }
        }
    }

    fun leave() {
        if (modified) confirmDiscard = true else onBack()
    }
    BackHandler(enabled = !busy) { leave() }

    val boxOf: (Selected?) -> TextBox? = { s -> (s as? Selected.Box)?.let { content?.boxes?.getOrNull(it.id) } }

    Scaffold(
        topBar = {
            ScreenTopBar(stringResource(R.string.tool_edit_content_short), ::leave) {
                IconButton(enabled = modified && !busy, onClick = { versions.removeAt(versions.lastIndex) }) {
                    Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo))
                }
                IconButton(
                    enabled = modified && !busy,
                    onClick = {
                        val file = current ?: return@IconButton
                        scope.launch {
                            busy = true
                            val copy = File(workDir, "final.pdf")
                            withContext(Dispatchers.IO) { file.copyTo(copy, overwrite = true) }
                            if (!onSave(copy)) busy = false
                        }
                    }
                ) { Icon(Icons.Default.Check, stringResource(R.string.save)) }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    val hint = when {
                        selected is Selected.Box -> stringResource(R.string.edit_hint_box)
                        selected is Selected.Image -> stringResource(R.string.edit_hint_image)
                        mode == EditMode.IMAGES && content?.images?.isEmpty() == true -> stringResource(R.string.edit_no_images)
                        mode == EditMode.IMAGES -> stringResource(R.string.edit_hint_images)
                        mode == EditMode.ADD_TEXT -> stringResource(R.string.edit_hint_add_text)
                        content?.lines?.isEmpty() == true && content?.boxes?.isEmpty() == true -> stringResource(R.string.edit_no_text)
                        else -> stringResource(R.string.edit_hint_default)
                    }
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = mode == EditMode.EDIT,
                            onClick = { mode = EditMode.EDIT; selected = null },
                            label = { Text(stringResource(R.string.edit_mode_edit)) }
                        )
                        FilterChip(
                            selected = mode == EditMode.ADD_TEXT,
                            onClick = { mode = EditMode.ADD_TEXT; selected = null },
                            label = { Text(stringResource(R.string.edit_mode_text)) }
                        )
                        FilterChip(
                            selected = mode == EditMode.IMAGES,
                            onClick = { mode = EditMode.IMAGES; selected = null },
                            label = { Text(stringResource(R.string.edit_mode_images)) }
                        )
                        FilledTonalIconButton(enabled = !busy, onClick = { pickImage.launch("image/*") }) {
                            Icon(Icons.Default.AddPhotoAlternate, stringResource(R.string.edit_add_image))
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(enabled = page > 0 && !busy, onClick = { page-- }) {
                            Icon(Icons.Default.ChevronLeft, stringResource(R.string.back))
                        }
                        Text(
                            stringResource(R.string.edit_page_of, page + 1, pageCount.coerceAtLeast(1)),
                            style = MaterialTheme.typography.labelLarge
                        )
                        IconButton(enabled = page < pageCount - 1 && !busy, onClick = { page++ }) {
                            Icon(Icons.Default.ChevronRight, stringResource(R.string.done))
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceContainer)) {
            val shown = bitmap
            val pageContent = content
            if (shown != null) {
                PageCanvas(
                    bitmap = shown,
                    content = pageContent,
                    mode = mode,
                    selected = selected,
                    draft = objectDraft,
                    onTapLine = { draft = TextDraft(it, null, 0f, 0f) },
                    onTapBox = { box ->
                        if (selected == Selected.Box(box.id)) {
                            draft = TextDraft(null, box, 0f, 0f)
                        } else {
                            selected = Selected.Box(box.id)
                            objectDraft = null
                        }
                    },
                    onTapImage = { id ->
                        selected = Selected.Image(id)
                        objectDraft = null
                        mode = EditMode.IMAGES
                    },
                    onTapEmpty = { nx, ny ->
                        if (mode == EditMode.ADD_TEXT) draft = TextDraft(null, null, nx, ny) else selected = null
                    },
                    onDraft = { objectDraft = it },
                    onCommit = { sel, rect ->
                        objectDraft = null
                        val pageNow = content ?: return@PageCanvas
                        when (sel) {
                            is Selected.Image -> {
                                val image = pageNow.images.getOrNull(sel.id) ?: return@PageCanvas
                                runOp(then = { Selected.Image(sel.id) }) { input, output ->
                                    editor.placeImage(input, output, page, image, rect)
                                }
                            }
                            is Selected.Box -> {
                                val box = pageNow.boxes.getOrNull(sel.id) ?: return@PageCanvas
                                runOp(then = { Selected.Box(sel.id) }) { input, output ->
                                    editor.updateTextBox(
                                        input, output, page, box,
                                        TextBoxSpec(rect, box.text, box.fontSize, box.colorArgb, box.style)
                                    )
                                }
                            }
                        }
                    }
                )
            }
            val sel = selected
            if (sel != null && pageContent != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    if (sel is Selected.Box) {
                        val box = boxOf(sel)
                        if (box != null) {
                            FilledTonalButton(onClick = { draft = TextDraft(null, box, 0f, 0f) }) {
                                Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.edit_edit_box))
                            }
                            FilledTonalButton(onClick = {
                                selected = null
                                runOp { input, output ->
                                    editor.deleteTextBox(input, output, page, box)
                                }
                            }) {
                                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.delete))
                            }
                        }
                    } else if (sel is Selected.Image) {
                        val image = pageContent.images.getOrNull(sel.id)
                        if (image != null) {
                            FilledTonalButton(onClick = {
                                selected = null
                                runOp { input, output -> editor.deleteImage(input, output, page, image) }
                            }) {
                                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.edit_delete_image))
                            }
                        }
                    }
                }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }

    draft?.let { d ->
        TextDialog(
            draft = d,
            defaultSize = d.line?.fontSize ?: d.box?.fontSize ?: 14f,
            onDismiss = { draft = null },
            onApply = { text, size, color, style ->
                draft = null
                val line = d.line
                val box = d.box
                val pageNow = content
                when {
                    box != null -> runOp(then = { Selected.Box(box.id) }) { input, output ->
                        if (text.isBlank()) {
                            editor.deleteTextBox(input, output, page, box)
                        } else {
                            editor.updateTextBox(input, output, page, box, TextBoxSpec(box.box, text, size, color, style))
                        }
                    }
                    line != null -> runOp(then = { c -> c.boxes.lastIndex.takeIf { it >= 0 && text.isNotBlank() }?.let { Selected.Box(it) } }) { input, output ->
                        editor.replaceText(input, output, page, line, text, size, color, style)
                    }
                    text.isNotBlank() && pageNow != null -> {
                        val width = 0.6f
                        val left = d.nx.coerceAtMost(0.97f - width).coerceAtLeast(0.02f)
                        val top = d.ny.coerceIn(0f, 0.95f)
                        val rect = PdfRect(left, top, left + width, top + size * 1.3f / pageNow.heightPt)
                        mode = EditMode.EDIT
                        runOp(then = { c -> c.boxes.lastIndex.takeIf { it >= 0 }?.let { Selected.Box(it) } }) { input, output ->
                            editor.addTextBox(input, output, page, TextBoxSpec(rect, text, size, color, style))
                        }
                    }
                }
            }
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard)) },
            text = { Text(stringResource(R.string.edit_discard_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onBack() }) {
                    Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

/** The page bitmap with pinch zoom, tappable text and boxes, and draggable boxes and images. */
@Composable
private fun PageCanvas(
    bitmap: Bitmap,
    content: PageContent?,
    mode: EditMode,
    selected: Selected?,
    draft: PdfRect?,
    onTapLine: (TextLine) -> Unit,
    onTapBox: (TextBox) -> Unit,
    onTapImage: (Int) -> Unit,
    onTapEmpty: (Float, Float) -> Unit,
    onDraft: (PdfRect) -> Unit,
    onCommit: (Selected, PdfRect) -> Unit
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    val aspect = bitmap.width.toFloat() / bitmap.height
    val accent = MaterialTheme.colorScheme.tertiary

    fun clampPan(p: Offset, z: Float): Offset {
        val maxX = (pageSize.width * z - container.width).coerceAtLeast(0f) / 2f
        val maxY = (pageSize.height * z - container.height).coerceAtLeast(0f) / 2f
        return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { container = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, p, z, _ ->
                    val newZoom = (zoom * z).coerceIn(1f, 5f)
                    zoom = newZoom
                    pan = if (newZoom <= 1f) Offset.Zero else clampPan(pan + p, newZoom)
                }
            }
    ) {
        // Fit the whole page inside the available area, in either orientation.
        val density = LocalDensity.current
        val fitWidthPx = minOf(container.width.toFloat(), container.height * aspect)
        val fitWidth = with(density) { fitWidthPx.toDp() }
        val fitHeight = with(density) { (fitWidthPx / aspect).toDp() }
        if (container.width > 0 && container.height > 0) Box(
            Modifier
                .align(Alignment.Center)
                .size(fitWidth, fitHeight)
                .onSizeChanged { pageSize = it }
                .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }
                .pointerInput(content, mode) {
                    detectTapGestures { offset ->
                        val c = content ?: return@detectTapGestures
                        val nx = offset.x / size.width
                        val ny = offset.y / size.height
                        val image = c.images.lastOrNull { contains(it.box, nx, ny) }
                        when (mode) {
                            EditMode.EDIT -> {
                                val box = c.boxes.lastOrNull { contains(it.box, nx, ny, 0.004f) }
                                val line = c.lines.firstOrNull { contains(it.box, nx, ny, 0.004f) }
                                when {
                                    box != null -> onTapBox(box)
                                    line != null -> onTapLine(line)
                                    image != null -> onTapImage(image.id)
                                    else -> onTapEmpty(nx, ny)
                                }
                            }
                            EditMode.IMAGES -> if (image != null) onTapImage(image.id) else onTapEmpty(nx, ny)
                            EditMode.ADD_TEXT -> onTapEmpty(nx, ny)
                        }
                    }
                }
        ) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize())
            if (content != null) {
                val selectedBox = (selected as? Selected.Box)?.let { content.boxes.getOrNull(it.id) }
                val selectedImage = (selected as? Selected.Image)?.let { content.images.getOrNull(it.id) }
                Canvas(Modifier.fillMaxSize()) {
                    if (mode == EditMode.EDIT) {
                        content.lines.filterNot { line ->
                            content.images.any { contains(it.box, line.box.centerX, line.box.centerY) }
                        }.forEach { line ->
                            val r = line.box
                            drawRect(
                                accent.copy(alpha = 0.18f),
                                Offset(r.left * size.width, r.top * size.height),
                                Size(r.width * size.width, r.height * size.height)
                            )
                        }
                        content.boxes.forEach { box ->
                            val r = if (box.id == (selected as? Selected.Box)?.id) draft ?: box.box else box.box
                            drawRect(
                                accent.copy(alpha = if (box.id == (selected as? Selected.Box)?.id) 1f else 0.7f),
                                Offset(r.left * size.width, r.top * size.height),
                                Size(r.width * size.width, r.height * size.height),
                                style = Stroke(
                                    width = if (box.id == (selected as? Selected.Box)?.id) 3f else 1.5f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f))
                                )
                            )
                        }
                    }
                    if (mode == EditMode.IMAGES) content.images.forEach { image ->
                        val isSelected = image.id == (selected as? Selected.Image)?.id
                        val r = if (isSelected) draft ?: image.box else image.box
                        drawRect(
                            accent.copy(alpha = if (isSelected) 1f else 0.5f),
                            Offset(r.left * size.width, r.top * size.height),
                            Size(r.width * size.width, r.height * size.height),
                            style = Stroke(width = if (isSelected) 3f else 1.5f)
                        )
                    }
                }
                if (selectedBox != null && selected != null) {
                    ObjectHandles(
                        key = selectedBox.id * 2 + 1,
                        box = draft ?: selectedBox.box,
                        lockAspect = false,
                        pageSize = pageSize,
                        zoom = zoom,
                        onDraft = onDraft,
                        onCommit = { rect -> onCommit(selected, rect) }
                    )
                }
                if (selectedImage != null && selected != null) {
                    ObjectHandles(
                        key = selectedImage.id * 2,
                        box = draft ?: selectedImage.box,
                        lockAspect = true,
                        pageSize = pageSize,
                        zoom = zoom,
                        onDraft = onDraft,
                        onCommit = { rect -> onCommit(selected, rect) }
                    )
                }
            }
        }
    }
}

private fun contains(r: PdfRect, x: Float, y: Float, pad: Float = 0f) =
    x in (r.left - pad)..(r.right + pad) && y in (r.top - pad)..(r.bottom + pad)

/**
 * Drag surface and four corner handles for the selected object. Images keep their proportions
 * when resized ([lockAspect]); text boxes resize freely.
 */
@Composable
private fun ObjectHandles(
    key: Int,
    box: PdfRect,
    lockAspect: Boolean,
    pageSize: IntSize,
    zoom: Float,
    onDraft: (PdfRect) -> Unit,
    onCommit: (PdfRect) -> Unit
) {
    if (pageSize.width == 0 || pageSize.height == 0) return
    val currentBox by rememberUpdatedState(box)
    val startRatio = remember(key) { box.height / box.width.coerceAtLeast(1e-4f) }
    val minWidth = 0.05f
    val minHeight = 0.02f
    val density = LocalDensity.current
    val handleSize = (34f / zoom).coerceAtLeast(14f)
    val handleDp = with(density) { handleSize.toDp() }
    val color = MaterialTheme.colorScheme.tertiary

    // Body: moves the object.
    Box(
        Modifier
            .offset { IntOffset((box.left * pageSize.width).roundToInt(), (box.top * pageSize.height).roundToInt()) }
            .size(
                with(density) { (box.width * pageSize.width).toDp() },
                with(density) { (box.height * pageSize.height).toDp() }
            )
            .pointerInput(key) {
                detectDragGestures(
                    onDragEnd = { onCommit(currentBox) },
                    onDragCancel = { onCommit(currentBox) }
                ) { change, drag ->
                    change.consume()
                    val b = currentBox
                    val dx = (drag.x / pageSize.width).coerceIn(-b.left, 1f - b.right)
                    val dy = (drag.y / pageSize.height).coerceIn(-b.top, 1f - b.bottom)
                    onDraft(b.offset(dx, dy))
                }
            }
    )

    // Corners: 0 = top-left, 1 = top-right, 2 = bottom-left, 3 = bottom-right. The opposite corner stays put.
    val corners = listOf(
        Offset(box.left, box.top), Offset(box.right, box.top),
        Offset(box.left, box.bottom), Offset(box.right, box.bottom)
    )
    corners.forEachIndexed { index, corner ->
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (corner.x * pageSize.width - handleSize / 2).roundToInt(),
                        (corner.y * pageSize.height - handleSize / 2).roundToInt()
                    )
                }
                .size(handleDp)
                .clip(CircleShape)
                .background(color)
                .border(3.dp, Color.White, CircleShape)
                .pointerInput(key, index) {
                    detectDragGestures(
                        onDragEnd = { onCommit(currentBox) },
                        onDragCancel = { onCommit(currentBox) }
                    ) { change, drag ->
                        change.consume()
                        val b = currentBox
                        val right = index == 1 || index == 3
                        val bottom = index >= 2
                        val anchorX = if (right) b.left else b.right
                        val anchorY = if (bottom) b.top else b.bottom
                        val movingX = ((if (right) b.right else b.left) + drag.x / pageSize.width).coerceIn(0f, 1f)
                        val movingY = ((if (bottom) b.bottom else b.top) + drag.y / pageSize.height).coerceIn(0f, 1f)
                        val width = abs(movingX - anchorX).coerceAtLeast(minWidth)
                        // Images: the width decides and the height follows. Text boxes: both are free.
                        val height = if (lockAspect) (width * startRatio).coerceAtMost(1f) else abs(movingY - anchorY).coerceAtLeast(minHeight)
                        val l = if (right) anchorX else anchorX - width
                        val t = if (bottom) anchorY else anchorY - height
                        onDraft(PdfRect(l, t, l + width, t + height))
                    }
                }
        )
    }
}

@Composable
private fun TextDialog(
    draft: TextDraft,
    defaultSize: Float,
    onDismiss: () -> Unit,
    onApply: (text: String, size: Float, color: Int, style: TextStyle) -> Unit
) {
    val existing = draft.line != null || draft.box != null
    var text by remember { mutableStateOf(draft.line?.text ?: draft.box?.text ?: "") }
    var size by remember { mutableFloatStateOf(defaultSize.coerceIn(6f, 72f)) }
    var color by remember { mutableIntStateOf(draft.line?.colorArgb ?: draft.box?.colorArgb ?: TextColors[0]) }
    var style by remember { mutableStateOf(draft.line?.style ?: draft.box?.style ?: TextStyle()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing) R.string.edit_text_title else R.string.edit_new_text_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.edit_text_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        FontFamilyKind.SANS to R.string.edit_font_sans,
                        FontFamilyKind.SERIF to R.string.edit_font_serif,
                        FontFamilyKind.MONO to R.string.edit_font_mono
                    ).forEach { (family, label) ->
                        FilterChip(
                            selected = style.family == family,
                            onClick = { style = style.copy(family = family) },
                            label = { Text(stringResource(label)) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = style.bold,
                        onClick = { style = style.copy(bold = !style.bold) },
                        label = { Text("B", fontWeight = FontWeight.Bold) }
                    )
                    FilterChip(
                        selected = style.italic,
                        onClick = { style = style.copy(italic = !style.italic) },
                        label = { Text("I", fontStyle = FontStyle.Italic) }
                    )
                    FilterChip(
                        selected = style.underline,
                        onClick = { style = style.copy(underline = !style.underline) },
                        label = { Text("U", textDecoration = TextDecoration.Underline) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        Triple(TextAlignment.LEFT, Icons.Default.FormatAlignLeft, R.string.edit_align_left),
                        Triple(TextAlignment.CENTER, Icons.Default.FormatAlignCenter, R.string.edit_align_center),
                        Triple(TextAlignment.RIGHT, Icons.Default.FormatAlignRight, R.string.edit_align_right),
                        Triple(TextAlignment.JUSTIFY, Icons.Default.FormatAlignJustify, R.string.edit_align_justify)
                    ).forEach { (align, icon, label) ->
                        FilledIconToggleButton(
                            checked = style.align == align,
                            onCheckedChange = { style = style.copy(align = align) }
                        ) { Icon(icon, stringResource(label)) }
                    }
                }
                Text(stringResource(R.string.edit_font_size, size.roundToInt()), style = MaterialTheme.typography.labelLarge)
                Slider(value = size, onValueChange = { size = it }, valueRange = 6f..72f)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextColors.forEach { c ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    if ((color and 0xFFFFFF) == (c and 0xFFFFFF)) 3.dp else 1.dp,
                                    if ((color and 0xFFFFFF) == (c and 0xFFFFFF)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                                .pointerInput(c) { detectTapGestures { color = c } }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(text, size, color, style) }, enabled = text.isNotBlank() || existing) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            Row {
                if (existing) {
                    TextButton(onClick = { onApply("", size, color, style) }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}
