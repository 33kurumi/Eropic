package io.github.kurumi33.eropic.core.media

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import io.github.kurumi33.eropic.core.database.entity.VideoEntity
import io.github.kurumi33.eropic.core.index.ScanScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 从 MediaStore 读视频元数据（只读；绝不移动/删除/改名任何文件）。
 *
 * 为什么单独一个类：
 * - 图片索引有 10 万级的体量，视频通常只有几十到几千条，批大小与列集都不同；
 * - 视频多了 DURATION 列，图片那边没有，硬塞进 [MediaStoreDataSource] 会让两边的
 *   projection 变成超集，白白多读列。
 *
 * 其余取舍与图片一致：一次只读关键列、按 DATE_MODIFIED 升序、500 条一批交给调用方，
 * 不把整个 Cursor 一直攥在手里。
 */
class VideoDataSource(private val context: Context) {

    /**
     * @return 是否完整查到结尾。权限被拒/列缺失导致中途失败时返回 false——
     *   调用方据此判断"这一轮没看全"，绝不能把没看到的老行标成缺席。
     */
    suspend fun queryVideos(
        scope: ScanScope = ScanScope.Default,
        onBatch: suspend (List<VideoEntity>) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.VOLUME_NAME,
                MediaStore.Video.Media.RELATIVE_PATH,
            )
            // 扫描范围（隐藏目录 / 排除目录）直接进 selection：查出来再丢会白白多扫一遍。
            val (selection, selectionArgs) = scope.selection(
                "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.IS_TRASHED} = 0"
            )
            val order = "${MediaStore.Video.Media.DATE_MODIFIED} ASC"
            val batch = ArrayList<VideoEntity>(BATCH_SIZE)
            context.contentResolver.query(collection, projection, selection, selectionArgs, order)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val bucketIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val widthIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val takenIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                val modifiedIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val volumeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.VOLUME_NAME)
                val relativeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    // 与图片索引同一套路径策略：DATA 是真实路径、大小写可靠，优先据此反推相对目录；
                    // 个别 ROM 返回 ""，和 null 一样当作"没有 DATA"。
                    val data = if (cursor.isNull(dataIdx)) null else cursor.getString(dataIdx)?.takeIf { it.isNotBlank() }
                    val name = cursor.getString(nameIdx) ?: "VID_$id"
                    val relativeFromData = data?.let { StoragePaths.relativePathOf(it) }
                    val relativePath = relativeFromData ?: PathCase.canonicalize(
                        cursor.getString(relativeIdx) ?: "",
                        StoragePaths.primaryRoot(),
                    )
                    // DATA 为空时若用 File(relativePath, name) 会拼出进程工作目录下的假路径，改用卷根拼接。
                    val path = data ?: File(StoragePaths.primaryRoot(), relativePath + name).absolutePath
                    val modifiedSecs = cursor.getLong(modifiedIdx)
                    val taken = cursor.getLong(takenIdx).takeIf { it > 0 } ?: modifiedSecs * 1000
                    // bucketName 在部分 ROM 上为空，用父目录名兜底，避免视频列表出现"未知"分组。
                    val bucketName = cursor.getString(bucketNameIdx)?.takeIf { it.isNotBlank() }
                        ?: File(path).parentFile?.name
                    batch.add(
                        VideoEntity(
                            id = id,
                            displayName = name,
                            path = path,
                            relativePath = relativePath,
                            sizeBytes = cursor.getLong(sizeIdx),
                            mimeType = cursor.getString(mimeIdx) ?: "video/*",
                            durationMs = cursor.getLong(durationIdx),
                            width = cursor.getInt(widthIdx),
                            height = cursor.getInt(heightIdx),
                            dateTakenMs = taken,
                            dateModifiedMs = modifiedSecs * 1000,
                            bucketId = cursor.getLong(bucketIdIdx),
                            bucketName = bucketName,
                            isPresent = true,
                        ),
                    )
                    if (batch.size >= BATCH_SIZE) {
                        onBatch(ArrayList(batch))
                        batch.clear()
                    }
                }
            }
            if (batch.isNotEmpty()) onBatch(batch)
            true
        } catch (e: SecurityException) {
            // 权限被用户在系统设置里撤掉时这里会抛；视频索引不是核心功能，静默降级即可，
            // 绝不能因为抛异常把 App 拖崩。返回 false 让调用方放弃"标缺席"这一步。
            Log.w(TAG, "查询视频失败（无权限），本轮跳过", e)
            false
        } catch (e: IllegalArgumentException) {
            // 个别 ROM 的 MediaStore 缺少 projection 里的列会抛这个（协议不完整）。
            Log.w(TAG, "查询视频失败（列不可用），本轮跳过", e)
            false
        }
    }

    companion object {
        private const val TAG = "VideoDataSource"
        // 视频行比图片宽（多了时长、通常体积也大），批小一点可以让落库事务更均匀。
        private const val BATCH_SIZE = 500
    }
}
