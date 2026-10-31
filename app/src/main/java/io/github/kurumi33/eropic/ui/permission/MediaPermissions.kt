package io.github.kurumi33.eropic.ui.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** 当前照片访问级别。Android 14+ 存在"仅授权部分照片"。 */
data class MediaAccess(
    val full: Boolean,
    val partial: Boolean,
    /** v0.8：视频读取权限（Android 13+ 的 READ_MEDIA_VIDEO；12 及以下与图片共用 READ_EXTERNAL_STORAGE）。 */
    val videoGranted: Boolean = false,
) {
    val allowed: Boolean get() = full || partial
}

object MediaPermissions {

    /** Android 13+ 是 READ_MEDIA_IMAGES；Android 12 及以下只有 READ_EXTERNAL_STORAGE。 */
    private val readPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /** 视频权限：Android 13+ 独立成 READ_MEDIA_VIDEO；12 及以下仍由 READ_EXTERNAL_STORAGE 覆盖。 */
    private val videoPermission: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            null
        }

    fun required(): Array<String> = buildList {
        add(readPermission)
        // v0.8：视频索引需要视频权限；和图片一起申请，系统会显示「照片和视频」
        videoPermission?.let { add(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
    }.toTypedArray()

    fun evaluate(context: Context): MediaAccess {
        val full = ContextCompat.checkSelfPermission(context, readPermission) ==
            PackageManager.PERMISSION_GRANTED
        // "仅选中照片"只在 Android 14+ 存在，且只有全量权限缺失时才成立。
        val partial = !full &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
        val videoGranted = videoPermission?.let {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        } ?: full
        return MediaAccess(full = full, partial = partial, videoGranted = videoGranted)
    }
}
