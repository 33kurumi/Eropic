package io.github.kurumi33.eropic.core.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Size
import androidx.room.withTransaction
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.database.entity.VideoEntity
import io.github.kurumi33.eropic.core.index.ScanScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 视频索引的读写仓库。
 *
 * 只做识别与统计：视频不会被移动、改名或删除，索引里记录的 path/relativePath
 * 只是用来在"视频"虚拟文件夹里按目录聚合展示。
 *
 * 与图片仓库的差异：视频量小（通常几十到几千条），因此对账可以"整批 upsert"，
 * 不需要像图片那样逐行比对 MediaStore 字段；换来的是实现简单、不会漏掉元数据变化。
 */
class VideoRepository(
    private val context: Context,
    private val database: EropicDatabase,
) {
    private val videoDao = database.videoDao()
    private val dataSource = VideoDataSource(context)

    /** 单飞：一次性任务、周期任务、手动刷新可能同时进来，串行化避免互相覆盖 seen 集合。 */
    private val scanMutex = Mutex()

    suspend fun syncFromMediaStore(
        scope: ScanScope = ScanScope.Default,
        onProgress: suspend (scanned: Int, changed: Int) -> Unit = { _, _ -> },
    ): VideoSyncResult = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            var scanned = 0
            var changed = 0
            // 视频量小，预先占位即可；这里只需要"这一轮看到过哪些 id"。
            val seenIds = HashSet<Long>(256)
            // completed=false 表示查询中途被权限/列问题打断，此时 seenIds 只覆盖了前几批，
            // 后面的老行其实还在，绝不能当成"已删除"。
            val completed = dataSource.queryVideos(scope) { batch ->
                // 每批一个事务：中途被取消/进程被杀时，已落库的批次完整可见，不会出现半批数据。
                database.withTransaction { videoDao.upsertAll(batch) }
                scanned += batch.size
                // 视频量小且 upsert 是幂等的，直接按"整批都算变化"上报，省一次全表 id 查询。
                changed += batch.size
                batch.forEach { seenIds += it.id }
                onProgress(scanned, changed)
            }
            // 三个条件都满足才允许标缺席：
            // 1) 至少看到过一个视频（scanned == 0 多半是没权限，清空会把视频页整个变空）；
            // 2) 查询完整跑完（中途抛异常时 seenIds 不完整）；
            // 3) 拿到的是"读取全部视频"权限（Android 14 的"仅选中的视频"只会返回部分行）。
            if (scanned > 0 && completed && hasFullVideoAccess()) {
                database.withTransaction {
                    videoDao.markAllAbsent()
                    seenIds.chunked(500).forEach { videoDao.markPresent(it) }
                }
            }
            VideoSyncResult(
                scanned = scanned,
                changed = changed,
                totalIndexed = videoDao.countPresent(),
            )
        }
    }

    suspend fun count(): Int = videoDao.countPresent()

    suspend fun totalBytes(): Long = videoDao.totalBytes()

    /** 视频列表（DAO 侧按拍摄时间倒序）；默认给一个足够覆盖常见设备的上限，避免一次读太多进内存。 */
    suspend fun list(limit: Int = 5000): List<VideoEntity> = videoDao.list(limit)

    /**
     * 视频缩略图。失败一律返回 null 而不是抛：
     * 视频编码千奇百怪，个别文件解不出来是常态，UI 侧显示占位图即可。
     */
    suspend fun thumbnail(id: Long): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.loadThumbnail(
                MediaUris.ofVideo(id),
                Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE),
                null,
            )
        }.getOrNull()
    }

    fun countFlow(): Flow<Int> = videoDao.countFlow()

    fun totalBytesFlow(): Flow<Long> = videoDao.totalBytesFlow()

    suspend fun clear() = videoDao.clear()

    /**
     * 是否拿到了"读取全部视频"的权限。
     *
     * Android 13+ 用 READ_MEDIA_VIDEO；Android 12 及以下只有 READ_EXTERNAL_STORAGE。
     * 只有全量权限才允许把没看到的旧行标缺席，否则部分授权会把视频页清空。
     */
    private fun hasFullVideoAccess(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        // minSdk 31，Context.checkSelfPermission 本身就有（ContextCompat 在 API>=23 时也是直接转调它）。
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        // 视频列表是 3 列网格，320px 足够；再大只会多占内存、拖慢滚动。
        private const val THUMBNAIL_SIZE = 320
    }
}

/** 一轮视频对账的结果。[totalIndexed] 是在场（isPresent = 1）的视频条数。 */
data class VideoSyncResult(
    val scanned: Int,
    val changed: Int,
    val totalIndexed: Int,
)
