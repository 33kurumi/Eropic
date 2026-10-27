package io.github.kurumi33.eropic.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class GalleryDatesTest {

    private fun msOf(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDateTime.of(year, month, day, hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun sameMonthSharesKey() {
        val a = GalleryDates.monthKey(msOf(2024, 7, 1))
        val b = GalleryDates.monthKey(msOf(2024, 7, 31, 23))
        assertEquals(a, b)
    }

    @Test
    fun differentMonthHasDifferentKey() {
        assertNotEquals(GalleryDates.monthKey(msOf(2024, 7, 31)), GalleryDates.monthKey(msOf(2024, 8, 1)))
    }

    @Test
    fun labelContainsYearAndMonth() {
        val label = GalleryDates.monthLabel(msOf(2023, 12, 5))
        assertTrue(label, label.contains("2023"))
        assertTrue(label, label.contains("12"))
    }

    @Test
    fun nonPositiveTimestampIsUnknown() {
        assertEquals("unknown", GalleryDates.monthKey(0L))
        assertEquals("未知日期", GalleryDates.monthLabel(-1L))
    }
}
