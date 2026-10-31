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
import io.github.kurumi33.eropic.core.settings.SettingsRepository
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
    private val settingsRepository: SettingsRepository,
) {

    private val mediaDao = database.mediaDao()
    private val trashDao = database.trashDao()
    private val logDao = database.operationLogDao()

    val items: Flow<List<TrashItemEntity>> = trashDao.observeAll()
    val countFlow: Flow<Int> = trashDao.countFlow()
    val totalBytesFlow: Flow<Long> = trashDao.totalBytesFlow()

    suspend fun count(): Int = trashDao.count()

    /**
     * 按用户当前的保留期重算已有条目的到期时间。
     *
     * 必须在"改保留期"和"启动时清理"两处调用：否则改成"永久保留"后旧条目仍会按旧日期被删。
     */
    suspend fun applyRetention(retentionDays: Int = RETENTION_DAYS) = withContext(Dispatchers.IO) {
        runCatching { trashDao.remarkRetention(retentionDays, Long.MAX_VALUE) }
        Unit
    }

    suspend fun totalBytes(): Long = trashDao.totalBytes()

    // ---- v0.2：回收站位置可见、可改（用户反馈"不知道回收站在哪、能不能换地方"） ----

    /** 按用户设置算出某个卷上的回收站目录；设置读取失败时退回 <卷根>/.EropicTrash。 */
    private suspend fun resolveTrashDir(root: String): File {
        val relative = runCatching { settingsRepository.current().trashRelativeDir }.getOrDefault("")
        return StoragePaths.trashDir(root, relative)
    }

    /**
     * v0.9：真正可用的回收站目录（可写探测 + 回退链）。
     *
     * 用户反馈「个别图片无法移入回收站：无法创建回收站目录」（v0.8）。部分 ROM 上即使已授予
     * 「所有文件访问」，也可能被拒绝在卷根新建目录（OEM 策略、存储未完全挂载、或用户把目录配置
     * 到了不可写的位置）。这里按优先级逐个尝试，只有真的能在目录里落一个临时文件才算可用：
     *   1. 用户配置的目录（默认 <卷根>/.EropicTrash）
     *   2. <卷根>/.EropicTrash（配置目录不可写时退回默认位置）
     *   3. 应用外部私有目录 .../Android/data/<pkg>/files/trash（同一卷，rename 依旧瞬移）
     *   4. 应用内部 filesDir/trash（最保险的兜底）
     *
     * 回退是安全的：确实移进某个目录了才写 trash_item，恢复仍按 originalPath 搬回原处。
     */
    private suspend fun resolveWritableTrashDir(root: String): TrashDirChoice {
        val candidates = LinkedHashSet<File>()
        candidates += resolveTrashDir(root)
        candidates += StoragePaths.trashDir(root)
        context.getExternalFilesDir(null)?.let { candidates += File(it, "trash") }
        candidates += File(context.filesDir, "trash")

        val attempted = mutableListOf<String>()
        for (dir in candidates) {
            attempted += dir.absolutePath
            if (probeWritable(dir)) {
                return TrashDirChoice(dir = dir, attempted = attempted.toList())
            }
        }
        return TrashDirChoice(dir = null, attempted = attempted.toList())
    }

    /** 目录存在（或能建出来）且真的能写一个临时文件——只判断 mkdirs 会漏掉「建得出来但写不进」的情况。 */
    private suspend fun probeWritable(dir: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
            val probe = File(dir, ".eropic-probe-${System.nanoTime()}.tmp")
            val created = probe.createNewFile()
            runCatching { probe.delete() }
            created
        }.getOrDefault(false)
    }

    /** 当前设置对应的回收站绝对路径（主存储卷，未做可写探测），设置页直接展示给用户。 */
    suspend fun configuredTrashDir(): String = withContext(Dispatchers.IO) {
        resolveTrashDir(StoragePaths.primaryRoot()).absolutePath
    }

    /** v0.9：真正在用的回收站目录（做了可写探测，可能已经回退到别的目录）。 */
    suspend fun effectiveTrashDir(): String = withContext(Dispatchers.IO) {
        val primary = StoragePaths.primaryRoot()
        resolveWritableTrashDir(primary).dir?.absolutePath ?: resolveTrashDir(primary).absolutePath
    }

    /** v0.9：按优先级尝试过的候选目录（回收站页用它解释「为什么当前目录在这里」）。 */
    suspend fun trashDirCandidates(): List<String> = withContext(Dispatchers.IO) {
        val primary = StoragePaths.primaryRoot()
        val list = LinkedHashSet<String>()
        list += resolveTrashDir(primary).absolutePath
        list += StoragePaths.trashDir(primary).absolutePath
        context.getExternalFilesDir(null)?.let { list += File(it, "trash").absolutePath }
        list += File(context.filesDir, "trash").absolutePath
        list.toList()
    }

    /**
     * 目前实际存在条目用到的所有回收站目录。
     *
     * 用户改过位置后，老条目仍留在老目录（否则就凭空消失了），所以这里把
     * "当前设置目录 + 已有条目所在目录" 都列出来，UI 上说明"旧条目留在原目录、仍可恢复"。
     */
    suspend fun trashLocations(): List<String> = withContext(Dispatchers.IO) {
        val result = LinkedHashSet<String>()
        runCatching { result += configuredTrashDir() }
        runCatching { trashDao.all() }.getOrDefault(emptyList()).forEach { row ->
            File(row.trashPath).parentFile?.absolutePath?.let { result += it }
        }
        result.toList()
    }

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
        // 真正从图库消失的 mediaId：用于事后重算重复分组统计。
        val trashedMediaIds = mutableListOf<Long>()
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
            val choice = resolveWritableTrashDir(root)
            val trashDir = choice.dir
            if (trashDir == null) {
                failed++
                // 把尝试过的目录写进失败原因：用户能直接看出是哪个位置不可写。
                failures += OpFailure(
                    entity.displayName,
                    "回收站目录不可写（已尝试 " + choice.attempted.joinToString("、") + "）",
                )
                continue
            }
            ensureNoMedia(trashDir)
            val target = uniqueTarget(trashDir, entity.displayName)
            if (!moveFile(source, target)) {
                failed++
                failures += OpFailure(entity.displayName, "移动文件失败")
                continue
            }
            // 先移文件后写库：写库失败必须把文件搬回原处，否则文件留在隐藏的 .EropicTrash 里、
            // 索引行也还在，用户视角就是"图片丢了、相册还显示但打不开"。
            val trashId = runCatching {
                trashDao.insert(
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
            }.getOrNull()
            if (trashId == null) {
                runCatching { if (!source.exists()) target.renameTo(source) }
                failed++
                failures += OpFailure(entity.displayName, "写入回收站记录失败，已还原")
                continue
            }
            newIds += trashId
            oldPaths += entity.path
            items += OpItem(
                id = trashId,
                name = entity.displayName,
                from = entity.path,
                to = target.absolutePath,
            )
            mediaDao.deleteByIds(listOf(id))
            trashedMediaIds += id
            trashed++
            bytes += entity.sizeBytes
        }

        // 让系统相册/MediaStore 感知"原路径已不存在"，避免残留缩略图或幽灵条目。
        MediaScanner.scan(context, oldPaths)
        pruneDuplicateGroups(trashedMediaIds)
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
            val choice = resolveWritableTrashDir(root)
            val trashDir = choice.dir
            if (trashDir == null) {
                failed++
                failures += OpFailure(
                    ref.displayName,
                    "回收站目录不可写（已尝试 " + choice.attempted.joinToString("、") + "）",
                )
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
            val trashId = runCatching {
                trashDao.insert(
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
            }.getOrNull()
            if (trashId == null) {
                runCatching { if (!source.exists()) target.renameTo(source) }
                failed++
                failures += OpFailure(ref.displayName, "写入回收站记录失败，已还原")
                continue
            }
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
            if (!volumeAvailable(row.trashPath)) {
                // 卷没挂载（SD 卡拔出/U 盘断开）≠ 文件被删，必须保留记录，插回去还能恢复。
                failed++
                failures += OpFailure(row.displayName, "存储未挂载，暂不可恢复")
                continue
            }
            if (!source.exists()) {
                // 卷在但文件确实没了（外部删除），把失效记录清掉。
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
        if (trashIds.isEmpty()) {
            // 空列表拼出 "IN ()" 会抛 SQLiteException，这里必须先挡住。
            return@withContext DeleteResult(0, 0, 0L, "没有选中任何条目")
        }
        var deleted = 0
        var failed = 0
        var bytes = 0L
        val failures = mutableListOf<OpFailure>()
        for (row in trashDao.byIds(trashIds)) {
            if (!volumeAvailable(row.trashPath)) {
                failed++
                failures += OpFailure(row.displayName, "存储未挂载，暂不可删除")
                continue
            }
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
            // 卷没挂载时不能把记录删掉：文件还在卡里，删记录等于永久丢图。
            if (!volumeAvailable(row.trashPath)) continue
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

    /** 文件所在卷是否可访问：父目录存在即视为已挂载（不依赖 StorageManager，避免额外权限）。 */
    private fun volumeAvailable(path: String): Boolean {
        val parent = File(path).parentFile ?: return false
        return parent.exists() || parent.parentFile?.exists() == true
    }

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
        }.getOrElse {
            // 复制失败可能留下半截目标文件，必须清掉，否则回收站里多一个坏文件。
            runCatching { target.delete() }
            false
        }
    }

    private fun ensureNoMedia(dir: File) {
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
    }

    /**
     * 文件离开图库后重算重复分组。
     *
     * 只删 media_item 不碰 duplicate_member 的话，分组卡会长期显示虚高的「N 张 · 可释放 X」，
     * 「选择保留」也会基于已经不存在的成员给建议。成员不足 2 个的组连成员行一起删。
     * 这里纯属统计修正，失败不影响主流程（下次索引会重建）。
     */
    private suspend fun pruneDuplicateGroups(mediaIds: List<Long>) {
        if (mediaIds.isEmpty()) return
        runCatching {
            val duplicateDao = database.duplicateDao()
            duplicateDao.deleteMembersForMedia(mediaIds)
            duplicateDao.recomputeGroups()
            duplicateDao.deleteMembersOfIncompleteGroups()
            duplicateDao.deleteIncompleteGroups()
        }
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

/**
 * 回收站目录的选择结果。
 *
 * [dir] 为空表示所有候选目录都不可写；[attempted] 按尝试顺序记录路径，
 * 写进失败原因里，用户能直接看出是哪个位置不可写。
 */
private data class TrashDirChoice(val dir: File?, val attempted: List<String>)

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
