package io.github.kurumi33.eropic.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.kurumi33.eropic.MainActivity
import io.github.kurumi33.eropic.R

/**
 * 回收站到期提醒（对应规划 F-04 / D-05）。
 *
 * 提醒只在"回收站里有 3 天内到期的条目"时发一次，内容只说明数量与入口，
 * 不做任何自动删除动作——删除与否始终由用户决定（产品红线）。
 */
object TrashNotifications {

    const val CHANNEL_ID = "eropic_trash"
    private const val NOTIFICATION_ID = 301

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "回收站提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "回收站里有图片即将到期时提醒你"
            },
        )
    }

    /** 有 n 张图片将在 [withinDays] 天内被清理时提醒。没有通知权限时静默跳过。 */
    fun notifyExpiring(context: Context, count: Int, withinDays: Int = 3) {
        if (count <= 0) return
        if (!hasPermission(context)) return
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TRASH, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("回收站里有 $count 张图片即将到期")
            .setContentText("点开确认是否恢复；到期后会被永久删除。")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    const val EXTRA_OPEN_TRASH = "eropic_open_trash"
}
