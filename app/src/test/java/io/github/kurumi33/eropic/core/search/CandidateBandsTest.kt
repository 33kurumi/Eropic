package io.github.kurumi33.eropic.core.search

import io.github.kurumi33.eropic.core.hash.Hashing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 倒排候选工具的纯 JVM 单测。
 *
 * 重点验证鸽笼性质：只要 64 位 dHash 的汉明距离 ≤ 16，就一定有某个 16 bit band
 * 落在「半径 = 距离/4」的邻居集合里——这条性质不成立，倒排就会漏召回。
 */
class CandidateBandsTest {

    @Test
    fun `半径对应的候选值数量与组合数一致`() {
        // 1 + C(16,1) + C(16,2) + C(16,3) + C(16,4)
        val expected = intArrayOf(1, 17, 137, 697, 2517)
        for (radius in 0..CandidateBands.MAX_RADIUS) {
            assertEquals("radius=$radius", expected[radius], CandidateBands.neighbors(0x1234, radius).size)
        }
    }

    @Test
    fun `邻居互不重复且汉明距离都不超过半径`() {
        val center = 0xABCD
        for (radius in 0..CandidateBands.MAX_RADIUS) {
            val values = CandidateBands.neighbors(center, radius)
            assertEquals("radius=$radius 应当没有重复值", values.size, values.toSet().size)
            assertTrue(
                "radius=$radius 存在超出半径的值",
                values.all { value -> Integer.bitCount(value xor center) <= radius },
            )
        }
    }

    @Test
    fun `邻居集合总是包含中心值`() {
        for (radius in 0..CandidateBands.MAX_RADIUS) {
            assertTrue(CandidateBands.neighbors(0x0F0F, radius).contains(0x0F0F))
        }
    }

    @Test
    fun `radiusFor 按四分法取整并夹在 0 到 4`() {
        assertEquals(0, CandidateBands.radiusFor(-5))
        assertEquals(0, CandidateBands.radiusFor(0))
        assertEquals(0, CandidateBands.radiusFor(3))
        assertEquals(1, CandidateBands.radiusFor(4))
        assertEquals(1, CandidateBands.radiusFor(7))
        assertEquals(2, CandidateBands.radiusFor(8))
        assertEquals(3, CandidateBands.radiusFor(12))
        assertEquals(4, CandidateBands.radiusFor(16))
        // 超出覆盖范围时调用方会退回全表扫描，这里只保证不会越界
        assertEquals(4, CandidateBands.radiusFor(64))
    }

    @Test
    fun `鸽笼性质_距离不超过 16 时必有 band 落在半径内`() {
        val random = kotlin.random.Random(20261031)
        repeat(300) {
            val base = random.nextLong()
            val flipped = random.nextInt(0, 17)
            var candidate = base
            for (position in (0 until 64).shuffled(random).take(flipped)) {
                candidate = candidate xor (1L shl position)
            }
            val distance = Integer.bitCount((base xor candidate).toInt()) +
                Integer.bitCount((((base xor candidate) ushr 32) and 0xFFFFFFFFL).toInt())
            assertEquals("翻位数应当等于汉明距离", flipped, distance)
            val radius = CandidateBands.radiusFor(distance)
            val hit = (0 until Hashing.BAND_COUNT).any { bandNo ->
                val band = Hashing.band(candidate, bandNo)
                CandidateBands.neighbors(Hashing.band(base, bandNo), radius).contains(band)
            }
            assertTrue("distance=$distance radius=$radius 时倒排应当命中", hit)
        }
    }
}
