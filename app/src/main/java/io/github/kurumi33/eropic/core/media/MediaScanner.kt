package io.github.kurumi33.eropic.core.media

import android.content.Context
import android.media.MediaScannerConnection

/** 真实文件操作后通知系统相册更新（移动/删除/恢复都要调）。 */
object MediaScanner {

    fun scan(context: Context, paths: Collection<String>) {
        val list = paths.filter { it.isNotBlank() }.distinct()
        if (list.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(context.applicationContext, list.toTypedArray(), null, null)
        }
    }
}
