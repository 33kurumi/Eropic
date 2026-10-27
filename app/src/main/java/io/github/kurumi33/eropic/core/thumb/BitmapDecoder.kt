package io.github.kurumi33.eropic.core.thumb

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.min

/**
 * 统一的原图解码入口：按长边上限解码。
 *
 * 为什么不用 BitmapFactory：Android 10+ 的 ImageDecoder 会自动处理 EXIF 方向，
 * 并且可以在解码阶段直接缩放到目标尺寸，不会先把上亿像素的原图整个读进内存。
 */
object BitmapDecoder {

    suspend fun decode(contentResolver: ContentResolver, uri: Uri, longEdge: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    // 软件位图才能 getPixels；硬件位图在部分 ROM 上读取会抛异常。
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    val w = info.size.width.coerceAtLeast(1)
                    val h = info.size.height.coerceAtLeast(1)
                    // 只缩小不放大：小图保持原尺寸，避免凭空插值。
                    val scale = min(1f, longEdge.toFloat() / maxOf(w, h))
                    if (scale < 1f) {
                        decoder.setTargetSize(
                            (w * scale).toInt().coerceAtLeast(1),
                            (h * scale).toInt().coerceAtLeast(1),
                        )
                    }
                }
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
}
