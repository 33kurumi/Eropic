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
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * 联网以图搜图：把本地图片上传到各家「以图搜图」服务，拿回一个能直接打开的结果页。
 *
 * v0.5 起**协议完全照抄 kitUIN/PicImageSearch**（https://github.com/kitUIN/PicImageSearch，
 * 各引擎源码在 PicImageSearch/engines 目录下的 *.py）：端点、表单字段名、参数、结果页取法都以它为准，
 * 每个函数头部注明对应的引擎文件，出问题时逐行对照即可。
 *
 * 这里用的都是**非官方网页接口**（这些服务没有对个人开发者开放的匿名上传 API），
 * 一旦某家改版，照着注释重新抓一次就能修，不用去翻逆向脚本。
 *
 * 五家的现状（2026-10 对照 PicImageSearch；v0.6 去掉了 Yandex——结果页依赖服务端 302，稳定性差，
 * 且国内同样要代理，实测反而不如百度/ascii2d 好用）：
 * - Google Lens：POST /v3/upload，字段 encoded_image；**带 hl 参数会 403**（PicImageSearch 注释明确写了）；
 * - 百度识图：POST graph.baidu.com/upload，字段 image + from=pc，JSON 里取 data.url；
 *   **不需要 Cookie**，但必须带空的 `Acs-Token` 头（缺了返回 {"status":1,"msg":"Reject"}），
 *   且实测 >5MB 会被直接掐断连接、HEIC 不认 —— 所以超过 4MB 或非 jpeg/png 时先本地转 1600 长边 JPEG；
 * - SauceNAO：官方 API，必须带个人 key，表单 api_key/output_type=2/file；
 * - ascii2d：POST /search/file?type=color，字段 file，成功后 302 到 /search/color/<hash>；
 * - IQDB：POST iqdb.org，字段 file，结果页就是重定向后的 URL。
 *
 * 失败一律走 [OnlineSearchResult.ok] = false + 中文 [OnlineSearchResult.message]，不抛异常：
 * 用户在电梯里没信号、图片被服务商限流都属于预期内情况，不该让 UI 处理异常。
 */
class OnlineImageSearch(
    private val contentResolver: ContentResolver,
    /** 匿名临时图床：URL 型服务商（onegai/lenso/IQDB/ascii2d/Lens）先把图传上去换公网链接。 */
    private val imageHost: ImageHost = ImageHost(),
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

    private suspend fun execute(
        bytes: ByteArray,
        mime: String,
        provider: ReverseImageProvider,
    ): OnlineSearchResult = when (provider) {
        ReverseImageProvider.GOOGLE_LENS -> googleLens(bytes, mime)
        ReverseImageProvider.BAIDU -> baidu(bytes, mime)
        ReverseImageProvider.SAUCENAO -> sauceNao(bytes, mime)
        // ascii2d / IQDB 都支持 multipart 直传（PicImageSearch 用的就是这条），
        // 直传能成就不必经过图床 —— 图床正是 v0.9 在用户手机上失败的那一环。
        ReverseImageProvider.ASCII2D -> ascii2d(bytes, mime)
        ReverseImageProvider.IQDB -> iqdb(bytes, mime)
        // onegai / Lenso 只收公网 URL，没有匿名上传入口，只能经图床中转（见 ImageHost）
        ReverseImageProvider.ONEGAI -> urlSearch(provider, bytes, mime) { publicUrl ->
            "https://onegai.moe/search?url=" + Uri.encode(publicUrl, "/:")
        }
        ReverseImageProvider.LENSO -> urlSearch(provider, bytes, mime) { publicUrl ->
            // Lenso 的页面入口需要带 utm_source=sbi，否则会落到首页而不是「按图搜索」
            "https://lenso.ai/en/search-by-url?url=" + Uri.encode(publicUrl, "/:") + "&utm_source=sbi"
        }
    }

    /**
     * URL 型服务商的统一通道：先匿名上传换公网链接，再把链接拼成搜索页给浏览器打开。
     *
     * 为什么不能 multipart 直传：这几家的入口是「给我一个 URL」而不是「收一个文件」，
     * App 的 content:// 地址外网访问不到，所以必须中转一次（FlutterPicOrigin 用自建 Worker 做同样的事）。
     */
    private suspend fun urlSearch(
        provider: ReverseImageProvider,
        bytes: ByteArray,
        mime: String,
        buildUrl: (String) -> String,
    ): OnlineSearchResult {
        val upload = imageHost.uploadDetailed(bytes, mime)
        val publicUrl = upload.url
            ?: return failed(
                provider,
                provider.label + " 只接受公网图片链接，而所有临时图床都上传失败了（" +
                    upload.attempted.joinToString("；") +
                    "）。这类引擎必须先把图放到公网，通常是网络到不了图床；" +
                    "可挂代理后重试，或改用百度识图 / ascii2d / IQDB（它们收文件本体，不需要图床）",
            )
        return OnlineSearchResult(
            provider = provider,
            ok = true,
            resultUrl = buildUrl(publicUrl),
            message = "已把源图上传到临时图床（会自动过期删除），结果页在浏览器里打开",
        )
    }

    /** multipart 直传拿到的一张结果页（HTML + 跟随重定向后的最终地址）。 */
    private data class HtmlPage(val html: String, val finalUrl: String)

    /**
     * 用 multipart 把图片直接 POST 给某家，拿回结果页 HTML。
     *
     * 直传的好处：不经过第三方图床，少一个失败点，也不会把图片给别人托管。
     * ascii2d / IQDB 都支持这条（PicImageSearch 各引擎里用的就是 file 字段）。
     * okhttp 默认跟随重定向，所以 ascii2d 的 302 会自己落到 /search/color/<hash>。
     */
    private fun postImageForHtml(
        url: String,
        fieldName: String,
        bytes: ByteArray,
        mime: String,
        referer: String? = null,
    ): HtmlPage? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(fieldName, IMAGE_FILE_NAME, bytes.toRequestBody(mime.toMediaType()))
            .build()
        return post(parsed, body, referer = referer).use { response ->
            if (!response.isSuccessful) return@use null
            HtmlPage(response.body?.string().orEmpty(), response.request.url.toString())
        }
    }

    /**
     * ascii2d（对照 PicImageSearch/engines/ascii2d.py）。
     *
     * POST https://ascii2d.net/search/file，字段 file；成功后 302 到 /search/color/<hash>，
     * 页面里 div.item-box 是命中条目（OnlineParsers.ascii2dHtml 解析）。
     * 判定成功的条件就是「最终地址进了 /search/color 或 /search/bovw」——
     * v0.6 只判断 URL 里有没有 uri= 参数，把成功也当失败，用户看到的就是「没有结果」。
     *
     * 直传失败（网络/服务改版）才退回图床 + GET /search/url/<公网链接>。
     */
    private suspend fun ascii2d(bytes: ByteArray, mime: String): OnlineSearchResult {
        val provider = ReverseImageProvider.ASCII2D
        val page = runCatching {
            postImageForHtml(
                url = "https://ascii2d.net/search/file",
                fieldName = "file",
                bytes = bytes,
                mime = mime,
                referer = "https://ascii2d.net/",
            )
        }.getOrNull()
        if (page != null) {
            val items = OnlineParsers.ascii2dHtml(page.html, page.finalUrl)
            val reachedResult = page.finalUrl.contains("/search/color") || page.finalUrl.contains("/search/bovw")
            if (items.isNotEmpty()) {
                return OnlineSearchResult(
                    provider = provider,
                    ok = true,
                    resultUrl = page.finalUrl,
                    items = items.toOnlineHits(),
                    message = "ascii2d 返回 " + items.size + " 个匹配，也可在浏览器查看完整结果页",
                )
            }
            if (reachedResult) {
                return OnlineSearchResult(
                    provider = provider,
                    ok = true,
                    resultUrl = page.finalUrl,
                    message = "ascii2d 已生成结果页（没有解析出条目），在浏览器里打开查看",
                )
            }
        }
        return urlSearch(provider, bytes, mime) { publicUrl ->
            ASCII2D_URL_SEARCH_PREFIX + Uri.encode(publicUrl, "/:")
        }
    }

    /**
     * IQDB（对照 PicImageSearch/engines/iqdb.py）。
     *
     * POST https://iqdb.org/，字段 file；响应**直接就是结果页 HTML**（不重定向），
     * 用 OnlineParsers.iqdbHtml 解析表格里的相似度 / 来源站 / 链接。
     *
     * 注意 v0.6 的坑：当时把重定向后的地址当结果页交给浏览器，用户看到的是 IQDB 首页。
     * 现在优先在 App 内展示解析结果，只有在直传失败时才退回图床 + ?url= 模式。
     */
    private suspend fun iqdb(bytes: ByteArray, mime: String): OnlineSearchResult {
        val provider = ReverseImageProvider.IQDB
        val page = runCatching {
            postImageForHtml(
                url = "https://iqdb.org/",
                fieldName = "file",
                bytes = bytes,
                mime = mime,
                referer = "https://iqdb.org/",
            )
        }.getOrNull()
        if (page != null) {
            val items = OnlineParsers.iqdbHtml(page.html, page.finalUrl)
            if (items.isNotEmpty()) {
                return OnlineSearchResult(
                    provider = provider,
                    ok = true,
                    items = items.toOnlineHits(),
                    message = "IQDB 返回 " + items.size + " 个匹配",
                )
            }
        }
        return urlSearch(provider, bytes, mime) { publicUrl -> iqdbUrl(publicUrl) }
    }

    /**
     * Google Lens（对照 PicImageSearch/engines/google_lens.py + FlutterPicOrigin 的 URL 模式）。
     *
     * 两条路，优先第一条：
     * 1. **URL 模式**（v0.7 起为主）：https://lens.google.com/uploadbyurl?url=<公网图片链接>。
     *    用户反馈 v0.6 的 POST 上传「实测不对」——原因是 POST 后 Lens 会 303 到
     *    lens.google.com/search?ep=... 这种**依赖上传会话**的地址，用系统浏览器打开它时没有那份会话，
     *    于是落在「请上传图片」的空页上。URL 模式的结果页是自包含的，换设备/换浏览器都能打开，
     *    FlutterPicOrigin 也正是这么做的。
     * 2. **multipart 模式**（回退）：POST https://lens.google.com/v3/upload，字段 encoded_image。
     *    注意**不能带 hl 参数**（PicImageSearch 注释原话：带 hl 直接 403），只能带 q。
     *    拿到响应后：最终 URL 是 /search? 就直接用；否则按 PicImageSearch 的做法从 HTML 里
     *    找 a[href*="udm=37|44|48"]（37 商品 / 44 同图 / 48 视觉匹配）拼成 www.google.com 链接。
     */
    private suspend fun googleLens(bytes: ByteArray, mime: String): OnlineSearchResult {
        val provider = ReverseImageProvider.GOOGLE_LENS
        val upload = imageHost.uploadDetailed(bytes, mime)
        val publicUrl = upload.url
        if (publicUrl != null) {
            return OnlineSearchResult(
                provider = provider,
                ok = true,
                resultUrl = "https://lens.google.com/uploadbyurl?url=" + Uri.encode(publicUrl, "/:"),
                message = "已把源图上传到临时图床（1 小时后自动删除），结果页在浏览器里打开",
            )
        }
        // 图床不可用（国内直连 catbox 常失败）时退回直传，至少还有机会拿到结果页
        val url = UPLOAD_URL_GOOGLE_LENS.toHttpUrlOrNull()
            ?: return failed(provider, "内部错误：上传地址拼装失败")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("encoded_image", IMAGE_FILE_NAME, bytes.toRequestBody(mime.toMediaType()))
            .build()
        post(url, body).use { response ->
            val finalUrl = response.request.url.toString()
            if (finalUrl.contains("/search?") || finalUrl.contains("/uploadbyurl?")) {
                return OnlineSearchResult(provider = provider, ok = true, resultUrl = finalUrl)
            }
            val text = response.body?.string().orEmpty()
            val parsed = ReverseSearchParsers.googleLensResultUrl(text)
            return if (parsed != null) {
                OnlineSearchResult(provider = provider, ok = true, resultUrl = parsed)
            } else {
                failed(
                    provider,
                    "Google Lens 没有返回结果页（接口可能已变更，HTTP " + response.code + "）",
                )
            }
        }
    }

    /**
     * 百度识图（对照 PicImageSearch/engines/baidu.py）。
     *
     * 端点（非官方）：POST https://graph.baidu.com/upload
     * 请求头：Acs-Token: 空字符串（PicImageSearch 显式带了；**不能用 Cookie 代替**，2026-10-28 实测
     *        缺这个头会返回 {"status":1,"msg":"Reject"}，带了空值才成功）
     * 表单字段：image = 图片文件、from = pc
     * 返回 JSON：{"status":0,"data":{"url":"https://graph.baidu.com/s?..."}}，取 data.url 直接打开即可。
     * 该结果页里 window.cardData 的 cardName 可能是 noresult（无结果）/ same（同图）/ simipic（相似图），
     * 那是页面内的事，我们只要把用户送到结果页。
     *
     * 体积限制（v0.6 实测）：空图返回 status=1002「Params illegal」；7MB 损坏图会直接连接被断；
     * 手机原图 6-10MB，所以超过 [BAIDU_SHRINK_THRESHOLD_BYTES] 或非 jpeg/png 时先本地缩成 JPEG 再传，
     * 否则用户只会看到一句莫名其妙的失败。
     *
     * 这是整套联网识图里**唯一国内直连不需要代理**的一家。
     */
    private fun baidu(bytes: ByteArray, mime: String): OnlineSearchResult {
        val provider = ReverseImageProvider.BAIDU
        val url = UPLOAD_URL_BAIDU.toHttpUrlOrNull()
            ?: return failed(provider, "内部错误：上传地址拼装失败")
        val needsShrink = bytes.size > BAIDU_SHRINK_THRESHOLD_BYTES ||
            (mime != "image/jpeg" && mime != "image/png")
        // 缩图失败（格式不支持/内存不足）时退回原图：宁可让百度报错，也不要直接告诉用户搜不了
        val payload = if (needsShrink) shrinkForUpload(bytes, BAIDU_LONG_EDGE) ?: bytes else bytes
        val payloadMime = if (payload !== bytes) "image/jpeg" else mime
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", IMAGE_FILE_NAME, payload.toRequestBody(payloadMime.toMediaType()))
            .addFormDataPart("from", "pc")
            .build()
        post(url, body, referer = "https://graph.baidu.com/", extraHeaders = listOf("Acs-Token" to "")).use { response ->
            if (!response.isSuccessful) return failed(provider, baiduHttpMessage(response.code))
            val text = response.body?.string().orEmpty()
            val status = ReverseSearchParsers.baiduStatus(text)
            if (status != null && status != 0) return failed(provider, baiduStatusMessage(status))
            val resultUrl = ReverseSearchParsers.baiduResultUrl(text)
                ?: return failed(
                    provider,
                    "百度识图没有返回结果页（接口可能已变更，HTTP " + response.code + "）",
                )
            return OnlineSearchResult(provider = provider, ok = true, resultUrl = resultUrl)
        }
    }

    /**
     * SauceNAO（二次元 / 插画以图搜图：pixiv、twitter、danbooru 等索引）。
     *
     * 对照 PicImageSearch/engines/saucenao.py：官方 API，参数 testmode=0、numres=5、output_type=2、
     * hide=0、db=999、minsim=30，有 key 时再加 api_key；文件字段 file。
     *
     * 返回形如：
     * {"header":{"status":0,"results_returned":2,...},
     *  "results":[{"header":{"similarity":"95.6","index_name":"Pixiv"},
     *              "data":{"ext_urls":["https://www.pixiv.net/artworks/123"],...}}, ...]}
     * header.status：0 成功，-1 key 无效，-2 限流，-3 搜索出错（其它负数为未知错误）。
     *
     * 结果 URL：JSON 里**没有**可复用的「网页结果页」链接，但每个命中都带 data.ext_urls，
     * 即作品在原站的页面。直接打开这个对用户最有用。
     *
     * 上传体积：网页端上限只有几 MB，手机原图常有 3-10MB，超过阈值先在本地缩到 1600 长边再传。
     */
    private suspend fun sauceNao(bytes: ByteArray, mime: String): OnlineSearchResult {
        val provider = ReverseImageProvider.SAUCENAO
        // v0.6：key 内置在代码里（用户自己的账号），设置页不再有输入框，也不用再判断"未配置"
        val apiKey = SAUCE_NAO_API_KEY
        val shrunk = shrinkForUpload(bytes, SAUCE_NAO_LONG_EDGE, SAUCE_NAO_SHRINK_THRESHOLD_BYTES)
        val payload = shrunk ?: bytes
        val payloadMime = if (shrunk != null) "image/jpeg" else mime
        val url = UPLOAD_URL_SAUCE_NAO.toHttpUrlOrNull()
            ?: return failed(provider, "内部错误：上传地址拼装失败")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("api_key", apiKey)
            // output_type=2 表示要 JSON；0 是网页版，返回的是没有可复用 URL 的一次性页面
            .addFormDataPart("output_type", "2")
            .addFormDataPart("testmode", "0")
            .addFormDataPart("numres", "5")
            .addFormDataPart("hide", "0")
            .addFormDataPart("db", "999")
            .addFormDataPart("minsim", "30")
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
            // v0.7：不再「只挑一个 ext_url 当结果页」，改成把命中列表带回 App 内展示。
            // 用户反馈的原话是「显示找到 8 个结果什么意思，不给我跳转到结果地方吗」——根源就是
            // v0.6 在没有 ext_url 时把整次搜索判成失败，只留下一句话。
            val parsed = OnlineParsers.sauceNao(text)
            val items = parsed.results.toOnlineHits()
            val returned = ReverseSearchParsers.sauceNaoResultsReturned(text) ?: parsed.results.size
            if (items.isEmpty()) {
                // v1.0.0：用户的原话是「SauceNAO 应该返回它的搜索结果页面」。
                // API 的 JSON 里确实没有可复用的结果页链接，但网页版接受 ?url=<公网图片链接>，
                // 所以先试一次图床；换到链接就打开网页版结果页（和浏览器里手动搜一样），
                // 图床不通时再退回纯文字提示。
                val upload = imageHost.uploadDetailed(payload, payloadMime)
                val publicUrl = upload.url
                if (publicUrl != null) {
                    return OnlineSearchResult(
                        provider = provider,
                        ok = true,
                        resultUrl = "https://saucenao.com/search.php?url=" + Uri.encode(publicUrl, "/:"),
                        message = "SauceNAO 的 API 没有返回条目，已改用网页版结果页（临时图片会在图床上自动过期）",
                    )
                }
                return failed(
                    provider,
                    if (returned <= 0) {
                        "SauceNAO 没有找到相似来源，可换一张更清晰的图，或到 saucenao.com 手动检索"
                    } else {
                        "SauceNAO 报告有 " + returned + " 个结果，但解析后没有可展示条目（接口可能已变更），" +
                            "网页版结果页也打不开：" + upload.attempted.joinToString("；")
                    },
                )
            }
            return run {
                OnlineSearchResult(
                    provider = provider,
                    ok = true,
                    // 有原站链接时顺手给出「在浏览器打开」；没有也不影响 App 内列表展示
                    resultUrl = items.firstNotNullOfOrNull { it.extUrl },
                    items = items,
                    message = if (returned > items.size) {
                        "共 " + returned + " 个结果，这里显示相似度最高的 " + items.size + " 个"
                    } else {
                        null
                    },
                )
            }
        }
    }

    /**
     * 把图片缩到 [longEdge] 长边并重新编码成 JPEG。
     *
     * [thresholdBytes] > 0 时表示"只有超过这个体积才缩"，调用方判断过就不必再传；
     * 传 0 表示无条件缩（百度识图这种按格式/体积判断后已经决定的场景）。
     *
     * 用 ImageDecoder 而不是 BitmapFactory：它会按 EXIF 自动摆正方向，
     * 而 pixiv 这类索引对旋转很敏感，转了 90 度匹配率会明显下降。
     * 缩失败（格式不支持、内存不足）返回 null，调用方按原图上传 ——
     * 宁可让服务端拒绝，也不能因为本地转码失败就直接告诉用户「搜不了」。
     */
    private fun shrinkForUpload(bytes: ByteArray, longEdge: Int, thresholdBytes: Int = 0): ByteArray? {
        if (thresholdBytes > 0 && bytes.size <= thresholdBytes) return null
        return runCatching {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val width = info.size.width.coerceAtLeast(1)
                val height = info.size.height.coerceAtLeast(1)
                val scale = min(1f, longEdge.toFloat() / max(width, height).toFloat())
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
     * IQDB 的查询地址：`https://iqdb.org/?url=<公网图片链接>`。
     *
     * v0.6 用 POST https://iqdb.org（字段 file）——用户反馈「IQDB 为什么是直接跳转过去没有把图片传进去」，
     * 原因是 POST 被服务端重定向到 iqdb.org/?url=... 时 okhttp 自动跟随成了一次**GET 首页**，
     * 我们又把重定向后的 URL 当结果页打开，于是用户看到的就是 IQDB 首页。改成 URL 模式后，
     * 图片链接是查询参数的一部分，服务端一定会按它检索。
     */
    private fun iqdbUrl(publicUrl: String): String =
        "https://iqdb.org/?url=" + Uri.encode(publicUrl, "/:")

    /** 把网页解析层（[OnlineParsers]）的命中转成 UI 模型，见 [OnlineHit] 的注释。 */
    internal fun List<ParsedHit>.toOnlineHits(): List<OnlineHit> = map { hit ->
        OnlineHit(
            title = hit.title,
            similarity = hit.similarity,
            extUrl = hit.extUrl,
            pageUrl = hit.pageUrl,
            thumbnailUrl = hit.thumbnailUrl,
            characters = hit.characters,
            material = hit.material,
            creator = hit.creator,
        )
    }

    private fun post(
        url: HttpUrl,
        body: okhttp3.RequestBody,
        referer: String? = null,
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ): Response {
        val builder = Request.Builder()
            .url(url)
            // 这些网页接口会按 UA 返回不同版本（移动端可能直接给 JS 页），用桌面 UA 最稳
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .post(body)
        if (referer != null) builder.header("Referer", referer)
        for ((name, value) in extraHeaders) builder.header(name, value)
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
        const val UPLOAD_URL_BAIDU = "https://graph.baidu.com/upload"
        const val UPLOAD_URL_SAUCE_NAO = "https://saucenao.com/search.php"
        /**
         * ascii2d 的 URL 检索路径：`https://ascii2d.net/search/url/<公网图片链接>`。
         *
         * 它同时有 POST /search/file（表单上传，PicImageSearch 用的就是它）和这条 GET 路径；
         * 我们选 GET 是因为 multipart 那次的 302 目标 /search/color/<hash> 是**服务端会话**地址，
         * 用户反馈「ascii2d 显示没有跳转到结果页」正是 v0.6 判断 302 失败导致的。
         */
        const val ASCII2D_URL_SEARCH_PREFIX = "https://ascii2d.net/search/url/"

        const val DEFAULT_MIME = "image/jpeg"
        const val IMAGE_FILE_NAME = "image.jpg"

        /** 20MB：比各家上限都宽，真正的限制交给服务端；这里只防「把 100MB 扫描件读进内存」。 */
        const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024

        /**
         * v0.6：SauceNAO 的 API Key 内置（用户本人的 key，写在需求里要求不再做成设置项）。
         *
         * 放在代码里意味着公开仓库里能看到它——这点已经和用户确认过（侧载自用，key 是他自己的）。
         */
        const val SAUCE_NAO_API_KEY = "8673cc597E30FF0A9BFFDEDb8E548F96F5EBA256"

        /** 百度上传接口实测 >5MB 会被掐断连接；4MB 留余量，缩完通常只剩几百 KB。 */
        const val BAIDU_SHRINK_THRESHOLD_BYTES = 4 * 1024 * 1024
        const val BAIDU_LONG_EDGE = 1600

        /** 超过 1.5MB 就先缩图：SauceNAO 网页上传上限只有几 MB，手机原图基本都超。 */
        const val SAUCE_NAO_SHRINK_THRESHOLD_BYTES = 1500 * 1024

        /** 1600 长边足够 SauceNAO 做特征匹配，同时能把体积压到几百 KB。 */
        const val SAUCE_NAO_LONG_EDGE = 1600
        const val SAUCE_NAO_JPEG_QUALITY = 85

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Safari/537.36"

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

    /** Lens 结果页：老式 lens.google.com 链接。 */
    private val GOOGLE_LENS_RESULT_URL = Regex("https://lens\\.google\\.com/(?:search|uploadbyurl)\\?[^\"'<>\\s]+")

    /** Lens 新版跳转到 google.com 的 udm=37/44/48 页面（PicImageSearch 就是按这个找链接）。 */
    private val GOOGLE_UDM_LINK = Regex("href=\"(/search\\?[^\"]*udm=(?:37|44|48)[^\"]*)\"")

    /** SauceNAO 顶层 header 里的 "status":0/-1/-2/-3。 */
    private val SAUCE_NAO_STATUS = Regex("\"status\"\\s*:\\s*(-?\\d+)")

    /** SauceNAO 顶层 header 里的 "results_returned":2。 */
    private val SAUCE_NAO_RESULTS_RETURNED = Regex("\"results_returned\"\\s*:\\s*(\\d+)")

    /** 单个命中里的 "similarity":"95.6"（注意是字符串，不是数字）。 */
    private val SAUCE_NAO_SIMILARITY = Regex("\"similarity\"\\s*:\\s*\"([0-9.]+)\"")

    private val SAUCE_NAO_INDEX_NAME = Regex("\"index_name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    private val SAUCE_NAO_TITLE = Regex("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /**
     * 从 Google Lens 的 HTML 响应里抓结果页链接。
     *
     * 先找新版 udm=37/44/48 的相对链接（拼到 www.google.com），再退回老式 lens.google.com 链接。
     * 与 PicImageSearch/engines/google_lens.py 的 udm 判断保持一致。
     */
    fun googleLensResultUrl(text: String): String? {
        val udm = GOOGLE_UDM_LINK.find(text)?.groupValues?.get(1)
        if (udm != null) return "https://www.google.com" + udm.replace("&amp;", "&")
        return GOOGLE_LENS_RESULT_URL.find(text)?.value?.replace("&amp;", "&")
    }

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

    /** 百度识图返回体顶层的 "status"：0 成功、1 Reject、1002 参数非法…… */
    private val BAIDU_STATUS = Regex("\"status\"\\s*:\\s*(-?\\d+)")

    /** 百度识图的业务状态码；响应不是 JSON 时为 null。 */
    fun baiduStatus(body: String): Int? =
        BAIDU_STATUS.find(body)?.groupValues?.get(1)?.toIntOrNull()

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

/** 百度识图业务状态码到中文提示（v0.6：实测缺 Acs-Token 头会回 status=1 Reject）。 */
internal fun baiduStatusMessage(status: Int): String = when (status) {
    1 -> "百度识图拒绝了这次上传（校验失败或接口已变更），请稍后再试或改用其它识图服务"
    1002 -> "百度识图认为图片参数不合法，请换一张图片再试"
    else -> "百度识图返回错误码 " + status + "，请稍后再试"
}

/** 百度识图的 HTTP 状态码到中文提示。 */
internal fun baiduHttpMessage(code: Int): String = when (code) {
    413 -> "图片太大，百度识图拒绝接收，请先裁剪或换一张"
    429 -> "百度识图请求太频繁，请稍后再试"
    else -> "百度识图返回 HTTP " + code
}

/** SauceNAO 业务错误码（header.status）到中文提示；key 已内置，用户不需要去设置里改。 */
internal fun sauceNaoStatusMessage(status: Int): String = when (status) {
    -1 -> "SauceNAO 认为 API Key 无效或额度已用尽（key 内置在应用里，通常是当日额度用完），请稍后再试"
    -2 -> "SauceNAO 请求太频繁（免费额度有冷却时间），请稍后再试"
    -3 -> "SauceNAO 搜索出错，请稍后再试"
    else -> "SauceNAO 返回错误码 " + status + "，请稍后再试"
}

/** SauceNAO 的 HTTP 状态码到中文提示（服务端连 status 都不给时只能靠它）。 */
internal fun sauceNaoHttpMessage(code: Int): String = when (code) {
    403 -> "SauceNAO 拒绝访问：key 无效或额度已用尽（内置 key，通常稍后自动恢复）"
    413 -> "图片太大，SauceNAO 拒绝接收，请先裁剪或换一张"
    429 -> "SauceNAO 请求太频繁，请稍后再试"
    400 -> "SauceNAO 无法处理这张图片（格式或内容不受支持），请换一张"
    else -> "SauceNAO 返回 HTTP " + code
}