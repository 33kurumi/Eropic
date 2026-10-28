package io.github.kurumi33.eropic.core.analysis.detectors

import io.github.kurumi33.eropic.core.database.entity.MediaEntity

/**
 * 大图检测（v0.3）：按分辨率（宽 × 高）从大到小。
 *
 * 与 [LargeFileDetector] 的分工：
 * - 大文件 = 占空间多（MB），用于"腾空间"；
 * - 大图 = 像素多（分辨率），用于"找出那些特别大的照片/长截图/全景图"。
 * 一张 12MP 截图可能只有 800KB，一张 8MP 照片可能有 6MB，两者不能互相替代，
 * 所以用户把"小图"这一分类换成了"大图"。
 *
 * 默认阈值 800 万像素（约 3264×2448）：低于它的手机照片不算"大图"；
 * 8MP 也是 2015 年前后主流手机的主摄水平，再低就是压缩过或裁过的。
 */
class LargeImageDetector(private val detectionDao: DetectionDao) {

    suspend fun find(
        minPixels: Int = DEFAULT_MIN_PIXELS,
        limit: Int = DEFAULT_LIMIT,
    ): List<MediaEntity> = detectionDao.largeImages(
        minPixels = minPixels.coerceIn(1, Int.MAX_VALUE),
        limit = limit.coerceIn(1, MAX_LIMIT),
    )

    /** 全库命中数与总字节（不按 LIMIT 截断，供清理报告用）。 */
    suspend fun summary(minPixels: Int = DEFAULT_MIN_PIXELS): LargeFileSummaryRow =
        detectionDao.largeImageSummary(minPixels.coerceIn(1, Int.MAX_VALUE))

    companion object {
        /** 800 万像素。 */
        const val DEFAULT_MIN_PIXELS = 8_000_000
        private const val DEFAULT_LIMIT = 500
        private const val MAX_LIMIT = 2000
    }
}
