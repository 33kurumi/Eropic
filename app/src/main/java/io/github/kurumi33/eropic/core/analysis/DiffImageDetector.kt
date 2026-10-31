package io.github.kurumi33.eropic.core.analysis

/**
 * 差分图判定的输入行：只需要「同一张图的不同差分」这三列。
 *
 * 用 MediaEntity 直接判也可以，但投影越小越省内存，而且纯 Kotlin 数据结构能在 JVM 单测里直接造。
 */
data class DiffEntry(
    val id: Long,
    val displayName: String,
    val sizeBytes: Long,
)

/**
 * 差分图检测：文件名主体相同、只差末尾 p0/p1 这类序号后缀的图，视为同一张图的「差分」。
 *
 * 为什么单独成组而不当普通重复：差分图（差分 = 同一构图的改色/改表情版本）**通常每张都想留**，
 * 用户真正想处理的是「完全重复」和「几乎一样」；把它们混进相似组会让用户误删想要的版本。
 * 因此这类图单独落一组 KIND_DIFF，UI 上只提示「有 N 组差分图」，默认不勾选删除。
 *
 * 判定纯靠文件名（不看像素）：像素上一次差分可能只差 1% 的色相，dHash/pHash 根本分不开；
 * 而画师/工具导出差分时的命名规律非常固定，比像素启发式可靠得多。
 *
 * 纯 Kotlin、零 Android 依赖，可直接单测。
 */
object DiffImageDetector {

    /**
     * 末尾 p + 1~3 位数字：p0 / p1 / p12 / P3，前面允许用 _ - 空格分隔。
     *
     * 用懒惰的 (.*?) 而不是贪婪匹配，避免 "IMG_p0_p1" 这种名字被整段当成 base；
     * 1~3 位数字的上限是为了避免把 "photo_p2024"（年份）误判成差分。
     */
    private val P_SUFFIX = Regex("^(.*?)[ _\\-]*[pP](\\d{1,3})$")

    /** 中文写法：末尾「差分1 / _差分2 / 差分 3」。 */
    private val DIFF_SUFFIX = Regex("^(.*?)[ _\\-]*差分[ _\\-]*(\\d{1,3})$")

    /** base 至少两个字：否则 "p0/p1" 这种纯序号文件名会把无关图片凑成一组。 */
    private const val MIN_BASE_CHARS = 2

    private val SUFFIXES = listOf(P_SUFFIX, DIFF_SUFFIX)

    /**
     * 计算差分分组的键；不是差分图时返回 null。
     *
     * 键 = base 小写 + "|" + 扩展名小写：扩展名不同（同一张图 p0.jpg 与 p1.png）不算一组，
     * 因为那通常意味着处理流程不同、体积差很多，混在一起会误导「可释放空间」。
     * 大小写不敏感是为了兼容 IMG_P0 与 img_p1 混用的情况。
     */
    fun baseKeyOf(displayName: String): String? {
        val name = displayName.trim()
        if (name.isEmpty()) return null
        val dot = name.lastIndexOf('.')
        // dot == 0 是 ".nomedia" 这类隐藏文件，没有扩展名可言，按整个名字处理
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot + 1).lowercase() else ""
        val base = baseOf(stem) ?: return null
        return base.lowercase() + "|" + extension
    }

    /**
     * 把一组条目按差分键分组，**只返回成员数 >= 2 的组**。
     *
     * 组内按 displayName 升序、组间按 baseKey 升序：输出稳定，单测可以直接断言整体结构，
     * 也方便 UI 每次刷新时顺序不变（避免列表跳动）。
     */
    fun collect(entries: List<DiffEntry>): List<List<DiffEntry>> {
        if (entries.size < 2) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<DiffEntry>>()
        for (entry in entries) {
            val key = baseKeyOf(entry.displayName) ?: continue
            grouped.getOrPut(key) { ArrayList(2) }.add(entry)
        }
        return grouped
            .filterValues { it.size >= 2 }
            .toSortedMap()
            .values
            .map { members -> members.sortedBy { it.displayName } }
    }

    /** 从一个 stem 里剥出 base；不匹配任何差分写法、或 base 太短时返回 null。 */
    private fun baseOf(stem: String): String? {
        for (regex in SUFFIXES) {
            val match = regex.matchEntire(stem) ?: continue
            val base = match.groupValues[1].trim().trimEnd('_', '-', ' ')
            // 命中写法但 base 太短，说明它其实是 "p0" 这种纯序号名，直接判否
            return base.takeIf { it.length >= MIN_BASE_CHARS }
        }
        return null
    }
}
