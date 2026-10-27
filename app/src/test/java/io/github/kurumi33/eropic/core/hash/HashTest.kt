package io.github.kurumi33.eropic.core.hash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashTest {

    private fun gradient(width: Int, height: Int): GrayImage =
        GrayImage.generate(width, height) { x, y -> (x * 7 + y * 13).toFloat() }

    @Test
    fun dhashIsStableForSameImage() {
        val a = gradient(64, 48)
        val b = gradient(64, 48)
        val ha = DHash.compute(a)
        val hb = DHash.compute(b)
        assertEquals(ha, hb)
        assertEquals(0, Hashing.hamming64(ha, hb))
    }

    @Test
    fun dhashSetsAllBitsWhenEveryLeftPixelIsBrighter() {
        val img = GrayImage.generate(9, 8) { x, _ -> (9 - x).toFloat() }
        assertEquals(-1L, DHash.compute(img))
    }

    @Test
    fun dhashSetsNoBitWhenEveryLeftPixelIsDarker() {
        val img = GrayImage.generate(9, 8) { x, _ -> x.toFloat() }
        assertEquals(0L, DHash.compute(img))
    }

    @Test
    fun dhashOfFlippedImageDiffers() {
        val original = gradient(9, 8)
        val flipped = GrayImage.generate(9, 8) { x, _ -> original[8 - x, 0] }
        val hamming = Hashing.hamming64(DHash.compute(original), DHash.compute(flipped))
        assertTrue("flipped image should differ significantly, got $hamming", hamming > 10)
    }

    @Test
    fun phashOfFlatImageIsStable() {
        val flat = GrayImage.generate(32, 32) { _, _ -> 128f }
        val hash = PHash.compute(flat)
        // 只有 DC 位，其余 AC 系数相同（中位数比较不成立）
        assertEquals(1L, hash)
    }

    @Test
    fun phashIsStableForSameImage() {
        val a = gradient(40, 30)
        val b = gradient(40, 30)
        assertEquals(0, Hashing.hamming64(PHash.compute(a), PHash.compute(b)))
    }

    @Test
    fun laplacianIsZeroOnFlatImageAndPositiveOnEdges() {
        val flat = GrayImage.generate(32, 32) { _, _ -> 100f }
        assertEquals(0.0, Laplacian.variance(flat), 1e-6)
        val edges = GrayImage.generate(32, 32) { x, _ -> if (x % 2 == 0) 0f else 255f }
        assertTrue(Laplacian.variance(edges) > 100.0)
    }

    @Test
    fun hammingCountsDifferingBits() {
        assertEquals(0, Hashing.hamming64(0L, 0L))
        assertEquals(1, Hashing.hamming64(0L, 1L))
        assertEquals(64, Hashing.hamming64(0L, -1L))
    }

    @Test
    fun bandsCoverAll64BitsAndNeighborsHaveExpectedSize() {
        val hash = 0x0123_4567_89AB_CDEFL
        val bands = Hashing.bands(hash)
        assertEquals(Hashing.BAND_COUNT, bands.size)
        for (i in bands.indices) {
            assertEquals(Hashing.band(hash, i), bands[i])
        }
        val value = bands[0]
        assertEquals(1, Hashing.neighbors(value, 0).size)
        assertEquals(17, Hashing.neighbors(value, 1).size)
        assertEquals(137, Hashing.neighbors(value, 2).size)
        // 所有邻居与自身距离 <= radius
        for (neighbor in Hashing.neighbors(value, 2)) {
            val distance = Integer.bitCount(value xor neighbor)
            assertTrue(distance <= 2)
        }
    }
}
