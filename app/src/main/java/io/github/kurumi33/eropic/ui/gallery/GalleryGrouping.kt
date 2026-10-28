package io.github.kurumi33.eropic.ui.gallery

import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.gallery.GalleryDates as CoreGalleryDates
import io.github.kurumi33.eropic.core.settings.DateGroupMode

/**
 * 图库网格里实际渲染的一行：日期分组标题，或一张照片。
 *
 * 与 [GalleryRow] 的区别：这是"渲染用"的行，折叠的分组只剩标题行，
 * 照片序号（[GalleryDisplayRow.Photo.pagingIndex]）仍然指向分页里对应的位置，
 * 网格渲染时可以拿它去访问 [androidx.paging.compose.LazyPagingItems]，让分页继续加载。
 */
sealed interface GalleryDisplayRow {

    data class Header(
        /** 折叠状态用的键；按时间排序时就是日期键，其它排序下会带上"第几段"后缀保证唯一。 */
        val key: String,
        val label: String,
        /** 当前已加载的、属于这一组的照片张数（分页还没取到的部分不计入）。 */
        val count: Int,
        val collapsed: Boolean,
    ) : GalleryDisplayRow

    data class Photo(
        val entity: MediaEntity,
        /** 这张照片在分页数据里的下标，用于访问 Paging 触发后续加载。 */
        val pagingIndex: Int,
    ) : GalleryDisplayRow
}

/**
 * 日期分组的键与标题；纯函数，方便单测。
 *
 * v0.5：分组规则统一放到 core/gallery/GalleryDates.kt（那边有独立单测），
 * 这里只保留网格用得到的入口。两处各写一份很容易漂移——比如"按月"的键
 * 一边是 "2026-10"、另一边是 "2026 年 10 月"，同一批折叠状态就对不上了。
 */
object GalleryGroupDates {

    /** 折叠/展开用的分组键：不分组返回空串，未知时间返回 "unknown"。 */
    fun key(timestampMs: Long, mode: DateGroupMode): String = when (mode) {
        DateGroupMode.NONE -> ""
        DateGroupMode.MONTH -> monthKey(timestampMs)
        DateGroupMode.DAY -> dayKey(timestampMs)
    }

    /** 分组标题文案。 */
    fun label(timestampMs: Long, mode: DateGroupMode): String = when (mode) {
        DateGroupMode.NONE -> ""
        DateGroupMode.MONTH -> monthLabel(timestampMs)
        DateGroupMode.DAY -> dayLabel(timestampMs)
    }

    fun monthKey(timestampMs: Long): String = CoreGalleryDates.groupKeyOf(timestampMs, byDay = false)

    fun monthLabel(timestampMs: Long): String = CoreGalleryDates.monthLabel(timestampMs)

    fun dayKey(timestampMs: Long): String = CoreGalleryDates.groupKeyOf(timestampMs, byDay = true)

    fun dayLabel(timestampMs: Long): String = CoreGalleryDates.dayLabel(timestampMs)
}

/**
 * 把当前已加载的照片展开成网格行。
 *
 * - [DateGroupMode.NONE] 时不做分组，原样返回照片行。
 * - 同一分组的照片必须连续才会合并成一个标题：按拍摄时间排序时天然连续；
 *   其它排序（文件名/大小等）下同一月份会反复出现，这里按"每一段"各生成一个标题，
 *   并用"日期键#段号"做唯一键，避免 LazyGrid 的重复 key 崩溃。
 * - [collapsedKeys] 里的分组只输出标题行，照片全部跳过（分页仍然靠尾部哨兵继续加载）。
 * - 标题上的张数只统计已加载的照片：Paging 是懒加载的，没取到的行无从得知；
 *   用户往下滚、分页补齐后会自然变大。
 */
fun buildGalleryDisplayRows(
    photos: List<MediaEntity>,
    mode: DateGroupMode,
    dateSorted: Boolean,
    collapsedKeys: Set<String>,
): List<GalleryDisplayRow> {
    if (photos.isEmpty()) return emptyList()
    if (mode == DateGroupMode.NONE) {
        return photos.mapIndexed { index, entity -> GalleryDisplayRow.Photo(entity, index) }
    }

    val rows = ArrayList<GalleryDisplayRow>(photos.size + 16)
    var start = 0
    var runOrdinal = 0
    while (start < photos.size) {
        val groupKey = GalleryGroupDates.key(photos[start].dateTakenMs, mode)
        var end = start + 1
        while (end < photos.size && GalleryGroupDates.key(photos[end].dateTakenMs, mode) == groupKey) {
            end++
        }
        val rowKey = if (dateSorted) groupKey else groupKey + "#" + runOrdinal
        runOrdinal++
        val collapsed = rowKey in collapsedKeys
        rows.add(
            GalleryDisplayRow.Header(
                key = rowKey,
                label = GalleryGroupDates.label(photos[start].dateTakenMs, mode),
                count = end - start,
                collapsed = collapsed,
            ),
        )
        if (!collapsed) {
            for (index in start until end) {
                rows.add(GalleryDisplayRow.Photo(photos[index], index))
            }
        }
        start = end
    }
    return rows
}
