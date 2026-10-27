package io.github.kurumi33.eropic.core.fileops

import android.content.Context
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.database.entity.OperationLogEntity
import io.github.kurumi33.eropic.core.media.MediaScanner
import io.github.kurumi33.eropic.core.media.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 复制图片（对应规划 C-02）。
 *
 * 与移动不同：源文件保留，目标目录多出一份新文件。复制出来的文件在系统里是"新图片"，
 * 所以这里**不**直接写 media_item（MediaStore 会给它新 id），只做 MediaScanner 扫描 +
 * 记入操作日志；等增量同步把它收进索引。这也让"撤销复制"变成"把新文件移入回收站"。
 */
class CopyRepository(
    private val context: Context,
    private val database: EropicDatabase,
) {

    private val mediaDao = database.mediaDao()
    private val logDao = database.operationLogDao()

    suspend fun targetFolders(): List<String> = withContext(Dispatchers.IO) {
        val existing = runCatching { mediaDao.folderRows().map { it.relativePath } }.getOrDefault(emptyList())
        val standard = listOf("Pictures/", "Pictures/Eropic/", "DCIM/Camera/", "Download/")
        (existing + standard)
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { it.lowercase() }
    }

    suspend fun copyTo(
        mediaIds: List<Long>,
        targetRelativePath: String,
        batch: BatchController? = null,
    ): FileOpReport = withContext(Dispatchers.IO) {
        if (mediaIds.isEmpty()) {
            return@withContext FileOpReport(type = "COPY", title = "复制", failed = 0)
        }
        val target = targetRelativePath.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }
        var copied = 0
        var failed = 0
        var bytes = 0L
        val scanPaths = mutableListOf<String>()
        val items = mutableListOf<OpItem>()
        val failures = mutableListOf<OpFailure>()
        batch?.begin("复制到 $target", mediaIds.size)
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
                    failed++
                    failures += OpFailure(entity.displayName, "源文件已不存在")
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
                val ok = runCatching {
                    source.inputStream().use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }.isSuccess
                if (!ok) {
                    runCatching { dest.delete() }
                    failed++
                    failures += OpFailure(entity.displayName, "复制失败（空间不足或权限问题）")
                    batch?.fail(entity.displayName)
                    continue
                }
                scanPaths += dest.absolutePath
                items += OpItem(id = id, name = dest.name, from = entity.path, to = dest.absolutePath)
                copied++
                bytes += dest.length()
                batch?.step(dest.name)
            }
        } finally {
            batch?.finish()
        }

        MediaScanner.scan(context, scanPaths)
        val logId = runCatching {
            logDao.insert(
                OperationLogEntity(
                    type = "COPY",
                    summary = "复制到 $target$copied 张" + if (failed > 0) "，$failed 张失败" else "",
                    payloadJson = OperationPayload.encode(items),
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
        }.getOrDefault(0L)
        FileOpReport(
            type = "COPY",
            title = "复制",
            requested = mediaIds.size,
            succeeded = copied,
            failed = failed,
            cancelled = cancelled,
            bytes = bytes,
            failures = failures,
            logId = logId,
        )
    }
}
