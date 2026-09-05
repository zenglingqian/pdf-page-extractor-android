package com.pdfx.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CoreLogicTest {

    @Test
    fun batchTextParser_basic() {
        // 模拟从 Excel 复制两列：3 / 5 / 9-18
        val text = "3\t\n5\t\n9\t18\n"
        val rows = BatchTextParser.parse(text)
        assertEquals(3, rows.size)
        assertEquals("3", rows[0].start); assertEquals("", rows[0].end)
        assertEquals("5", rows[1].start); assertEquals("", rows[1].end)
        assertEquals("9", rows[2].start); assertEquals("18", rows[2].end)
    }

    @Test
    fun batchTextParser_skipsInvalid() {
        val text = "起始页\t结束页\n3\t\nabc\t5\n7\t8\n\n"
        val rows = BatchTextParser.parse(text)
        assertEquals(2, rows.size)
        assertEquals("3", rows[0].start)
        assertEquals("7", rows[1].start); assertEquals("8", rows[1].end)
    }

    @Test
    fun batchTextParser_singleNumberOnly() {
        val rows = BatchTextParser.parse("42")
        assertEquals(1, rows.size)
        assertEquals("42", rows[0].start)
        assertEquals("", rows[0].end)
    }

    @Test
    fun xlsx_templateRoundTrip() {
        // 写模板
        val bos = ByteArrayOutputStream()
        XlsxIO.writeTemplate(bos)
        // 读回
        val rows = XlsxIO.readRanges(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(3, rows.size)
        assertEquals("3", rows[0].start); assertEquals("", rows[0].end)
        assertEquals("5", rows[1].start); assertEquals("", rows[1].end)
        assertEquals("9", rows[2].start); assertEquals("18", rows[2].end)
    }

    // ---------------- 手写测试 PDF 生成器（无第三方依赖） ----------------

    /** 生成 pageCount 页的经典结构 PDF（未压缩，xref 表）。 */
    private fun makeTestPdf(pageCount: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) { for (c in s) out.write(c.code and 0xFF) }
        var next = 3
        val pageNums = IntArray(pageCount)
        val contentNums = IntArray(pageCount)
        val fontNums = IntArray(pageCount)
        for (i in 0 until pageCount) {
            pageNums[i] = next++; contentNums[i] = next++; fontNums[i] = next++
        }
        val maxObj = next - 1
        val offsets = IntArray(maxObj + 1)

        w("%PDF-1.4\n")
        // Catalog
        offsets[1] = out.size(); w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        // Pages
        offsets[2] = out.size()
        w("2 0 obj\n<< /Type /Pages /Kids [")
        for (i in 0 until pageCount) { if (i > 0) w(" "); w("${pageNums[i]} 0 R") }
        w("] /Count $pageCount >>\nendobj\n")
        // 每页
        for (i in 0 until pageCount) {
            val content = "BT /F1 24 Tf 72 720 Td (Page ${i + 1}) Tj ET"
            offsets[pageNums[i]] = out.size()
            w("${pageNums[i]} 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 ${fontNums[i]} 0 R >> >> " +
                "/Contents ${contentNums[i]} 0 R >>\nendobj\n")
            offsets[contentNums[i]] = out.size()
            w("${contentNums[i]} 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")
            offsets[fontNums[i]] = out.size()
            w("${fontNums[i]} 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        }
        val xrefPos = out.size()
        w("xref\n0 ${maxObj + 1}\n0000000000 65535 f \n")
        for (i in 1..maxObj) w("%010d 00000 n \n".format(offsets[i]))
        w("trailer\n<< /Size ${maxObj + 1} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        return out.toByteArray()
    }

    @Test
    fun pdfEngine_pageCount() {
        val bytes = makeTestPdf(20)
        assertEquals(20, PdfEngine.getPageCount(bytes))
    }

    @Test
    fun pdfEngine_extractPages() {
        val bytes = makeTestPdf(20)
        // 提取第 3 页、第 5 页、第 9~18 页 => 共 12 页
        val out = ByteArrayOutputStream()
        val count = PdfEngine.extractPages(
            bytes, out,
            listOf(
                PdfEngine.PageRange(3, 3),
                PdfEngine.PageRange(5, 5),
                PdfEngine.PageRange(9, 18)
            )
        )
        assertEquals(12, count)
        // 输出 PDF 应可再次解析且页数为 12
        assertEquals(12, PdfEngine.getPageCount(out.toByteArray()))
    }

    @Test
    fun pdfEngine_extractDedup() {
        val bytes = makeTestPdf(10)
        val out = ByteArrayOutputStream()
        // 重叠范围应去重：1-5 与 3-7 => 7 页
        val count = PdfEngine.extractPages(
            bytes, out,
            listOf(PdfEngine.PageRange(1, 5), PdfEngine.PageRange(3, 7))
        )
        assertEquals(7, count)
        assertEquals(7, PdfEngine.getPageCount(out.toByteArray()))
    }

    @Test
    fun pdfEngine_outOfRangeThrows() {
        val bytes = makeTestPdf(5)
        var thrown = false
        try {
            PdfEngine.extractPages(bytes, ByteArrayOutputStream(), listOf(PdfEngine.PageRange(6, 6)))
        } catch (e: PdfEngine.PdfException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    // ---------------- 真实 PDF 验证（桌面版测试样本） ----------------

    private fun realPdf(name: String): java.io.File? {
        // 单元测试工作目录为 app/ 模块目录
        val f = java.io.File("../../pdf-page-extractor/tests/real/$name")
        return if (f.exists()) f else null
    }

    @Test
    fun pdfEngine_realFiles() {
        for (name in listOf("junk.pdf", "nested.pdf", "xrefstream.pdf")) {
            val f = realPdf(name) ?: continue
            val bytes = f.readBytes()
            val count = PdfEngine.getPageCount(bytes)
            assertTrue("$name 页数应大于 0", count > 0)
            // 提取第一页并验证输出可解析
            val out = ByteArrayOutputStream()
            val n = PdfEngine.extractPages(bytes, out, listOf(PdfEngine.PageRange(1, 1)))
            assertEquals(1, n)
            assertEquals(1, PdfEngine.getPageCount(out.toByteArray()))
        }
    }

    @Test
    fun pdfEngine_realTenPages() {
        val f = java.io.File("../../pdf-page-extractor/tests/test_10pages.pdf")
        if (!f.exists()) return
        val bytes = f.readBytes()
        assertEquals(10, PdfEngine.getPageCount(bytes))
        val out = ByteArrayOutputStream()
        val n = PdfEngine.extractPages(
            bytes, out,
            listOf(PdfEngine.PageRange(3, 3), PdfEngine.PageRange(5, 5), PdfEngine.PageRange(9, 10))
        )
        assertEquals(4, n)
        assertEquals(4, PdfEngine.getPageCount(out.toByteArray()))
    }
}
