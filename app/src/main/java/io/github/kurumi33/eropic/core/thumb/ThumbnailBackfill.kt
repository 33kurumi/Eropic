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
 * 这里在后台慢慢补。是否只在充电时跑由设置 [io.github.kurumi33.eropic.core.settings.AppSettings.thumbnailBackfillChargingOnly]
 * 决定（默认充电时，插电场景下不打扰；关掉后不插电也会补，耗电略高）。
 */
object ThumbnailBackfill {

    private const val UNIQUE_NAME = "eropic-thumbnails-backfill"

    /**
     * 入队一次补齐任务。
     *
     * @param requiresCharging true = 仅充电时执行；false = 不插电也执行（仅受系统后台限制）。
     */
    fun enqueue(context: Context, requiresCharging: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(requiresCharging)
            .build()
        val request = OneTimeWorkRequestBuilder<ThumbnailBackfillWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}
