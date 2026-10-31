package io.github.kurumi33.eropic.core.update

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 应用内检查更新 / 下载新版本（v0.7）。
 *
 * 数据源是 GitHub Releases API（`/repos/{owner}/{repo}/releases/latest`）：
 * 这是侧载分发唯一不需要服务器的方案，用户发 release 后所有旧版 App 都能立刻看到。
 *
 * 为什么不比 versionCode：本项目的 versionCode 是构建时间戳（见 app/build.gradle.kts），
 * 本地无法预知线上包的时间戳，所以改用版本名做语义比较（[VersionCompare]）。
 *
 * 隐私/权限说明：只读公开仓库的 release 信息（匿名请求，速率 60 次/小时），
 * 不上报任何设备信息；manifest 里为此新增了 INTERNET 权限（v0.7 起，见 ADR）。
 */
class UpdateChecker(
    private val context: Context,
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * 查询最新 release。
     *
     * 返回 null 表示「线上没有比当前更新的版本」（或者仓库还没有任何 release）；
     * 抛出的异常由调用方转成中文提示。
     */
    suspend fun check(localVersionName: String?): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API_LATEST_RELEASE)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) {
                // 仓库还没有发布过 release：不是错误，只是没有可更新的东西
                return@withContext null
            }
            if (!response.isSuccessful) {
                throw IOException("GitHub 返回 HTTP " + response.code + "（" + statusHint(response.code) + "）")
            }
            val body = response.body?.string().orEmpty()
            val info = parseRelease(body) ?: return@withContext null
            if (!VersionCompare.isNewer(info.versionName, localVersionName)) {
                return@withContext null
            }
            info
        }
    }

    /**
     * 下载 [info] 的 APK 到 `cacheDir/updates/`，返回本地绝对路径。
     *
     * 存 cacheDir 而不是外部存储：系统「清理缓存」能顺手回收，也免得在用户相册目录里扔安装包。
     * 下载先写 .part 再改名，避免中途失败留下一个「看起来完整」的 APK。
     */
    suspend fun download(
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
        /** 用户点「取消」时置 true：每写一块检查一次，取消后立即抛 CancellationException 并删掉 .part。 */
        isCancelled: () -> Boolean = { false },
    ): String =
        withContext(Dispatchers.IO) {
            val downloadUrl = info.downloadUrl
                ?: throw IOException("这个 release 没有附 APK，只能打开发布页手动下载")
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "Eropic-" + info.versionName + ".apk")
            val part = File(dir, target.name + ".part")
            val request = Request.Builder().url(downloadUrl).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("下载失败：HTTP " + response.code)
                }
                val body = response.body ?: throw IOException("下载失败：响应为空")
                val total = body.contentLength()
                part.delete()
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            if (isCancelled()) {
                                throw kotlinx.coroutines.CancellationException("用户取消下载")
                            }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                        output.flush()
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("下载完成但无法重命名安装包")
            onProgress(1f)
            target.absolutePath
        }

    private fun parseRelease(body: String): UpdateInfo? = runCatching {
        val json = JSONObject(body)
        val tag = json.optString("tag_name").trim()
        val version = tag.removePrefix("v").removePrefix("V").ifBlank { tag }
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                    if (apkUrl != null) break
                }
            }
        }
        UpdateInfo(
            versionName = version,
            notes = json.optString("body").takeIf { it.isNotBlank() },
            downloadUrl = apkUrl,
            pageUrl = json.optString("html_url").takeIf { it.isNotBlank() },
            publishedAtMs = parseIsoTime(json.optString("published_at")),
        ).takeIf { version.isNotBlank() }
    }.getOrNull()

    /**
     * GitHub 的 "published_at" 是 ISO8601（如 2026-10-31T06:12:00Z）。
     *
     * 用 java.time 解析而不手写正则：时区、闰秒、毫秒都交给标准库；解析不了返回 0（卡片不显示时间）。
     */
    private fun parseIsoTime(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0L
        return runCatching {
            java.time.Instant.parse(text).toEpochMilli()
        }.getOrElse {
            runCatching {
                java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
            }.getOrDefault(0L)
        }
    }

    private fun statusHint(code: Int): String = when (code) {
        403 -> "访问太频繁或仓库是私有的"
        401 -> "仓库是私有的，匿名检查不可用"
        in 500..599 -> "GitHub 服务端异常"
        else -> "网络或账号限制"
    }

    companion object {
        /** 仓库固定为 33kurumi/Eropic（与 README、build.gradle.kts 的 remote 一致）。 */
        private const val OWNER = "33kurumi"
        private const val REPO = "Eropic"
        private const val API_LATEST_RELEASE =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest"
        private const val USER_AGENT = "Eropic-Android-Updater"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}
