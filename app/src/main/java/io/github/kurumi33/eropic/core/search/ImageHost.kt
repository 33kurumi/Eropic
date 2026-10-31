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
 * 为什么非要有这一步（v0.7）：
 * onegai.moe、lenso.ai、IQDB、ascii2d 这些站点只提供「给我一个图片 URL」的 GET 入口
 * （`/search?url=...`、`/?url=...`），没有匿名 multipart 上传接口，App 又没法把自己的
 * `content://` 地址暴露给外网，所以必须先在公网上放一份，再把 URL 拼进搜索页。
 * FlutterPicOrigin（https://github.com/4evergr8/FlutterPicOrigin）用的也是这个思路，
 * 区别是它要求用户自己部署 Cloudflare Worker，这里换成现成的匿名图床，用户零配置。
 *
 * 选 litterbox 而不是 catbox 主站的原因：litterbox 是 catbox 的**临时**空间，
 * 上传时必须给 `time` 参数（这里用 1h），一小时后文件自动删除 —— 用户把私人照片
 * 传到公网这件事，时间越短越好。主站 catbox 是永久托管，不采用。
 *
 * 失败时返回 null，调用方负责给用户一句中文说明（不要在这里抛异常，
 * 联网失败是这个功能的常态，不是程序错误）。
 */
class ImageHost(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * 上传 [bytes]，返回形如 `https://litter.catbox.moe/ab12cd.jpg` 的公网 URL。
     *
     * @param mime 用于给文件挑扩展名；litterbox 按文件名后缀判断类型，后缀错了部分站点会拒收。
     */
    suspend fun upload(bytes: ByteArray, mime: String): String? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext null
        val fileName = "eropic." + extensionOf(mime)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            // 1h：litterbox 最短档，够用户看完结果页，过期后链接自动失效
            .addFormDataPart("time", "1h")
            .addFormDataPart("fileToUpload", fileName, bytes.toRequestBody(mime.toMediaType()))
            .build()
        val request = Request.Builder().url(UPLOAD_URL).post(body).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body?.string().orEmpty().trim()
                // 成功时响应体就是一行纯文本 URL；失败会给一段 HTML 错误页
                text.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            }
        }.getOrNull()
    }

    private fun extensionOf(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        else -> "jpg"
    }

    companion object {
        /** litterbox 的匿名上传端点，参数很简单：reqtype=fileupload + time + fileToUpload。 */
        const val UPLOAD_URL = "https://litterbox.catbox.moe/resources/internals/api.php"

        /** 上传比普通 API 慢（要落盘 + 生成 CDN 链接），给到 30 秒。 */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }
}
