package org.ultimatepdf.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ultimatepdf.core.model.PdfRect
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

enum class FontFamilyKind(val assetName: String) { SANS("Sans"), SERIF("Serif"), MONO("Mono") }

enum class TextAlignment { LEFT, CENTER, RIGHT, JUSTIFY }

/** How a piece of text looks: one of the bundled families plus bold / italic / underline / alignment. */
data class TextStyle(
    val family: FontFamilyKind = FontFamilyKind.SANS,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val align: TextAlignment = TextAlignment.LEFT
)

/** A text box created in the editor: it can be moved, resized, restyled and edited again. */
data class TextBox(
    val id: Int,
    /** Page-normalized (0..1, top-left origin, as displayed). */
    val box: PdfRect,
    val text: String,
    val fontSize: Float,
    val colorArgb: Int,
    val style: TextStyle
)

/** One line of real page text. [box] is page-normalized (0..1, top-left origin, as displayed). */
data class TextLine(
    val id: Int,
    val text: String,
    val box: PdfRect,
    /** Font size in points. */
    val fontSize: Float,
    val colorArgb: Int,
    val style: TextStyle = TextStyle()
)

/** What to draw for a text box: the same fields as [TextBox] without an id. */
class TextBoxSpec(val box: PdfRect, val text: String, val fontSize: Float, val colorArgb: Int, val style: TextStyle)

/** An image (or picture-only group) drawn on the page, [box] page-normalized. */
data class PageImage(val id: Int, val box: PdfRect)

/** An image ready to embed elsewhere: [widthPt] × [heightPt] is its size on the page. */
class EncodedImage(val box: PdfRect, val bytes: ByteArray, val isPng: Boolean, val widthPt: Float, val heightPt: Float)

/** What can be edited on one page. Sizes are those of the page as displayed (rotation applied). */
data class PageContent(
    val widthPt: Float,
    val heightPt: Float,
    val lines: List<TextLine>,
    val images: List<PageImage>,
    val boxes: List<TextBox> = emptyList()
)

/**
 * Edits the content of existing PDF pages: rewrites or removes real text, adds text and
 * images, and moves, resizes or deletes the images already on the page.
 *
 * Text is edited by taking the original text-showing operators out of the page's content
 * stream (so the old words are really gone, not just covered) and drawing the new text with
 * an embedded font. Images are moved and resized by wrapping their drawing operator in a
 * corrective transformation. Pages with a /Rotate entry are handled by mapping between the
 * displayed page and the PDF's own coordinates. Every operation reads [input] and writes
 * [output], which keeps undo trivial: the caller just keeps the previous file.
 */
class PdfContentEditor(private val context: Context) {

    companion object {
        private const val TAG = "PdfContentEditor"
        private val SHOW_OPERATORS = setOf("Tj", "TJ", "'", "\"")

        /** Marked-content tag wrapping every text box, carrying its properties. */
        private const val BOX_TAG = "UltimateText"
    }

    suspend fun scan(input: File, pageIndex: Int): PageContent = withContext(Dispatchers.IO) {
        PDDocument.load(input).use { doc ->
            val page = doc.getPage(pageIndex)
            scanPage(doc, page, pageIndex).toContent()
        }
    }

    /** The images drawn on a page, encoded for embedding in other formats. */
    suspend fun pageImages(input: File, pageIndex: Int): List<EncodedImage> = withContext(Dispatchers.IO) {
        PDDocument.load(input).use { doc ->
            val page = doc.getPage(pageIndex)
            val scan = scanPage(doc, page, pageIndex)
            val resources = page.resources ?: return@use emptyList()
            scan.imageOps.mapNotNull { op ->
                try {
                    val name = op.name ?: return@mapNotNull null
                    val xObject = resources.getXObject(name) as? PDImageXObject ?: return@mapNotNull null
                    val bitmap = xObject.image ?: return@mapNotNull null
                    val png = bitmap.hasAlpha()
                    val bytes = java.io.ByteArrayOutputStream().also {
                        bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, it)
                    }.toByteArray()
                    val box = scan.normalizedBox(op.bounds())
                    EncodedImage(box, bytes, png, scan.geo.dispW * box.width, scan.geo.dispH * box.height)
                } catch (e: Exception) {
                    Log.w(TAG, "Image export failed", e)
                    null
                }
            }
        }
    }

    /** Replaces [line] with [newText] (empty deletes it); the new text becomes an editable box. */
    suspend fun replaceText(
        input: File, output: File, pageIndex: Int,
        line: TextLine, newText: String, fontSize: Float, colorArgb: Int, style: TextStyle
    ) = withContext(Dispatchers.IO) {
        PDDocument.load(input).use { doc ->
            val page = doc.getPage(pageIndex)
            val scan = scanPage(doc, page, pageIndex)
            val geo = scan.geo
            val padX = line.fontSize * 0.5f / geo.dispW
            val padY = line.fontSize * 0.1f / geo.dispH
            val inside = scan.showOps.filter { op ->
                val nx = geo.nx(op.x, op.y)
                val ny = geo.ny(op.x, op.y)
                nx in (line.box.left - padX)..(line.box.right + padX) &&
                    ny in (line.box.top - padY)..(line.box.bottom + padY)
            }
            rewrite(doc, page, inside.associate { it.index to it.replacement() })
            if (newText.isNotBlank()) {
                val font = loadFont(doc, style)
                val natural = naturalWidth(font, newText, fontSize) / geo.dispW
                val left = line.box.left
                val width = minOf(maxOf(line.box.width, natural) + 0.01f, 0.98f - left).coerceAtLeast(0.05f)
                // Keep the original baseline: the box's first baseline sits 0.9 font sizes below its top.
                val baseline = line.box.top + 0.8f * line.fontSize / geo.dispH
                val top = baseline - 0.9f * fontSize / geo.dispH
                drawBox(doc, page, geo, TextBoxSpec(PdfRect(left, top, left + width, top + fontSize / geo.dispH), newText, fontSize, colorArgb, style))
            }
            doc.save(output)
        }
    }

    /** Adds a new text box. */
    suspend fun addTextBox(input: File, output: File, pageIndex: Int, spec: TextBoxSpec) = withContext(Dispatchers.IO) {
        PDDocument.load(input).use { doc ->
            val page = doc.getPage(pageIndex)
            drawBox(doc, page, Geo.of(page), spec)
            doc.save(output)
        }
    }

    /** Rewrites [box] (found by its id on the page) with a new content, style or position. */
    suspend fun updateTextBox(input: File, output: File, pageIndex: Int, box: TextBox, spec: TextBoxSpec) = withContext(Dispatchers.IO) {
        PDDocument.load(input).use { doc ->
            val page = doc.getPage(pageIndex)
            val scan = scanPage(doc, page, pageIndex)
            val range = scan.boxRanges.getOrNull(box.id) ?: return@use
            rewrite(doc, page, (range.start..range.end).associateWith { emptyList<Any>() })
            if (spec.text.isNotBlank()) drawBox(doc, page, scan.geo, spec)
            doc.save(output)
        }
    }

    suspend fun deleteTextBox(input: File, output: File, pageIndex: Int, box: TextBox) =
        updateTextBox(input, output, pageIndex, box, TextBoxSpec(box.box, "", box.fontSize, box.colorArgb, box.style))

    suspend fun deleteImage(input: File, output: File, pageIndex: Int, image: PageImage) = withContext(Dispatchers.IO) {
        PDDocument.load(input).use { doc ->
            val page = doc.getPage(pageIndex)
            val scan = scanPage(doc, page, pageIndex)
            val op = scan.imageOps.getOrNull(image.id) ?: return@use
            rewrite(doc, page, mapOf(op.index to emptyList()))
            doc.save(output)
        }
    }

    /** Moves / resizes [image] so it covers [box] (page-normalized). */
    suspend fun placeImage(input: File, output: File, pageIndex: Int, image: PageImage, box: PdfRect) = withContext(Dispatchers.IO) {
        PDDocument.load(input).use { doc ->
            val page = doc.getPage(pageIndex)
            val scan = scanPage(doc, page, pageIndex)
            val geo = scan.geo
            val op = scan.imageOps.getOrNull(image.id) ?: return@use
            val old = op.bounds()
            val target = geo.userBounds(box)
            val sx = target.width / old.width
            val sy = target.height / old.height
            // S works in page space; CTM·S·CTM⁻¹ expresses it where the operator sits.
            val s = Aff(sx, 0.0, 0.0, sy, target.left - old.left * sx, target.bottom - old.bottom * sy)
            val m = op.ctm.times(s).times(op.ctm.inverse())
            val tokens = mutableListOf<Any>(
                Operator.getOperator("q"),
                num(m.a), num(m.b), num(m.c), num(m.d), num(m.e), num(m.f), Operator.getOperator("cm")
            )
            tokens.addAll(op.emit)
            tokens.add(Operator.getOperator("Q"))
            rewrite(doc, page, mapOf(op.index to tokens))
            doc.save(output)
        }
    }

    /** Adds [bitmap] covering [box] (page-normalized), upright as displayed. */
    suspend fun addImage(input: File, output: File, pageIndex: Int, bitmap: Bitmap, box: PdfRect) = withContext(Dispatchers.IO) {
        PDDocument.load(input).use { doc ->
            val page = doc.getPage(pageIndex)
            val geo = Geo.of(page)
            val image = if (bitmap.hasAlpha()) LosslessFactory.createFromImage(doc, bitmap) else JPEGFactory.createFromImage(doc, bitmap, 0.92f)
            // Unit square → page: bottom-left, bottom-right and top-left corners of the displayed box.
            val p00x = geo.userX(box.left, box.bottom); val p00y = geo.userY(box.left, box.bottom)
            val p10x = geo.userX(box.right, box.bottom); val p10y = geo.userY(box.right, box.bottom)
            val p01x = geo.userX(box.left, box.top); val p01y = geo.userY(box.left, box.top)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.drawImage(image, Matrix(p10x - p00x, p10y - p00y, p01x - p00x, p01y - p00y, p00x, p00y))
            }
            doc.save(output)
        }
    }

    // ---- geometry -------------------------------------------------------------------------

    /**
     * Maps between PDF user space and the page as displayed: normalized 0..1, origin at the
     * top-left, with the page's /Rotate applied.
     */
    private class Geo(val l: Float, val b: Float, val r: Float, val t: Float, val rotation: Int) {
        val w = r - l
        val h = t - b
        val dispW = if (rotation % 180 == 0) w else h
        val dispH = if (rotation % 180 == 0) h else w

        fun nx(x: Float, y: Float) = when (rotation) {
            90 -> (y - b) / h
            180 -> (r - x) / w
            270 -> (t - y) / h
            else -> (x - l) / w
        }

        fun ny(x: Float, y: Float) = when (rotation) {
            90 -> (x - l) / w
            180 -> (y - b) / h
            270 -> (r - x) / w
            else -> (t - y) / h
        }

        fun userX(nx: Float, ny: Float) = when (rotation) {
            90 -> l + ny * w
            180 -> r - nx * w
            270 -> r - ny * w
            else -> l + nx * w
        }

        fun userY(nx: Float, ny: Float) = when (rotation) {
            90 -> b + nx * h
            180 -> b + ny * h
            270 -> t - nx * h
            else -> t - ny * h
        }

        /** Axis-aligned box in user space covering a displayed, normalized rectangle. */
        fun userBounds(box: PdfRect): AffBounds {
            val xs = listOf(userX(box.left, box.top), userX(box.right, box.bottom))
            val ys = listOf(userY(box.left, box.top), userY(box.right, box.bottom))
            return AffBounds(xs.min().toDouble(), xs.max().toDouble(), ys.min().toDouble(), ys.max().toDouble())
        }

        companion object {
            fun of(page: PDPage): Geo {
                val box = page.cropBox
                return Geo(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY, ((page.rotation % 360) + 360) % 360)
            }
        }
    }

    // ---- scanning -------------------------------------------------------------------------

    private class Word(val text: String, val box: PdfRect, val fontSize: Float, val style: TextStyle)

    private class ShowOp(
        val index: Int,
        val name: String,
        val operands: List<COSBase>,
        /** Origin of the text in user space. */
        val x: Float,
        val y: Float,
        /** Horizontal advance in text space units and the font-size × scaling it is relative to. */
        val advance: Float,
        val fontScale: Float,
        val colorArgb: Int,
        /** Invisible text, e.g. the OCR layer of a scanned page. */
        val invisible: Boolean
    ) {
        /** Tokens that keep the text position of what follows after this operator is dropped. */
        fun replacement(): List<Any> {
            val tokens = mutableListOf<Any>()
            when (name) {
                "'" -> tokens.add(Operator.getOperator("T*"))
                "\"" -> if (operands.size >= 3) {
                    tokens.add(operands[0]); tokens.add(Operator.getOperator("Tw"))
                    tokens.add(operands[1]); tokens.add(Operator.getOperator("Tc"))
                    tokens.add(Operator.getOperator("T*"))
                }
            }
            if (name == "Tj" || name == "TJ") {
                val adjust = -advance * 1000f / fontScale
                if (fontScale > 0f && abs(adjust) >= 1f) {
                    tokens.add(COSArray().apply { add(COSFloat(adjust)) })
                    tokens.add(Operator.getOperator("TJ"))
                }
            }
            return tokens
        }
    }

    /**
     * Something drawn as a picture: an image XObject, an inline image, or a form XObject made
     * only of images. [emit] are the original tokens (operands and operator) to put back.
     */
    private class ImageOp(
        val index: Int,
        val emit: List<Any>,
        val name: COSName?,
        val ctm: Aff,
        val explicit: AffBounds?
    ) {
        /** Page-space bounding box. */
        fun bounds(): AffBounds = explicit ?: unitSquareBounds(ctm)
    }

    /** Operators [start]..[end] of the page content that draw one text box described by [dict]. */
    private class BoxRange(val start: Int, val end: Int, val dict: COSDictionary)

    private class PageScan(
        val geo: Geo,
        val words: List<Word>,
        val showOps: List<ShowOp>,
        val imageOps: List<ImageOp>,
        val boxRanges: List<BoxRange>
    ) {
        val boxes: List<TextBox> = boxRanges.mapIndexed { i, r -> parseBox(i, r.dict) }

        private fun parseBox(id: Int, d: COSDictionary): TextBox {
            val x = d.getFloat("X", 0f)
            val y = d.getFloat("Y", 0f)
            val style = TextStyle(
                family = FontFamilyKind.entries.getOrElse(d.getInt("Fam", 0)) { FontFamilyKind.SANS },
                bold = d.getInt("B", 0) == 1,
                italic = d.getInt("I", 0) == 1,
                underline = d.getInt("U", 0) == 1,
                align = TextAlignment.entries.getOrElse(d.getInt("A", 0)) { TextAlignment.LEFT }
            )
            return TextBox(
                id, PdfRect(x, y, x + d.getFloat("W", 0.3f), y + d.getFloat("H", 0.05f)),
                d.getString("T") ?: "", d.getFloat("FS", 12f), 0xFF000000.toInt() or d.getInt("C", 0), style
            )
        }

        private fun insideBox(word: Word): Boolean = boxes.any { b ->
            val cx = (word.box.left + word.box.right) / 2f
            val cy = (word.box.top + word.box.bottom) / 2f
            cx in b.box.left..b.box.right && cy in b.box.top..b.box.bottom
        }

        private fun inBoxRange(opIndex: Int) = boxRanges.any { opIndex in it.start..it.end }

        fun normalizedBox(b: AffBounds): PdfRect {
            val xs = listOf(geo.nx(b.left.toFloat(), b.bottom.toFloat()), geo.nx(b.right.toFloat(), b.top.toFloat()))
            val ys = listOf(geo.ny(b.left.toFloat(), b.bottom.toFloat()), geo.ny(b.right.toFloat(), b.top.toFloat()))
            return PdfRect(xs.min(), ys.min(), xs.max(), ys.max())
        }

        fun toContent(): PageContent {
            val images = imageOps.mapIndexed { i, op -> PageImage(i, normalizedBox(op.bounds())) }
            return PageContent(geo.dispW, geo.dispH, buildLines(), images, boxes)
        }

        private fun isInvisible(word: Word): Boolean {
            val padY = word.fontSize * 0.1f / geo.dispH
            return showOps.any { op ->
                if (!op.invisible) return@any false
                val nx = geo.nx(op.x, op.y)
                val ny = geo.ny(op.x, op.y)
                nx in (word.box.left - 0.005f)..word.box.right && ny in (word.box.top - padY)..(word.box.bottom + padY)
            }
        }

        private fun buildLines(): List<TextLine> {
            class Acc(var box: PdfRect, val fontSize: Float, val style: TextStyle, val parts: MutableList<String>)
            val accs = mutableListOf<Acc>()
            for (word in words) {
                if (isInvisible(word) || insideBox(word)) continue
                val wordBase = word.box.bottom - 0.22f * word.fontSize / geo.dispH
                val target = accs.lastOrNull { acc -> fits(acc.box, acc.fontSize, word, wordBase) }
                    ?: accs.firstOrNull { acc -> fits(acc.box, acc.fontSize, word, wordBase) }
                if (target == null) {
                    accs += Acc(word.box, word.fontSize, word.style, mutableListOf(word.text))
                } else {
                    target.box = PdfRect(
                        minOf(target.box.left, word.box.left), minOf(target.box.top, word.box.top),
                        maxOf(target.box.right, word.box.right), maxOf(target.box.bottom, word.box.bottom)
                    )
                    target.parts += word.text
                }
            }
            return accs.mapIndexed { i, acc ->
                val first = showOps.firstOrNull { op ->
                    val sx = geo.nx(op.x, op.y)
                    val sy = geo.ny(op.x, op.y)
                    !op.invisible && !inBoxRange(op.index) && sx in (acc.box.left - 0.01f)..acc.box.right && sy in acc.box.top..(acc.box.bottom + 0.005f)
                }
                TextLine(i, acc.parts.joinToString(" "), acc.box, acc.fontSize, first?.colorArgb ?: 0xFF000000.toInt(), acc.style)
            }.filter { it.text.isNotBlank() }
        }

        private fun fits(box: PdfRect, fontSize: Float, word: Word, wordBase: Float): Boolean {
            val lineBase = box.bottom - 0.22f * fontSize / geo.dispH
            if (abs(lineBase - wordBase) > 0.4f * fontSize / geo.dispH) return false
            val gap = (word.box.left - box.right) * geo.dispW
            return gap < 2.2f * fontSize && gap > -fontSize
        }
    }

    private class Scanner(val geo: Geo) : PDFTextStripper() {
        val words = mutableListOf<Word>()
        val showOps = mutableListOf<ShowOp>()
        val imageOps = mutableListOf<ImageOp>()
        private var depth = 0
        private var index = 0
        private var formBounds: AffBounds? = null
        private var formTexts = 0
        val boxRanges = mutableListOf<BoxRange>()
        // One entry per open marked-content sequence: the text box it opened, or null.
        private val markedStack = ArrayList<Pair<Int, COSDictionary>?>()

        override fun showForm(form: PDFormXObject) {
            depth++
            try {
                super.showForm(form)
            } finally {
                depth--
            }
        }

        private fun isImageXObject(name: COSName?): Boolean =
            name != null && runCatching { resources.isImageXObject(name) }.getOrDefault(false)

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            val name = operator.name
            if (depth > 0) {
                // Inside a form XObject: only note whether it holds pictures or text.
                when {
                    name == "Do" && isImageXObject(operands.firstOrNull() as? COSName) -> addFormImage()
                    name == "BI" -> addFormImage()
                    name in SHOW_OPERATORS -> formTexts++
                }
                super.processOperator(operator, operands)
                return
            }
            val opIndex = index++
            val args = operands.toList()
            when {
                name == "BDC" -> {
                    val tag = args.getOrNull(0) as? COSName
                    val dict = args.getOrNull(1) as? COSDictionary
                    markedStack.add(if (tag?.name == BOX_TAG && dict != null) opIndex to dict else null)
                    super.processOperator(operator, operands)
                }
                name == "BMC" -> {
                    markedStack.add(null)
                    super.processOperator(operator, operands)
                }
                name == "EMC" -> {
                    markedStack.removeLastOrNull()?.let { (start, dict) -> boxRanges += BoxRange(start, opIndex, dict) }
                    super.processOperator(operator, operands)
                }
                name in SHOW_OPERATORS -> {
                    val before = origin()
                    super.processOperator(operator, operands)
                    val after = origin()
                    if (before != null) {
                        val advance = if (after != null && before.scaleX > 0f && (name == "Tj" || name == "TJ")) {
                            (after.x - before.x) / before.scaleX
                        } else {
                            0f
                        }
                        showOps += ShowOp(
                            opIndex, name, args, before.x, before.y, advance,
                            before.fontSize * horizontalScaling(), fillColor(),
                            graphicsState.textState.renderingMode == RenderingMode.NEITHER
                        )
                    }
                }
                name == "Do" -> {
                    val ctm = Aff.of(graphicsState.currentTransformationMatrix)
                    val xName = args.firstOrNull() as? COSName
                    val isImage = isImageXObject(xName)
                    formBounds = null
                    formTexts = 0
                    super.processOperator(operator, operands)
                    val group = formBounds
                    if (isImage) {
                        imageOps += ImageOp(opIndex, args + operator, xName, ctm, null)
                    } else if (group != null && formTexts == 0) {
                        imageOps += ImageOp(opIndex, args + operator, xName, ctm, group)
                    }
                }
                name == "BI" -> {
                    val ctm = Aff.of(graphicsState.currentTransformationMatrix)
                    super.processOperator(operator, operands)
                    imageOps += ImageOp(opIndex, listOf(operator), null, ctm, null)
                }
                else -> super.processOperator(operator, operands)
            }
        }

        private fun addFormImage() {
            val b = unitSquareBounds(Aff.of(graphicsState.currentTransformationMatrix))
            formBounds = formBounds?.union(b) ?: b
        }

        private class Origin(val x: Float, val y: Float, val scaleX: Float, val fontSize: Float)

        private fun origin(): Origin? {
            val tm = textMatrix ?: return null
            val m = tm.multiply(graphicsState.currentTransformationMatrix)
            return Origin(m.translateX, m.translateY, hypot(m.getValue(0, 0), m.getValue(0, 1)), graphicsState.textState.fontSize)
        }

        private fun horizontalScaling() = graphicsState.textState.horizontalScaling / 100f

        private fun fillColor(): Int = try {
            0xFF000000.toInt() or graphicsState.nonStrokingColor.toRGB()
        } catch (e: Exception) {
            0xFF000000.toInt()
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (textPositions.isEmpty() || text.isBlank()) return
            // Text at a slant (watermarks, stamps) isn't part of the reading flow.
            if (textPositions.any { tp ->
                    val m = tp.textMatrix
                    val angle = Math.toDegrees(atan2(m.getValue(0, 1).toDouble(), m.getValue(0, 0).toDouble()))
                    val off = ((angle % 90.0) + 90.0) % 90.0
                    off in 3.0..87.0
                }
            ) return
            var left = Float.MAX_VALUE
            var right = 0f
            var base = 0f
            var size = 0f
            for (tp in textPositions) {
                left = minOf(left, tp.xDirAdj)
                right = maxOf(right, tp.xDirAdj + tp.widthDirAdj)
                base = tp.yDirAdj
                size = if (tp.fontSizeInPt > 0f) tp.fontSizeInPt else tp.heightDir / 0.7f
            }
            words += Word(
                text.trim(),
                PdfRect(
                    left / geo.dispW, (base - size * 0.8f) / geo.dispH,
                    right / geo.dispW, (base + size * 0.22f) / geo.dispH
                ),
                size,
                guessStyle(textPositions[0].font?.name)
            )
        }
    }

    private fun scanPage(doc: PDDocument, page: PDPage, pageIndex: Int): PageScan {
        val geo = Geo.of(page)
        val scanner = Scanner(geo)
        scanner.startPage = pageIndex + 1
        scanner.endPage = pageIndex + 1
        try {
            scanner.getText(doc)
        } catch (e: Exception) {
            Log.w(TAG, "Page $pageIndex scan failed", e)
        }
        return PageScan(geo, scanner.words, scanner.showOps, scanner.imageOps, scanner.boxRanges)
    }

    // ---- rewriting ------------------------------------------------------------------------

    /**
     * Replaces the operators numbered in [replacements] (counting only top-level operators of
     * the page's content) together with their operands by the given tokens.
     */
    private fun rewrite(doc: PDDocument, page: PDPage, replacements: Map<Int, List<Any>>) {
        if (replacements.isEmpty()) return
        val parser = PDFStreamParser(page)
        parser.parse()
        val out = ArrayList<Any>()
        val pending = ArrayList<Any>()
        var opIndex = 0
        for (token in parser.tokens) {
            if (token is Operator) {
                val replacement = replacements[opIndex]
                if (replacement != null) {
                    out.addAll(replacement)
                } else {
                    out.addAll(pending)
                    out.add(token)
                }
                pending.clear()
                opIndex++
            } else {
                pending.add(token)
            }
        }
        out.addAll(pending)
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(out) }
        page.setContents(stream)
    }

    private class LaidOutLine(val words: List<String>, val widthPt: Float, val justify: Boolean)

    /** Breaks [text] into lines no wider than [maxWidthPt], honouring newlines and [align]. */
    private fun layout(font: PDFont, text: String, fontSize: Float, maxWidthPt: Float, align: TextAlignment): List<LaidOutLine> {
        fun w(s: String) = font.getStringWidth(s) / 1000f * fontSize
        val space = w(" ")
        val out = mutableListOf<LaidOutLine>()
        for (paragraph in text.split('\n')) {
            val words = paragraph.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                out += LaidOutLine(emptyList(), 0f, false)
                continue
            }
            var current = mutableListOf<String>()
            var currentWidth = 0f
            fun flush(last: Boolean) {
                if (current.isEmpty()) return
                out += LaidOutLine(current.toList(), currentWidth, align == TextAlignment.JUSTIFY && !last && current.size > 1)
                current = mutableListOf()
                currentWidth = 0f
            }
            for (word in words) {
                // A word wider than the box is split by characters.
                val pieces = if (w(word) <= maxWidthPt) listOf(word) else {
                    val parts = mutableListOf<String>()
                    var part = StringBuilder()
                    for (ch in word) {
                        if (part.isNotEmpty() && w(part.toString() + ch) > maxWidthPt) {
                            parts += part.toString()
                            part = StringBuilder()
                        }
                        part.append(ch)
                    }
                    if (part.isNotEmpty()) parts += part.toString()
                    parts
                }
                for (piece in pieces) {
                    val pw = w(piece)
                    val extra = if (current.isEmpty()) 0f else space
                    if (current.isNotEmpty() && currentWidth + extra + pw > maxWidthPt) flush(false)
                    currentWidth += (if (current.isEmpty()) 0f else space) + pw
                    current += piece
                }
            }
            flush(true)
        }
        return out
    }

    /** Width in points of the widest paragraph of [text] when it isn't wrapped. */
    private fun naturalWidth(font: PDFont, text: String, fontSize: Float): Float =
        text.split('\n').maxOf { runCatching { font.getStringWidth(encodable(font, it)) }.getOrDefault(0f) } / 1000f * fontSize

    /**
     * Draws a text box and tags it, so it can be found and edited again: the text is real page
     * text, wrapped to the box width and aligned as requested.
     */
    private fun drawBox(doc: PDDocument, page: PDPage, geo: Geo, spec: TextBoxSpec) {
        val style = spec.style
        val font = loadFont(doc, style)
        val fontSize = spec.fontSize
        val text = spec.text.split('\n').joinToString("\n") { encodable(font, it) }
        val boxWidthPt = spec.box.width * geo.dispW
        val lines = layout(font, text, fontSize, boxWidthPt, style.align)
        val leading = fontSize * 1.2f
        val neededPt = lines.size * leading + fontSize * 0.25f
        val boxHeight = maxOf(spec.box.height, neededPt / geo.dispH)
        val left = spec.box.left
        val top = spec.box.top
        val r = (spec.colorArgb shr 16) and 0xFF
        val g = (spec.colorArgb shr 8) and 0xFF
        val b = spec.colorArgb and 0xFF
        val rotation = Math.toRadians(geo.rotation.toDouble())

        fun position(nx: Float, ny: Float) = Matrix.getRotateInstance(rotation, geo.userX(nx, ny), geo.userY(nx, ny))

        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.appendRawCommands(boxProperties(spec, left, top, boxHeight))
            cs.beginText()
            cs.setFont(font, fontSize)
            cs.setNonStrokingColor(r, g, b)
            val underlines = mutableListOf<Triple<Float, Float, Float>>()
            lines.forEachIndexed { i, line ->
                if (line.words.isEmpty()) return@forEachIndexed
                val ny = top + (fontSize * 0.9f + i * leading) / geo.dispH
                val lineStart = when (style.align) {
                    TextAlignment.CENTER -> (boxWidthPt - line.widthPt) / 2f
                    TextAlignment.RIGHT -> boxWidthPt - line.widthPt
                    else -> 0f
                }
                if (line.justify) {
                    val wordsWidth = line.words.sumOf { (font.getStringWidth(it) / 1000f * fontSize).toDouble() }.toFloat()
                    val gap = (boxWidthPt - wordsWidth) / (line.words.size - 1)
                    var x = 0f
                    for (word in line.words) {
                        cs.setTextMatrix(position(left + x / geo.dispW, ny))
                        cs.showText(word)
                        x += font.getStringWidth(word) / 1000f * fontSize + gap
                    }
                    underlines += Triple(0f, boxWidthPt, ny)
                } else {
                    cs.setTextMatrix(position(left + lineStart / geo.dispW, ny))
                    cs.showText(line.words.joinToString(" "))
                    underlines += Triple(lineStart, lineStart + line.widthPt, ny)
                }
            }
            cs.endText()
            if (style.underline) {
                cs.setStrokingColor(r, g, b)
                cs.setLineWidth((fontSize / 16f).coerceAtLeast(0.5f))
                for ((x0, x1, ny) in underlines) {
                    val y = ny + fontSize * 0.12f / geo.dispH
                    cs.moveTo(geo.userX(left + x0 / geo.dispW, y), geo.userY(left + x0 / geo.dispW, y))
                    cs.lineTo(geo.userX(left + x1 / geo.dispW, y), geo.userY(left + x1 / geo.dispW, y))
                    cs.stroke()
                }
            }
            cs.appendRawCommands("EMC\n")
        }
    }

    /** The BDC operator (with its inline dictionary) that opens a text box. */
    private fun boxProperties(spec: TextBoxSpec, left: Float, top: Float, height: Float): String {
        fun f(v: Float) = String.format(Locale.US, "%.5f", v)
        val hex = StringBuilder("FEFF")
        for (ch in spec.text) hex.append(String.format(Locale.US, "%04X", ch.code))
        val st = spec.style
        return "/$BOX_TAG << /X ${f(left)} /Y ${f(top)} /W ${f(spec.box.width)} /H ${f(height)} " +
            "/FS ${f(spec.fontSize)} /C ${spec.colorArgb and 0xFFFFFF} /Fam ${st.family.ordinal} " +
            "/B ${if (st.bold) 1 else 0} /I ${if (st.italic) 1 else 0} /U ${if (st.underline) 1 else 0} " +
            "/A ${st.align.ordinal} /T <$hex> >> BDC\n"
    }

    private fun loadFont(doc: PDDocument, style: TextStyle): PDFont {
        val variant = when {
            style.bold && style.italic -> "BoldItalic"
            style.bold -> "Bold"
            style.italic -> "Italic"
            else -> "Regular"
        }
        return try {
            context.assets.open("fonts/Liberation${style.family.assetName}-$variant.ttf").use { PDType0Font.load(doc, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Can't load bundled font", e)
            PDType1Font.HELVETICA
        }
    }

    private fun encodable(font: PDFont, text: String): String {
        if (runCatching { font.encode(text) }.isSuccess) return text
        return text.filter { c -> runCatching { font.encode(c.toString()) }.isSuccess }
    }

    private fun num(v: Double) = COSFloat(v.toFloat())
}

/** Best bundled match for an embedded font name such as "ABCDEF+Arial-BoldMT". */
internal fun guessStyle(fontName: String?): TextStyle {
    val n = (fontName ?: return TextStyle()).substringAfter('+').lowercase()
    val bold = listOf("bold", "black", "heavy", "demi", "-bd").any { it in n }
    val italic = listOf("italic", "oblique", "ital").any { it in n } || n.endsWith("-it")
    val mono = listOf("courier", "mono", "consolas", "menlo", "typewriter", "fixed", "lucidaconsole").any { it in n }
    val serif = "sans" !in n && listOf("times", "serif", "georgia", "garamond", "palatino", "cambria", "minion", "century", "charter", "bookman").any { it in n }
    val family = when {
        mono -> FontFamilyKind.MONO
        serif -> FontFamilyKind.SERIF
        else -> FontFamilyKind.SANS
    }
    return TextStyle(family, bold, italic)
}

private class AffBounds(val left: Double, val right: Double, val bottom: Double, val top: Double) {
    val width get() = (right - left).coerceAtLeast(1e-6)
    val height get() = (top - bottom).coerceAtLeast(1e-6)

    fun union(o: AffBounds) = AffBounds(minOf(left, o.left), maxOf(right, o.right), minOf(bottom, o.bottom), maxOf(top, o.top))
}

/** Affine matrix [a b c d e f] with PDF's row-vector convention. */
private class Aff(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    /** this × o: apply this first, then o. */
    fun times(o: Aff) = Aff(
        a * o.a + b * o.c, a * o.b + b * o.d,
        c * o.a + d * o.c, c * o.b + d * o.d,
        e * o.a + f * o.c + o.e, e * o.b + f * o.d + o.f
    )

    fun inverse(): Aff {
        val det = a * d - b * c
        return Aff(d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det)
    }

    companion object {
        fun of(m: Matrix) = Aff(
            m.getValue(0, 0).toDouble(), m.getValue(0, 1).toDouble(),
            m.getValue(1, 0).toDouble(), m.getValue(1, 1).toDouble(),
            m.getValue(2, 0).toDouble(), m.getValue(2, 1).toDouble()
        )
    }
}

/** Bounding box of the unit square [ctm] maps onto the page. */
private fun unitSquareBounds(ctm: Aff): AffBounds {
    val xs = doubleArrayOf(0.0, 1.0, 0.0, 1.0)
    val ys = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
    val px = xs.indices.map { ctm.a * xs[it] + ctm.c * ys[it] + ctm.e }
    val py = xs.indices.map { ctm.b * xs[it] + ctm.d * ys[it] + ctm.f }
    return AffBounds(px.min(), px.max(), py.min(), py.max())
}
