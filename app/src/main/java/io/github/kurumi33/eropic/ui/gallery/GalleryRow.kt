package io.github.kurumi33.eropic.ui.gallery

import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import java.time.Instant
import java.time.ZoneId

/**
 * 图库网格里的一行：普通照片，或「2026 年 9 月」这样的分组标题。
 *
 * 分组标题是用 Paging 的 insertSeparators 插进去的，因此它也是这一行的合法取值，
 * 网格里按 index 取到后按类型分支渲染即可（Paging 只会把它当成"不是照片"的分隔项）。
 */
sealed interface GalleryRow {
    data class Header(val key: String, val label: String) : GalleryRow

    data class Photo(val entity: MediaEntity) : GalleryRow
}

/** 月份分组用的键与文案。用设备时区，这样跨年/跨月的边界和用户直觉一致。 */
object GalleryDates {

    fun monthKey(timestampMs: Long): String {
        if (timestampMs <= 0L) return "unknown"
        val date = Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return date.year.toString() + "-" + date.monthValue.toString().padStart(2, '0')
    }

    fun monthLabel(timestampMs: Long): String {
        if (timestampMs <= 0L) return "未知日期"
        val date = Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return date.year.toString() + " 年 " + date.monthValue + " 月"
    }
}
