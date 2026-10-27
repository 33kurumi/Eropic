package io.github.kurumi33.eropic.core.media

/**
 * 图库/文件夹网格的排序方式；sql 直接拼进 ORDER BY。
 *
 * M4 补齐 A-06：名称 / 拍摄时间 / 修改时间 / 大小 / 分辨率 / 格式 / 路径 七类均支持升降序。
 * 枚举名会存进 DataStore（settings 默认排序），改名会导致旧偏好回落默认值。
 */
enum class SortOrder(val label: String, val sql: String) {
    DATE_DESC("拍摄时间 · 新→旧", "dateTakenMs DESC"),
    DATE_ASC("拍摄时间 · 旧→新", "dateTakenMs ASC"),
    NAME_ASC("文件名 A→Z", "displayName COLLATE NOCASE ASC"),
    NAME_DESC("文件名 Z→A", "displayName COLLATE NOCASE DESC"),
    SIZE_DESC("文件大小 · 大→小", "sizeBytes DESC"),
    SIZE_ASC("文件大小 · 小→大", "sizeBytes ASC"),
    MODIFIED_DESC("修改时间 · 新→旧", "dateModifiedMs DESC"),
    MODIFIED_ASC("修改时间 · 旧→新", "dateModifiedMs ASC"),
    RESOLUTION_DESC("分辨率 · 高→低", "(width * height) DESC"),
    RESOLUTION_ASC("分辨率 · 低→高", "(width * height) ASC"),
    FORMAT_ASC("格式 A→Z", "mimeType COLLATE NOCASE ASC, displayName COLLATE NOCASE ASC"),
    FORMAT_DESC("格式 Z→A", "mimeType COLLATE NOCASE DESC, displayName COLLATE NOCASE ASC"),
    PATH_ASC("所在目录 A→Z", "relativePath COLLATE NOCASE ASC, displayName COLLATE NOCASE ASC"),
    PATH_DESC("所在目录 Z→A", "relativePath COLLATE NOCASE DESC, displayName COLLATE NOCASE ASC"),
}
