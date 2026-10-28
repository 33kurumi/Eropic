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
     * 文件名 / 目录关键词，语义与图库搜索一致，支持多关键词语法，详见 [KeywordSyntax]。
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
 * v0.5 起改成**照抄 kitUIN/PicImageSearch 的上传协议**（https://github.com/kitUIN/PicImageSearch），
 * 不再自己逆向：每个引擎的端点、表单字段名、结果页来源都在 [OnlineImageSearch] 对应函数里注明出处。
 * 采用 PicImageSearch 的理由：它是目前维护最勤的 Python 多引擎实现，字段名改动能第一时间发现，
 * 出问题时可以逐行对照，而不是靠猜。
 *
 * 保留六家（都支持「匿名 multipart 上传本地图片」这一条硬要求）：
 * - [GOOGLE_LENS]：通用识图覆盖最好；
 * - [YANDEX]：实拍与二次元都能用，服务端直接 302 到结果页；
 * - [BAIDU]：百度识图，**国内不需要代理**，是唯一能当兜底用的；
 * - [SAUCENAO]：二次元索引最全，但官方 API 必须带个人 Key（PicImageSearch 同样要 api_key）；
 * - [ASCII2D]：二次元以图搜图，匿名可用；
 * - [IQDB]：老牌二次元图库检索，匿名可用（国内通常要代理）。
 *
 * 被排除的：Bing（需要解密 imageSignature 的 XOR 两步流程，维护成本高）、TinEye（要先拿 query_hash
 * 再走会话接口）、trace.moe / AnimeTrace（返回 JSON 而不是结果页，属于「识别 API」而非「以图搜图」）、
 * whos.tv（非匿名：账号 + 积分 + Cloudflare，v0.4 已下线）。
 *
 * 枚举顺序即 UI 上按钮顺序。
 */
enum class ReverseImageProvider(val label: String, val homeUrl: String) {
    /** Google Lens：匿名上传最稳的一家，通用图片覆盖最好。 */
    GOOGLE_LENS("Google Lens", "https://lens.google.com/"),

    /** Yandex 识图：实拍与二次元都能用，匿名上传后 302 到结果页。 */
    YANDEX("Yandex", "https://yandex.com/images/"),

    /** 百度识图：匿名上传，国内直连不需要代理；实拍/中文场景覆盖好，二次元一般。 */
    BAIDU("百度识图", "https://graph.baidu.com/"),

    /** SauceNAO：pixiv / twitter / danbooru 等索引，二次元识图最准；必须先在设置里填 API Key。 */
    SAUCENAO("SauceNAO", "https://saucenao.com/"),

    /** ascii2d：二次元以图搜图，匿名可用。 */
    ASCII2D("ascii2d", "https://ascii2d.net/"),

    /** IQDB：二次元图库检索（2D 图），匿名可用；国内通常需要代理。 */
    IQDB("IQDB", "https://iqdb.org/"),
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
    /**
     * 成功时可直接用浏览器打开的结果页；失败时为 null。
     * SauceNAO 没有可复用的结果页，这里返回的是「最相似作品在原站的页面」（data.ext_urls）。
     */
    val resultUrl: String? = null,
    /** 失败原因（中文）；成功时为 null。 */
    val message: String? = null,
)

/**
 * 关键词查询（文件名 + 目录路径）的语法解析。
 *
 * 语法（v0.5 起）：
 * - 空格分隔多个条件 = **全部满足**（AND）：`cat 2024` 要求文件名或目录同时含 cat 与 2024；
 * - `|` 表示**或**（OR）：`cat|kitten` 命中任意一个即可，可与空格组合：`cat|kitten 2024`；
 * - `-前缀` 表示**排除**：`cat -screenshot` 命中 cat 但不含 screenshot；
 * - 不区分大小写、按子串匹配（`cat` 会命中 `vacation.jpg`）；`%` 与 `_` 按普通字符处理；
 * - 引号不做特殊处理（不需要精确短语，空格本身就是 AND）。
 *
 * 抽成纯函数是为了能在 JVM 单测里直接断言，也避免 UI 与仓库层各写一套。
 */
internal object KeywordSyntax {

    /** 解析结果：每组 includes 之间是 AND，组内多个词是 OR；excludes 命中任意一个即排除。 */
    data class Query(
        val includes: List<List<String>>,
        val excludes: List<String>,
    ) {
        val isEmpty: Boolean get() = includes.isEmpty() && excludes.isEmpty()
    }

    fun parse(raw: String?): Query {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Query(emptyList(), emptyList())
        val includes = ArrayList<List<String>>()
        val excludes = ArrayList<String>()
        // 用正则切空白（含全角空格），避免用户从别处粘贴来的不间断空格把词粘在一起
        for (token in text.split(WHITESPACE)) {
            if (token.isEmpty()) continue
            if (token.length > 1 && token.startsWith("-")) {
                excludes += token.substring(1).lowercase()
                continue
            }
            val alternatives = token.split('|')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
            if (alternatives.isNotEmpty()) includes += alternatives
        }
        return Query(includes, excludes)
    }

    /** 是否命中：任一 include 组整体不满足就失败；excludes 命中任意一个就失败。 */
    fun matches(displayName: String, relativePath: String, query: Query): Boolean {
        if (query.isEmpty) return true
        val haystack = (displayName + "\n" + relativePath).lowercase()
        for (group in query.includes) {
            if (group.none { haystack.contains(it) }) return false
        }
        for (excluded in query.excludes) {
            if (haystack.contains(excluded)) return false
        }
        return true
    }

    fun matches(displayName: String, relativePath: String, raw: String?): Boolean =
        matches(displayName, relativePath, parse(raw))

    private val WHITESPACE = Regex("[\\s\\u3000]+")
}

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
     * 关键词是否命中：文件名或目录满足全部条件即算命中，语法见 [KeywordSyntax]。
     *
     * 空关键词一律视为「不筛选」；`%` 与 `_` 按字面比较（与 [SimilarityQuery.keyword] 的文档一致）。
     */
    fun matchesKeyword(displayName: String, relativePath: String, keyword: String?): Boolean =
        KeywordSyntax.matches(displayName, relativePath, keyword)
}
