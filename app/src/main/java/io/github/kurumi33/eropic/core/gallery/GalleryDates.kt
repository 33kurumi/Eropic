package io.github.kurumi33.eropic.core.gallery

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 某个时间点所属的"本地日历日"（年 / 月 / 日）。
 *
 * 这是图库日期分组的中间结果：先换算成三个数字，再据此拼分组键或标题，
 * 就避免了"截字符串/比字符串"这类容易出错的写法。
 * 时间非法（<= 0）时三个字段都是 0，表示"未知日期"。
 */
data class GalleryDateKey(
    val year: Int,
    val month: Int,
    val day: Int,
)

/**
 * 图库日期分组的键与标题（v0.5，纯 Kotlin，方便单测）。
 *
 * 统一放这里的原因：分组键既被网格渲染（ui/gallery）使用，也可能被后续的
 * 文件夹聚合 / 搜索页使用；如果散在各处，很容易出现"按月"的键格式不一致
 * （"2026-10" 与 "2026 年 10 月"），导致折叠状态对不上。
 *
 * 时区一律用设备当前时区（[ZoneId.systemDefault]）：用户看到的日期就是本地日期，
 * 跨年、跨月、闰年/闰秒这些边界交给 java.time 处理，不自己算。
 */
object GalleryDates {

    /** 周一..周日，索引 = dayOfWeek.value - 1。 */
    private val WEEKDAY_LABELS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private const val UNKNOWN_GROUP_KEY = "unknown"
    private const val UNKNOWN_LABEL = "未知日期"

    /** 时间非法时返回的键，用 0 表示未知；调用方可用 [GalleryDateKey.year] 判断。 */
    private val UNKNOWN_KEY = GalleryDateKey(year = 0, month = 0, day = 0)

    /**
     * 把时间戳换算成本地日历日。
     *
     * 注意：[timeMs] <= 0（MediaStore 里没写拍摄时间的图片）返回 [UNKNOWN_KEY]，
     * 而不是 1970-01-01：1970 这个假日期会让"未知日期"的图片错误地混进 1970 年分组。
     */
    fun keyOf(timeMs: Long): GalleryDateKey {
        if (timeMs <= 0L) return UNKNOWN_KEY
        val date = localDate(timeMs)
        return GalleryDateKey(date.year, date.monthValue, date.dayOfMonth)
    }

    /** 按月的标题，例："2026年10月"；时间非法时返回"未知日期"。 */
    fun monthLabel(timeMs: Long): String {
        if (timeMs <= 0L) return UNKNOWN_LABEL
        val date = localDate(timeMs)
        return date.year.toString() + "年" + date.monthValue + "月"
    }

    /**
     * 按日的标题，例："10月28日 · 周三"。
     *
     * 每月 1 号额外带上年份与月份（"2026年10月1日 · 周三"）：按日分组时，
     * 1 号是跨月边界，只写"1日"用户看不出这是哪个月；其余日期带月份就够，
     * 不必每天标题都堆一个年份。
     *
     * 时间非法时返回"未知日期"。
     */
    fun dayLabel(timeMs: Long): String {
        if (timeMs <= 0L) return UNKNOWN_LABEL
        val date = localDate(timeMs)
        val weekday = WEEKDAY_LABELS[date.dayOfWeek.value - 1]
        if (date.dayOfMonth == 1) {
            return date.year.toString() + "年" + date.monthValue + "月" + date.dayOfMonth + "日 · " + weekday
        }
        return date.monthValue.toString() + "月" + date.dayOfMonth + "日 · " + weekday
    }

    /**
     * 分组键（同时也用作折叠状态的键）。
     *
     * - [byDay] = false：按 "2026-10"，跨年也不会撞键；
     * - [byDay] = true：按 "2026-10-28"，同一天内所有图片共用；
     * - 时间非法：统一 "unknown"，全部未知日期的图片归到同一个"未知日期"组。
     *
     * 用补零的定长格式，除了方便阅读，也能直接按字符串排序得到时间顺序。
     */
    fun groupKeyOf(timeMs: Long, byDay: Boolean): String {
        if (timeMs <= 0L) return UNKNOWN_GROUP_KEY
        val date = localDate(timeMs)
        val year = date.year.toString()
        val month = date.monthValue.toString().padStart(2, '0')
        if (!byDay) return year + "-" + month
        val day = date.dayOfMonth.toString().padStart(2, '0')
        return year + "-" + month + "-" + day
    }

    private fun localDate(timeMs: Long): LocalDate =
        Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).toLocalDate()
}
