package io.github.kurumi33.eropic.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VisualSimilarity] 的纯 JVM 单测。
 *
 * 只测 [VisualSimilarity.profileFromPixels]、[VisualSimilarity.colorSimilarity]、
 * [VisualSimilarity.combinedScore] 三个纯函数：Bitmap 那条路径依赖 Android 运行时，
 * 由 instrumentation 覆盖，这里用像素数组构造固定输入，保证断言可重复。
 *
 * 像素约定：0xAARRGGBB。
 */
class VisualSimilarityTest {

    /** 生成整张纯色的像素数组。 */
    private fun solid(width: Int, height: Int, color: Int): IntArray =
        IntArray(width * height) { color }

    /** 按 (x + y) % palette.size 循环取色，用来造「同风格不同配色」的图。 */
    private fun striped(width: Int, height: Int, palette: IntArray): IntArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = palette[(x + y) % palette.size]
            }
        }
        return pixels
    }

    private fun profileOf(width: Int, height: Int, color: Int): ColorProfile =
        VisualSimilarity.profileFromPixels(width, height, solid(width, height, color))

    // ---- 颜色相似度 ----

    @Test
    fun sameColorProfileScoresFullColorSimilarity() {
        val red = profileOf(8, 8, 0xFFFF0000.toInt())
        assertEquals(100.0, VisualSimilarity.colorSimilarity(red, red), 1e-9)
    }

    @Test
    fun redAndBlueHaveLowColorSimilarity() {
        val red = profileOf(8, 8, 0xFFFF0000.toInt())
        val blue = profileOf(8, 8, 0xFF0000FF.toInt())
        val score = VisualSimilarity.colorSimilarity(red, blue)
        // 色相直方图完全正交（0 分），饱和度/明度/长宽比三项全同（各满分）：只剩 15 + 15 + 10。
        assertEquals(40.0, score, 0.01)
        assertTrue(score < 50.0)
    }

    @Test
    fun hueHistogramHasTwelveBucketsAndSumsToSamples() {
        val profile = profileOf(8, 8, 0xFFFF0000.toInt())
        assertEquals(12, profile.hueHistogram.size)
        assertEquals(64, profile.hueHistogram.sum())
        // 纯红落在第 0 桶（0..30 度）。
        assertEquals(64, profile.hueHistogram[0])
    }

    @Test
    fun transparentPixelsAreExcludedFromStatistics() {
        // 左半边不透明红、右半边全透明：只有 32 个像素参与统计。
        val pixels = IntArray(8 * 8) { index ->
            if (index % 8 < 4) 0xFFFF0000.toInt() else 0x00000000
        }
        val profile = VisualSimilarity.profileFromPixels(8, 8, pixels)
        assertEquals(32, profile.hueHistogram.sum())
        assertEquals(1.0, profile.saturationMean, 1e-9)
    }

    @Test
    fun aspectRatioDifferenceLowersColorSimilarity() {
        val square = profileOf(8, 8, 0xFFFF0000.toInt())
        val wide = profileOf(16, 9, 0xFFFF0000.toInt())
        // 16:9 与 1:1 的相对差是 7/16，长宽比项只拿 0.5625；其余三项满分。
        assertEquals(95.625, VisualSimilarity.colorSimilarity(square, wide), 1e-6)
        assertTrue(VisualSimilarity.colorSimilarity(square, wide) < 100.0)
    }

    // ---- 合成分数 ----

    @Test
    fun identicalProfilesWithZeroDistanceScoreFull() {
        val red = profileOf(8, 8, 0xFFFF0000.toInt())
        // 哈希距离 0 → 基础分 100；长宽比一致 → 奖励 3 分，但被 clamp 在 100。
        assertEquals(100.0, VisualSimilarity.combinedScore(0, 0, red, red), 1e-9)
    }

    @Test
    fun largerHashDistanceLowersCombinedScore() {
        val red = profileOf(8, 8, 0xFFFF0000.toInt())
        val close = VisualSimilarity.combinedScore(0, 0, red, red)
        val far = VisualSimilarity.combinedScore(10, 10, red, red)
        // 100*(1 - 10/64) = 84.375，再加长宽比奖励 3。
        assertEquals(87.375, far, 1e-6)
        assertTrue(close - far > 10.0)
    }

    @Test
    fun outOfRangeDistancesAreClamped() {
        val red = profileOf(8, 8, 0xFFFF0000.toInt())
        // 负数按 0 处理 → 基础分 100，加重奖励后被 clamp 到 100。
        assertEquals(100.0, VisualSimilarity.combinedScore(-5, -5, red, red), 1e-9)
        // 超过 64 按 64 处理 → 基础分 0，只剩长宽比奖励 3。
        assertEquals(3.0, VisualSimilarity.combinedScore(200, 200, red, red), 1e-6)
        assertEquals(
            VisualSimilarity.combinedScore(64, 64, red, red),
            VisualSimilarity.combinedScore(200, 200, red, red),
            1e-9,
        )
    }

    @Test
    fun bothAnimeBlendsColorSimilarity() {
        val warm = VisualSimilarity.profileFromPixels(
            8,
            8,
            striped(8, 8, intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt())),
        )
        val cool = VisualSimilarity.profileFromPixels(
            8,
            8,
            striped(8, 8, intArrayOf(0xFF00FFFF.toInt(), 0xFFFF00FF.toInt())),
        )
        assertTrue(warm.isLikelyAnime)
        assertTrue(cool.isLikelyAnime)
        // 两张都像二次元：100 * 0.85 + 40 * 0.15 = 91，再加长宽比奖励 3。
        assertEquals(94.0, VisualSimilarity.combinedScore(0, 0, warm, cool), 1e-6)
    }

    @Test
    fun mixedAnimeStyleIsPenalized() {
        val anime = VisualSimilarity.profileFromPixels(
            8,
            8,
            striped(8, 8, intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt())),
        )
        val photoLike = profileOf(8, 8, 0xFF808080.toInt())
        assertTrue(anime.isLikelyAnime)
        assertFalse(photoLike.isLikelyAnime)
        // 基础分 100，只有一方像二次元扣 8，长宽比一致加 3。
        assertEquals(95.0, VisualSimilarity.combinedScore(0, 0, anime, photoLike), 1e-6)
    }

    // ---- 二次元启发式 ----

    @Test
    fun flatAnimeColorsAreDetectedAsAnime() {
        val profile = VisualSimilarity.profileFromPixels(
            8,
            8,
            striped(8, 8, intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt())),
        )
        assertTrue(profile.isLikelyAnime)
        assertEquals(1.0, profile.saturationMean, 1e-9)
        // 四个色相桶均分：最大桶占比 0.25，远小于 0.65。
        assertEquals(0.25, profile.hueHistogram.maxOrNull()!!.toDouble() / 64.0, 1e-9)
    }

    @Test
    fun flatGrayPhotoIsNotAnime() {
        // 纯灰：饱和度 0、只落一个色相桶（占比 1.0）、没有边缘 → 四条只满足「明度」一条。
        val profile = profileOf(8, 8, 0xFF808080.toInt())
        assertFalse(profile.isLikelyAnime)
        assertEquals(0.0, profile.saturationMean, 1e-9)
        assertEquals(0.0, profile.edgeDensity, 1e-9)
    }

    @Test
    fun invalidInputReturnsEmptyProfile() {
        val profile = VisualSimilarity.profileFromPixels(0, 0, IntArray(0))
        assertEquals(12, profile.hueHistogram.size)
        assertEquals(0, profile.hueHistogram.sum())
        assertEquals(0.0, profile.aspectRatio, 1e-9)
        assertFalse(profile.isLikelyAnime)

        // 数组比 width * height 短：同样退化为空画像，而不是越界崩溃。
        val short = VisualSimilarity.profileFromPixels(4, 4, IntArray(3))
        assertEquals(0, short.hueHistogram.sum())
    }
}
