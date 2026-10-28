package io.github.kurumi33.eropic.core.search

import android.content.Context
import android.net.Uri
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.hash.ImageSignature
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.core.thumb.BitmapDecoder
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
 */
class ImageSearchRepository(
    context: Context,
    private val database: EropicDatabase,
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

    /** 源图指纹；实体里已有就直接用，省一次解码。 */
    private suspend fun signatureOf(item: MediaEntity): SourceSignature? {
        val dhash = item.dhash
        if (dhash != null) return SourceSignature(dhash = dhash, phash = item.phash)
        return computeSignature(MediaUris.of(item.id))
    }

    /**
     * 现场解码算指纹。
     *
     * 不复用 [io.github.kurumi33.eropic.core.index.SignatureGenerator]：那个类会顺带写缩略图缓存（有副作用），
     * 而且需要 ThumbnailCache 依赖；搜索只需要「解码 → 算指纹」这两步，直接用底层两个工具类更干净。
     */
    private suspend fun computeSignature(uri: Uri): SourceSignature? {
        val bitmap = BitmapDecoder.decode(contentResolver, uri, ImageSignature.DECODE_LONG_EDGE) ?: return null
        return try {
            ImageSignature.compute(bitmap)?.let { SourceSignature(dhash = it.dhash, phash = it.phash) }
        } finally {
            // compute 已经把像素读完了；及时回收，避免后面整库扫描时和候选实体抢内存
            bitmap.recycle()
        }
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

        return worstFirst.sortedWith(
            compareByDescending<SimilarImageHit> { it.similarity }.thenByDescending { it.dateTakenMs },
        )
    }

    /** 源图指纹：dHash 一定有，pHash 是可选。 */
    private data class SourceSignature(
        val dhash: Long,
        val phash: Long?,
    )

    private companion object {
        /** 每批读取的实体条数：太小会让查询次数暴涨，太大则单批实体占内存。 */
        const val PAGE_SIZE = 2000

        /** MediaStore 的 id 都是正数，用 -1 表示「不排除任何行」。 */
        const val EXCLUDE_NONE = -1L
    }
}
