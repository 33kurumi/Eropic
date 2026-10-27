package io.github.kurumi33.eropic.core.model

import androidx.room.ColumnInfo

/**
 * 存储仪表盘 / 清理统计里的一个聚合行（按格式、年份、目录分组）。
 *
 * Kotlin 字段叫 key，但 SQL 别名必须叫 groupKey：KEY 是 SQLite 关键字，
 * Room 的 SQL 解析器遇到 `AS key` 会直接报 "mismatched input 'key' expecting {IDENTIFIER, STRING_LITERAL}"。
 */
data class StatRow(
    /** 分组键：MIME、年份字符串或相对路径。 */
    @ColumnInfo(name = "groupKey") val key: String,
    val itemCount: Long,
    val totalBytes: Long,
)
