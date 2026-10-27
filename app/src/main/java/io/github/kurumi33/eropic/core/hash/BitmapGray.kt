package io.github.kurumi33.eropic.core.hash

import android.graphics.Bitmap

/** Android 侧：把解码后的位图转成灰度图；哈希与模糊分都在纯 JVM 里算，方便单测。 */
object BitmapGray {

    /** 失败（宽高非法）返回 null。 */
    fun fromBitmap(bitmap: Bitmap): GrayImage? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        return GrayImage.fromArgb(argb, w, h)
    }
}
