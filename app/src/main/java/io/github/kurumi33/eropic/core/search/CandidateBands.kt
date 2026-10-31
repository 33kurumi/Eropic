package io.github.kurumi33.eropic.core.search

import io.github.kurumi33.eropic.core.hash.Hashing

/**
 * 4x16 bit band 倒排的候选生成工具，供 [ImageSearchRepository] 用 dHash 粗筛候选。
 *
 * 为什么需要它：全表扫描时要为每张图读一整行实体；倒排表 hash_band 只存
 * (mediaId, bandNo, bandVal)，用「源图每个 band 的邻近值」反查候选，
 * 能把候选量从「全库」降到「约 10% 的库」，大图库（10 万+）下差别是秒级。
 *
 * 半径怎么定（同 [io.github.kurumi33.eropic.core.analysis.NearDuplicateGrouper] 的鸽笼思路）：
 * 64 位 dHash 分成 4 个 16 bit 的 band，若两个哈希的汉明距离 <= d，
 * 由鸽笼原理至少有一个 band 的差异 <= d / 4。
 * 所以半径 radius = d / 4 时，任何汉明距离 <= d 的图都必然被某个 band 命中，召回不丢。
 * radius 4 对应每 band 2517 个候选值（sum C(16,i), i<=4），这也是 [MAX_RADIUS] 取 4 的原因：
 * 再大候选值数量指数上涨、SQL 参数会过多，收益却只覆盖更大的距离。
 */
internal object CandidateBands {

    /** 单个 band 最多枚举的翻转位数；4 对应 2517 个候选值。 */
    const val MAX_RADIUS = 4

    /** 预先算好的「翻转掩码」表，按半径分组；neighbors() 只做 xor，避免每次搜索重算组合。 */
    private val offsets: Array<IntArray> = Array(MAX_RADIUS + 1) { radius -> buildOffsets(radius) }

    /** 给定 dHash 距离上限，返回需要的 band 翻转半径；超出覆盖范围时调用方应退回全表扫描。 */
    fun radiusFor(maxDistance: Int): Int =
        (maxDistance.coerceAtLeast(0) / Hashing.BAND_COUNT).coerceIn(0, MAX_RADIUS)

    /** 与 [center] 的 16 bit 汉明距离 <= radius 的所有 band 值（含 center 自身）。 */
    fun neighbors(center: Int, radius: Int): IntArray {
        val table = offsets[radius.coerceIn(0, MAX_RADIUS)]
        return IntArray(table.size) { index -> center xor table[index] }
    }

    private fun buildOffsets(radius: Int): IntArray {
        if (radius <= 0) return intArrayOf(0)
        val result = ArrayList<Int>(2500)
        for (mask in 0 until (1 shl Hashing.BAND_BITS)) {
            if (Integer.bitCount(mask) <= radius) result += mask
        }
        return result.toIntArray()
    }
}
