package io.github.kurumi33.eropic

import android.app.Application
import io.github.kurumi33.eropic.core.sync.MediaChangeObserver
import io.github.kurumi33.eropic.core.sync.SyncScheduler
import io.github.kurumi33.eropic.core.thumb.ThumbnailCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EropicApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    /** 进程级协程作用域：只放"跟随应用生命周期"的轻量工作，不阻塞启动。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 1) 设置里的缩略图缓存开关/上限同步给缓存层；上限调小时立刻裁剪到新上限。
        appScope.launch {
            container.settingsRepository.settings.collect { settings ->
                val cache: ThumbnailCache = container.thumbnailCache
                val limit = settings.thumbnailCacheMb.toLong() * 1024L * 1024L
                val limitShrunk = limit < cache.limitBytes()
                cache.configure(settings.thumbnailCacheEnabled, limit)
                if (limitShrunk) runCatching { cache.trim() }
            }
        }

        // 2) 系统相册变化时防抖触发增量对账（进程存活期间有效）。
        MediaChangeObserver.start(this)

        // 3) 周期兜底：进程被回收期间新增/删除的照片由它补齐（12h）。
        SyncScheduler.ensurePeriodic(this)

        // 4) 冷启动立刻补一次对账，避免"刚拍的照片要等 12 小时才出现"。
        SyncScheduler.requestImmediate(this)
    }
}
