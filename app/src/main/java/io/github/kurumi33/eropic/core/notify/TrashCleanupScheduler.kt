package io.github.kurumi33.eropic.core.notify

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 回收站清理任务（对应规划 D-05）：
 * - 周期任务每天跑一次，删掉超过保留期的条目，并在有条目 3 天内到期时发提醒；
 * - 启动时也立即跑一次，保证"改小保留期"之后马上生效。
 *
 * 只有"清理自己放在 .EropicTrash 里的文件"这一种删除动作，不会碰用户图库里的原图。
 */
object TrashCleanupScheduler {

    private const val WORK_NAME_ONCE = "eropic-trash-cleanup-once"
    private const val WORK_NAME_PERIODIC = "eropic-trash-cleanup-periodic"

    fun requestImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<TrashCleanupWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME_ONCE, ExistingWorkPolicy.REPLACE, request)
    }

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.NONE)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK_NAME_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
