package io.github.kurumi33.eropic.core.analysis.detectors

import io.github.kurumi33.eropic.core.database.entity.MediaEntity

/**
 * 小图检测：面积小于阈值的图片（表情包、图标、聊天里存下来的缩略图等）。
 *
 * 阈值默认 200x200 = 4 万像素：低于它的图放大后一定糊，留着几乎没有保留价值，
 * 但依然只作为候选展示，删除/保留由用户决定。
 */
class SmallImageDetector(private val detectionDao: DetectionDao) {

    suspend fun find(
        maxPixels: Int = DEFAULT_MAX_PIXELS,
        limit: Int = DEFAULT_LIMIT,
    ): List<MediaEntity> = detectionDao.smallImages(maxPixels, limit)

    companion object {
        const val DEFAULT_MAX_PIXELS = 200 * 200
        private const val DEFAULT_LIMIT = 500
    }
}
