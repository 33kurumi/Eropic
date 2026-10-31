package io.github.kurumi33.eropic.core.search

import io.github.kurumi33.eropic.core.query.SearchText
import io.github.kurumi33.eropic.core.settings.SimilarityLevel

/**
 * 本地「以图搜图」的一条命中。
 *
 * [similarity] 用 0..100 的百分比而不是 0..1 的小数：UI 直接显示「92.3%」，每个调用点不用再乘一次 100，
 * 也避免 Float/Double 混用后出现 0.9230000001 这种脏数据。
 * 同时保留 [dhashDistance] / [phashDistance] 两个原始距离：用户问「为什么这张排后面」时，
 * 详情页可以直接把两个距离摆出来，不用再反推。
 *
 * v1.0 起 [similarity] 来自多特征评分（[MultiFeatureSimilarity]），不再是单一哈希公式换算，
 * 四个分项放在 [scores] 里；[scores] 为 null 表示这张图没走到精排（源图画像解码失败等），
 * [similarity] 是旧的哈希公式粗算值，UI 照常显示即可。
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
    /** 多特征评分的四个分项；null 表示这条没精排，[similarity] 是哈希粗算值。 */
    val scores: SimilarityScores? = null,
)

/**
 * 本地相似图查询参数。
 *
 * UI 只暴露「关键词 + 最多几条」即可，其余都有默认值；
 * [maxDistance] 是 dHash 距离上限，默认 16（约等于「肉眼能看出是同一张」），
 * 放宽到 24 以上会开始混入同场景不同拍的照片。
 * [minSimilarity] 是 v1.0 多特征评分的下限，见 [DEFAULT_MIN_SIMILARITY]。
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
    /**
     * 最终相似度下限（0..100）：低于它的命中会被丢弃。
     *
     * 默认 [DEFAULT_MIN_SIMILARITY] = 0，即「不裁剪」——
     * 现有 UI 还没有把设置页的 [SimilarityLevel] 传进来，默认裁剪会让用户
     * 升级后突然看不到本来有的结果。调用方想启用档位时用 [atLevel] 构造查询即可。
     */
    val minSimilarity: Double = DEFAULT_MIN_SIMILARITY,
) {
    companion object {
        const val DEFAULT_LIMIT = 200
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 1000
        const val DEFAULT_MAX_DISTANCE = 16

        /** 不裁剪：0 分也保留（哈希粗排的结果仍然按分数排序）。 */
        const val DEFAULT_MIN_SIMILARITY = 0.0

        /**
         * 档位对应的相似度下限（经验值）。
         *
         * 标定方式：同一张图（缩放/压缩后）新评分通常在 95 以上；同角色不同图 70~88；
         * 同场景非同图 55~70；毫不相关一般低于 50。所以：
         * - 严格：85，只留「几乎肯定同源」的；
         * - 标准：70，同角色不同图基本都在里面，杂图基本被挡掉；
         * - 宽松：55，宁可多给候选，由用户自己判断。
         * 这些数字与 [SimilarityLevel.dhashThreshold] 是两套口径：
         * 前者管最终多特征分，后者管候选阶段的 dHash 粗筛。
         */
        const val STRICT_MIN_SIMILARITY = 85.0
        const val STANDARD_MIN_SIMILARITY = 70.0
        const val LOOSE_MIN_SIMILARITY = 55.0

        /** 把设置页档位映射成相似度下限。 */
        fun minSimilarityFor(level: SimilarityLevel): Double = when (level) {
            SimilarityLevel.STRICT -> STRICT_MIN_SIMILARITY
            SimilarityLevel.STANDARD -> STANDARD_MIN_SIMILARITY
            SimilarityLevel.LOOSE -> LOOSE_MIN_SIMILARITY
        }

        /**
         * 按设置页档位构造查询：粗筛距离用 [SimilarityLevel.dhashThreshold]，
         * 最终下限用 [minSimilarityFor]。
         *
         * 为什么两个都取档位值：粗筛放太宽会让精排解码 200 张无关图（慢），
         * 放太窄又会漏掉「颜色像但结构差」的同角色图；档位的 dhashThreshold
         * 正是为「查重」标定的同源距离，直接借来做候选上限最省心。
         * 注意：UI 目前仍用默认 [maxDistance]（16），档位尚未接线；
         * 接线只需要把 ViewModel 里的 SimilarityQuery(...) 换成 SimilarityQuery.atLevel(level)。
         */
        fun atLevel(level: SimilarityLevel, keyword: String? = null): SimilarityQuery =
            SimilarityQuery(
                keyword = keyword,
                maxDistance = level.dhashThreshold,
                minSimilarity = minSimilarityFor(level),
            )
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
 * 保留五家（都支持「匿名 multipart 上传本地图片」这一条硬要求）；
 * v0.6 去掉了 Yandex：结果页依赖服务端 302，稳定性最差，且国内同样要代理，实际可用性不如别家。
 * - [GOOGLE_LENS]：通用识图覆盖最好；
 * - [BAIDU]：百度识图，**国内不需要代理**，是唯一能当兜底用的；
 * - [SAUCENAO]：二次元索引最全，官方 API 需 api_key（v0.6 起 key 内置在代码里，不再让用户填）；
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

    /** 百度识图：匿名上传，国内直连不需要代理；实拍/中文场景覆盖好，二次元一般。 */
    BAIDU("百度识图", "https://graph.baidu.com/"),

    /** SauceNAO：pixiv / twitter / danbooru 等索引，二次元识图最准；key 已内置。 */
    SAUCENAO("SauceNAO", "https://saucenao.com/"),

    /** ascii2d：二次元以图搜图，匿名可用。 */
    ASCII2D("ascii2d", "https://ascii2d.net/"),

    /** IQDB：二次元图库检索（2D 图），匿名可用；国内通常需要代理。 */
    IQDB("IQDB", "https://iqdb.org/"),

    /**
     * onegai.moe：二次元图库检索（FlutterPicOrigin 里列出的引擎）。
     *
     * 它**只接受公网图片 URL**（GET /search?url=...），没有匿名 multipart 上传入口，
     * 所以走「先上传到匿名图床拿 URL、再拼搜索页」的通道，见 [ImageHost]。
     */
    ONEGAI("onegai.moe", "https://onegai.moe/"),

    /**
     * Lenso.ai：AI 图像检索，对 cosplay / 角色图 / 地标都有效。
     *
     * 原本有 POST /api/upload + /api/search 的 JSON 接口，2026 年被 Cloudflare turnstile 挡掉，
     * 现在只剩 URL 入口 /en/search-by-url?url=...，同样依赖匿名图床。
     */
    LENSO("Lenso.ai", "https://lenso.ai/"),
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
    /**
     * 服务商在结果页里给出的命中条目（v0.7 起）。
     *
     * 动机：用户反馈 SauceNAO 只弹一句「找到 8 个结果」却跳不到结果页——因为它的命中经常没有
     * ext_url，原来的实现就把整次搜索判定成失败。现在把命中列表带回 App 内展示，
     * [resultUrl] 只是「顺便能打开的原站链接」，不再是成功的必要条件。
     */
    val items: List<OnlineHit> = emptyList(),
)

/**
 * 联网识图的一条命中（与 [io.github.kurumi33.eropic.core.search.OnlineParsers.ParsedHit] 字段一致）。
 *
 * 独立成一个类型而不是直接复用解析器的 ParsedHit：ParsedHit 属于「网页解析层」，
 * 以后换解析库或字段可以整体替换，而 UI 依赖的模型应保持稳定。
 */
data class OnlineHit(
    val title: String? = null,
    /** 0..100 的相似度百分比；服务商不给（ascii2d/IQDB 只有「完全一致/相似」分档）时为 null。 */
    val similarity: Double? = null,
    /** 命中的原站链接（pixiv 作品页等）；没有时为 null。 */
    val extUrl: String? = null,
    /** 结果页（如 iqdb 的 thumb 页、ascii2d 的详情页）；没有时为 null。 */
    val pageUrl: String? = null,
    /** 缩略图地址；App 不加载外链图片，仅保留给「复制链接」用。 */
    val thumbnailUrl: String? = null,
    /** SauceNAO 的角色标注（逗号分隔）；只有 SauceNAO 给，其它服务商为 null。见 [CharacterRecognizer]。 */
    val characters: String? = null,
    /** SauceNAO 的作品 / 素材名（"vocaloid" 等）；没有时为 null。 */
    val material: String? = null,
    /** SauceNAO 的画师名；没有时为 null。 */
    val creator: String? = null,
)

/**
 * 「找同一角色的本地图片」的结果（v0.7 新增）。
 *
 * 两步走：先联网识图（SauceNAO / ascii2d）拿到角色/作品提示，再在本地按视觉相似度排序，
 * 所以 [roleHint] 可能为空（离线 / 服务商没给标题），此时 [localMatches] 退化成纯视觉相似结果。
 */
data class CharacterMatchResult(
    /** 作为查询源的图片。 */
    val source: SimilarImageHit?,
    /** 本地候选（已按综合分降序）。 */
    val localMatches: List<SimilarImageHit> = emptyList(),
    /** 从联网识图拿到的角色/作品提示，例如 "hatsune miku"；拿不到为 null。 */
    val roleHint: String? = null,
    /** 给用户看的一句话说明（例如「联网识图没有给出角色名，以下按颜色与结构相似度排序」）。 */
    val message: String? = null,
    /** 是否真的联网问过；false 表示只是本地排序。 */
    val onlineUsed: Boolean = false,
    /**
     * 角色名候选（v0.8 起），按加权票数降序；可能为空（离线 / 服务商没给标注）。
     * [roleHint] 恒等于它的第一条名字，保留旧字段是为了让不关心候选的 UI 少改一处。
     */
    val candidates: List<CharacterCandidate> = emptyList(),
    /** 识别依据，一条一个字符串（"SauceNAO 命中 5 条，其中 3 条标注角色为「xxx」"），可直接展示。 */
    val basis: List<String> = emptyList(),
)

/**
 * 关键词查询（文件名 + 目录路径）的语法解析。
 *
 * 语法（v0.6 起，与图库搜索共用 [io.github.kurumi33.eropic.core.query.SearchText]）：
 * - 空格分隔多个条件 = **全部满足**（AND）：`cat 2024` 要求文件名或目录同时含 cat 与 2024；
 * - 用双引号包住 = **整段连在一起**：`"my cat"` 要求文件名/目录里出现带空格的这一整段；
 * - `-前缀` 表示**排除**：`cat -screenshot` 命中 cat 但不含 screenshot；
 * - `|` 仍表示**或**（兼容 v0.5）：`cat|kitten` 命中任意一个即可；
 * - 不区分大小写、按子串匹配（`cat` 会命中 `vacation.jpg`）；`%` 与 `_` 按普通字符处理。
 *
 * 解析本身在 [io.github.kurumi33.eropic.core.query.SearchText] 里，这里只做适配：
 * 识图页的关键词辅助与图库搜索必须完全同一套语义，否则用户会发现两处结果对不上。
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
        val parsed = SearchText.parse(raw)
        return Query(parsed.groups, parsed.excludes)
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
