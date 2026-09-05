package com.pdfx.extractor

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * PDF 预览选页：用系统 PdfRenderer 渲染缩略图，网格展示，点按选择。
 * 确认后将所选页面压缩为连续范围，回传给 MainActivity。
 */
class PreviewActivity : Activity() {

    companion object {
        const val EXTRA_SELECTED = "selected_pages" // IntArray，1 基
        const val INPUT_FILE = "input.pdf"
    }

    private var renderer: PdfRenderer? = null
    private val thumbs = HashMap<Int, Bitmap>()
    private val selected = HashSet<Int>()
    private var pageCount = 0
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var adapter: PageAdapter
    private lateinit var tvSelectedCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        val grid = findViewById<GridView>(R.id.gridPages)
        adapter = PageAdapter()
        grid.adapter = adapter

        findViewById<Button>(R.id.btnSelectAll).setOnClickListener {
            if (selected.size == pageCount) {
                selected.clear()
            } else {
                for (i in 1..pageCount) selected.add(i)
            }
            updateCount()
            adapter.notifyDataSetChanged()
        }
        findViewById<Button>(R.id.btnConfirmSelection).setOnClickListener { confirm() }

        openPdf()
    }

    private fun openPdf() {
        val file = File(cacheDir, INPUT_FILE)
        if (!file.exists()) {
            toast("请先在主界面选择 PDF 文件")
            finish()
            return
        }
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            pageCount = renderer!!.pageCount
            findViewById<TextView>(R.id.tvPreviewTitle).text =
                getString(R.string.preview_title_n, pageCount)
            adapter.notifyDataSetChanged()
            updateCount()
        } catch (e: SecurityException) {
            toast("该 PDF 已加密，无法预览")
            finish()
        } catch (e: Exception) {
            toast("预览失败：${e.message ?: "未知错误"}")
            finish()
        }
    }

    /** 在后台线程渲染缩略图（PdfRenderer 同一时刻只能打开一页，须串行）。 */
    private fun renderThumb(index: Int, target: ImageView) {
        target.setImageBitmap(null)
        target.tag = index
        executor.execute {
            val bmp = thumbs[index] ?: try {
                val r = renderer ?: return@execute
                var page: PdfRenderer.Page? = null
                var result: Bitmap? = null
                synchronized(r) {
                    page = r.openPage(index)
                    val scale = 320f / page!!.width.coerceAtLeast(1)
                    val w = (page!!.width * scale).toInt().coerceAtLeast(1)
                    val h = (page!!.height * scale).toInt().coerceAtLeast(1)
                    result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    result!!.eraseColor(Color.WHITE)
                    page!!.render(result!!, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page!!.close()
                }
                result
            } catch (e: Exception) {
                null
            }
            if (bmp != null) {
                thumbs[index] = bmp
                runOnUiThread {
                    if (target.tag == index) target.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun updateCount() {
        tvSelectedCount.text = getString(R.string.preview_selected_count, selected.size)
    }

    private fun confirm() {
        if (selected.isEmpty()) {
            toast("请至少选择一页")
            return
        }
        val pages = selected.sorted()
        val data = Intent()
        data.putExtra(EXTRA_SELECTED, pages.toIntArray())
        setResult(RESULT_OK, data)
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        try { renderer?.close() } catch (_: Exception) {}
    }

    private inner class PageAdapter : BaseAdapter() {
        override fun getCount() = pageCount
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: LayoutInflater.from(this@PreviewActivity).inflate(R.layout.item_page, parent, false)
            val page = position + 1
            val img = view.findViewById<ImageView>(R.id.imgPage)
            val tvNum = view.findViewById<TextView>(R.id.tvPageNum)
            val tvCheck = view.findViewById<TextView>(R.id.tvCheck)

            tvNum.text = page.toString()
            tvCheck.visibility = if (selected.contains(page)) View.VISIBLE else View.GONE
            view.setBackgroundColor(
                if (selected.contains(page)) 0x334CAF50 else Color.TRANSPARENT
            )

            val cached = thumbs[position]
            if (cached != null) {
                img.tag = position
                img.setImageBitmap(cached)
            } else {
                renderThumb(position, img)
            }

            view.setOnClickListener {
                if (selected.contains(page)) selected.remove(page) else selected.add(page)
                updateCount()
                notifyDataSetChanged()
            }
            return view
        }
    }
}
