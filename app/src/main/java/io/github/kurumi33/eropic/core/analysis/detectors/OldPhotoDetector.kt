package io.github.kurumi33.eropic.core.analysis.detectors

import io.github.kurumi33.eropic.core.database.entity.MediaEntity

/**
 * 久远照片检测（规划 B-05）：拍摄时间早于 N 年、且没有任何收藏标记。
 *
 * 为什么不用"最后访问时间"：Android 没有可靠的 last-access 字段，
 * 从 MediaStore 拿到的 DATE_MODIFIED 是文件修改时间，移动/重命名就会变，
 * 用它判断"很久没看"会误杀（规划里也明确排除了这个信号）。
 *
 * 为什么必须排除收藏：收藏是用户唯一显式表达"别删"的动作，
 * 老照片恰好是最常被收藏的，漏掉这一条会给出很危险的清理建议。
 */
class OldPhotoDetector(private val dao: DetectionDao) {

    /**
     * @param nowMs 当前时间（epoch 毫秒）。显式传入而不是内部取系统时间，方便单测固定时间点。
     * @param thresholdDays 判定"久远"的天数，默认 3 年。
     */
    suspend fun find(
        nowMs: Long,
        thresholdDays: Int = DEFAULT_THRESHOLD_DAYS,
        limit: Int = DEFAULT_LIMIT,
    ): List<MediaEntity> = dao.oldPhotos(cutoffMs(nowMs, thresholdDays), limit.coerceIn(1, MAX_LIMIT))

    companion object {
        const val DAY_MS: Long = 24L * 60 * 60 * 1000

        /** 3 年按 365 天算：闰年误差最多一天，对"三年以上的老照片"这种粗筛没有意义，反而更好解释。 */
        const val DEFAULT_THRESHOLD_DAYS: Int = 3 * 365
        const val DEFAULT_LIMIT: Int = 200
        const val MAX_LIMIT: Int = 2000

        /** 上限 100 年：防止调用方传 Int.MAX_VALUE 时乘法溢出成负数，截止时间跑到未来。 */
        const val MAX_THRESHOLD_DAYS: Int = 100 * 365

        /** 截止时间：严格小于它的拍摄时间才算久远。 */
        fun cutoffMs(nowMs: Long, thresholdDays: Int): Long {
            val days = thresholdDays.coerceIn(0, MAX_THRESHOLD_DAYS).toLong()
            return nowMs - days * DAY_MS
        }
    }
}
