package io.github.kurumi33.eropic.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [partitionForNear] 的纯 JVM 单测：覆盖「保留过滤」「差分排除」「两者交集」。
 *
 * DuplicateScanner 本身依赖 ContentResolver/DAO 不好测，但它最容易出错的其实是分区顺序：
 * 先剔保留、再挑差分、剩下的才聚类——这些用例就是在守这个顺序。
 */
class NearPartitionTest {

    private fun entry(id: Long, name: String) = DiffEntry(id = id, displayName = name, sizeBytes = 1024L)

    private fun item(id: Long, dhash: Long = 0L, phash: Long = 0L) = HashItem(id = id, dhash = dhash, phash = phash)

    @Test
    fun diffImagesExcludedFromNearGroups() {
        // 同一构图的 p0/p1 像素极近：若不先挑差分，它们会被并进相似组
        val entries = listOf(entry(1, "pic_p0.jpg"), entry(2, "pic_p1.jpg"))
        val hashes = mapOf(1L to item(1), 2L to item(2))

        val partition = partitionForNear(entries, hashes, ignored = emptySet(), dThreshold = 10, pThreshold = 8)

        assertEquals(1, partition.diffGroups.size)
        assertEquals(listOf(1L, 2L), partition.diffGroups[0].map { it.id })
        assertTrue("差分图不该出现在相似组里", partition.nearGroups.isEmpty())
    }

    @Test
    fun ignoredMemberDroppedFromDiffGroup() {
        val entries = listOf(entry(1, "pic_p0.jpg"), entry(2, "pic_p1.jpg"))
        val hashes = mapOf(1L to item(1), 2L to item(2))

        val partition = partitionForNear(entries, hashes, ignored = setOf(2L), dThreshold = 10, pThreshold = 8)

        assertTrue("被保留的成员剔掉后不足 2 张，该组不再显示", partition.diffGroups.isEmpty())
    }

    @Test
    fun ignoredMemberDroppedFromNearGroup() {
        val entries = listOf(entry(1, "a.jpg"), entry(2, "b.jpg"))
        val hashes = mapOf(1L to item(1), 2L to item(2))

        val kept = partitionForNear(entries, hashes, ignored = emptySet(), dThreshold = 10, pThreshold = 8)
        assertEquals(1, kept.nearGroups.size)

        val filtered = partitionForNear(entries, hashes, ignored = setOf(1L), dThreshold = 10, pThreshold = 8)
        assertTrue(filtered.nearGroups.isEmpty())
    }

    @Test
    fun normalNearGroupSurvives() {
        // 三张里有一张被保留：剩下两张仍然成组，组里不应含被保留的那张
        val entries = listOf(entry(1, "a.jpg"), entry(2, "b.jpg"), entry(3, "c_p0.jpg"))
        val hashes = mapOf(1L to item(1), 2L to item(2), 3L to item(3))

        val partition = partitionForNear(entries, hashes, ignored = setOf(3L), dThreshold = 10, pThreshold = 8)

        assertEquals(1, partition.nearGroups.size)
        assertEquals(setOf(1L, 2L), partition.nearGroups[0].members.map { it.id }.toSet())
    }

    @Test
    fun diffAndNearCoexistSeparately() {
        val entries = listOf(
            entry(1, "pic_p0.jpg"),
            entry(2, "pic_p1.jpg"),
            entry(3, "other.jpg"),
            entry(4, "other2.jpg"),
        )
        val hashes = mapOf(
            1L to item(1),
            2L to item(2),
            3L to item(3),
            4L to item(4),
        )

        val partition = partitionForNear(entries, hashes, ignored = emptySet(), dThreshold = 10, pThreshold = 8)

        assertEquals(1, partition.diffGroups.size)
        assertEquals(1, partition.nearGroups.size)
        assertEquals(setOf(3L, 4L), partition.nearGroups[0].members.map { it.id }.toSet())
    }

    @Test
    fun emptyInputReturnsEmptyPartition() {
        val partition = partitionForNear(emptyList(), emptyMap(), ignored = emptySet(), dThreshold = 10, pThreshold = 8)
        assertTrue(partition.diffGroups.isEmpty())
        assertTrue(partition.nearGroups.isEmpty())
    }

    @Test
    fun entryWithoutHashIgnoredByNearGrouping() {
        // 没有指纹的条目（理论上 hashedItems 不会给）不能把聚类搞崩，也不能冒充成员
        val entries = listOf(entry(1, "a.jpg"), entry(2, "b.jpg"))
        val hashes = mapOf(1L to item(1))

        val partition = partitionForNear(entries, hashes, ignored = emptySet(), dThreshold = 10, pThreshold = 8)

        assertTrue(partition.nearGroups.isEmpty())
    }
}
