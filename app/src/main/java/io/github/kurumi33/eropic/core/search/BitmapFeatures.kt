package io.github.kurumi33.eropic.core.search

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 把 Android 的 Bitmap 变成 [ImageFeatures]（多特征画像）的入口。
 *
 * 为什么单独一个文件/对象：评分逻辑（[MultiFeatureSimilarity]）完全不依赖 Android，
 * 纯 JVM 单测才能直接构造像素数组；这里只做「缩放 + getPixels」这点必须依赖 Bitmap 的事，
 * 两边的采样子图长边统一走 [MultiFeatureSimilarity.SAMPLE_MAX_EDGE]，保证同图与候选图比分口径一致。
 */
object BitmapFeatures {

    /**
     * 读取 [bitmap] 的多特征画像；宽高非法时返回 null。
     *
     * 只缩小不放大：图比 64 还小就按原尺寸算，避免插值凭空造出细节。
     * 只回收自己创建出来的缩放副本——调用方的 bitmap 可能还挂在 UI 缩略图上，绝不能 recycle。
     */
    fun extract(bitmap: Bitmap): ImageFeatures? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        // 长宽比用原图尺寸算，缩放的四舍五入不该影响「画幅是否一致」这个分项。
        val aspectRatio = width.toDouble() / height
        val longEdge = max(width, height)
        if (longEdge <= MultiFeatureSimilarity.SAMPLE_MAX_EDGE) {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return MultiFeatureSimilarity.featuresFromPixels(width, height, pixels, aspectRatio)
        }

        val scale = MultiFeatureSimilarity.SAMPLE_MAX_EDGE.toDouble() / longEdge
        val targetWidth = max(1, (width * scale).roundToInt())
        val targetHeight = max(1, (height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        return try {
            val pixels = IntArray(targetWidth * targetHeight)
            scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            MultiFeatureSimilarity.featuresFromPixels(
                targetWidth,
                targetHeight,
                pixels,
                aspectRatio,
            )
        } finally {
            // createScaledBitmap 在尺寸相同时可能直接返回入参，回收前必须判一下。
            if (scaled !== bitmap) scaled.recycle()
        }
    }
}
