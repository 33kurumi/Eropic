package io.github.kurumi33.eropic.core.analysis.detectors

import io.github.kurumi33.eropic.core.database.entity.MediaEntity

/**
 * 模糊图检测（规划 B-07）：拉普拉斯方差 < 阈值。
 *
 * 阈值 100 来自规划里的经验值（按机型需要校准），不能再往上调：
 * 夜景、逆光人像的方差也常年低于 150，阈值抬高会把正常照片批量标成模糊。
 *
 * 已知误报：纯色壁纸、大面积留白的构图、低对比度文字长截图，它们的方差本来就很低；
 * 当前扫描链路对所有图片都算清晰度（SignatureGenerator 不区分截图），
 * 所以这批建议里会混入截图，UI 必须在文案里提示"模糊是相对判据，请逐张复核"。
 *
 * blurScore 是 Double（MediaEntity 字段），因此这里的阈值也用 Double，避免 Float->Double 的精度偏差。
 */
class BlurDetector(private val dao: DetectionDao) {

    /** 只读查询；threshold 参数化是为了按机型校准，而不是让调用方随手改 UI。 */
    suspend fun find(
        threshold: Double = DEFAULT_BLUR_THRESHOLD,
        limit: Int = DEFAULT_LIMIT,
    ): List<MediaEntity> = dao.blurryImages(threshold.coerceAtLeast(0.0), limit.coerceIn(1, MAX_LIMIT))

    companion object {
        const val DEFAULT_BLUR_THRESHOLD: Double = 100.0
        const val DEFAULT_LIMIT: Int = 500
        const val MAX_LIMIT: Int = 2000
    }
}
