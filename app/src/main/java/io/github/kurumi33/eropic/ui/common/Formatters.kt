package io.github.kurumi33.eropic.ui.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

fun formatCount(count: Long): String = String.format(Locale.US, "%,d", count)

/** 列表里显示用；日期未知/无效时给占位符，不显示 1970。 */
fun formatDate(ms: Long): String {
    if (ms <= 0L) return "时间未知"
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
}

/** 例如 "4000×3000"；宽高缺失时返回 null。 */
fun formatResolution(width: Int, height: Int): String? =
    if (width > 0 && height > 0) "${width}×${height}" else null

/** 列表副标题：大小 · 分辨率 · 日期，缺项自动省略。 */
fun formatMediaSubtitle(sizeBytes: Long, width: Int, height: Int, dateTakenMs: Long): String =
    listOfNotNull(formatBytes(sizeBytes), formatResolution(width, height), formatDate(dateTakenMs))
        .joinToString(" · ")
