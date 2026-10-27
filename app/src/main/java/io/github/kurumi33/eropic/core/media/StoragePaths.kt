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

    /** 某卷的回收站目录。 */
    fun trashDir(root: String): File = File(root, TRASH_DIR_NAME)

    /** 同目录重名时追加 " (1)"、" (2)" …… */
    fun uniqueName(name: String, suffix: Int): String {
        if (suffix <= 0) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return "$stem ($suffix)$ext"
    }
}
