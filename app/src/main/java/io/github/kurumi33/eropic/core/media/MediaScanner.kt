package io.github.kurumi33.eropic.core.media

import android.content.Context
import android.media.MediaScannerConnection

/**
 * 真实文件操作后通知系统相册更新（移动/删除/恢复都要调）。
 *
 * 注意分批：scanFile 走 Binder，一次传一万条路径（每条 60~90 字符）会超过
 * 1MB 的事务上限，异常被吞掉后系统相册就不更新了，所以按 [BATCH_SIZE] 切块。
 */
object MediaScanner {

    /** 单次 Binder 事务里的路径条数上限（远小于 1MB，给其它参数留余量）。 */
    private const val BATCH_SIZE = 500

    fun scan(context: Context, paths: Collection<String>) {
        val list = paths.filter { it.isNotBlank() }.distinct()
        if (list.isEmpty()) return
        val appContext = context.applicationContext
        list.chunked(BATCH_SIZE).forEach { chunk ->
            runCatching {
                MediaScannerConnection.scanFile(appContext, chunk.toTypedArray(), null, null)
            }
        }
    }
}
