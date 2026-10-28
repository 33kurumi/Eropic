package io.github.kurumi33.eropic.core.folders

import io.github.kurumi33.eropic.core.model.FolderRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTreeTest {

    private fun row(path: String, count: Long, bytes: Long) = FolderRow(path, count, bytes)

    @Test
    fun emptyRowsBuildEmptyTree() {
        assertTrue(FolderTreeBuilder.build(emptyList()).isEmpty())
    }

    @Test
    fun singleRowBecomesNestedPath() {
        val nodes = FolderTreeBuilder.build(listOf(row("DCIM/Camera/", 10, 1000)))

        assertEquals(1, nodes.size)
        val dcim = nodes.first()
        assertEquals("DCIM", dcim.name)
        assertEquals("DCIM/", dcim.path)
        assertEquals(0, dcim.depth)
        // 中间目录没有直属图片，但总数要汇总上来。
        assertEquals(0L, dcim.directCount)
        assertEquals(10L, dcim.totalCount)
        assertEquals(1000L, dcim.totalBytes)

        val camera = dcim.children.single()
        assertEquals("Camera", camera.name)
        assertEquals("DCIM/Camera/", camera.path)
        assertEquals(1, camera.depth)
        assertEquals(10L, camera.directCount)
        assertEquals(1000L, camera.directBytes)
        assertTrue(camera.children.isEmpty())
    }

    @Test
    fun totalsAccumulateBottomUp() {
        val nodes = FolderTreeBuilder.build(
            listOf(
                row("DCIM/Camera/", 10, 1000),
                row("DCIM/Screenshots/", 5, 200),
                row("Pictures/", 2, 100),
            ),
        )

        val dcim = nodes.first { it.name == "DCIM" }
        assertEquals(15L, dcim.totalCount)
        assertEquals(1200L, dcim.totalBytes)
        assertEquals(0L, dcim.directCount)

        val pictures = nodes.first { it.name == "Pictures" }
        assertEquals(2L, pictures.directCount)
        assertEquals(2L, pictures.totalCount)
    }

    @Test
    fun siblingsSortedByNameAscending() {
        // 大小不参与排序：big 有 900 字节，照样排在 beta 后面。
        val nodes = FolderTreeBuilder.build(
            listOf(
                row("A/small/", 1, 10),
                row("A/big/", 1, 900),
                row("A/beta/", 1, 10),
            ),
        )

        val names = nodes.first().children.map { it.name }
        assertEquals(listOf("beta", "big", "small"), names)
    }

    @Test
    fun siblingOrderIgnoresCase() {
        val nodes = FolderTreeBuilder.build(
            listOf(
                row("A/Zebra/", 1, 10),
                row("A/album/", 1, 10),
                row("A/Beta/", 1, 10),
            ),
        )

        assertEquals(
            listOf("album", "Beta", "Zebra"),
            nodes.first().children.map { it.name },
        )
    }

    @Test
    fun rootLevelImagesBecomeRootNodePlacedLast() {
        val nodes = FolderTreeBuilder.build(
            listOf(
                row("DCIM/Camera/", 10, 1000),
                row("", 3, 300),
            ),
        )

        assertEquals(2, nodes.size)
        val root = nodes.last()
        assertEquals("根目录", root.name)
        assertEquals("", root.path)
        assertEquals(3L, root.directCount)
        assertEquals(300L, root.totalBytes)
        assertTrue(root.children.isEmpty())
    }

    @Test
    fun directAndDescendantCountsAreSeparated() {
        val nodes = FolderTreeBuilder.build(
            listOf(
                row("DCIM/", 4, 400),
                row("DCIM/Camera/", 6, 600),
            ),
        )

        val dcim = nodes.single()
        assertEquals(4L, dcim.directCount)
        assertEquals(400L, dcim.directBytes)
        assertEquals(10L, dcim.totalCount)
        assertEquals(1000L, dcim.totalBytes)
    }

    @Test
    fun duplicateRowsForSamePathAreSummed() {
        // 不同卷可能出现相同 relativePath，聚合后直接相加。
        val nodes = FolderTreeBuilder.build(
            listOf(
                row("DCIM/Camera/", 2, 200),
                row("DCIM/Camera/", 3, 300),
            ),
        )

        val camera = nodes.single().children.single()
        assertEquals(5L, camera.directCount)
        assertEquals(500L, camera.directBytes)
    }

    @Test
    fun mixedCaseAndDotsAreKeptVerbatim() {
        val nodes = FolderTreeBuilder.build(listOf(row("Pictures/.thumbnails/", 7, 70)))

        val pictures = nodes.single()
        assertEquals("Pictures", pictures.name)
        assertEquals(".thumbnails", pictures.children.single().name)
    }
}
