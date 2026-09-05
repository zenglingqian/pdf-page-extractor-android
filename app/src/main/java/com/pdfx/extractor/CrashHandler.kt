package com.pdfx.extractor

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：把未捕获异常的堆栈写入应用内部文件，
 * 下次启动时由 MainActivity 弹窗展示，便于用户截图反馈。
 */
object CrashHandler {

    private const val FILE_NAME = "crash_log.txt"

    fun crashFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("===== 崩溃时间: " +
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + " =====")
                pw.println("应用版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                pw.println("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                pw.println("线程: ${thread.name}")
                throwable.printStackTrace(pw)
                pw.flush()
                // 追加写入，保留最近 3 条
                val file = crashFile(appContext)
                val old = if (file.exists()) file.readText() else ""
                val sections = (sw.toString() + "\n" + old)
                file.writeText(sections.take(60000))
            } catch (_: Exception) {
            }
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /** 读取并清空崩溃日志；无日志返回 null。 */
    fun consume(context: Context): String? {
        val file = crashFile(context)
        if (!file.exists()) return null
        val text = file.readText().trim()
        file.delete()
        return text.ifEmpty { null }
    }
}
