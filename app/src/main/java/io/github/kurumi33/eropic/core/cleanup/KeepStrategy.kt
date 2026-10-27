package io.github.kurumi33.eropic.core.cleanup

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
 * [reclaimableBytes] 为这些待删除图片的字节数之和。
 */
data class KeepSuggestion(
    val keepIds: List<Long>,
    val trashIds: List<Long>,
    val reclaimableBytes: Long,
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

        val ordered = items.sortedWith(keepFirstOrder(strategy))
        val kept = ordered.first()
        val trashed = ordered.drop(1)
        return KeepSuggestion(
            keepIds = listOf(kept.id),
            trashIds = trashed.map { it.id },
            reclaimableBytes = trashed.sumOf { it.sizeBytes },
        )
    }

    /** 越靠前越应该保留；并列时统一按 id 升序，避免结果依赖输入顺序。 */
    private fun keepFirstOrder(strategy: KeepStrategy): Comparator<KeepCandidate> = when (strategy) {
        KeepStrategy.LARGEST -> compareByDescending<KeepCandidate> { it.sizeBytes }.thenBy { it.id }
        KeepStrategy.SMALLEST -> compareBy<KeepCandidate> { it.sizeBytes }.thenBy { it.id }
        KeepStrategy.OLDEST -> compareBy<KeepCandidate> { it.dateTakenMs }.thenBy { it.id }
        KeepStrategy.NEWEST -> compareByDescending<KeepCandidate> { it.dateTakenMs }.thenBy { it.id }
        KeepStrategy.SHORTEST_PATH -> compareBy<KeepCandidate> { it.path.length }.thenBy { it.id }
        // 上面已提前返回 NONE，这里只是让 when 穷尽。
        KeepStrategy.NONE -> compareBy { it.id }
    }
}
