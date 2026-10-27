package io.github.kurumi33.eropic

import android.app.Application
import io.github.kurumi33.eropic.core.log.CrashLogger
import io.github.kurumi33.eropic.core.notify.TrashCleanupScheduler
import io.github.kurumi33.eropic.core.permissions.StorageAccess
import io.github.kurumi33.eropic.core.sync.MediaChangeObserver
import io.github.kurumi33.eropic.core.sync.SyncScheduler
import io.github.kurumi33.eropic.core.thumb.ThumbnailCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class EropicApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    /** 进程级协程作用域：只放"跟随应用生命周期"的轻量工作，不阻塞启动。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 0) 崩溃日志：只在应用私有目录留最近 10 份，供"关于"页查看，不联网。
        CrashLogger.install(this)

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

        // 5) 自动续算：上次指纹任务被打断（进程被杀/用户退后台被回收）时，启动后接着算，
        //    不用用户手动再点"开始扫描"。见 maybeResumeInterruptedIndex()。
        appScope.launch { maybeResumeInterruptedIndex() }

        // 6) 回收站过期清理：按设置里的保留期清理，超期的文件才真正删除。
        //    这是用户明示的保留策略，不是"自动删照片"；没有所有文件访问权限时跳过。
        appScope.launch {
            runCatching {
                if (StorageAccess.hasAllFilesAccess()) container.trashRepository.cleanupExpired()
                // 顺带检查"即将到期"，有的话发一条通知提醒用户决定恢复还是让它到期。
                val expiring = container.trashRepository.expiringSoon()
                if (expiring.isNotEmpty()) {
                    io.github.kurumi33.eropic.core.notify.TrashNotifications.notifyExpiring(
                        this@EropicApplication,
                        expiring.size,
                    )
                }
            }
        }
        // 7) 每天兜底清理一次：进程不在时也要能按时清理过期条目。
        TrashCleanupScheduler.ensurePeriodic(this)
    }

    /**
     * 判断上次索引是否被中途打断，是则自动续算。
     *
     * 判据：已经算过指纹（countHashed > 0）且还有没算的（countNeedingSignature > 0）。
     * 全新安装（countHashed == 0）不会在这里擅自开始全量扫描——那是用户主动点的"开始建立索引"。
     * 用户可在设置里关掉"启动时自动续算"。
     */
    private suspend fun maybeResumeInterruptedIndex() {
        val settings = runCatching { container.settingsRepository.settings.first() }.getOrNull() ?: return
        if (!settings.autoResumeIndex) return
        val coordinator = container.indexCoordinator
        if (coordinator.state.value.isRunning) return
        val hashed = runCatching { container.mediaRepository.countHashed() }.getOrDefault(0)
        if (hashed <= 0) return
        val needing = runCatching { container.mediaRepository.countNeedingSignature() }.getOrDefault(0)
        if (needing > 0) coordinator.start(similarity = settings.similarity, scope = settings.scanScope())
    }
}