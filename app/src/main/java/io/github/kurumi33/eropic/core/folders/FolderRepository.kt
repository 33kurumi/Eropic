package io.github.kurumi33.eropic.core.folders

import android.content.Context
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.fileops.OpFailure
import io.github.kurumi33.eropic.core.fileops.RenameTemplate
import io.github.kurumi33.eropic.core.media.MediaScanner
import io.github.kurumi33.eropic.core.media.StoragePaths
import io.github.kurumi33.eropic.core.settings.SettingsRepository
import io.github.kurumi33.eropic.core.trash.TrashRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件夹级操作（v0.5）：
 * - 重命名：**真实重命名磁盘目录**，并同步改写索引里该目录下所有图片的 path / relativePath；
 * - 删除：不直接删文件，而是把目录下所有图片移进回收站（30 天可恢复），再删掉变空的目录；
 * - 置顶 / 更换图集封面：属于用户偏好，存在 DataStore（SettingsRepository），不进媒体库。
 *
 * 为什么不做「复制文件夹」：100GB 量级的目录复制既慢又容易被用户误触，v0.5 明确不做（见 ADR）。
 */
class FolderRepository(
    context: Context,
    private val database: EropicDatabase,
    private val settingsRepository: SettingsRepository,
    private val trashRepository: TrashRepository,
) {
    private val appContext = context.applicationContext
    private val mediaDao = database.mediaDao()

    /**
     * 重命名文件夹。
     *
     * 步骤：校验名字 → 同卷 renameTo（同卷是原子操作，不会产生半截目录）→ 改写索引行 → 迁移置顶/封面配置
     * → 让 MediaScanner 重扫新旧目录（MediaStore 里的 RELATIVE_PATH 要跟着变）。
     */
    suspend fun renameFolder(relativePath: String, newName: String): FolderOpResult = withContext(Dispatchers.IO) {
        val rel = normalize(relativePath)
        if (rel.isEmpty()) return@withContext FolderOpResult(false, "根目录不能重命名")
        val name = newName.trim()
        RenameTemplate.validateName(name)?.let { return@withContext FolderOpResult(false, it) }
        val rows = mediaDao.pathRowsUnderFolder(patternOf(rel))
        val root = rootOf(rel, rows.firstOrNull()?.path)
        val source = File(root, rel)
        if (!source.isDirectory) return@withContext FolderOpResult(false, "目录不存在（可能已被移动或删除）")
        val target = File(source.parentFile ?: return@withContext FolderOpResult(false, "无法定位上级目录"), name)
        if (target.exists()) return@withContext FolderOpResult(false, "同名文件夹已存在：$name")
        val renamed = runCatching { source.renameTo(target) }.getOrDefault(false)
        if (!renamed) return@withContext FolderOpResult(false, "重命名失败：可能没有写入权限，请检查「所有文件访问」授权")

        val parentRel = parentOf(rel)
        val newRel = if (parentRel.isEmpty()) name else "$parentRel/$name"
        var updated = 0
        val failures = ArrayList<OpFailure>()
        for (row in rows) {
            val suffix = row.relativePath.removePrefix("$rel/")
            val newRowRel = if (suffix.isEmpty()) "$newRel/" else "$newRel/$suffix"
            val newPath = if (row.path.startsWith(source.absolutePath)) {
                target.absolutePath + row.path.removePrefix(source.absolutePath)
            } else {
                File(target, row.displayName).absolutePath
            }
            runCatching { mediaDao.updatePathAndName(row.id, newPath, row.displayName, newRowRel) }
                .onSuccess { updated++ }
                .onFailure { failures += OpFailure(row.displayName, it.message ?: "索引更新失败") }
        }
        runCatching { settingsRepository.renameFolderPrefs(rel, newRel) }
        // 新旧两个绝对目录都扫一遍：旧目录要通知 MediaStore 条目消失，新目录要收编新路径
        MediaScanner.scan(appContext, listOf(source.absolutePath, target.absolutePath))
        FolderOpResult(
            ok = true,
            message = "已重命名为「$name」，更新 $updated 张图片的路径",
            changed = updated,
            failures = failures,
        )
    }

    /**
     * 删除文件夹：先把目录下所有图片移入回收站（可恢复），再删掉变空的目录。
     *
     * 注意「变空」的判断：目录里可能还有视频、文档等 Eropic 没有索引的文件，
     * 这些文件不会被删，目录也就保留下来，并在结果里告诉用户还剩多少——绝不静默丢数据。
     */
    suspend fun trashFolder(relativePath: String): FolderOpResult = withContext(Dispatchers.IO) {
        val rel = normalize(relativePath)
        if (rel.isEmpty()) return@withContext FolderOpResult(false, "根目录不能删除")
        val rows = mediaDao.pathRowsUnderFolder(patternOf(rel))
        val root = rootOf(rel, rows.firstOrNull()?.path)
        val dir = File(root, rel)
        if (!dir.exists()) return@withContext FolderOpResult(false, "目录不存在（可能已被移动或删除）")
        if (rows.isEmpty()) {
            val removed = deleteEmptyDirs(dir)
            return@withContext if (removed) {
                FolderOpResult(true, "该目录没有已索引的图片，已删除空目录")
            } else {
                FolderOpResult(false, "目录里还有未索引的文件（视频/文档等），已保留未删")
            }
        }
        val result = trashRepository.moveToTrash(rows.map { it.id })
        val removed = deleteEmptyDirs(dir)
        val left = countFiles(dir)
        val suffix = when {
            !removed -> "；目录里还有未索引的文件，已保留"
            left > 0 -> "；剩余 $left 个未索引文件未处理（视频/文档等）"
            else -> ""
        }
        FolderOpResult(
            ok = result.trashed > 0,
            message = result.message + suffix,
            changed = result.trashed,
            failures = result.failures,
        )
    }

    /** 置顶 / 取消置顶（偏好，不涉及文件系统）。 */
    suspend fun setPinned(relativePath: String, pinned: Boolean) {
        settingsRepository.setFolderPinned(relativePath, pinned)
    }

    /** 更换图集封面；[mediaId] = null 表示恢复成「该目录最新一张图」。 */
    suspend fun setCover(relativePath: String, mediaId: Long?) {
        settingsRepository.setFolderCover(relativePath, mediaId)
    }

    /** 目录默认封面（该目录及子目录里最新的一张）；返回 null 表示目录里没有已索引图片。 */
    suspend fun defaultCover(relativePath: String) =
        mediaDao.folderCover(patternOf(normalize(relativePath)))

    private fun patternOf(rel: String): String =
        escapeLike(rel) + "/%"

    private fun rootOf(rel: String, samplePath: String?): String {
        val fromIndex = samplePath?.let { runCatching { StoragePaths.volumeRootOf(it) }.getOrNull() }
        return fromIndex ?: StoragePaths.primaryRoot()
    }

    private fun normalize(path: String): String = path.trim().replace('\\', '/').trim('/')

    private fun parentOf(rel: String): String = rel.substringBeforeLast('/', "")

    /** 只删除空的子目录（自底向上），返回是否连根目录也删掉了。 */
    private fun deleteEmptyDirs(dir: File): Boolean {
        val children = dir.listFiles() ?: return false
        for (child in children) {
            if (child.isDirectory) deleteEmptyDirs(child)
        }
        val remaining = dir.listFiles()
        return if (remaining != null && remaining.isEmpty()) dir.delete() else false
    }

    private fun countFiles(dir: File): Int {
        val files = dir.listFiles() ?: return 0
        var count = 0
        for (file in files) {
            if (file.isDirectory) count += countFiles(file) else count++
        }
        return count
    }

    private fun escapeLike(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}

/** 文件夹操作结果：只关心「成功与否 + 一句中文说明 + 有多少张受影响 + 失败明细」。 */
data class FolderOpResult(
    val ok: Boolean,
    val message: String,
    val changed: Int = 0,
    val failures: List<OpFailure> = emptyList(),
)
