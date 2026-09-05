package com.pdfx.extractor

import android.app.Activity
import android.app.ProgressDialog
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), BatchPasteListener {

    private lateinit var tvPdfInfo: TextView
    private lateinit var rangeContainer: LinearLayout
    private lateinit var tvCount: TextView

    private var pdfUri: Uri? = null
    private var pdfName: String = ""
    private var pageCount = 0
    private var pdfCacheFile: java.io.File? = null

    private val rows = mutableListOf<View>()

    companion object {
        private const val REQ_PICK_PDF = 1
        private const val REQ_IMPORT_XLSX = 2
        private const val REQ_SAVE_TEMPLATE = 3
        private const val REQ_SAVE_OUTPUT = 4
        private const val REQ_PREVIEW = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        showCrashLogIfAny()

        tvPdfInfo = findViewById(R.id.tvPdfInfo)
        rangeContainer = findViewById(R.id.rangeContainer)
        tvCount = findViewById(R.id.tvCount)

        findViewById<Button>(R.id.btnPickPdf).setOnClickListener { pickPdf() }
        findViewById<Button>(R.id.btnPreview).setOnClickListener { openPreview() }
        findViewById<Button>(R.id.btnTemplate).setOnClickListener { saveTemplate() }
        findViewById<Button>(R.id.btnImport).setOnClickListener { importXlsx() }
        findViewById<Button>(R.id.btnPaste).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.btnAddGroup).setOnClickListener { addRow("", "") }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            if (rows.isNotEmpty()) {
                clearRows()
                toast(getString(R.string.msg_cleared))
            }
        }
        findViewById<Button>(R.id.btnExtract).setOnClickListener { doExtract() }

        addRow("", "") // 默认一组空输入
        updateCount()
    }

    // ---------------- 崩溃日志展示 ----------------
    private fun showCrashLogIfAny() {
        val log = CrashHandler.consume(this) ?: return
        val view = android.widget.ScrollView(this).apply {
            setPadding(32, 16, 32, 8)
        }
        val tv = TextView(this).apply {
            text = log
            textSize = 12f
            setTextIsSelectable(true)
        }
        view.addView(tv)
        android.app.AlertDialog.Builder(this)
            .setTitle("上次运行时发生崩溃，请把以下内容截图反馈给开发者")
            .setView(view)
            .setPositiveButton("知道了", null)
            .show()
    }

    // ---------------- PDF 选择 ----------------
    private fun pickPdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(intent, REQ_PICK_PDF)
    }

    private fun loadPdf(uri: Uri) {
        val name = queryDisplayName(uri) ?: "未知文件"
        val dialog = ProgressDialog.show(this, null, getString(R.string.dlg_loading), true, false)
        Thread {
            var count = -1
            var err: String? = null
            var cache: java.io.File? = null
            try {
                // 复制到应用缓存，避免大文件全量占用内存且便于重复读取
                val tmp = java.io.File(cacheDir, "input.pdf")
                val ins = contentResolver.openInputStream(uri)
                if (ins == null) {
                    err = "无法读取该文件"
                } else {
                    ins.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    cache = tmp
                    count = PdfEngine.getPageCount(tmp)
                }
            } catch (e: Exception) {
                err = e.message ?: "PDF 解析失败"
                cache = null
            }
            val cacheRef = cache
            runOnUiThread {
                dialog.dismiss()
                if (err != null || count < 0 || cacheRef == null) {
                    toast(getString(R.string.msg_pdf_load_fail, err ?: "未知错误"))
                } else {
                    pdfUri = uri
                    pdfName = name
                    pageCount = count
                    pdfCacheFile = cacheRef
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                    tvPdfInfo.text = getString(R.string.pdf_loaded, name, count)
                }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) { null }

    // ---------------- 预览选页 ----------------
    private fun openPreview() {
        if (pdfUri == null || pdfCacheFile?.exists() != true) {
            toast(getString(R.string.err_pick_pdf_first))
            return
        }
        startActivityForResult(Intent(this, PreviewActivity::class.java), REQ_PREVIEW)
    }

    /** 把所选页面（1 基、升序）压缩为连续范围并填入输入框。 */
    private fun fillFromSelection(pages: IntArray) {
        if (pages.isEmpty()) return
        val sorted = pages.sorted()
        val ranges = mutableListOf<Pair<Int, Int>>()
        var start = sorted[0]
        var prev = sorted[0]
        for (i in 1 until sorted.size) {
            val p = sorted[i]
            if (p == prev + 1) {
                prev = p
            } else {
                ranges.add(start to prev)
                start = p
                prev = p
            }
        }
        ranges.add(start to prev)

        clearRows()
        ranges.forEach { addRow(it.first.toString(), if (it.second == it.first) "" else it.second.toString()) }
        toast(getString(R.string.msg_preview_fill_ok, ranges.size, sorted.size))
    }

    // ---------------- 范围行管理 ----------------
    private fun addRow(start: String, end: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_range, rangeContainer, false)
        val etStart = view.findViewById<PasteAwareEditText>(R.id.etStart)
        val etEnd = view.findViewById<PasteAwareEditText>(R.id.etEnd)
        val tvDesc = view.findViewById<TextView>(R.id.tvRangeDesc)

        etStart.setText(start)
        etEnd.setText(end)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateRowDesc(view) }
        }
        etStart.addTextChangedListener(watcher)
        etEnd.addTextChangedListener(watcher)

        view.findViewById<TextView>(R.id.btnDeleteRow).setOnClickListener {
            rangeContainer.removeView(view)
            rows.remove(view)
            refreshTitles()
        }

        rangeContainer.addView(view)
        rows.add(view)
        refreshTitles()
        updateRowDesc(view)
    }

    private fun refreshTitles() {
        rows.forEachIndexed { i, v ->
            v.findViewById<TextView>(R.id.tvGroupTitle).text =
                getString(R.string.group_title, i + 1)
        }
        updateCount()
    }

    private fun updateCount() {
        tvCount.text = getString(R.string.range_count, rows.size)
    }

    private fun updateRowDesc(view: View) {
        val s = view.findViewById<EditText>(R.id.etStart).text.toString().trim()
        val e = view.findViewById<EditText>(R.id.etEnd).text.toString().trim()
        val tv = view.findViewById<TextView>(R.id.tvRangeDesc)
        tv.text = when {
            s.isEmpty() -> getString(R.string.range_desc_invalid)
            e.isEmpty() || e == s -> getString(R.string.range_desc_single, s)
            else -> getString(R.string.range_desc_range, s, e)
        }
    }

    private fun clearRows() {
        rangeContainer.removeAllViews()
        rows.clear()
        updateCount()
    }

    // ---------------- 批量粘贴 ----------------
    override fun onBatchPaste(text: String): Boolean {
        if (!text.contains('\n') && !text.contains('\t')) return false
        val parsed = BatchTextParser.parse(text)
        if (parsed.isEmpty()) return false
        appendRows(parsed)
        toast(getString(R.string.msg_paste_ok, parsed.size))
        return true
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        val parsed = BatchTextParser.parse(text)
        if (parsed.isEmpty()) {
            toast(getString(R.string.msg_paste_none))
            return
        }
        appendRows(parsed)
        toast(getString(R.string.msg_paste_ok, parsed.size))
    }

    private fun appendRows(parsed: List<BatchTextParser.RangeRow>) {
        // 若当前只有一组且全空，则替换之
        if (rows.size == 1) {
            val v = rows[0]
            val sEmpty = v.findViewById<EditText>(R.id.etStart).text.isBlank()
            val eEmpty = v.findViewById<EditText>(R.id.etEnd).text.isBlank()
            if (sEmpty && eEmpty) {
                clearRows()
            }
        }
        parsed.forEach { addRow(it.start, it.end) }
    }

    // ---------------- 模板与导入 ----------------
    private fun saveTemplate() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_TITLE, getString(R.string.template_file_name))
        }
        startActivityForResult(intent, REQ_SAVE_TEMPLATE)
    }

    private fun importXlsx() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel", "application/octet-stream"
                )
            )
        }
        startActivityForResult(intent, REQ_IMPORT_XLSX)
    }

    private fun doImport(uri: Uri) {
        try {
            val parsed = contentResolver.openInputStream(uri)?.use { XlsxIO.readRanges(it) }
                ?: throw IllegalArgumentException("无法读取该文件")
            clearRows()
            parsed.forEach { addRow(it.start, it.end) }
            toast(getString(R.string.msg_import_ok, parsed.size))
        } catch (e: Exception) {
            toast(getString(R.string.msg_import_fail, e.message ?: "未知错误"))
        }
    }

    // ---------------- 提取 ----------------
    private data class UiRange(val start: Int, val end: Int, val index: Int)

    private fun collectRanges(): Pair<List<UiRange>?, String?> {
        if (rows.isEmpty()) return null to getString(R.string.err_no_ranges)
        val out = mutableListOf<UiRange>()
        rows.forEachIndexed { i, v ->
            val s = v.findViewById<EditText>(R.id.etStart).text.toString().trim()
            val e = v.findViewById<EditText>(R.id.etEnd).text.toString().trim()
            if (s.isEmpty() && e.isEmpty()) return@forEachIndexed
            val si = s.toIntOrNull()
            if (si == null || !s.all { it.isDigit() }) {
                return null to getString(R.string.err_start_invalid, i + 1)
            }
            if (e.isNotEmpty() && (e.toIntOrNull() == null || !e.all { it.isDigit() })) {
                return null to getString(R.string.err_end_invalid, i + 1)
            }
            if (si < 1) return null to getString(R.string.err_start_zero, i + 1)
            val ei = if (e.isEmpty()) si else e.toInt()
            if (e.isNotEmpty() && ei < si) {
                return null to getString(R.string.err_end_lt_start, i + 1)
            }
            if (pageCount > 0) {
                if (si > pageCount) return null to getString(R.string.err_start_exceed, i + 1, s, pageCount)
                if (e.isNotEmpty() && ei > pageCount) return null to getString(R.string.err_end_exceed, i + 1, e, pageCount)
            }
            out.add(UiRange(si, ei, i))
        }
        if (out.isEmpty()) return null to getString(R.string.err_no_ranges)
        return out to null
    }

    private fun doExtract() {
        if (pdfUri == null) {
            toast(getString(R.string.err_pick_pdf_first))
            return
        }
        val (ranges, err) = collectRanges()
        if (ranges == null) {
            toast(err ?: "")
            return
        }
        val base = pdfName.substringBeforeLast('.', pdfName)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, getString(R.string.default_output_name, base))
        }
        pendingRanges = ranges
        startActivityForResult(intent, REQ_SAVE_OUTPUT)
    }

    private var pendingRanges: List<UiRange>? = null

    private fun runExtract(uri: Uri, ranges: List<UiRange>) {
        val dialog = ProgressDialog.show(this, null, getString(R.string.dlg_extracting), true, false)
        Thread {
            var ok = -1
            var err: String? = null
            try {
                val src = pdfCacheFile
                if (src == null || !src.exists()) {
                    err = "请重新选择 PDF 文件"
                } else {
                    contentResolver.openOutputStream(uri)?.use { outs ->
                        ok = PdfEngine.extractPages(
                            src, outs,
                            ranges.map { PdfEngine.PageRange(it.start, it.end) }
                        )
                    } ?: run { err = "无法写入输出文件" }
                }
            } catch (e: Exception) {
                err = e.message ?: "提取失败"
            }
            runOnUiThread {
                dialog.dismiss()
                if (err != null || ok < 0) {
                    toast(getString(R.string.msg_extract_fail, err ?: "未知错误"))
                } else {
                    toast(getString(R.string.msg_extract_ok, ok))
                }
            }
        }.start()
    }

    // ---------------- 结果回调 ----------------
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        // 预览选页：结果在 extras 中，无 data.data
        if (requestCode == REQ_PREVIEW) {
            val pages = data?.getIntArrayExtra(PreviewActivity.EXTRA_SELECTED)
            if (pages != null) fillFromSelection(pages)
            return
        }
        if (data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_PICK_PDF -> loadPdf(uri)
            REQ_IMPORT_XLSX -> doImport(uri)
            REQ_SAVE_TEMPLATE -> {
                try {
                    contentResolver.openOutputStream(uri)?.use { XlsxIO.writeTemplate(it) }
                        ?: throw IllegalStateException("无法写入文件")
                    toast(getString(R.string.msg_template_ok))
                } catch (e: Exception) {
                    toast(getString(R.string.msg_template_fail, e.message ?: "未知错误"))
                }
            }
            REQ_SAVE_OUTPUT -> {
                pendingRanges?.let { runExtract(uri, it) }
                pendingRanges = null
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
