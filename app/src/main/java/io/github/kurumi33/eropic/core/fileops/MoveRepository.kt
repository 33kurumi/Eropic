package io.github.kurumi33.eropic.core.fileops

import android.content.Context
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.database.entity.OperationLogEntity
import io.github.kurumi33.eropic.core.media.MediaScanner
import io.github.kurumi33.eropic.core.media.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 真实移动的结果。 */
data class MoveResult(
    val moved: Int,
    val failed: Int,
    val targetPath: String,
    val message: String,
    /** M4：逐条失败原因（结果报告页用） */
    val failures: List<OpFailure> = emptyList(),
    val bytes: Long = 0,
    val cancelled: Boolean = false,
    val logId: Long = 0,
)

/**
 * 真实移动图片到另一个目录（改变实际存储路径，不是 App 内分组）。
 *
 * 依赖 MANAGE_EXTERNAL_STORAGE（ADR-0012）：同卷优先 rename（零拷贝），
 * 跨卷或失败时退回"复制 + 删除"。移动后本地索引立刻改写 path/relativePath，
 * 再由 MediaScanner + 增量同步修正 MediaStore 的 _ID 变化。
 */
class MoveRepository(
    private val context: Context,
    private val database: EropicDatabase,
) {

    private val mediaDao = database.mediaDao()
    private val logDao = database.operationLogDao()

    /** 可选的移动目标目录（相对路径，形如 DCIM/Camera/）。 */
    suspend fun targetFolders(): List<String> = withContext(Dispatchers.IO) {
        val existing = runCatching { mediaDao.folderRows().map { it.relativePath } }.getOrDefault(emptyList())
        val standard = listOf("Pictures/", "Pictures/Eropic/", "DCIM/Camera/", "Download/")
        (existing + standard)
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { it.lowercase() }
    }

    suspend fun moveTo(
        mediaIds: List<Long>,
        targetRelativePath: String,
        batch: BatchController? = null,
    ): MoveResult = withContext(Dispatchers.IO) {
        if (mediaIds.isEmpty()) {
            return@withContext MoveResult(0, 0, targetRelativePath, "没有选中任何图片")
        }
        // 统一成 "A/B/" 形式，避免出现相对路径尾部斜杠不一致导致的目录分裂。
        val target = targetRelativePath.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }
        var moved = 0
        var failed = 0
        var bytes = 0L
        val scanPaths = mutableListOf<String>()
        val items = mutableListOf<OpItem>()
        val failures = mutableListOf<OpFailure>()
        batch?.begin("移动到 $target", mediaIds.size)
        var cancelled = false
        try {
            for (id in mediaIds) {
                if (batch != null && !batch.gate()) {
                    cancelled = true
                    break
                }
                val entity = mediaDao.byId(id)
                if (entity == null) {
                    failed++
                    failures += OpFailure("#$id", "索引里已不存在")
                    batch?.fail()
                    continue
                }
                val source = File(entity.path)
                if (!source.exists()) {
                    mediaDao.deleteByIds(listOf(id))
                    failed++
                    failures += OpFailure(entity.displayName, "文件已不存在")
                    batch?.fail(entity.displayName)
                    continue
                }
                val root = StoragePaths.volumeRootOf(entity.path)
                val destDir = if (target.isEmpty()) File(root) else File(root, target)
                if (!destDir.exists() && !destDir.mkdirs()) {
                    failed++
                    failures += OpFailure(entity.displayName, "无法创建目标目录")
                    batch?.fail(entity.displayName)
                    continue
                }
                var dest = File(destDir, entity.displayName)
                var suffix = 1
                while (dest.exists()) {
                    dest = File(destDir, StoragePaths.uniqueName(entity.displayName, suffix))
                    suffix++
                }
                if (!moveFile(source, dest)) {
                    failed++
                    failures += OpFailure(entity.displayName, "移动文件失败")
                    batch?.fail(entity.displayName)
                    continue
                }
                mediaDao.updateLocation(
                    id = id,
                    path = dest.absolutePath,
                    relativePath = target,
                    bucketId = target.lowercase().hashCode().toLong(),
                    bucketName = destDir.name,
                )
                scanPaths += entity.path
                scanPaths += dest.absolutePath
                items += OpItem(id = id, name = dest.name, from = entity.path, to = dest.absolutePath)
                moved++
                bytes += entity.sizeBytes
                batch?.step(dest.name)
            }
        } finally {
            batch?.finish()
        }

        MediaScanner.scan(context, scanPaths)
        val logId = runCatching {
            logDao.insert(
                OperationLogEntity(
                    type = "MOVE",
                    summary = "移动到 $target$moved 张" + if (failed > 0) "，$failed 张失败" else "",
                    payloadJson = OperationPayload.encode(items),
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
        }.getOrDefault(0L)
        MoveResult(
            moved = moved,
            failed = failed,
            targetPath = target,
            message = buildString {
                append("已移动 ").append(moved).append(" 张到 ").append(target)
                if (failed > 0) append(" · ").append(failed).append(" 张失败")
                if (cancelled) append("（已取消）")
            },
            failures = failures,
            bytes = bytes,
            cancelled = cancelled,
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
}
