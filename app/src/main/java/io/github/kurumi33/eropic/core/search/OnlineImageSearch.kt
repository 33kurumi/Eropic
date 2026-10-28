package io.github.kurumi33.eropic.core.search

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
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
 * 联网以图搜图：把本地图片上传到各家「以图搜图」服务，拿回一个能直接打开的结果页。
 *
 * 这里用的都是**非官方网页接口**（这些服务没有对个人开发者开放的匿名上传 API），
 * 因此每个端点的「上传地址 + 表单字段 + 结果 URL 拼法」都写进了对应函数开头：
 * 一旦某家改版，照着注释重新抓一次就能修，不用去翻逆向脚本。
 *
 * 四家的现状（2026-10 核对公开实现：PicImageSearch / ShareX custom uploaders）：
 * - Google Lens、Yandex、ascii2d：匿名 multipart 上传可用，服务端会重定向到结果页；
 * - SauceNAO：必须带 API Key（在 saucenao.com 免费注册后获取），成功时返回最相似来源的页面。
 *
 * 失败一律走 [OnlineSearchResult.ok] = false + 中文 [OnlineSearchResult.message]，不抛异常：
 * 用户在电梯里没信号、图片被服务商限流都属于预期内情况，不该让 UI 处理异常。
 */
class OnlineImageSearch(
    private val contentResolver: ContentResolver,
    /**
     * 每次搜索时现取 SauceNAO 的 API Key（而不是构造时取一次）：
     * 用户在设置页填完 key 立刻生效，不用重建 ViewModel 或重进页面。
     */
    private val sauceNaoApiKey: () -> String,
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
            ReverseImageProvider.SAUCENAO -> sauceNao(bytes, mime)
            ReverseImageProvider.ASCII2D -> ascii2d(bytes, mime)
        }

    /**
     * Google Lens。
     *
     * 端点（非官方，随时可能失效）：POST https://lens.google.com/v3/upload?stcs=<毫秒时间戳>&hl=zh-CN
     * 表单字段：encoded_image = 图片文件（multipart/form-data，字段名固定）
     * 结果 URL：服务端 303 跳到 https://lens.google.com/search?...，跟随重定向后取最终请求 URL；
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
                    "Google Lens 没有返回结果页（接口可能已变更，HTTP " + response.code + "）",
                )
            }
        }
    }

    /**
     * Yandex 识图。
     *
     * 端点（非官方）：POST https://yandex.com/images/search?rpt=imageview&format=json&request=<json>
     * 其中 request 参数固定为 {"blocks":[{"block":"b-page_type_search-by-image__link"}]}。
     * 表单字段：upfile = 图片文件（multipart/form-data）
     * 结果 URL：响应 JSON 里的 blocks[0].params.url（形如 ?rpt=imageview&cbir_id=...），
     *           拼在 https://yandex.com/images/search 后面即可直接打开；
     *           解析不到时退回用 cbir_id 拼一个等价链接。
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
                return failed(ReverseImageProvider.YANDEX, "Yandex 返回 HTTP " + response.code)
            }
            val result = ReverseSearchParsers.yandexResultUrl(text)
                ?: return failed(ReverseImageProvider.YANDEX, "Yandex 没有解析出结果页链接（接口可能已变更）")
            return OnlineSearchResult(provider = ReverseImageProvider.YANDEX, ok = true, resultUrl = result)
        }
    }

    /**
     * SauceNAO（二次元 / 插画以图搜图：pixiv、twitter、danbooru 等索引）。
     *
     * 端点（官方 API，但必须带个人 key）：POST https://saucenao.com/search.php
     * 表单字段：api_key=<个人 key>、output_type=2（要 JSON）、file=图片
     * 返回形如：
     * {"header":{"status":0,"results_returned":2,...},
     *  "results":[{"header":{"similarity":"95.6","index_name":"Pixiv"},
     *              "data":{"ext_urls":["https://www.pixiv.net/artworks/123"],...}}, ...]}
     * header.status：0 成功，-1 key 无效，-2 限流，-3 搜索出错（其它负数为未知错误）。
     *
     * 结果 URL：JSON 里**没有**可复用的「网页结果页」链接（网页版结果页是一次性页面，靠 cookie 才能看），
     * 但每个命中都带 data.ext_urls，即作品在原站的页面。直接打开这个对用户最有用，
     * 所以成功时返回「第一个带 ext_urls 的命中」的来源链接，而不是让用户再去官网手动传一次。
     *
     * 上传体积：网页端上限只有几 MB，手机原图常有 3-10MB，超过阈值先在本地缩到 1600 长边再传，
     * 否则会被服务端拒绝（用户只会看到「失败」，很难猜到是图片太大）。
     */
    private fun sauceNao(bytes: ByteArray, mime: String): OnlineSearchResult {
        val provider = ReverseImageProvider.SAUCENAO
        val apiKey = sauceNaoApiKey().trim()
        // 没 key 就不发请求：既省流量，也避免服务端回 403 让用户以为网络坏了
        if (apiKey.isEmpty()) return failed(provider, SAUCE_NAO_NO_KEY_MESSAGE)
        val shrunk = shrinkForSauceNao(bytes)
        val payload = shrunk ?: bytes
        val payloadMime = if (shrunk != null) "image/jpeg" else mime
        val url = UPLOAD_URL_SAUCE_NAO.toHttpUrlOrNull()
            ?: return failed(provider, "内部错误：上传地址拼装失败")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("api_key", apiKey)
            // output_type=2 表示要 JSON；0 是网页版，返回的是没有可复用 URL 的一次性页面
            .addFormDataPart("output_type", "2")
            .addFormDataPart("file", IMAGE_FILE_NAME, payload.toRequestBody(payloadMime.toMediaType()))
            .build()
        post(url, body).use { response ->
            if (!response.isSuccessful) {
                return failed(provider, sauceNaoHttpMessage(response.code))
            }
            val text = response.body?.string().orEmpty()
            val status = ReverseSearchParsers.sauceNaoStatus(text)
                ?: return failed(provider, "SauceNAO 返回了无法解析的响应（接口可能已变更），请稍后再试")
            if (status != 0) return failed(provider, sauceNaoStatusMessage(status))
            val hits = ReverseSearchParsers.sauceNaoResults(text)
            val extUrl = hits.firstNotNullOfOrNull { it.extUrl }
            if (extUrl != null) {
                return OnlineSearchResult(provider = provider, ok = true, resultUrl = extUrl)
            }
            val returned = ReverseSearchParsers.sauceNaoResultsReturned(text) ?: hits.size
            return if (returned <= 0) {
                failed(provider, "SauceNAO 没有找到相似来源，可换一张更清晰的图，或到 saucenao.com 手动检索")
            } else {
                failed(
                    provider,
                    "SauceNAO 找到 " + returned + " 个结果，但都没有可直接打开的来源链接；" +
                        "请到 saucenao.com 用同一张图手动检索",
                )
            }
        }
    }

    /**
     * 把超过 [SAUCE_NAO_SHRINK_THRESHOLD_BYTES] 的图缩到 [SAUCE_NAO_LONG_EDGE] 长边并重新编码成 JPEG。
     *
     * 用 ImageDecoder 而不是 BitmapFactory：它会按 EXIF 自动摆正方向，
     * 而 pixiv 这类索引对旋转很敏感，转了 90 度匹配率会明显下降。
     * 缩失败（格式不支持、内存不足）返回 null，调用方按原图上传 ——
     * 宁可让服务端拒绝，也不能因为本地转码失败就直接告诉用户「搜不了」。
     */
    private fun shrinkForSauceNao(bytes: ByteArray): ByteArray? {
        if (bytes.size <= SAUCE_NAO_SHRINK_THRESHOLD_BYTES) return null
        return runCatching {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val width = info.size.width.coerceAtLeast(1)
                val height = info.size.height.coerceAtLeast(1)
                val scale = min(1f, SAUCE_NAO_LONG_EDGE.toFloat() / max(width, height).toFloat())
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
            try {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, SAUCE_NAO_JPEG_QUALITY, out)
                out.toByteArray().takeIf { it.isNotEmpty() }
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }

    /**
     * ascii2d（二次元以图搜图）。
     *
     * 端点（非官方）：POST https://ascii2d.net/search/file?type=color
     * 表单字段：file = 图片文件（multipart/form-data）
     * 结果 URL：302 跳到 https://ascii2d.net/search/file?uri=<hash>&type=color，跟随重定向后取最终 URL。
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
                    "ascii2d 没有跳转到结果页（接口可能已变更，HTTP " + response.code + "）",
                )
            }
        }
    }

    private fun post(url: HttpUrl, body: RequestBody): Response {
        val request = Request.Builder()
            .url(url)
            // 这些网页接口会按 UA 返回不同版本（移动端可能直接给 JS 页），用桌面 UA 最稳
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .post(body)
            .build()
        return client.newCall(request).execute()
    }

    /**
     * 读图片字节，超过 [MAX_UPLOAD_BYTES] 直接失败。
     *
     * 不直接用 readBytes()：用户可能选中 100MB 的扫描件，一次读进内存就 OOM 了。
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
                    throw IOException("图片超过 " + (MAX_UPLOAD_BYTES / (1024 * 1024)) + "MB，联网搜图暂不支持")
                }
                output.write(buffer, 0, read)
            }
            if (output.size() == 0) throw IOException("图片内容为空，无法上传")
            return output.toByteArray()
        }
    }

    private fun failed(provider: ReverseImageProvider, message: String): OnlineSearchResult =
        OnlineSearchResult(provider = provider, ok = false, message = message)

    private companion object {
        const val UPLOAD_URL_GOOGLE_LENS = "https://lens.google.com/v3/upload"
        const val UPLOAD_URL_YANDEX = "https://yandex.com/images/search"
        const val UPLOAD_URL_SAUCE_NAO = "https://saucenao.com/search.php"
        const val UPLOAD_URL_ASCII2D = "https://ascii2d.net/search/file"

        const val DEFAULT_MIME = "image/jpeg"
        const val IMAGE_FILE_NAME = "image.jpg"

        /** 20MB：比各家上限都宽，真正的限制交给服务端；这里只防「把 100MB 扫描件读进内存」。 */
        const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024

        /** 超过 1.5MB 就先缩图：SauceNAO 网页上传上限只有几 MB，手机原图基本都超。 */
        const val SAUCE_NAO_SHRINK_THRESHOLD_BYTES = 1500 * 1024

        /** 1600 长边足够 SauceNAO 做特征匹配，同时能把体积压到几百 KB。 */
        const val SAUCE_NAO_LONG_EDGE = 1600
        const val SAUCE_NAO_JPEG_QUALITY = 85

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
    }
}

/**
 * 各服务商响应体的解析（纯函数，可在 JVM 单测里直接断言）。
 *
 * 不引入 JSON 库：这里只需要从固定结构的 JSON 里取几个字段，
 * 手写扫描比为此新增依赖划算，也避免 org.json 在 JVM 单测里不可用的问题。
 */
internal object ReverseSearchParsers {

    /** SauceNAO results 数组里的一项（只保留用得上的字段）。 */
    data class SauceNaoHit(
        /** 相似度百分比，解析不到时为 null。 */
        val similarity: Double?,
        /** 命中的索引名，如 Pixiv / Twitter。 */
        val indexName: String?,
        /** 作品标题（可能含多语言换行），解析不到时为 null。 */
        val title: String?,
        /** 作品在原站的页面链接；ext_urls 为空或都不是 http(s) 时为 null。 */
        val extUrl: String?,
    )

    /** Yandex JSON 里的 "url":"..." 字段（值里可能含有转义字符）。 */
    private val YANDEX_URL_FIELD = Regex("\"url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** cbir_id=xxx（查询串）/ cbir_id%3Dxxx（已编码）/ "cbir_id":"xxx"（JSON 字段）三种写法。 */
    private val YANDEX_CBIR_ID = Regex("cbir_id(?:%3D|%3d|[^A-Za-z0-9]{1,4})([A-Za-z0-9_\\-]{6,})")

    /** HTML 里出现的 Lens 结果页链接。 */
    private val GOOGLE_RESULT_URL = Regex("https://lens\\.google\\.com/(?:search|uploadbyurl)\\?[^\"'<>\\s]+")

    /** SauceNAO 顶层 header 里的 "status":0/-1/-2/-3。 */
    private val SAUCE_NAO_STATUS = Regex("\"status\"\\s*:\\s*(-?\\d+)")

    /** SauceNAO 顶层 header 里的 "results_returned":2。 */
    private val SAUCE_NAO_RESULTS_RETURNED = Regex("\"results_returned\"\\s*:\\s*(\\d+)")

    /** 单个命中里的 "similarity":"95.6"（注意是字符串，不是数字）。 */
    private val SAUCE_NAO_SIMILARITY = Regex("\"similarity\"\\s*:\\s*\"([0-9.]+)\"")

    private val SAUCE_NAO_INDEX_NAME = Regex("\"index_name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    private val SAUCE_NAO_TITLE = Regex("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /**
     * 从 Yandex 的上传响应里取结果页 URL。
     *
     * 优先取带 cbir_id / rpt=imageview 的那个 "url" 字段：响应里可能还有缩略图等别的 url 字段，
     * 取第一个会拿到错的东西。全都取不到时用 cbir_id 自己拼。
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

    /** SauceNAO 顶层 header.status；响应不是 JSON（比如网关错误页）时为 null。 */
    fun sauceNaoStatus(body: String): Int? =
        SAUCE_NAO_STATUS.find(body)?.groupValues?.get(1)?.toIntOrNull()

    /** SauceNAO 顶层 header.results_returned（服务端认为命中了几条）。 */
    fun sauceNaoResultsReturned(body: String): Int? =
        SAUCE_NAO_RESULTS_RETURNED.find(body)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * 解析 SauceNAO 的 results 数组（按相似度从高到低，顺序即服务端给的顺序）。
     *
     * 先按花括号配对切出每个命中对象，再在对象内部取字段：
     * 直接用正则在整个响应体里找 "ext_urls" 会串行（第二个命中的 ext_urls 可能被当成第一个的）。
     */
    fun sauceNaoResults(body: String): List<SauceNaoHit> =
        objectsInArray(body, "results").map { obj ->
            SauceNaoHit(
                similarity = SAUCE_NAO_SIMILARITY.find(obj)?.groupValues?.get(1)?.toDoubleOrNull(),
                indexName = SAUCE_NAO_INDEX_NAME.find(obj)?.groupValues?.get(1)?.let(::unescapeJsonString),
                title = SAUCE_NAO_TITLE.find(obj)?.groupValues?.get(1)?.let(::unescapeJsonString),
                extUrl = firstHttpUrlInArray(obj, "ext_urls"),
            )
        }

    /** 取 JSON 里某个键对应的数组中的第一个 http(s) 字符串（用于 ext_urls）。 */
    private fun firstHttpUrlInArray(body: String, key: String): String? {
        var i = indexAfterKey(body, key, '[') ?: return null
        i++
        while (i < body.length) {
            while (i < body.length && (body[i].isWhitespace() || body[i] == ',')) i++
            if (i >= body.length || body[i] == ']') return null
            // 数组元素只可能是字符串；不是就说明结构变了，直接放弃而不是瞎猜
            if (body[i] != '"') return null
            i++
            val raw = StringBuilder()
            while (i < body.length && body[i] != '"') {
                if (body[i] == '\\' && i + 1 < body.length) {
                    raw.append(body[i]).append(body[i + 1])
                    i += 2
                } else {
                    raw.append(body[i])
                    i++
                }
            }
            i++ // 跳过右引号
            val url = unescapeJsonString(raw.toString())
            if (url.startsWith("https://") || url.startsWith("http://")) return url
        }
        return null
    }

    /**
     * 把 JSON 里某个键对应的数组切成一个个对象字面量。
     *
     * 只处理「值是对象数组」这一种情况；结构不符（值是 null、键不存在）时返回空列表。
     */
    private fun objectsInArray(body: String, key: String): List<String> {
        var i = indexAfterKey(body, key, '[') ?: return emptyList()
        i++
        val objects = ArrayList<String>()
        while (i < body.length) {
            while (i < body.length && (body[i].isWhitespace() || body[i] == ',')) i++
            if (i >= body.length || body[i] != '{') break
            val end = endOfObject(body, i) ?: break
            objects.add(body.substring(i, end))
            i = end
        }
        return objects
    }

    /** 找到 key 之后、目标字符（'['）的索引；中间只允许冒号和空白，否则视为结构不符。 */
    private fun indexAfterKey(body: String, key: String, target: Char): Int? {
        val keyIndex = body.indexOf("\"$key\"")
        if (keyIndex < 0) return null
        var i = keyIndex + key.length + 2
        while (i < body.length && body[i] != target) {
            if (body[i] != ':' && !body[i].isWhitespace()) return null
            i++
        }
        return if (i < body.length) i else null
    }

    /** 从 start 处的 '{' 起做括号配对，返回配对 '}' 之后的下标；字符串里的括号不计数。 */
    private fun endOfObject(body: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var i = start
        while (i < body.length) {
            val c = body[i]
            if (inString) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
                else -> Unit
            }
            i++
        }
        return null
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

/** SauceNAO 未配置 API Key 时的提示：必须告诉用户去哪儿填，否则只会看到一句「失败」。 */
internal const val SAUCE_NAO_NO_KEY_MESSAGE =
    "请在 设置 → 联网识图 填 SauceNAO API Key（在 saucenao.com 免费注册账号即可获取）"

/** SauceNAO 业务错误码（header.status）到中文提示。 */
internal fun sauceNaoStatusMessage(status: Int): String = when (status) {
    -1 -> "SauceNAO 认为 API Key 无效或额度已用尽，请到 设置 → 联网识图 检查后重填"
    -2 -> "SauceNAO 请求太频繁（免费额度有冷却时间），请稍后再试"
    -3 -> "SauceNAO 搜索出错，请稍后再试"
    else -> "SauceNAO 返回错误码 " + status + "，请稍后再试"
}

/** SauceNAO 的 HTTP 状态码到中文提示（服务端连 status 都不给时只能靠它）。 */
internal fun sauceNaoHttpMessage(code: Int): String = when (code) {
    403 -> "SauceNAO 拒绝访问：API Key 无效或额度已用尽，请到 设置 → 联网识图 检查"
    413 -> "图片太大，SauceNAO 拒绝接收，请先裁剪或换一张"
    429 -> "SauceNAO 请求太频繁，请稍后再试"
    400 -> "SauceNAO 无法处理这张图片（格式或内容不受支持），请换一张"
    else -> "SauceNAO 返回 HTTP " + code
}
