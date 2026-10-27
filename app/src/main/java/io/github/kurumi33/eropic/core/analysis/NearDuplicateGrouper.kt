package io.github.kurumi33.eropic.core.analysis

import io.github.kurumi33.eropic.core.hash.Hashing

/** 一条用于近重复比对的哈希记录。 */
data class HashItem(
    val id: Long,
    val dhash: Long,
    val phash: Long,
)

/** 一组近重复图片。maxDistance 为组内 dHash 与首图的最大汉明距离。 */
data class HashGroup(
    val members: List<HashItem>,
    val maxDistance: Int,
)

/**
 * 近重复分组：4×16bit 倒排索引 + 并查集。
 *
 * 复杂度接近 O(n·C)，C ≈ 4×137，而不是 O(n²)。纯 Kotlin，无 Android 依赖，可单测。
 */
object NearDuplicateGrouper {

    const val DEFAULT_DHASH_THRESHOLD = 10
    const val DEFAULT_PHASH_THRESHOLD = 8

    fun group(
        items: List<HashItem>,
        dThreshold: Int = DEFAULT_DHASH_THRESHOLD,
        pThreshold: Int = DEFAULT_PHASH_THRESHOLD,
    ): List<HashGroup> {
        if (items.size < 2) return emptyList()

        // bandNo -> (band value -> 下标列表)
        val bandIndex = Array(Hashing.BAND_COUNT) { HashMap<Int, MutableList<Int>>() }
        items.forEachIndexed { index, item ->
            for (bandNo in 0 until Hashing.BAND_COUNT) {
                val value = Hashing.band(item.dhash, bandNo)
                bandIndex[bandNo].getOrPut(value) { ArrayList(2) }.add(index)
            }
        }

        val parent = IntArray(items.size) { it }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != cur) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        val candidates = HashSet<Int>(512)
        for (i in items.indices) {
            val item = items[i]
            candidates.clear()
            for (bandNo in 0 until Hashing.BAND_COUNT) {
                val bucket = bandIndex[bandNo]
                val base = Hashing.band(item.dhash, bandNo)
                for (neighbor in Hashing.neighbors(base, 2)) {
                    bucket[neighbor]?.let { candidates.addAll(it) }
                }
            }
            for (j in candidates) {
                if (j <= i) continue
                val other = items[j]
                // dHash 必须达标；pHash 作为二次确认，降低误报。
                if (Hashing.hamming64(item.dhash, other.dhash) > dThreshold) continue
                if (Hashing.hamming64(item.phash, other.phash) > pThreshold) continue
                union(i, j)
            }
        }

        val grouped = HashMap<Int, MutableList<Int>>()
        items.indices.forEach { idx -> grouped.getOrPut(find(idx)) { ArrayList(2) }.add(idx) }

        return grouped.values
            .filter { it.size >= 2 }
            .map { indices ->
                val members = indices.map { items[it] }
                var maxDistance = 0
                for (k in 1 until members.size) {
                    val d = Hashing.hamming64(members[0].dhash, members[k].dhash)
                    if (d > maxDistance) maxDistance = d
                }
                HashGroup(members = members, maxDistance = maxDistance)
            }
            .sortedByDescending { it.members.size }
    }
}
