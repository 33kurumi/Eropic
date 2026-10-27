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

    fun required(): Array<String> = buildList {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
    }.toTypedArray()

    fun evaluate(context: Context): MediaAccess {
        val full = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED
        val partial = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
        return MediaAccess(full = full, partial = partial)
    }
}
