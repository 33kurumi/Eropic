package io.github.kurumi33.eropic.core.hash

/** 64 bit 哈希的通用操作与分段倒排工具。 */
object Hashing {

    const val BAND_COUNT = 4
    const val BAND_BITS = 16
    private const val BAND_MASK = 0xFFFFL

    fun hamming64(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun band(hash: Long, bandNo: Int): Int {
        require(bandNo in 0 until BAND_COUNT) { "bandNo out of range: $bandNo" }
        return ((hash ushr (bandNo * BAND_BITS)) and BAND_MASK).toInt()
    }

    fun bands(hash: Long): IntArray = IntArray(BAND_COUNT) { band(hash, it) }

    /**
     * 找出与 [value] 的 16 bit 汉明距离 <= [radius] 的所有桶值。
     * radius=2 时最大 137 个值（1 + 16 + 120）。
     */
    fun neighbors(value: Int, radius: Int): IntArray {
        require(radius in 0..4)
        val result = ArrayList<Int>(137)
        result.add(value)
        if (radius == 0) return result.toIntArray()
        for (bit in 0 until BAND_BITS) {
            val single = value xor (1 shl bit)
            result.add(single)
            if (radius >= 2) {
                for (bit2 in bit + 1 until BAND_BITS) {
                    result.add(single xor (1 shl bit2))
                }
            }
        }
        return result.toIntArray()
    }
}
