package io.github.kurumi33.eropic.ui.i18n

/**
 * 中文 → 英文的运行时翻译器。
 *
 * 为什么不用 Android 资源文件（values-en/strings.xml）？
 * 这个项目从第一版起就是「中文硬编码在 Composable 里」，有 1500+ 条字面量散落在 60 多个文件里，
 * 一次性抽成资源是大手术、且会打断所有正在开发的功能。所以这里退一步做「渲染期翻译」：
 * Compose 的 Text 在画之前把中文串交给 [tr]，命中词表就换成英文。
 * 好处是切换语言立即生效、不用重启 Activity；代价是英文版是译文而不是原生英文文案，
 * 以及少量拼接句可能读起来生硬（后续会逐步把重要界面换成资源文件）。
 *
 * 匹配策略（依次）：
 * 1. 整句精确命中（[EN_STRINGS_CORE] 优先，再查两个批次词表）；
 * 2. 长句里做「片段替换」：把词表里长度 >= 2 的中文片段换成英文（数字插值句就是靠这个）。
 *    单字片段（如「张」「项」）只在整句命中时使用，避免把用户文件名里的字也换掉。
 * 3. 收尾清理多余空格与标点。
 */
object Translator {

    /** 词表合并顺序决定优先级：核心词 > 批次 1（高频）> 批次 2。 */
    private val exact: Map<String, String> by lazy {
        LinkedHashMap<String, String>(2048).apply {
            putAll(EN_STRINGS_CORE)
            putAll(EN_STRINGS_PART_1)
            putAll(EN_STRINGS_PART_2)
        }
    }

    /** 用于长句片段替换的条目：只要长度 >= 2 的中文片段。 */
    private val fragments: List<Pair<String, String>> by lazy {
        exact.entries.asSequence()
            .filter { it.key.length >= 2 && it.key != it.value && it.key.any(::isChinese) }
            .sortedByDescending { it.key.length }
            .map { it.key to it.value }
            .toList()
    }

    /** 结果缓存：同一句话只翻一次（列表滚动时会有大量重复调用）。 */
    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 2048
    }

    private fun isChinese(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF

    private fun hasChinese(text: String): Boolean {
        for (ch in text) if (isChinese(ch)) return true
        return false
    }

    /** 翻译一个界面字符串；非英文模式或没有中文时原样返回（零成本）。 */
    fun translate(raw: String): String {
        if (!LangState.isEnglish) return raw
        if (raw.isEmpty() || !hasChinese(raw)) return raw
        cache[raw]?.let { return it }
        val result = translateUncached(raw)
        if (cache.size >= 2048) cache.clear()
        cache[raw] = result
        return result
    }

    private fun translateUncached(raw: String): String {
        exact[raw]?.let { return it }
        var text = raw
        for ((zh, en) in fragments) {
            if (text.contains(zh)) text = text.replace(zh, en)
        }
        return tidy(text, raw)
    }

    /**
     * 收尾清理：片段替换后可能出现「 3  items」「 ，」这类痕迹。
     * 只在确实改动过（含英文）时才清理，避免破坏用户原始文件名里的空格。
     */
    private fun tidy(translated: String, original: String): String {
        if (translated == original) return original
        var text = translated
        text = text.replace(Regex("""[ \t]{2,}"""), " ")
        text = text.replace(Regex("""\s+([,.;:!?)])(?!\d)"""), "$1")
        text = text.replace(Regex("""\(\s+"""), "(")
        text = text.replace(" ,", ", ").replace(" .", ". ")
        return text.trim()
    }
}
