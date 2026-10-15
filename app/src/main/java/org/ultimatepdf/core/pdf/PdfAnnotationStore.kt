package org.ultimatepdf.core.pdf

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDAppearanceContentStream
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLine
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ultimatepdf.core.model.AnnotationType
import org.ultimatepdf.core.model.PdfAnnotation
import org.ultimatepdf.core.model.PdfPoint
import org.ultimatepdf.core.model.PdfRect
import org.ultimatepdf.core.model.SignatureShape
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

/**
 * Reads and writes the markup annotations the reader can edit (Highlight / Underline /
 * StrikeOut / Ink / Text / Shapes / Stamps / Redaction), converting between PDF user space
 * and the page-normalized coordinates of [PdfAnnotation].
 *
 * Android's PdfRenderer does not draw annotations, so the reader draws them itself from
 * [read]. On [write], annotations read from the file that are still present are left
 * untouched, deleted ones are removed and new ones are appended with generated appearances.
 */
class PdfAnnotationStore(private val context: Context) {

    companion object {
        private const val TAG = "PdfAnnotationStore"

        /** Private key holding a placed signature's vector data, so it stays editable. */
        private val SIGNATURE_KEY = COSName.getPDFName("UltimatePdfSignature")
        private val ROTATION_KEY = COSName.getPDFName("UltimatePdfRotation")
        private val SHAPE_KEY = COSName.getPDFName("UltimatePdfShape")
        private val EDITABLE_SUBTYPES = setOf(
            PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT,
            PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE,
            PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT,
            PDAnnotationMarkup.SUB_TYPE_INK,
            PDAnnotationText.SUB_TYPE,
            PDAnnotationSquareCircle.SUB_TYPE_SQUARE,
            PDAnnotationSquareCircle.SUB_TYPE_CIRCLE,
            PDAnnotationLine.SUB_TYPE,
            PDAnnotationRubberStamp.SUB_TYPE
        )
    }


    suspend fun read(uri: Uri): List<PdfAnnotation> = withContext(Dispatchers.IO) {
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext emptyList()
            input.use { stream ->
                PDDocument.load(stream).use { doc ->
                    doc.pages.flatMapIndexed { pageIndex, page ->
                        val mapper = PageMapper(page)
                        page.annotations.mapIndexedNotNull { i, a ->
                            try {
                                fromPdf(a, pageIndex, i, mapper)
                            } catch (e: Exception) {
                                Log.w(TAG, "Skipping unreadable annotation $i on page $pageIndex", e)
                                null
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read annotations", e)
            emptyList()
        }
    }

    suspend fun write(
        sourceUri: Uri,
        annotations: List<PdfAnnotation>,
        outputFile: File
    ) = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw IllegalArgumentException("Cannot read $sourceUri")
        input.use { stream ->
            PDDocument.load(stream).use { doc ->
                doc.pages.forEachIndexed { pageIndex, page ->
                    val onPage = annotations.filter { it.pageIndex == pageIndex }
                    val kept = onPage.mapNotNullTo(HashSet()) { it.sourceIndex }
                    val existing = page.annotations
                    val removed = existing.filterIndexed { i, a -> isEditable(a) && i !in kept }
                    val removedDicts = removed.map { it.cosObject }
                    val result = existing.filterNot { a ->
                        a in removed ||
                            // Drop the popup that belonged to a deleted annotation.
                            (a.subtype == "Popup" && a.cosObject.getCOSDictionary(COSName.PARENT) in removedDicts)
                    }.toMutableList()

                    val mapper = PageMapper(page)
                    for (annotation in onPage.filter { it.sourceIndex == null }) {
                        result += toPdf(annotation, mapper, doc) ?: continue
                    }
                    if (removed.isNotEmpty() || result.size != existing.size) page.annotations = result
                }
                FileOutputStream(outputFile).use { doc.save(it) }
            }
        }
    }

    private fun isEditable(a: PDAnnotation) =
        a.subtype in EDITABLE_SUBTYPES || (a is PDAnnotationRubberStamp && (a.cosObject.containsKey(SIGNATURE_KEY) || a.cosObject.containsKey(SHAPE_KEY)))

    private fun fromPdf(a: PDAnnotation, pageIndex: Int, index: Int, m: PageMapper): PdfAnnotation? {
        if (!isEditable(a)) return null
        val markup = a as? PDAnnotationMarkup
        val color = a.color?.let(::argbOf) ?: 0xFFFFE066.toInt()
        val opacity = markup?.constantOpacity ?: 1f
        val rotation = a.cosObject.getFloat(ROTATION_KEY, 0f)
        val base = PdfAnnotation(
            pageIndex = pageIndex,
            type = AnnotationType.HIGHLIGHT,
            bounds = PdfRect(0f, 0f, 0f, 0f),
            colorArgb = color,
            opacity = opacity,
            text = a.contents ?: "",
            author = markup?.titlePopup ?: "",
            sourceIndex = index,
            rotation = rotation
        )
        return when (a.subtype) {
            PDAnnotationRubberStamp.SUB_TYPE -> {
                val shapeStr = a.cosObject.getString(SIGNATURE_KEY)
                val shape = shapeStr?.let { SignatureShape.decode(it) }
                val customShapeStr = a.cosObject.getString(SHAPE_KEY)
                val customShapeType = customShapeStr?.let { runCatching { AnnotationType.valueOf(it) }.getOrNull() }
                val r = a.rectangle ?: return null
                val p1 = m.fromPdf(r.lowerLeftX, r.upperRightY)
                val p2 = m.fromPdf(r.upperRightX, r.lowerLeftY)
                val bounds = PdfRect(minOf(p1.x, p2.x), minOf(p1.y, p2.y), maxOf(p1.x, p2.x), maxOf(p1.y, p2.y))
                if (shape != null) {
                    val isStamp = a.cosObject.getString(COSName.getPDFName("UltimatePdfType")) == "STAMP"
                    base.copy(
                        type = if (isStamp) AnnotationType.STAMP else AnnotationType.SIGNATURE,
                        bounds = bounds,
                        strokeWidth = shape.strokeWidth,
                        inkPoints = shape.contours,
                        rotation = rotation,
                        text = a.contents ?: ""
                    )
                } else if (customShapeType != null) {
                    base.copy(
                        type = customShapeType,
                        bounds = bounds,
                        strokeWidth = (markup?.borderStyle?.width ?: 2f) / m.displayWidth,
                        rotation = rotation
                    )
                } else {
                    base.copy(
                        type = AnnotationType.STAMP,
                        bounds = bounds,
                        text = a.contents ?: "APPROVED",
                        rotation = rotation
                    )
                }
            }
            PDAnnotationSquareCircle.SUB_TYPE_SQUARE -> {
                val r = a.rectangle ?: return null
                val p1 = m.fromPdf(r.lowerLeftX, r.upperRightY)
                val p2 = m.fromPdf(r.upperRightX, r.lowerLeftY)
                val isRedact = a.contents == "REDACTED"
                base.copy(
                    type = if (isRedact) AnnotationType.REDACTION else AnnotationType.RECTANGLE,
                    bounds = PdfRect(minOf(p1.x, p2.x), minOf(p1.y, p2.y), maxOf(p1.x, p2.x), maxOf(p1.y, p2.y)),
                    strokeWidth = (markup?.borderStyle?.width ?: 2f) / m.displayWidth,
                    rotation = rotation
                )
            }
            PDAnnotationSquareCircle.SUB_TYPE_CIRCLE -> {
                val r = a.rectangle ?: return null
                val p1 = m.fromPdf(r.lowerLeftX, r.upperRightY)
                val p2 = m.fromPdf(r.upperRightX, r.lowerLeftY)
                base.copy(
                    type = AnnotationType.CIRCLE,
                    bounds = PdfRect(minOf(p1.x, p2.x), minOf(p1.y, p2.y), maxOf(p1.x, p2.x), maxOf(p1.y, p2.y)),
                    strokeWidth = (markup?.borderStyle?.width ?: 2f) / m.displayWidth,
                    rotation = rotation
                )
            }
            PDAnnotationLine.SUB_TYPE -> {
                val lineAnnot = a as? PDAnnotationLine ?: return null
                val coords = lineAnnot.line ?: return null
                if (coords.size < 4) return null
                val p1 = m.fromPdf(coords[0], coords[1])
                val p2 = m.fromPdf(coords[2], coords[3])
                val isDoubleArrow = (lineAnnot.startPointEndingStyle == PDAnnotationLine.LE_CLOSED_ARROW || lineAnnot.startPointEndingStyle == PDAnnotationLine.LE_OPEN_ARROW) &&
                    (lineAnnot.endPointEndingStyle == PDAnnotationLine.LE_CLOSED_ARROW || lineAnnot.endPointEndingStyle == PDAnnotationLine.LE_OPEN_ARROW)
                val isArrow = lineAnnot.endPointEndingStyle == PDAnnotationLine.LE_OPEN_ARROW || lineAnnot.endPointEndingStyle == PDAnnotationLine.LE_CLOSED_ARROW
                base.copy(
                    type = when {
                        isDoubleArrow -> AnnotationType.DOUBLE_ARROW
                        isArrow -> AnnotationType.ARROW
                        else -> AnnotationType.LINE
                    },
                    bounds = PdfRect(minOf(p1.x, p2.x), minOf(p1.y, p2.y), maxOf(p1.x, p2.x), maxOf(p1.y, p2.y)),
                    strokeWidth = (markup?.borderStyle?.width ?: 2f) / m.displayWidth,
                    inkPoints = listOf(listOf(p1, p2)),
                    rotation = rotation
                )
            }
            PDAnnotationText.SUB_TYPE -> {
                val r = a.rectangle ?: return null
                val p = m.fromPdf(r.lowerLeftX + 10f, r.upperRightY - 10f)
                base.copy(type = AnnotationType.STICKY_NOTE, bounds = PdfRect(p.x, p.y, p.x, p.y))
            }
            PDAnnotationMarkup.SUB_TYPE_INK -> {
                val inkList = markup?.inkList ?: return null
                val strokes = inkList.map { s -> (0 until s.size / 2).map { i -> m.fromPdf(s[i * 2], s[i * 2 + 1]) } }
                    .filter { it.size > 1 }
                if (strokes.isEmpty()) return null
                val width = markup.borderStyle?.width ?: 1f
                val all = strokes.flatten()
                val brush = a.cosObject.getString(COSName.getPDFName("UltimatePdfBrush")) ?: "ballpoint"
                base.copy(
                    type = AnnotationType.INK_PEN,
                    bounds = PdfRect(all.minOf { it.x }, all.minOf { it.y }, all.maxOf { it.x }, all.maxOf { it.y }),
                    strokeWidth = width / m.displayWidth,
                    inkPoints = strokes,
                    rotation = rotation,
                    brushType = brush
                )
            }
            else -> {
                val q = (a as? PDAnnotationTextMarkup)?.quadPoints ?: return null
                val quads = (0 until q.size / 8).map { i ->
                    val corners = (0 until 4).map { c -> m.fromPdf(q[i * 8 + c * 2], q[i * 8 + c * 2 + 1]) }
                    PdfRect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
                }
                if (quads.isEmpty()) return null
                base.copy(
                    type = when (a.subtype) {
                        PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE -> AnnotationType.UNDERLINE
                        PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT -> AnnotationType.STRIKETHROUGH
                        else -> AnnotationType.HIGHLIGHT
                    },
                    bounds = PdfRect(quads.minOf { it.left }, quads.minOf { it.top }, quads.maxOf { it.right }, quads.maxOf { it.bottom }),
                    quads = quads
                )
            }
        }
    }

    private fun toPdf(annotation: PdfAnnotation, mapper: PageMapper, doc: PDDocument): PDAnnotationMarkup? {
        val pdAnnotation = when (annotation.type) {
            AnnotationType.HIGHLIGHT,
            AnnotationType.UNDERLINE,
            AnnotationType.STRIKETHROUGH -> textMarkup(annotation, mapper)
            AnnotationType.INK_PEN -> ink(annotation, mapper)
            AnnotationType.STICKY_NOTE -> note(annotation, mapper)
            AnnotationType.SIGNATURE -> return signature(annotation, mapper, doc)
            AnnotationType.RECTANGLE -> square(annotation, mapper)
            AnnotationType.CIRCLE -> circle(annotation, mapper)
            AnnotationType.LINE, AnnotationType.ARROW, AnnotationType.DOUBLE_ARROW -> line(annotation, mapper)
            AnnotationType.TRIANGLE,
            AnnotationType.STAR,
            AnnotationType.CLOUD,
            AnnotationType.CALLOUT -> return vectorShape(annotation, mapper, doc)
            AnnotationType.STAMP -> {
                if (annotation.inkPoints.isNotEmpty()) {
                    return signature(annotation, mapper, doc)?.also {
                        it.cosObject.setString(COSName.getPDFName("UltimatePdfType"), "STAMP")
                        it.name = annotation.text.ifEmpty { "Stamp" }
                        it.contents = annotation.text.ifEmpty { "Stamp" }
                    }
                }
                return stamp(annotation, mapper, doc)
            }
            AnnotationType.REDACTION -> redact(annotation, mapper)
            else -> null
        } ?: return null

        pdAnnotation.color = pdColor(annotation.colorArgb)
        pdAnnotation.constantOpacity = annotation.opacity
        pdAnnotation.titlePopup = annotation.author
        pdAnnotation.creationDate = Calendar.getInstance()
        pdAnnotation.setModifiedDate(Calendar.getInstance())
        pdAnnotation.isPrinted = true
        if (annotation.text.isNotEmpty()) pdAnnotation.contents = annotation.text
        pdAnnotation.constructAppearances(doc)
        return pdAnnotation
    }

    private fun textMarkup(a: PdfAnnotation, m: PageMapper): PDAnnotationMarkup? {
        if (a.quads.isEmpty()) return null
        val subtype = when (a.type) {
            AnnotationType.UNDERLINE -> PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
            AnnotationType.STRIKETHROUGH -> PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT
            else -> PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
        }
        val quadPoints = FloatArray(a.quads.size * 8)
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        a.quads.forEachIndexed { i, q ->
            // QuadPoints order used by Acrobat: top-left, top-right, bottom-left, bottom-right.
            val corners = listOf(q.left to q.top, q.right to q.top, q.left to q.bottom, q.right to q.bottom)
            corners.forEachIndexed { c, (x, y) ->
                val (px, py) = m.toPdf(x, y)
                quadPoints[i * 8 + c * 2] = px
                quadPoints[i * 8 + c * 2 + 1] = py
                xs += px
                ys += py
            }
        }
        return PDAnnotationTextMarkup(subtype).apply {
            this.quadPoints = quadPoints
            rectangle = PDRectangle(xs.min() - 1f, ys.min() - 1f, xs.max() - xs.min() + 2f, ys.max() - ys.min() + 2f)
        }
    }

    private fun ink(a: PdfAnnotation, m: PageMapper): PDAnnotationMarkup? {
        val strokes = a.inkPoints.filter { it.size > 1 }
        if (strokes.isEmpty()) return null
        val width = a.strokeWidth * m.displayWidth
        val inkList = strokes.map { stroke ->
            stroke.flatMap { p -> m.toPdf(p.x, p.y).toList() }.toFloatArray()
        }.toTypedArray()
        val xs = inkList.flatMap { s -> s.filterIndexed { i, _ -> i % 2 == 0 } }
        val ys = inkList.flatMap { s -> s.filterIndexed { i, _ -> i % 2 == 1 } }
        return PDAnnotationMarkup().apply {
            cosObject.setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_INK)
            setInkList(inkList)
            borderStyle = PDBorderStyleDictionary().apply { this.width = width }
            if (a.brushType.isNotEmpty()) {
                cosObject.setString(COSName.getPDFName("UltimatePdfBrush"), a.brushType)
            }
            rectangle = PDRectangle(
                xs.min() - width, ys.min() - width,
                xs.max() - xs.min() + width * 2, ys.max() - ys.min() + width * 2
            )
        }
    }

    /**
     * A placed signature becomes a Stamp annotation whose appearance is the vector signature,
     * so every viewer shows it; the shape is also kept under [SIGNATURE_KEY] for re-editing.
     */
    private fun signature(a: PdfAnnotation, m: PageMapper, doc: PDDocument): PDAnnotationRubberStamp? {
        val b = a.bounds
        if (a.inkPoints.isEmpty() || b.width <= 0f || b.height <= 0f) return null
        fun toPage(p: PdfPoint) = m.toPdf(b.left + p.x * b.width, b.top + p.y * b.height)
        val corners = listOf(m.toPdf(b.left, b.top), m.toPdf(b.right, b.bottom))
        val rect = PDRectangle(
            corners.minOf { it.first }, corners.minOf { it.second },
            kotlin.math.abs(corners[0].first - corners[1].first), kotlin.math.abs(corners[0].second - corners[1].second)
        )
        val stamp = PDAnnotationRubberStamp().apply {
            rectangle = rect
            name = "Signature"
            contents = a.text.ifEmpty { "Signature" }
            titlePopup = a.author
            creationDate = Calendar.getInstance()
            setModifiedDate(Calendar.getInstance())
            isPrinted = true
            color = pdColor(a.colorArgb)
        }
        val shape = SignatureShape(a.inkPoints, filled = a.strokeWidth == 0f, strokeWidth = a.strokeWidth, aspect = b.width / b.height)
        stamp.cosObject.setString(SIGNATURE_KEY, shape.encode())

        val appearance = PDAppearanceStream(doc).apply {
            bBox = rect
            resources = PDResources()
        }
        val rgb = pdColor(a.colorArgb).components
        PDAppearanceContentStream(appearance).use { cs ->
            for (contour in a.inkPoints) {
                if (contour.size < 2) continue
                val (x0, y0) = toPage(contour[0])
                cs.moveTo(x0, y0)
                for (i in 1 until contour.size) {
                    val (x, y) = toPage(contour[i])
                    cs.lineTo(x, y)
                }
                if (shape.filled) cs.closePath()
            }
            if (shape.filled) {
                cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2])
                cs.fillEvenOdd()
            } else {
                cs.setStrokingColor(rgb[0], rgb[1], rgb[2])
                // strokeWidth is relative to the box width as displayed (rotated pages swap axes).
                cs.setLineWidth(a.strokeWidth * if (m.isQuarterTurn) rect.height else rect.width)
                cs.setLineCapStyle(1)
                cs.setLineJoinStyle(1)
                cs.stroke()
            }
        }
        stamp.appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance) }
        return stamp
    }

    private fun note(a: PdfAnnotation, m: PageMapper): PDAnnotationMarkup {
        // The note icon is centred on the tapped point.
        val (x, y) = m.toPdf(a.bounds.left, a.bounds.top)
        return PDAnnotationText().apply {
            name = PDAnnotationText.NAME_COMMENT
            rectangle = PDRectangle(x - 10f, y - 10f, 20f, 20f)
        }
    }

    private fun square(a: PdfAnnotation, m: PageMapper): PDAnnotationSquareCircle {
        val b = a.bounds
        val (p1x, p1y) = m.toPdf(b.left, b.top)
        val (p2x, p2y) = m.toPdf(b.right, b.bottom)
        val rect = PDRectangle(minOf(p1x, p2x), minOf(p1y, p2y), kotlin.math.abs(p1x - p2x), kotlin.math.abs(p1y - p2y))
        return PDAnnotationSquareCircle(PDAnnotationSquareCircle.SUB_TYPE_SQUARE).apply {
            rectangle = rect
            borderStyle = PDBorderStyleDictionary().apply { width = a.strokeWidth * m.displayWidth }
        }
    }

    private fun circle(a: PdfAnnotation, m: PageMapper): PDAnnotationSquareCircle {
        val b = a.bounds
        val (p1x, p1y) = m.toPdf(b.left, b.top)
        val (p2x, p2y) = m.toPdf(b.right, b.bottom)
        val rect = PDRectangle(minOf(p1x, p2x), minOf(p1y, p2y), kotlin.math.abs(p1x - p2x), kotlin.math.abs(p1y - p2y))
        return PDAnnotationSquareCircle(PDAnnotationSquareCircle.SUB_TYPE_CIRCLE).apply {
            rectangle = rect
            borderStyle = PDBorderStyleDictionary().apply { width = a.strokeWidth * m.displayWidth }
        }
    }

    private fun line(a: PdfAnnotation, m: PageMapper): PDAnnotationLine? {
        val stroke = a.inkPoints.firstOrNull() ?: return null
        if (stroke.size < 2) return null
        val (x1, y1) = m.toPdf(stroke[0].x, stroke[0].y)
        val (x2, y2) = m.toPdf(stroke[1].x, stroke[1].y)
        return PDAnnotationLine().apply {
            setLine(floatArrayOf(x1, y1, x2, y2))
            if (a.type == AnnotationType.ARROW) {
                endPointEndingStyle = PDAnnotationLine.LE_CLOSED_ARROW
            } else if (a.type == AnnotationType.DOUBLE_ARROW) {
                startPointEndingStyle = PDAnnotationLine.LE_CLOSED_ARROW
                endPointEndingStyle = PDAnnotationLine.LE_CLOSED_ARROW
            }
            rectangle = PDRectangle(minOf(x1, x2) - 8f, minOf(y1, y2) - 8f, kotlin.math.abs(x1 - x2) + 16f, kotlin.math.abs(y1 - y2) + 16f)
            borderStyle = PDBorderStyleDictionary().apply { width = a.strokeWidth * m.displayWidth }
            if (a.rotation != 0f) cosObject.setFloat(ROTATION_KEY, a.rotation)
        }
    }

    private fun stamp(a: PdfAnnotation, m: PageMapper, doc: PDDocument): PDAnnotationRubberStamp {
        val b = a.bounds
        val (p1x, p1y) = m.toPdf(b.left, b.top)
        val (p2x, p2y) = m.toPdf(b.right, b.bottom)
        val rect = PDRectangle(minOf(p1x, p2x), minOf(p1y, p2y), kotlin.math.abs(p1x - p2x), kotlin.math.abs(p1y - p2y))
        val rawText = a.text.ifEmpty { "APPROVED" }
        val safeText = java.text.Normalizer.normalize(rawText, java.text.Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "")
            .filter { it.code in 32..126 || it.code in 160..255 }
            .ifEmpty { "APPROVED" }
        val stamp = PDAnnotationRubberStamp().apply {
            rectangle = rect
            name = safeText
            contents = a.text
            color = pdColor(a.colorArgb)
        }
        if (a.rotation != 0f) stamp.cosObject.setFloat(ROTATION_KEY, a.rotation)
        val appearance = PDAppearanceStream(doc).apply {
            bBox = rect
            resources = PDResources()
        }
        val rgb = pdColor(a.colorArgb).components
        PDAppearanceContentStream(appearance).use { cs ->
            if (a.rotation != 0f) {
                val cx = rect.lowerLeftX + rect.width / 2f
                val cy = rect.lowerLeftY + rect.height / 2f
                cs.transform(Matrix.getRotateInstance(Math.toRadians(-a.rotation.toDouble()), cx, cy))
            }
            cs.setStrokingColor(rgb[0], rgb[1], rgb[2])
            val strokeWidth = (rect.height * 0.05f).coerceIn(1.5f, 4.5f)
            cs.setLineWidth(strokeWidth)
            cs.addRect(rect.lowerLeftX + 2f, rect.lowerLeftY + 2f, rect.width - 4f, rect.height - 4f)
            cs.stroke()

            val innerMargin = (rect.height * 0.08f).coerceIn(3f, 7f)
            if (rect.width > innerMargin * 2.5f && rect.height > innerMargin * 2.5f) {
                cs.setLineWidth((strokeWidth * 0.45f).coerceAtLeast(1f))
                cs.addRect(rect.lowerLeftX + innerMargin, rect.lowerLeftY + innerMargin, rect.width - innerMargin * 2f, rect.height - innerMargin * 2f)
                cs.stroke()
            }

            cs.beginText()
            var fontSize = (rect.height * 0.45f).coerceAtLeast(8f)
            val font = PDType1Font.HELVETICA_BOLD
            val rawWidth = runCatching { (font.getStringWidth(safeText) / 1000f) }.getOrDefault(1f)
            val maxAllowedWidth = (rect.width - innerMargin * 3f).coerceAtLeast(10f)
            if (rawWidth * fontSize > maxAllowedWidth && rawWidth > 0f) {
                fontSize = (maxAllowedWidth / rawWidth).coerceAtLeast(6f)
            }
            cs.setFont(font, fontSize)
            cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2])
            val textWidth = rawWidth * fontSize
            val tx = rect.lowerLeftX + (rect.width - textWidth) / 2f
            val ty = rect.lowerLeftY + (rect.height - fontSize) / 2f
            cs.setTextMatrix(Matrix.getTranslateInstance(tx, ty))
            cs.showText(safeText)
            cs.endText()
        }
        stamp.appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance) }
        return stamp
    }

    private fun vectorShape(a: PdfAnnotation, m: PageMapper, doc: PDDocument): PDAnnotationRubberStamp {
        val b = a.bounds
        val (p1x, p1y) = m.toPdf(b.left, b.top)
        val (p2x, p2y) = m.toPdf(b.right, b.bottom)
        val rect = PDRectangle(minOf(p1x, p2x), minOf(p1y, p2y), kotlin.math.abs(p1x - p2x), kotlin.math.abs(p1y - p2y))
        val stamp = PDAnnotationRubberStamp().apply {
            rectangle = rect
            name = a.type.name
            contents = a.type.name
            color = pdColor(a.colorArgb)
        }
        stamp.cosObject.setString(SHAPE_KEY, a.type.name)
        if (a.rotation != 0f) stamp.cosObject.setFloat(ROTATION_KEY, a.rotation)
        val appearance = PDAppearanceStream(doc).apply {
            bBox = rect
            resources = PDResources()
        }
        val rgb = pdColor(a.colorArgb).components
        PDAppearanceContentStream(appearance).use { cs ->
            if (a.rotation != 0f) {
                val cx = rect.lowerLeftX + rect.width / 2f
                val cy = rect.lowerLeftY + rect.height / 2f
                cs.transform(Matrix.getRotateInstance(Math.toRadians(-a.rotation.toDouble()), cx, cy))
            }
            cs.setStrokingColor(rgb[0], rgb[1], rgb[2])
            cs.setLineWidth((a.strokeWidth * m.displayWidth).coerceAtLeast(1.5f))
            val x = rect.lowerLeftX
            val y = rect.lowerLeftY
            val w = rect.width
            val h = rect.height
            when (a.type) {
                AnnotationType.TRIANGLE -> {
                    cs.moveTo(x + w / 2f, y + h)
                    cs.lineTo(x + w, y)
                    cs.lineTo(x, y)
                    cs.closePath()
                    cs.stroke()
                }
                AnnotationType.STAR -> {
                    val cx = x + w / 2f
                    val cy = y + h / 2f
                    val rOuter = minOf(w, h) / 2f
                    val rInner = rOuter * 0.382f
                    for (i in 0 until 10) {
                        val r = if (i % 2 == 0) rOuter else rInner
                        val angle = Math.toRadians((i * 36 + 90).toDouble())
                        val px = (cx + r * kotlin.math.cos(angle)).toFloat()
                        val py = (cy + r * kotlin.math.sin(angle)).toFloat()
                        if (i == 0) cs.moveTo(px, py) else cs.lineTo(px, py)
                    }
                    cs.closePath()
                    cs.stroke()
                }
                else -> {
                    cs.addRect(x + 2f, y + 2f, w - 4f, h - 4f)
                    cs.stroke()
                }
            }
        }
        stamp.appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance) }
        return stamp
    }


    private fun redact(a: PdfAnnotation, m: PageMapper): PDAnnotationSquareCircle {
        val b = a.bounds
        val (p1x, p1y) = m.toPdf(b.left, b.top)
        val (p2x, p2y) = m.toPdf(b.right, b.bottom)
        val rect = PDRectangle(minOf(p1x, p2x), minOf(p1y, p2y), kotlin.math.abs(p1x - p2x), kotlin.math.abs(p1y - p2y))
        return PDAnnotationSquareCircle(PDAnnotationSquareCircle.SUB_TYPE_SQUARE).apply {
            rectangle = rect
            color = PDColor(floatArrayOf(0f, 0f, 0f), PDDeviceRGB.INSTANCE)
            interiorColor = PDColor(floatArrayOf(0f, 0f, 0f), PDDeviceRGB.INSTANCE)
            contents = "REDACTED"
        }
    }

    private fun pdColor(argb: Int) = PDColor(
        floatArrayOf(
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f
        ),
        PDDeviceRGB.INSTANCE
    )

    private fun argbOf(color: PDColor): Int? {
        val c = color.components
        fun channel(v: Float) = (v.coerceIn(0f, 1f) * 255).toInt()
        return when (c.size) {
            3 -> (0xFF shl 24) or (channel(c[0]) shl 16) or (channel(c[1]) shl 8) or channel(c[2])
            1 -> channel(c[0]).let { g -> (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
            4 -> {
                // CMYK → RGB, good enough for annotation colours.
                val k = 1 - c[3]
                (0xFF shl 24) or (channel((1 - c[0]) * k) shl 16) or (channel((1 - c[1]) * k) shl 8) or channel((1 - c[2]) * k)
            }
            else -> null
        }
    }

    /** Maps between top-left normalized coordinates of the displayed (rotated) page and PDF user space. */
    private class PageMapper(page: PDPage) {
        private val box = page.cropBox
        private val rotation = ((page.rotation % 360) + 360) % 360
        val isQuarterTurn = rotation == 90 || rotation == 270
        val displayWidth: Float = if (isQuarterTurn) box.height else box.width

        fun toPdf(u: Float, v: Float): Pair<Float, Float> {
            // Undo the page rotation: (x, y) are normalized coordinates on the unrotated page.
            val (x, y) = when (rotation) {
                90 -> v to 1f - u
                180 -> 1f - u to 1f - v
                270 -> 1f - v to u
                else -> u to v
            }
            return (box.lowerLeftX + x * box.width) to (box.upperRightY - y * box.height)
        }

        fun fromPdf(px: Float, py: Float): PdfPoint {
            val x = (px - box.lowerLeftX) / box.width
            val y = (box.upperRightY - py) / box.height
            return when (rotation) {
                90 -> PdfPoint(1f - y, x)
                180 -> PdfPoint(1f - x, 1f - y)
                270 -> PdfPoint(y, 1f - x)
                else -> PdfPoint(x, y)
            }
        }
    }
}
