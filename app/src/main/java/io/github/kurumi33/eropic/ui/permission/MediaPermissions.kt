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

    fun required(): Array<String> = buildList {
        add(readPermission)
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
        return MediaAccess(full = full, partial = partial)
    }
}
