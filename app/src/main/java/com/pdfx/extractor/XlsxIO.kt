package com.pdfx.extractor

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 最小 xlsx 读写，与桌面版模板格式一致：
 * - 写：两个工作表（页面范围 / 填写说明），表头「起始页 / 结束页」，示例 3 / 5 / 9-18
 * - 读：解析第一个工作表，自动定位「起始/结束」表头列，兼容共享字符串与行内字符串
 */
object XlsxIO {

    data class XlsxRow(val start: String, val end: String)

    // ---------------- 写模板 ----------------
    private fun xmlEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    private fun xmlUnescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun colLetter(col: Int): String { // 0 基
        var c = col + 1
        var s = ""
        while (c > 0) { val r = (c - 1) % 26; s = ('A' + r) + s; c = (c - 1) / 26 }
        return s
    }

    private const val CONTENT_TYPES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    private const val ROOT_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private const val WORKBOOK =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<sheets><sheet name=\"页面范围\" sheetId=\"1\" r:id=\"rId1\"/>" +
        "<sheet name=\"填写说明\" sheetId=\"2\" r:id=\"rId2\"/></sheets>" +
        "</workbook>"

    private const val WORKBOOK_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    private const val STYLES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
        "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>" +
        "</styleSheet>"

    private fun StringBuilder.cellStr(col: Int, row: Int, text: String) {
        append("<c r=\"").append(colLetter(col)).append(row)
            .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
        append(xmlEscape(text))
        append("</t></is></c>")
    }

    private fun StringBuilder.cellNum(col: Int, row: Int, v: Int) {
        append("<c r=\"").append(colLetter(col)).append(row).append("\"><v>").append(v).append("</v></c>")
    }

    private fun buildSheet1(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        append("<cols><col min=\"1\" max=\"2\" width=\"14\"/></cols><sheetData>")
        append("<row r=\"1\">")
        cellStr(0, 1, "起始页")
        cellStr(1, 1, "结束页")
        append("</row>")
        append("<row r=\"2\">"); cellNum(0, 2, 3); append("</row>")
        append("<row r=\"3\">"); cellNum(0, 3, 5); append("</row>")
        append("<row r=\"4\">"); cellNum(0, 4, 9); cellNum(1, 4, 18); append("</row>")
        append("</sheetData></worksheet>")
    }

    private fun buildSheet2(): String {
        val lines = arrayOf(
            "PDF 页面提取 - 导入模板填写说明",
            "",
            "1. 「起始页」列：填写要提取范围的起始页码（必填，正整数）。",
            "2. 「结束页」列：填写结束页码；留空表示只提取起始页这一页。",
            "   例：起始页 9、结束页 18 → 提取第 9 到 18 页。",
            "   例：起始页 3、结束页留空 → 只提取第 3 页。",
            "3. 每行代表一组提取范围，可填写任意多行。",
            "4. 第一行为表头，请勿修改表头文字（起始页 / 结束页）。",
            "5. 填写完成后保存，回到软件点击「导入 Excel」上传此文件即可。"
        )
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            append("<cols><col min=\"1\" max=\"1\" width=\"72\"/></cols><sheetData>")
            lines.forEachIndexed { i, line ->
                append("<row r=\"").append(i + 1).append("\">")
                cellStr(0, i + 1, line)
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
    }

    fun writeTemplate(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun add(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add("[Content_Types].xml", CONTENT_TYPES)
            add("_rels/.rels", ROOT_RELS)
            add("xl/workbook.xml", WORKBOOK)
            add("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            add("xl/styles.xml", STYLES)
            add("xl/worksheets/sheet1.xml", buildSheet1())
            add("xl/worksheets/sheet2.xml", buildSheet2())
        }
    }

    // ---------------- 读 ----------------
    // 简易 XML 扫描：取 <tag ...>...</tag> 的内容
    private fun findTag(xml: String, tag: String, from: Int): Triple<Int, Int, String>? {
        val open = "<$tag"
        val p = xml.indexOf(open, from)
        if (p < 0) return null
        val gt = xml.indexOf('>', p)
        if (gt < 0) return null
        var attrs = xml.substring(p + open.length, gt)
        if (attrs.endsWith("/")) {
            attrs = attrs.dropLast(1)
            return Triple(gt + 1, gt + 1, attrs) // 自闭合
        }
        val close = "</$tag>"
        val ce = xml.indexOf(close, gt + 1)
        if (ce < 0) return null
        return Triple(gt + 1, ce, attrs)
    }

    private fun attrValue(attrs: String, name: String): String {
        val key = "$name=\""
        val p = attrs.indexOf(key)
        if (p < 0) return ""
        val e = attrs.indexOf('"', p + key.length)
        if (e < 0) return ""
        return attrs.substring(p + key.length, e)
    }

    private fun colFromRef(ref: String): Int {
        var col = 0
        for (c in ref) {
            when {
                c in 'A'..'Z' -> col = col * 26 + (c - 'A' + 1)
                c in 'a'..'z' -> col = col * 26 + (c - 'a' + 1)
                else -> break
            }
        }
        return col - 1
    }

    private fun readZipEntry(zip: ZipFile, name: String): String? {
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    private data class Cell(val col: Int, val text: String)

    /** 从 xlsx 输入流读取页面范围。抛异常时 message 为用户可读错误。 */
    fun readRanges(input: InputStream): List<XlsxRow> {
        // 先落到临时文件（ZipFile 需要随机访问）
        val tmp = File.createTempFile("pdfx_import", ".xlsx")
        try {
            input.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
            val zip = try {
                ZipFile(tmp)
            } catch (e: Exception) {
                throw IllegalArgumentException("无法打开 Excel 文件（不是有效的 .xlsx）")
            }
            zip.use { z ->
                val shared = readZipEntry(z, "xl/sharedStrings.xml") ?: ""
                // 解析共享字符串
                val ss = mutableListOf<String>()
                var from = 0
                while (true) {
                    val si = findTag(shared, "si", from) ?: break
                    val siXml = shared.substring(si.first, si.second)
                    val text = StringBuilder()
                    var tf = 0
                    while (true) {
                        val t = findTag(siXml, "t", tf) ?: break
                        text.append(siXml.substring(t.first, t.second))
                        tf = t.second
                    }
                    ss.add(xmlUnescape(text.toString()))
                    from = si.second
                }

                val sheet = readZipEntry(z, "xl/worksheets/sheet1.xml")
                    ?: throw IllegalArgumentException("Excel 文件中找不到工作表数据")

                // 逐行解析
                val grid = mutableListOf<List<Cell>>()
                var f = 0
                while (true) {
                    val rowTag = findTag(sheet, "row", f) ?: break
                    val rowXml = sheet.substring(rowTag.first, rowTag.second)
                    val row = mutableListOf<Cell>()
                    var cf = 0
                    while (true) {
                        val cTag = findTag(rowXml, "c", cf) ?: break
                        val cellXml = rowXml.substring(cTag.first, cTag.second)
                        val ref = attrValue(cTag.third, "r")
                        val type = attrValue(cTag.third, "t")
                        var text = ""
                        val v = findTag(cellXml, "v", 0)
                        if (v != null) {
                            text = cellXml.substring(v.first, v.second)
                            if (type == "s") {
                                val idx = text.toIntOrNull() ?: -1
                                if (idx in ss.indices) text = ss[idx]
                            }
                        } else if (type == "inlineStr") {
                            val t = findTag(cellXml, "t", 0)
                            if (t != null) text = cellXml.substring(t.first, t.second)
                        }
                        row.add(Cell(colFromRef(ref), xmlUnescape(text)))
                        cf = cTag.second
                    }
                    grid.add(row)
                    f = rowTag.second
                }
                if (grid.isEmpty()) throw IllegalArgumentException("Excel 表格内容为空")

                // 定位表头
                var headerRow = -1
                var startCol = -1
                var endCol = -1
                for (i in grid.indices.take(10)) {
                    for (c in grid[i]) {
                        if (c.text.contains("起始")) startCol = c.col
                        if (c.text.contains("结束")) endCol = c.col
                    }
                    if (startCol >= 0) { headerRow = i; break }
                }
                val dataStart: Int
                val sC: Int
                val eC: Int
                if (headerRow >= 0) { dataStart = headerRow + 1; sC = startCol; eC = endCol }
                else { dataStart = 0; sC = 0; eC = 1 }

                fun getCell(row: List<Cell>, col: Int): String =
                    row.firstOrNull { it.col == col }?.text ?: ""

                val rows = mutableListOf<XlsxRow>()
                for (i in dataStart until grid.size) {
                    var sv = getCell(grid[i], sC).trim()
                    var ev = if (eC >= 0) getCell(grid[i], eC).trim() else ""
                    if (sv.isEmpty()) continue
                    // 数字单元格可能带小数（如 "3.0"）
                    sv = sv.removeSuffix(".0")
                    ev = ev.removeSuffix(".0")
                    if (!sv.all { it.isDigit() }) continue
                    if (ev.isNotEmpty() && !ev.all { it.isDigit() }) ev = ""
                    rows.add(XlsxRow(sv, ev))
                }
                if (rows.isEmpty()) {
                    throw IllegalArgumentException("未读取到有效数据：请确认第一列为「起始页」、第二列为「结束页」的数字")
                }
                return rows
            }
        } finally {
            tmp.delete()
        }
    }
}
