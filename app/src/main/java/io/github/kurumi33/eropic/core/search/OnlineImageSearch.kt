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
import okhttp3.Cookie
import okhttp3.CookieJar
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
    /**
     * 每次搜索时现取 whos.tv 的「用户名 to 密码」。
     *
     * 和 SauceNAO key 一样用函数而不是构造时快照：用户在设置里改完账号，回识图页立刻生效。
     */
    private val whosTvAccount: () -> Pair<String, String>,
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
            ReverseImageProvider.BAIDU -> baidu(bytes, mime)
            ReverseImageProvider.WHOSTV -> whosTv(bytes, mime)
        }

    /**
     * Google Lens。
     *
     * 端点（非官方，随时可能失效）：POST https://lens.google.com/v3/upload?stcs=<毫秒时间戳>
     * 表单字段：encoded_image = 图片文件（multipart/form-data，字段名固定）
     * 结果 URL：服务端 303 跳到 https://lens.google.com/search?...，跟随重定向后取最终请求 URL；
     *           少数情况下返回一个中转页，再从 HTML 里抓 lens.google.com/search 链接兜底。
     *
     * 2026-10-28 对照 PicImageSearch（kitUIN/PicImageSearch，engines/google_lens.py）修正：
     * 上传请求**不能带 hl 参数**，否则 Lens 直接返回 403；stcs 是 ShareX 这类客户端习惯带的时间戳，保留无碍。
     */
    private fun googleLens(bytes: ByteArray, mime: String): OnlineSearchResult {
        val url = UPLOAD_URL_GOOGLE_LENS.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("stcs", System.currentTimeMillis().toString())
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
            // 结果页形如 https://ascii2d.net/search/color/<hash>（特征检索是 /search/bovw/<hash>），
            // 只有提交失败时才会停在 /search/file 上；2026-10-28 对照 PicImageSearch 的实现确认这一点。
            return if (finalUrl.contains("/search/color") || finalUrl.contains("/search/bovw") || finalUrl.contains("uri=")) {
                OnlineSearchResult(provider = ReverseImageProvider.ASCII2D, ok = true, resultUrl = finalUrl)
            } else {
                failed(
                    ReverseImageProvider.ASCII2D,
                    "ascii2d 没有跳转到结果页（接口可能已变更，HTTP " + response.code + "）",
                )
            }
        }
    }

    /**
     * 百度识图。
     *
     * 端点（非官方）：POST https://graph.baidu.com/upload?uptime=<毫秒时间戳>
     * 表单字段：image = 图片文件、image_source = PC_UPLOAD_SEARCH_FILE（网页端固定值）、
     *          range = 一段固定的 JSON（表示"来自搜索结果页"的来源标记，缺了也能用）
     * 返回 JSON：{"status":0,"data":{"url":"https://graph.baidu.com/s?..."}}，取 data.url 直接打开即可。
     *
     * 这是整套联网识图里**唯一国内直连不需要代理**的一家，所以放在按钮列表靠后但文案上会标注"国内直连"。
     */
    private fun baidu(bytes: ByteArray, mime: String): OnlineSearchResult {
        val provider = ReverseImageProvider.BAIDU
        val url = UPLOAD_URL_BAIDU.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("uptime", System.currentTimeMillis().toString())
            ?.build()
            ?: return failed(provider, "内部错误：上传地址拼装失败")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", IMAGE_FILE_NAME, bytes.toRequestBody(mime.toMediaType()))
            .addFormDataPart("image_source", BAIDU_IMAGE_SOURCE)
            .addFormDataPart("range", BAIDU_RANGE_JSON)
            .build()
        post(url, body, referer = "https://graph.baidu.com/").use { response ->
            if (!response.isSuccessful) return failed(provider, "百度识图返回 HTTP " + response.code)
            val text = response.body?.string().orEmpty()
            val resultUrl = ReverseSearchParsers.baiduResultUrl(text)
                ?: return failed(
                    provider,
                    "百度识图没有返回结果页（接口可能已变更，HTTP " + response.code + "）",
                )
            return OnlineSearchResult(provider = provider, ok = true, resultUrl = resultUrl)
        }
    }

    /**
     * whos.tv 截图搜番号。
     *
     * 与其它几家最大的不同：**它不是匿名服务**。对照 FlanChanXwO/whostv-toolkit-manager：
     * - 先 POST /api/login（JSON: username/password），服务端种 cookie 维持会话；
     * - 搜一下要扣积分（每天签到 +10），积分不够会直接拒绝；
     * - POST /upload-search（multipart 字段 file）返回 /search-wait/<id>，
     *   然后轮询 GET /search-status/<id>（带 X-Requested-With: XMLHttpRequest），返回结果页 URL，
     *   或者 {"code":404000} 表示任务不存在、URL 以 /error 结尾表示没匹配到。
     * - 站点在 Cloudflare 后面，官方工具箱要靠 curl_cffi 的 TLS 指纹甚至真浏览器才稳；
     *   我们只有 OkHttp，所以这里**按尽力而为处理**：失败时明确告诉用户去浏览器手动传。
     */
    private fun whosTv(bytes: ByteArray, mime: String): OnlineSearchResult {
        val provider = ReverseImageProvider.WHOSTV
        val (username, password) = whosTvAccount()
        if (username.isBlank() || password.isBlank()) return failed(provider, WHOS_TV_NO_ACCOUNT_MESSAGE)

        // 1) 登录（cookie 由 client 的内存 CookieJar 保存）
        val loginUrl = (WHOS_TV_BASE + WHOS_TV_LOGIN_PATH).toHttpUrlOrNull()
            ?: return failed(provider, "内部错误：登录地址拼装失败")
        val loginJson = """{"username":""" + jsonString(username) + ""","password":""" + jsonString(password) + """}"""
        val loginBody = loginJson.toRequestBody(JSON_MEDIA_TYPE)
        post(loginUrl, loginBody, referer = "$WHOS_TV_BASE/").use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) return failed(provider, whosTvHttpMessage(response.code))
            val code = ReverseSearchParsers.whosStatusCode(text)
            if (code != null && code != WHOS_TV_CODE_OK) {
                val detail = ReverseSearchParsers.whosMessage(text) ?: ("错误码 " + code)
                return failed(provider, "whos.tv 登录失败：" + detail)
            }
        }

        // 2) 上传并触发搜索
        val uploadUrl = (WHOS_TV_BASE + WHOS_TV_UPLOAD_PATH).toHttpUrlOrNull()
            ?: return failed(provider, "内部错误：上传地址拼装失败")
        val uploadBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", IMAGE_FILE_NAME, bytes.toRequestBody(mime.toMediaType()))
            .build()
        val searchId: String
        post(uploadUrl, uploadBody, referer = "$WHOS_TV_BASE/").use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) return failed(provider, whosTvHttpMessage(response.code))
            val code = ReverseSearchParsers.whosStatusCode(text)
            if (code != null && code != WHOS_TV_CODE_OK) {
                val detail = ReverseSearchParsers.whosMessage(text) ?: ("错误码 " + code)
                return failed(provider, "whos.tv 拒绝搜索：" + detail + "（积分不足时可在官网签到）")
            }
            val direct = ReverseSearchParsers.whosResultUrl(text)
            if (direct != null) return OnlineSearchResult(provider = provider, ok = true, resultUrl = direct)
            searchId = ReverseSearchParsers.whosSearchId(text)
                ?: return failed(provider, "whos.tv 没有返回搜索任务号（接口可能已变更）")
        }

        // 3) 轮询结果（服务端是异步任务，一般几秒，慢的时候十几秒）
        val deadline = System.currentTimeMillis() + WHOS_TV_POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val statusUrl = (WHOS_TV_BASE + WHOS_TV_STATUS_PATH + searchId).toHttpUrlOrNull()
                ?: return failed(provider, "内部错误：状态地址拼装失败")
            get(statusUrl, referer = "$WHOS_TV_BASE/", extraHeader = "X-Requested-With" to "XMLHttpRequest")
                .use { response ->
                    val text = response.body?.string().orEmpty().trim()
                    if (!response.isSuccessful) return failed(provider, whosTvHttpMessage(response.code))
                    val direct = ReverseSearchParsers.whosResultUrl(text)
                    if (direct != null) return OnlineSearchResult(provider = provider, ok = true, resultUrl = direct)
                    val code = ReverseSearchParsers.whosStatusCode(text)
                    if (code == WHOS_TV_CODE_TASK_MISSING) {
                        return failed(provider, "whos.tv 的搜索任务不存在或已过期，请重试")
                    }
                }
            Thread.sleep(WHOS_TV_POLL_INTERVAL_MS)
        }
        return failed(provider, "whos.tv 在 " + (WHOS_TV_POLL_TIMEOUT_MS / 1000) + " 秒内没有给出结果，可到官网手动上传")
    }

    /** JSON 字符串转义（只需要处理引号/反斜杠/控制字符；用户名密码里不会有别的花样）。 */
    private fun jsonString(raw: String): String {
        val out = StringBuilder(raw.length + 2)
        out.append('"')
        for (c in raw) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    private fun post(url: HttpUrl, body: RequestBody, referer: String? = null): Response {
        val builder = Request.Builder()
            .url(url)
            // 这些网页接口会按 UA 返回不同版本（移动端可能直接给 JS 页），用桌面 UA 最稳
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .post(body)
        if (referer != null) builder.header("Referer", referer)
        return client.newCall(builder.build()).execute()
    }

    private fun get(url: HttpUrl, referer: String, extraHeader: Pair<String, String>? = null): Response {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", referer)
            .get()
        if (extraHeader != null) builder.header(extraHeader.first, extraHeader.second)
        return client.newCall(builder.build()).execute()
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
        const val UPLOAD_URL_BAIDU = "https://graph.baidu.com/upload"

        /** 百度识图网页端固定值：表示"从本地上传一张图来搜"。 */
        const val BAIDU_IMAGE_SOURCE = "PC_UPLOAD_SEARCH_FILE"

        /** 百度识图网页端的来源标记；服务端对新客户端偶尔宽容，缺了通常也能用。 */
        const val BAIDU_RANGE_JSON = """{"page_from":"searchIndex","search_from":"result"}"""

        const val WHOS_TV_BASE = "https://whos.tv"
        const val WHOS_TV_LOGIN_PATH = "/api/login"
        const val WHOS_TV_UPLOAD_PATH = "/upload-search"
        const val WHOS_TV_STATUS_PATH = "/search-status/"
        const val WHOS_TV_CODE_OK = 200000
        const val WHOS_TV_CODE_TASK_MISSING = 404000

        /** whos.tv 是异步任务：官方工具箱默认轮询 120 秒，这里给 60 秒（手机端不值得等太久）。 */
        const val WHOS_TV_POLL_TIMEOUT_MS = 60_000L
        const val WHOS_TV_POLL_INTERVAL_MS = 2_000L

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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

        /**
         * 内存 CookieJar：只有 whos.tv 需要（登录后用 cookie 维持会话）。
         *
         * 不落盘是刻意的：这些 cookie 只是临时会话凭据，进程结束就该失效；
         * 每次搜索重新登录一次，代价是 1 个请求，换来"用户改完账号立刻生效"。
         */
        private val cookieJar: CookieJar = object : CookieJar {
            private val store = java.util.concurrent.ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val list = store.getOrPut(url.host) { java.util.Collections.synchronizedList(mutableListOf()) }
                synchronized(list) {
                    for (cookie in cookies) {
                        list.removeAll { it.name == cookie.name }
                        list.add(cookie)
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val list = store[url.host] ?: return emptyList()
                return synchronized(list) { list.filter { it.matches(url) } }
            }
        }

        /** 共用连接池；超时按「一次上传 + 服务端处理」估，20s 足够，也避免用户等太久。 */
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                // whos.tv 的异步任务要轮询，单次 callTimeout 20s 够用，整体等待由 whosTv 自己控制
                .callTimeout(20, TimeUnit.SECONDS)
                .cookieJar(cookieJar)
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

    /** 百度识图返回体里的 "url":"https://graph.baidu.com/s?..."（值可能带转义斜杠）。 */
    private val BAIDU_URL_FIELD = Regex("\"url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /**
     * 从百度识图的上传响应里取结果页链接。
     *
     * 响应形如 {"status":0,"data":{"url":"https://graph.baidu.com/s?..."}}。
     * 只认 graph.baidu.com 的链接：同响应里可能还有别的 url 字段（缩略图、埋点），取第一个会拿错。
     */
    fun baiduResultUrl(body: String): String? =
        BAIDU_URL_FIELD.findAll(body)
            .map { unescapeJsonString(it.groupValues[1]) }
            .firstOrNull { it.contains("graph.baidu.com/s") || it.contains("graph.baidu.com/details") }

    /** whos.tv 上传响应里的任务号：/search-wait/<id>（有时是 JSON 里的一段字符串）。 */
    private val WHOS_TV_SEARCH_ID = Regex("/search-wait/([A-Za-z0-9_-]+)")

    /** whos.tv 任何响应里的 "code":200000 / "code":404000。 */
    private val WHOS_TV_CODE = Regex("\"code\"\\s*:\\s*(-?\\d+)")

    /** whos.tv 响应里的 "message":"..." 或 "msg":"..."（错误提示用的字段名两种都出现过）。 */
    private val WHOS_TV_MESSAGE = Regex("\"(?:message|msg)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** whos.tv 结果页 URL：轮询成功时响应体直接就是一个 https://whos.tv/search/... 链接。 */
    private val WHOS_TV_RESULT_URL = Regex("https?://(?:www\\.)?whos\\.tv/(?:search|v/)[^\"'<>\\s]+")

    /** 取 whos.tv 的任务号（/search-wait/<id> 里的 id）。 */
    fun whosSearchId(body: String): String? =
        WHOS_TV_SEARCH_ID.find(body)?.groupValues?.get(1)

    /** 取 whos.tv 的结果页链接（响应体可能是纯链接，也可能包在 JSON 里）。 */
    fun whosResultUrl(body: String): String? =
        WHOS_TV_RESULT_URL.find(body)?.value

    /** 取 whos.tv 响应里的业务 code。 */
    fun whosStatusCode(body: String): Int? =
        WHOS_TV_CODE.find(body)?.groupValues?.get(1)?.toIntOrNull()

    /** 取 whos.tv 响应里的错误文案。 */
    fun whosMessage(body: String): String? =
        WHOS_TV_MESSAGE.find(body)?.groupValues?.get(1)?.let(::unescapeJsonString)

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

/** whos.tv 没配置账号时的提示：它家必须登录，匿名用不了。 */
internal const val WHOS_TV_NO_ACCOUNT_MESSAGE =
    "whos.tv 需要账号：请在 设置 → 联网识图 填 whos.tv 用户名与密码（每次搜图会扣积分，可到官网签到）"

/** whos.tv 的 HTTP 状态码到中文提示。 */
internal fun whosTvHttpMessage(code: Int): String = when (code) {
    // Cloudflare 挡在我们和 whos.tv 之间时，返回的通常是 403 + 人机校验页
    403 -> "whos.tv 返回 403：可能被 Cloudflare 人机校验拦住了，请到 whos.tv 网页里手动上传（或用浏览器登录一次再试）"
    429 -> "whos.tv 请求太频繁，请稍后再试"
    401 -> "whos.tv 登录失败：用户名或密码不对，请到 设置 → 联网识图 检查"
    else -> "whos.tv 返回 HTTP " + code
}

/** SauceNAO 的 HTTP 状态码到中文提示（服务端连 status 都不给时只能靠它）。 */
internal fun sauceNaoHttpMessage(code: Int): String = when (code) {
    403 -> "SauceNAO 拒绝访问：API Key 无效或额度已用尽，请到 设置 → 联网识图 检查"
    413 -> "图片太大，SauceNAO 拒绝接收，请先裁剪或换一张"
    429 -> "SauceNAO 请求太频繁，请稍后再试"
    400 -> "SauceNAO 无法处理这张图片（格式或内容不受支持），请换一张"
    else -> "SauceNAO 返回 HTTP " + code
}
