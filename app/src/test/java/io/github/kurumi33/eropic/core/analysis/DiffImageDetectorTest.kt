package io.github.kurumi33.eropic.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 差分图判定的纯 JVM 单测：命名规则是用户可见行为的全部依据（不跑像素），
 * 所以每条规则都要有正例 + 反例，避免以后"顺手放宽"把普通图片也并成差分。
 */
class DiffImageDetectorTest {

    private fun entry(id: Long, name: String, size: Long = 1024L) = DiffEntry(id, name, size)

    @Test
    fun collect_groupsP0AndP1() {
        val groups = DiffImageDetector.collect(
            listOf(entry(1, "IMG_001_p0.jpg"), entry(2, "IMG_001_p1.jpg")),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L), groups[0].map { it.id })
    }

    @Test
    fun collect_p2JoinsSameGroup() {
        val groups = DiffImageDetector.collect(
            listOf(entry(1, "IMG_001_p0.jpg"), entry(2, "IMG_001_p1.jpg"), entry(3, "IMG_001_p2.jpg")),
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].size)
    }

    @Test
    fun collect_differentBaseNotMerged() {
        val groups = DiffImageDetector.collect(
            listOf(entry(1, "IMG_001_p0.jpg"), entry(2, "IMG_002_p1.jpg")),
        )
        assertTrue("base 不同不该成组", groups.isEmpty())
    }

    @Test
    fun collect_differentExtensionNotMerged() {
        assertTrue(
            "扩展名不同（导出流程不同）不该合并",
            DiffImageDetector.collect(
                listOf(entry(1, "IMG_001_p0.jpg"), entry(2, "IMG_001_p1.png")),
            ).isEmpty(),
        )
    }

    @Test
    fun collect_singleFileNotGrouped() {
        assertTrue(DiffImageDetector.collect(listOf(entry(1, "IMG_001_p0.jpg"))).isEmpty())
        assertTrue(DiffImageDetector.collect(emptyList()).isEmpty())
    }

    @Test
    fun collect_chineseDiffSuffix() {
        val groups = DiffImageDetector.collect(
            listOf(entry(1, "插画_差分1.png"), entry(2, "插画_差分2.png")),
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
    }

    @Test
    fun collect_recognizesSeparatorsAndCase() {
        val names = listOf("IMG-001_p0.JPG", "IMG-001_P1.jpg", "IMG-001 p2.jpg")
        val groups = DiffImageDetector.collect(names.mapIndexed { index, name -> entry(index + 1L, name) })
        assertEquals("大小写/分隔符不同应视为同一 base", 1, groups.size)
        assertEquals(3, groups[0].size)
    }

    @Test
    fun collect_sortIsStable() {
        val groups = DiffImageDetector.collect(
            listOf(
                entry(1, "bb_p1.jpg"),
                entry(2, "bb_p0.jpg"),
                entry(3, "aa_p1.jpg"),
                entry(4, "aa_p0.jpg"),
            ),
        )
        assertEquals(2, groups.size)
        // 组间按 baseKey 升序：a 在 b 前
        assertEquals(listOf(4L, 3L), groups[0].map { it.id })
        // 组间键是 "aa|jpg" < "bb|jpg"
        // 组内按 displayName 升序：p0 在 p1 前
        assertEquals(listOf(2L, 1L), groups[1].map { it.id })
    }

    @Test
    fun baseKeyOf_returnsNullWithoutDiffSuffix() {
        assertNull(DiffImageDetector.baseKeyOf("IMG_001.jpg"))
        assertNull(DiffImageDetector.baseKeyOf("风景照片.png"))
        assertNull(DiffImageDetector.baseKeyOf(""))
        assertNull("四位数不是差分序号（可能是年份）", DiffImageDetector.baseKeyOf("photo_p2024.jpg"))
    }

    @Test
    fun baseKeyOf_rejectsTooShortBase() {
        assertNull("纯序号文件名（p0/p1）不该凑成一组", DiffImageDetector.baseKeyOf("p0.jpg"))
        assertNull(DiffImageDetector.baseKeyOf("a_p1.png"))
    }

    @Test
    fun baseKeyOf_ignoresExtensionCaseButKeepsIt() {
        assertNotNull(DiffImageDetector.baseKeyOf("IMG_p0.JPG"))
        assertEquals(
            "同一张图扩展名大小写不同应归一组",
            DiffImageDetector.baseKeyOf("IMG_P0.JPG"),
            DiffImageDetector.baseKeyOf("img_p1.jpg"),
        )
    }

    @Test
    fun baseKeyOf_handlesNameWithoutExtension() {
        // 没有扩展名时整个名字当 stem，仍然能识别尾部 p0
        val groups = DiffImageDetector.collect(listOf(entry(1, "wallpaper_p0"), entry(2, "wallpaper_p1")))
        assertEquals(1, groups.size)
    }
}
