package io.github.kurumi33.eropic.core.thumb

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.media.MediaUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 缩略图磁盘缓存。
 *
 * 为什么需要：图库网格如果直接加载原图（3~10MB），每格都要解出几十 MB 的位图，
 * 十万张照片滚动时会明显掉帧、也容易触发系统内存压力；而 Coil 的磁盘缓存
 * 默认并不缓存 content:// 的本地文件。
 *
 * 做法：把原图按长边 320 解码一次、压成十几 KB 的 WebP 落到 cacheDir/thumbs。
 * 之后网格只读小文件（解码 5~10ms），滚动流畅，也不用再做"后台预加载"。
 *
 * 为什么放 cacheDir 而不是 filesDir：系统空间紧张时可以随时清理，丢了大不了重新生成，
 * 不占用户的图片空间，也不会被系统相册扫到。
 */
class ThumbnailCache(
    context: Context,
    private val contentResolver: ContentResolver,
) {

    private val rootDir = File(context.cacheDir, CACHE_DIR_NAME)

    /** 由设置订阅方写入：关闭缓存时不再写盘，网格直接用原图兜底。 */
    @Volatile
    private var enabled: Boolean = true

    @Volatile
    private var limitBytes: Long = DEFAULT_LIMIT_BYTES

    /** 同一个 key 只允许一个协程生成，避免网格快速滚动时同一张图被重复解码。 */
    private val inFlight = ConcurrentHashMap<String, Mutex>()

    fun configure(enabled: Boolean, limitBytes: Long) {
        this.enabled = enabled
        this.limitBytes = limitBytes.coerceAtLeast(0L)
    }

    fun isEnabled(): Boolean = enabled

    /** 当前配置的字节上限；设置页/启动自检用来判断"上限是否被调小"。 */
    fun limitBytes(): Long = limitBytes

    /** 缓存文件名里带上 size 与修改时间：图片被编辑/替换后自然失效，不需要额外的失效逻辑。 */
    fun fileFor(item: MediaEntity): File {
        val shard = String.format(Locale.US, "%02x", item.id % SHARD_COUNT)
        return File(File(rootDir, shard), keyOf(item) + ".webp")
    }

    fun existing(item: MediaEntity): File? {
        if (!enabled) return null
        val file = fileFor(item)
        return if (file.isFile && file.length() > 0L) file else null
    }

    /**
     * 读取或生成缩略图。返回 null 表示缓存关闭或这张图无法解码，调用方用原图兜底。
     */
    suspend fun get(item: MediaEntity): File? {
        if (!enabled) return null
        existing(item)?.let { return it }
        val key = keyOf(item)
        val mutex = inFlight.getOrPut(key) { Mutex() }
        try {
            return mutex.withLock { existing(item) ?: generate(item) }
        } finally {
            inFlight.remove(key, mutex)
        }
    }

    /**
     * 索引阶段已经为算指纹解码过一次位图，这里直接复用，不再解第二次。
     * 注意：调用方负责 recycle 位图。
     */
    suspend fun write(item: MediaEntity, bitmap: Bitmap): File? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        val target = fileFor(item)
        val temp = File(target.parentFile, target.name + ".tmp")
        try {
            temp.parentFile?.mkdirs()
            FileOutputStream(temp).use { out ->
                // 画质 80 的 WebP，320px 下通常 10~25KB，细节对网格足够。
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target
        } catch (e: IOException) {
            temp.delete()
            null
        } catch (e: SecurityException) {
            temp.delete()
            null
        }
    }

    private suspend fun generate(item: MediaEntity): File? {
        val bitmap = BitmapDecoder.decode(contentResolver, MediaUris.of(item.id), THUMB_LONG_EDGE) ?: return null
        return try {
            write(item, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        if (!rootDir.exists()) return@withContext 0L
        var total = 0L
        rootDir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        total
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        rootDir.deleteRecursively()
    }

    /**
     * 超出上限时按"最后修改时间"从旧到新删除（约等于生成时间）。
     * 这里扫一遍目录是有意的：十万个文件大约几百毫秒，只在索引结束/上限调小时做一次，
     * 比每次读缩略图都更新访问时间的写放大更划算。
     */
    suspend fun trim(maxBytes: Long = limitBytes) = withContext(Dispatchers.IO) {
        if (maxBytes <= 0L) {
            clear()
            return@withContext
        }
        if (!rootDir.exists()) return@withContext
        val files = rootDir.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return@withContext
        // 一次多删一点，避免刚删完马上又超限。
        val target = (maxBytes * 0.8).toLong()
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= target) return@forEach
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun keyOf(item: MediaEntity): String =
        "v" + CACHE_VERSION + "-" + item.id + "-" + item.sizeBytes + "-" + item.dateModifiedMs

    companion object {
        /** 网格在 3~6 列、2K 屏下最大单格约 480px；320 兼顾清晰度与体积。 */
        const val THUMB_LONG_EDGE = 320

        private const val QUALITY = 80
        private const val CACHE_VERSION = 1
        private const val SHARD_COUNT = 256
        private const val CACHE_DIR_NAME = "thumbs"
        const val DEFAULT_LIMIT_BYTES = 1024L * 1024L * 1024L
    }
}
