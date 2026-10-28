package io.github.kurumi33.eropic.core.search

/**
 * 本地「以图搜图」的一条命中。
 *
 * [similarity] 用 0..100 的百分比而不是 0..1 的小数：UI 直接显示「92.3%」，每个调用点不用再乘一次 100，
 * 也避免 Float/Double 混用后出现 0.9230000001 这种脏数据。
 * 同时保留 [dhashDistance] / [phashDistance] 两个原始距离：用户问「为什么这张排后面」时，
 * 详情页可以直接把两个距离摆出来，不用再反推。
 */
data class SimilarImageHit(
    val mediaId: Long,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val dateTakenMs: Long,
    /** 0..64 的 dHash 汉明距离，越小越像。 */
    val dhashDistance: Int,
    /** 0..64 的 pHash 汉明距离；源图或候选缺 pHash 时会退化成 dHash 距离（见 [SimilarityMath.similarity]）。 */
    val phashDistance: Int,
    /** 0.0..100.0 的综合相似度百分比，按它降序返回。 */
    val similarity: Double,
)

/**
 * 本地相似图查询参数。
 *
 * 三个参数都有默认值，UI 只暴露「关键词 + 最多几条」即可；
 * [maxDistance] 是 dHash 距离上限，默认 16（约等于「肉眼能看出是同一张」），
 * 放宽到 24 以上会开始混入同场景不同拍的照片。
 */
data class SimilarityQuery(
    /**
     * 文件名 / 目录关键词，语义与图库搜索一致：不区分大小写的子串匹配，
     * `%` 与 `_` 按普通字符处理（用户在搜索框里打 % 是想要 % 这个字符，不是通配符）。
     */
    val keyword: String? = null,
    /** 最多返回多少条，会被夹到 [MIN_LIMIT]..[MAX_LIMIT]。 */
    val limit: Int = DEFAULT_LIMIT,
    /** dHash 距离上限，会被夹到 0..64。 */
    val maxDistance: Int = DEFAULT_MAX_DISTANCE,
) {
    companion object {
        const val DEFAULT_LIMIT = 200
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 1000
        const val DEFAULT_MAX_DISTANCE = 16
    }
}

/**
 * 联网识图服务。
 *
 * 用 enum 而不是 sealed class：这里的成员只有「服务商」一个维度，每个服务商的展示名与官网是固定数据，
 * UI 用 `entries` 直接铺一排按钮；具体某次搜索成功与否由 [OnlineSearchResult] 表达，不需要为结果再造一层类型。
 * [homeUrl] 是兜底入口：接口失效时 UI 还能让用户去官网手动传图。
 */
enum class ReverseImageProvider(val label: String, val homeUrl: String) {
    GOOGLE_LENS("Google Lens", "https://lens.google.com/"),
    YANDEX("Yandex", "https://yandex.com/images/"),
    BING("Bing", "https://www.bing.com/images"),
    TINEYE("TinEye", "https://tineye.com/"),
    SAUCENAO("SauceNAO", "https://saucenao.com/"),
    ASCII2D("ascii2d", "https://ascii2d.net/"),
    IQDB("IQDB", "https://iqdb.org/"),
    TRACEMOE("trace.moe", "https://trace.moe/"),
}

/**
 * 一次联网识图的结果。
 *
 * 刻意用「ok + message」而不是抛异常或 sealed Success/Failure：
 * - 联网失败是常态（离线、被墙、服务商改接口），不是程序错误，不该让调用方 try/catch；
 * - [message] 是给用户看的中文原因，UI 直接展示，不用自己翻译错误码；
 * - 成功判定统一为 `ok && !resultUrl.isNullOrBlank()`，UI 少写一个 if。
 */
data class OnlineSearchResult(
    val provider: ReverseImageProvider,
    val ok: Boolean,
    /** 成功时可直接用浏览器打开的结果页；失败时为 null。 */
    val resultUrl: String? = null,
    /** 失败原因（中文）；成功时为 null。 */
    val message: String? = null,
)

/**
 * 相似度计算的纯函数集合。
 *
 * 单独抽出来的原因：权重、阈值、距离口径是全 App 唯一的定义，
 * 既要在 Repository 里用，也要能在纯 JVM 单测里断言（不能依赖 Android 类）。
 * internal 即可：只允许 core.search 包内使用，避免 UI 层自己再算一遍导致两处口径不一致。
 */
internal object SimilarityMath {

    /** 64 位哈希的最大汉明距离。 */
    const val MAX_HAMMING_DISTANCE = 64

    /** dHash 与 pHash 在综合相似度里的权重：dHash 对结构更敏感，pHash 对亮度/缩放更宽容。 */
    private const val DHASH_WEIGHT = 0.6
    private const val PHASH_WEIGHT = 0.4

    /** 两个 64 位哈希的汉明距离；用 Long.bitCount 而不是自己数位，JVM 会把它编译成单条指令。 */
    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /**
     * 综合相似度百分比：`100 * (1 - (0.6 * dhash + 0.4 * phash) / 64)`。
     *
     * 距离为 0 时是 100%，两个哈希都完全不同时是 0%。
     * 缺 pHash 时调用方会把 phashDistance 传成 dhashDistance，此时两个权重相加为 1，等价于只按 dHash 计分。
     */
    fun similarity(dhashDistance: Int, phashDistance: Int): Double {
        val combined = DHASH_WEIGHT * dhashDistance + PHASH_WEIGHT * phashDistance
        return (100.0 * (1.0 - combined / MAX_HAMMING_DISTANCE)).coerceIn(0.0, 100.0)
    }

    fun clampLimit(limit: Int): Int = limit.coerceIn(SimilarityQuery.MIN_LIMIT, SimilarityQuery.MAX_LIMIT)

    fun clampDistance(distance: Int): Int = distance.coerceIn(0, MAX_HAMMING_DISTANCE)

    /**
     * 关键词是否命中：文件名或目录任一包含即算命中，忽略大小写。
     *
     * 空关键词一律视为「不筛选」；`%` 与 `_` 按字面比较（与 [SimilarityQuery.keyword] 的文档一致）。
     */
    fun matchesKeyword(displayName: String, relativePath: String, keyword: String?): Boolean {
        val needle = keyword?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        return displayName.contains(needle, ignoreCase = true) ||
            relativePath.contains(needle, ignoreCase = true)
    }
}
