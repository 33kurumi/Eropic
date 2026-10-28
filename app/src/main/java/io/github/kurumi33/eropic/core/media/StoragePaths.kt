package io.github.kurumi33.eropic.core.media

import android.os.Environment
import java.io.File

/** 路径相关的纯函数：卷根目录识别、回收站目录、重名文件改名。 */
object StoragePaths {

    /** 回收站目录名（带点前缀 + .nomedia，系统相册不会展示）。 */
    const val TRASH_DIR_NAME = ".EropicTrash"

    /** 主存储根目录，通常是 /storage/emulated/0。 */
    fun primaryRoot(): String = Environment.getExternalStorageDirectory().absolutePath

    /**
     * 从绝对路径推出所在卷的根目录。
     *
     * /storage/emulated/0/DCIM/a.jpg -> /storage/emulated/0
     * /storage/1234-ABCD/DCIM/a.jpg -> /storage/1234-ABCD
     * 认不出来时退回主存储根目录。
     */
    fun volumeRootOf(path: String): String {
        val primary = primaryRoot()
        if (path == primary || path.startsWith(primary + "/")) return primary
        if (path.startsWith("/storage/")) {
            val end = path.indexOf('/', "/storage/".length)
            if (end > 0) return path.substring(0, end)
        }
        return primary
    }

    /**
     * 从绝对路径反推 MediaStore 风格的相对目录（含结尾斜杠；文件就在卷根时返回空串）。
     *
     * 用来修正 RELATIVE_PATH 的大小写：DATA 绝对路径是文件系统里的真实名字，比 RELATIVE_PATH 可靠。
     * 返回 null 表示路径不在任何已知卷内，调用方应回落到 RELATIVE_PATH。
     *
     * /storage/emulated/0/Pictures/Camera/a.jpg -> "Pictures/Camera/"
     * /storage/emulated/0/a.jpg -> ""
     */
    fun relativePathOf(absolutePath: String): String? {
        val trimmed = absolutePath.trimEnd('/')
        val root = volumeRootOf(trimmed)
        if (trimmed == root) return ""
        if (!trimmed.startsWith(root + "/")) return null
        val rest = trimmed.substring(root.length + 1)
        val slash = rest.lastIndexOf('/')
        return if (slash < 0) "" else rest.substring(0, slash + 1)
    }

    /** 某卷的默认回收站目录（<卷根>/.EropicTrash）。 */
    fun trashDir(root: String): File = File(root, TRASH_DIR_NAME)

    /**
     * 用户自定义位置的回收站目录（v0.2）。
     *
     * [relativeDir] 是相对卷根的目录，可以只给父目录（"Pictures"）也可以给完整目录
     * （"Pictures/.EropicTrash"）；为空时退回默认位置。末级不是 [TRASH_DIR_NAME] 时自动补上，
     * 避免回收站文件直接撒进用户的相册目录。
     */
    fun trashDir(root: String, relativeDir: String): File {
        val segments = relativeDir.trim().replace('\\', '/').trim('/')
            .split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (segments.isEmpty()) return File(root, TRASH_DIR_NAME)
        val withLeaf = if (segments.last() == TRASH_DIR_NAME) segments else segments + TRASH_DIR_NAME
        return File(root, withLeaf.joinToString("/"))
    }

    /** 同目录重名时追加 " (1)"、" (2)" …… */
    fun uniqueName(name: String, suffix: Int): String {
        if (suffix <= 0) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return "$stem ($suffix)$ext"
    }
}
