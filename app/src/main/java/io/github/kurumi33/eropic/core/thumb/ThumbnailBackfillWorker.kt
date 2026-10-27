package io.github.kurumi33.eropic.core.thumb

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.kurumi33.eropic.EropicApplication
import kotlinx.coroutines.flow.first

/**
 * 充电时补齐缩略图。逐批取索引里的图片，只为"还缺缩略图"的那些解码写盘。
 * 不修改任何照片，也不删除索引数据，随时可取消。
 */
class ThumbnailBackfillWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as EropicApplication).container
        val settings = container.settingsRepository.settings.first()
        if (!settings.thumbnailCacheEnabled) return Result.success()

        val repository = container.mediaRepository
        val cache = container.thumbnailCache
        var lastId = 0L
        var processed = 0
        var generated = 0
        while (true) {
            if (isStopped) return Result.success()
            val batch = repository.pagePresentItems(afterId = lastId, limit = BATCH_SIZE)
            if (batch.isEmpty()) break
            for (item in batch) {
                lastId = item.id
                processed++
                if (cache.existing(item) != null) continue
                // get() 内部单飞 + 解码，失败返回 null 直接跳过（损坏图不值得反复尝试）。
                if (cache.get(item) != null) generated++
            }
            setProgress(progressData(processed, generated))
        }
        cache.trim()
        return Result.success()
    }

    private fun progressData(processed: Int, generated: Int) =
        androidx.work.workDataOf(
            KEY_PROCESSED to processed,
            KEY_GENERATED to generated,
        )

    companion object {
        private const val BATCH_SIZE = 100
        const val KEY_PROCESSED = "processed"
        const val KEY_GENERATED = "generated"
    }
}
