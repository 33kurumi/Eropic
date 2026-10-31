package io.github.kurumi33.eropic.core.analysis

/**
 * [DuplicateScanner.scanNear] 的纯逻辑部分：不碰数据库、不碰 ContentResolver，可直接在 JVM 单测里跑。
 *
 * 三步顺序有讲究：
 * 1. 先剔「保留」的图——它们不参与任何分组，组内成员被剔到只剩 1 张时自然不再成组；
 * 2. 再挑差分图——差分图之间 dHash 往往极近（同一构图的改色/改表情），
 *    若先跑相似分组会被并进相似组，用户就分不清"这是差分"还是"这是重复"；
 * 3. 剩下的才交给近重复聚类。
 *
 * [hashById] 单独传而不塞进 [DiffEntry]：差分判定只用文件名，近重复判定才需要指纹，
 * 让两种职责各自的数据结构保持最小。
 */
internal fun partitionForNear(
    entries: List<DiffEntry>,
    hashById: Map<Long, HashItem>,
    ignored: Set<Long>,
    dThreshold: Int,
    pThreshold: Int,
): NearPartition {
    val kept = if (ignored.isEmpty()) entries else entries.filterNot { it.id in ignored }
    val diffGroups = DiffImageDetector.collect(kept)
    val diffIds = HashSet<Long>(diffGroups.size * 3)
    for (group in diffGroups) {
        for (entry in group) diffIds.add(entry.id)
    }
    // 没有指纹的条目（dhash 为空）本来也进不了近重复分组：hashedItems 已过滤，这里再兜一层
    val nearItems = kept.asSequence()
        .filterNot { it.id in diffIds }
        .mapNotNull { hashById[it.id] }
        .toList()
    return NearPartition(
        diffGroups = diffGroups,
        nearGroups = NearDuplicateGrouper.group(nearItems, dThreshold, pThreshold),
    )
}

/** 近重复扫描的分区结果：差分图组与相似图组分开（v0.9）。 */
internal data class NearPartition(
    val diffGroups: List<List<DiffEntry>> = emptyList(),
    val nearGroups: List<HashGroup> = emptyList(),
)
