package io.github.kurumi33.eropic.core.search

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * 联网以图搜图：把本地图片以 multipart 方式匿名上传到各家「以图搜图」服务，拿回结果页链接。
 *
 * 这里用的都是**非官方网页接口**（各大厂商没有对个人开发者开放的匿名上传 API），
 * 因此每个端点的「上传地址 + 表单字段 + 结果 URL 拼法」都写进了对应函数开头：
 * 一旦某家改版，照着注释重新抓一次就能修，不用去翻逆向脚本。
 *
 * 各服务商现状（2026-10 核对公开实现：PicImageSearch / ShareX custom uploaders）：
 * - Google Lens、Yandex、Bing、ascii2d：匿名 multipart 上传可用，能拿到可直接打开的结果页；
 * - TinEye、SauceNAO：需要 API Key（或只返回一次性页面），返回「暂未支持」；
 * - IQDB：结果页没有独立链接，返回「暂未支持」；
 * - trace.moe：只接受公网图片链接，本地文件无法直接上传，返回「暂未支持」。
 *
 * 失败一律走 [OnlineSearchResult.ok] = false + 中文 [OnlineSearchResult.message]，不抛异常：
 * 用户在电梯里没信号、图片被服务商限流都属于预期内情况，不该让 UI 处理异常。
 */
class OnlineImageSearch(
    private val contentResolver: ContentResolver,
) {

    suspend fun search(uri: Uri, provider: ReverseImageProvider): OnlineSearchResult =
        withContext(Dispatchers.IO) {
            val bytes = try {
                readImageBytes(uri)
            } catch (e: IOException) {
                return@withContext failed(provider, e.message ?: "读取图片失败")
            } catch (e: SecurityException) {
                return@withContext failed(provider, "没有读取这张图片的权限，请重新授权后再试")
            }
            // 有些 provider 直接用 ContentResolver 给的 MIME；非 image/* 或取不到时按 JPEG 处理
            val mime = runCatching { contentResolver.getType(uri) }
                .getOrNull()
                ?.takeIf { it.startsWith("image/") }
                ?: DEFAULT_MIME
            try {
                execute(bytes, mime, provider)
            } catch (e: CancellationException) {
                // 用户切走页面导致的取消不该变成一条「失败」提示
                throw e
            } catch (e: Exception) {
                // 网络异常、JSON 解析异常等全部收敛成可展示的中文提示（e.message 可能为 null）
                failed(provider, "联网搜图失败：" + (e.message ?: e.javaClass.simpleName))
            }
        }

    private fun execute(bytes: ByteArray, mime: String, provider: ReverseImageProvider): OnlineSearchResult =
        when (provider) {
            ReverseImageProvider.GOOGLE_LENS -> googleLens(bytes, mime)
            ReverseImageProvider.YANDEX -> yandex(bytes, mime)
            ReverseImageProvider.BING -> bing(bytes, mime)
            ReverseImageProvider.ASCII2D -> ascii2d(bytes, mime)
            ReverseImageProvider.TINEYE -> notSupported(
                provider,
                "TinEye 的公开接口需要 API Key，本应用暂未内置，可点「打开官网」手动上传",
            )
            ReverseImageProvider.SAUCENAO -> notSupported(
                provider,
                "SauceNAO 的匿名上传只返回一次性结果页、没有可复用的链接，需要 API Key，暂未支持",
            )
            ReverseImageProvider.IQDB -> notSupported(
                provider,
                "IQDB 的结果页没有独立链接，暂未支持",
            )
            ReverseImageProvider.TRACEMOE -> notSupported(
                provider,
                "trace.moe 只接受可公网访问的图片链接，本地图片无法直接上传，暂未支持",
            )
        }

    /**
     * Google Lens。
     *
     * 端点（非官方，随时可能失效）：`POST https://lens.google.com/v3/upload?stcs=<毫秒时间戳>&hl=zh-CN`
     * 表单字段：`encoded_image` = 图片文件（multipart/form-data，字段名固定）
     * 结果 URL：服务端 303 跳到 `https://lens.google.com/search?...`，跟随重定向后取最终请求 URL；
     *           少数情况下返回一个中转页，再从 HTML 里抓 lens.google.com/search 链接兜底。
     */
    private fun googleLens(bytes: ByteArray, mime: String): OnlineSearchResult {
        val url = UPLOAD_URL_GOOGLE_LENS.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("stcs", System.currentTimeMillis().toString())
            ?.addQueryParameter("hl", "zh-CN")
            ?.build()
            ?: return failed(ReverseImageProvider.GOOGLE_LENS, "内部错误：上传地址拼装失败")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("encoded_image", IMAGE_FILE_NAME, bytes.toRequestBody(mime.toMediaType()))
            .build()
        post(url, body).use { response ->
            val finalUrl = response.request.url.toString()
            if (finalUrl.contains("/search?") || finalUrl.contains("/uploadbyurl?")) {
                return OnlineSearchResult(provider = ReverseImageProvider.GOOGLE_LENS, ok = true, resultUrl = finalUrl)
            }
            val text = response.body?.string().orEmpty()
            val parsed = ReverseSearchParsers.googleResultUrl(text)
            return if (parsed != null) {
                OnlineSearchResult(provider = ReverseImageProvider.GOOGLE_LENS, ok = true, resultUrl = parsed)
            } else {
                failed(
                    ReverseImageProvider.GOOGLE_LENS,
                    "Google Lens 没有返回结果页（接口可能已变更，HTTP ${response.code}）",
                )
            }
        }
    }

    /**
     * Yandex 识图。
     *
     * 端点（非官方）：`POST https://yandex.com/images/search?rpt=imageview&format=json&request=<json>`
     * 其中 request 参数固定为 `{"blocks":[{"block":"b-page_type_search-by-image__link"}]}`。
     * 表单字段：`upfile` = 图片文件（multipart/form-data）
     * 结果 URL：响应 JSON 里的 `blocks[0].params.url`（形如 `?rpt=imageview&cbir_id=...`），
     *           拼在 `https://yandex.com/images/search` 后面即可直接打开；
     *           解析不到时退回用 `cbir_id` 拼一个等价链接。
     */
    private fun yandex(bytes: ByteArray, mime: String): OnlineSearchResult {
        val url = UPLOAD_URL_YANDEX.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("rpt", "imageview")
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("request", YANDEX_REQUEST_JSON)
            ?.build()
            ?: return failed(ReverseImageProvider.YANDEX, "内部错误：上传地址拼装失败")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            // 文件名用 blob：Yandex 用扩展名判断类型，别传中文文件名
            .addFormDataPart("upfile", "blob", bytes.toRequestBody(mime.toMediaType()))
            .build()
        post(url, body).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return failed(ReverseImageProvider.YANDEX, "Yandex 返回 HTTP ${response.code}")
            }
            val result = ReverseSearchParsers.yandexResultUrl(text)
                ?: return failed(ReverseImageProvider.YANDEX, "Yandex 没有解析出结果页链接（接口可能已变更）")
            return OnlineSearchResult(provider = ReverseImageProvider.YANDEX, ok = true, resultUrl = result)
        }
    }

    /**
     * Bing 识图。
     *
     * 端点（非官方）：`POST https://www.bing.com/images/searchbyimage/upload?cbir=sbi&imgurl=`
     * 表单字段：`imgurl`（空串，保留字段顺序）、`cbir`=`sbi`、`imageBin` = 图片文件
     * 结果 URL：302 的 `Location` 头就是 `https://www.bing.com/images/search?...`，
     *           所以这里**不跟随重定向**，直接读头。
     */
    private fun bing(bytes: ByteArray, mime: String): OnlineSearchResult {
        val url = UPLOAD_URL_BING.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("cbir", "sbi")
            ?.addQueryParameter("imgurl", "")
            ?.build()
            ?: return failed(ReverseImageProvider.BING, "内部错误：上传地址拼装失败")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("imgurl", "")
            .addFormDataPart("cbir", "sbi")
            .addFormDataPart("imageBin", IMAGE_FILE_NAME, bytes.toRequestBody(mime.toMediaType()))
            .build()
        post(url, body, followRedirects = false).use { response ->
            val location = response.header("Location")
            val result = ReverseSearchParsers.resolveUrl(url.toString(), location)
                ?: return failed(
                    ReverseImageProvider.BING,
                    "Bing 没有返回跳转地址（接口可能已变更，HTTP ${response.code}）",
                )
            return OnlineSearchResult(provider = ReverseImageProvider.BING, ok = true, resultUrl = result)
        }
    }

    /**
     * ascii2d（二次元以图搜图）。
     *
     * 端点（非官方）：`POST https://ascii2d.net/search/file?type=color`
     * 表单字段：`file` = 图片文件（multipart/form-data）
     * 结果 URL：302 跳到 `https://ascii2d.net/search/file?uri=<hash>&type=color`，跟随重定向后取最终 URL。
     *           ascii2d 靠 Cookie 串两次检索（色合/特征），本类用默认 CookieJar，首次上传即可打开结果页。
     */
    private fun ascii2d(bytes: ByteArray, mime: String): OnlineSearchResult {
        val url = UPLOAD_URL_ASCII2D.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("type", "color")
            ?.build()
            ?: return failed(ReverseImageProvider.ASCII2D, "内部错误：上传地址拼装失败")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", IMAGE_FILE_NAME, bytes.toRequestBody(mime.toMediaType()))
            .build()
        post(url, body).use { response ->
            val finalUrl = response.request.url.toString()
            return if (finalUrl.contains("uri=")) {
                OnlineSearchResult(provider = ReverseImageProvider.ASCII2D, ok = true, resultUrl = finalUrl)
            } else {
                failed(
                    ReverseImageProvider.ASCII2D,
                    "ascii2d 没有跳转到结果页（接口可能已变更，HTTP ${response.code}）",
                )
            }
        }
    }

    private fun post(
        url: HttpUrl,
        body: RequestBody,
        followRedirects: Boolean = true,
    ): Response {
        val request = Request.Builder()
            .url(url)
            // 这些网页接口会按 UA 返回不同版本（移动端可能直接给 JS 页），用桌面 UA 最稳
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .post(body)
            .build()
        return (if (followRedirects) client else noRedirectClient).newCall(request).execute()
    }

    /**
     * 读图片字节，超过 [MAX_UPLOAD_BYTES] 直接失败。
     *
     * 不直接用 `readBytes()`：用户可能选中 100MB 的扫描件，一次读进内存就 OOM 了。
     */
    private fun readImageBytes(uri: Uri): ByteArray {
        val stream = contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开图片，可能已被移动或删除")
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_UPLOAD_BYTES) {
                    throw IOException("图片超过 ${MAX_UPLOAD_BYTES / (1024 * 1024)}MB，联网搜图暂不支持")
                }
                output.write(buffer, 0, read)
            }
            if (output.size() == 0) throw IOException("图片内容为空，无法上传")
            return output.toByteArray()
        }
    }

    private fun failed(provider: ReverseImageProvider, message: String): OnlineSearchResult =
        OnlineSearchResult(provider = provider, ok = false, message = message)

    private fun notSupported(provider: ReverseImageProvider, message: String): OnlineSearchResult =
        OnlineSearchResult(provider = provider, ok = false, message = message)

    private companion object {
        const val UPLOAD_URL_GOOGLE_LENS = "https://lens.google.com/v3/upload"
        const val UPLOAD_URL_YANDEX = "https://yandex.com/images/search"
        const val UPLOAD_URL_BING = "https://www.bing.com/images/searchbyimage/upload"
        const val UPLOAD_URL_ASCII2D = "https://ascii2d.net/search/file"

        const val DEFAULT_MIME = "image/jpeg"
        const val IMAGE_FILE_NAME = "image.jpg"

        /** 20MB：主流以图搜图服务的上限，也是低端机内存能接受的上限。 */
        const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Safari/537.36"

        const val YANDEX_REQUEST_JSON = """{"blocks":[{"block":"b-page_type_search-by-image__link"}]}"""

        /** 共用连接池；超时按「一次上传 + 服务端处理」估，20s 足够，也避免用户等太久。 */
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        /** Bing / ascii2d 需要读 302 的 Location 头，因此单独一个「不自动跟随重定向」的客户端。 */
        val noRedirectClient: OkHttpClient by lazy {
            client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        }
    }
}

/**
 * 各服务商响应体的解析（纯函数，可在 JVM 单测里直接断言）。
 *
 * 不引入 JSON 库：Yandex 的响应只需要取一个 `params.url` 字段，
 * 用轻量正则比为此新增依赖划算，也避免 org.json 在 JVM 单测里不可用的问题。
 */
internal object ReverseSearchParsers {

    /** Yandex JSON 里的 `"url":"..."` 字段（值里可能含有转义字符）。 */
    private val YANDEX_URL_FIELD = Regex("\"url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** `cbir_id=xxx`（查询串）/ `cbir_id%3Dxxx`（已编码）/ `"cbir_id":"xxx"`（JSON 字段）三种写法。 */
    private val YANDEX_CBIR_ID = Regex("cbir_id(?:%3D|%3d|[^A-Za-z0-9]{1,4})([A-Za-z0-9_\\-]{6,})")

    /** HTML 里出现的 Lens 结果页链接。 */
    private val GOOGLE_RESULT_URL = Regex("https://lens\\.google\\.com/(?:search|uploadbyurl)\\?[^\"'<>\\s]+")

    /**
     * 从 Yandex 的上传响应里取结果页 URL。
     *
     * 优先取带 `cbir_id` / `rpt=imageview` 的那个 "url" 字段：响应里可能还有缩略图等别的 url 字段，
     * 取第一个会拿到错的东西。全都取不到时用 `cbir_id` 自己拼。
     */
    fun yandexResultUrl(body: String): String? {
        val candidates = YANDEX_URL_FIELD.findAll(body)
            .map { unescapeJsonString(it.groupValues[1]) }
            .toList()
        val raw = candidates.firstOrNull { it.contains("cbir_id") || it.contains("rpt=imageview") }
            ?: candidates.firstOrNull()
        if (!raw.isNullOrBlank()) {
            if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
            val query = raw.removePrefix("?").trim()
            if (query.isNotEmpty()) return "https://yandex.com/images/search?$query"
        }
        val cbirId = YANDEX_CBIR_ID.find(body)?.groupValues?.get(1) ?: return null
        return "https://yandex.com/images/search?rpt=imageview&cbir_id=$cbirId"
    }

    /** 从 Google Lens 的 HTML 响应里抓结果页链接（跟随重定向失败时的兜底）。 */
    fun googleResultUrl(text: String): String? =
        GOOGLE_RESULT_URL.find(text)?.value?.replace("&amp;", "&")

    /**
     * 把 HTTP 的 Location 头解析成绝对链接。
     *
     * 服务端可能给相对地址（`/images/search?...`）；`javascript:`、`intent:` 这类协议一律丢掉，
     * 否则 UI 交给浏览器会直接失败。
     */
    fun resolveUrl(baseUrl: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val resolved = runCatching { URI(baseUrl).resolve(location.trim()).toString() }.getOrNull() ?: return null
        return resolved.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    /** 只处理 JSON 字符串里可能出现的几种转义；未知转义按原字符保留。 */
    private fun unescapeJsonString(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                out.append(c)
                i++
                continue
            }
            when (val escaped = raw[i + 1]) {
                '"' -> { out.append('"'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                '/' -> { out.append('/'); i += 2 }
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'u' -> {
                    val code = if (i + 6 <= raw.length) raw.substring(i + 2, i + 6).toIntOrNull(16) else null
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                    } else {
                        out.append(escaped)
                        i += 2
                    }
                }
                else -> { out.append(escaped); i += 2 }
            }
        }
        return out.toString()
    }
}
