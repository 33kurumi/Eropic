package io.github.kurumi33.eropic.core.thumb

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * 缩略图补齐任务：只对"已经索引过、但还没有缩略图"的老图片生效。
 *
 * 为什么需要：M1.5 之前建好的索引里没有缩略图，用户不想为了缩略图再跑一遍全量指纹；
 * 这里在充电时慢慢补，默认设置里可以关掉。
 */
object ThumbnailBackfill {

    private const val UNIQUE_NAME = "eropic-thumbnails-backfill"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<ThumbnailBackfillWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}
