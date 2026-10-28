package io.github.kurumi33.eropic.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 同步任务的唯一入口：手动触发、相册变化、周期兜底都收敛到 WorkManager 的唯一任务名上，
 * 保证系统里同时最多只有一个待跑的同步任务。
 *
 * 不设任何约束（尤其不设网络约束；INTERNET 只给「联网搜图」用，对账本身不联网）：对账是本地只读查询 + 短时写库，
 * 加低电量/充电约束反而会让周期任务长期不执行，索引一直过期。
 */
object SyncScheduler {

    private const val WORK_NAME_ONCE = "eropic-sync-once"
    private const val WORK_NAME_PERIODIC = "eropic-sync-periodic"

    /** 进程被回收期间的相册变化只能靠周期任务兜底；12 小时是"索引不至于明显过期"和"别太费电"的折中。 */
    private const val PERIODIC_INTERVAL_HOURS = 12L

    /** 失败多半是 MediaStore 暂时不可用（系统正在扫描）或数据库忙，10 分钟起指数退避足够，不必每分钟重试。 */
    private const val BACKOFF_MINUTES = 10L

    /**
     * 立即对账一次。REPLACE 会打断正在跑的旧一次性任务：对账本身幂等，
     * 且中断只会留下半程的 upsert（缺席标记在末尾统一做），下一次完整的扫描会自我修正。
     */
    fun requestImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME_ONCE, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * 保证 12 小时周期的兜底同步存在。用 KEEP 而不是 REPLACE：每次 App 启动都重建周期任务
     * 会让计时器不断重置，任务可能永远等不到执行。
     */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK_NAME_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
