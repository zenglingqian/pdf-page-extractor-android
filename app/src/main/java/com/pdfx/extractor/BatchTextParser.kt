package com.pdfx.extractor

/**
 * 批量文本解析：与桌面版规则一致。
 * 每行一组 "起始页(\t结束页)?"，结束页可空；跳过空行与非数字行。
 */
object BatchTextParser {

    data class RangeRow(val start: String, val end: String)

    fun parse(text: String): List<RangeRow> {
        val rows = mutableListOf<RangeRow>()
        for (rawLine in text.replace('\r', '\n').split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val cols = line.split('\t').map { it.trim() }
            if (cols.isEmpty() || cols[0].isEmpty()) continue
            if (!cols[0].all { it.isDigit() }) continue
            var e = if (cols.size > 1) cols[1] else ""
            if (e.isNotEmpty() && !e.all { it.isDigit() }) e = ""
            rows.add(RangeRow(cols[0], e))
        }
        return rows
    }
}
