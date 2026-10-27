package io.github.kurumi33.eropic.core.trash

import android.content.Context
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.database.entity.OperationLogEntity
import io.github.kurumi33.eropic.core.database.entity.TrashItemEntity
import io.github.kurumi33.eropic.core.media.MediaScanner
import io.github.kurumi33.eropic.core.media.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * 自建回收站（ADR-0004 / ADR-0012）。
 *
 * 删除 = 同卷 rename 到 /<卷根>/.EropicTrash/，零拷贝、瞬间完成；恢复 = rename 回原路径，
 * 原路径被占用时自动加 " (1)"。默认保留 30 天，过期由 [cleanupExpired] 永久删除。
 * 所有操作都是真实文件操作，不做"只在 App 内隐藏"的假删除。
 */
class TrashRepository(
    private val context: Context,
    private val database: EropicDatabase,
) {

    private val mediaDao = database.mediaDao()
    private val trashDao = database.trashDao()
    private val logDao = database.operationLogDao()

    val items: Flow<List<TrashItemEntity>> = trashDao.observeAll()
    val countFlow: Flow<Int> = trashDao.countFlow()
    val totalBytesFlow: Flow<Long> = trashDao.totalBytesFlow()

    suspend fun count(): Int = trashDao.count()

    suspend fun totalBytes(): Long = trashDao.totalBytes()

    suspend fun moveToTrash(mediaIds: List<Long>): TrashResult = withContext(Dispatchers.IO) {
        if (mediaIds.isEmpty()) return@withContext TrashResult(0, 0, 0L, emptyList(), "没有选中任何图片")
        val now = System.currentTimeMillis()
        val expiresAt = now + RETENTION_MS
        var trashed = 0
        var failed = 0
        var bytes = 0L
        val newIds = mutableListOf<Long>()
        val oldPaths = mutableListOf<String>()

        for (id in mediaIds) {
            val entity = mediaDao.byId(id)
            if (entity == null) {
                failed++
                continue
            }
            val source = File(entity.path)
            if (!source.exists()) {
                // 索引里的路径已经失效：清掉这条索引即可，不算用户可见的失败。
                mediaDao.deleteByIds(listOf(id))
                failed++
                continue
            }
            val root = StoragePaths.volumeRootOf(entity.path)
            val trashDir = StoragePaths.trashDir(root)
            if (!trashDir.exists() && !trashDir.mkdirs()) {
                failed++
                continue
            }
            ensureNoMedia(trashDir)
            val target = uniqueTarget(trashDir, entity.displayName)
            if (!moveFile(source, target)) {
                failed++
                continue
            }
            val trashId = trashDao.insert(
                TrashItemEntity(
                    mediaId = id,
                    displayName = entity.displayName,
                    trashPath = target.absolutePath,
                    originalPath = entity.path,
                    sizeBytes = entity.sizeBytes,
                    mimeType = entity.mimeType,
                    trashedAtMs = now,
                    expiresAtMs = expiresAt,
                ),
            )
            newIds += trashId
            oldPaths += entity.path
            mediaDao.deleteByIds(listOf(id))
            trashed++
            bytes += entity.sizeBytes
        }

        // 让系统相册/MediaStore 感知"原路径已不存在"，避免残留缩略图或幽灵条目。
        MediaScanner.scan(context, oldPaths)
        log(
            type = "TRASH",
            summary = "移入回收站 $trashed 张" + if (failed > 0) "，$failed 张失败" else "",
            payloadJson = JSONArray(newIds).toString(),
        )
        TrashResult(
            trashed = trashed,
            failed = failed,
            bytes = bytes,
            trashIds = newIds,
            message = buildString {
                append("已移入回收站 ").append(trashed).append(" 张")
                if (bytes > 0) append(" · ").append(formatBytes(bytes))
                append(" · ").append(RETENTION_DAYS).append(" 天内可恢复")
                if (failed > 0) append(" · ").append(failed).append(" 张失败")
            },
        )
    }

    suspend fun restore(trashIds: List<Long>): RestoreResult = withContext(Dispatchers.IO) {
        if (trashIds.isEmpty()) return@withContext RestoreResult(0, 0, "没有选中任何条目")
        var restored = 0
        var failed = 0
        val scanPaths = mutableListOf<String>()
        for (row in trashDao.byIds(trashIds)) {
            val source = File(row.trashPath)
            if (!source.exists()) {
                // 文件已经不在了（外部删除），直接把记录清掉。
                trashDao.deleteByIds(listOf(row.id))
                failed++
                continue
            }
            val original = File(row.originalPath)
            original.parentFile?.mkdirs()
            var target = original
            var suffix = 1
            while (target.exists()) {
                target = File(original.parentFile, StoragePaths.uniqueName(original.name, suffix))
                suffix++
            }
            if (moveFile(source, target)) {
                trashDao.deleteByIds(listOf(row.id))
                scanPaths += target.absolutePath
                restored++
            } else {
                failed++
            }
        }
        MediaScanner.scan(context, scanPaths)
        log(
            type = "RESTORE",
            summary = "从回收站恢复 $restored 张" + if (failed > 0) "，$failed 张失败" else "",
            payloadJson = JSONArray(trashIds).toString(),
        )
        RestoreResult(
            restored = restored,
            failed = failed,
            message = buildString {
                append("已恢复 ").append(restored).append(" 张")
                if (failed > 0) append(" · ").append(failed).append(" 张失败")
            },
        )
    }

    /** 永久删除（不可恢复）：只作用于回收站里的文件。 */
    suspend fun deletePermanently(trashIds: List<Long>): DeleteResult = withContext(Dispatchers.IO) {
        var deleted = 0
        var failed = 0
        var bytes = 0L
        for (row in trashDao.byIds(trashIds)) {
            val file = File(row.trashPath)
            val ok = !file.exists() || file.delete()
            if (ok) {
                trashDao.deleteByIds(listOf(row.id))
                deleted++
                bytes += row.sizeBytes
            } else {
                failed++
            }
        }
        log(
            type = "DELETE",
            summary = "永久删除 $deleted 张" + if (failed > 0) "，$failed 张失败" else "",
            payloadJson = JSONArray(trashIds).toString(),
        )
        DeleteResult(
            deleted = deleted,
            failed = failed,
            bytes = bytes,
            message = buildString {
                append("已永久删除 ").append(deleted).append(" 张")
                if (bytes > 0) append(" · ").append(formatBytes(bytes))
                if (failed > 0) append(" · ").append(failed).append(" 张失败")
            },
        )
    }

    /** 清空回收站（全部永久删除）。 */
    suspend fun emptyTrash(): DeleteResult = withContext(Dispatchers.IO) {
        val all = trashDao.all()
        deletePermanently(all.map { it.id })
    }

    /** 启动时/每天调用：删掉超过 30 天的条目，返回清理数量。 */
    suspend fun cleanupExpired(nowMs: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val expired = trashDao.expiredBefore(nowMs)
        if (expired.isEmpty()) return@withContext 0
        var deleted = 0
        for (row in expired) {
            val file = File(row.trashPath)
            if (!file.exists() || file.delete()) {
                trashDao.deleteByIds(listOf(row.id))
                deleted++
            }
        }
        if (deleted > 0) {
            log(
                type = "EMPTY_TRASH",
                summary = "过期自动清理 $deleted 张",
                payloadJson = JSONArray(expired.map { it.id }).toString(),
            )
        }
        deleted
    }

    // ---- 内部工具 ----

    private fun uniqueTarget(dir: File, displayName: String): File {
        val safeName = displayName.ifBlank { "image" }
        var target = File(dir, safeName)
        var suffix = 1
        while (target.exists()) {
            target = File(dir, StoragePaths.uniqueName(safeName, suffix))
            suffix++
        }
        return target
    }

    /** 同卷 rename 零拷贝；跨卷或权限异常时退回"复制 + 删除"。 */
    private fun moveFile(source: File, target: File): Boolean {
        if (source.renameTo(target)) return true
        return runCatching {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (!source.delete()) {
                target.delete()
                false
            } else {
                true
            }
        }.getOrDefault(false)
    }

    private fun ensureNoMedia(dir: File) {
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
    }

    private suspend fun log(type: String, summary: String, payloadJson: String) {
        runCatching {
            logDao.insert(
                OperationLogEntity(
                    type = type,
                    summary = summary,
                    payloadJson = payloadJson,
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    companion object {
        /** 默认保留 30 天。 */
        const val RETENTION_DAYS = 30
        private const val RETENTION_MS = RETENTION_DAYS * 24L * 60L * 60L * 1000L
    }
}

/** 字节数转人类可读（回收站提示用，避免依赖 UI 层工具）。 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    if (gb < 1024) return String.format("%.2f GB", gb)
    return String.format("%.2f TB", gb / 1024.0)
}
