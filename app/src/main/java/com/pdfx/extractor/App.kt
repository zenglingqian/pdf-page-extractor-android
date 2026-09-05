package com.pdfx.extractor

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        // PDF 解析使用纯 Kotlin 的 PdfEngine，无需任何第三方库初始化
    }
}
