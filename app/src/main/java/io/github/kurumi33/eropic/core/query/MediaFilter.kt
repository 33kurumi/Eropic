package io.github.kurumi33.eropic.core.query

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.kurumi33.eropic.core.media.SortOrder

/**
 * 清理页 / 图库高级筛选的组合条件（对应规划 B-09）。
 *
 * 所有字段都有"不限制"的默认值：UI 每加一个条件只改一个字段，不需要额外的 builder；
 * 空 [MediaFilter]() 等价于"没有任何筛选"。时间统一用 epoch 毫秒（规范 §2），与 MediaEntity 一致。
 */
data class MediaFilter(
    val sizeMinBytes: Long? = null,
    val sizeMaxBytes: Long? = null,
    /** 只看宽/高至少多少像素的图；不区分横竖，直接比较实体里的 width/height。 */
    val widthMin: Int? = null,
    val heightMin: Int? = null,
    /**
     * 长边下限（M4 补齐 B-09 的「分辨率」档）：width >= N 或 height >= N 即命中。
     *
     * 不能直接用 widthMin 表达"长边≥1080"：竖图的宽可能只有 1080 而高是 1920，
     * 用 widthMin 会误杀；单独的宽高下限条件无法正确表达"较长的那一边"。
     */
    val minLongEdge: Int? = null,
    /** 精确匹配 MIME（如 image/png），大小写按 MediaStore 的约定保持小写。 */
    val mimeTypes: Set<String> = emptySet(),
    /**
     * 拍摄时间区间（闭区间），筛选列 dateTakenMs。
     *
     * 不用 dateAddedSecs：它在重装、换机、恢复备份后会被重置成"导入时间"，
     * 用它筛"老照片"会把十年的照片都算成新图。dateTakenMs 缺失时回退到修改时间由扫描层处理。
     */
    val dateFromMs: Long? = null,
    val dateToMs: Long? = null,
    /** 文件修改时间区间（闭区间），筛选列 dateModifiedMs；与拍摄时间是两个独立维度。 */
    val modifiedFromMs: Long? = null,
    val modifiedToMs: Long? = null,
    /** 系统收藏（MediaStore IS_FAVORITE）与 App 内收藏任一为真即算收藏。 */
    val favoritesOnly: Boolean = false,
    /** 目录路径（以 / 分隔，结尾可带可不带 /）；多个目录之间是"或"关系，与其他条件是"与"关系。 */
    val folders: List<String> = emptyList(),
    val includeSubfolders: Boolean = true,
    /** 清晰度区间，列 blurScore（拉普拉斯方差，越大越清晰）；null 表示未计算，不会被区间命中。 */
    val minBlurScore: Float? = null,
    val maxBlurScore: Float? = null,
    /** 文件名 / 目录模糊搜索（对应规划 A-11），不区分大小写，% 与 _ 会转义。 */
    val nameLike: String? = null,
) {

    /** 没有任何生效条件；UI 用它决定是否显示"已筛选"角标、是否显示"清空筛选"。 */
    val isEmpty: Boolean
        get() = sizeMinBytes == null &&
            sizeMaxBytes == null &&
            widthMin == null &&
            heightMin == null &&
            minLongEdge == null &&
            mimeTypes.isEmpty() &&
            dateFromMs == null &&
            dateToMs == null &&
            modifiedFromMs == null &&
            modifiedToMs == null &&
            !favoritesOnly &&
            folders.isEmpty() &&
            minBlurScore == null &&
            maxBlurScore == null &&
            nameLike.isNullOrBlank()
}

/**
 * 把 [MediaFilter] 翻译成 SQL 片段。
 *
 * 拆成"纯函数 where() + 薄封装 toQuery()"的原因：
 * - where() 不碰 Android 类，可以在纯 JVM 单测里断言条件顺序、参数个数与 LIKE 转义；
 * - 拼 SQL 的活只有这一处，Repository / ViewModel / 单测共用同一份语义。
 */
object MediaFilterSql {

    /** LIKE 转义字符统一用反斜杠，与 MediaQuery 保持一致（SQLite 默认没有转义字符，必须显式声明）。 */
    private const val LIKE_ESCAPE = " ESCAPE '\\'"

    /**
     * 返回 [条件片段, 绑定参数]：片段之间用 AND 连接、不含 WHERE 关键字，空条件返回 ""。
     *
     * 调用方负责带上 `isPresent = 1`（见 [toQuery]）；条件顺序固定为
     * 大小 → 分辨率 → 长边 → 格式 → 拍摄时间 → 修改时间 → 收藏 → 目录 → 清晰度 → 文件名，
     * 参数顺序与片段顺序一一对应，改顺序时务必同步单测。
     */
    fun where(filter: MediaFilter): Pair<String, List<Any>> {
        val clauses = ArrayList<String>(8)
        val args = ArrayList<Any>(8)

        filter.sizeMinBytes?.let {
            clauses += "sizeBytes >= ?"
            args += it
        }
        filter.sizeMaxBytes?.let {
            clauses += "sizeBytes <= ?"
            args += it
        }
        filter.widthMin?.let {
            clauses += "width >= ?"
            args += it
        }
        filter.heightMin?.let {
            clauses += "height >= ?"
            args += it
        }
        filter.minLongEdge?.let {
            // 长边：宽或高任一不小于阈值即算达到该分辨率
            clauses += "(width >= ? OR height >= ?)"
            args += it
            args += it
        }
        if (filter.mimeTypes.isNotEmpty()) {
            // 排序保证绑定参数顺序稳定：Set 的迭代顺序不保证，单测与日志都需要可复现
            val types = filter.mimeTypes.sorted()
            clauses += "mimeType IN (" + types.joinToString(", ") { "?" } + ")"
            args.addAll(types)
        }
        filter.dateFromMs?.let {
            clauses += "dateTakenMs >= ?"
            args += it
        }
        filter.dateToMs?.let {
            clauses += "dateTakenMs <= ?"
            args += it
        }
        filter.modifiedFromMs?.let {
            clauses += "dateModifiedMs >= ?"
            args += it
        }
        filter.modifiedToMs?.let {
            clauses += "dateModifiedMs <= ?"
            args += it
        }
        if (filter.favoritesOnly) {
            // 两个收藏来源都算，和 MediaQuery 的语义保持一致，避免同一张图在两个页面表现不同
            clauses += "(isFavorite = 1 OR localFavorite = 1)"
        }
        if (filter.folders.isNotEmpty()) {
            val folderClauses = ArrayList<String>(filter.folders.size)
            for (raw in filter.folders) {
                // 实体里的 relativePath 一定以 / 结尾；这里接受调用方带不带结尾斜杠
                val normalized = raw.trimEnd('/')
                if (normalized.isEmpty()) continue
                if (filter.includeSubfolders) {
                    folderClauses += "relativePath LIKE ?" + LIKE_ESCAPE
                    args += escapeLike(normalized) + "/%"
                } else {
                    folderClauses += "relativePath = ?"
                    args += normalized + "/"
                }
            }
            if (folderClauses.isNotEmpty()) {
                clauses += "(" + folderClauses.joinToString(" OR ") + ")"
            }
        }
        filter.minBlurScore?.let {
            // blurScore 为 NULL 的行比较结果为 NULL（相当于 false），未算清晰度的图不会误进"清晰"筛选
            clauses += "blurScore >= ?"
            args += it
        }
        filter.maxBlurScore?.let {
            clauses += "blurScore <= ?"
            args += it
        }
        filter.nameLike?.takeIf { it.isNotBlank() }?.let { raw ->
            // 文件名与目录都参与匹配：用户往往记得"在哪个文件夹"而不记得文件名本身
            val pattern = "%" + escapeLike(raw.trim()) + "%"
            clauses += "(displayName LIKE ?" + LIKE_ESCAPE + " OR relativePath LIKE ?" + LIKE_ESCAPE + ")"
            args += pattern
            args += pattern
        }
        return clauses.joinToString(" AND ") to args
    }

    /**
     * 用 [SimpleSQLiteQuery] 包成可直接喂给 @RawQuery 的查询。
     *
     * [columns] 与 [sortSql] 只接受代码内常量（"*"、"id"、[SortOrder.sql]），
     * 不要拼接用户输入——参数化只覆盖筛选值，列名与排序无法绑定参数。
     * 需要稳定分页时请自行在 sortSql 末尾追加 `, id DESC` 兜底，避免同值行跨页重复。
     */
    fun toQuery(columns: String, filter: MediaFilter, sortSql: String): SupportSQLiteQuery {
        val (whereSql, args) = where(filter)
        val sql = StringBuilder("SELECT ")
            .append(columns)
            .append(" FROM media_item WHERE isPresent = 1")
        if (whereSql.isNotEmpty()) {
            sql.append(" AND ").append(whereSql)
        }
        sql.append(" ORDER BY ").append(sortSql.ifBlank { SortOrder.DATE_DESC.sql })
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    private fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}