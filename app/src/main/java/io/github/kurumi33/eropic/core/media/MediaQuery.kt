package io.github.kurumi33.eropic.core.media

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/** 图库与文件夹页共用的查询条件。 */
data class MediaQueryFilter(
    /** 只在文件夹页使用；为 null 表示整个图库。 */
    val folderPath: String? = null,
    /** true = 包含所有子文件夹（递归往下）；false = 只看当前文件夹直属图片。 */
    val includeSubfolders: Boolean = true,
    val favoritesOnly: Boolean = false,
)

/** 用 RawQuery 拼 SQL：排序与筛选都是运行时变量，@Query 无法覆盖。 */
object MediaQuery {

    fun paging(filter: MediaQueryFilter, sort: SortOrder): SupportSQLiteQuery =
        build(filter, sort, columns = "*")

    /** 只取 id，用于给大图查看器准备顺序（10 万个 Long 约 800KB）。 */
    fun ids(filter: MediaQueryFilter, sort: SortOrder): SupportSQLiteQuery =
        build(filter, sort, columns = "id")

    private fun build(filter: MediaQueryFilter, sort: SortOrder, columns: String): SupportSQLiteQuery {
        val args = ArrayList<Any>(4)
        val sql = StringBuilder("SELECT ")
        sql.append(columns)
        sql.append(" FROM media_item WHERE isPresent = 1")

        if (filter.favoritesOnly) {
            // 系统收藏（MediaStore IS_FAVORITE）与 App 内本地收藏都算收藏
            sql.append(" AND (isFavorite = 1 OR localFavorite = 1)")
        }
        filter.folderPath?.let { path ->
            val trimmed = path.trimEnd('/')
            // 根目录（path 为空）代表"全部照片"：不追加目录条件。
            // 之前这里会拼出 LIKE '/%'，一条也匹配不到，导致根目录详情页空着。
            if (trimmed.isNotEmpty()) {
                if (filter.includeSubfolders) {
                    sql.append(" AND relativePath LIKE ? ESCAPE '\\'")
                    args += escapeLike(trimmed) + "/%"
                } else {
                    sql.append(" AND relativePath = ?")
                    args += path
                }
            }
        }
        sql.append(" ORDER BY ").append(sort.sql).append(", id DESC")
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    private fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
