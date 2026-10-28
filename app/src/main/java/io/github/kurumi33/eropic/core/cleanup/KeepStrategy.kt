package io.github.kurumi33.eropic.core.cleanup

import io.github.kurumi33.eropic.core.database.entity.MediaEntity

/**
 * 一组重复/相似图片的保留策略。
 *
 * 只决定「哪一张留下」，不代表会自动删除：调用方拿到建议后仍需用户确认。
 */
enum class KeepStrategy(val label: String) {
    /** 保留体积最大的一张，通常画质最好。 */
    LARGEST("保留体积最大"),

    /** 保留体积最小的一张，适合只想留个记录的场景。 */
    SMALLEST("保留体积最小"),

    /** 保留拍摄时间最早的一张。 */
    OLDEST("保留最早拍摄"),

    /** 保留拍摄时间最新的一张。 */
    NEWEST("保留最新拍摄"),

    /** 保留路径最短的一张（通常是层次更浅、更靠近相册根目录的那张）。 */
    SHORTEST_PATH("保留路径最短"),

    /** 综合像素、格式、bpp、时间、文件名与清晰度打分，保留质量最高的一张。 */
    BEST_QUALITY("按质量综合评分"),

    /** 不自动选择，留给用户手动勾选。 */
    NONE("不自动选择"),
}

/** 参与保留建议的单张图片。纯 Kotlin，无 Android 依赖，可直接单测。 */
data class KeepCandidate(
    val id: Long,
    val sizeBytes: Long,
    val dateTakenMs: Long,
    val path: String,
)

/**
 * 一次保留建议的结果。
 *
 * [keepIds] 至多 1 项；[trashIds] 是其余待移入回收站的图片；
 * [reclaimableBytes] 为这些待删除图片的字节数之和；
 * [rationale] 是按 [KeepStrategy.BEST_QUALITY] 评分时的人类可读依据（其它策略为空）。
 */
data class KeepSuggestion(
    val keepIds: List<Long>,
    val trashIds: List<Long>,
    val reclaimableBytes: Long,
    val rationale: List<String> = emptyList(),
)

/** 根据保留策略为「每组只留 1 张」生成建议。纯逻辑，不读写文件。 */
object KeepSuggester {

    /**
     * 为一组候选图片生成保留建议。
     *
     * - 策略为 [KeepStrategy.NONE] 或列表为空时不做选择，返回空建议；
     * - 否则按策略挑出唯一保留项，其余全部进入待删除；
     * - 指标并列时按 id 升序取第一张，保证同样的输入永远得到同样的结果。
     */
    fun suggest(items: List<KeepCandidate>, strategy: KeepStrategy): KeepSuggestion {
        if (strategy == KeepStrategy.NONE || items.isEmpty()) {
            return KeepSuggestion(keepIds = emptyList(), trashIds = emptyList(), reclaimableBytes = 0L)
        }

        // KeepCandidate 只有体积/时间/路径，没有分辨率和格式，做不了真正的质量评分。
        // 这时退回按体积（通常画质最好）选择，并在 rationale 里说明，避免静默给出错误建议。
        val effective = if (strategy == KeepStrategy.BEST_QUALITY) KeepStrategy.LARGEST else strategy
        val ordered = items.sortedWith(keepFirstOrder(effective))
        val kept = ordered.first()
        val trashed = ordered.drop(1)
        return KeepSuggestion(
            keepIds = listOf(kept.id),
            trashIds = trashed.map { it.id },
            reclaimableBytes = trashed.sumOf { it.sizeBytes },
            rationale = if (strategy == KeepStrategy.BEST_QUALITY) {
                listOf(
                    "缺少分辨率与格式信息，无法做质量综合评分，已按体积近似选择（体积越大通常画质越好）。" +
                        "需要完整评分依据时请改用 suggest(members)。",
                )
            } else {
                emptyList()
            },
        )
    }

    /**
     * 按多因子质量评分（[QualityScorer]）生成建议：质量最高的一张保留，其余移入回收站。
     *
     * 与 [suggest] 的 KeepCandidate 版本不同，这里用完整的 [MediaEntity] 参与评分，
     * 因而 [KeepSuggestion.rationale] 里会带「为什么保留它 / 为什么淘汰其它」的中文依据。
     * 空列表返回空建议；组内只有 1 张时不会产生待删除项。
     */
    fun suggest(members: List<MediaEntity>): KeepSuggestion = suggestByQuality(members)

    /** [suggest] 的显式命名版本，语义与 [KeepStrategy.BEST_QUALITY] 一致。 */
    fun suggestByQuality(members: List<MediaEntity>): KeepSuggestion {
        if (members.isEmpty()) {
            return KeepSuggestion(keepIds = emptyList(), trashIds = emptyList(), reclaimableBytes = 0L)
        }
        val assessment = QualityScorer.assess(members)
        if (assessment.scores.isEmpty() || assessment.keepMediaId <= 0L) {
            return KeepSuggestion(keepIds = emptyList(), trashIds = emptyList(), reclaimableBytes = 0L)
        }
        val trashIdSet = assessment.trashIds.toSet()
        return KeepSuggestion(
            keepIds = listOf(assessment.keepMediaId),
            trashIds = assessment.trashIds,
            reclaimableBytes = members.filter { it.id in trashIdSet }.sumOf { it.sizeBytes },
            rationale = assessment.rationale,
        )
    }

    /** 越靠前越应该保留；并列时统一按 id 升序，避免结果依赖输入顺序。 */
    private fun keepFirstOrder(strategy: KeepStrategy): Comparator<KeepCandidate> = when (strategy) {
        KeepStrategy.LARGEST -> compareByDescending<KeepCandidate> { it.sizeBytes }.thenBy { it.id }
        KeepStrategy.SMALLEST -> compareBy<KeepCandidate> { it.sizeBytes }.thenBy { it.id }
        KeepStrategy.OLDEST -> compareBy<KeepCandidate> { it.dateTakenMs }.thenBy { it.id }
        KeepStrategy.NEWEST -> compareByDescending<KeepCandidate> { it.dateTakenMs }.thenBy { it.id }
        KeepStrategy.SHORTEST_PATH -> compareBy<KeepCandidate> { it.path.length }.thenBy { it.id }
        // 调用方已把 BEST_QUALITY 换成 LARGEST（KeepCandidate 缺分辨率/格式），这里只是让 when 穷尽。
        KeepStrategy.BEST_QUALITY -> compareByDescending<KeepCandidate> { it.sizeBytes }.thenBy { it.id }
        // 上面已提前返回 NONE，这里只是让 when 穷尽。
        KeepStrategy.NONE -> compareBy { it.id }
    }
}
