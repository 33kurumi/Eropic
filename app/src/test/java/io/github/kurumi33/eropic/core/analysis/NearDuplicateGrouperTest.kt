package io.github.kurumi33.eropic.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearDuplicateGrouperTest {

    private fun item(id: Long, dhash: Long, phash: Long = 0L) = HashItem(id, dhash, phash)

    @Test
    fun groupsHashesWithinThreshold() {
        val items = listOf(
            item(1, 0L),
            item(2, 0b11L),          // 距离 2
            item(3, 0b1111L),        // 距离 4
            item(4, -1L),            // 距离 64，不应入组
        )
        val groups = NearDuplicateGrouper.group(items)
        assertEquals(1, groups.size)
        val members = groups.first().members.map { it.id }.toSet()
        assertEquals(setOf(1L, 2L, 3L), members)
        assertEquals(4, groups.first().maxDistance)
    }

    @Test
    fun respectsDhashThresholdBoundary() {
        val tenBits = (0 until 10).fold(0L) { acc, bit -> acc or (1L shl bit) }
        val elevenBits = (0 until 11).fold(0L) { acc, bit -> acc or (1L shl bit) }

        val within = NearDuplicateGrouper.group(listOf(item(1, 0L), item(2, tenBits)))
        assertEquals(1, within.size)

        val beyond = NearDuplicateGrouper.group(listOf(item(1, 0L), item(2, elevenBits)))
        assertTrue(beyond.isEmpty())
    }

    @Test
    fun phashActsAsSecondConfirmation() {
        val farPhash = -1L
        val items = listOf(item(1, 0L, 0L), item(2, 0L, farPhash))
        assertTrue(NearDuplicateGrouper.group(items).isEmpty())
    }

    @Test
    fun handlesLargeInputWithoutQuadraticBlowup() {
        val items = (0 until 5_000).map { index ->
            item(index.toLong(), index.toLong() * 0x9E3779B97F4A7C15uL.toLong())
        }
        val groups = NearDuplicateGrouper.group(items)
        // 随机哈希之间不应该出现大量误报（阈值 10/8 下期望接近 0 组）
        assertTrue("unexpected groups: ${groups.size}", groups.size < 10)
    }

    @Test
    fun identicalHashesCollapseIntoOneGroup() {
        val items = (1..8).map { item(it.toLong(), 0x1234_5678L, 0x4321_8765L) }
        val groups = NearDuplicateGrouper.group(items)
        assertEquals(1, groups.size)
        assertEquals(8, groups.first().members.size)
        assertEquals(0, groups.first().maxDistance)
    }
}
