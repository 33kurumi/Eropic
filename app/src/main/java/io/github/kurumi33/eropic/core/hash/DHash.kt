package io.github.kurumi33.eropic.core.hash

/**
 * 差值哈希（dHash）：缩放到 9x8 灰度图后，比较水平相邻像素。
 * 64 bit，位序：y*8+x，左亮右暗记 1。
 */
object DHash {

    const val BIT_COUNT = 64

    fun compute(gray: GrayImage): Long {
        val img = if (gray.width == 9 && gray.height == 8) gray else gray.resize(9, 8)
        var bits = 0L
        var index = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (img[x, y] > img[x + 1, y]) {
                    bits = bits or (1L shl index)
                }
                index++
            }
        }
        return bits
    }
}
