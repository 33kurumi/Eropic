package io.github.kurumi33.eropic.core.cleanup

import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QualityScorer] 的纯 JVM 单测：不依赖 Android，也不读写真实文件。
 *
 * 覆盖任务要求的几个关键场景：单张不产生删除项、像素优先、无损胜有损、
 * bpp 过低扣分、新图小分辨率压不过旧图大分辨率、平局按 id 稳定。
 */
class QualityScorerTest {

    /**
     * 构造一张测试图片。
     *
     * [MediaEntity] 必填字段很多，这里给一组「4800×3200、1200 万像素、3MB」的默认值，
     * 各测试只覆盖自己关心的字段，避免构造代码淹没断言。
     */
    private fun media(
        id: Long,
        displayName: String = "IMG_0001.jpg",
        sizeBytes: Long = 3_000_000L,
        width: Int = 4800,
        height: Int = 3200,
        dateTakenMs: Long = 1_600_000_000_000L,
        mimeType: String = "image/jpeg",
        blurScore: Double? = null,
    ): MediaEntity = MediaEntity(
        id = id,
        volumeName = "external_primary",
        path = "/storage/emulated/0/DCIM/Camera/$displayName",
        displayName = displayName,
        relativePath = "DCIM/Camera/",
        bucketId = 1L,
        bucketName = "Camera",
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        dateTakenMs = dateTakenMs,
        dateModifiedMs = dateTakenMs,
        dateAddedSecs = 1_600_000_000L,
        orientation = 0,
        isFavorite = false,
        blurScore = blurScore,
        indexedAtMs = 1_700_000_000_000L,
    )

    private fun factor(score: QualityScore, label: String): QualityFactor? =
        score.factors.firstOrNull { it.label == label }

    // ---- 基础边界 ----

    @Test
    fun `空列表不产生任何建议`() {
        val result = QualityScorer.assess(emptyList())

        assertTrue(result.scores.isEmpty())
        assertEquals(-1L, result.keepMediaId)
        assertTrue(result.trashIds.isEmpty())
        assertEquals(0.0, result.confidence, 0.0)
        assertTrue(result.rationale.isNotEmpty())
    }

    @Test
    fun `单张图片不会被移入回收站`() {
        val only = media(id = 1L)
        val result = QualityScorer.assess(listOf(only))

        assertEquals(listOf(1L), result.scores.map { it.mediaId })
        assertEquals(1L, result.keepMediaId)
        assertTrue(result.trashIds.isEmpty())
        assertEquals(0.0, result.confidence, 0.0)
        // 单张时 confidence 必为 0，依据文案要说清「没有重复项」。
        assertTrue(result.rationale.any { it.contains("只有 1 张") })
    }

    @Test
    fun `缺少分辨率与体积时不硬推荐`() {
        val a = media(id = 1L, width = 0, height = 0, sizeBytes = 0L)
        val b = media(id = 2L, width = 0, height = 0, sizeBytes = 0L)
        val result = QualityScorer.assess(listOf(a, b))

        assertEquals(0.0, result.confidence, 0.0)
        assertTrue(result.rationale.any { it.contains("资料不足") })
        // 即使资料不足，也仍然给出一个稳定的保留项，UI 可以要求用户手动确认。
        assertEquals(1L, result.keepMediaId)
    }

    // ---- 像素优先 ----

    @Test
    fun `同格式同压缩率下保留分辨率更高的一张`() {
        // bpp 都是 2.0，格式、时间、文件名一致，只有像素不同。
        val big = media(id = 1L, displayName = "假期_海边_001.jpg", width = 4000, height = 3000, sizeBytes = 3_000_000L)
        val small = media(id = 2L, displayName = "假期_海边_002.jpg", width = 1000, height = 750, sizeBytes = 187_500L)
        val result = QualityScorer.assess(listOf(small, big))

        assertEquals(1L, result.keepMediaId)
        assertEquals(listOf(2L), result.trashIds)
        val bigPixel = factor(result.scores.first { it.mediaId == 1L }, "像素")!!.score
        val smallPixel = factor(result.scores.first { it.mediaId == 2L }, "像素")!!.score
        assertEquals(100.0, bigPixel, 0.01)
        assertEquals(6.3, smallPixel, 0.1)
        assertTrue(result.rationale.any { it.contains("像素") })
    }

    @Test
    fun `无损格式优先于有损格式`() {
        // 像素、bpp 之外的条件都一致，PNG 应该压过 JPG。
        val png = media(
            id = 1L, displayName = "照片_无损.png", mimeType = "image/png",
            width = 1200, height = 1000, sizeBytes = 300_000L,
        )
        val jpg = media(
            id = 2L, displayName = "照片_有损.jpg", mimeType = "image/jpeg",
            width = 1200, height = 1000, sizeBytes = 45_000L,
        )
        val result = QualityScorer.assess(listOf(jpg, png))

        assertEquals(1L, result.keepMediaId)
        val pngFormat = factor(result.scores.first { it.mediaId == 1L }, "格式")!!
        val jpgFormat = factor(result.scores.first { it.mediaId == 2L }, "格式")!!
        assertEquals(100.0, pngFormat.score, 0.01)
        assertEquals(55.0, jpgFormat.score, 0.01)
        assertTrue(pngFormat.value.contains("无损"))
        assertTrue(jpgFormat.value.contains("有损"))
    }

    @Test
    fun `bpp 过低会被扣分并在依据里说明`() {
        // 同尺寸、同格式，A 压缩过狠（0.32 bpp），B 接近原始（4 bpp）。
        val crushed = media(id = 1L, width = 1000, height = 1000, sizeBytes = 40_000L)
        val full = media(id = 2L, width = 1000, height = 1000, sizeBytes = 500_000L)
        val result = QualityScorer.assess(listOf(crushed, full))

        assertEquals(2L, result.keepMediaId)
        val crushedBpp = factor(result.scores.first { it.mediaId == 1L }, "压缩率")!!
        assertEquals(32.0, crushedBpp.score, 0.1)
        assertNotNull(crushedBpp.note)
        assertTrue(crushedBpp.note!!.contains("压缩过狠"))
        assertTrue(result.rationale.any { it.contains("压缩过狠") })
    }

    @Test
    fun `新图小分辨率压不过旧图大分辨率`() {
        val oldLarge = media(
            id = 1L, displayName = "旧照片_旅行.jpg", width = 4000, height = 3000,
            sizeBytes = 3_000_000L, dateTakenMs = 1_500_000_000_000L,
        )
        val newSmall = media(
            id = 2L, displayName = "新照片_旅行.jpg", width = 1000, height = 750,
            sizeBytes = 500_000L, dateTakenMs = 1_700_000_000_000L,
        )
        val result = QualityScorer.assess(listOf(newSmall, oldLarge))

        // 拍摄时间权重只有 8%，不足以逆转 45% 的像素差距。
        assertEquals(1L, result.keepMediaId)
        assertEquals(listOf(2L), result.trashIds)
        assertTrue(result.rationale.any { it.contains("新≠一定更好") })
    }

    // ---- 平局与稳定性 ----

    @Test
    fun `平局时按 id 升序稳定保留`() {
        // 两张除 id 外完全一样（含文件名），分数必然相同。
        val second = media(id = 7L, displayName = "海滩_自拍.jpg")
        val first = media(id = 3L, displayName = "海滩_自拍.jpg")
        val result = QualityScorer.assess(listOf(second, first))

        assertEquals(listOf(3L, 7L), result.scores.map { it.mediaId })
        assertEquals(3L, result.keepMediaId)
        assertEquals(listOf(7L), result.trashIds)
        assertEquals(0.0, result.confidence, 0.0)
        assertTrue(result.rationale.any { it.contains("几乎平局") })
    }

    @Test
    fun `评分按总分降序排列且结果与输入顺序无关`() {
        val a = media(id = 10L, displayName = "风景_远山.jpg", width = 3000, height = 2000, sizeBytes = 2_000_000L)
        val b = media(id = 20L, displayName = "风景_近景.jpg", width = 6000, height = 4000, sizeBytes = 8_000_000L)
        val c = media(id = 30L, displayName = "风景_特写.jpg", width = 1500, height = 1000, sizeBytes = 500_000L)

        val one = QualityScorer.assess(listOf(a, b, c))
        val other = QualityScorer.assess(listOf(c, b, a))

        assertEquals(one.scores.map { it.mediaId }, other.scores.map { it.mediaId })
        assertEquals(one.keepMediaId, other.keepMediaId)
        // 分数必须严格降序（相同则按 id 升序）。
        val totals = one.scores.map { it.total }
        assertTrue(totals.zipWithNext().all { (x, y) -> x >= y })
        assertEquals(20L, one.keepMediaId)
    }

    // ---- 因子与权重 ----

    @Test
    fun `没有清晰度指纹时不出现清晰度因子且权重仍归一化`() {
        val a = media(id = 1L, blurScore = null)
        val b = media(id = 2L, blurScore = 5.0)
        val result = QualityScorer.assess(listOf(a, b))

        result.scores.forEach { score ->
            assertEquals(5, score.factors.size)
            assertNull(factor(score, "清晰度"))
            assertEquals(1.0, score.factors.sumOf { it.weight }, 1e-9)
            // 每项权重都是 0~1 的正数。
            assertTrue(score.factors.all { it.weight > 0.0 && it.weight <= 1.0 })
        }
    }

    @Test
    fun `全组都有指纹时清晰度参与评分`() {
        val sharp = media(id = 1L, displayName = "猫_清晰.jpg", blurScore = 500.0)
        val blurry = media(id = 2L, displayName = "猫_模糊.jpg", blurScore = 100.0)
        val result = QualityScorer.assess(listOf(blurry, sharp))

        assertEquals(1L, result.keepMediaId)
        result.scores.forEach { score ->
            assertEquals(6, score.factors.size)
            assertEquals(1.0, score.factors.sumOf { it.weight }, 1e-9)
        }
        val sharpFactor = factor(result.scores.first { it.mediaId == 1L }, "清晰度")!!
        val blurryFactor = factor(result.scores.first { it.mediaId == 2L }, "清晰度")!!
        assertEquals(100.0, sharpFactor.score, 0.01)
        assertEquals(20.0, blurryFactor.score, 0.01)
        assertTrue(result.rationale.any { it.contains("清晰度") })
    }

    @Test
    fun `依据至少三条且包含回收站范围与综合分`() {
        val big = media(id = 1L, displayName = "合影_全家.jpg", width = 4000, height = 3000, sizeBytes = 3_000_000L)
        val small = media(id = 2L, displayName = "合影_全家_缩略.jpg", width = 800, height = 600, sizeBytes = 60_000L)
        val result = QualityScorer.assess(listOf(big, small))

        assertTrue(result.rationale.size in 3..6)
        assertTrue(result.rationale.any { it.contains("30 天内可恢复") })
        assertTrue(result.rationale.any { it.contains("综合评分") })
        assertTrue(result.confidence in 0.0..1.0)
        // 本组差距很大，confidence 应该接近 1。
        assertTrue(result.confidence > 0.8)
    }

    // ---- KeepSuggester 与质量评分的衔接 ----

    @Test
    fun `KeepSuggester 用 MediaEntity 时返回质量最高的一张`() {
        val best = media(id = 1L, displayName = "合影_原图.jpg", width = 4000, height = 3000, sizeBytes = 3_000_000L)
        val worse = media(id = 2L, displayName = "合影_转发.jpg", width = 800, height = 600, sizeBytes = 40_000L)

        val suggestion = KeepSuggester.suggest(listOf(worse, best))

        assertEquals(listOf(1L), suggestion.keepIds)
        assertEquals(listOf(2L), suggestion.trashIds)
        assertEquals(worse.sizeBytes, suggestion.reclaimableBytes)
        assertTrue(suggestion.rationale.isNotEmpty())
    }

    @Test
    fun `KeepSuggester 的空组与单张都不产生删除项`() {
        assertTrue(KeepSuggester.suggest(emptyList<MediaEntity>()).trashIds.isEmpty())
        val single = KeepSuggester.suggest(listOf(media(id = 9L)))
        assertEquals(listOf(9L), single.keepIds)
        assertTrue(single.trashIds.isEmpty())
        assertEquals(0L, single.reclaimableBytes)
    }

    @Test
    fun `KeepCandidate 缺少分辨率时 BEST_QUALITY 退化为按体积并在依据里说明`() {
        // 旧入口（KeepCandidate 只有体积/时间/路径）没有分辨率，只能近似选择。
        val large = KeepCandidate(id = 1L, sizeBytes = 3_000_000L, dateTakenMs = 0L, path = "/a.jpg")
        val small = KeepCandidate(id = 2L, sizeBytes = 100_000L, dateTakenMs = 0L, path = "/b.jpg")

        val suggestion = KeepSuggester.suggest(listOf(small, large), KeepStrategy.BEST_QUALITY)

        assertEquals(listOf(1L), suggestion.keepIds)
        assertEquals(listOf(2L), suggestion.trashIds)
        assertTrue(suggestion.rationale.any { it.contains("无法做质量综合评分") })
    }
}
