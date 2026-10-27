package io.github.kurumi33.eropic.core.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.kurumi33.eropic.EropicApplication
import kotlinx.coroutines.CancellationException

/**
 * 后台增量对账：只做 MediaStore → Room 的同步，不做指纹和去重。
 *
 * 指纹要解码整张图，10 万张是分钟级开销，塞进后台任务会被系统判成异常耗电；
 * 那部分仍由用户在前台触发 [io.github.kurumi33.eropic.core.index.IndexCoordinator] 完成。
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 没有读取权限时直接算成功：失败会让 WorkManager 按退避策略反复重试，
        // 而权限只有用户去系统设置里授权才会变，重试永远等不到结果，只是白耗电。
        if (!hasMediaReadAccess()) return Result.success()

        val container = (applicationContext as? EropicApplication)?.container
            ?: return Result.failure()

        return try {
            // 后台对账必须沿用设置里的扫描范围（隐藏目录/排除目录），
            // 否则后台一次同步会把用户排除掉的目录又收进索引。
            val scope = container.settingsRepository.current().scanScope()
            // 后台任务没有进度 UI，进度回调直接丢弃。
            container.mediaRepository.syncFromMediaStore(scope) { _, _ -> }
            Result.success()
        } catch (e: CancellationException) {
            // REPLACE 策略会取消正在跑的旧任务；被取消不是失败，原样抛出交给框架处理。
            throw e
        } catch (e: Exception) {
            // 关键文件权限这类错误重试再多次也不会自己好，超过 3 次就放弃，等下次前台同步。
            if (runAttemptCount >= MAX_ATTEMPTS) {
                Log.w(TAG, "后台同步连续失败，放弃本次", e)
                Result.failure()
            } else {
                Log.w(TAG, "后台同步失败，稍后重试", e)
                Result.retry()
            }
        }
    }

    /**
     * Android 14 起用户可能只授权"部分照片"，此时 MediaStore 仍能查到被选中的那几张，
     * 所以部分授权也允许跑同步（syncFromMediaStore 已处理"查不到时不要误清空索引"）；
     * 只有完全没授权才跳过。
     */
    private fun hasMediaReadAccess(): Boolean {
        val context = applicationContext
        val full = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_IMAGES,
        ) == PackageManager.PERMISSION_GRANTED
        if (full) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val MAX_ATTEMPTS = 3
    }
}
