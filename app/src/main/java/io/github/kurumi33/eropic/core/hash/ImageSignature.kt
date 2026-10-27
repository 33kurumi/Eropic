package io.github.kurumi33.eropic.core.hash

import android.graphics.Bitmap
import kotlin.math.roundToInt

data class Signature(
    val dhash: Long,
    val phash: Long,
    val blurScore: Double,
)

/**
 * 从解码后的位图产出一份指纹：dHash + pHash + 模糊分。
 *
 * M1.5 起解码入口统一成长边 320（同一次解码顺带生成缩略图），
 * 这里再归一化到 64 长边灰度图，保证哈希和模糊分的尺度与旧版本一致
 * （blurScore 的 100 阈值是在 64 长边上标定的，不能直接用 320 的方差）。
 */
object ImageSignature {

    /** 解码长边：兼顾缩略图清晰度与内存占用。 */
    const val DECODE_LONG_EDGE = 320

    /** 算指纹用的灰度图长边。 */
    private const val HASH_LONG_EDGE = 64

    fun compute(bitmap: Bitmap): Signature? {
        val source = BitmapGray.fromBitmap(bitmap) ?: return null
        val gray = normalize(source)
        val dhash = DHash.compute(gray.resize(9, 8))
        val phash = PHash.compute(gray.resize(32, 32))
        val blur = Laplacian.variance(gray)
        return Signature(dhash = dhash, phash = phash, blurScore = blur)
    }

    private fun normalize(gray: GrayImage): GrayImage {
        val longest = maxOf(gray.width, gray.height)
        if (longest == HASH_LONG_EDGE) return gray
        val scale = HASH_LONG_EDGE.toFloat() / longest
        return gray.resize(
            (gray.width * scale).roundToInt().coerceAtLeast(1),
            (gray.height * scale).roundToInt().coerceAtLeast(1),
        )
    }
}
