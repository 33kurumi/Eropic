package io.github.kurumi33.eropic.core.media

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.database.entity.HashBandEntity
import io.github.kurumi33.eropic.core.model.FolderRow
import io.github.kurumi33.eropic.core.query.MediaFilter
import io.github.kurumi33.eropic.core.query.MediaFilterSql
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 索引读写与首屏查询。 */
class MediaRepository(
    private val context: Context,
    private val database: EropicDatabase,
) {
    private val mediaDao = database.mediaDao()
    private val dataSource = MediaStoreDataSource(context)
    private val scanMutex = Mutex()

    private val pagingConfig = PagingConfig(
        pageSize = 200,
        initialLoadSize = 400,
        prefetchDistance = 600,
        enablePlaceholders = false,
    )

    /** 带排序/筛选/文件夹范围的分页流；sort 改变时由调用方重新订阅（VM 内部 flatMapLatest）。 */
    fun pagingData(
        sort: SortOrder = SortOrder.DATE_DESC,
        favoritesOnly: Boolean = false,
        folderPath: String? = null,
        includeSubfolders: Boolean = true,
    ): Flow<PagingData<MediaEntity>> = Pager(
        config = pagingConfig,
        pagingSourceFactory = {
            val filter = MediaQueryFilter(
                folderPath = folderPath,
                includeSubfolders = includeSubfolders,
                favoritesOnly = favoritesOnly,
            )
            mediaDao.pagingSourceRaw(MediaQuery.paging(filter, sort))
        },
    ).flow

    /** 给大图查看器用的有序 id 列表（与 pagingData 的排序/筛选保持一致）。 */
    suspend fun ids(
        sort: SortOrder = SortOrder.DATE_DESC,
        favoritesOnly: Boolean = false,
        folderPath: String? = null,
        includeSubfolders: Boolean = true,
    ): List<Long> = mediaDao.idsRaw(
        MediaQuery.ids(
            MediaQueryFilter(
                folderPath = folderPath,
                includeSubfolders = includeSubfolders,
                favoritesOnly = favoritesOnly,
            ),
            sort,
        )
    )

    /** M2 组合筛选（大小/分辨率/格式/日期/收藏/目录），分页配置与图库一致。 */
    fun pagingData(filter: MediaFilter, sort: SortOrder = SortOrder.DATE_DESC): Flow<PagingData<MediaEntity>> =
        Pager(
            config = pagingConfig,
            pagingSourceFactory = {
                // MediaFilterSql 不追加 id 兜底，这里补上，保证分页稳定。
                mediaDao.pagingSourceRaw(MediaFilterSql.toQuery("*", filter, sort.sql + ", id DESC"))
            },
        ).flow

    /** 与 filter 分页同一口径的有序 id 列表（给查看器用）。 */
    suspend fun ids(filter: MediaFilter, sort: SortOrder = SortOrder.DATE_DESC): List<Long> =
        mediaDao.idsRaw(MediaFilterSql.toQuery("id", filter, sort.sql + ", id DESC"))

    suspend fun byId(id: Long): MediaEntity? = mediaDao.byId(id)

    /** 切换本地收藏，返回切换后的状态。 */
    suspend fun toggleLocalFavorite(id: Long): Boolean {
        val next = !(mediaDao.byId(id)?.localFavorite ?: false)
        mediaDao.setLocalFavorite(id, next)
        return next
    }

    suspend fun folderRows(): List<FolderRow> = mediaDao.folderRows()

    fun countFlow(): Flow<Long> = mediaDao.countFlow()

    fun totalSizeFlow(): Flow<Long> = mediaDao.totalSizeFlow()

    /** 用 MediaStore 做一次全量对账（幂等，可反复调用）。 */
    suspend fun syncFromMediaStore(onProgress: suspend (scanned: Int, changed: Int) -> Unit): SyncResult =
        scanMutex.withLock {
            val existing = mediaDao.idStates().associateBy { it.id }
            var scanned = 0
            var changed = 0
            val seenIds = ArrayList<Long>(existing.size + 1024)
            dataSource.queryImages { batch ->
                val changedItems = batch.filter { existing[it.id]?.dateModifiedMs != it.dateModifiedMs }
                if (changedItems.isNotEmpty()) {
                    mediaDao.upsertAll(changedItems)
                    changed += changedItems.size
                }
                batch.forEach { seenIds += it.id }
                scanned += batch.size
                onProgress(scanned, changed)
            }
            // scanned == 0 有两种原因：真的没有图片，或权限被降级成"仅选中照片"。
            // 后者若继续把全部旧行标记为缺席，会把已有索引整体清掉，因此这里选择保守跳过。
            if (scanned > 0) {
                // 统一在末尾做一次"缺席标记 + 批量恢复在场"，避免逐批 markPresent 的往返开销。
                mediaDao.markAllAbsent()
                seenIds.chunked(500).forEach { mediaDao.markPresent(it) }
            }
            SyncResult(scanned = scanned, changed = changed, totalIndexed = mediaDao.countAll())
        }

    suspend fun itemsNeedingSignature(limit: Int): List<MediaEntity> = mediaDao.itemsNeedingSignature(limit)

    suspend fun hashedItems(): List<MediaEntity> = mediaDao.hashedItems()

    /** 键集分页遍历（id 递增），供后台任务使用。 */
    suspend fun pagePresentItems(afterId: Long, limit: Int): List<MediaEntity> =
        mediaDao.pagePresentItems(afterId, limit)

    /** 批量收藏/取消收藏（只改 App 内的 localFavorite，不写回系统相册）。 */
    suspend fun setLocalFavorites(ids: Collection<Long>, favorite: Boolean) {
        if (ids.isEmpty()) return
        ids.chunked(500).forEach { mediaDao.setLocalFavoriteBatch(it, favorite) }
    }

    suspend fun updateSignature(id: Long, dhash: Long, phash: Long, blur: Double) = mediaDao.updateSignature(id, dhash, phash, blur)

    suspend fun updateSha(id: Long, sha: String) = mediaDao.updateSha(id, sha)

    suspend fun countNeedingSignature(): Int = mediaDao.countNeedingSignature()

    /** 标记无法解码/不支持的条目，跳过指纹计算（否则下一批查询会再次取到它们）。 */
    suspend fun markSignatureUnsupported(ids: List<Long>) {
        if (ids.isEmpty()) return
        mediaDao.markSignatureUnsupported(ids)
    }

    /** 指纹与倒排桶一起落库；桶只用于未来增量索引，M0 的内存分组不读它。 */
    suspend fun saveSignature(
        id: Long,
        dhash: Long,
        phash: Long,
        blurScore: Double,
        bands: List<HashBandEntity>,
    ) {
        mediaDao.updateSignature(id, dhash, phash, blurScore)
        val hashBandDao = database.hashBandDao()
        hashBandDao.deleteForMedia(id)
        hashBandDao.insertAll(bands)
    }

    suspend fun exactCandidates() = mediaDao.exactCandidates()

    suspend fun count(): Int = mediaDao.countAll()

    suspend fun clear() {
        database.hashBandDao().clear()
        database.duplicateDao().clearMembers()
        database.duplicateDao().clearGroups()
        mediaDao.clear()
    }

    suspend fun stats(): RepositoryStats {
        val count = mediaDao.countAll()
        return RepositoryStats(count)
    }
}

data class SyncResult(
    val scanned: Int,
    val changed: Int,
    val totalIndexed: Int,
)

data class RepositoryStats(val totalCount: Int)
