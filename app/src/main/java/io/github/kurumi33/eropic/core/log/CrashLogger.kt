package io.github.kurumi33.eropic.core.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简崩溃日志（对应规划 F-06 的"关于页可查看最近崩溃"）。
 *
 * 只写应用私有目录 filesDir/crashes，不联网、不上报；保留最近 [MAX_FILES] 份。
 * 安装异常处理器后仍会调用系统的默认处理器，所以系统弹窗/日志行为不变。
 */
object CrashLogger {

    private const val MAX_FILES = 10
    private const val DIR_NAME = "crashes"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread.name, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun logDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 最近一次崩溃内容（供"关于"页展示）；没有则返回 null。 */
    fun latest(context: Context): String? {
        val files = listCrashes(context)
        return files.firstOrNull()?.let { runCatching { it.readText() }.getOrNull() }
    }

    fun listCrashes(context: Context): List<File> =
        logDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun clear(context: Context) {
        logDir(context).listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun write(context: Context, threadName: String, throwable: Throwable) {
        val dir = logDir(context)
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$stamp.txt")
        file.writeText(
            buildString {
                append("时间: ").append(Date()).append('\n')
                append("线程: ").append(threadName).append('\n')
                append("异常: ").append(throwable).append('\n')
                append(throwable.stackTraceToString())
            },
        )
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES)
            ?.forEach { runCatching { it.delete() } }
    }
}
