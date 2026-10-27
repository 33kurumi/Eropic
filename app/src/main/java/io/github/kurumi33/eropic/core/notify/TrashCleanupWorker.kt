package io.github.kurumi33.eropic.core.notify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.kurumi33.eropic.EropicApplication
import io.github.kurumi33.eropic.core.permissions.StorageAccess
import kotlinx.coroutines.CancellationException

/**
 * 回收站过期清理 + 到期提醒。
 *
 * 没有"所有文件访问"权限时删不掉 .EropicTrash 里的文件，此时只做提醒；
 * 清理失败也不 retry（文件可能被占用，下次周期任务自然会再试）。
 */
class TrashCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? EropicApplication)?.container ?: return Result.failure()
        return try {
            var cleaned = 0
            if (StorageAccess.hasAllFilesAccess()) {
                cleaned = runCatching { container.trashRepository.cleanupExpired() }.getOrDefault(0)
            }
            val expiring = runCatching { container.trashRepository.expiringSoon(EXPIRING_WINDOW_MS) }
                .getOrDefault(emptyList())
            if (expiring.isNotEmpty()) {
                TrashNotifications.notifyExpiring(applicationContext, expiring.size, EXPIRING_WINDOW_DAYS)
            }
            if (cleaned > 0) Log.i(TAG, "已清理过期回收站条目 $cleaned 个")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "回收站清理失败", e)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "TrashCleanupWorker"
        private const val EXPIRING_WINDOW_DAYS = 3
        private const val EXPIRING_WINDOW_MS = EXPIRING_WINDOW_DAYS * 24L * 60 * 60 * 1000
    }
}
