package io.github.kurumi33.eropic.core.hash

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.IOException

/** Android 侧：把 MediaStore 图片解码成灰度图（只解目标尺寸，内存可控）。 */
object BitmapGray {

    /**
     * 按长边 [longEdge] 解码；失败返回 null（损坏图、无权限、不支持的格式）。
     * 使用软件解码，避免硬件 Bitmap 无法 getPixels。
     */
    fun decode(contentResolver: ContentResolver, uri: Uri, longEdge: Int = 64): GrayImage? {
        var bitmap: Bitmap? = null
        return try {
            val source = ImageDecoder.createSource(contentResolver, uri)
            val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val w = info.size.width.coerceAtLeast(1)
                val h = info.size.height.coerceAtLeast(1)
                val scale = longEdge.toFloat() / maxOf(w, h)
                val tw = (w * scale).toInt().coerceAtLeast(1)
                val th = (h * scale).toInt().coerceAtLeast(1)
                decoder.setTargetSize(tw, th)
            }
            bitmap = bmp
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return null
            val argb = IntArray(w * h)
            bmp.getPixels(argb, 0, w, 0, 0, w, h)
            GrayImage.fromArgb(argb, w, h)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } finally {
            bitmap?.recycle()
        }
    }
}