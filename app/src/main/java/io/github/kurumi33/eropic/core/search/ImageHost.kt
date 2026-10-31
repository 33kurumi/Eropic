package io.github.kurumi33.eropic.core.search

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 匿名临时图床：把本地图片上传一次，拿一个**公网可直接访问的 URL**。
 *
 * 为什么非要有这一步：
 * onegai.moe、lenso.ai、Google Lens（URL 模式）这些站点只提供「给我一个图片 URL」的入口
 * （`/search?url=...`、`/uploadbyurl?url=...`），没有匿名 multipart 上传接口，
 * App 又没法把自己的 content:// 地址暴露给外网，所以必须先在公网上放一份，
 * 再把 URL 拼进搜索页。FlutterPicOrigin（https://github.com/4evergr8/FlutterPicOrigin）
 * 用的也是这个思路，区别是它要求用户自己部署 Cloudflare Worker，这里换成现成的匿名图床，用户零配置。
 *
 * v1.0.0 起改成**多图床回退链**：用户实测手机上 litterbox（catbox 系列）上传失败，
 * 而只写一家意味着「这家不通 = 这个引擎彻底不能用」。现在按顺序试
 * 0x0.st -> tmpfiles.org -> uguu.se -> litterbox，任意一家成功就用它的链接；
 * 全部失败时把每一家的失败原因带回去（[UploadResult.attempted]），
 * 用户报错时能直接看出是网络不通还是服务改版，也方便写进「上传日志」。
 *
 * 这些图床都是**临时**的（1 小时 ~ 数天自动删除）：把私人照片传到公网这件事，时间越短越好。
 *
 * 失败不抛异常：联网失败是这个功能的常态，调用方负责给用户一句中文说明。
 */
class ImageHost(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * 一次上传尝试的结果。
     *
     * [url] 非空表示成功；为空时 [attempted] 是每一家的「名字：失败原因」，
     * 直接拼进用户提示里，避免只给一句「上传失败」让用户没法判断。
     */
    data class UploadResult(
        val url: String? = null,
        val attempted: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = url != null
    }

    /** 上传 [bytes]，返回公网 URL；全部图床都失败时返回 null。 */
    suspend fun upload(bytes: ByteArray, mime: String): String? = uploadDetailed(bytes, mime).url

    /** 上传并保留每一家的失败原因（给错误提示与日志用）。 */
    suspend fun uploadDetailed(bytes: ByteArray, mime: String): UploadResult =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) return@withContext UploadResult(attempted = listOf("本地图片内容为空"))
            val failures = ArrayList<String>(HOSTS.size)
            for (host in HOSTS) {
                val result = runCatching { host.upload(this@ImageHost, bytes, mime) }
                val url = result.getOrNull()
                if (!url.isNullOrBlank() && url.startsWith("http")) {
                    return@withContext UploadResult(url = url, attempted = failures)
                }
                val reason = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                    ?: "没有返回图片链接"
                failures += host.name + "：" + shorten(reason)
            }
            UploadResult(url = null, attempted = failures)
        }

    /** 把失败的原始异常文本压到一行，避免 Snackbar/日志里出现整段堆栈。 */
    private fun shorten(raw: String): String {
        val oneLine = raw.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (oneLine.length <= 60) oneLine else oneLine.take(60) + "…"
    }

    private fun extensionOf(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        else -> "jpg"
    }

    /** 一家图床的上传方式。字段名/返回格式各不相同，各自一小段。 */
    private interface Host {
        val name: String

        fun upload(host: ImageHost, bytes: ByteArray, mime: String): String?
    }

    /**
     * 0x0.st：匿名单文件上传，字段名固定 `file`，成功时响应体就是一行纯文本 URL。
     * 限制单文件 512MB，对我们缩过图之后的几百 KB 完全够；保留 30 天。
     */
    private object ZeroXZeroHost : Host {
        override val name = "0x0.st"

        override fun upload(host: ImageHost, bytes: ByteArray, mime: String): String? {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "eropic." + host.extensionOf(mime), bytes.toRequestBody(mime.toMediaType()))
                .build()
            return host.execute(
                Request.Builder().url("https://0x0.st").post(body).header("User-Agent", BROWSER_UA).build(),
            ) { text -> text.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") } }
        }
    }

    /**
     * tmpfiles.org：字段名 `file`，返回 JSON `{"data":{"url":"https://tmpfiles.org/12345/x.jpg"}}`。
     * 返回的是**预览页**而不是直链，必须把 `tmpfiles.org/` 换成 `tmpfiles.org/dl/` 才是图片本体
     * （引擎抓不到图片本体时会直接报「这不是图片」）。保留 1 小时。
     */
    private object TmpFilesHost : Host {
        override val name = "tmpfiles.org"

        override fun upload(host: ImageHost, bytes: ByteArray, mime: String): String? {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "eropic." + host.extensionOf(mime), bytes.toRequestBody(mime.toMediaType()))
                .build()
            return host.execute(
                Request.Builder().url("https://tmpfiles.org/api/v1/upload").post(body).build(),
            ) { text ->
                val match = Regex("\"url\"\\s*:\\s*\"(https?://[^\"]+)\"").find(text)
                match?.groupValues?.get(1)
                    ?.replace("https://tmpfiles.org/", "https://tmpfiles.org/dl/")
            }
        }
    }

    /**
     * uguu.se：字段名 `files[]`（带方括号，少了会被当成没传文件），
     * 返回 JSON `{"success":true,"files":[{"url":"https://x.uguu.se/abc.jpg"}]}`。保留 3 小时。
     */
    private object UguuHost : Host {
        override val name = "uguu.se"

        override fun upload(host: ImageHost, bytes: ByteArray, mime: String): String? {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("files[]", "eropic." + host.extensionOf(mime), bytes.toRequestBody(mime.toMediaType()))
                .build()
            return host.execute(
                Request.Builder().url("https://uguu.se/upload").post(body).header("User-Agent", BROWSER_UA).build(),
            ) { text ->
                Regex("\"url\"\\s*:\\s*\"(https?://[^\"]+)\"").find(text)?.groupValues?.get(1)
            }
        }
    }

    /**
     * litterbox（catbox 的临时空间）：reqtype=fileupload + time=1h + fileToUpload，
     * 成功时响应体就是一行纯文本 URL。
     *
     * 排最后是因为用户网络实测它上传失败（国内直连 catbox 常被墙）；
     * 但如果用户挂了代理，它反而是最稳定的一家，所以留着当兜底。
     */
    private object LitterboxHost : Host {
        override val name = "litterbox"

        override fun upload(host: ImageHost, bytes: ByteArray, mime: String): String? {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("reqtype", "fileupload")
                .addFormDataPart("time", "1h")
                .addFormDataPart("fileToUpload", "eropic." + host.extensionOf(mime), bytes.toRequestBody(mime.toMediaType()))
                .build()
            return host.execute(
                Request.Builder().url("https://litterbox.catbox.moe/resources/internals/api.php").post(body).build(),
            ) { text -> text.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") } }
        }
    }

    /**
     * 发一次上传请求并把响应体交给 [parse]。
     *
     * 单独抽出来是为了三点：统一的短超时（链式回退不能被一家卡死）、
     * 统一判 HTTP 状态（4xx/5xx 当失败而不是去解析错误页）、
     * 以及 `use {}` 确保响应体一定被关闭，否则连接池会被占满。
     */
    private fun execute(request: Request, parse: (String) -> String?): String? {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string().orEmpty()
            return parse(text)?.takeIf { it.isNotBlank() }
        }
    }

    companion object {
        /**
         * 上传回退链的顺序。
         *
         * 排序依据：先用「国内相对能连上 + 直接返回直链」的，catbox 系列放最后（用户实测不通）。
         * 单家超时压到 15 秒，四家全挂也就一分钟左右，用户不会以为 App 卡死。
         */
        private val HOSTS = listOf(ZeroXZeroHost, TmpFilesHost, UguuHost, LitterboxHost)

        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Safari/537.36"

        /** 单次上传：15 秒足够传几百 KB，超时就换下一家。 */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
