package io.github.kurumi33.eropic.core.search

/**
 * 「找同角色」的一个角色名候选。
 *
 * [score] 是 **0..1 的相对分**，不是「角色识别置信度」：
 * 它只由联网命中的相似度加权累加后除以最高分得到，用途是排序与在 UI 上显示相对强弱。
 * SauceNAO 给的 similarity 是「这张图像不像某个已索引的作品」，和「是不是这个角色」不是一回事，
 * 所以这里刻意不叫 confidence，也不能据此自动替用户做删除/收藏决定。
 *
 * [source] 是这条候选的名字来源（"SauceNAO" / "ascii2d" / "标题"），用来在详情里解释「凭什么这么说」。
 */
data class CharacterCandidate(
    val name: String,
    val score: Double,
    val source: String,
)

/**
 * 角色名候选聚合器（纯函数，零 Android 依赖，可在纯 JVM 单测里直接跑）。
 *
 * 为什么需要它（v0.8，用户反馈「找同角色功能好像不太行」）：
 * v0.7 只把联网命中的**第一条非空标题**当角色名，而 SauceNAO 的 data.title 常常是作品名、
 * 画师自拟的句子甚至来源站名，于是 UI 经常显示一个跟角色无关的字符串。
 * 实际上 SauceNAO 的 data.characters 就是人工标注的角色 tag，data.material 是作品名，
 * 把它们拆开、按命中相似度加权投票，比「取第一条标题」稳得多。
 *
 * 权重设计（见 [fieldsOf]）：
 * - characters（角色标注）权重最高：唯一直接回答「图里有谁」的字段；
 * - material 次之：同人图的作品名经常就是角色名/系列名（"vocaloid"、"touhou"）；
 * - title 更低：多数是作品标题；
 * - creator 最低：画师名几乎从不是角色名，只在其它字段全缺时兜底。
 * 单条命中内同名只计一次（取权重最高的字段），避免同一个词同时出现在 characters 与 title 时被加倍。
 */
object CharacterRecognizer {

    /** 默认最多返回几条候选：UI 上一行放三四个，留 8 条足够，再多会把用户注意力打散。 */
    const val DEFAULT_MAX = 8

    /** 无论调用方传多大，内部最多算这么多：防止异常输入把内存撑爆。 */
    private const val MAX_CANDIDATES = 32

    /** 角色名超过这个长度基本是整句描述/作品简介，不是名字。 */
    private const val MAX_NAME_CHARS = 40

    /** 服务商没给相似度时的兜底权重（0.5）：既不捧到最高，也不至于完全被忽略。 */
    private const val FALLBACK_SIMILARITY = 50.0

    private const val CHARACTER_WEIGHT = 1.0
    private const val MATERIAL_WEIGHT = 0.7
    private const val TITLE_WEIGHT = 0.4
    private const val CREATOR_WEIGHT = 0.3

    /** SauceNAO 的 characters/material/title/creator 都是逗号分隔；中文站点常见中文逗号与顿号。 */
    private val SEPARATORS = charArrayOf(',', '，', '、', ';', '；', '\n')

    /**
     * 明显不是角色名的词（大小写不敏感）。
     *
     * 只放「几乎必然不是名字」的通用词：宁可漏过滤，也不要把 "Miku"、"Rin" 这种短名字误杀——
     * 角色名在英文里就是普通单词，过度过滤会让功能更差。
     */
    private val NOISE_WORDS: Set<String> = setOf(
        "original",
        "original character",
        "comic",
        "anime",
        "illustration",
        "sample",
        "spoiler",
        "sketch",
        "fanart",
        "fan art",
        "artwork",
        "unfinished",
        "wip",
        "unknown",
        "other",
        "manga",
        "game",
        "series",
        "oc",
    )

    /**
     * 把一批联网命中聚合成角色名候选，按加权票数降序返回，最多 [max] 条。
     *
     * 输入为空、全部命中都没有可用字段、或 [max] <= 0 时返回空列表（调用方据此降级为「只按本地相似度排序」）。
     */
    fun candidates(hits: List<ParsedHit>, max: Int = DEFAULT_MAX): List<CharacterCandidate> {
        if (hits.isEmpty() || max <= 0) return emptyList()
        val limit = max.coerceAtMost(MAX_CANDIDATES)
        val votes = LinkedHashMap<String, Vote>()
        for (hit in hits) {
            val hitWeight = weightOf(hit)
            if (hitWeight <= 0.0) continue
            // 同一条命中内同名只算一次：取先出现的（权重最高的）字段
            val seenInHit = HashSet<String>()
            for (field in fieldsOf(hit)) {
                for (name in splitNames(field.raw)) {
                    val key = keyOf(name)
                    if (!seenInHit.add(key)) continue
                    val vote = votes.getOrPut(key) { Vote(name = name, source = field.source, priority = field.weight) }
                    vote.score += hitWeight * field.weight
                    // 来源标注跟「最有说服力的那个字段」走：同一名字先由标题出现、后又被 characters 标注时，
                    // 应显示 "SauceNAO" 而不是 "标题"（UI 用它解释依据的可信度）
                    if (field.weight > vote.priority) {
                        vote.name = name
                        vote.source = field.source
                        vote.priority = field.weight
                    }
                }
            }
        }
        if (votes.isEmpty()) return emptyList()

        // 归一化：除以最高票，让第一名恒为 1.0。绝对票数取决于「这次联网命中几条」，
        // 直接展示没有可比性；相对分对排序与「谁更可信」已经够了。
        val best = votes.values.maxOf { it.score }
        if (best <= 0.0) return emptyList()
        return votes.values
            .map { CharacterCandidate(name = it.name, score = it.score / best, source = it.source) }
            .sortedWith(compareByDescending<CharacterCandidate> { it.score }.thenBy { it.name })
            .take(limit)
    }

    /**
     * 把一个逗号分隔的字段拆成角色名列表（去空白、去噪声、去重）。
     *
     * 公开是为了让调用方（Repository 生成「识别依据」、单测）复用同一套拆分与过滤规则，
     * 避免两处对「什么算一个名字」理解不一致。
     */
    fun splitNames(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val names = ArrayList<String>(4)
        for (token in text.split(*SEPARATORS)) {
            // 只剥掉装饰性引号/括号；不剥圆括号——"初音ミク(hatsune miku)" 这种写法剥一半会变成残句
            val name = token.trim().trim('"', '\'', '「', '」', '『', '』').trim()
            if (!isUsableName(name)) continue
            if (!seen.add(keyOf(name))) continue
            names.add(name)
        }
        return names
    }

    /**
     * 出现次数最多的作品名（material 字段），用于「作品线索：vocaloid」。
     *
     * 讨论票数而不看相似度：作品名只是给用户的旁证，按出现频次更稳；
     * 没有 material 时返回 null（ascii2d / IQDB 没有这个字段），调用方据此不生成这条依据。
     */
    fun topMaterial(hits: List<ParsedHit>): String? {
        if (hits.isEmpty()) return null
        val counts = LinkedHashMap<String, Int>()
        val display = HashMap<String, String>()
        for (hit in hits) {
            for (name in splitNames(hit.material)) {
                val key = keyOf(name)
                display.putIfAbsent(key, name)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        val bestKey = counts.entries.maxByOrNull { it.value }?.key ?: return null
        return display[bestKey]
    }

    /** 一条命中的基础权重：服务商给的相似度百分比换算成 0..1；缺省时用 [FALLBACK_SIMILARITY]。 */
    private fun weightOf(hit: ParsedHit): Double =
        (hit.similarity ?: FALLBACK_SIMILARITY).coerceIn(0.0, 100.0) / 100.0

    /** 一条命中里参与投票的字段，按权重从高到低排列。 */
    private fun fieldsOf(hit: ParsedHit): List<Field> = listOf(
        Field(raw = hit.characters, weight = CHARACTER_WEIGHT, source = characterSource(hit)),
        Field(raw = hit.material, weight = MATERIAL_WEIGHT, source = TITLE_SOURCE),
        Field(raw = hit.title, weight = TITLE_WEIGHT, source = titleSource(hit)),
        Field(raw = hit.creator, weight = CREATOR_WEIGHT, source = TITLE_SOURCE),
    )

    /** characters 字段来自服务商的人工标注，来源直接写服务商名；不知道服务商时按 SauceNAO 处理。 */
    private fun characterSource(hit: ParsedHit): String = hit.source?.trim()?.takeIf { it.isNotEmpty() } ?: SAUCE_NAO

    /** 标题类字段：ascii2d 的标题单独标成 "ascii2d"，方便用户区分「标注」与「标题里猜的」。 */
    private fun titleSource(hit: ParsedHit): String {
        val source = hit.source?.trim().orEmpty()
        return if (source.isNotEmpty() && source != SAUCE_NAO) source else TITLE_SOURCE
    }

    private fun isUsableName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME_CHARS) return false
        // 纯数字 / 纯符号（"2024"、"---"）不是名字；至少要有一个字母或数字
        if (name.none { it.isLetterOrDigit() }) return false
        if (name.all { it.isDigit() }) return false
        // URL / 域名当不了角色名
        if (name.contains("://") || name.startsWith("www.", ignoreCase = true)) return false
        return keyOf(name) !in NOISE_WORDS
    }

    /** 归一化键：小写、下划线当空格、压缩连续空白——让 "Hatsune_Miku" 与 "hatsune miku" 合并。 */
    private fun keyOf(name: String): String =
        name.lowercase().replace('_', ' ').split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    private data class Field(val raw: String?, val weight: Double, val source: String)

    private class Vote(var name: String, var source: String, var priority: Double) {
        var score: Double = 0.0
    }

    private const val SAUCE_NAO = "SauceNAO"
    private const val TITLE_SOURCE = "标题"
}
