package io.github.kurumi33.eropic.core.fileops

import android.content.Context
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.database.entity.OperationLogEntity
import io.github.kurumi33.eropic.core.media.MediaScanner
import io.github.kurumi33.eropic.core.media.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 批量重命名的预览行：[newName] 是最终会落盘的文件名。 */
data class RenamePreview(
    val id: Long,
    val oldName: String,
    val newName: String,
    /** 目标文件名已被占用（实际执行时会自动加 " (1)"） */
    val conflict: Boolean,
)

/**
 * 文件命名模板（对应规划 C-03）。
 *
 * 占位符：{name} 原名（不含扩展名）、{ext} 扩展名（不含点）、{n} 三位序号、
 * {date} 拍摄日期 yyyyMMdd、{time} 拍摄时间 HHmmss。
 * 模板里没写 {ext} 时，执行时会自动补上原扩展名，避免产出没有后缀的文件。
 */
object RenameTemplate {

    const val DEFAULT_BATCH = "{date}_{n}"

    private val KNOWN_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "dng", "tif", "tiff",
    )

    private val ILLEGAL_CHARS = listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /** 返回错误文案；null 表示合法。 */
    fun validateName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "文件名不能为空"
        if (trimmed.length > 120) return "文件名最长 120 个字符"
        if (trimmed == "." || trimmed == "..") return "文件名不合法"
        if (trimmed.any { it in ILLEGAL_CHARS }) return "文件名不能包含 / \\ : * ? \" < > |"
        return null
    }

    /** 模板里只允许占位符与普通文件名，不允许路径分隔符（防止把文件挪出原目录）。 */
    fun validateTemplate(template: String): String? {
        if (template.isBlank()) return "模板不能为空"
        val nameError = validateName(template)
        if (nameError != null) return nameError
        return null
    }

    fun apply(template: String, index: Int, originalName: String, dateTakenMs: Long): String {
        val stem = originalName.substringBeforeLast('.', originalName)
        val ext = originalName.substringAfterLast('.', "")
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(dateTakenMs))
        val time = SimpleDateFormat("HHmmss", Locale.US).format(Date(dateTakenMs))
        return template
            .replace("{name}", stem)
            .replace("{ext}", ext)
            .replace("{n}", String.format(Locale.US, "%03d", index))
            .replace("{date}", date)
            .replace("{time}", time)
            .trim()
    }

    /** 模板没带扩展名时补上原扩展名；[apply] 已生成 {ext} 的话这里不会重复追加。 */
    fun ensureExtension(name: String, originalName: String, template: String): String {
        if (template.contains("{ext}")) return name
        val ext = originalName.substringAfterLast('.', "")
        if (ext.isEmpty() || !KNOWN_EXTENSIONS.contains(ext.lowercase())) {
            // 原文件没有扩展名或不是图片常见后缀：原样返回，不硬塞一个假扩展名
            return name
        }
        val slashIndex = name.lastIndexOf('.')
        val alreadyHasExt = slashIndex > 0 &&
            KNOWN_EXTENSIONS.contains(name.substring(slashIndex + 1).lowercase())
        return if (alreadyHasExt) name else "$name.$ext"
    }
}

/**
 * 重命名（对应规划 C-03）：单张改名 + 按模板批量改名。
 *
 * 只改文件名，不换目录；目标目录里已有同名文件时自动加 " (1)"，绝不覆盖。
 * 文件系统操作成功后再改索引，最后由 MediaScanner 让系统相册看到新名字。
 */
class RenameRepository(
    private val context: Context,
    private val database: EropicDatabase,
) {

    private val mediaDao = database.mediaDao()
    private val logDao = database.operationLogDao()

    /** 批量预览；[items] 的顺序就是 {n} 的编号顺序（调用方按当前排序传入即可）。 */
    fun preview(items: List<MediaEntity>, template: String): List<RenamePreview> =
        items.mapIndexed { index, entity ->
            val raw = RenameTemplate.apply(template, index + 1, entity.displayName, entity.dateTakenMs)
            val newName = RenameTemplate.ensureExtension(raw, entity.displayName, template)
            val target = File(File(entity.path).parentFile, newName)
            RenamePreview(
                id = entity.id,
                oldName = entity.displayName,
                newName = newName,
                conflict = target.exists() && newName != entity.displayName,
            )
        }

    suspend fun rename(id: Long, newName: String): FileOpReport = withContext(Dispatchers.IO) {
        val entity = mediaDao.byId(id)
            ?: return@withContext FileOpReport("RENAME", "重命名", requested = 1, failed = 1)
        val template = newName
        val name = RenameTemplate.ensureExtension(newName.trim(), entity.displayName, template)
        val validation = RenameTemplate.validateName(name)
        if (validation != null) {
            return@withContext FileOpReport(
                type = "RENAME",
                title = "重命名",
                requested = 1,
                failed = 1,
                failures = listOf(OpFailure(entity.displayName, validation)),
            )
        }
        val result = renameOne(entity, name)
        result
    }

    suspend fun renameBatch(
        ids: List<Long>,
        template: String,
        batch: BatchController? = null,
    ): FileOpReport = withContext(Dispatchers.IO) {
        val templateError = RenameTemplate.validateTemplate(template)
        if (templateError != null) {
            return@withContext FileOpReport(
                type = "RENAME",
                title = "批量重命名",
                requested = ids.size,
                failed = ids.size,
                failures = listOf(OpFailure("模板", templateError)),
            )
        }
        var succeeded = 0
        var failed = 0
        val items = mutableListOf<OpItem>()
        val failures = mutableListOf<OpFailure>()
        batch?.begin("批量重命名", ids.size)
        var cancelled = false
        try {
            ids.forEachIndexed { index, id ->
                if (batch != null && !batch.gate()) {
                    cancelled = true
                    return@forEachIndexed
                }
                val entity = mediaDao.byId(id)
                if (entity == null) {
                    failed++
                    failures += OpFailure("#$id", "索引里已不存在")
                    batch?.fail()
                    return@forEachIndexed
                }
                val raw = RenameTemplate.apply(template, index + 1, entity.displayName, entity.dateTakenMs)
                val name = RenameTemplate.ensureExtension(raw, entity.displayName, template)
                val one = renameOne(entity, name)
                if (one.succeeded > 0) {
                    succeeded++
                    one.items.forEach { items += it }
                    batch?.step(name)
                } else {
                    failed++
                    one.failures.forEach { failures += it }
                    batch?.fail(entity.displayName)
                }
            }
        } finally {
            batch?.finish()
        }
        val logId = log(
            type = "RENAME",
            summary = "批量重命名 $succeeded 张" + if (failed > 0) "，$failed 张失败" else "",
            items = items,
        )
        FileOpReport(
            type = "RENAME",
            title = "批量重命名",
            requested = ids.size,
            succeeded = succeeded,
            failed = failed,
            cancelled = cancelled,
            failures = failures,
            logId = logId,
        )
    }

    /** 单张改名：返回的 FileOpReport 里带 items（幂等撤销用），供批量复用。 */
    private suspend fun renameOne(entity: MediaEntity, requestedName: String): FileOpReport {
        val validation = RenameTemplate.validateName(requestedName)
        if (validation != null) {
            return FileOpReport(
                type = "RENAME",
                title = "重命名",
                requested = 1,
                failed = 1,
                failures = listOf(OpFailure(entity.displayName, validation)),
            )
        }
        val source = File(entity.path)
        if (!source.exists()) {
            return FileOpReport(
                type = "RENAME",
                title = "重命名",
                requested = 1,
                failed = 1,
                failures = listOf(OpFailure(entity.displayName, "文件已不存在")),
            )
        }
        if (entity.displayName == requestedName) {
            return FileOpReport(type = "RENAME", title = "重命名", requested = 1)
        }
        val parent = source.parentFile ?: return FileOpReport(
            type = "RENAME",
            title = "重命名",
            requested = 1,
            failed = 1,
            failures = listOf(OpFailure(entity.displayName, "无法定位所在目录")),
        )
        var target = File(parent, requestedName)
        var suffix = 1
        while (target.exists()) {
            target = File(parent, StoragePaths.uniqueName(requestedName, suffix))
            suffix++
        }
        if (!source.renameTo(target)) {
            return FileOpReport(
                type = "RENAME",
                title = "重命名",
                requested = 1,
                failed = 1,
                failures = listOf(OpFailure(entity.displayName, "重命名失败（文件被占用？）")),
            )
        }
        mediaDao.updatePathAndName(
            id = entity.id,
            path = target.absolutePath,
            displayName = target.name,
            relativePath = entity.relativePath,
        )
        MediaScanner.scan(context, listOf(entity.path, target.absolutePath))
        val item = OpItem(id = entity.id, name = target.name, from = entity.path, to = target.absolutePath)
        val logId = log("RENAME", "重命名 ${entity.displayName} → ${target.name}", listOf(item))
        return FileOpReport(
            type = "RENAME",
            title = "重命名",
            requested = 1,
            succeeded = 1,
            logId = logId,
            items = listOf(item),
        )
    }

    private suspend fun log(type: String, summary: String, items: List<OpItem>): Long = runCatching {
        logDao.insert(
            OperationLogEntity(
                type = type,
                summary = summary,
                payloadJson = OperationPayload.encode(items),
                createdAtMs = System.currentTimeMillis(),
            ),
        )
    }.getOrDefault(0L)
}
