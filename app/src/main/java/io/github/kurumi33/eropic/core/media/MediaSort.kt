package io.github.kurumi33.eropic.core.media

/** 图库/文件夹网格的排序方式；sql 直接拼进 ORDER BY。 */
enum class SortOrder(val label: String, val sql: String) {
    DATE_DESC("拍摄时间 · 新→旧", "dateTakenMs DESC"),
    DATE_ASC("拍摄时间 · 旧→新", "dateTakenMs ASC"),
    NAME_ASC("文件名 A→Z", "displayName COLLATE NOCASE ASC"),
    SIZE_DESC("文件大小 · 大→小", "sizeBytes DESC"),
    SIZE_ASC("文件大小 · 小→大", "sizeBytes ASC"),
    MODIFIED_DESC("最近修改", "dateModifiedMs DESC"),
    RESOLUTION_DESC("分辨率 · 高→低", "(width * height) DESC"),
    FORMAT_ASC("格式 A→Z", "mimeType COLLATE NOCASE ASC"),
}
