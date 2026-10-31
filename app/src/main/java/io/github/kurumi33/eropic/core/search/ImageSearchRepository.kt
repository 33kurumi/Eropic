package io.github.kurumi33.eropic.core.search

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.hash.ImageSignature
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.core.thumb.BitmapDecoder
import io.github.kurumi33.eropic.core.thumb.ThumbnailCache
import java.util.PriorityQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地「以图搜图」：用一张图的 dHash/pHash 在已建好的索引里找汉明距离最近的图。
 *
 * 三个设计取舍：
 * 1. 不用 `mediaDao.hashedItems()`：它虽然只筛了 dhash 非空，但返回的是完整 [MediaEntity] 列表，
 *    10 万张时是一次性几十 MB 的峰值内存。这里改用 `pagePresentItems` 按 id 键集分页（每批 [PAGE_SIZE] 条），
 *    内存只和「返回条数」有关，和库大小无关。
 * 2. 结果只保留 Top-N（[SimilarityQuery.limit]）：相似度是逐条算出的标量，用小顶堆维护 Top-N 就够了，
 *    没必要把所有命中先攒成 List 再排序。
 * 3. 全程只读：搜索绝不写库（连「顺手把算好的指纹存回去」都不做）。
 *    指纹由索引任务统一维护，搜索时临时算的指纹若写库，会把 signatureVersion 的语义搅乱。
 *
 * v0.7 起多了两层能力（用户要求「本地识图的算法还需要改进，重点是二次元图」）：
 * - **颜色画像精排**：哈希只描述结构，二次元图换配色后距离会很大。对哈希粗筛出来的前 [REFINE_LIMIT] 条
 *   再解码一次算 [ColorProfile]，用 [VisualSimilarity.combinedScore] 重排。
 *   为什么只精排前 N 条：解码是全流程最贵的操作，全库精排等于把「以图搜图」变成几分钟的任务。
 * - **找同角色**：先联网问 SauceNAO / ascii2d「这是谁」，再拿返回的标题当角色线索，
 *   配合本地颜色/结构相似度给出候选集（见 [findCharacterByUri] / [findCharacterByMediaId]）。
 *   联网拿不到角色名时仍然返回本地排序结果，只是 [CharacterMatchResult.message] 会说明这一点。
 */
class ImageSearchRepository(
    context: Context,
    private val database: EropicDatabase,
    /** 缩略图缓存：精排时优先读 320px 缩略图，比每次解码原图快一个数量级；为 null 时退回原图。 */
    private val thumbnailCache: ThumbnailCache? = null,
    /** 联网识图（找同角色用）；为 null 时完全不联网。 */
    private val onlineImageSearch: OnlineImageSearch? = null,
) {

    private val contentResolver = context.contentResolver
    private val mediaDao = database.mediaDao()

    /**
     * 以索引里已有的某张图找相似图（会排除它自己）。
     *
     * 源图没有指纹（signatureVersion = 0，或旧版本只算了 dHash 之外的字段）时，现场解码算一次；
     * 解码失败（比如文件已经不存在、权限被收回）返回空列表，不抛异常——搜索失败不是崩溃级别的问题。
     */
    suspend fun findByMediaId(
        mediaId: Long,
        query: SimilarityQuery = SimilarityQuery(),
    ): List<SimilarImageHit> = withContext(Dispatchers.IO) {
        try {
            val source = mediaDao.byId(mediaId) ?: return@withContext emptyList()
            val signature = signatureOf(source) ?: return@withContext emptyList()
            scan(excludeId = source.id, source = signature, query = query)
        } catch (e: CancellationException) {
            // 协程取消必须原样抛出；被 runCatching 吞掉后 UI 会以为「搜索完成，结果为空」
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 以任意 content Uri（比如刚从系统分享面板收进来的图）找相似图。
     *
     * 没有「排除自身」的概念：外部图片不知道对应库里的哪条记录；
     * 如果它本来就是库里的一张图，那张图会以 100% 相似度排在第一条，这在语义上是对的。
     */
    suspend fun findByUri(
        uri: Uri,
        query: SimilarityQuery = SimilarityQuery(),
    ): List<SimilarImageHit> = withContext(Dispatchers.IO) {
        try {
            val signature = computeSignature(uri) ?: return@withContext emptyList()
            scan(excludeId = EXCLUDE_NONE, source = signature, query = query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 「找同角色」：以库内某张图为准。
     *
     * 流程 = 本地颜色/结构排序（候选放宽到 [CHARACTER_CANDIDATES] 条）+ 联网识别角色名，
     * 两者都只是「给用户看的线索」，最终是否同一角色由用户判断（本 App 从不自动删除/自动判定）。
     */
    suspend fun findCharacterByMediaId(
        mediaId: Long,
        query: SimilarityQuery = SimilarityQuery(),
    ): CharacterMatchResult = withContext(Dispatchers.IO) {
        val entity = runCatching { mediaDao.byId(mediaId) }.getOrNull()
        val sourceUri = entity?.let { MediaUris.of(it.id) }
        characterMatch(
            sourceUri = sourceUri,
            excludeId = mediaId,
            query = query,
            sourceHit = entity?.let { selfHit(it) },
        )
    }

    /** 「找同角色」：以任意 Uri（系统选图/分享进来的图）为准。 */
    suspend fun findCharacterByUri(
        uri: Uri,
        query: SimilarityQuery = SimilarityQuery(),
    ): CharacterMatchResult = withContext(Dispatchers.IO) {
        characterMatch(sourceUri = uri, excludeId = EXCLUDE_NONE, query = query, sourceHit = null)
    }

    private suspend fun characterMatch(
        sourceUri: Uri?,
        excludeId: Long,
        query: SimilarityQuery,
        sourceHit: SimilarImageHit?,
    ): CharacterMatchResult {
        // 候选放宽到 120 条：同角色往往不是「最像的那 10 张」，放宽后由颜色/结构分重新排序
        val widened = query.copy(limit = maxOf(query.limit, CHARACTER_CANDIDATES))
        val localMatches = if (sourceUri != null) {
            if (excludeId == EXCLUDE_NONE) {
                findByUri(sourceUri, widened)
            } else {
                findByMediaId(excludeId, widened)
            }
        } else {
            emptyList()
        }

        val roleHint = runCatching { onlineRoleHint(sourceUri) }.getOrNull()
        val message = when {
            roleHint?.hint != null ->
                "联网识图识别为「" + roleHint.hint + "」，以下按颜色与画面结构相似度排序"
            roleHint?.tried == true ->
                "联网识图没有给出角色名（命中里没有标题），以下按颜色与画面结构相似度排序"
            else ->
                "没有联网（或联网失败），以下只按本地颜色与画面结构相似度排序"
        }
        return CharacterMatchResult(
            source = sourceHit ?: localMatches.firstOrNull(),
            localMatches = localMatches,
            roleHint = roleHint?.hint,
            message = message,
            onlineUsed = roleHint?.tried == true,
        )
    }

    /**
     * 联网识别角色名：依次问 SauceNAO、ascii2d，取第一个非空标题当线索。
     *
     * 为什么只取标题：SauceNAO 的 data.title 通常是角色名/作品名（pixiv 的「キャラ名」tag），
     * ascii2d 的 h6 链接文本是作品页标题；两者都足够给用户一个「这是谁」的提示，
     * 再深的解析（作者、tag 列表）对「找同角色」没有额外收益，反而更容易随页面改版失效。
     */
    private suspend fun onlineRoleHint(uri: Uri?): RoleHint {
        val search = onlineImageSearch ?: return RoleHint(tried = false)
        if (uri == null) return RoleHint(tried = false)
        for (provider in ROLE_HINT_PROVIDERS) {
            val result = runCatching { search.search(uri, provider) }.getOrNull() ?: continue
            if (!result.ok) continue
            val title = result.items.asSequence()
                .mapNotNull { it.title?.trim() }
                .firstOrNull { it.isNotEmpty() }
            if (title != null) return RoleHint(tried = true, hint = title.take(ROLE_HINT_MAX_CHARS))
        }
        return RoleHint(tried = true)
    }

    /** 源图指纹；实体里已有就直接用，省一次解码。 */
    private suspend fun signatureOf(item: MediaEntity): SourceSignature? {
        val dhash = item.dhash
        if (dhash != null) {
            // 指纹在库里，但颜色画像没存过，精排需要源图画像时只能现解码一次（最多少 30ms）
            return SourceSignature(dhash = dhash, phash = item.phash, profile = profileOfEntity(item))
        }
        return computeSignature(MediaUris.of(item.id))
    }

    /**
     * 现场解码算指纹 + 颜色画像。
     *
     * 不复用 [io.github.kurumi33.eropic.core.index.SignatureGenerator]：那个类会顺带写缩略图缓存（有副作用），
     * 而且需要 ThumbnailCache 依赖；搜索只需要「解码 → 算指纹」这两步，直接用底层两个工具类更干净。
     */
    private suspend fun computeSignature(uri: Uri): SourceSignature? {
        val bitmap = BitmapDecoder.decode(contentResolver, uri, ImageSignature.DECODE_LONG_EDGE) ?: return null
        return try {
            ImageSignature.compute(bitmap)?.let {
                SourceSignature(
                    dhash = it.dhash,
                    phash = it.phash,
                    profile = runCatching { VisualSimilarity.profile(bitmap) }.getOrNull(),
                )
            }
        } finally {
            // compute 已经把像素读完了；及时回收，避免后面整库扫描时和候选实体抢内存
            bitmap.recycle()
        }
    }

    /** 候选图的颜色画像：优先用缩略图（小、读起来快），没有缓存再解码原图。 */
    private suspend fun profileOfEntity(item: MediaEntity): ColorProfile? {
        val cached = thumbnailCache?.existing(item)?.takeIf { it.exists() }
        val bitmap = decodeForProfile(item, cached) ?: return null
        return try {
            VisualSimilarity.profile(bitmap)
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun decodeForProfile(item: MediaEntity, cached: java.io.File?): Bitmap? {
        val localUri = cached?.let { Uri.fromFile(it) }
        if (localUri != null) {
            BitmapDecoder.decode(contentResolver, localUri, PROFILE_EDGE)?.let { return it }
        }
        return BitmapDecoder.decode(contentResolver, MediaUris.of(item.id), PROFILE_EDGE)
    }

    /**
     * 按 id 键集分页扫全库，边扫边算距离，用小顶堆维护 Top-N。
     *
     * [excludeId] 传 [EXCLUDE_NONE] 表示不排除任何行。
     */
    private suspend fun scan(
        excludeId: Long,
        source: SourceSignature,
        query: SimilarityQuery,
    ): List<SimilarImageHit> {
        val maxDistance = SimilarityMath.clampDistance(query.maxDistance)
        val limit = SimilarityMath.clampLimit(query.limit)
        val keyword = query.keyword?.trim()?.takeIf { it.isNotEmpty() }

        // 堆顶 = 当前最差的一条（相似度最低；同分时拍摄时间更早的算更差）。
        // 超过 limit 就弹堆顶，最终堆里就是 Top-N，内存 O(limit)。
        val worstFirst = PriorityQueue<SimilarImageHit>(
            limit + 1,
            compareBy<SimilarImageHit> { it.similarity }.thenBy { it.dateTakenMs },
        )

        var afterId = 0L
        while (true) {
            val batch = mediaDao.pagePresentItems(afterId, PAGE_SIZE)
            if (batch.isEmpty()) break
            for (item in batch) {
                val candidateDhash = item.dhash ?: continue
                if (item.id == excludeId) continue
                if (keyword != null &&
                    !SimilarityMath.matchesKeyword(item.displayName, item.relativePath, keyword)
                ) {
                    continue
                }
                val dhashDistance = SimilarityMath.hammingDistance(source.dhash, candidateDhash)
                // 先用 dHash 做粗筛：dHash 对「结构相同」最灵敏，绝大多数行在这一步就被排除，
                // 后面才轮到稍贵的 pHash 计算
                if (dhashDistance > maxDistance) continue

                val candidatePhash = item.phash
                val sourcePhash = source.phash
                // 缺 pHash 时退化成纯 dHash 计分（两个权重和为 1），并把 phashDistance 记成 dhashDistance，
                // 保证 UI 展示的两个距离和 similarity 是同一套口径
                val phashDistance = if (sourcePhash != null && candidatePhash != null) {
                    SimilarityMath.hammingDistance(sourcePhash, candidatePhash)
                } else {
                    dhashDistance
                }

                worstFirst.add(
                    SimilarImageHit(
                        mediaId = item.id,
                        displayName = item.displayName,
                        relativePath = item.relativePath,
                        sizeBytes = item.sizeBytes,
                        width = item.width,
                        height = item.height,
                        mimeType = item.mimeType,
                        dateTakenMs = item.dateTakenMs,
                        dhashDistance = dhashDistance,
                        phashDistance = phashDistance,
                        similarity = SimilarityMath.similarity(dhashDistance, phashDistance),
                    ),
                )
                if (worstFirst.size > limit) worstFirst.poll()
            }
            afterId = batch.last().id
            // 补齐最后一批：pagePresentItems 按 id 升序，不足一批说明已经到底
            if (batch.size < PAGE_SIZE) break
        }

        val base = worstFirst.sortedWith(
            compareByDescending<SimilarImageHit> { it.similarity }.thenByDescending { it.dateTakenMs },
        )
        return refineWithColor(base, source.profile)
    }

    /**
     * 颜色画像精排：只对基础分最高的前 [REFINE_LIMIT] 条解码算画像，然后用综合分重排。
     *
     * 源图没有有效画像（解码失败、全透明）时直接返回基础分结果 —— 精排是「加分项」，不是必需品。
     */
    private suspend fun refineWithColor(
        hits: List<SimilarImageHit>,
        sourceProfile: ColorProfile?,
    ): List<SimilarImageHit> {
        if (hits.size < 2) return hits
        if (sourceProfile == null || sourceProfile.aspectRatio <= 0.0) return hits
        val head = hits.take(REFINE_LIMIT)
        val refined = HashMap<Long, Double>(head.size)
        for (hit in head) {
            val entity = runCatching { mediaDao.byId(hit.mediaId) }.getOrNull() ?: continue
            val candidate = runCatching { profileOfEntity(entity) }.getOrNull() ?: continue
            if (candidate.aspectRatio <= 0.0) continue
            refined[hit.mediaId] = VisualSimilarity.combinedScore(
                dhashDist = hit.dhashDistance,
                phashDist = hit.phashDistance,
                base = sourceProfile,
                candidate = candidate,
            )
        }
        if (refined.isEmpty()) return hits
        return hits
            .map { hit -> refined[hit.mediaId]?.let { hit.copy(similarity = it) } ?: hit }
            .sortedWith(compareByDescending<SimilarImageHit> { it.similarity }.thenByDescending { it.dateTakenMs })
    }

    /** 把库内实体包装成「源图」命中，供 UI 显示「识别依据」。 */
    private fun selfHit(item: MediaEntity): SimilarImageHit = SimilarImageHit(
        mediaId = item.id,
        displayName = item.displayName,
        relativePath = item.relativePath,
        sizeBytes = item.sizeBytes,
        width = item.width,
        height = item.height,
        mimeType = item.mimeType,
        dateTakenMs = item.dateTakenMs,
        dhashDistance = 0,
        phashDistance = 0,
        similarity = 100.0,
    )

    /** 源图指纹 + 可选颜色画像。 */
    private data class SourceSignature(
        val dhash: Long,
        val phash: Long?,
        val profile: ColorProfile? = null,
    )

    private data class RoleHint(
        val tried: Boolean = false,
        val hint: String? = null,
    )

    private companion object {
        /** 每批读取的实体条数：太小会让查询次数暴涨，太大则单批实体占内存。 */
        const val PAGE_SIZE = 2000

        /** MediaStore 的 id 都是正数，用 -1 表示「不排除任何行」。 */
        const val EXCLUDE_NONE = -1L

        /** 颜色精排的条数上限：每条要多解码一次，60 条约 1-2 秒（缩略图命中时更快）。 */
        const val REFINE_LIMIT = 60

        /** 算颜色画像时的解码长边：64 已足够描述配色与线条密度（与 VisualSimilarity 的采样一致）。 */
        const val PROFILE_EDGE = 64

        /** 「找同角色」的候选宽度。 */
        const val CHARACTER_CANDIDATES = 120

        /** 角色名提示最多显示多少字：标题过长会撑坏卡片。 */
        const val ROLE_HINT_MAX_CHARS = 40

        /** 角色识别只问这两家：SauceNAO 二次元索引最全，ascii2d 是它的匿名补充。 */
        val ROLE_HINT_PROVIDERS = listOf(
            ReverseImageProvider.SAUCENAO,
            ReverseImageProvider.ASCII2D,
        )
    }
}
