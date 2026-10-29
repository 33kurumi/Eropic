package io.github.kurumi33.eropic.core.query

/**
 * 关键词语法解析（v0.6）。
 *
 * 图库搜索、文件夹搜索、识图页的关键词辅助全部走这一份解析，避免三处各写一套导致行为不一致。
 *
 * 语法：
 * - 空格分开 = **同时包含**（AND）：`猫 2024` = 文件名或目录里同时有「猫」和「2024」；
 * - 用双引号包住 = **整段连在一起**（短语）：`"我的 照片"` = 中间有空格的那一整段；
 * - 词前加 `-` = **排除**：`猫 -截图` = 含「猫」但不含「截图」；
 * - `|` 仍然表示或（兼容 v0.5 老用户习惯）：`猫|猫猫` 任意一个即可；
 * - 不区分大小写、按子串匹配；`%` 与 `_` 按普通字符处理（SQL 里由 [MediaFilterSql] 转义）。
 *
 * 单个字符的解析是纯函数、不碰 Android，所以能直接在 JVM 单测里断言。
 */
object SearchText {

    /**
     * 解析结果。
     *
     * [groups] 之间是 AND，组内多个词是 OR；[excludes] 命中任意一个即排除。
     */
    data class Parsed(
        val groups: List<List<String>>,
        val excludes: List<String>,
    ) {
        val isEmpty: Boolean get() = groups.isEmpty() && excludes.isEmpty()
    }

    fun parse(raw: String?): Parsed {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Parsed(emptyList(), emptyList())
        val groups = ArrayList<List<String>>()
        val excludes = ArrayList<String>()
        for (token in tokenize(text)) {
            // 兼容 v0.5 的竖线语法：一个 token 内部仍可用 | 表示或
            val alternatives = token.text.split('|')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
            if (alternatives.isEmpty()) continue
            if (token.negated) {
                // -a|b 视为「排除 a 或 b」：excludes 命中任意一个就失败
                excludes += alternatives
            } else {
                groups += alternatives
            }
        }
        return Parsed(groups, excludes)
    }

    private data class Token(val text: String, val negated: Boolean)

    /**
     * 按「双引号内不切分」的规则切成词。
     *
     * 手写扫描而不是正则：引号需要跨空格配对，用正则要先做平衡匹配，反而更绕。
     * 引号本身不进入词内容（用户看到的是"我的 照片"，搜的是「我的 照片」）。
     */
    private fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        val current = StringBuilder()
        var inQuote = false
        var negated = false
        var started = false

        fun flush() {
            if (!started) return
            val value = current.toString().trim()
            if (value.isNotEmpty()) tokens += Token(value, negated)
            current.setLength(0)
            negated = false
            started = false
        }

        for (ch in text) {
            if (ch == '"') {
                // 引号只切换状态，不进入词内容；单独一个 "" 会被 flush 里的空串判断丢掉
                inQuote = !inQuote
                started = true
                continue
            }
            if (!inQuote && (ch.isWhitespace() || ch == '\u3000')) {
                flush()
                continue
            }
            if (!inQuote && !started && ch == '-') {
                negated = true
                started = true
                continue
            }
            started = true
            current.append(ch)
        }
        flush()
        return tokens
    }
}
