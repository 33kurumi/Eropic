package io.github.kurumi33.eropic.core.hash

/** 拉普拉斯方差：衡量清晰度，值越小越糊。 */
object Laplacian {

    fun variance(gray: GrayImage): Double {
        if (gray.width < 3 || gray.height < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until gray.height - 1) {
            for (x in 1 until gray.width - 1) {
                val v = gray[x - 1, y] + gray[x + 1, y] + gray[x, y - 1] + gray[x, y + 1] - 4f * gray[x, y]
                sum += v
                sumSq += v * v
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }
}
