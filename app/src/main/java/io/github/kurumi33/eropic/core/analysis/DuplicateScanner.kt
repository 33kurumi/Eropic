package io.github.kurumi33.eropic.core.analysis

import android.content.ContentResolver
import io.github.kurumi33.eropic.core.database.dao.DuplicateDao
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
) {

    suspend fun scanExact(): ExactScanResult = withContext(Dispatchers.IO) {
        // 先删成员再删组：clearMembersOfKind 的子查询依赖 duplicate_group 行还存在，
        // 顺序反了就会留下永远没人清理的孤儿成员行（表只增不减）。
        duplicateDao.clearMembersOfKind(KIND_EXACT)
        duplicateDao.clearGroupsOfKind(KIND_EXACT)
        val bySize = mediaDao.exactCandidates().groupBy { it.sizeBytes }
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
        duplicateDao.clearMembersOfKind(KIND_NEAR)
        duplicateDao.clearGroupsOfKind(KIND_NEAR)
        val hashed = mediaDao.hashedItems()
        val items = hashed.map { HashItem(id = it.id, dhash = it.dhash ?: 0L, phash = it.phash ?: 0L) }
        val sizes = hashed.associate { it.id to it.sizeBytes }
        val groups = NearDuplicateGrouper.group(items, dThreshold, pThreshold)
        var members = 0
        var reclaimable = 0L
        for (group in groups) {
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
        NearScanResult(groups = groups.size, files = members, reclaimableBytes = reclaimable)
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
    }
}

data class ExactScanResult(val groups: Int, val files: Int, val reclaimableBytes: Long)
data class NearScanResult(val groups: Int, val files: Int, val reclaimableBytes: Long)
