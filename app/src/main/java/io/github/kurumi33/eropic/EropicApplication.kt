package io.github.kurumi33.eropic

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.kurumi33.eropic.core.log.CrashLogger
import io.github.kurumi33.eropic.core.notify.TrashCleanupScheduler
import io.github.kurumi33.eropic.core.notify.TrashNotifications
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

    /** 前台 Activity 计数：用来判断"是不是用户打开了应用"，而不是后台任务把进程唤醒。 */
    private var startedActivities = 0

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

        // 5) 自动续算：只在用户真的打开应用时触发，不能在后台任务（WorkManager 唤醒进程）时
        //    擅自跑全量指纹计算——那是分钟级的解码工作，会被系统当成异常耗电。
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) {
                    appScope.launch { maybeResumeInterruptedIndex() }
                    appScope.launch { maintainTrash() }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // 6) 每天兜底清理一次：进程不在时也要能按时清理过期条目。
        TrashCleanupScheduler.ensurePeriodic(this)
    }

    /**
     * 回收站维护：按设置里的保留期重算到期时间，再清理真正过期的条目。
     *
     * 重算是必须的：到期时间在"移入回收站"时写死，用户之后把保留期从 30 天改成"永久保留"，
     * 旧条目否则仍会按 30 天被自动永久删除（那是真丢图，见 ADR-0019）。
     */
    private suspend fun maintainTrash() {
        runCatching {
            val settings = container.settingsRepository.settings.first()
            if (StorageAccess.hasAllFilesAccess()) {
                container.trashRepository.applyRetention(settings.retention.days)
                container.trashRepository.cleanupExpired()
            }
            // 顺带检查"即将到期"，有的话发一条通知提醒用户决定恢复还是让它到期。
            val expiring = container.trashRepository.expiringSoon()
            if (expiring.isNotEmpty()) {
                TrashNotifications.notifyExpiring(this@EropicApplication, expiring.size)
            }
            // 操作历史只留最近 90 天：operation_log 只用于撤销/回顾，没有长期价值。
            container.database.operationLogDao().clearBefore(
                System.currentTimeMillis() - OPERATION_LOG_RETENTION_MS,
            )
        }
    }

    /**
     * 判断上次索引是否被中途打断，是则自动续算。
     *
     * 判据：已经算过指纹（countHashed > 0）且还有没算的（countNeedingSignature > 0）。
     * 全新安装（countHashed == 0）不会在这里擅自开始全量扫描——那是用户主动点的"开始建立索引"。
     * 用户可在设置里关掉"启动时自动续算"。
     *
     * v0.7：指纹先写待落盘表，所以要先把上次遗留的合并回主表——否则它们既不算"已算过"、
     * 又不会出现在"待算"里，续算判断会误以为没有工作。
     */
    private suspend fun maybeResumeInterruptedIndex() {
        val settings = runCatching { container.settingsRepository.settings.first() }.getOrNull() ?: return
        if (!settings.autoResumeIndex) return
        val coordinator = container.indexCoordinator
        if (coordinator.state.value.isRunning) return
        runCatching { container.mediaRepository.flushPendingSignatures() }
        val hashed = runCatching { container.mediaRepository.countHashed() }.getOrDefault(0)
        if (hashed <= 0) return
        val needing = runCatching { container.mediaRepository.countNeedingSignature() }.getOrDefault(0)
        if (needing > 0) coordinator.start(similarity = settings.similarity, scope = settings.scanScope())
    }

    companion object {
        private const val OPERATION_LOG_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
    }
}
