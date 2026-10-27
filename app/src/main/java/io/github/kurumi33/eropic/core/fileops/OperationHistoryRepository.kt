package io.github.kurumi33.eropic.core.fileops

import android.content.Context
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.database.entity.OperationLogEntity
import io.github.kurumi33.eropic.core.media.MediaScanner
import io.github.kurumi33.eropic.core.media.StoragePaths
import io.github.kurumi33.eropic.core.trash.TrashFileRef
import io.github.kurumi33.eropic.core.trash.TrashRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/** 操作历史的一行（对应规划 C-08 的列表）。 */
data class OperationRecord(
    val id: Long,
    val type: String,
    /** 中文类型名，例如「移入回收站」 */
    val title: String,
    val summary: String,
    val createdAtMs: Long,
    val undone: Boolean,
    val canUndo: Boolean,
    /** 不能撤销时给用户的原因 */
    val undoHint: String,
    val itemCount: Int,
)

/**
 * 操作历史与撤销（对应规划 C-07 / C-08）。
 *
 * "真正能撤销"的前提是日志里存了 from/to（[OperationPayload] v2）：
 * - MOVE / RENAME：把文件搬回 from；
 * - COPY：把复制出来的新文件移入回收站；
 * - TRASH：按回收站 id 恢复；
 * - DELETE / EMPTY_TRASH / RESTORE：不可撤销（文件已不存在或已经恢复）。
 *
 * M3 时期的老日志只有 id 数组，无法逐项搬回，这里会明确告知"记录太旧，无法撤销"。
 */
class OperationHistoryRepository(
    private val context: Context,
    private val database: EropicDatabase,
    private val trashRepository: TrashRepository,
) {

    private val mediaDao = database.mediaDao()
    private val logDao = database.operationLogDao()

    val records: Flow<List<OperationRecord>> =
        logDao.observeRecent(HISTORY_LIMIT).map { list -> list.map { it.toRecord() } }

    suspend fun recent(limit: Int = HISTORY_LIMIT): List<OperationRecord> =
        withContext(Dispatchers.IO) { logDao.recent(limit).map { it.toRecord() } }

    /**
     * 撤销一条操作。[retentionDays] 用于 COPY 撤销时把新文件放进回收站。
     */
    suspend fun undo(
        logId: Long,
        retentionDays: Int = TrashRepository.RETENTION_DAYS,
    ): FileOpReport = withContext(Dispatchers.IO) {
        val log = logDao.byId(logId)
            ?: return@withContext FileOpReport("UNDO", "撤销", failed = 1)
        if (log.undone) {
            return@withContext FileOpReport(
                type = "UNDO",
                title = "撤销",
                failed = 1,
                failures = listOf(OpFailure("记录", "这条操作已经撤销过了")),
            )
        }
        val items = OperationPayload.decode(log.payloadJson)
        if (items.isEmpty()) {
            return@withContext FileOpReport(
                type = "UNDO",
                title = "撤销",
                failed = 1,
                failures = listOf(OpFailure("记录", "这条记录缺少文件路径信息，无法撤销")),
            )
        }
        val report = when (log.type) {
            "TRASH" -> {
                val result = trashRepository.restore(items.map { it.id })
                FileOpReport(
                    type = "UNDO",
                    title = "撤销移入回收站",
                    requested = items.size,
                    succeeded = result.restored,
                    failed = result.failed,
                    failures = result.failures,
                    logId = logId,
                )
            }
            "MOVE", "RENAME" -> moveBack(items, log.type, logId)
            "COPY" -> {
                val refs = items.mapNotNull { item ->
                    item.to?.let { path -> TrashFileRef(path, item.name, File(path).length()) }
                }
                val result = trashRepository.trashFiles(refs, retentionDays)
                FileOpReport(
                    type = "UNDO",
                    title = "撤销复制",
                    requested = refs.size,
                    succeeded = result.trashed,
                    failed = result.failed,
                    failures = result.failures,
                    logId = logId,
                )
            }
            else -> FileOpReport(
                type = "UNDO",
                title = "撤销",
                failed = 1,
                failures = listOf(OpFailure("记录", "${titleOf(log.type)}不支持撤销")),
            )
        }
        // 只有"全部成功"才标记为已撤销：部分失败时若标记掉，剩下的文件就永远没有入口去救了。
        if (report.succeeded > 0 && report.failed == 0) runCatching { logDao.markUndone(logId) }
        report
    }

    // ---- 内部实现 ----

    private suspend fun moveBack(items: List<OpItem>, type: String, logId: Long): FileOpReport {
        var restored = 0
        var failed = 0
        val failures = mutableListOf<OpFailure>()
        val scanPaths = mutableListOf<String>()
        for (item in items) {
            val from = item.from
            val to = item.to
            if (from.isNullOrBlank() || to.isNullOrBlank()) {
                failed++
                failures += OpFailure(item.name, "缺少路径信息，无法撤销")
                continue
            }
            val current = File(to)
            if (!current.exists()) {
                failed++
                failures += OpFailure(item.name, "文件已不在操作后的位置")
                continue
            }
            val original = File(from)
            original.parentFile?.mkdirs()
            var target = original
            var suffix = 1
            while (target.exists()) {
                target = File(original.parentFile, StoragePaths.uniqueName(original.name, suffix))
                suffix++
            }
            if (!moveFile(current, target)) {
                failed++
                failures += OpFailure(item.name, "搬回原位置失败")
                continue
            }
            runCatching {
                val entity = mediaDao.byId(item.id)
                if (entity != null) {
                    val relative = relativeDirOf(target.absolutePath)
                    if (type == "MOVE") {
                        mediaDao.updateLocation(
                            id = item.id,
                            path = target.absolutePath,
                            relativePath = relative,
                            bucketId = relative.lowercase().hashCode().toLong(),
                            bucketName = target.parentFile?.name ?: "",
                        )
                    } else {
                        mediaDao.updatePathAndName(
                            id = item.id,
                            path = target.absolutePath,
                            displayName = target.name,
                            relativePath = relative,
                        )
                    }
                }
            }
            scanPaths += target.absolutePath
            restored++
        }
        MediaScanner.scan(context, scanPaths)
        return FileOpReport(
            type = "UNDO",
            title = if (type == "MOVE") "撤销移动" else "撤销重命名",
            requested = items.size,
            succeeded = restored,
            failed = failed,
            failures = failures,
            logId = logId,
        )
    }

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

    /** 从绝对路径推出所在目录（相对主存储根，带结尾斜杠）。 */
    private fun relativeDirOf(absolutePath: String): String {
        val root = StoragePaths.volumeRootOf(absolutePath)
        val relative = absolutePath.removePrefix(root).trimStart('/')
        val dir = relative.substringBeforeLast('/', "")
        return if (dir.isEmpty()) "" else "$dir/"
    }

    private fun OperationLogEntity.toRecord(): OperationRecord {
        val items = OperationPayload.decode(payloadJson)
        val legacy = OperationPayload.isLegacy(payloadJson)
        // 老版本（v1 纯 id 数组）没有 from/to，搬不回去，不能显示成可撤销。
        val undoable = !undone && !legacy && type in UNDOABLE_TYPES &&
            items.any { it.from != null || it.to != null || it.id > 0 }
        return OperationRecord(
            id = id,
            type = type,
            title = titleOf(type),
            summary = summary,
            createdAtMs = createdAtMs,
            undone = undone,
            canUndo = undoable,
            undoHint = when {
                undone -> "已撤销"
                legacy -> "这条记录来自旧版本，缺少路径信息，无法撤销"
                type !in UNDOABLE_TYPES -> "${titleOf(type)}不支持撤销"
                !undoable -> "缺少路径信息，无法撤销"
                else -> ""
            },
            itemCount = items.size,
        )
    }

    private fun titleOf(type: String): String = when (type) {
        "TRASH" -> "移入回收站"
        "RESTORE" -> "从回收站恢复"
        "MOVE" -> "移动"
        "COPY" -> "复制"
        "RENAME" -> "重命名"
        "DELETE" -> "永久删除"
        "EMPTY_TRASH" -> "清空/清理回收站"
        "FOLDER" -> "文件夹操作"
        else -> type
    }

    private companion object {
        const val HISTORY_LIMIT = 200
        val UNDOABLE_TYPES = setOf("MOVE", "RENAME", "COPY", "TRASH")
    }
}