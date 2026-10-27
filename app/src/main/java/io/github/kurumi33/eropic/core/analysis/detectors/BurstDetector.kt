package io.github.kurumi33.eropic.core.analysis.detectors

import io.github.kurumi33.eropic.core.database.entity.MediaEntity

/** 一组疑似连拍：同目录、彼此相邻、间隔都在阈值内，且至少 3 张。 */
data class BurstGroup(
    val members: List<MediaEntity>,
    val startMs: Long,
    val endMs: Long,
)

data class BurstResult(
    val groups: List<BurstGroup>,
    val hitCount: Int,
    /** 每组保留一张（字节最大的）时可释放的字节数。 */
    val reclaimableBytes: Long,
)

/**
 * 连拍检测（规划 B-04 的补充项）。
 *
 * 判定只看时间与目录，不看画面：连拍的一组照片本来就长得像，
 * 但仍可能是用户刻意留下的动作分解，所以这里只给"候选"，绝不自动保留/删除。
 */
class BurstDetector(private val detectionDao: DetectionDao) {

    /**
     * [gapMs] 内、同目录、连续 >= [MIN_MEMBERS] 张算一组。
     *
     * [sinceMs] 默认只看最近三年：再老的连拍用户大概率已经处理过，全部扫一遍没有收益。
     */
    suspend fun find(
        limit: Int = CANDIDATE_LIMIT,
        gapMs: Long = DEFAULT_GAP_MS,
        sinceMs: Long = System.currentTimeMillis() - WINDOW_MS,
    ): BurstResult {
        val candidates = detectionDao.burstCandidates(sinceMs, limit)
        val groups = ArrayList<BurstGroup>()
        var start = 0
        for (index in 1..candidates.size) {
            val previous = candidates[index - 1]
            val current = candidates.getOrNull(index)
            val sameRun = current != null &&
                current.bucketName == previous.bucketName &&
                current.dateTakenMs - previous.dateTakenMs in 0..gapMs
            if (!sameRun) {
                val run = candidates.subList(start, index)
                if (run.size >= MIN_MEMBERS) {
                    groups += BurstGroup(
                        members = run,
                        startMs = run.first().dateTakenMs,
                        endMs = run.last().dateTakenMs,
                    )
                }
                start = index
            }
        }
        val hitCount = groups.sumOf { it.members.size }
        val reclaimable = groups.sumOf { group ->
            val keep = group.members.maxOf { it.sizeBytes }
            group.members.sumOf { it.sizeBytes } - keep
        }
        return BurstResult(
            groups = groups.sortedByDescending { it.members.size },
            hitCount = hitCount,
            reclaimableBytes = reclaimable,
        )
    }

    companion object {
        /** 连拍通常一秒多张，5 秒足够覆盖；再放大就会把"随手拍两张"也算进来。 */
        const val DEFAULT_GAP_MS = 5_000L
        const val MIN_MEMBERS = 3
        private const val CANDIDATE_LIMIT = 20_000
        private const val WINDOW_MS = 3L * 365 * 24 * 60 * 60 * 1000
    }
}
