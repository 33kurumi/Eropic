package io.github.kurumi33.eropic.core.index

import android.provider.MediaStore

/**
 * 扫描范围（对应规划 F-03）。
 *
 * 规则放在数据源一层的 selection 里，而不是查出来之后再过滤：
 * 100k+ 图片时"先全查再丢"会把扫描时间翻倍，也容易漏掉 IS_PENDING 之类边界行。
 */
data class ScanScope(
    /** 是否包含隐藏目录（路径中以 . 开头的目录）。默认不含，避免把缓存目录里的图收进来。 */
    val includeHidden: Boolean = false,
    /** 排除目录（相对路径，例如 "Pictures/Screenshots"，结尾斜杠可有可无）；子目录一并排除。 */
    val excludedDirs: List<String> = emptyList(),
) {

    /** 拼到既有 selection 后面，返回 [selection, selectionArgs]。 */
    fun selection(base: String): Pair<String, Array<String>> {
        val clauses = ArrayList<String>(2 + excludedDirs.size)
        val args = ArrayList<String>(2 + excludedDirs.size * 2)
        if (base.isNotBlank()) clauses += base
        if (!includeHidden) {
            // 前面补 '/' 再匹配 "%/.%"，这样卷根下的 .thumbnails/ 这类顶层隐藏目录也能命中
            //（只匹配 "%/.%" 会漏掉斜杠前没有字符的情况）。
            clauses += "('/' || IFNULL(${MediaStore.MediaColumns.RELATIVE_PATH}, '')) NOT LIKE ? ESCAPE '\\'"
            args += "%/.%"
        }
        for (raw in excludedDirs) {
            val dir = normalizeDir(raw) ?: continue
            clauses += "(IFNULL(${MediaStore.MediaColumns.RELATIVE_PATH}, '') NOT LIKE ? ESCAPE '\\'" +
                " AND IFNULL(${MediaStore.MediaColumns.RELATIVE_PATH}, '') <> ?)"
            args += escapeLike(dir) + "/%"
            args += dir + "/"
        }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    /** 单张图片是否落在排除范围内；供索引协调器做二次保险（例如移动后写回的行）。 */
    fun excludes(relativePath: String?): Boolean {
        if (relativePath.isNullOrBlank()) return false
        if (!includeHidden && isHidden(relativePath)) return true
        val normalized = relativePath.trimEnd('/')
        return excludedDirs.mapNotNull { normalizeDir(it) }.any { dir ->
            normalized.equals(dir, ignoreCase = true) || normalized.startsWith("$dir/", ignoreCase = true)
        }
    }

    fun describe(): String {
        if (includeHidden && excludedDirs.isEmpty()) return "全部目录（含隐藏）"
        if (!includeHidden && excludedDirs.isEmpty()) return "默认（不含隐藏目录）"
        val hidden = if (includeHidden) "含隐藏" else "不含隐藏"
        return "$hidden · 排除 ${excludedDirs.size} 个目录"
    }

    companion object {
        val Default = ScanScope()

        /** 路径里是否存在以点开头的目录段（例如 DCIM/.thumbnails/）。 */
        fun isHidden(relativePath: String): Boolean {
            val segments = relativePath.split('/')
            return segments.any { it.length > 1 && it.startsWith(".") }
        }

        fun normalizeDir(raw: String): String? {
            val dir = raw.trim().trim('/')
            return if (dir.isEmpty()) null else dir
        }

        private fun escapeLike(raw: String): String =
            raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
