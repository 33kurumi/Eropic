package io.github.kurumi33.eropic.core.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [GalleryDates] 的单测。
 *
 * 所有时间都用"本地时区"构造：分组规则本来就以设备时区为准，
 * 写死 UTC 毫秒再去断言"10 月"会在不同时区的 CI 上随机失败。
 * 只有 [fixedMillisAreConvertedWithSystemZone] 用固定毫秒，并且期望值也是当场用
 * java.time 算出来的，仍然与时区无关。
 */
class GalleryDatesTest {

    private fun msOf(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun expectedGroupKey(timeMs: Long, byDay: Boolean): String {
        val date = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return if (byDay) {
            date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        } else {
            date.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        }
    }

    @Test
    fun keyOfSeparatesDifferentDays() {
        assertNotEquals(GalleryDates.keyOf(msOf(2024, 7, 1)), GalleryDates.keyOf(msOf(2024, 7, 2)))
        assertEquals(GalleryDates.keyOf(msOf(2024, 7, 1)), GalleryDates.keyOf(msOf(2024, 7, 1, hour = 23, minute = 59)))
        assertEquals(GalleryDateKey(2024, 7, 1), GalleryDates.keyOf(msOf(2024, 7, 1)))
    }

    @Test
    fun monthKeyIsStableInsideOneMonth() {
        assertEquals(
            GalleryDates.groupKeyOf(msOf(2024, 7, 1, hour = 0), byDay = false),
            GalleryDates.groupKeyOf(msOf(2024, 7, 31, hour = 23), byDay = false),
        )
        assertEquals("2024-07", GalleryDates.groupKeyOf(msOf(2024, 7, 16), byDay = false))
    }

    @Test
    fun monthKeyDoesNotCollideAcrossYears() {
        assertEquals("2024-12", GalleryDates.groupKeyOf(msOf(2024, 12, 31, hour = 23), byDay = false))
        assertEquals("2025-01", GalleryDates.groupKeyOf(msOf(2025, 1, 1, hour = 0), byDay = false))
        assertNotEquals(
            GalleryDates.keyOf(msOf(2024, 12, 31, hour = 23)),
            GalleryDates.keyOf(msOf(2025, 1, 1, hour = 0)),
        )
    }

    @Test
    fun byDaySwitchChangesGranularity() {
        val morning = msOf(2024, 7, 1, hour = 9)
        val evening = msOf(2024, 7, 1, hour = 20)
        assertEquals(GalleryDates.groupKeyOf(morning, byDay = false), GalleryDates.groupKeyOf(evening, byDay = false))
        assertEquals(GalleryDates.groupKeyOf(morning, byDay = true), GalleryDates.groupKeyOf(evening, byDay = true))
        assertEquals("2024-07-01", GalleryDates.groupKeyOf(morning, byDay = true))
        assertNotEquals(
            GalleryDates.groupKeyOf(morning, byDay = true),
            GalleryDates.groupKeyOf(msOf(2024, 7, 2), byDay = true),
        )
    }

    @Test
    fun labelsAreReadableChinese() {
        assertEquals("2024年7月", GalleryDates.monthLabel(msOf(2024, 7, 16)))
        // 2024-07-02 是周二
        assertEquals("7月2日 · 周二", GalleryDates.dayLabel(msOf(2024, 7, 2)))
    }

    @Test
    fun firstDayOfMonthKeepsYearAndMonth() {
        // 2024-07-01 是周一；按日分组时 1 号是跨月边界，标题必须能看出是哪个月
        assertEquals("2024年7月1日 · 周一", GalleryDates.dayLabel(msOf(2024, 7, 1)))
        // 12 月 1 日同时是跨年边界
        assertTrue(GalleryDates.dayLabel(msOf(2024, 12, 1)).startsWith("2024年12月1日"))
    }

    @Test
    fun fixedMillisAreConvertedWithSystemZone() {
        // 2025-01-01T00:00:00Z：东八区仍是 1 月 1 日，西半球是 2024-12-31，两种都得自洽
        val fixed = 1_735_689_600_000L
        assertEquals(expectedGroupKey(fixed, byDay = false), GalleryDates.groupKeyOf(fixed, byDay = false))
        assertEquals(expectedGroupKey(fixed, byDay = true), GalleryDates.groupKeyOf(fixed, byDay = true))
        val date = Instant.ofEpochMilli(fixed).atZone(ZoneId.systemDefault()).toLocalDate()
        assertEquals(GalleryDateKey(date.year, date.monthValue, date.dayOfMonth), GalleryDates.keyOf(fixed))
    }

    @Test
    fun nonPositiveTimestampFallsIntoUnknownGroup() {
        assertEquals(GalleryDateKey(0, 0, 0), GalleryDates.keyOf(-1L))
        assertEquals("unknown", GalleryDates.groupKeyOf(0L, byDay = false))
        assertEquals("unknown", GalleryDates.groupKeyOf(-5L, byDay = true))
        assertEquals("未知日期", GalleryDates.monthLabel(0L))
        assertEquals("未知日期", GalleryDates.dayLabel(-1L))
    }
}
