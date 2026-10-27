package io.github.kurumi33.eropic.ui.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FilterUiState → MediaFilter 的映射：档位、格式、时间、收藏。纯 JVM，无 Compose 运行时依赖。 */
class FilterUiStateTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun emptyByDefault() {
        assertTrue(FilterUiState().isEmpty)
    }

    @Test
    fun sizeMegabytesBecomeBytes() {
        val filter = FilterUiState(sizeMinMb = 10).toFilter(now)
        assertEquals(10L * 1024L * 1024L, filter.sizeMinBytes)
    }

    @Test
    fun formatsFlattenToMimeTypes() {
        val filter = FilterUiState(formats = setOf(FormatFilter.JPG, FormatFilter.HEIC)).toFilter(now)
        assertEquals(setOf("image/jpeg", "image/heic", "image/heif"), filter.mimeTypes)
    }

    @Test
    fun lastSevenDaysSetsLowerBoundOnly() {
        val filter = FilterUiState(datePreset = DatePreset.LAST_7_DAYS).toFilter(now)
        assertEquals(now - 7 * day, filter.dateFromMs)
        assertNull(filter.dateToMs)
    }

    @Test
    fun olderThanThreeYearsSetsUpperBoundOnly() {
        val filter = FilterUiState(datePreset = DatePreset.OLDER_THAN_3Y).toFilter(now)
        assertEquals(now - 3 * 365 * day, filter.dateToMs)
        assertNull(filter.dateFromMs)
    }

    @Test
    fun favoritesOnlyPropagates() {
        assertTrue(FilterUiState(favoritesOnly = true).toFilter(now).favoritesOnly)
    }

    @Test
    fun anyDimensionMakesItNonEmpty() {
        assertFalse(FilterUiState(sizeMinMb = 5).isEmpty)
        assertFalse(FilterUiState(formats = setOf(FormatFilter.PNG)).isEmpty)
        assertFalse(FilterUiState(datePreset = DatePreset.LAST_YEAR).isEmpty)
        assertFalse(FilterUiState(favoritesOnly = true).isEmpty)
    }
}
