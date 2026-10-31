package io.github.kurumi33.eropic.core.analysis

import android.content.ContentResolver
import io.github.kurumi33.eropic.core.database.dao.DuplicateDao
import io.github.kurumi33.eropic.core.database.dao.IgnoredDao
import io.github.kurumi33.eropic.core.database.dao.MediaDao
import io.github.kurumi33.eropic.core.database.entity.DuplicateGroupEntity
import io.github.kurumi33.eropic.core.database.entity.DuplicateMemberEntity
import io.github.kurumi33.eropic.core.hash.Hashing
import io.github.kurumi33.eropic.core.hash.Sha256Util
import io.github.kurumi33.eropic.core.media.MediaUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 重复扫描（只读）：只负责"找出候选并分组"，绝不改动用户文件。
 *
 * 精确重复：大小分组 → 头尾 64KB 快速哈希 → 全文件 SHA-256。
 * 近重复：dHash/pHash 倒排索引分组，dHash ≤10 且 pHash ≤8。
 */
class DuplicateScanner(
    private val contentResolver: ContentResolver,
    private val mediaDao: MediaDao,
    private val duplicateDao: DuplicateDao,
    /**
     * 「保留」名单（v0.9）：用户手动标记过「这张别动」的图，不参与任何分组。
     * 放最后一个参数并给 AppContainer 留出显式装配点：这个依赖是新增的，顺序保持能让 diff 一眼看出。
     */
    private val ignoredDao: IgnoredDao,
) {

    suspend fun scanExact(): ExactScanResult = withContext(Dispatchers.IO) {
        // 先删成员再删组：clearMembersOfKind 的子查询依赖 duplicate_group 行还存在，
        // 顺序反了就会留下永远没人清理的孤儿成员行（表只增不减）。
        duplicateDao.clearMembersOfKind(KIND_EXACT)
        duplicateDao.clearGroupsOfKind(KIND_EXACT)
        // 被「保留」的图不参与分组：先过滤候选，后面的快速/全量哈希就完全不会读到它们。
        val ignored = ignoredDao.allIds().toSet()
        val bySize = mediaDao.exactCandidates()
            .filterNot { it.id in ignored }
            .groupBy { it.sizeBytes }
        var groups = 0
        var files = 0
        var reclaimable = 0L

        for ((_, sameSize) in bySize) {
            if (sameSize.size < 2) continue
            // 先用"大小 + 头尾哈希"把绝大多数不同文件排除。
            val byQuick = HashMap<String, MutableList<Long>>()
            for (candidate in sameSize) {
                val quick = runCatching {
                    Sha256Util.quick(contentResolver, MediaUris.of(candidate.id), candidate.sizeBytes)
                }.getOrNull() ?: continue
                byQuick.getOrPut(quick) { ArrayList(2) }.add(candidate.id)
            }
            for ((_, quickIds) in byQuick) {
                if (quickIds.size < 2) continue
                val byFull = HashMap<String, MutableList<Long>>()
                for (id in quickIds) {
                    val full = runCatching {
                        Sha256Util.full(contentResolver, MediaUris.of(id))
                    }.getOrNull() ?: continue
                    byFull.getOrPut(full) { ArrayList(2) }.add(id)
                    mediaDao.updateSha(id, full)
                }
                for ((_, fullIds) in byFull) {
                    if (fullIds.size < 2) continue
                    persistGroup(kind = KIND_EXACT, ids = fullIds, maxDistance = 0)
                    groups++
                    files += fullIds.size
                    reclaimable += reclaimableBytes(fullIds)
                }
            }
        }
        ExactScanResult(groups = groups, files = files, reclaimableBytes = reclaimable)
    }

    suspend fun scanNear(
        dThreshold: Int = NearDuplicateGrouper.DEFAULT_DHASH_THRESHOLD,
        pThreshold: Int = NearDuplicateGrouper.DEFAULT_PHASH_THRESHOLD,
    ): NearScanResult = withContext(Dispatchers.IO) {
        // 成员表与组表各自成对清理；NEAR 与 DIFF 两种 kind 互不影响，都要清。
        // 顺序仍然是「先成员后组」：clearMembersOfKind 的子查询依赖组行还在。
        duplicateDao.clearMembersOfKind(KIND_NEAR)
        duplicateDao.clearGroupsOfKind(KIND_NEAR)
        duplicateDao.clearMembersOfKind(KIND_DIFF)
        duplicateDao.clearGroupsOfKind(KIND_DIFF)

        val ignored = ignoredDao.allIds().toSet()
        val hashed = mediaDao.hashedItems()
        val entries = hashed.map { DiffEntry(id = it.id, displayName = it.displayName, sizeBytes = it.sizeBytes) }
        val hashById = hashed.mapNotNull { item ->
            val dhash = item.dhash ?: return@mapNotNull null
            item.id to HashItem(id = item.id, dhash = dhash, phash = item.phash ?: 0L)
        }.toMap()
        val sizes = hashed.associate { it.id to it.sizeBytes }

        // 分区是纯函数（可单测）：先剔「保留」的图，再挑出差分图，剩下的才进近重复分组。
        val partition = partitionForNear(entries, hashById, ignored, dThreshold, pThreshold)

        var diffGroups = 0
        var diffMembers = 0
        for (group in partition.diffGroups) {
            val ids = group.map { it.id }
            // 差分图不靠汉明距离分组，maxDistance 固定 0，避免 UI 把它显示成「相似度 0」
            persistGroup(kind = KIND_DIFF, ids = ids, maxDistance = 0, sizeById = sizes)
            diffGroups++
            diffMembers += ids.size
        }

        var members = 0
        var reclaimable = 0L
        for (group in partition.nearGroups) {
            val ids = group.members.map { it.id }
            persistGroup(
                kind = KIND_NEAR,
                ids = ids,
                maxDistance = group.maxDistance,
                sizeById = sizes,
            )
            members += ids.size
            reclaimable += reclaimableBytes(ids, sizes)
        }
        NearScanResult(
            groups = partition.nearGroups.size,
            files = members,
            reclaimableBytes = reclaimable,
            diffGroups = diffGroups,
            diffFiles = diffMembers,
        )
    }

    private suspend fun persistGroup(
        kind: String,
        ids: List<Long>,
        maxDistance: Int,
        sizeById: Map<Long, Long> = emptyMap(),
    ) {
        val groupId = duplicateDao.insertGroup(
            DuplicateGroupEntity(
                kind = kind,
                maxDistance = maxDistance,
                reclaimableBytes = reclaimableBytes(ids, sizeById),
                memberCount = ids.size,
                createdAtMs = System.currentTimeMillis(),
            ),
        )
        duplicateDao.insertMembers(
            ids.map { DuplicateMemberEntity(groupId = groupId, mediaId = it, distance = maxDistance) },
        )
    }

    private suspend fun reclaimableBytes(ids: List<Long>, sizeById: Map<Long, Long> = emptyMap()): Long {
        val sizes = if (sizeById.isNotEmpty()) ids.map { sizeById[it] ?: 0L } else ids.map { id ->
            mediaDao.byIds(listOf(id)).firstOrNull()?.sizeBytes ?: 0L
        }
        val total = sizes.sum()
        val keep = sizes.maxOrNull() ?: 0L
        return (total - keep).coerceAtLeast(0L)
    }

    companion object {
        const val KIND_EXACT = "EXACT"
        const val KIND_NEAR = "NEAR"

        /** v0.9：差分图（同 base、只差 p0/p1 后缀）。单独一种 kind，默认不进「重复」统计。 */
        const val KIND_DIFF = "DIFF"
    }
}

data class ExactScanResult(val groups: Int, val files: Int, val reclaimableBytes: Long)

/**
 * 近重复扫描结果。
 *
 * [diffGroups] / [diffFiles] 是 v0.9 新增的差分图统计，与 [groups]（相似组）分开计数：
 * 差分图通常每张都要留，把它算进"可释放"会高估空间、诱导误删。
 * 两个新字段都给默认值，旧的调用处（IndexCoordinator 等）不用改就能编译。
 */
data class NearScanResult(
    val groups: Int,
    val files: Int,
    val reclaimableBytes: Long,
    val diffGroups: Int = 0,
    val diffFiles: Int = 0,
)

