package io.github.kurumi33.eropic.core.hash

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** 与 Android 无关的灰度图，方便 JVM 单元测试。 */
class GrayImage(
    val width: Int,
    val height: Int,
    val pixels: FloatArray,
) {
    init {
        require(width > 0 && height > 0) { "width/height must be > 0" }
        require(pixels.size == width * height) { "pixels.size != width*height" }
    }

    operator fun get(x: Int, y: Int): Float = pixels[y * width + x]

    /** 双线性缩放，避免最近邻带来的锯齿干扰哈希。 */
    fun resize(newWidth: Int, newHeight: Int): GrayImage {
        require(newWidth > 0 && newHeight > 0)
        if (newWidth == width && newHeight == height) return this
        val out = FloatArray(newWidth * newHeight)
        val xRatio = if (newWidth > 1) (width - 1).toFloat() / (newWidth - 1) else 0f
        val yRatio = if (newHeight > 1) (height - 1).toFloat() / (newHeight - 1) else 0f
        for (y in 0 until newHeight) {
            val sy = y * yRatio
            val y0 = floor(sy).toInt().coerceIn(0, height - 1)
            val y1 = min(y0 + 1, height - 1)
            val wy = sy - y0
            for (x in 0 until newWidth) {
                val sx = x * xRatio
                val x0 = floor(sx).toInt().coerceIn(0, width - 1)
                val x1 = min(x0 + 1, width - 1)
                val wx = sx - x0
                val top = get(x0, y0) * (1 - wx) + get(x1, y0) * wx
                val bottom = get(x0, y1) * (1 - wx) + get(x1, y1) * wx
                out[y * newWidth + x] = top * (1 - wy) + bottom * wy
            }
        }
        return GrayImage(newWidth, newHeight, out)
    }

    companion object {
        /** ARGB_8888 像素数组转灰度（Rec.601 亮度）。 */
        fun fromArgb(argb: IntArray, width: Int, height: Int): GrayImage {
            require(argb.size == width * height)
            val out = FloatArray(argb.size)
            for (i in argb.indices) {
                val c = argb[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }
            return GrayImage(width, height, out)
        }

        /** 测试用：按函数生成灰度图。 */
        fun generate(width: Int, height: Int, fn: (x: Int, y: Int) -> Float): GrayImage {
            val out = FloatArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[y * width + x] = fn(x, y)
                }
            }
            return GrayImage(width, height, out)
        }
    }
}

internal fun FloatArray.median(): Float {
    val copy = copyOf()
    copy.sort()
    val n = copy.size
    return if (n % 2 == 1) copy[n / 2] else (copy[n / 2 - 1] + copy[n / 2]) / 2f
}

internal fun distanceSq(x0: Int, y0: Int, x1: Int, y1: Int): Int = (x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0)
