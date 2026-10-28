package io.github.kurumi33.eropic.ui.gallery

import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.settings.DateGroupMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class GalleryGroupingTest {

    private fun msOf(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDateTime.of(year, month, day, hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun photo(id: Long, dateTakenMs: Long): MediaEntity = MediaEntity(
        id = id,
        volumeName = "external_primary",
        path = "/storage/emulated/0/DCIM/Camera/IMG_" + id + ".jpg",
        displayName = "IMG_" + id + ".jpg",
        relativePath = "DCIM/Camera/",
        bucketId = 1L,
        bucketName = "Camera",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        width = 4000,
        height = 3000,
        dateTakenMs = dateTakenMs,
        dateModifiedMs = dateTakenMs,
        dateAddedSecs = dateTakenMs / 1000,
        orientation = 0,
        isFavorite = false,
        indexedAtMs = dateTakenMs,
    )

    private fun List<GalleryDisplayRow>.headers() = filterIsInstance<GalleryDisplayRow.Header>()

    private fun List<GalleryDisplayRow>.photos() = filterIsInstance<GalleryDisplayRow.Photo>()

    @Test
    fun dayKeySeparatesDaysInSameMonth() {
        assertNotEquals(
            GalleryGroupDates.dayKey(msOf(2024, 7, 1)),
            GalleryGroupDates.dayKey(msOf(2024, 7, 2)),
        )
        assertEquals(
            GalleryGroupDates.monthKey(msOf(2024, 7, 1)),
            GalleryGroupDates.monthKey(msOf(2024, 7, 2)),
        )
    }

    @Test
    fun dayLabelContainsDateAndWeekday() {
        val label = GalleryGroupDates.dayLabel(msOf(2024, 7, 1))
        assertTrue(label, label.contains("2024"))
        assertTrue(label, label.contains("7"))
        assertTrue(label, label.contains("1"))
        assertTrue(label, label.startsWith("2024年7月1日"))
        assertTrue(label, label.contains("周"))
    }

    @Test
    fun nonPositiveTimestampIsUnknownForDayMode() {
        assertEquals("unknown", GalleryGroupDates.dayKey(-1L))
        assertEquals("未知日期", GalleryGroupDates.dayLabel(0L))
    }

    @Test
    fun noneModeKeepsEveryPhotoInOrder() {
        val photos = listOf(photo(1L, msOf(2024, 7, 1)), photo(2L, msOf(2024, 7, 1)), photo(3L, msOf(2024, 8, 1)))
        val rows = buildGalleryDisplayRows(photos, DateGroupMode.NONE, dateSorted = true, collapsedKeys = emptySet())

        assertEquals(3, rows.size)
        assertEquals(listOf(1L, 2L, 3L), rows.photos().map { it.entity.id })
        assertEquals(listOf(0, 1, 2), rows.photos().map { it.pagingIndex })
        assertTrue(rows.headers().isEmpty())
    }

    @Test
    fun monthModeGroupsContiguousPhotosIntoOneHeader() {
        val photos = listOf(
            photo(1L, msOf(2024, 7, 1)),
            photo(2L, msOf(2024, 7, 20)),
            photo(3L, msOf(2024, 8, 2)),
        )
        val rows = buildGalleryDisplayRows(photos, DateGroupMode.MONTH, dateSorted = true, collapsedKeys = emptySet())

        val headers = rows.headers()
        assertEquals(2, headers.size)
        assertEquals("2024年7月", headers[0].label)
        assertEquals(2, headers[0].count)
        assertEquals("2024年8月", headers[1].label)
        assertEquals(1, headers[1].count)
        // 标题行在前、照片行跟在各自标题后面
        assertTrue(rows[0] is GalleryDisplayRow.Header)
        assertTrue(rows[1] is GalleryDisplayRow.Photo)
        assertTrue(rows[2] is GalleryDisplayRow.Photo)
        assertTrue(rows[3] is GalleryDisplayRow.Header)
        assertTrue(rows[4] is GalleryDisplayRow.Photo)
        assertEquals(listOf(1L, 2L, 3L), rows.photos().map { it.entity.id })
    }

    @Test
    fun collapsedGroupOnlyKeepsHeaderRow() {
        val photos = listOf(
            photo(1L, msOf(2024, 7, 1)),
            photo(2L, msOf(2024, 8, 1)),
            photo(3L, msOf(2024, 8, 2)),
        )
        val monthKey = GalleryGroupDates.monthKey(msOf(2024, 8, 1))
        val rows = buildGalleryDisplayRows(
            photos = photos,
            mode = DateGroupMode.MONTH,
            dateSorted = true,
            collapsedKeys = setOf(monthKey),
        )

        val headers = rows.headers()
        assertEquals(2, headers.size)
        assertTrue(headers[0].collapsed == false)
        assertTrue(headers[1].collapsed)
        // 折叠的 8 月只留标题：照片只剩 7 月那一张
        assertEquals(listOf(1L), rows.photos().map { it.entity.id })
        assertEquals(monthKey, headers[1].key)
    }

    @Test
    fun dayModeSeparatesSameMonthPhotos() {
        val photos = listOf(
            photo(1L, msOf(2024, 7, 1, 9)),
            photo(2L, msOf(2024, 7, 1, 18)),
            photo(3L, msOf(2024, 7, 2, 9)),
        )
        val rows = buildGalleryDisplayRows(photos, DateGroupMode.DAY, dateSorted = true, collapsedKeys = emptySet())

        val headers = rows.headers()
        assertEquals(2, headers.size)
        assertEquals(2, headers[0].count)
        assertEquals(1, headers[1].count)
        assertNotEquals(headers[0].key, headers[1].key)
    }

    @Test
    fun nonDateSortedRepeatedMonthGetsDistinctKeys() {
        // 按文件大小/名称排序时，同一个月份会反复出现：每段各一个标题，键必须唯一，否则 LazyGrid 会重复 key 崩溃。
        val photos = listOf(
            photo(1L, msOf(2024, 7, 5)),
            photo(2L, msOf(2024, 7, 20)),
            photo(3L, msOf(2024, 8, 1)),
            photo(4L, msOf(2024, 7, 2)),
        )
        val rows = buildGalleryDisplayRows(photos, DateGroupMode.MONTH, dateSorted = false, collapsedKeys = emptySet())

        val headers = rows.headers()
        assertEquals(3, headers.size)
        val keys = headers.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        // 7 月出现两段，但两段的标题文案相同
        assertEquals("2024年7月", headers[0].label)
        assertEquals("2024年7月", headers[2].label)
    }

    @Test
    fun dateSortedRepeatedMonthUsesPlainDateKey() {
        val photos = listOf(photo(1L, msOf(2024, 7, 1)), photo(2L, msOf(2024, 7, 2)))
        val rows = buildGalleryDisplayRows(photos, DateGroupMode.MONTH, dateSorted = true, collapsedKeys = emptySet())

        val headers = rows.headers()
        assertEquals(1, headers.size)
        assertEquals(GalleryGroupDates.monthKey(msOf(2024, 7, 1)), headers[0].key)
    }

    @Test
    fun unknownDatePhotosGroupUnderUnknownHeader() {
        val photos = listOf(photo(1L, 0L), photo(2L, -5L), photo(3L, msOf(2024, 7, 1)))
        val rows = buildGalleryDisplayRows(photos, DateGroupMode.MONTH, dateSorted = true, collapsedKeys = emptySet())

        val headers = rows.headers()
        assertEquals(2, headers.size)
        assertEquals("未知日期", headers[0].label)
        assertEquals("unknown", headers[0].key)
        assertEquals(2, headers[0].count)
    }

    @Test
    fun emptyInputProducesNoRows() {
        assertTrue(buildGalleryDisplayRows(emptyList(), DateGroupMode.MONTH, true, emptySet()).isEmpty())
    }
}
