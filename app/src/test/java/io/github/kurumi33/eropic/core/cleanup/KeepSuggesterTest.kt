package io.github.kurumi33.eropic.core.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** KeepSuggester 纯逻辑测试：5 种策略、NONE、空列表、单项与并列时的确定性。纯 JVM，无 Android / Compose 依赖。 */
class KeepSuggesterTest {

    private fun candidate(
        id: Long,
        sizeBytes: Long = 100L,
        dateTakenMs: Long = 1_000L,
        path: String = "DCIM/Camera/$id.jpg",
    ) = KeepCandidate(id = id, sizeBytes = sizeBytes, dateTakenMs = dateTakenMs, path = path)

    @Test
    fun emptyListSuggestsNothing() {
        val suggestion = KeepSuggester.suggest(emptyList(), KeepStrategy.LARGEST)
        assertTrue(suggestion.keepIds.isEmpty())
        assertTrue(suggestion.trashIds.isEmpty())
        assertEquals(0L, suggestion.reclaimableBytes)
    }

    @Test
    fun singleItemIsKeptWithoutTrash() {
        val suggestion = KeepSuggester.suggest(listOf(candidate(1)), KeepStrategy.LARGEST)
        assertEquals(listOf(1L), suggestion.keepIds)
        assertTrue(suggestion.trashIds.isEmpty())
        assertEquals(0L, suggestion.reclaimableBytes)
    }

    @Test
    fun largestKeepsBiggestFile() {
        val suggestion = KeepSuggester.suggest(
            listOf(
                candidate(1, sizeBytes = 300),
                candidate(2, sizeBytes = 900),
                candidate(3, sizeBytes = 500),
            ),
            KeepStrategy.LARGEST,
        )
        assertEquals(listOf(2L), suggestion.keepIds)
        assertEquals(setOf(1L, 3L), suggestion.trashIds.toSet())
        assertEquals(800L, suggestion.reclaimableBytes)
    }

    @Test
    fun smallestKeepsSmallestFile() {
        val suggestion = KeepSuggester.suggest(
            listOf(
                candidate(1, sizeBytes = 300),
                candidate(2, sizeBytes = 900),
                candidate(3, sizeBytes = 500),
            ),
            KeepStrategy.SMALLEST,
        )
        assertEquals(listOf(1L), suggestion.keepIds)
        assertEquals(setOf(2L, 3L), suggestion.trashIds.toSet())
        assertEquals(1_400L, suggestion.reclaimableBytes)
    }

    @Test
    fun oldestKeepsEarliestDate() {
        val suggestion = KeepSuggester.suggest(
            listOf(
                candidate(1, dateTakenMs = 5_000),
                candidate(2, dateTakenMs = 1_000),
                candidate(3, dateTakenMs = 9_000),
            ),
            KeepStrategy.OLDEST,
        )
        assertEquals(listOf(2L), suggestion.keepIds)
        assertEquals(setOf(1L, 3L), suggestion.trashIds.toSet())
    }

    @Test
    fun newestKeepsLatestDate() {
        val suggestion = KeepSuggester.suggest(
            listOf(
                candidate(1, dateTakenMs = 5_000),
                candidate(2, dateTakenMs = 1_000),
                candidate(3, dateTakenMs = 9_000),
            ),
            KeepStrategy.NEWEST,
        )
        assertEquals(listOf(3L), suggestion.keepIds)
        assertEquals(setOf(1L, 2L), suggestion.trashIds.toSet())
    }

    @Test
    fun shortestPathKeepsShallowestPath() {
        val suggestion = KeepSuggester.suggest(
            listOf(
                candidate(1, path = "DCIM/Camera/2024/a.jpg"),
                candidate(2, path = "DCIM/a.jpg"),
                candidate(3, path = "DCIM/Camera/b.jpg"),
            ),
            KeepStrategy.SHORTEST_PATH,
        )
        assertEquals(listOf(2L), suggestion.keepIds)
    }

    @Test
    fun noneStrategySuggestsNothing() {
        val suggestion = KeepSuggester.suggest(
            listOf(candidate(1), candidate(2), candidate(3)),
            KeepStrategy.NONE,
        )
        assertTrue(suggestion.keepIds.isEmpty())
        assertTrue(suggestion.trashIds.isEmpty())
        assertEquals(0L, suggestion.reclaimableBytes)
    }

    @Test
    fun tieBreaksByIdAscending() {
        // 体积并列时保留 id 更小的一张。
        val largest = KeepSuggester.suggest(
            listOf(
                candidate(7, sizeBytes = 500),
                candidate(3, sizeBytes = 500),
                candidate(5, sizeBytes = 500),
            ),
            KeepStrategy.LARGEST,
        )
        assertEquals(listOf(3L), largest.keepIds)

        // 拍摄时间并列时同样按 id 升序。
        val oldest = KeepSuggester.suggest(
            listOf(candidate(9, dateTakenMs = 2_000), candidate(4, dateTakenMs = 2_000)),
            KeepStrategy.OLDEST,
        )
        assertEquals(listOf(4L), oldest.keepIds)
    }

    @Test
    fun resultDoesNotDependOnInputOrder() {
        val items = listOf(
            candidate(9, sizeBytes = 200),
            candidate(4, sizeBytes = 200),
            candidate(7, sizeBytes = 800),
        )
        val forward = KeepSuggester.suggest(items, KeepStrategy.LARGEST)
        val reversed = KeepSuggester.suggest(items.reversed(), KeepStrategy.LARGEST)
        assertEquals(forward, reversed)
    }

    @Test
    fun everyAutoStrategyKeepsExactlyOne() {
        val items = listOf(
            candidate(1, sizeBytes = 100, dateTakenMs = 3_000, path = "DCIM/Camera/a.jpg"),
            candidate(2, sizeBytes = 200, dateTakenMs = 1_000, path = "DCIM/b.jpg"),
            candidate(3, sizeBytes = 300, dateTakenMs = 2_000, path = "DCIM/Camera/2024/c.jpg"),
        )
        KeepStrategy.entries.forEach { strategy ->
            if (strategy == KeepStrategy.NONE) return@forEach
            val suggestion = KeepSuggester.suggest(items, strategy)
            assertEquals(1, suggestion.keepIds.size)
            assertEquals(items.size - 1, suggestion.trashIds.size)
            assertTrue(suggestion.keepIds.none { it in suggestion.trashIds })
        }
    }

    @Test
    fun reclaimableBytesSumsTrashedSizes() {
        val suggestion = KeepSuggester.suggest(
            listOf(
                candidate(1, sizeBytes = 10),
                candidate(2, sizeBytes = 20),
                candidate(3, sizeBytes = 70),
            ),
            KeepStrategy.SMALLEST,
        )
        // 保留 10 B 的那张，其余 20 + 70 = 90 B 计入可释放空间。
        assertEquals(90L, suggestion.reclaimableBytes)
    }
}
