package io.github.kurumi33.eropic.core.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 写权限判定：真实移动、回收站、永久删除都依赖"所有文件访问"（[android.Manifest.permission.MANAGE_EXTERNAL_STORAGE]）。
 *
 * 决策背景见 ADR-0012：Eropic 只侧载分发、不上架 Play，因此可以直接申请
 * MANAGE_EXTERNAL_STORAGE，用 java.io 做真正的文件移动与同卷 rename 回收站，
 * 与 Fossify Gallery / IacobIonut01 Gallery 的做法一致。未授予时全部写操作入口禁用，
 * 只读功能（浏览/查重/筛选）不受影响。
 */
object StorageAccess {

    /** 是否已获得"所有文件访问"（Android 11+ 才有这个概念）。 */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** 拉起系统授权页；个别 ROM 没有该页面时退回应用详情页。 */
    fun requestIntent(context: Context): Intent {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
        val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return if (direct.resolveActivity(context.packageManager) != null) direct else fallback
    }
}
