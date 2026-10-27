package io.github.kurumi33.eropic.core.trash

import android.content.Context
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.database.entity.OperationLogEntity
import io.github.kurumi33.eropic.core.database.entity.TrashItemEntity
import io.github.kurumi33.eropic.core.fileops.OpFailure
import io.github.kurumi33.eropic.core.fileops.OpItem
import io.github.kurumi33.eropic.core.fileops.OperationPayload
import io.github.kurumi33.eropic.core.media.MediaScanner
import io.github.kurumi33.eropic.core.media.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自建回收站（ADR-0004 / ADR-0012）。
 *
 * 删除 = 同卷 rename 到 /<卷根>/.EropicTrash/，零拷贝、瞬间完成；恢复 = rename 回原路径，
 * 原路径被占用时自动加 " (1)"。保留期由用户设置（7/30/60 天或永久），过期由 [cleanupExpired] 永久删除。
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

    /**
     * 把索引里的图片移入回收站。
     *
     * [retentionDays] <= 0 表示永久保留（expiresAtMs 设为 [Long.MAX_VALUE]，不会被清理任务碰到）。
     */
    suspend fun moveToTrash(
        mediaIds: List<Long>,
        retentionDays: Int = RETENTION_DAYS,
    ): TrashResult = withContext(Dispatchers.IO) {
        if (mediaIds.isEmpty()) {
            return@withContext TrashResult(0, 0, 0L, emptyList(), "没有选中任何图片")
        }
        val now = System.currentTimeMillis()
        val expiresAt = expiryOf(now, retentionDays)
        var trashed = 0
        var failed = 0
        var bytes = 0L
        val newIds = mutableListOf<Long>()
        val oldPaths = mutableListOf<String>()
        val items = mutableListOf<OpItem>()
        val failures = mutableListOf<OpFailure>()

        for (id in mediaIds) {
            val entity = mediaDao.byId(id)
            if (entity == null) {
                failed++
                failures += OpFailure("#$id", "索引里已不存在")
                continue
            }
            val source = File(entity.path)
            if (!source.exists()) {
                // 索引里的路径已经失效：清掉这条索引即可，不算用户可见的失败。
                mediaDao.deleteByIds(listOf(id))
                failed++
                failures += OpFailure(entity.displayName, "文件已不存在")
                continue
            }
            val root = StoragePaths.volumeRootOf(entity.path)
            val trashDir = StoragePaths.trashDir(root)
            if (!trashDir.exists() && !trashDir.mkdirs()) {
                failed++
                failures += OpFailure(entity.displayName, "无法创建回收站目录")
                continue
            }
            ensureNoMedia(trashDir)
            val target = uniqueTarget(trashDir, entity.displayName)
            if (!moveFile(source, target)) {
                failed++
                failures += OpFailure(entity.displayName, "移动文件失败")
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
            items += OpItem(
                id = trashId,
                name = entity.displayName,
                from = entity.path,
                to = target.absolutePath,
            )
            mediaDao.deleteByIds(listOf(id))
            trashed++
            bytes += entity.sizeBytes
        }

        // 让系统相册/MediaStore 感知"原路径已不存在"，避免残留缩略图或幽灵条目。
        MediaScanner.scan(context, oldPaths)
        log(
            type = "TRASH",
            summary = "移入回收站 $trashed 张" + if (failed > 0) "，$failed 张失败" else "",
            payloadJson = OperationPayload.encode(items),
        )
        TrashResult(
            trashed = trashed,
            failed = failed,
            bytes = bytes,
            trashIds = newIds,
            message = buildString {
                append("已移入回收站 ").append(trashed).append(" 张")
                if (bytes > 0) append(" · ").append(formatBytes(bytes))
                if (retentionDays <= 0) append(" · 永久保留（不会自动清理）")
                else append(" · ").append(retentionDays).append(" 天内可恢复")
                if (failed > 0) append(" · ").append(failed).append(" 张失败")
            },
            failures = failures,
        )
    }

    /**
     * 把指定路径的文件移入回收站（不经过索引）。
     *
     * 用途：撤销"复制"操作时要清掉复制出来的新文件——它们刚生成，还没进索引。
     */
    suspend fun trashFiles(
        refs: List<TrashFileRef>,
        retentionDays: Int = RETENTION_DAYS,
    ): TrashResult = withContext(Dispatchers.IO) {
        if (refs.isEmpty()) return@withContext TrashResult(0, 0, 0L, emptyList(), "没有需要清理的文件")
        val now = System.currentTimeMillis()
        val expiresAt = expiryOf(now, retentionDays)
        var trashed = 0
        var failed = 0
        var bytes = 0L
        val newIds = mutableListOf<Long>()
        val oldPaths = mutableListOf<String>()
        val items = mutableListOf<OpItem>()
        val failures = mutableListOf<OpFailure>()

        for (ref in refs) {
            val source = File(ref.path)
            if (!source.exists()) {
                failed++
                failures += OpFailure(ref.displayName, "文件已不存在")
                continue
            }
            val root = StoragePaths.volumeRootOf(ref.path)
            val trashDir = StoragePaths.trashDir(root)
            if (!trashDir.exists() && !trashDir.mkdirs()) {
                failed++
                failures += OpFailure(ref.displayName, "无法创建回收站目录")
                continue
            }
            ensureNoMedia(trashDir)
            val target = uniqueTarget(trashDir, ref.displayName)
            if (!moveFile(source, target)) {
                failed++
                failures += OpFailure(ref.displayName, "移动文件失败")
                continue
            }
            val size = if (ref.sizeBytes > 0) ref.sizeBytes else target.length()
            val trashId = trashDao.insert(
                TrashItemEntity(
                    mediaId = 0,
                    displayName = ref.displayName,
                    trashPath = target.absolutePath,
                    originalPath = ref.path,
                    sizeBytes = size,
                    mimeType = ref.mimeType,
                    trashedAtMs = now,
                    expiresAtMs = expiresAt,
                ),
            )
            newIds += trashId
            oldPaths += ref.path
            items += OpItem(id = trashId, name = ref.displayName, from = ref.path, to = target.absolutePath)
            trashed++
            bytes += size
        }
        MediaScanner.scan(context, oldPaths)
        if (trashed > 0) {
            log(
                type = "TRASH",
                summary = "移入回收站 $trashed 张",
                payloadJson = OperationPayload.encode(items),
            )
        }
        TrashResult(
            trashed = trashed,
            failed = failed,
            bytes = bytes,
            trashIds = newIds,
            message = "已移入回收站 $trashed 张",
            failures = failures,
        )
    }

    suspend fun restore(trashIds: List<Long>): RestoreResult = withContext(Dispatchers.IO) {
        if (trashIds.isEmpty()) return@withContext RestoreResult(0, 0, "没有选中任何条目")
        var restored = 0
        var failed = 0
        val scanPaths = mutableListOf<String>()
        val items = mutableListOf<OpItem>()
        val failures = mutableListOf<OpFailure>()
        for (row in trashDao.byIds(trashIds)) {
            val source = File(row.trashPath)
            if (!source.exists()) {
                // 文件已经不在了（外部删除），直接把记录清掉。
                trashDao.deleteByIds(listOf(row.id))
                failed++
                failures += OpFailure(row.displayName, "回收站里的文件已不存在")
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
                items += OpItem(id = row.id, name = row.displayName, from = row.trashPath, to = target.absolutePath)
                restored++
            } else {
                failed++
                failures += OpFailure(row.displayName, "恢复失败（目标目录可能不可写）")
            }
        }
        MediaScanner.scan(context, scanPaths)
        log(
            type = "RESTORE",
            summary = "从回收站恢复 $restored 张" + if (failed > 0) "，$failed 张失败" else "",
            payloadJson = OperationPayload.encode(items),
        )
        RestoreResult(
            restored = restored,
            failed = failed,
            message = buildString {
                append("已恢复 ").append(restored).append(" 张")
                if (failed > 0) append(" · ").append(failed).append(" 张失败")
            },
            failures = failures,
        )
    }

    /** 永久删除（不可恢复）：只作用于回收站里的文件。 */
    suspend fun deletePermanently(trashIds: List<Long>): DeleteResult = withContext(Dispatchers.IO) {
        var deleted = 0
        var failed = 0
        var bytes = 0L
        val failures = mutableListOf<OpFailure>()
        for (row in trashDao.byIds(trashIds)) {
            val file = File(row.trashPath)
            val ok = !file.exists() || file.delete()
            if (ok) {
                trashDao.deleteByIds(listOf(row.id))
                deleted++
                bytes += row.sizeBytes
            } else {
                failed++
                failures += OpFailure(row.displayName, "删除失败（文件被占用？）")
            }
        }
        log(
            type = "DELETE",
            summary = "永久删除 $deleted 张" + if (failed > 0) "，$failed 张失败" else "",
            payloadJson = OperationPayload.encode(trashIds.map { OpItem(it, "") }),
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
            failures = failures,
        )
    }

    /** 清空回收站（全部永久删除）。 */
    suspend fun emptyTrash(): DeleteResult = withContext(Dispatchers.IO) {
        val all = trashDao.all()
        deletePermanently(all.map { it.id })
    }

    /** 启动时/周期性调用：删掉超过保留期的条目，返回清理数量。 */
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
                payloadJson = OperationPayload.encode(expired.map { OpItem(it.id, it.displayName) }),
            )
        }
        deleted
    }

    /** 未来 [withinMs] 内会到期的条目（M4 到期提醒用）。 */
    suspend fun expiringSoon(withinMs: Long = 3L * 24 * 60 * 60 * 1000): List<TrashItemEntity> {
        val now = System.currentTimeMillis()
        return withContext(Dispatchers.IO) { trashDao.expiringBetween(now, now + withinMs) }
    }

    // ---- 内部工具 ----

    private fun expiryOf(nowMs: Long, retentionDays: Int): Long =
        if (retentionDays <= 0) Long.MAX_VALUE else nowMs + retentionDays * 24L * 60L * 60L * 1000L

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
        /** 默认保留 30 天（用户可在设置里改，对应 F-04）。 */
        const val RETENTION_DAYS = 30
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
