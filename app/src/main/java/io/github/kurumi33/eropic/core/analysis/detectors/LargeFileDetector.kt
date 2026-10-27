package io.github.kurumi33.eropic.core.analysis.detectors

import io.github.kurumi33.eropic.core.database.entity.MediaEntity

/**
 * 大文件检测（规划 B-04）：按体积倒序取 Top N。
 *
 * 默认阈值 5 MB：Android 相册里 1~2 MB 的普通照片太多，阈值再低会让列表变成"全部照片"；
 * 5 MB 以上基本是原图、连拍、长视频截图或单反导入，清理收益明显。
 * Top N 默认 200：再多的条目用户也看不完，UI 侧一次渲染这么多缩略图已经接近上限。
 */
class LargeFileDetector(private val dao: DetectionDao) {

    /** 只读查询：返回列表 + 命中集合的真实字节合计，不改动任何数据。 */
    suspend fun find(
        topN: Int = DEFAULT_TOP_N,
        minSizeBytes: Long = DEFAULT_MIN_SIZE_BYTES,
    ): LargeFileResult {
        val limit = effectiveTopN(topN)
        val minBytes = effectiveMinSizeBytes(minSizeBytes)
        val items = dao.largeFiles(minBytes, limit)
        val summary = dao.largeFileSummary(minBytes)
        return LargeFileResult(
            items = items,
            hitCount = summary.hits,
            reclaimableBytes = summary.bytes,
        )
    }

    companion object {
        const val DEFAULT_TOP_N: Int = 200
        const val DEFAULT_MIN_SIZE_BYTES: Long = 5L * 1024 * 1024

        /** 上限保护：LIMIT 过大等于退回全表扫描，内存和渲染都撑不住。 */
        const val MAX_TOP_N: Int = 2000

        /** topN 传 0 或负数会让 SQL 的 LIMIT 语义变成"无限制"，这里回落到默认值。 */
        fun effectiveTopN(topN: Int): Int = if (topN <= 0) DEFAULT_TOP_N else topN.coerceAtMost(MAX_TOP_N)

        fun effectiveMinSizeBytes(minSizeBytes: Long): Long = minSizeBytes.coerceAtLeast(0L)
    }
}

/** [LargeFileDetector.find] 的结果。 */
data class LargeFileResult(
    /** Top N 列表（已按体积倒序）。 */
    val items: List<MediaEntity>,
    /** 满足阈值（不限 Top N）的条目总数。 */
    val hitCount: Long,
    /** 满足阈值的条目总字节，供 UI 显示"最多可释放"。 */
    val reclaimableBytes: Long,
) {

    /** 当前列表自身的字节合计；用户只勾选前若干张时用它估算，不重复查库。 */
    val topBytes: Long get() = items.sumOf { it.sizeBytes }
}
