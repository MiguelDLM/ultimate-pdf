package org.ultimatepdf.core.export

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One piece of a Word document, in reading order. */
sealed interface DocBlock {
    class Paragraph(val text: String, val sizePt: Float, val bold: Boolean = false) : DocBlock
    class Picture(val bytes: ByteArray, val isPng: Boolean, val widthPt: Float, val heightPt: Float) : DocBlock
    object PageBreak : DocBlock
}

internal fun xmlEscape(text: String): String {
    val sb = StringBuilder(text.length)
    for (c in text) {
        when {
            c == '&' -> sb.append("&amp;")
            c == '<' -> sb.append("&lt;")
            c == '>' -> sb.append("&gt;")
            c == '"' -> sb.append("&quot;")
            c == '\t' || c == '\n' || c == '\r' || c.code >= 0x20 -> sb.append(c)
        }
    }
    return sb.toString()
}

/** Writes a minimal but valid .docx (Office Open XML) with paragraphs and inline pictures. */
object DocxWriter {

    private const val EMU_PER_PT = 12700L
    private const val NS_MAIN = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun write(blocks: List<DocBlock>, pageWidthPt: Float, pageHeightPt: Float, output: File) {
        output.outputStream().use { write(blocks, pageWidthPt, pageHeightPt, it) }
    }

    fun write(blocks: List<DocBlock>, pageWidthPt: Float, pageHeightPt: Float, output: OutputStream) {
        val marginPt = 54f
        val textWidthPt = (pageWidthPt - 2 * marginPt).coerceAtLeast(100f)
        val relationships = StringBuilder()
        val media = mutableListOf<Pair<String, ByteArray>>()
        val body = StringBuilder()
        var pictureId = 0

        for (block in blocks) {
            when (block) {
                is DocBlock.Paragraph -> {
                    val half = (block.sizePt * 2).toInt().coerceIn(8, 144)
                    body.append("<w:p><w:pPr><w:spacing w:after=\"120\"/></w:pPr>")
                    val lines = block.text.split('\n')
                    body.append("<w:r><w:rPr>")
                    if (block.bold) body.append("<w:b/>")
                    body.append("<w:sz w:val=\"$half\"/></w:rPr>")
                    lines.forEachIndexed { i, line ->
                        if (i > 0) body.append("<w:br/>")
                        body.append("<w:t xml:space=\"preserve\">").append(xmlEscape(line)).append("</w:t>")
                    }
                    body.append("</w:r></w:p>")
                }
                is DocBlock.Picture -> {
                    pictureId++
                    val ext = if (block.isPng) "png" else "jpeg"
                    val name = "image$pictureId.$ext"
                    media += "word/media/$name" to block.bytes
                    relationships.append(
                        "<Relationship Id=\"rIdImg$pictureId\" Type=\"$NS_REL/image\" Target=\"media/$name\"/>"
                    )
                    val scale = if (block.widthPt > textWidthPt) textWidthPt / block.widthPt else 1f
                    val cx = (block.widthPt * scale * EMU_PER_PT).toLong().coerceAtLeast(EMU_PER_PT)
                    val cy = (block.heightPt * scale * EMU_PER_PT).toLong().coerceAtLeast(EMU_PER_PT)
                    body.append(
                        "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
                            "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$pictureId\" name=\"Picture $pictureId\"/>" +
                            "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
                            "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                            "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                            "<pic:nvPicPr><pic:cNvPr id=\"$pictureId\" name=\"$name\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                            "<pic:blipFill><a:blip r:embed=\"rIdImg$pictureId\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
                            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
                            "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
                    )
                }
                DocBlock.PageBreak -> body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            }
        }

        val w = (pageWidthPt * 20).toInt()
        val h = (pageHeightPt * 20).toInt()
        val margin = (marginPt * 20).toInt()
        val document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"$NS_MAIN\" xmlns:r=\"$NS_REL\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">" +
            "<w:body>$body<w:sectPr><w:pgSz w:w=\"$w\" w:h=\"$h\"/>" +
            "<w:pgMar w:top=\"$margin\" w:right=\"$margin\" w:bottom=\"$margin\" w:left=\"$margin\" " +
            "w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"

        ZipOutputStream(output).use { zip ->
            zip.entry(
                "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
                    "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
                    "<Override PartName=\"/word/document.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                    "</Types>"
            )
            zip.entry(
                "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"$NS_REL/officeDocument\" Target=\"word/document.xml\"/></Relationships>"
            )
            zip.entry(
                "word/_rels/document.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$relationships</Relationships>"
            )
            zip.entry("word/document.xml", document)
            media.forEach { (path, bytes) -> zip.entry(path, bytes) }
        }
    }
}

/** Writes a minimal .xlsx: one sheet per table, text and numbers stored as typed cells. */
object XlsxWriter {

    class Sheet(val name: String, val rows: List<List<String>>)

    fun write(sheets: List<Sheet>, output: File) {
        require(sheets.isNotEmpty())
        val names = uniqueNames(sheets.map { it.name })
        ZipOutputStream(output.outputStream()).use { zip ->
            zip.entry(
                "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                    sheets.indices.joinToString("") {
                        "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" " +
                            "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    } + "</Types>"
            )
            zip.entry(
                "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"
            )
            zip.entry(
                "xl/workbook.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
                    names.mapIndexed { i, n -> "<sheet name=\"${xmlEscape(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") +
                    "</sheets></workbook>"
            )
            zip.entry(
                "xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    sheets.indices.joinToString("") {
                        "<Relationship Id=\"rId${it + 1}\" " +
                            "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" " +
                            "Target=\"worksheets/sheet${it + 1}.xml\"/>"
                    } + "</Relationships>"
            )
            sheets.forEachIndexed { i, sheet -> zip.entry("xl/worksheets/sheet${i + 1}.xml", sheetXml(sheet)) }
        }
    }

    private fun sheetXml(sheet: Sheet): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        sheet.rows.forEachIndexed { r, row ->
            sb.append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, cell ->
                if (cell.isBlank()) return@forEachIndexed
                val ref = columnName(c) + (r + 1)
                val number = asNumber(cell)
                if (number != null) {
                    sb.append("<c r=\"$ref\"><v>$number</v></c>")
                } else {
                    sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(xmlEscape(cell)).append("</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    /** A plain number such as "1234.5" or "-7"; anything with symbols or separators stays text. */
    private fun asNumber(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 15 || (t.length > 1 && t[0] == '0' && t[1].isDigit())) return null
        return if (Regex("-?\\d+(\\.\\d+)?").matches(t)) t else null
    }

    private fun columnName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    /** Sheet names: at most 31 characters, none of []:*?/\ and unique. */
    private fun uniqueNames(raw: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return raw.map { name ->
            val base = name.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().take(28).ifEmpty { "Sheet" }
            var candidate = base
            var n = 2
            while (!used.add(candidate.lowercase())) candidate = "$base ${n++}"
            candidate
        }
    }
}

private fun ZipOutputStream.entry(path: String, text: String) = entry(path, text.toByteArray(Charsets.UTF_8))

private fun ZipOutputStream.entry(path: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(path))
    write(bytes)
    closeEntry()
}
