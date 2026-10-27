package io.github.kurumi33.eropic.core.hash

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 感知哈希（pHash）：32x32 灰度图做 2D DCT-II，取左上 8x8 低频系数，
 * 与中位数比较得到 64 bit。
 */
object PHash {

    private const val N = 32
    private const val LOW = 8

    /**
     * 系数死区：纯色/纯渐变图做 DCT 后理论上 AC 全为 0，但浮点累加会留下 ~1e-14 的噪声，
     * 直接与中位数比较会让这些噪声变成随机 bit，导致同一张纯色图的 pHash 不稳定。
     * 8bit 输入的 AC 系数通常远大于 1e-6，因此该阈值不会影响真实图像。
     */
    private const val EPSILON = 1e-6

    private val cosTable: Array<DoubleArray> = Array(N) { u ->
        DoubleArray(N) { x ->
            cos((2.0 * x + 1.0) * u * PI / (2.0 * N))
        }
    }

    private val alpha: DoubleArray = DoubleArray(N) { u ->
        if (u == 0) sqrt(1.0 / N) else sqrt(2.0 / N)
    }

    fun compute(gray: GrayImage): Long {
        val img = if (gray.width == N && gray.height == N) gray else gray.resize(N, N)
        val dct = dct2d(img)
        val low = DoubleArray(LOW * LOW)
        for (y in 0 until LOW) {
            for (x in 0 until LOW) {
                val v = dct[y * N + x]
                low[y * LOW + x] = if (abs(v) < EPSILON) 0.0 else v
            }
        }
        // 中位数排除 DC，避免整体亮度干扰；DC 位单独按“大于 0”处理
        val acPart = low.copyOfRange(1, low.size)
        val median = acPart.median()
        var bits = 0L
        if (low[0] > 0.0) bits = bits or 1L
        for (i in 1 until low.size) {
            if (low[i] > median) bits = bits or (1L shl i)
        }
        return bits
    }

    private fun dct2d(img: GrayImage): DoubleArray {
        val rows = Array(N) { DoubleArray(N) }
        val tmp = DoubleArray(N)
        for (y in 0 until N) {
            for (x in 0 until N) tmp[x] = img[x, y].toDouble()
            val transformed = dct1d(tmp)
            for (x in 0 until N) rows[y][x] = transformed[x]
        }
        val out = DoubleArray(N * N)
        val colIn = DoubleArray(N)
        for (x in 0 until N) {
            for (y in 0 until N) colIn[y] = rows[y][x]
            val transformed = dct1d(colIn)
            for (y in 0 until N) out[y * N + x] = transformed[y]
        }
        return out
    }

    private fun dct1d(input: DoubleArray): DoubleArray {
        val out = DoubleArray(N)
        for (u in 0 until N) {
            var sum = 0.0
            val c = cosTable[u]
            for (x in 0 until N) sum += input[x] * c[x]
            out[u] = alpha[u] * sum
        }
        return out
    }

    private fun DoubleArray.median(): Double {
        val copy = copyOf()
        copy.sort()
        val n = copy.size
        return if (n % 2 == 1) copy[n / 2] else (copy[n / 2 - 1] + copy[n / 2]) / 2.0
    }
}
