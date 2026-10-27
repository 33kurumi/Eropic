package io.github.kurumi33.eropic.core.fileops

import android.content.Context
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.media.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 一个空目录（相对主存储根的路径，带结尾斜杠）。 */
data class EmptyFolderEntry(val relativePath: String, val absolutePath: String)

/**
 * 文件夹管理（对应规划 C-04：新建目录、查找并删除空目录）。
 *
 * 只扫 Pictures / DCIM / Download / Documents 四个常见目录，且不进入隐藏目录与 Android/，
 * 避免在 100k+ 文件时把整块存储遍历一遍。
 */
class FolderOps(
    private val context: Context,
    private val database: EropicDatabase,
) {

    private val logDao = database.operationLogDao()
    private val root = File(StoragePaths.primaryRoot())

    /** 找出所有"整棵子树都没有文件"的目录，只返回最上层（删掉它就连子目录一起清掉）。 */
    suspend fun listEmptyFolders(): List<EmptyFolderEntry> = withContext(Dispatchers.IO) {
        val found = mutableListOf<String>()
        for (name in ROOTS) {
            val dir = File(root, name)
            if (dir.isDirectory) walk(dir, found, 0)
        }
        val topmost = found.sortedBy { it.length }.filter { path ->
            found.none { other -> other != path && path.startsWith("$other/") }
        }
        topmost.sorted().map { absolute ->
            EmptyFolderEntry(relativePath = relativeOf(absolute), absolutePath = absolute)
        }
    }

    /** 新建目录；[raw] 是相对主存储根的路径（如 "Pictures/Eropic/Trips"）。 */
    suspend fun createFolder(raw: String): FileOpReport = withContext(Dispatchers.IO) {
        val relative = normalize(raw)
            ?: return@withContext FileOpReport(
                type = "FOLDER",
                title = "新建文件夹",
                requested = 1,
                failed = 1,
                failures = listOf(OpFailure(raw, "路径不合法（不能为空、不能是绝对路径、不能包含 ..）")),
            )
        val dir = File(root, relative)
        if (dir.exists()) {
            return@withContext FileOpReport(
                type = "FOLDER",
                title = "新建文件夹",
                requested = 1,
                failed = 1,
                failures = listOf(OpFailure(relative, "目录已存在")),
            )
        }
        val ok = dir.mkdirs()
        if (!ok) {
            return@withContext FileOpReport(
                type = "FOLDER",
                title = "新建文件夹",
                requested = 1,
                failed = 1,
                failures = listOf(OpFailure(relative, "创建失败（权限或空间不足）")),
            )
        }
        log("FOLDER", "新建目录 $relative", emptyList())
        FileOpReport(type = "FOLDER", title = "新建文件夹", requested = 1, succeeded = 1)
    }

    /** 删除空目录（先复查一遍，避免把刚被系统写入文件的目录删掉数据）。 */
    suspend fun deleteEmptyFolders(absolutePaths: List<String>): FileOpReport = withContext(Dispatchers.IO) {
        if (absolutePaths.isEmpty()) {
            return@withContext FileOpReport("FOLDER", "删除空文件夹")
        }
        var succeeded = 0
        var failed = 0
        val failures = mutableListOf<OpFailure>()
        for (path in absolutePaths) {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                failed++
                failures += OpFailure(relativeOf(path), "目录已不存在")
                continue
            }
            if (!insideRoot(dir) || dir.absolutePath.contains(StoragePaths.TRASH_DIR_NAME)) {
                failed++
                failures += OpFailure(relativeOf(path), "不在可管理范围内")
                continue
            }
            if (hasAnyFile(dir, 0)) {
                failed++
                failures += OpFailure(relativeOf(path), "目录里还有文件，已跳过")
                continue
            }
            if (deleteRecursivelyEmpty(dir)) {
                succeeded++
            } else {
                failed++
                failures += OpFailure(relativeOf(path), "删除失败（可能被占用）")
            }
        }
        if (succeeded > 0) log("FOLDER", "删除空目录 $succeeded 个", emptyList())
        FileOpReport(
            type = "FOLDER",
            title = "删除空文件夹",
            requested = absolutePaths.size,
            succeeded = succeeded,
            failed = failed,
            failures = failures,
        )
    }

    // ---- 内部工具 ----

    /** 返回 true = 这棵子树里有文件（含隐藏目录，隐藏目录一律视为非空，避免误删缓存）。 */
    private fun walk(dir: File, found: MutableList<String>, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return true
        val children = dir.listFiles() ?: return true
        var hasFile = false
        for (child in children) {
            if (child.isDirectory) {
                if (child.name == "Android") {
                    hasFile = true
                    continue
                }
                if (child.name.startsWith(".")) {
                    // 隐藏目录（缓存/缩略图）不遍历也不清理
                    hasFile = true
                    continue
                }
                if (walk(child, found, depth + 1)) hasFile = true
            } else {
                hasFile = true
            }
        }
        if (!hasFile) found += dir.absolutePath
        return hasFile
    }

    private fun hasAnyFile(dir: File, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return true
        val children = dir.listFiles() ?: return true
        for (child in children) {
            if (child.isDirectory) {
                if (hasAnyFile(child, depth + 1)) return true
            } else {
                return true
            }
        }
        return false
    }

    private fun deleteRecursivelyEmpty(dir: File): Boolean {
        val children = dir.listFiles().orEmpty()
        for (child in children) {
            if (child.isDirectory && !deleteRecursivelyEmpty(child)) return false
            if (!child.isDirectory && !child.delete()) return false
        }
        return dir.delete()
    }

    private fun insideRoot(dir: File): Boolean =
        dir.absolutePath.startsWith(root.absolutePath + "/")

    private fun relativeOf(absolute: String): String =
        absolute.removePrefix(root.absolutePath).trimStart('/')

    private fun normalize(raw: String): String? {
        val trimmed = raw.trim().trim('/')
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("/") || trimmed.contains("..")) return null
        val segments = trimmed.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        if (segments.any { it.contains(':') || it.contains('\\') || it == "." }) return null
        return segments.joinToString("/")
    }

    private suspend fun log(type: String, summary: String, items: List<OpItem>) {
        runCatching {
            logDao.insert(
                io.github.kurumi33.eropic.core.database.entity.OperationLogEntity(
                    type = type,
                    summary = summary,
                    payloadJson = OperationPayload.encode(items),
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private companion object {
        val ROOTS = listOf("Pictures", "DCIM", "Download", "Documents")
        const val MAX_DEPTH = 8
    }
}
