package com.pdfx.extractor

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText
import android.content.ClipboardManager

/** 批量粘贴回调：返回 true 表示已按批量数据处理（拦截默认粘贴）。 */
interface BatchPasteListener {
    fun onBatchPaste(text: String): Boolean
}

/**
 * 起始/结束页输入框：拦截「粘贴」，若剪贴板是多行/多列的批量数据则批量导入。
 */
class PasteAwareEditText @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : EditText(context, attrs) {

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
            if ((context as? BatchPasteListener)?.onBatchPaste(text) == true) {
                return true
            }
        }
        return super.onTextContextMenuItem(id)
    }
}
