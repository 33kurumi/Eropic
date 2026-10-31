package io.github.kurumi33.eropic.core.diagnostics

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import io.github.kurumi33.eropic.BuildConfig
import io.github.kurumi33.eropic.core.log.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 「上传日志」的本机实现：把诊断信息拼成一个 txt 放进 cacheDir/logs/，
 * 再由 [shareIntent] 交给系统分享面板（微信/邮件/文件管理器随用户选）。
 *
 * 全模块不发起任何网络请求——Eropic 的定位是本地优先，日志只能由用户自己决定发给谁。
 * 也刻意不收集图片/视频内容或媒体路径，只收数量统计，避免用户误发隐私。
 */
object LogCollector {

    private const val TAG = "LogCollector"
    private const val DIR_NAME = "logs"
    /** 缓存目录只留最近几份：用户可能反复点「上传日志」，攒多了会白占空间。 */
    private const val MAX_FILES = 10
    private const val CRASH_FILE_LIMIT = 3
    private const val CRASH_TEXT_LIMIT = 8_000
    private const val LOGCAT_TAIL_LINES = 800
    private const val LOGCAT_MAX_LINES = 500

    /**
     * 生成一份诊断日志，返回文件（cacheDir/logs/eropic-log-<时间戳>.txt）。
     *
     * @param statsProvider 图片/视频/重复组等统计，由调用方（UI）用当前数据算好传进来；
     *   传 null 时该段写「本次未提供统计」。统计失败不会让整份日志生不出来——
     *   反而正需要看「统计为什么失败」，所以失败信息也写进日志。
     */
    suspend fun collect(
        context: Context,
        statsProvider: (suspend () -> String)? = null,
    ): File = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dir = File(appContext.cacheDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()

        val now = Date()
        val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        val humanTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .format(Date(BuildConfig.BUILD_TIME_MS))
        val metrics = appContext.resources.displayMetrics
        val (logcatLines, logcatFailure) = readLogcat()

        val stats = if (statsProvider == null) {
            null
        } else {
            runCatching { statsProvider.invoke() }
                .getOrElse { "（统计失败: ${it.javaClass.simpleName}: ${it.message}）" }
        }

        val content = buildString {
            append(LogFormatter.header(humanTime))
            append(
                LogFormatter.appSection(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE.toLong(),
                    buildStamp = BuildConfig.BUILD_STAMP,
                    buildTimeText = buildTime,
                    packageName = appContext.packageName,
                    debuggable = BuildConfig.DEBUG,
                ),
            )
            append(
                LogFormatter.deviceSection(
                    manufacturer = Build.MANUFACTURER,
                    brand = Build.BRAND,
                    model = Build.MODEL,
                    device = Build.DEVICE,
                    release = Build.VERSION.RELEASE,
                    sdkInt = Build.VERSION.SDK_INT,
                    abis = Build.SUPPORTED_ABIS.toList(),
                    widthPx = metrics.widthPixels,
                    heightPx = metrics.heightPixels,
                    densityDpi = metrics.densityDpi,
                    density = metrics.density,
                ),
            )
            append(LogFormatter.permissionSection(permissionRows(appContext)))
            append(LogFormatter.statsSection(stats))
            append(LogFormatter.crashSection(recentCrashes(appContext)))
            append(LogFormatter.logcatSection(logcatLines, logcatFailure))
        }

        val file = File(dir, "eropic-log-$fileStamp.txt")
        file.writeText(content, Charsets.UTF_8)
        pruneOldFiles(dir)
        Log.d(TAG, "诊断日志已生成: ${file.name}（${file.length()} 字节）")
        file
    }

    /**
     * 分享意图。必须走 FileProvider：cacheDir 是应用私有目录，直接传 file:// 在 Android 7+ 会抛
     * FileUriExposedException；这里用项目已有的 ${applicationId}.fileprovider，只需 file_paths.xml
     * 暴露 cacheDir/logs/。
     */
    fun shareIntent(context: Context, file: File): Intent {
        val authority = context.packageName + ".fileprovider"
        val uri = try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (e: IllegalArgumentException) {
            // 配置错误（file_paths.xml 没暴露 logs/）比分享面板一片空白更好排查，明确抛出来。
            throw IllegalStateException("FileProvider 未配置 cacheDir/$DIR_NAME（请检查 res/xml/file_paths.xml）", e)
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension.equals("zip", ignoreCase = true)) "application/zip" else "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            // clipData 是给部分 ROM 的兼容：只设 EXTRA_STREAM 时个别分享目标拿不到临时读权限。
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun permissionRows(context: Context): List<Pair<String, String>> = buildList {
        add("READ_MEDIA_IMAGES" to grantState(context, Manifest.permission.READ_MEDIA_IMAGES))
        // v0.8 起视频也读 MediaStore；权限没给时视频页为空，日志里带上便于排查。
        add("READ_MEDIA_VIDEO" to grantState(context, Manifest.permission.READ_MEDIA_VIDEO))
        // Android 14 的「仅选中的照片/视频」：这条给了而 READ_MEDIA_* 没给，就是部分授权。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(
                "READ_MEDIA_VISUAL_USER_SELECTED" to
                    grantState(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            )
        }
        // 移动/回收站/永久删除依赖「所有文件访问」，用户常卡在这一步。
        add(
            "MANAGE_EXTERNAL_STORAGE" to
                if (Environment.isExternalStorageManager()) "已授予" else "未授予",
        )
        add("POST_NOTIFICATIONS" to grantState(context, Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun grantState(context: Context, permission: String): String =
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            "已授予"
        } else {
            "未授予"
        }

    /** 最近几次崩溃的内容（路径见 [CrashLogger.logDir]：filesDir/crashes）。 */
    private fun recentCrashes(context: Context): List<Pair<String, String>> = runCatching {
        CrashLogger.listCrashes(context).take(CRASH_FILE_LIMIT).map { file ->
            val text = runCatching { file.readText() }
                .getOrElse { "（读取失败: ${it.javaClass.simpleName}）" }
            file.name to LogFormatter.truncate(text, CRASH_TEXT_LIMIT)
        }
    }.getOrElse { emptyList() }

    /**
     * 读本进程的 logcat。Android 10+ 只允许读到自己应用的日志（拿不到别人的），
     * 这里再用关键字过滤一遍，避免把系统噪音也带进诊断文件。
     *
     * @return 过滤后的行 + 失败说明（失败时行为空，说明会写进日志）。
     */
    private fun readLogcat(): Pair<List<String>, String?> {
        var process: Process? = null
        return try {
            val p = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", LOGCAT_TAIL_LINES.toString()),
            )
            process = p
            // -d 让 logcat 打印完就退出；-t 800 给了上限，readLines 不会无限占内存。
            val raw = p.inputStream.bufferedReader().use { it.readLines() }
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy()
            LogFormatter.filterLogcatLines(raw, LOGCAT_MAX_LINES) to null
        } catch (e: SecurityException) {
            emptyList<String>() to "读取 logcat 失败（系统限制）: ${e.javaClass.simpleName}"
        } catch (e: IOException) {
            emptyList<String>() to "读取 logcat 失败（IO）: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            process?.destroy()
        }
    }

    private fun pruneOldFiles(dir: File) {
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("eropic-log-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES)
            ?.forEach { runCatching { it.delete() } }
    }
}
