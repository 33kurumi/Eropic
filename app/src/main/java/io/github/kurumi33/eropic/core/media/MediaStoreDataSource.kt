package io.github.kurumi33.eropic.core.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.index.ScanScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 唯一的数据入口：从 MediaStore 读图片元数据。
 *
 * 关键点：
 * - 一次查询只读关键列，缩略图/EXIF 后续按需；
 * - 1000 条一批交给调用方落库，不把 10 万条 Cursor 一直攥在手里；
 * - 只读 IS_PENDING=0 / IS_TRASHED=0 的条目。
 */
class MediaStoreDataSource(private val context: Context) {

    suspend fun queryImages(
        scope: ScanScope = ScanScope.Default,
        onBatch: suspend (List<MediaEntity>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.IS_FAVORITE,
            MediaStore.Images.Media.VOLUME_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        // 扫描范围（隐藏目录 / 排除目录）直接进 selection：先全查再过滤会让 10 万级的扫描慢一倍
        val (selection, selectionArgs) = scope.selection(
            "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.IS_TRASHED} = 0"
        )
        val order = "${MediaStore.Images.Media.DATE_MODIFIED} ASC"
        val batch = ArrayList<MediaEntity>(BATCH_SIZE)
        context.contentResolver.query(collection, projection, selection, selectionArgs, order)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val bucketIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val widthIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val takenIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val modifiedIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val addedIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val orientationIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
            val favoriteIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_FAVORITE)
            val volumeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.VOLUME_NAME)
            val relativeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                // 空字符串与 null 一样视为"没有 DATA"：个别 ROM 会返回 ""，直接当路径用会写出假行。
                val data = if (cursor.isNull(dataIdx)) null else cursor.getString(dataIdx)?.takeIf { it.isNotBlank() }
                val name = cursor.getString(nameIdx) ?: "IMG_$id"
                // DATA 是文件系统里的真实路径，大小写可靠，优先用它反推相对目录；
                // 只有 DATA 为空时才回落到 RELATIVE_PATH，并按卷根下的真实目录名修正顶层大小写。
                val relativeFromData = data?.let { StoragePaths.relativePathOf(it) }
                val relativePath = relativeFromData ?: PathCase.canonicalize(
                    cursor.getString(relativeIdx) ?: "",
                    StoragePaths.primaryRoot(),
                )
                // DATA 为空时用 File(relativePath, name) 会拼出进程工作目录下的假路径，改用卷根拼接。
                val path = data ?: File(StoragePaths.primaryRoot(), relativePath + name).absolutePath
                val modifiedSecs = cursor.getLong(modifiedIdx)
                val taken = cursor.getLong(takenIdx).takeIf { it > 0 } ?: modifiedSecs * 1000
                val bucketName = cursor.getString(bucketNameIdx)
                    ?: File(path).parentFile?.name
                    ?: "未知相册"
                batch.add(
                    MediaEntity(
                        id = id,
                        volumeName = cursor.getString(volumeIdx) ?: "external_primary",
                        path = path,
                        displayName = name,
                        relativePath = relativePath,
                        bucketId = cursor.getLong(bucketIdIdx),
                        bucketName = bucketName,
                        mimeType = cursor.getString(mimeIdx) ?: "image/*",
                        sizeBytes = cursor.getLong(sizeIdx),
                        width = cursor.getInt(widthIdx),
                        height = cursor.getInt(heightIdx),
                        dateTakenMs = taken,
                        dateModifiedMs = modifiedSecs * 1000,
                        dateAddedSecs = cursor.getLong(addedIdx),
                        orientation = cursor.getInt(orientationIdx),
                        isFavorite = cursor.getInt(favoriteIdx) == 1,
                        isPresent = true,
                        indexedAtMs = System.currentTimeMillis(),
                    ),
                )
                if (batch.size >= BATCH_SIZE) {
                    onBatch(ArrayList(batch))
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) onBatch(batch)
    }

    companion object {
        private const val BATCH_SIZE = 1000
    }
}