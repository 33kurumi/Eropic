package io.github.kurumi33.eropic.core.sync

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 监听系统相册变化，防抖后交给 [SyncScheduler] 做一次增量对账。
 *
 * 为什么不绑 Activity 生命周期：截图、相机、其他 App 存图大多发生在 Eropic 退到后台之后，
 * 绑 Activity 恰好会漏掉最该同步的时机。代价是进程被回收后监听随之消失，
 * 冷启动期间的空白由 [SyncScheduler.ensurePeriodic] 的周期任务兜底。
 */
object MediaChangeObserver {

    private const val TAG = "MediaChangeObserver"

    /** 一次批量落盘会连发几十个 onChange；1.5s 足以把一波变化并成一次扫描，又不会让人觉得"同步很慢"。 */
    private const val DEBOUNCE_MS = 1500L

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            // 后台协程的异常没人接会直接杀掉进程；自动同步是尽力而为的任务，失败只记日志。
            Log.w(TAG, "自动同步触发失败", e)
        },
    )

    private val lock = Any()

    @Volatile
    private var active: Active? = null

    /**
     * 幂等：重复调用只保留第一次注册。未授权或该机型不允许监听整个相册集合时静默降级，
     * 保证在 Application.onCreate 里调用绝不会抛异常。
     */
    fun start(context: Context) {
        if (active != null) return
        val appContext = context.applicationContext
        synchronized(lock) {
            if (active != null) return
            val debouncer = Debouncer(scope, DEBOUNCE_MS) {
                // 真正的扫描交给 WorkManager：进程退到后台不会被直接掐断，
                // 也能和周期任务共用唯一任务名，避免两边同时扫库。
                SyncScheduler.requestImmediate(appContext)
            }
            val observer = MediaObserver(debouncer)
            val registered = try {
                appContext.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    observer,
                )
                true
            } catch (e: SecurityException) {
                // 部分机型在"仅授权部分照片"时会拒绝监听整个相册集合。那是用户的选择而非错误，
                // 退化成只靠周期任务同步即可，不能影响 App 启动。
                Log.w(TAG, "注册相册监听被拒绝，自动同步降级为周期任务", e)
                false
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "相册 URI 不可监听，自动同步降级为周期任务", e)
                false
            }
            if (registered) active = Active(observer, debouncer)
        }
    }

    /** 与 [start] 配对调用；从未注册成功时什么都不做。 */
    fun stop(context: Context) {
        synchronized(lock) {
            val current = active ?: return
            active = null
            current.debouncer.cancel()
            try {
                context.applicationContext.contentResolver.unregisterContentObserver(current.observer)
            } catch (e: IllegalArgumentException) {
                // 系统可能已因权限回收丢掉了这个监听，重复注销会抛异常，忽略即可。
                Log.w(TAG, "注销相册监听失败", e)
            }
        }
    }

    private class Active(
        val observer: ContentObserver,
        val debouncer: Debouncer,
    )

    /** 显式挂主线程 Handler：注册可以发生在任意线程，回调必须在可预期的线程上只做防抖提交。 */
    private class MediaObserver(private val debouncer: Debouncer) :
        ContentObserver(Handler(Looper.getMainLooper())) {

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            debouncer.submit()
        }

        override fun onChange(selfChange: Boolean) {
            debouncer.submit()
        }
    }
}
