package com.pdfx.extractor

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.floor

/**
 * PDF 页面提取核心（纯 Kotlin 手写解析器，无任何第三方依赖）。
 * 与桌面版 src/pdfextract.cpp 逻辑一致：
 * 支持经典 xref 表、PDF 1.5 xref 流与对象流（ObjStm）、FlateDecode/ASCIIHexDecode、
 * PNG 反过滤（Predictor>=10）。不支持加密 PDF（抛出明确错误）。
 * 提取结果保留原始页面内容与质量，不做重新渲染。
 */
object PdfEngine {

    data class PageRange(val start: Int, val end: Int)

    class PdfException(message: String) : Exception(message)

    // ---------------- 对象模型 ----------------
    enum class T { NULL, BOOL, NUM, STR, NAME, ARR, DICT, STREAM, REF }

    class Obj(var t: T = T.NULL) {
        var b = false
        var n = 0.0
        var s = ""                              // STR / NAME（STR 以 ISO-8859-1 保存原始字节）
        val a = ArrayList<Obj?>()               // ARR
        val d = ArrayList<Pair<String, Obj?>>() // DICT（保序）
        var raw = ByteArray(0)                  // 已物化的字节（ObjStm 解析 / 解码结果）
        internal var srcRef: Src? = null                 // STREAM 原始字节的懒引用（大文件不物化）
        var rawStart = 0
        var rawLen = 0
        val rawLength: Int get() = if (srcRef == null) raw.size else rawLen
        fun rawBytes(): ByteArray = if (srcRef == null) raw else srcRef!!.slice(rawStart, rawLen)
        fun writeRawTo(out: OutputStream) {
            val s = srcRef
            if (s == null) out.write(raw) else s.writeTo(rawStart, rawLen, out)
        }
        var rn = 0                              // REF 对象号
        var rg = 0                              // REF 代号

        fun get(k: String): Obj? = d.firstOrNull { it.first == k }?.second
        fun has(k: String): Boolean = get(k) != null
        fun set(k: String, v: Obj?) {
            for (i in d.indices) if (d[i].first == k) { d[i] = k to v; return }
            d.add(k to v)
        }
        fun erase(k: String) {
            var i = d.size - 1
            while (i >= 0) { if (d[i].first == k) d.removeAt(i); i-- }
        }
    }

    private fun mkNull() = Obj(T.NULL)
    private fun mkBool(v: Boolean) = Obj(T.BOOL).apply { b = v }
    private fun mkNum(v: Double) = Obj(T.NUM).apply { n = v }
    private fun mkStr(v: String) = Obj(T.STR).apply { s = v }
    private fun mkName(v: String) = Obj(T.NAME).apply { s = v }
    private fun mkArr() = Obj(T.ARR)
    private fun mkDict() = Obj(T.DICT)
    private fun mkRef(n: Int, g: Int) = Obj(T.REF).apply { rn = n; rg = g }

    // ---------------- 字节源 ----------------
    // 抽象字节源：小文件走内存（MemSrc），大文件走内存映射（MmapSrc）。
    // 解析器按需读取，避免把整个 PDF 读进 Java 堆导致 OOM。
    internal abstract class Src {
        abstract val n: Int
        abstract operator fun get(i: Int): Int
        abstract fun slice(start: Int, len: Int): ByteArray
        abstract fun writeTo(start: Int, len: Int, out: OutputStream)
        open fun close() {}
    }

    private class MemSrc(val data: ByteArray) : Src() {
        override val n: Int get() = data.size
        override fun get(i: Int): Int = data[i].toInt() and 0xFF
        override fun slice(start: Int, len: Int): ByteArray = data.copyOfRange(start, start + len)
        override fun writeTo(start: Int, len: Int, out: OutputStream) = out.write(data, start, len)
    }

    private class MmapSrc(file: File) : Src() {
        private val raf = java.io.RandomAccessFile(file, "r")
        private val buf: java.nio.MappedByteBuffer
        override val n: Int get() = buf.capacity()

        init {
            val sz = raf.length()
            if (sz > Int.MAX_VALUE - 8) {
                raf.close()
                throw PdfException("文件超过 2GB，暂不支持")
            }
            buf = raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, sz)
        }

        override fun get(i: Int): Int = buf.get(i).toInt() and 0xFF
        override fun slice(start: Int, len: Int): ByteArray {
            val out = ByteArray(len)
            val dup = buf.duplicate()
            dup.position(start)
            dup.get(out, 0, len)
            return out
        }

        override fun writeTo(start: Int, len: Int, out: OutputStream) {
            val dup = buf.duplicate()
            dup.position(start)
            val scratch = ByteArray(65536)
            var left = len
            while (left > 0) {
                val k = minOf(scratch.size, left)
                dup.get(scratch, 0, k)
                out.write(scratch, 0, k)
                left -= k
            }
        }

        override fun close() { try { raf.close() } catch (_: Exception) {} }
    }

    // 边写边落盘的计数输出流（避免把整个输出缓冲在内存里）
    private class CountingOut(val real: OutputStream) : OutputStream() {
        var count = 0
        override fun write(b: Int) { real.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { real.write(b, off, len); count += len }
    }

    // ---------------- 字节工具 ----------------
    private fun isWS(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
    private fun isDelim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
        c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code
    private fun isDigitC(c: Int) = c in '0'.code..'9'.code

    private fun indexOfPattern(data: Src, pat: ByteArray, from: Int = 0): Int {
        val last = data.n - pat.size
        if (last < from) return -1
        outer@ for (i in from..last) {
            for (j in pat.indices) if (data[i + j] != (pat[j].toInt() and 0xFF)) continue@outer
            return i
        }
        return -1
    }

    private fun lastIndexOfPattern(data: Src, pat: ByteArray): Int {
        outer@ for (i in data.n - pat.size downTo 0) {
            for (j in pat.indices) if (data[i + j] != (pat[j].toInt() and 0xFF)) continue@outer
            return i
        }
        return -1
    }

    private class Reader(val src: Src, var pos: Int = 0) {
        val n: Int get() = src.n
        fun eof() = pos >= n
        fun cur(): Int = if (pos < n) src[pos] else 0
        fun at(i: Int): Int = if (i in 0 until n) src[i] else 0

        fun skipWs() {
            while (pos < n) {
                val c = src[pos]
                if (isWS(c)) { pos++; continue }
                if (c == '%'.code) {
                    while (pos < n && src[pos] != '\n'.code && src[pos] != '\r'.code) pos++
                    continue
                }
                break
            }
        }

        fun match(kw: String): Boolean {
            val l = kw.length
            if (pos + l > n) return false
            for (i in 0 until l) {
                if (src[pos + i] != kw[i].code) return false
            }
            if (pos + l < n) {
                val c = src[pos + l]
                if (!isWS(c) && !isDelim(c)) return false
            }
            pos += l
            return true
        }
    }

    private fun hexVal(c: Int): Int = when {
        c in '0'.code..'9'.code -> c - '0'.code
        c in 'a'.code..'f'.code -> c - 'a'.code + 10
        c in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> -1
    }

    private fun parseNumber(r: Reader, isInt: BooleanArray): Double {
        r.skipWs()
        val tok = StringBuilder()
        while (!r.eof()) {
            val c = r.cur()
            if (isDigitC(c) || c == '+'.code || c == '-'.code || c == '.'.code) {
                tok.append(c.toChar()); r.pos++
            } else break
        }
        isInt[0] = !tok.contains('.')
        if (tok.isEmpty()) return 0.0
        return tok.toString().toDoubleOrNull() ?: 0.0
    }

    private fun parseInt(r: Reader): Long {
        r.skipWs()
        var sign = 1L
        var v = 0L
        var any = false
        if (r.cur() == '+'.code) r.pos++
        else if (r.cur() == '-'.code) { sign = -1; r.pos++ }
        while (!r.eof() && isDigitC(r.cur())) {
            v = v * 10 + (r.cur() - '0'.code)
            r.pos++
            any = true
        }
        return if (any) sign * v else 0
    }

    // ---------------- 对象解析 ----------------
    private fun parseName(r: Reader): Obj {
        r.pos++ // 跳过 '/'
        val sb = StringBuilder()
        while (!r.eof()) {
            val c = r.cur()
            if (isWS(c) || isDelim(c)) break
            if (c == '#'.code && r.pos + 2 < r.n) {
                val h = hexVal(r.at(r.pos + 1)); val l = hexVal(r.at(r.pos + 2))
                if (h >= 0 && l >= 0) {
                    sb.append((h * 16 + l).toChar())
                    r.pos += 3
                    continue
                }
            }
            sb.append(c.toChar())
            r.pos++
        }
        return mkName(sb.toString())
    }

    private fun parseLiteralString(r: Reader): Obj {
        r.pos++ // '('
        val sb = StringBuilder()
        var depth = 1
        while (!r.eof()) {
            val c = r.cur()
            if (c == '\\'.code) {
                r.pos++
                if (r.eof()) break
                val e = r.cur()
                when {
                    e == 'n'.code -> { sb.append('\n'); r.pos++ }
                    e == 'r'.code -> { sb.append('\r'); r.pos++ }
                    e == 't'.code -> { sb.append('\t'); r.pos++ }
                    e == 'b'.code -> { sb.append('\b'); r.pos++ }
                    e == 'f'.code -> { sb.append('\u000C'); r.pos++ }
                    e == '('.code -> { sb.append('('); r.pos++ }
                    e == ')'.code -> { sb.append(')'); r.pos++ }
                    e == '\\'.code -> { sb.append('\\'); r.pos++ }
                    e == '\r'.code -> { r.pos++; if (r.cur() == '\n'.code) r.pos++ }
                    e == '\n'.code -> { r.pos++ }
                    e in '0'.code..'7'.code -> {
                        var v = 0; var cnt = 0
                        while (cnt < 3 && r.cur() in '0'.code..'7'.code) {
                            v = v * 8 + (r.cur() - '0'.code); r.pos++; cnt++
                        }
                        sb.append(v.toChar())
                    }
                    else -> { sb.append(e.toChar()); r.pos++ }
                }
                continue
            }
            if (c == '('.code) { depth++; sb.append('('); r.pos++; continue }
            if (c == ')'.code) {
                depth--
                r.pos++
                if (depth == 0) break
                sb.append(')')
                continue
            }
            sb.append(c.toChar())
            r.pos++
        }
        return mkStr(sb.toString())
    }

    private fun parseHexString(r: Reader): Obj {
        r.pos++ // '<'
        val hex = StringBuilder()
        while (!r.eof() && r.cur() != '>'.code) {
            if (!isWS(r.cur())) hex.append(r.cur().toChar())
            r.pos++
        }
        if (!r.eof()) r.pos++ // '>'
        if (hex.length % 2 != 0) hex.append('0')
        val sb = StringBuilder(hex.length / 2)
        var i = 0
        while (i + 1 < hex.length) {
            val h = hexVal(hex[i].code); val l = hexVal(hex[i + 1].code)
            sb.append(((if (h < 0) 0 else h) * 16 + (if (l < 0) 0 else l)).toChar())
            i += 2
        }
        return mkStr(sb.toString())
    }

    private fun parseArray(r: Reader): Obj {
        r.pos++ // '['
        val arr = mkArr()
        while (true) {
            r.skipWs()
            if (r.eof()) break
            if (r.cur() == ']'.code) { r.pos++; break }
            val v = parseObject(r) ?: break
            arr.a.add(v)
        }
        return arr
    }

    private fun parseDict(r: Reader): Obj {
        r.pos += 2 // '<<'
        val d = mkDict()
        while (true) {
            r.skipWs()
            if (r.eof()) break
            if (r.cur() == '>'.code && r.at(r.pos + 1) == '>'.code) { r.pos += 2; break }
            if (r.cur() != '/'.code) { r.pos++; continue } // 容错
            val key = parseName(r)
            r.skipWs()
            val v = parseObject(r) ?: mkNull()
            d.set(key.s, v)
        }
        return d
    }

    private fun parseObject(r: Reader): Obj? {
        r.skipWs()
        if (r.eof()) return null
        val c = r.cur()
        if (c == '<'.code) {
            if (r.at(r.pos + 1) == '<'.code) {
                val d = parseDict(r)
                // 检查是否为流对象
                r.skipWs()
                if (!r.eof() && r.cur() == 's'.code && r.match("stream")) {
                    // stream 后跟 \r\n 或 \n
                    if (!r.eof() && r.cur() == '\r'.code) r.pos++
                    if (!r.eof() && r.cur() == '\n'.code) r.pos++
                    val lObj = d.get("Length")
                    val len = if (lObj != null && lObj.t == T.NUM) lObj.n.toLong() else 0L
                    d.t = T.STREAM
                    if (len > 0 && len <= Int.MAX_VALUE && r.pos + len <= r.n) {
                        d.srcRef = r.src; d.rawStart = r.pos; d.rawLen = len.toInt()
                        r.pos += len.toInt()
                    } else {
                        // 长度不可靠：扫描 endstream
                        var e = indexOfPattern(r.src, "endstream".toByteArray(Charsets.US_ASCII), r.pos)
                        if (e < 0) e = r.n
                        var end = e
                        // 去掉末尾换行
                        while (end > r.pos && (r.src[end - 1] == '\n'.code || r.src[end - 1] == '\r'.code)) end--
                        d.srcRef = r.src; d.rawStart = r.pos; d.rawLen = end - r.pos
                        r.pos = e
                    }
                    r.match("endstream")
                }
                return d
            }
            return parseHexString(r)
        }
        if (c == '('.code) return parseLiteralString(r)
        if (c == '/'.code) return parseName(r)
        if (c == '['.code) return parseArray(r)
        if (c == 't'.code) {
            if (r.match("true")) return mkBool(true)
            r.pos++; return mkNull()
        }
        if (c == 'f'.code) {
            if (r.match("false")) return mkBool(false)
            r.pos++; return mkNull()
        }
        if (c == 'n'.code) {
            if (r.match("null")) return mkNull()
            r.pos++; return mkNull()
        }
        if (isDigitC(c) || c == '+'.code || c == '-'.code || c == '.'.code) {
            val isInt = BooleanArray(1)
            val n1 = parseNumber(r, isInt)
            if (isInt[0]) {
                // 尝试 "N G R"
                var p = r.pos
                while (p < r.n && isWS(r.src[p])) p++
                if (p < r.n && isDigitC(r.src[p])) {
                    val r2 = Reader(r.src, p)
                    val g = parseInt(r2)
                    var q = r2.pos
                    while (q < r.n && isWS(r.src[q])) q++
                    if (q < r.n && r.src[q] == 'R'.code) {
                        val nx = if (q + 1 < r.n) r.src[q + 1] else 0
                        if (nx == 0 || isWS(nx) || isDelim(nx)) {
                            r.pos = q + 1
                            return mkRef(n1.toInt(), g.toInt())
                        }
                    }
                }
            }
            return mkNum(n1)
        }
        // 未知，跳过一个字节
        r.pos++
        return mkNull()
    }

    // ---------------- Flate 解码 + PNG 反过滤 ----------------
    private fun flateDecode(input: ByteArray): ByteArray? {
        return try {
            val inf = Inflater()
            inf.setInput(input)
            val buf = ByteArrayOutputStream()
            val tmp = ByteArray(65536)
            var zeros = 0
            while (!inf.finished()) {
                val k = try { inf.inflate(tmp) } catch (e: Exception) { inf.end(); return null }
                if (k > 0) { buf.write(tmp, 0, k); zeros = 0 }
                else if (inf.needsInput() || inf.needsDictionary()) break
                else if (++zeros > 4) break
            }
            inf.end()
            buf.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun pngUnfilter(data: ByteArray, bytesPerRow: Int): ByteArray {
        if (bytesPerRow == 0) return data
        val stride = bytesPerRow + 1
        val rows = data.size / stride
        if (rows == 0) return data
        val prev = ByteArray(bytesPerRow)
        val cur = ByteArray(bytesPerRow)
        val out = ByteArrayOutputStream(rows * bytesPerRow)
        for (row in 0 until rows) {
            val ft = data[row * stride].toInt() and 0xFF
            for (i in 0 until bytesPerRow) {
                val x = data[row * stride + 1 + i].toInt() and 0xFF
                val a = if (i > 0) cur[i - 1].toInt() and 0xFF else 0
                val bch = prev[i].toInt() and 0xFF
                val cch = if (i > 0) prev[i - 1].toInt() and 0xFF else 0
                val v = when (ft) {
                    1 -> x + a
                    2 -> x + bch
                    3 -> x + (a + bch) / 2
                    4 -> {
                        val p = a + bch - cch
                        val pa = abs(p - a); val pb = abs(p - bch); val pc = abs(p - cch)
                        val pr = if (pa <= pb && pa <= pc) a else if (pb <= pc) bch else cch
                        x + pr
                    }
                    else -> x
                }
                cur[i] = (v and 0xFF).toByte()
            }
            out.write(cur, 0, bytesPerRow)
            cur.copyInto(prev)
        }
        return out.toByteArray()
    }

    // ---------------- 文档解析 ----------------
    private class XrefEntry(val type: Int, val f2: Long, val f3: Int)

    private class Doc {
        var src: Src = MemSrc(ByteArray(0))
        val xref = HashMap<Int, XrefEntry>()
        private val cache = HashMap<Int, Obj?>()
        private val objStmCache = HashMap<Int, HashMap<Int, Obj?>>()
        var rootNum = -1
        var bias = 0 // 文件头前垃圾字节导致的偏移偏差
        var err = ""
        private var mainTrailer: Obj? = null
        private var prevOut: Obj? = null

        fun load(s: Src): Boolean {
            src = s
            // 允许 %PDF- 前有少量垃圾字节（真实文件常见）
            val hdr = indexOfPattern(src, "%PDF-".toByteArray(Charsets.US_ASCII))
            if (hdr < 0 || hdr > 1024) {
                err = "不是有效的 PDF 文件（缺少 %PDF- 头）"
                return false
            }
            val sx = findStartXref()
            if (sx < 0) {
                err = "找不到 startxref"
                return false
            }
            bias = hdr
            if (!buildXref(sx + hdr)) return false
            val td = mainTrailer
            if (td == null) {
                err = "找不到 trailer"
                return false
            }
            if (td.has("Encrypt")) {
                err = "不支持加密的 PDF 文件"
                return false
            }
            val root = td.get("Root")
            if (root == null || root.t != T.REF) {
                err = "trailer 缺少 /Root"
                return false
            }
            rootNum = root.rn
            return true
        }

        private fun findStartXref(): Long {
            val found = lastIndexOfPattern(src, "startxref".toByteArray(Charsets.US_ASCII))
            if (found < 0) return -1
            val r = Reader(src, found + 9)
            r.skipWs()
            return parseInt(r)
        }

        private fun parseObjectAt(offset: Int): Obj? {
            if (offset < 0 || offset >= src.n) return null
            val r = Reader(src, offset)
            r.skipWs()
            // 期望 "N G obj"
            parseInt(r) // obj num
            parseInt(r) // gen
            r.skipWs()
            r.match("obj")
            return parseObject(r)
        }

        private fun buildXref(offset: Long): Boolean {
            val visited = HashSet<Long>()
            var cur = offset
            var first = true
            while (cur != 0L) {
                if (!visited.add(cur)) break
                if (!parseXrefSection(cur.toInt(), first)) return false
                first = false
                val np = prevOut
                if (np == null || np.t != T.NUM) break
                cur = np.n.toLong() + bias
            }
            return true
        }

        private fun parseXrefSection(offset: Int, first: Boolean): Boolean {
            if (offset < 0 || offset >= src.n) {
                err = "xref 解析失败"
                return false
            }
            val r = Reader(src, offset)
            r.skipWs()
            prevOut = null
            if (r.cur() == 'x'.code) {
                // 经典 xref 表
                if (!r.match("xref")) {
                    err = "xref 解析失败"
                    return false
                }
                while (true) {
                    r.skipWs()
                    if (r.eof()) break
                    if (r.cur() == 't'.code) { // trailer
                        r.match("trailer")
                        r.skipWs()
                        val td = parseObject(r)
                        if (first) mainTrailer = td
                        prevOut = td?.get("Prev")
                        break
                    }
                    val start = parseInt(r)
                    val cnt = parseInt(r)
                    for (i in 0 until cnt) {
                        r.skipWs()
                        // 读取 "0000000000 65535 f"
                        val off = StringBuilder()
                        while (!r.eof() && isDigitC(r.cur())) { off.append(r.cur().toChar()); r.pos++ }
                        r.skipWs()
                        val gen = StringBuilder()
                        while (!r.eof() && isDigitC(r.cur())) { gen.append(r.cur().toChar()); r.pos++ }
                        r.skipWs()
                        var typ = 'f'
                        if (!r.eof() && (r.cur() == 'f'.code || r.cur() == 'n'.code)) {
                            typ = r.cur().toChar(); r.pos++
                        }
                        val objNum = (start + i).toInt()
                        if (typ == 'n' && !xref.containsKey(objNum)) {
                            val f2 = off.toString().toLongOrNull() ?: 0L
                            val f3 = gen.toString().toIntOrNull() ?: 0
                            if (f2 > 0) xref[objNum] = XrefEntry(1, f2, f3)
                        }
                    }
                }
                return true
            } else {
                // xref 流
                val o = parseObjectAt(offset)
                if (o == null || o.t != T.STREAM) {
                    err = "xref 流解析失败"
                    return false
                }
                if (first) mainTrailer = o // 流对象本身就是 trailer 字典
                prevOut = o.get("Prev")
                val dec = decodeStream(o)
                if (dec == null) {
                    err = "xref 流解码失败"
                    return false
                }
                val w = o.get("W")
                val sizeObj = o.get("Size")
                if (w == null || w.t != T.ARR || w.a.size < 3) {
                    err = "xref 流缺少 /W"
                    return false
                }
                val w0 = w.a[0]!!.n.toInt(); val w1 = w.a[1]!!.n.toInt(); val w2 = w.a[2]!!.n.toInt()
                val size = sizeObj?.n?.toInt() ?: 0
                val ew = w0 + w1 + w2
                if (ew == 0) {
                    err = "xref 流 /W 无效"
                    return false
                }
                // Index
                val idx = ArrayList<Pair<Int, Int>>()
                val indexObj = o.get("Index")
                if (indexObj != null && indexObj.t == T.ARR) {
                    var i = 0
                    while (i + 1 < indexObj.a.size) {
                        idx.add(indexObj.a[i]!!.n.toInt() to indexObj.a[i + 1]!!.n.toInt())
                        i += 2
                    }
                } else {
                    idx.add(0 to size)
                }
                var p = 0
                fun rd(wBytes: Int): Long {
                    var v = 0L
                    for (k in 0 until wBytes) {
                        v = (v shl 8) or (if (p < dec.size) (dec[p].toLong() and 0xFF) else 0L)
                        p++
                    }
                    return v
                }
                for (seg in idx) {
                    for (i in 0 until seg.second) {
                        val objNum = seg.first + i
                        val t1 = if (w0 != 0) rd(w0).toInt() else 1
                        val f2 = rd(w1)
                        val f3 = if (w2 != 0) rd(w2).toInt() else 0
                        if (!xref.containsKey(objNum)) {
                            xref[objNum] = XrefEntry(t1, f2, f3)
                        }
                    }
                }
                return true
            }
        }

        fun decodeStream(o: Obj?): ByteArray? {
            if (o == null || o.t != T.STREAM) return null
            val f = o.get("Filter")
            val raw = o.rawBytes()
            if (f == null || (f.t == T.NAME && f.s == "null")) return raw
            val filters = ArrayList<String>()
            if (f.t == T.NAME) filters.add(f.s)
            else if (f.t == T.ARR) for (x in f.a) if (x != null && x.t == T.NAME) filters.add(x.s)
            var cur = raw
            for (flt in filters) {
                if (flt == "FlateDecode" || flt == "Fl") {
                    cur = flateDecode(cur) ?: return null
                } else if (flt == "ASCIIHexDecode" || flt == "AHx") {
                    val h = StringBuilder()
                    for (ch in cur) {
                        val b = ch.toInt() and 0xFF
                        if (b == '>'.code) break
                        if (!isWS(b)) h.append(b.toChar())
                    }
                    if (h.length % 2 != 0) h.append('0')
                    val bos = ByteArrayOutputStream(h.length / 2)
                    var i = 0
                    while (i + 1 < h.length) {
                        val x = hexVal(h[i].code); val y = hexVal(h[i + 1].code)
                        bos.write((if (x < 0) 0 else x) * 16 + (if (y < 0) 0 else y))
                        i += 2
                    }
                    cur = bos.toByteArray()
                } else {
                    return null // 不支持的过滤器
                }
            }
            // Predictor
            val dp = o.get("DecodeParms")
            var parms = dp
            if (dp != null && dp.t == T.ARR && dp.a.isNotEmpty()) parms = dp.a[0]
            if (parms != null && parms.t == T.DICT) {
                val pred = parms.get("Predictor")
                if (pred != null && pred.n >= 10) {
                    var cols = 1
                    val cObj = parms.get("Columns")
                    if (cObj != null) cols = cObj.n.toInt()
                    cur = pngUnfilter(cur, cols)
                }
            }
            return cur
        }

        fun getObject(num: Int): Obj? {
            if (cache.containsKey(num)) return cache[num]
            val xe = xref[num]
            if (xe == null) {
                cache[num] = null
                return null
            }
            val result: Obj?
            if (xe.type == 1) {
                result = parseObjectAt((xe.f2 + bias).toInt())
            } else if (xe.type == 2) {
                val stmNum = xe.f2.toInt()
                val idx = xe.f3
                var m = objStmCache[stmNum]
                if (m == null) {
                    m = HashMap()
                    objStmCache[stmNum] = m
                    val so = getObjectDirect(stmNum)
                    if (so != null && so.t == T.STREAM) {
                        val dec = decodeStream(so)
                        if (dec != null) {
                            val nObj = so.get("N"); val firstObj = so.get("First")
                            if (nObj != null && firstObj != null) {
                                val cnt = nObj.n.toInt(); val firstOff = firstObj.n.toInt()
                                val r = Reader(MemSrc(dec), 0)
                                val hdr = ArrayList<Pair<Int, Int>>()
                                for (i in 0 until cnt) {
                                    val on = parseInt(r).toInt()
                                    val off = parseInt(r).toInt()
                                    hdr.add(on to off)
                                }
                                for (i in 0 until cnt) {
                                    if (i >= hdr.size) break
                                    val rr = Reader(MemSrc(dec), firstOff + hdr[i].second)
                                    m[i] = parseObject(rr)
                                }
                            }
                        }
                    }
                }
                result = m[idx]
            } else {
                result = null
            }
            cache[num] = result
            return result
        }

        // 不走缓存直接取（避免 ObjStm 递归）
        private fun getObjectDirect(num: Int): Obj? {
            val xe = xref[num] ?: return null
            return if (xe.type == 1) parseObjectAt((xe.f2 + bias).toInt()) else null
        }

        // 收集页面（按文档顺序）
        private fun collectPages(num: Int, out: ArrayList<Int>, seen: HashSet<Int>, depth: Int) {
            if (depth > 200) return
            if (!seen.add(num)) return
            val o = getObject(num)
            if (o == null || o.t != T.DICT) return
            val ty = o.get("Type")
            val tname = if (ty != null && ty.t == T.NAME) ty.s else ""
            if (tname == "Pages") {
                val kids = o.get("Kids")
                if (kids != null && kids.t == T.ARR) {
                    for (k in kids.a) if (k != null && k.t == T.REF) collectPages(k.rn, out, seen, depth + 1)
                }
            } else if (tname == "Page") {
                out.add(num)
            } else {
                // 无 /Type：若有 /Kids 视为 Pages，否则视为 Page
                if (o.has("Kids")) {
                    val kids = o.get("Kids")
                    if (kids != null && kids.t == T.ARR) {
                        for (k in kids.a) if (k != null && k.t == T.REF) collectPages(k.rn, out, seen, depth + 1)
                    }
                } else {
                    out.add(num)
                }
            }
        }

        fun getPageList(): ArrayList<Int> {
            val out = ArrayList<Int>()
            if (rootNum < 0) return out
            val root = getObject(rootNum) ?: return out
            val pages = root.get("Pages")
            var pagesNum = -1
            if (pages != null && pages.t == T.REF) pagesNum = pages.rn
            if (pagesNum < 0) return out
            collectPages(pagesNum, out, HashSet(), 0)
            if (out.isEmpty()) {
                // 回退：扫描所有对象找 /Type /Page（页面树损坏时）
                for (num in xref.keys.sorted()) {
                    val o = getObject(num)
                    if (o == null || o.t != T.DICT) continue
                    val ty = o.get("Type")
                    if (ty != null && ty.t == T.NAME && ty.s == "Page") out.add(num)
                }
            }
            return out
        }
    }

    // ---------------- 写出 ----------------
    private fun ascii(out: OutputStream, s: String) {
        for (c in s) out.write(c.code and 0xFF)
    }

    private fun writeName(out: OutputStream, name: String) {
        out.write('/'.code)
        for (ch in name) {
            val c = ch.code and 0xFF
            if (c < 33 || c > 126 || isDelim(c) || c == '#'.code) {
                ascii(out, "#%02X".format(c))
            } else {
                out.write(c)
            }
        }
    }

    private fun writeHexStr(out: OutputStream, s: String) {
        out.write('<'.code)
        val h = "0123456789ABCDEF"
        for (ch in s) {
            val c = ch.code and 0xFF
            out.write(h[c shr 4].code)
            out.write(h[c and 15].code)
        }
        out.write('>'.code)
    }

    private fun writeNum(out: OutputStream, n: Double) {
        if (n.isFinite() && n == floor(n) && abs(n) < 1e15) {
            ascii(out, n.toLong().toString())
        } else {
            ascii(out, "%.6f".format(n))
        }
    }

    private fun serialize(o: Obj?, out: OutputStream, remap: Map<Int, Int>) {
        if (o == null) { ascii(out, "null"); return }
        when (o.t) {
            T.NULL -> ascii(out, "null")
            T.BOOL -> ascii(out, if (o.b) "true" else "false")
            T.NUM -> writeNum(out, o.n)
            T.STR -> writeHexStr(out, o.s)
            T.NAME -> writeName(out, o.s)
            T.ARR -> {
                out.write('['.code)
                for (i in o.a.indices) {
                    if (i > 0) out.write(' '.code)
                    serialize(o.a[i], out, remap)
                }
                out.write(']'.code)
            }
            T.DICT, T.STREAM -> serializeDictEntries(o.d, out, remap)
            T.REF -> {
                val nn = remap[o.rn]
                if (nn != null) ascii(out, "$nn 0 R") else ascii(out, "null")
            }
        }
    }

    private fun serializeDictEntries(d: List<Pair<String, Obj?>>, out: OutputStream, remap: Map<Int, Int>) {
        ascii(out, "<<")
        for ((k, v) in d) {
            out.write(' '.code)
            writeName(out, k)
            out.write(' '.code)
            serialize(v, out, remap)
        }
        ascii(out, " >>")
    }

    private fun collectRefs(o: Obj?, out: MutableSet<Int>) {
        if (o == null) return
        when (o.t) {
            T.REF -> out.add(o.rn)
            T.ARR -> for (x in o.a) collectRefs(x, out)
            T.DICT, T.STREAM -> for ((_, v) in o.d) collectRefs(v, out)
            else -> {}
        }
    }

    // 将继承属性落到页面对象上
    private fun materializeInherited(doc: Doc, pageNum: Int) {
        val page = doc.getObject(pageNum)
        if (page == null || page.t != T.DICT) return
        val keys = arrayOf("MediaBox", "CropBox", "Resources", "Rotate")
        val anc = ArrayList<Obj>()
        var cur = pageNum
        for (d in 0 until 100) {
            val o = doc.getObject(cur) ?: break
            val par = o.get("Parent")
            if (par == null || par.t != T.REF) break
            val po = doc.getObject(par.rn) ?: break
            anc.add(po)
            cur = par.rn
        }
        for (k in keys) {
            if (!page.has(k)) {
                for (a in anc) {
                    if (a.has(k)) { page.set(k, a.get(k)); break }
                }
            }
        }
    }

    // ---------------- 公开 API ----------------

    /** 获取 PDF 页数。失败抛出 PdfException。 */
    fun getPageCount(bytes: ByteArray): Int = pageCountOf(MemSrc(bytes))

    /**
     * 提取 ranges 指定的页面（1 基，含端点），按给定顺序写入 output（去重保序）。
     * 返回提取的总页数。失败抛出 PdfException。
     */
    fun extractPages(bytes: ByteArray, output: OutputStream, ranges: List<PageRange>): Int =
        extractOf(MemSrc(bytes), output, ranges)

    private fun extractOf(src: Src, output: OutputStream, ranges: List<PageRange>): Int {
        val doc = Doc()
        if (!doc.load(src)) throw PdfException(doc.err)
        val pages = doc.getPageList()
        val total = pages.size
        if (total == 0) throw PdfException("未能解析出任何页面")

        // 展开范围为页面索引列表（去重保序）
        val selected = ArrayList<Int>()
        val seen = HashSet<Int>()
        for (rg in ranges) {
            if (rg.start < 1 || rg.start > total) {
                throw PdfException("起始页 ${rg.start} 超出范围（共 $total 页）")
            }
            val e = if (rg.end < rg.start) rg.start else rg.end
            if (e > total) {
                throw PdfException("结束页 $e 超出范围（共 $total 页）")
            }
            for (p in rg.start..e) if (seen.add(p)) selected.add(p)
        }
        if (selected.isEmpty()) throw PdfException("未选择任何页面")

        // 物化继承属性并移除 Parent
        for (p in selected) {
            materializeInherited(doc, pages[p - 1])
            doc.getObject(pages[p - 1])?.erase("Parent")
        }

        // 收集闭包
        val closure = HashSet<Int>()
        val queue = ArrayDeque<Int>()
        for (p in selected) {
            val num = pages[p - 1]
            if (closure.add(num)) queue.addLast(num)
        }
        while (queue.isNotEmpty()) {
            val num = queue.removeFirst()
            val refs = HashSet<Int>()
            collectRefs(doc.getObject(num), refs)
            for (r in refs) {
                if (!closure.contains(r) && doc.xref.containsKey(r)) {
                    closure.add(r)
                    queue.addLast(r)
                }
            }
        }

        // 分配新编号：1=Catalog 2=Pages
        val remap = HashMap<Int, Int>()
        var next = 3
        // 页面优先编号（便于阅读）
        val pageNewNums = ArrayList<Int>()
        for (p in selected) {
            val num = pages[p - 1]
            if (!remap.containsKey(num)) remap[num] = next++
            pageNewNums.add(remap[num]!!)
        }
        for (num in closure) {
            if (!remap.containsKey(num)) remap[num] = next++
        }
        val catalogNum = 1
        val pagesRootNum = 2
        val maxObj = next - 1

        // 序列化（边写边落盘，不整块缓冲输出）
        val out = CountingOut(output)
        ascii(out, "%PDF-1.7\n")
        out.write(0xE2); out.write(0xE3); out.write(0xCF); out.write(0xD3); out.write('\n'.code)
        val offsets = IntArray(maxObj + 1)

        fun beginObj(num: Int) {
            offsets[num] = out.count
            ascii(out, "$num 0 obj\n")
        }

        // Catalog
        beginObj(catalogNum)
        ascii(out, "<< /Type /Catalog /Pages $pagesRootNum 0 R >>")
        ascii(out, "\nendobj\n")
        // Pages root
        beginObj(pagesRootNum)
        ascii(out, "<< /Type /Pages /Kids [")
        for (i in pageNewNums.indices) {
            if (i > 0) out.write(' '.code)
            ascii(out, "${pageNewNums[i]} 0 R")
        }
        ascii(out, "] /Count ${pageNewNums.size} >>")
        ascii(out, "\nendobj\n")

        // 其余对象
        for (num in closure.sorted()) {
            val nn = remap[num]!!
            val o = doc.getObject(num) ?: continue
            beginObj(nn)
            if (o.t == T.STREAM) {
                // 写 dict：覆盖 Length
                ascii(out, "<<")
                for ((k, v) in o.d) {
                    if (k == "Length") continue
                    out.write(' '.code)
                    writeName(out, k)
                    out.write(' '.code)
                    serialize(v, out, remap)
                }
                ascii(out, " /Length ${o.rawLength} >>")
                ascii(out, "\nstream\n")
                o.writeRawTo(out)
                ascii(out, "\nendstream")
            } else if (o.t == T.DICT) {
                // 若是页面，补 Parent
                val ty = o.get("Type")
                val isPage = ty != null && ty.t == T.NAME && ty.s == "Page"
                ascii(out, "<<")
                for ((k, v) in o.d) {
                    if (k == "Parent") continue
                    out.write(' '.code)
                    writeName(out, k)
                    out.write(' '.code)
                    serialize(v, out, remap)
                }
                if (isPage) ascii(out, " /Parent $pagesRootNum 0 R")
                ascii(out, " >>")
            } else {
                serialize(o, out, remap)
            }
            ascii(out, "\nendobj\n")
        }

        // xref
        val xrefPos = out.count
        ascii(out, "xref\n")
        ascii(out, "0 ${maxObj + 1}\n")
        ascii(out, "0000000000 65535 f \n")
        for (i in 1..maxObj) {
            ascii(out, "%010d 00000 n \n".format(offsets[i]))
        }
        ascii(out, "trailer\n")
        ascii(out, "<< /Size ${maxObj + 1} /Root $catalogNum 0 R >>")
        ascii(out, "\nstartxref\n")
        ascii(out, "$xrefPos\n")
        ascii(out, "%%EOF\n")
        out.flush()
        return selected.size
    }

    // ---------------- 文件便捷方法 ----------------
    // 文件版使用内存映射（mmap）按需读取，不把整个文件读进 Java 堆，
    // 支持超大 PDF（仅受 2GB 限制），避免 OutOfMemoryError。
    fun getPageCount(file: File): Int = withFileSrc(file) { pageCountOf(it) }

    fun extractPages(file: File, output: OutputStream, ranges: List<PageRange>): Int =
        withFileSrc(file) { extractOf(it, output, ranges) }

    private inline fun <R> withFileSrc(file: File, block: (Src) -> R): R {
        val src = try {
            MmapSrc(file)
        } catch (e: PdfException) {
            throw e
        } catch (e: Exception) {
            throw PdfException("无法读取文件：${e.message ?: "未知错误"}")
        }
        try {
            return block(src)
        } finally {
            src.close()
        }
    }

    private fun pageCountOf(src: Src): Int {
        val doc = Doc()
        if (!doc.load(src)) throw PdfException(doc.err)
        val count = doc.getPageList().size
        if (count == 0) throw PdfException("未能解析出任何页面")
        return count
    }
}
