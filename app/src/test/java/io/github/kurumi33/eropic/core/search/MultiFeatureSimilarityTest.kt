package io.github.kurumi33.eropic.core.search

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多特征相似度的纯 JVM 单测。
 *
 * 为什么不直接用 Bitmap：评分逻辑刻意与 Android 解耦（见 [MultiFeatureSimilarity] 的注释），
 * 这里用 ARGB 数组构造合成图，既能精确控制「缩放 / 色偏 / 完全不同的构图」这些变量，
 * 也不需要 Robolectric（跑得快，CI 上也不挑环境）。
 */
class MultiFeatureSimilarityTest {

    private val size = 64

    @Test
    fun `同一张图像素总分正好 100`() {
        val features = featuresOf(gradient(size))
        val scores = MultiFeatureSimilarity.compare(0, features, features)
        assertEquals(100.0, scores.colorLayout, 1e-6)
        assertEquals(100.0, scores.hash, 1e-6)
        assertEquals(100.0, scores.histogram, 1e-6)
        assertEquals(100.0, scores.aspect, 1e-6)
        assertEquals(100.0, scores.total, 1e-6)
    }

    @Test
    fun `缩小一半后仍然高分`() {
        val base = featuresOf(gradient(size))
        val half = size / 2
        val candidate = requireNotNull(
            MultiFeatureSimilarity.featuresFromPixels(half, half, downsample(gradient(size), size, 2), 1.0),
        )
        // 64 位 pHash 距离给 2：真实管线里缩放后的 pHash 距离通常就是 0~4
        val scores = MultiFeatureSimilarity.compare(2, base, candidate)
        assertTrue("缩放后应当仍然很像，实际 ${scores.total}", scores.total >= 85.0)
        assertTrue("颜色布局对缩放不敏感，实际 ${scores.colorLayout}", scores.colorLayout >= 90.0)
    }

    @Test
    fun `轻微色偏后仍然高分`() {
        val base = featuresOf(gradient(size))
        val shifted = IntArray(size * size) { index -> shift(gradient(size)[index], 12) }
        val scores = MultiFeatureSimilarity.compare(0, base, featuresOf(shifted))
        assertTrue("轻微色偏不应把同图判成不同图，实际 ${scores.total}", scores.total >= 85.0)
    }

    @Test
    fun `不同构图的图得分明显更低`() {
        val base = featuresOf(gradient(size))
        // 高对比棋盘格：亮度分布、边缘密度、色相都和渐变图相反，且 64 位 pHash 距离给 32（一半位不同）
        val scores = MultiFeatureSimilarity.compare(32, base, featuresOf(checker(size, 8)))
        assertTrue("不同构图应当低分，实际 ${scores.total}", scores.total < 60.0)
    }

    @Test
    fun `色相 0 度与 359 度几乎相同_180 度明显低`() {
        val red = blockAt(0.0)
        val almostRed = blockAt(359.0)
        val cyan = blockAt(180.0)
        val circular = MultiFeatureSimilarity.layoutSimilarity(listOf(red), listOf(almostRed))
        assertTrue("0 度与 359 度应当几乎一致，实际 $circular", circular >= 99.0)
        val opposite = MultiFeatureSimilarity.layoutSimilarity(listOf(red), listOf(cyan))
        assertTrue("相差 180 度应当明显低，实际 $opposite", opposite < 45.0)
    }

    @Test
    fun `256 位哈希的汉明距离按位计数`() {
        val zero = Hash256(0L, 0L, 0L, 0L)
        assertEquals(0, zero.hammingDistance(zero))
        assertEquals(3, Hash256(0L, 0L, 0L, 0L).hammingDistance(Hash256(1L, 0L, 3L, 0L)))
        assertEquals(Hash256.BIT_COUNT, zero.hammingDistance(Hash256(-1L, -1L, -1L, -1L)))
    }

    @Test
    fun `哈希分项按 64 位四成_256 位六成加权`() {
        val features = featuresOf(gradient(size))
        assertEquals(100.0, MultiFeatureSimilarity.hashSimilarity(0, features, features), 1e-6)
        // 64 位距离 32 → 64 位分 50；256 位相同 → 100；合成 0.4*50 + 0.6*100 = 80
        assertEquals(80.0, MultiFeatureSimilarity.hashSimilarity(32, features, features), 1e-6)
        // 没有 256 位哈希时退化为纯 64 位
        val legacy = features.copy(hash256 = null)
        assertEquals(50.0, MultiFeatureSimilarity.hashSimilarity(32, legacy, features), 1e-6)
    }

    @Test
    fun `长宽比相似度按相对误差线性衰减`() {
        assertEquals(100.0, MultiFeatureSimilarity.aspectSimilarity(1.0, 1.0), 1e-6)
        // 1.0 vs 1.25 → 相对误差 0.2，除以 0.5 → 60 分
        assertEquals(60.0, MultiFeatureSimilarity.aspectSimilarity(1.0, 1.25), 1e-6)
        // 相对误差达到 50% 归零
        assertEquals(0.0, MultiFeatureSimilarity.aspectSimilarity(1.0, 2.0), 1e-6)
        // 参数非法时给中性分，避免坏数据把整张图判死
        assertEquals(50.0, MultiFeatureSimilarity.aspectSimilarity(0.0, 1.0), 1e-6)
    }

    @Test
    fun `组合分按 04_03_02_01 加权并夹在 0 到 100`() {
        assertEquals(40.0, MultiFeatureSimilarity.combine(100.0, 0.0, 0.0, 0.0), 1e-6)
        assertEquals(30.0, MultiFeatureSimilarity.combine(0.0, 100.0, 0.0, 0.0), 1e-6)
        assertEquals(20.0, MultiFeatureSimilarity.combine(0.0, 0.0, 100.0, 0.0), 1e-6)
        assertEquals(10.0, MultiFeatureSimilarity.combine(0.0, 0.0, 0.0, 100.0), 1e-6)
        assertEquals(100.0, MultiFeatureSimilarity.combine(100.0, 100.0, 100.0, 100.0), 1e-6)
        // 脏数据（负分/超 100）不能把总分带出范围
        assertEquals(0.0, MultiFeatureSimilarity.combine(-10.0, -10.0, -10.0, -10.0), 1e-6)
        assertEquals(100.0, MultiFeatureSimilarity.combine(200.0, 200.0, 200.0, 200.0), 1e-6)
    }

    @Test
    fun `灰度图与彩色图比较时色相项取中性分`() {
        val grayRamp = IntArray(size * size) { index ->
            val level = (index % size) * 255 / (size - 1)
            argb(255, level, level, level)
        }
        val scores = MultiFeatureSimilarity.compare(16, featuresOf(grayRamp), featuresOf(gradient(size)))
        // 灰度图 chromaticPixels = 0，色相项按 50 分中性算，不应该直接判 0
        assertTrue("色相项中性化后直方图分不应过低，实际 ${scores.histogram}", scores.histogram >= 40.0)
    }

    @Test
    fun `画幅不同时长宽比分项被惩罚`() {
        val base = featuresOf(gradient(size))
        // 同一个像素数组，但按 1.6 的画幅比解释（模拟被裁成宽幅）
        val cropped = requireNotNull(
            MultiFeatureSimilarity.featuresFromPixels(size, size, gradient(size), 1.6),
        )
        val scores = MultiFeatureSimilarity.compare(0, base, cropped)
        assertTrue("画幅差异应当被惩罚，实际 ${scores.aspect}", scores.aspect < 40.0)
    }

    @Test
    fun `非法像素数组返回 null`() {
        assertNull(MultiFeatureSimilarity.featuresFromPixels(0, 10, IntArray(0)))
        assertNull(MultiFeatureSimilarity.featuresFromPixels(-1, 10, IntArray(10)))
        assertNull(MultiFeatureSimilarity.featuresFromPixels(10, 10, IntArray(99)))
    }

    // ---- 合成图工具 ----

    private fun featuresOf(pixels: IntArray): ImageFeatures =
        requireNotNull(MultiFeatureSimilarity.featuresFromPixels(size, size, pixels))

    private fun blockAt(hueDegrees: Double): ColorBlock {
        val radians = hueDegrees * PI / 180.0
        return ColorBlock(
            hueX = cos(radians) * 0.8,
            hueY = sin(radians) * 0.8,
            hueWeight = 0.8,
            saturation = 0.8,
            value = 0.8,
            filled = true,
        )
    }

    /** 对角渐变色块：低频、低边缘密度，适合当「同一张图」的底图。 */
    private fun gradient(size: Int): IntArray {
        val pixels = IntArray(size * size)
        val span = (size - 1).coerceAtLeast(1)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val red = x * 255 / span
                val green = y * 255 / span
                val blue = (x + y) * 255 / (2 * span)
                pixels[y * size + x] = argb(255, red, green, blue)
            }
        }
        return pixels
    }

    /** 高对比棋盘格：边缘密度高、几乎无彩色，用来代表「完全不同的图」。 */
    private fun checker(size: Int, cell: Int): IntArray {
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val white = ((x / cell) + (y / cell)) % 2 == 0
                pixels[y * size + x] = if (white) argb(255, 255, 255, 255) else argb(255, 16, 16, 16)
            }
        }
        return pixels
    }

    /** 最近邻缩小：模拟「同一张图被缩放后重新解码」，比双线性更接近实际相册里的缩略图。 */
    private fun downsample(pixels: IntArray, size: Int, factor: Int): IntArray {
        val half = size / factor
        val out = IntArray(half * half)
        for (y in 0 until half) {
            for (x in 0 until half) {
                out[y * half + x] = pixels[(y * factor) * size + x * factor]
            }
        }
        return out
    }

    /** 每个通道加固定偏移并夹到 255：模拟「同一个角色、不同滤镜/白平衡」。 */
    private fun shift(pixel: Int, delta: Int): Int {
        val alpha = (pixel ushr 24) and 0xFF
        val red = (((pixel ushr 16) and 0xFF) + delta).coerceAtMost(255)
        val green = (((pixel ushr 8) and 0xFF) + delta).coerceAtMost(255)
        val blue = ((pixel and 0xFF) + delta).coerceAtMost(255)
        return argb(alpha, red, green, blue)
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
